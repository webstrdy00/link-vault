import {
  classifySnapshot,
  type ItemDetailSnapshot,
} from "./discovery-engine.ts";

const MAX_BATCH_LIMIT = 20;
const MAX_INTERNAL_BODY_BYTES = 1024;
const MAX_COMMIT_RETRIES = 2;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface WorkerRpcFailure {
  code?: string;
  message?: string;
}

export interface WorkerRpcResult {
  data: unknown;
  error: WorkerRpcFailure | null;
}

export interface ClaimedClassificationJob {
  job_id: string;
  lease_token: string;
  owner_id: string;
  target_revision: number;
  item: ClassificationJobSnapshot;
}

export interface ClassificationJobSnapshot extends ItemDetailSnapshot {
  version: number;
  text_revision: number;
}

export interface CompleteClassificationCall {
  jobId: string;
  leaseToken: string;
  expectedVersion: number;
  result: ReturnType<typeof classifySnapshot>;
}

export interface FailClassificationCall {
  jobId: string;
  leaseToken: string;
  retryable: boolean;
  errorCode: string;
}

export interface ClassificationGateway {
  claimClassificationJobs(limit: number): Promise<WorkerRpcResult>;
  completeClassificationJob(
    call: CompleteClassificationCall,
  ): Promise<WorkerRpcResult>;
  failClassificationJob(call: FailClassificationCall): Promise<WorkerRpcResult>;
}

export interface ClassificationBatchResult {
  claimed: number;
  succeeded: number;
  discarded: number;
  retry_scheduled: number;
  failed: number;
  version_conflicts: number;
  error_codes: string[];
}

export async function runClassificationBatch(
  gateway: ClassificationGateway,
  limit: number,
): Promise<ClassificationBatchResult> {
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_BATCH_LIMIT) {
    throw new TypeError("limit must be an integer between 1 and 20");
  }

  const claimResult = await gateway.claimClassificationJobs(limit);
  if (claimResult.error !== null || !isClaimResult(claimResult.data)) {
    throw new Error("CLASSIFICATION_CLAIM_FAILED");
  }

  const summary: ClassificationBatchResult = {
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
      processJob(gateway, job, summary, errorCodes)
    ),
  );

  summary.error_codes = [...errorCodes].sort();
  return summary;
}

async function processJob(
  gateway: ClassificationGateway,
  job: ClaimedClassificationJob,
  summary: ClassificationBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  let snapshot = job.item;

  for (let retry = 0; retry <= MAX_COMMIT_RETRIES; retry++) {
    let result: ReturnType<typeof classifySnapshot>;
    try {
      result = classifySnapshot(snapshot, job.target_revision);
    } catch {
      await reportFailure(
        gateway,
        job,
        false,
        "INVALID_RESULT",
        summary,
        errorCodes,
      );
      return;
    }

    let completeResult: WorkerRpcResult;
    try {
      completeResult = await gateway.completeClassificationJob({
        jobId: job.job_id,
        leaseToken: job.lease_token,
        expectedVersion: snapshot.version,
        result,
      });
    } catch {
      await reportFailure(
        gateway,
        job,
        true,
        "INTERNAL_ERROR",
        summary,
        errorCodes,
      );
      return;
    }

    if (completeResult.error !== null) {
      const invalidResult = isInvalidCompletionFailure(completeResult.error);
      await reportFailure(
        gateway,
        job,
        !invalidResult,
        invalidResult ? "INVALID_RESULT" : "INTERNAL_ERROR",
        summary,
        errorCodes,
      );
      return;
    }
    if (!isCompleteResult(completeResult.data)) {
      await reportFailure(
        gateway,
        job,
        true,
        "INTERNAL_ERROR",
        summary,
        errorCodes,
      );
      return;
    }

    if (completeResult.data.state === "succeeded") {
      summary.succeeded++;
      return;
    }
    if (completeResult.data.state === "discarded") {
      summary.discarded++;
      return;
    }

    summary.version_conflicts++;
    if (retry === MAX_COMMIT_RETRIES) {
      await reportFailure(
        gateway,
        job,
        true,
        "WORKER_BUSY",
        summary,
        errorCodes,
      );
      return;
    }
    snapshot = completeResult.data.item;
  }
}

