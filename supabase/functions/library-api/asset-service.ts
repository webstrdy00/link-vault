import { AssetImageError, verifyAssetImage } from "./asset-image.ts";
import { createInternalBatchHandler } from "./classification-worker.ts";
import {
  type EnrichedSnapshotPatch,
  type ItemUpdateSnapshot,
  prepareEnrichedSnapshot,
  type SearchField,
  type TopicField,
} from "./item-preparation.ts";

export const MAX_ASSET_BYTES = 2_000_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ASSET_MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const COMPLETE_CONFLICTS = new Set([
  "VERSION_CONFLICT",
  "RESERVATION_EXPIRED",
  "ASSET_NOT_RESERVED",
  "ASSET_OBJECT_MISSING",
  "ASSET_PATH_MISMATCH",
  "ASSET_TOO_LARGE",
  "ASSET_SIZE_INVALID",
  "ASSET_MIME_MISMATCH",
  "ASSET_DECODE_FAILED",
  "ASSET_PIXEL_LIMIT_EXCEEDED",
  "ASSET_DIMENSIONS_INVALID",
]);

export interface AssetRpcFailure {
  code?: string;
  message?: string;
}

export interface AssetRpcResult {
  data: unknown;
  error: AssetRpcFailure | null;
}

export interface AssetIdentity {
  ownerId: string;
  itemId: string;
  assetId: string;
}

export interface ReplayAssetRequestCall extends AssetIdentity {
  requestId: string;
  method: "POST" | "PATCH" | "DELETE";
  path: string;
  body: Record<string, unknown>;
}

export interface ReserveAssetCall {
  ownerId: string;
  itemId: string;
  requestId: string;
  body: Record<string, unknown>;
}

export interface CompleteAssetCall extends AssetIdentity {
  requestId: string;
  body: Record<string, unknown>;
  verifiedObject: {
    object_path: string;
    size: number;
    mime_type: string;
    width: number;
    height: number;
    present: true;
  };
  preparedIndex: PreparedAssetIndex | null;
}

export interface RejectAssetUploadCall extends AssetIdentity {
  requestId: string;
  body: Record<string, unknown>;
  failureCode: AssetUploadFailureCode;
}

export interface ChangeAssetCall extends AssetIdentity {
  requestId: string;
  body: Record<string, unknown>;
  preparedIndex: PreparedAssetIndex | null;
}

export type LookupAssetCall = AssetIdentity;

export interface DownloadAssetObjectCall {
  objectPath: string;
}

export type AssetObjectDownload =
  | { status: "missing" }
  | { status: "too_large" }
  | { status: "found"; bytes: Uint8Array; mimeType: string | null };

export interface RemoveAssetObjectCall {
  bucketId: "library-images";
  objectPath: string;
}

export interface CleanupAssetCall {
  assetId: string;
  leaseToken: string;
}

export interface FailAssetCleanupCall extends CleanupAssetCall {
  errorCode: string;
}

export interface AssetGateway {
  replayAssetRequest(call: ReplayAssetRequestCall): Promise<AssetRpcResult>;
  reserveAsset(call: ReserveAssetCall): Promise<AssetRpcResult>;
  lookupAsset(call: LookupAssetCall): Promise<AssetRpcResult>;
  downloadAssetObject(
    call: DownloadAssetObjectCall,
  ): Promise<AssetObjectDownload>;
  completeAsset(call: CompleteAssetCall): Promise<AssetRpcResult>;
  rejectAssetUpload(call: RejectAssetUploadCall): Promise<AssetRpcResult>;
  deleteAsset(call: ChangeAssetCall): Promise<AssetRpcResult>;
  updateAssetOcr(call: ChangeAssetCall): Promise<AssetRpcResult>;
  claimAssetCleanupJobs(limit: number): Promise<AssetRpcResult>;
  removeAssetObject(
    call: RemoveAssetObjectCall,
  ): Promise<{ error: AssetRpcFailure | null }>;
  finishAssetCleanup(call: CleanupAssetCall): Promise<AssetRpcResult>;
  failAssetCleanup(call: FailAssetCleanupCall): Promise<AssetRpcResult>;
}

