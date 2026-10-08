import {
  type AssetCleanupBatchResult,
  type AssetGateway,
  runAssetCleanupBatch,
} from "./asset-service.ts";
import { createInternalBatchHandler } from "./classification-worker.ts";

const MAX_BATCH_LIMIT = 10;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface DeletionRpcFailure {
  code?: string;
  message?: string;
}

export interface DeletionRpcResult {
  data: unknown;
  error: DeletionRpcFailure | null;
}

export interface AccountDeletionJobCall {
  ownerId: string;
  leaseToken: string;
}

export interface FailAccountDeletionCall extends AccountDeletionJobCall {
  errorCode: string;
}

export interface AccountStorageObject {
  objectId: string;
  bucketId: string;
  objectPath: string;
}

export interface DeletionGateway {
  claimAccountDeletionJobs(limit: number): Promise<DeletionRpcResult>;
  prepareAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult>;
  removeAccountStorageObject(
    object: AccountStorageObject,
  ): Promise<{ status: "deleted" | "already_absent" | "failed" }>;
  purgeAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult>;
  deleteAuthUser(
    ownerId: string,
  ): Promise<{ status: "deleted" | "already_absent" | "failed" }>;
  finalizeAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult>;
  failAccountDeletion(
    call: FailAccountDeletionCall,
  ): Promise<DeletionRpcResult>;
}

export interface MaintenanceGateway extends DeletionGateway, AssetGateway {
  purgeDeletedItems(limit: number): Promise<DeletionRpcResult>;
  runRetention(): Promise<DeletionRpcResult>;
}

interface ClaimedAccountDeletionJob {
  owner_id: string;
  request_id: string;
  attempts: number;
  lease_token: string;
  lease_until: string;
  business_purged: boolean;
}

interface PreparedStorageObject {
  object_id: string;
  bucket_id: string;
  object_path: string;
}

interface PreparedAccountDeletion {
  http_status: 200;
  state: "storage_cleanup";
  objects: PreparedStorageObject[];
  object_count: number;
  ownership_conflict_count: number;
  asset_count: number;
}

export interface DeletionBatchResult {
  claimed: number;
  storage_objects_deleted: number;
  business_purged: number;
  auth_users_deleted: number;
  completed: number;
  retry_scheduled: number;
  failed: number;
  lease_lost: number;
  error_codes: string[];
}

export interface MaintenanceBatchResult {
  account_deletion: DeletionBatchResult;
  asset_cleanup: AssetCleanupBatchResult;
  item_purge: { purged_items: number };
  retention: {
    deleted_api_requests: number;
    deleted_rate_buckets: number;
    deleted_challenges: number;
    deleted_item_tombstones: number;
    deleted_cancelled_jobs: number;
    deleted_account_jobs: number;
    deleted_ledger_events: number;
  };
}

export async function runDeletionBatch(
  gateway: DeletionGateway,
  limit = MAX_BATCH_LIMIT,
): Promise<DeletionBatchResult> {
  requireLimit(limit);
  const claimResult = await safeRpc(() =>
    gateway.claimAccountDeletionJobs(limit)
  );
  if (claimResult.error !== null || !isClaimResult(claimResult.data)) {
    throw new Error("ACCOUNT_DELETION_CLAIM_FAILED");
  }

  const summary: DeletionBatchResult = {
    claimed: claimResult.data.jobs.length,
    storage_objects_deleted: 0,
    business_purged: 0,
    auth_users_deleted: 0,
    completed: 0,
    retry_scheduled: 0,
    failed: 0,
    lease_lost: 0,
    error_codes: [],
  };
  const errorCodes = new Set<string>();

  for (const value of claimResult.data.jobs) {
    if (!isClaimedJob(value)) {
      errorCodes.add("INVALID_ACCOUNT_DELETION_JOB");
      if (hasJobIdentity(value)) {
        await reportFailure(
          gateway,
          value,
          "ACCOUNT_DELETION_INVALID_JOB",
          summary,
          errorCodes,
        );
      } else {
        summary.failed++;
      }
      continue;
    }

    try {
      await processJob(gateway, value, summary, errorCodes);
    } catch {
      await reportFailure(
        gateway,
        value,
        "ACCOUNT_DELETION_INTERNAL_ERROR",
        summary,
        errorCodes,
      );
    }
  }

  summary.error_codes = [...errorCodes].sort();
  return summary;
}