async function reportFailure(
  gateway: ClassificationGateway,
  job: ClaimedClassificationJob,
  retryable: boolean,
  errorCode: string,
  summary: ClassificationBatchResult,
  errorCodes: Set<string>,
): Promise<void> {
  errorCodes.add(errorCode);
  let failResult: WorkerRpcResult;
  try {
    failResult = await gateway.failClassificationJob({
      jobId: job.job_id,
      leaseToken: job.lease_token,
      retryable,
      errorCode,
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
    case "discarded":
      summary.discarded++;
      break;
    case "retry":
      summary.retry_scheduled++;
      break;
    case "failed":
      summary.failed++;
      break;
  }
}

export function isInternalClassificationPath(pathname: string): boolean {
  return pathname === "/v1/internal/classify" ||
    pathname === "/library-api/v1/internal/classify" ||
    pathname === "/functions/v1/library-api/v1/internal/classify";
}

export function createClassificationHandler(
  gateway: ClassificationGateway,
  serviceRoleKey: string,
): (request: Request) => Promise<Response> {
  return createInternalBatchHandler(
    serviceRoleKey,
    MAX_BATCH_LIMIT,
    (limit) => runClassificationBatch(gateway, limit),
    "CLASSIFICATION_WORKER_UNAVAILABLE",
  );
}

export function createInternalBatchHandler<T>(
  serviceRoleKey: string,
  maxBatchLimit: number,
  runBatch: (limit: number) => Promise<T>,
  unavailableErrorCode: string,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method.toUpperCase() !== "POST") {
      return internalResponse(405, { error_code: "METHOD_NOT_ALLOWED" }, {
        allow: "POST",
      });
    }
    if (request.headers.get("authorization") !== `Bearer ${serviceRoleKey}`) {
      return internalResponse(401, { error_code: "UNAUTHENTICATED" });
    }
    if (new URL(request.url).search.length !== 0) {
      return internalResponse(400, { error_code: "INVALID_QUERY" });
    }

    let body: unknown;
    try {
      body = await readInternalJson(request);
    } catch {
      return internalResponse(400, { error_code: "INVALID_BODY" });
    }
    if (
      !isObject(body) || Object.keys(body).length !== 1 ||
      !Object.hasOwn(body, "limit") || !Number.isInteger(body.limit) ||
      (body.limit as number) < 1 || (body.limit as number) > maxBatchLimit
    ) {
      return internalResponse(400, { error_code: "INVALID_BODY" });
    }

    try {
      return internalResponse(
        200,
        await runBatch(body.limit as number),
      );
    } catch {
      return internalResponse(503, {
        error_code: unavailableErrorCode,
      });
    }
  };
}

async function readInternalJson(request: Request): Promise<unknown> {
  const mediaType = request.headers.get("content-type")?.split(";", 1)[0]
    .trim().toLowerCase();
  if (mediaType !== "application/json" || request.body === null) {
    throw new Error("INVALID_BODY");
  }
  const contentLength = request.headers.get("content-length");
  if (
    contentLength !== null &&
    (!/^\d+$/.test(contentLength.trim()) ||
      Number(contentLength) > MAX_INTERNAL_BODY_BYTES)
  ) {
    throw new Error("INVALID_BODY");
  }

  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let bytes = 0;
  let text = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      bytes += value.byteLength;
      if (bytes > MAX_INTERNAL_BODY_BYTES) {
        await reader.cancel();
        throw new Error("INVALID_BODY");
      }
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
    return JSON.parse(text);
  } finally {
    reader.releaseLock();
  }
}

function isClaimResult(
  value: unknown,
): value is { jobs: ClaimedClassificationJob[] } {
  return isObject(value) && Object.keys(value).length === 1 &&
    Array.isArray(value.jobs) && value.jobs.every(isClaimedJob);
}

function isClaimedJob(value: unknown): value is ClaimedClassificationJob {
  return isObject(value) && typeof value.job_id === "string" &&
    UUID_PATTERN.test(value.job_id) && typeof value.lease_token === "string" &&
    UUID_PATTERN.test(value.lease_token) &&
    typeof value.owner_id === "string" &&
    UUID_PATTERN.test(value.owner_id) &&
    isPositiveInteger(value.target_revision) && isJobSnapshot(value.item) &&
    value.item.text_revision === value.target_revision;
}

function isJobSnapshot(value: unknown): value is ClassificationJobSnapshot {
  if (
    !isObject(value) || !isPositiveInteger(value.version) ||
    !isPositiveInteger(value.text_revision)
  ) {
    return false;
  }
  return [
    "user_title",
    "fetched_title",
    "note",
    "shared_text",
    "description",
    "body_text",
    "ocr_text",
  ].every((key) =>
    !Object.hasOwn(value, key) || value[key] === null ||
    typeof value[key] === "string"
  );
}

function isCompleteResult(value: unknown): value is
  | { state: "succeeded"; item?: ClassificationJobSnapshot }
  | { state: "discarded" }
  | { state: "version_conflict"; item: ClassificationJobSnapshot } {
  if (!isObject(value) || typeof value.state !== "string") {
    return false;
  }
  if (value.state === "discarded") {
    return Object.keys(value).length === 1;
  }
  if (value.state === "succeeded") {
    return Object.keys(value).length === 1 ||
      (Object.keys(value).length === 2 && isJobSnapshot(value.item));
  }
  return value.state === "version_conflict" &&
    Object.keys(value).length === 2 && isJobSnapshot(value.item);
}

function isFailResult(
  value: unknown,
): value is { state: "discarded" | "retry" | "failed" } {
  return isObject(value) && Object.keys(value).length === 1 &&
    ["discarded", "retry", "failed"].includes(value.state as string);
}

function isInvalidCompletionFailure(error: WorkerRpcFailure): boolean {
  return error.code === "P0001" &&
    [
      "INVALID_CLASSIFICATION_RESULT",
      "CLASSIFICATION_CATEGORIES_UNAVAILABLE",
      "INVALID_EXPECTED_VERSION",
    ].includes(error.message ?? "");
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 &&
    (value as number) <= 2_147_483_647;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function internalResponse(
  status: number,
  body: unknown,
  extraHeaders: HeadersInit = {},
): Response {
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("cache-control", "no-store");
  return new Response(JSON.stringify(body), { status, headers });
}