export interface PreparedAssetIndex {
  snapshot_version: number;
  normalized_fields: Record<SearchField, string>;
  alias_concepts: Record<TopicField, string[]>;
  cue_state: "pending" | "missing" | "limited" | "available";
  cue_flags: string[];
}

export type AssetUploadFailureCode =
  | "ASSET_OBJECT_MISSING"
  | "ASSET_PATH_MISMATCH"
  | "ASSET_TOO_LARGE"
  | "ASSET_SIZE_INVALID"
  | "ASSET_MIME_MISMATCH"
  | "ASSET_DECODE_FAILED"
  | "ASSET_PIXEL_LIMIT_EXCEEDED"
  | "ASSET_DIMENSIONS_INVALID";

interface AssetRecord {
  id: string;
  owner_id: string;
  item_id: string;
  state: "reserved" | "active" | "deleting";
  object_path: string;
  reserved_mime_type: string;
  mime_type: string | null;
  actual_bytes: number | null;
}

export interface CompleteAssetUploadInput extends AssetIdentity {
  requestId: string;
  body: {
    expected_version: number;
    ocr_state: "ready" | "failed" | "not_requested";
    ocr_text?: string | null;
    ocr_truncated?: boolean;
  };
  loadSnapshot(): Promise<AssetRpcResult>;
}

export interface AssetContent {
  bytes: Uint8Array;
  mimeType: "image/jpeg" | "image/png" | "image/webp";
}

export interface AssetCleanupBatchResult {
  claimed: number;
  deleted: number;
  completed: number;
  retry_scheduled: number;
  failed: number;
  error_codes: string[];
}

interface ClaimedAssetCleanupJob {
  asset_id: string;
  lease_token: string;
  owner_id: string;
  item_id: string;
  bucket_id: "library-images";
  object_path: string;
}

export class AssetServiceUnavailableError extends Error {
  constructor() {
    super("ASSET_SERVICE_UNAVAILABLE");
    this.name = "AssetServiceUnavailableError";
  }
}

