import { createInternalBatchHandler } from "./classification-worker.ts";
import {
  fetchMetadata,
  type MetadataErrorCode,
  type MetadataExtractionMeta,
  MetadataFetchFailure,
  type MetadataResult,
} from "./metadata-engine.ts";
import {
  type ItemUpdateSnapshot,
  prepareEnrichedSnapshot,
  type SearchField,
  type TopicField,
} from "./item-preparation.ts";

const MAX_BATCH_LIMIT = 10;
const MAX_COMMIT_RETRIES = 2;
const MAX_POSTGRES_INTEGER = 2_147_483_647;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type MetadataFailureCode =
  | MetadataErrorCode
  | "RETRY_AFTER_EXCEEDED";
const METADATA_ERROR_CODES = new Set<MetadataFailureCode>([
  "METADATA_TIMEOUT",
  "NETWORK_ERROR",
  "RATE_LIMITED",
  "RETRY_AFTER_EXCEEDED",
  "ACCESS_DENIED",
  "INVALID_CONTENT",
  "RESPONSE_TOO_LARGE",
  "METADATA_BUDGET_EXHAUSTED",
  "INVALID_RESULT",
  "INTERNAL_ERROR",
]);

export interface MetadataWorkerRpcFailure {
  code?: string;
  message?: string;
}

export interface MetadataWorkerRpcResult {
  data: unknown;
  error: MetadataWorkerRpcFailure | null;
}

export interface MetadataJobSnapshot extends ItemUpdateSnapshot {
  id: string;
  text_revision: number;
  metadata_state: "running";
}

export interface ClaimedMetadataJob {
  job_id: string;
  lease_token: string;
  owner_id: string;
  target_revision: number;
  expected_version: number;
  budget_reserved: true;
  item: MetadataJobSnapshot;
}

export interface MetadataCompletionResult {
  fetched_title: string | null;
  description: string | null;
  body_text: string | null;
  metadata_state: "ready" | "partial";
  extraction_meta: MetadataExtractionMeta;
}

export interface PreparedMetadataCompletion {
  snapshot_version: number;
  normalized_fields: Record<SearchField, string>;
  alias_concepts: Record<TopicField, string[]>;
  cue_state: "pending" | "missing" | "limited" | "available";
  cue_flags: string[];
}

export interface CompleteMetadataCall {
  jobId: string;
  leaseToken: string;
  expectedVersion: number;
  result: MetadataCompletionResult;
  prepared: PreparedMetadataCompletion;
}

export interface FailMetadataCall {
  jobId: string;
  leaseToken: string;
  errorCode: MetadataFailureCode;
  retryAfterSeconds: number | null;
}

export interface MetadataGateway {
  claimMetadataJobs(limit: number): Promise<MetadataWorkerRpcResult>;
  completeMetadataJob(
    call: CompleteMetadataCall,
  ): Promise<MetadataWorkerRpcResult>;
  failMetadataJob(call: FailMetadataCall): Promise<MetadataWorkerRpcResult>;
}

export type MetadataFetcher = (url: string) => Promise<MetadataResult>;

export interface MetadataBatchResult {
  claimed: number;
  succeeded: number;
  discarded: number;
  retry_scheduled: number;
  failed: number;
  version_conflicts: number;
  error_codes: string[];
}

export async function runMetadataBatch(
  gateway: MetadataGateway,
  limit: number,
  fetcher: MetadataFetcher = fetchMetadata,
): Promise<MetadataBatchResult> {
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_BATCH_LIMIT) {
    throw new TypeError("limit must be an integer between 1 and 10");
  }

  const claimResult = await gateway.claimMetadataJobs(limit);
  if (claimResult.error !== null || !isClaimResult(claimResult.data)) {
    throw new Error("METADATA_CLAIM_FAILED");
  }

  const summary: MetadataBatchResult = {
    claimed: claimResult.data.jobs.length,
    succeeded: 0,
    discarded: 0,
    retry_scheduled: 0,
    failed: 0,
    version_conflicts: 0,
    error_codes: [],
  };
  const errorCodes = new Set<string>();

  await Promise.all(
    claimResult.data.jobs.map((job) =>
      processJob(gateway, job, fetcher, summary, errorCodes)
    ),
  );

  summary.error_codes = [...errorCodes].sort();
  return summary;
}