async function processJob(
  gateway: DeletionGateway,
  job: ClaimedAccountDeletionJob,
  summary: DeletionBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  const call = { ownerId: job.owner_id, leaseToken: job.lease_token };
  const prepared = await prepareForDeletion(
    gateway,
    call,
    job,
    summary,
    errorCodes,
  );
  if (prepared === null) return;
  if (prepared.ownership_conflict_count !== 0) {
    await reportFailure(
      gateway,
      job,
      "ACCOUNT_STORAGE_OWNERSHIP_CONFLICT",
      summary,
      errorCodes,
    );
    return;
  }

  for (const object of prepared.objects) {
    let result: { status: "deleted" | "already_absent" | "failed" };
    try {
      result = await gateway.removeAccountStorageObject({
        objectId: object.object_id,
        bucketId: object.bucket_id,
        objectPath: object.object_path,
      });
    } catch {
      result = { status: "failed" };
    }
    if (result.status === "failed") {
      await reportFailure(
        gateway,
        job,
        "ACCOUNT_STORAGE_DELETE_FAILED",
        summary,
        errorCodes,
      );
      return;
    }
    if (result.status === "deleted") summary.storage_objects_deleted++;
  }

  if (prepared.objects.length !== 0) {
    const verified = await prepareForDeletion(
      gateway,
      call,
      job,
      summary,
      errorCodes,
    );
    if (verified === null) return;
    if (verified.ownership_conflict_count !== 0) {
      await reportFailure(
        gateway,
        job,
        "ACCOUNT_STORAGE_OWNERSHIP_CONFLICT",
        summary,
        errorCodes,
      );
      return;
    }
    if (verified.objects.length !== 0 || verified.object_count !== 0) {
      await reportFailure(
        gateway,
        job,
        "ACCOUNT_STORAGE_OBJECTS_REMAIN",
        summary,
        errorCodes,
      );
      return;
    }
  }

  if (!job.business_purged) {
    const purgeResult = await safeRpc(() => gateway.purgeAccountDeletion(call));
    const purgeCode = rpcSentinel(purgeResult);
    if (purgeCode === "LEASE_LOST") {
      recordLeaseLost(summary, errorCodes);
      return;
    }
    if (
      purgeCode === "STORAGE_OBJECTS_PRESENT" ||
      purgeCode === "STORAGE_OWNERSHIP_CONFLICT"
    ) {
      await reportFailure(
        gateway,
        job,
        purgeCode === "STORAGE_OBJECTS_PRESENT"
          ? "ACCOUNT_STORAGE_OBJECTS_REMAIN"
          : "ACCOUNT_STORAGE_OWNERSHIP_CONFLICT",
        summary,
        errorCodes,
      );
      return;
    }
    if (
      purgeResult.error !== null ||
      !isPurgeResult(purgeResult.data)
    ) {
      await reportFailure(
        gateway,
        job,
        "ACCOUNT_BUSINESS_PURGE_FAILED",
        summary,
        errorCodes,
      );
      return;
    }
    summary.business_purged++;
  }

  let authResult: { status: "deleted" | "already_absent" | "failed" };
  try {
    authResult = await gateway.deleteAuthUser(job.owner_id);
  } catch {
    authResult = { status: "failed" };
  }
  if (authResult.status === "failed") {
    await reportFailure(
      gateway,
      job,
      "ACCOUNT_AUTH_DELETE_FAILED",
      summary,
      errorCodes,
    );
    return;
  }
  if (authResult.status === "deleted") summary.auth_users_deleted++;

  const finalizeResult = await safeRpc(() =>
    gateway.finalizeAccountDeletion(call)
  );
  if (rpcSentinel(finalizeResult) === "LEASE_LOST") {
    recordLeaseLost(summary, errorCodes);
    return;
  }
  if (
    finalizeResult.error !== null ||
    !isFinalizeResult(finalizeResult.data)
  ) {
    await reportFailure(
      gateway,
      job,
      "ACCOUNT_FINALIZE_FAILED",
      summary,
      errorCodes,
    );
    return;
  }
  summary.completed++;
}