export async function completeAssetUpload(
  gateway: AssetGateway,
  input: CompleteAssetUploadInput,
): Promise<AssetRpcResult> {
  requireIdentity(input);
  if (!isUuid(input.requestId)) {
    throw new AssetServiceUnavailableError();
  }
  const path =
    `/items/${input.itemId.toLowerCase()}/assets/${input.assetId.toLowerCase()}/complete`;
  const replayResult = await safeRpc(() =>
    gateway.replayAssetRequest({
      ownerId: input.ownerId,
      itemId: input.itemId,
      assetId: input.assetId,
      requestId: input.requestId,
      method: "POST",
      path,
      body: input.body,
    })
  );
  if (replayResult.error !== null) {
    return replayResult;
  }
  const replay = parseCompleteReplay(replayResult.data, input.itemId);
  if (replay === null) {
    throw new AssetServiceUnavailableError();
  }
  if (replay.found) {
    return { data: replay.result, error: null };
  }

  const lookupResult = await safeRpc(() =>
    gateway.lookupAsset({
      ownerId: input.ownerId,
      itemId: input.itemId,
      assetId: input.assetId,
    })
  );
  if (lookupResult.error !== null) {
    return lookupResult;
  }
  if (lookupResult.data === null) {
    return await rejectUpload(gateway, input, "ASSET_OBJECT_MISSING");
  }
  if (!isAssetRecord(lookupResult.data)) {
    throw new AssetServiceUnavailableError();
  }
  const asset = lookupResult.data;
  if (!matchesIdentity(asset, input)) {
    throw new AssetServiceUnavailableError();
  }
  if (asset.state !== "reserved") {
    return await rejectUpload(gateway, input, "ASSET_OBJECT_MISSING");
  }
  if (asset.mime_type !== null || asset.actual_bytes !== null) {
    throw new AssetServiceUnavailableError();
  }
  if (asset.object_path !== expectedObjectPath(input)) {
    return await rejectUpload(gateway, input, "ASSET_PATH_MISMATCH");
  }
  if (!ASSET_MIME_TYPES.has(asset.reserved_mime_type)) {
    return await rejectUpload(gateway, input, "ASSET_MIME_MISMATCH");
  }

  let downloaded: AssetObjectDownload;
  try {
    downloaded = await gateway.downloadAssetObject({
      objectPath: asset.object_path,
    });
  } catch {
    throw new AssetServiceUnavailableError();
  }
  if (downloaded.status === "missing") {
    return await rejectUpload(gateway, input, "ASSET_OBJECT_MISSING");
  }
  if (downloaded.status === "too_large") {
    return await rejectUpload(gateway, input, "ASSET_TOO_LARGE");
  }
  if (
    downloaded.mimeType === null ||
    normalizeMime(downloaded.mimeType) !== asset.reserved_mime_type
  ) {
    return await rejectUpload(gateway, input, "ASSET_MIME_MISMATCH");
  }
  if (downloaded.bytes.byteLength === 0) {
    return await rejectUpload(gateway, input, "ASSET_SIZE_INVALID");
  }

  let verified: Awaited<ReturnType<typeof verifyAssetImage>>;
  try {
    verified = await verifyAssetImage(
      downloaded.bytes,
      asset.reserved_mime_type,
    );
  } catch (error) {
    const failureCode = assetImageFailureCode(error);
    if (failureCode === null) {
      throw new AssetServiceUnavailableError();
    }
    return await rejectUpload(
      gateway,
      input,
      failureCode,
    );
  }

  const snapshotResult = await safeRpc(input.loadSnapshot);
  let preparedIndex: PreparedAssetIndex | null = null;
  if (
    snapshotResult.error !== null &&
    !isRpcGuard(snapshotResult.error, "ITEM_NOT_FOUND")
  ) {
    return snapshotResult;
  }
  if (snapshotResult.error === null) {
    if (!isItemSnapshot(snapshotResult.data, input.itemId)) {
      throw new AssetServiceUnavailableError();
    }
    try {
      preparedIndex = prepareAssetIndex(snapshotResult.data, {
        ocr_state: input.body.ocr_state,
        ocr_text: input.body.ocr_state === "ready"
          ? input.body.ocr_text ?? null
          : null,
        ocr_truncated: input.body.ocr_truncated === true,
      });
    } catch {
      throw new AssetServiceUnavailableError();
    }
  }

  const result = await safeRpc(() =>
    gateway.completeAsset({
      ownerId: input.ownerId,
      itemId: input.itemId,
      assetId: input.assetId,
      requestId: input.requestId,
      body: input.body,
      verifiedObject: {
        object_path: asset.object_path,
        size: verified.byte_size,
        mime_type: verified.mime_type,
        width: verified.width,
        height: verified.height,
        present: true,
      },
      preparedIndex,
    })
  );
  return requireCompleteMutationResult(result, input.itemId);
}

export function prepareAssetIndex(
  snapshot: ItemUpdateSnapshot,
  patch: EnrichedSnapshotPatch,
): PreparedAssetIndex {
  const enriched = prepareEnrichedSnapshot(snapshot, patch);
  return {
    snapshot_version: snapshot.version,
    normalized_fields: enriched.normalized_fields,
    alias_concepts: enriched.concept_index,
    cue_state: enriched.cue_state,
    cue_flags: enriched.cue_flags,
  };
}

export async function readActiveAssetContent(
  gateway: AssetGateway,
  identity: AssetIdentity,
): Promise<AssetContent | null> {
  requireIdentity(identity);
  const lookupResult = await safeRpc(() => gateway.lookupAsset(identity));
  if (lookupResult.error !== null) {
    throw new AssetServiceUnavailableError();
  }
  if (lookupResult.data === null) {
    return null;
  }
  if (!isAssetRecord(lookupResult.data)) {
    throw new AssetServiceUnavailableError();
  }
  const asset = lookupResult.data;
  if (!matchesIdentity(asset, identity)) {
    throw new AssetServiceUnavailableError();
  }
  if (asset.state !== "active") {
    return null;
  }
  if (
    asset.object_path !== expectedObjectPath(identity) ||
    asset.mime_type === null || !ASSET_MIME_TYPES.has(asset.mime_type) ||
    asset.mime_type !== asset.reserved_mime_type ||
    !Number.isInteger(asset.actual_bytes) || asset.actual_bytes === null ||
    asset.actual_bytes < 1 || asset.actual_bytes > MAX_ASSET_BYTES
  ) {
    throw new AssetServiceUnavailableError();
  }

  let downloaded: AssetObjectDownload;
  try {
    downloaded = await gateway.downloadAssetObject({
      objectPath: asset.object_path,
    });
  } catch {
    throw new AssetServiceUnavailableError();
  }
  if (downloaded.status === "missing") {
    return null;
  }
  if (
    downloaded.status === "too_large" ||
    downloaded.bytes.byteLength !== asset.actual_bytes ||
    downloaded.mimeType === null ||
    normalizeMime(downloaded.mimeType) !== asset.mime_type
  ) {
    throw new AssetServiceUnavailableError();
  }
  return {
    bytes: downloaded.bytes,
    mimeType: asset.mime_type as AssetContent["mimeType"],
  };
}