async function processJob(
  gateway: MetadataGateway,
  job: ClaimedMetadataJob,
  fetcher: MetadataFetcher,
  summary: MetadataBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  let fetched: MetadataResult;
  try {
    fetched = await fetcher(job.item.url);
  } catch (error) {
    if (error instanceof MetadataFetchFailure) {
      await reportFailure(
        gateway,
        job,
        error.code,
        validRetryAfter(error.retryAfterSeconds),
        summary,
        errorCodes,
      );
    } else {
      await reportFailure(
        gateway,
        job,
        "INTERNAL_ERROR",
        null,
        summary,
        errorCodes,
      );
    }
    return;
  }

  const failureCode = resultFailureCode(fetched);
  if (failureCode !== null) {
    await reportFailure(
      gateway,
      job,
      failureCode,
      null,
      summary,
      errorCodes,
    );
    return;
  }
  if (
    fetched.metadata_state !== "ready" &&
    fetched.metadata_state !== "partial"
  ) {
    await reportFailure(
      gateway,
      job,
      "INVALID_RESULT",
      null,
      summary,
      errorCodes,
    );
    return;
  }

  const result: MetadataCompletionResult = {
    fetched_title: fetched.fetched_title,
    description: fetched.description,
    body_text: fetched.body_text,
    metadata_state: fetched.metadata_state,
    extraction_meta: {
      adapter_version: fetched.extraction_meta.adapter_version,
      final_url: fetched.extraction_meta.final_url,
      title_truncated: fetched.extraction_meta.title_truncated,
      description_truncated: fetched.extraction_meta.description_truncated,
      body_truncated: fetched.extraction_meta.body_truncated,
      last_checked_at: fetched.extraction_meta.last_checked_at,
      error_code: fetched.extraction_meta.error_code,
    },
  };
  let snapshot = job.item;

  for (let retry = 0; retry <= MAX_COMMIT_RETRIES; retry++) {
    let prepared: PreparedMetadataCompletion;
    try {
      const enriched = prepareEnrichedSnapshot(snapshot, {
        fetched_title: result.fetched_title,
        description: result.description,
        body_text: result.body_text,
        metadata_state: result.metadata_state,
        extraction_meta: { ...result.extraction_meta },
      });
      prepared = {
        snapshot_version: snapshot.version,
        normalized_fields: enriched.normalized_fields,
        alias_concepts: enriched.concept_index,
        cue_state: enriched.cue_state,
        cue_flags: enriched.cue_flags,
      };
    } catch {
      await reportFailure(
        gateway,
        job,
        "INVALID_RESULT",
        null,
        summary,
        errorCodes,
      );
      return;
    }

    let completeResult: MetadataWorkerRpcResult;
    try {
      completeResult = await gateway.completeMetadataJob({
        jobId: job.job_id,
        leaseToken: job.lease_token,
        expectedVersion: snapshot.version,
        result,
        prepared,
      });
    } catch {
      await reportFailure(
        gateway,
        job,
        "INTERNAL_ERROR",
        null,
        summary,
        errorCodes,
      );
      return;
    }

    if (completeResult.error !== null) {
      await reportFailure(
        gateway,
        job,
        isInvalidCompletionFailure(completeResult.error)
          ? "INVALID_RESULT"
          : "INTERNAL_ERROR",
        null,
        summary,
        errorCodes,
      );
      return;
    }
    if (!isCompleteResult(completeResult.data)) {
      await reportFailure(
        gateway,
        job,
        "INTERNAL_ERROR",
        null,
        summary,
        errorCodes,
      );
      return;
    }

    if (completeResult.data.state === "succeeded") {
      summary.succeeded++;
      return;
    }
    if (completeResult.data.state === "discarded_stale") {
      summary.discarded++;
      return;
    }

    summary.version_conflicts++;
    if (retry === MAX_COMMIT_RETRIES) {
      await reportFailure(
        gateway,
        job,
        "INTERNAL_ERROR",
        null,
        summary,
        errorCodes,
      );
      return;
    }
    snapshot = completeResult.data.item;
  }
}

async function reportFailure(
  gateway: MetadataGateway,
  job: ClaimedMetadataJob,
  errorCode: MetadataFailureCode,
  retryAfterSeconds: number | null,
  summary: MetadataBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  errorCodes.add(errorCode);
  let failResult: MetadataWorkerRpcResult;
  try {
    failResult = await gateway.failMetadataJob({
      jobId: job.job_id,
      leaseToken: job.lease_token,
      errorCode,
      retryAfterSeconds,
    });
  } catch {
    summary.failed++;
    errorCodes.add("FAIL_RPC_UNAVAILABLE");
    return;
  }

  if (failResult.error !== null || !isFailResult(failResult.data)) {
    summary.failed++;
    errorCodes.add("FAIL_RPC_UNAVAILABLE");
    return;
  }
  switch (failResult.data.state) {
    case "retry":
      summary.retry_scheduled++;
      break;
    case "failed":
      summary.failed++;
      break;
    case "discarded_stale":
      summary.discarded++;
      break;
  }
}

export function isInternalMetadataPath(pathname: string): boolean {
  return pathname === "/v1/internal/metadata" ||
    pathname === "/library-api/v1/internal/metadata" ||
    pathname === "/functions/v1/library-api/v1/internal/metadata";
}

export function createMetadataHandler(
  gateway: MetadataGateway,
  serviceRoleKey: string,
  fetcher: MetadataFetcher = fetchMetadata,
): (request: Request) => Promise<Response> {
  return createInternalBatchHandler(
    serviceRoleKey,
    MAX_BATCH_LIMIT,
    (limit) => runMetadataBatch(gateway, limit, fetcher),
    "METADATA_WORKER_UNAVAILABLE",
  );
}