async function prepareForDeletion(
  gateway: DeletionGateway,
  call: AccountDeletionJobCall,
  job: ClaimedAccountDeletionJob,
  summary: DeletionBatchResult,
  errorCodes: Set<string>,
): Promise<PreparedAccountDeletion | null> {
  const result = await safeRpc(() => gateway.prepareAccountDeletion(call));
  if (rpcSentinel(result) === "LEASE_LOST") {
    recordLeaseLost(summary, errorCodes);
    return null;
  }
  if (result.error !== null || !isPrepareResult(result.data)) {
    await reportFailure(
      gateway,
      job,
      "ACCOUNT_PREPARE_FAILED",
      summary,
      errorCodes,
    );
    return null;
  }
  return result.data;
}

async function reportFailure(
  gateway: DeletionGateway,
  job: Pick<ClaimedAccountDeletionJob, "owner_id" | "lease_token">,
  errorCode: string,
  summary: DeletionBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  errorCodes.add(errorCode);
  const result = await safeRpc(() =>
    gateway.failAccountDeletion({
      ownerId: job.owner_id,
      leaseToken: job.lease_token,
      errorCode,
    })
  );
  if (rpcSentinel(result) === "LEASE_LOST") {
    recordLeaseLost(summary, errorCodes);
    return;
  }
  if (result.error === null && isFinalizeResult(result.data)) {
    summary.completed++;
    return;
  }
  if (result.error !== null || !isRetryResult(result.data, errorCode)) {
    summary.failed++;
    errorCodes.add("ACCOUNT_FAILURE_REPORT_FAILED");
    return;
  }
  summary.retry_scheduled++;
}

export async function runMaintenanceBatch(
  gateway: MaintenanceGateway,
  limit = MAX_BATCH_LIMIT,
): Promise<MaintenanceBatchResult> {
  requireLimit(limit);
  const accountDeletion = await runDeletionBatch(gateway, limit);
  const assetCleanup = await runAssetCleanupBatch(gateway, limit);

  const itemPurgeResult = await safeRpc(() => gateway.purgeDeletedItems(limit));
  if (
    itemPurgeResult.error !== null || !isItemPurgeResult(itemPurgeResult.data)
  ) {
    throw new Error("ITEM_PURGE_FAILED");
  }

  const retentionResult = await safeRpc(() => gateway.runRetention());
  if (
    retentionResult.error !== null || !isRetentionResult(retentionResult.data)
  ) {
    throw new Error("RETENTION_FAILED");
  }

  return {
    account_deletion: accountDeletion,
    asset_cleanup: assetCleanup,
    item_purge: itemPurgeResult.data,
    retention: {
      deleted_api_requests: retentionResult.data.deleted_api_requests,
      deleted_rate_buckets: retentionResult.data.deleted_rate_buckets,
      deleted_challenges: retentionResult.data.deleted_challenges,
      deleted_item_tombstones: retentionResult.data.deleted_item_tombstones,
      deleted_cancelled_jobs: retentionResult.data.deleted_cancelled_jobs,
      deleted_account_jobs: retentionResult.data.deleted_account_jobs,
      deleted_ledger_events: retentionResult.data.deleted_ledger_events,
    },
  };
}

export function createMaintenanceHandler(
  gateway: MaintenanceGateway,
  serviceRoleKey: string,
): (request: Request) => Promise<Response> {
  return createInternalBatchHandler(
    serviceRoleKey,
    MAX_BATCH_LIMIT,
    (limit) => runMaintenanceBatch(gateway, limit),
    "MAINTENANCE_WORKER_UNAVAILABLE",
  );
}

export function isInternalMaintenancePath(pathname: string): boolean {
  return pathname === "/v1/internal/maintenance" ||
    pathname === "/library-api/v1/internal/maintenance" ||
    pathname === "/functions/v1/library-api/v1/internal/maintenance";
}

function requireLimit(limit: number): void {
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_BATCH_LIMIT) {
    throw new TypeError("limit must be an integer between 1 and 10");
  }
}

async function safeRpc(
  operation: () => Promise<DeletionRpcResult>,
): Promise<DeletionRpcResult> {
  try {
    return await operation();
  } catch {
    return { data: null, error: { code: "RPC_UNAVAILABLE" } };
  }
}

function isClaimResult(
  value: unknown,
): value is { http_status: 200; jobs: unknown[] } {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 200 && Array.isArray(value.jobs);
}