export async function readBoundedAssetResponse(
  response: Response,
): Promise<AssetObjectDownload> {
  if (response.status === 400 || response.status === 404) {
    try {
      await response.body?.cancel();
    } catch {
      // The stable missing result takes precedence over transport cleanup.
    }
    return { status: "missing" };
  }
  if (response.status !== 200 || response.body === null) {
    throw new AssetServiceUnavailableError();
  }

  const contentLength = response.headers.get("content-length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength.trim())) {
      throw new AssetServiceUnavailableError();
    }
    if (Number(contentLength) > MAX_ASSET_BYTES) {
      try {
        await response.body.cancel();
      } catch {
        // The bounded result takes precedence over transport cleanup.
      }
      return { status: "too_large" };
    }
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      byteLength += value.byteLength;
      if (byteLength > MAX_ASSET_BYTES) {
        try {
          await reader.cancel();
        } catch {
          // The bounded result takes precedence over transport cleanup.
        }
        return { status: "too_large" };
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof AssetServiceUnavailableError) {
      throw error;
    }
    throw new AssetServiceUnavailableError();
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return {
    status: "found",
    bytes,
    mimeType: normalizeOptionalMime(response.headers.get("content-type")),
  };
}

export function isInternalAssetCleanupPath(pathname: string): boolean {
  return pathname === "/v1/internal/assets-cleanup" ||
    pathname === "/library-api/v1/internal/assets-cleanup" ||
    pathname ===
      "/functions/v1/library-api/v1/internal/assets-cleanup";
}

export function createAssetCleanupHandler(
  gateway: AssetGateway,
  serviceRoleKey: string,
): (request: Request) => Promise<Response> {
  return createInternalBatchHandler(
    serviceRoleKey,
    10,
    (limit) => runAssetCleanupBatch(gateway, limit),
    "ASSET_CLEANUP_WORKER_UNAVAILABLE",
  );
}