function isClaimResult(
  value: unknown,
): value is { jobs: ClaimedMetadataJob[] } {
  return isObject(value) && Object.keys(value).length === 1 &&
    Array.isArray(value.jobs) && value.jobs.every(isClaimedJob);
}

function isClaimedJob(value: unknown): value is ClaimedMetadataJob {
  return isObject(value) && Object.keys(value).length === 7 &&
    typeof value.job_id === "string" && UUID_PATTERN.test(value.job_id) &&
    typeof value.lease_token === "string" &&
    UUID_PATTERN.test(value.lease_token) &&
    typeof value.owner_id === "string" && UUID_PATTERN.test(value.owner_id) &&
    isPositiveInteger(value.target_revision) &&
    isPositiveInteger(value.expected_version) &&
    value.budget_reserved === true && isJobSnapshot(value.item) &&
    value.target_revision === value.item.text_revision &&
    value.expected_version === value.item.version;
}

function isJobSnapshot(value: unknown): value is MetadataJobSnapshot {
  if (
    !isObject(value) || typeof value.id !== "string" ||
    !UUID_PATTERN.test(value.id) || !isPositiveInteger(value.version) ||
    !isPositiveInteger(value.text_revision) || typeof value.url !== "string" ||
    value.url.length === 0 || value.metadata_state !== "running" ||
    typeof value.ocr_state !== "string" || !isObject(value.extraction_meta) ||
    !Array.isArray(value.category_refs) ||
    !value.category_refs.every((category) =>
      isObject(category) && typeof category.id === "string" &&
      UUID_PATTERN.test(category.id) && typeof category.name === "string"
    ) || !isActiveAsset(value.active_asset)
  ) {
    return false;
  }
  return [
    "user_title",
    "fetched_title",
    "shared_text",
    "description",
    "body_text",
    "note",
  ].every((key) => isNullableString(value[key]));
}

function isActiveAsset(value: unknown): boolean {
  return value === null ||
    (isObject(value) &&
      (!Object.hasOwn(value, "ocr_text") || isNullableString(value.ocr_text)) &&
      (!Object.hasOwn(value, "ocr_truncated") ||
        typeof value.ocr_truncated === "boolean"));
}

function resultFailureCode(value: unknown): MetadataErrorCode | null {
  if (!isMetadataResult(value)) {
    return "INVALID_RESULT";
  }
  if (value.metadata_state === "ready" || value.metadata_state === "partial") {
    return null;
  }
  return value.extraction_meta.error_code ?? "INVALID_RESULT";
}

function isMetadataResult(value: unknown): value is MetadataResult {
  if (
    !isObject(value) || Object.keys(value).length !== 5 ||
    !isNullableString(value.fetched_title) ||
    !isNullableString(value.description) || value.body_text !== null ||
    !["ready", "partial", "unsupported", "failed"].includes(
      value.metadata_state as string,
    ) || !isExtractionMeta(value.extraction_meta)
  ) {
    return false;
  }
  return true;
}

function isExtractionMeta(value: unknown): value is MetadataExtractionMeta {
  if (
    !isObject(value) || Object.keys(value).length !== 7 ||
    typeof value.adapter_version !== "string" ||
    !isNullableString(value.final_url) ||
    typeof value.title_truncated !== "boolean" ||
    typeof value.description_truncated !== "boolean" ||
    typeof value.body_truncated !== "boolean" ||
    typeof value.last_checked_at !== "string"
  ) {
    return false;
  }
  return value.error_code === null ||
    (typeof value.error_code === "string" &&
      METADATA_ERROR_CODES.has(value.error_code as MetadataErrorCode));
}

function isCompleteResult(value: unknown): value is
  | { state: "succeeded"; item: Record<string, unknown> }
  | { state: "version_conflict"; item: MetadataJobSnapshot }
  | { state: "discarded_stale" } {
  if (!isObject(value) || typeof value.state !== "string") {
    return false;
  }
  if (value.state === "discarded_stale") {
    return Object.keys(value).length === 1;
  }
  if (value.state === "succeeded") {
    return Object.keys(value).length === 2 && isObject(value.item);
  }
  return value.state === "version_conflict" &&
    Object.keys(value).length === 2 && isJobSnapshot(value.item);
}

function isFailResult(value: unknown): value is
  | { state: "retry"; next_run_at: string }
  | { state: "failed" | "discarded_stale" } {
  if (!isObject(value) || typeof value.state !== "string") {
    return false;
  }
  if (value.state === "retry") {
    return Object.keys(value).length === 2 &&
      typeof value.next_run_at === "string";
  }
  return (value.state === "failed" || value.state === "discarded_stale") &&
    Object.keys(value).length === 1;
}

function isInvalidCompletionFailure(error: MetadataWorkerRpcFailure): boolean {
  return error.code === "P0001" &&
    ["INVALID_METADATA_RESULT", "INVALID_PREPARED"].includes(
      error.message ?? "",
    );
}

function validRetryAfter(value: number | null): number | null {
  return Number.isInteger(value) && value !== null && value >= 0 &&
      value <= MAX_POSTGRES_INTEGER
    ? value
    : null;
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 &&
    (value as number) <= MAX_POSTGRES_INTEGER;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