function isClaimedJob(value: unknown): value is ClaimedAccountDeletionJob {
  return isObject(value) && Object.keys(value).length === 6 &&
    typeof value.owner_id === "string" && isUuid(value.owner_id) &&
    typeof value.request_id === "string" && isUuid(value.request_id) &&
    Number.isInteger(value.attempts) && (value.attempts as number) >= 0 &&
    typeof value.lease_token === "string" && isUuid(value.lease_token) &&
    typeof value.lease_until === "string" &&
    !Number.isNaN(Date.parse(value.lease_until)) &&
    typeof value.business_purged === "boolean";
}

function hasJobIdentity(
  value: unknown,
): value is Pick<ClaimedAccountDeletionJob, "owner_id" | "lease_token"> {
  return isObject(value) && typeof value.owner_id === "string" &&
    isUuid(value.owner_id) && typeof value.lease_token === "string" &&
    isUuid(value.lease_token);
}

function isPrepareResult(value: unknown): value is PreparedAccountDeletion {
  return isObject(value) && Object.keys(value).length === 6 &&
    value.http_status === 200 && value.state === "storage_cleanup" &&
    Array.isArray(value.objects) && value.objects.every(isPreparedObject) &&
    Number.isInteger(value.object_count) &&
    (value.object_count as number) >= 0 &&
    value.object_count === value.objects.length &&
    Number.isInteger(value.ownership_conflict_count) &&
    (value.ownership_conflict_count as number) >= 0 &&
    Number.isInteger(value.asset_count) && (value.asset_count as number) >= 0;
}

function isPreparedObject(value: unknown): value is PreparedStorageObject {
  return isObject(value) && Object.keys(value).length === 3 &&
    typeof value.object_id === "string" && isUuid(value.object_id) &&
    typeof value.bucket_id === "string" && value.bucket_id.length > 0 &&
    typeof value.object_path === "string" && value.object_path.length > 0;
}

function isPurgeResult(value: unknown): boolean {
  return isObject(value) && Object.keys(value).length === 4 &&
    value.http_status === 200 && value.state === "ready_for_auth_delete" &&
    isNonnegativeInteger(value.released_reserved_bytes) &&
    isNonnegativeInteger(value.released_used_bytes);
}

function isFinalizeResult(value: unknown): boolean {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 200 && value.state === "complete";
}

function isRetryResult(value: unknown, errorCode: string): boolean {
  return isObject(value) && Object.keys(value).length === 5 &&
    value.http_status === 202 && value.state === "retry" &&
    typeof value.next_run_at === "string" &&
    !Number.isNaN(Date.parse(value.next_run_at)) &&
    Number.isInteger(value.attempts) && (value.attempts as number) >= 1 &&
    value.error_code === errorCode;
}

function rpcSentinel(result: DeletionRpcResult): string | null {
  if (
    result.error?.code === "P0001" && typeof result.error.message === "string"
  ) {
    return result.error.message;
  }
  return isObject(result.data) && result.data.http_status === 409 &&
      typeof result.data.error_code === "string"
    ? result.data.error_code
    : null;
}

function recordLeaseLost(
  summary: DeletionBatchResult,
  errorCodes: Set<string>,
): void {
  summary.lease_lost++;
  errorCodes.add("LEASE_LOST");
}

function isItemPurgeResult(
  value: unknown,
): value is { purged_items: number } {
  return isObject(value) && Object.keys(value).length === 1 &&
    isNonnegativeInteger(value.purged_items);
}

function isRetentionResult(value: unknown): value is {
  http_status: 200;
  deleted_api_requests: number;
  deleted_rate_buckets: number;
  deleted_challenges: number;
  deleted_item_tombstones: number;
  deleted_cancelled_jobs: number;
  deleted_account_jobs: number;
  deleted_ledger_events: number;
} {
  if (
    !isObject(value) || Object.keys(value).length !== 8 ||
    value.http_status !== 200
  ) {
    return false;
  }
  return [
    value.deleted_api_requests,
    value.deleted_rate_buckets,
    value.deleted_challenges,
    value.deleted_item_tombstones,
    value.deleted_cancelled_jobs,
    value.deleted_account_jobs,
    value.deleted_ledger_events,
  ].every(isNonnegativeInteger);
}

function isNonnegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value);
}