export async function runAssetCleanupBatch(
  gateway: AssetGateway,
  limit = 10,
): Promise<AssetCleanupBatchResult> {
  if (!Number.isInteger(limit) || limit < 1 || limit > 10) {
    throw new TypeError("limit must be an integer between 1 and 10");
  }
  const claimResult = await safeRpc(() => gateway.claimAssetCleanupJobs(limit));
  if (claimResult.error !== null || !isCleanupClaimResult(claimResult.data)) {
    throw new Error("ASSET_CLEANUP_CLAIM_FAILED");
  }

  const summary: AssetCleanupBatchResult = {
    claimed: claimResult.data.jobs.length,
    deleted: 0,
    completed: 0,
    retry_scheduled: 0,
    failed: 0,
    error_codes: [],
  };
  const errorCodes = new Set<string>();

  for (const value of claimResult.data.jobs) {
    if (!isClaimedCleanupJob(value) || !hasSafeCleanupPath(value)) {
      errorCodes.add("INVALID_CLEANUP_JOB");
      if (hasCleanupIdentity(value)) {
        await reportCleanupFailure(
          gateway,
          value.asset_id,
          value.lease_token,
          "ASSET_CLEANUP_INVALID_JOB",
          summary,
          errorCodes,
        );
      } else {
        summary.failed++;
      }
      continue;
    }

    let removeResult: { error: AssetRpcFailure | null };
    try {
      removeResult = await gateway.removeAssetObject({
        bucketId: value.bucket_id,
        objectPath: value.object_path,
      });
    } catch {
      removeResult = { error: { message: "unavailable" } };
    }
    if (removeResult.error !== null) {
      errorCodes.add("STORAGE_DELETE_FAILED");
      await reportCleanupFailure(
        gateway,
        value.asset_id,
        value.lease_token,
        "ASSET_STORAGE_DELETE_FAILED",
        summary,
        errorCodes,
      );
      continue;
    }
    summary.deleted++;

    let finishResult: AssetRpcResult;
    try {
      finishResult = await gateway.finishAssetCleanup({
        assetId: value.asset_id,
        leaseToken: value.lease_token,
      });
    } catch {
      errorCodes.add("FINISH_RPC_FAILED");
      await reportCleanupFailure(
        gateway,
        value.asset_id,
        value.lease_token,
        "ASSET_CLEANUP_FINISH_FAILED",
        summary,
        errorCodes,
      );
      continue;
    }
    if (finishResult.error !== null) {
      errorCodes.add("FINISH_RPC_FAILED");
      await reportCleanupFailure(
        gateway,
        value.asset_id,
        value.lease_token,
        "ASSET_CLEANUP_FINISH_FAILED",
        summary,
        errorCodes,
      );
      continue;
    }
    if (isFinishSuccess(finishResult.data, value.asset_id)) {
      summary.completed++;
      continue;
    }
    if (isCleanupConflict(finishResult.data, "LEASE_LOST")) {
      summary.failed++;
      errorCodes.add("LEASE_LOST");
      continue;
    }
    if (isCleanupConflict(finishResult.data, "STORAGE_OBJECT_PRESENT")) {
      errorCodes.add("STORAGE_OBJECT_PRESENT");
      await reportCleanupFailure(
        gateway,
        value.asset_id,
        value.lease_token,
        "ASSET_STORAGE_OBJECT_PRESENT",
        summary,
        errorCodes,
      );
      continue;
    }
    errorCodes.add("INVALID_FINISH_RESULT");
    await reportCleanupFailure(
      gateway,
      value.asset_id,
      value.lease_token,
      "ASSET_CLEANUP_INVALID_FINISH",
      summary,
      errorCodes,
    );
  }

  summary.error_codes = [...errorCodes].sort();
  return summary;
}

async function rejectUpload(
  gateway: AssetGateway,
  input: CompleteAssetUploadInput,
  failureCode: AssetUploadFailureCode,
): Promise<AssetRpcResult> {
  const result = await safeRpc(() =>
    gateway.rejectAssetUpload({
      ownerId: input.ownerId,
      itemId: input.itemId,
      assetId: input.assetId,
      requestId: input.requestId,
      body: input.body,
      failureCode,
    })
  );
  return requireCompleteMutationResult(result, input.itemId);
}

async function reportCleanupFailure(
  gateway: AssetGateway,
  assetId: string,
  leaseToken: string,
  failureCode: string,
  summary: AssetCleanupBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  let result: AssetRpcResult;
  try {
    result = await gateway.failAssetCleanup({
      assetId,
      leaseToken,
      errorCode: failureCode,
    });
  } catch {
    summary.failed++;
    errorCodes.add("FAIL_RPC_FAILED");
    return;
  }
  if (result.error !== null) {
    summary.failed++;
    errorCodes.add("FAIL_RPC_FAILED");
    return;
  }
  if (isCleanupRetry(result.data, assetId)) {
    summary.retry_scheduled++;
    return;
  }
  if (isFinishSuccess(result.data, assetId)) {
    summary.completed++;
    return;
  }
  if (isCleanupConflict(result.data, "LEASE_LOST")) {
    summary.failed++;
    errorCodes.add("LEASE_LOST");
    return;
  }
  summary.failed++;
  errorCodes.add("INVALID_FAIL_RESULT");
}

function parseCompleteReplay(value: unknown, itemId: string):
  | { found: false }
  | { found: true; result: Record<string, unknown> }
  | null {
  if (
    isObject(value) && Object.keys(value).length === 1 &&
    value.found === false
  ) {
    return { found: false };
  }
  if (!isObject(value) || value.found !== true) {
    return null;
  }
  if (
    Object.keys(value).length === 3 && value.http_status === 200 &&
    isObject(value.item) && typeof value.item.id === "string" &&
    isUuid(value.item.id) &&
    value.item.id.toLowerCase() === itemId.toLowerCase()
  ) {
    return {
      found: true,
      result: { http_status: 200, item: value.item },
    };
  }
  if (
    Object.keys(value).length === 3 && value.http_status === 409 &&
    typeof value.error_code === "string" &&
    COMPLETE_CONFLICTS.has(value.error_code)
  ) {
    return {
      found: true,
      result: { http_status: 409, error_code: value.error_code },
    };
  }
  return null;
}

function requireCompleteMutationResult(
  result: AssetRpcResult,
  itemId: string,
): AssetRpcResult {
  if (result.error !== null) {
    return result;
  }
  const value = result.data;
  if (
    isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 200 && isObject(value.item) &&
    typeof value.item.id === "string" && isUuid(value.item.id) &&
    value.item.id.toLowerCase() === itemId.toLowerCase()
  ) {
    return result;
  }
  if (
    isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 409 && typeof value.error_code === "string" &&
    COMPLETE_CONFLICTS.has(value.error_code)
  ) {
    return result;
  }
  throw new AssetServiceUnavailableError();
}

function isAssetRecord(value: unknown): value is AssetRecord {
  if (!isObject(value)) {
    return false;
  }
  const keys = Object.keys(value);
  return keys.length === 8 &&
    [
      "id",
      "owner_id",
      "item_id",
      "state",
      "object_path",
      "reserved_mime_type",
      "mime_type",
      "actual_bytes",
    ].every((key) => Object.hasOwn(value, key)) &&
    typeof value.id === "string" && isUuid(value.id) &&
    typeof value.owner_id === "string" && isUuid(value.owner_id) &&
    typeof value.item_id === "string" && isUuid(value.item_id) &&
    ["reserved", "active", "deleting"].includes(value.state as string) &&
    typeof value.object_path === "string" &&
    typeof value.reserved_mime_type === "string" &&
    (value.mime_type === null || typeof value.mime_type === "string") &&
    (value.actual_bytes === null ||
      (Number.isInteger(value.actual_bytes) &&
        (value.actual_bytes as number) >= 0));
}

function matchesIdentity(
  asset: AssetRecord,
  identity: AssetIdentity,
): boolean {
  return asset.id.toLowerCase() === identity.assetId.toLowerCase() &&
    asset.owner_id.toLowerCase() === identity.ownerId.toLowerCase() &&
    asset.item_id.toLowerCase() === identity.itemId.toLowerCase();
}

function expectedObjectPath(identity: AssetIdentity): string {
  return `${identity.ownerId.toLowerCase()}/${identity.itemId.toLowerCase()}/${identity.assetId.toLowerCase()}`;
}

function requireIdentity(identity: AssetIdentity): void {
  if (
    !isUuid(identity.ownerId) || !isUuid(identity.itemId) ||
    !isUuid(identity.assetId)
  ) {
    throw new AssetServiceUnavailableError();
  }
}

function assetImageFailureCode(
  error: unknown,
): AssetUploadFailureCode | null {
  if (!(error instanceof AssetImageError)) {
    return null;
  }
  switch (error.code) {
    case "ASSET_TOO_LARGE":
      return "ASSET_TOO_LARGE";
    case "ASSET_MIME_MISMATCH":
      return "ASSET_MIME_MISMATCH";
    case "IMAGE_DIMENSIONS_EXCEEDED":
      return "ASSET_PIXEL_LIMIT_EXCEEDED";
    case "INVALID_IMAGE":
      return "ASSET_DECODE_FAILED";
  }
}

function isItemSnapshot(
  value: unknown,
  itemId: string,
): value is ItemUpdateSnapshot & { id: string } {
  if (
    !isObject(value) || !isPositiveInteger(value.version) ||
    typeof value.id !== "string" || !isUuid(value.id) ||
    value.id.toLowerCase() !== itemId.toLowerCase() ||
    typeof value.url !== "string" ||
    !isNullableString(value.user_title) ||
    !isNullableString(value.fetched_title) ||
    !isNullableString(value.shared_text) ||
    !isNullableString(value.description) ||
    !isNullableString(value.body_text) ||
    !isNullableString(value.note) || !Array.isArray(value.category_refs) ||
    ![
      "queued",
      "running",
      "ready",
      "partial",
      "unsupported",
      "failed",
    ].includes(value.metadata_state as string) ||
    !["not_requested", "queued", "running", "ready", "failed"].includes(
      value.ocr_state as string,
    ) ||
    !isObject(value.extraction_meta) ||
    !isSnapshotActiveAsset(value.active_asset)
  ) {
    return false;
  }
  return value.category_refs.every((category) =>
    isObject(category) && typeof category.id === "string" &&
    isUuid(category.id) && typeof category.name === "string"
  );
}

function isSnapshotActiveAsset(value: unknown): boolean {
  return value === null ||
    (isObject(value) &&
      (!Object.hasOwn(value, "ocr_text") || isNullableString(value.ocr_text)) &&
      (!Object.hasOwn(value, "ocr_truncated") ||
        typeof value.ocr_truncated === "boolean"));
}

function isCleanupClaimResult(
  value: unknown,
): value is { http_status: 200; jobs: unknown[] } {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 200 && Array.isArray(value.jobs);
}

function isClaimedCleanupJob(
  value: unknown,
): value is ClaimedAssetCleanupJob {
  return isObject(value) && typeof value.asset_id === "string" &&
    isUuid(value.asset_id) && typeof value.lease_token === "string" &&
    isUuid(value.lease_token) && typeof value.owner_id === "string" &&
    isUuid(value.owner_id) && typeof value.item_id === "string" &&
    isUuid(value.item_id) && value.bucket_id === "library-images" &&
    typeof value.object_path === "string";
}

function hasCleanupIdentity(
  value: unknown,
): value is { asset_id: string; lease_token: string } {
  return isObject(value) && typeof value.asset_id === "string" &&
    isUuid(value.asset_id) && typeof value.lease_token === "string" &&
    isUuid(value.lease_token);
}

function hasSafeCleanupPath(job: ClaimedAssetCleanupJob): boolean {
  return job.object_path ===
    `${job.owner_id.toLowerCase()}/${job.item_id.toLowerCase()}/${job.asset_id.toLowerCase()}`;
}

function isFinishSuccess(value: unknown, assetId: string): boolean {
  return isObject(value) && Object.keys(value).length === 5 &&
    value.http_status === 200 && value.state === "complete" &&
    typeof value.asset_id === "string" && isUuid(value.asset_id) &&
    value.asset_id.toLowerCase() === assetId.toLowerCase() &&
    Number.isInteger(value.released_reserved_bytes) &&
    (value.released_reserved_bytes as number) >= 0 &&
    Number.isInteger(value.released_used_bytes) &&
    (value.released_used_bytes as number) >= 0;
}

function isCleanupRetry(value: unknown, assetId: string): boolean {
  return isObject(value) && Object.keys(value).length === 4 &&
    value.http_status === 202 && value.state === "retry" &&
    typeof value.asset_id === "string" && isUuid(value.asset_id) &&
    value.asset_id.toLowerCase() === assetId.toLowerCase() &&
    typeof value.next_run_at === "string" &&
    !Number.isNaN(Date.parse(value.next_run_at));
}

function isCleanupConflict(
  value: unknown,
  errorCode: "LEASE_LOST" | "STORAGE_OBJECT_PRESENT",
): boolean {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 409 && value.error_code === errorCode;
}

async function safeRpc(
  operation: () => Promise<AssetRpcResult>,
): Promise<AssetRpcResult> {
  try {
    return await operation();
  } catch {
    throw new AssetServiceUnavailableError();
  }
}

function normalizeOptionalMime(value: string | null): string | null {
  return value === null ? null : normalizeMime(value);
}

function normalizeMime(value: string): string {
  return value.split(";", 1)[0].trim().toLowerCase();
}

function isRpcGuard(error: AssetRpcFailure, message: string): boolean {
  return error.code === "P0001" && error.message === message;
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 &&
    (value as number) <= 2_147_483_647;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
