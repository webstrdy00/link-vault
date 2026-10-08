import { assert, assertEquals, assertRejects } from "@std/assert";
import { prepareEnrichedSnapshot } from "./item-preparation.ts";
import {
  type MetadataErrorCode,
  MetadataFetchFailure,
  type MetadataResult,
} from "./metadata-engine.ts";
import {
  type ClaimedMetadataJob,
  type CompleteMetadataCall,
  createMetadataHandler,
  type FailMetadataCall,
  isInternalMetadataPath,
  type MetadataCompletionResult,
  type MetadataFetcher,
  type MetadataGateway,
  type MetadataWorkerRpcResult,
  runMetadataBatch,
} from "./metadata-worker.ts";

const JOB_ID = "3d8752d2-47bb-4f4c-b2c7-7a3590eb02a9";
const LEASE_TOKEN = "5e51d680-b8d8-4f7a-a29f-d764f2965aa2";
const OWNER_ID = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const CATEGORY_ID = "264c6a49-04a0-45c4-954f-773f97c0c4bb";

class FakeMetadataGateway implements MetadataGateway {
  claimResult: MetadataWorkerRpcResult = {
    data: { jobs: [metadataJob()] },
    error: null,
  };
  completeResults: MetadataWorkerRpcResult[] = [{
    data: { state: "succeeded", item: { id: JOB_ID } },
    error: null,
  }];
  failResult: MetadataWorkerRpcResult = {
    data: { state: "retry", next_run_at: "2026-09-15T13:00:00Z" },
    error: null,
  };
  claimedLimits: number[] = [];
  completeCalls: CompleteMetadataCall[] = [];
  failCalls: FailMetadataCall[] = [];

  claimMetadataJobs(limit: number): Promise<MetadataWorkerRpcResult> {
    this.claimedLimits.push(limit);
    return Promise.resolve(this.claimResult);
  }

  completeMetadataJob(
    call: CompleteMetadataCall,
  ): Promise<MetadataWorkerRpcResult> {
    this.completeCalls.push(call);
    return Promise.resolve(
      this.completeResults.shift() ?? {
        data: { state: "discarded_stale" },
        error: null,
      },
    );
  }

  failMetadataJob(call: FailMetadataCall): Promise<MetadataWorkerRpcResult> {
    this.failCalls.push(call);
    return Promise.resolve(this.failResult);
  }
}

Deno.test("metadata worker fetches once and recomputes exact prepared payloads across two version conflicts", async () => {
  const initial = metadataJob();
  const second = {
    ...initial.item,
    version: 5,
    note: "두 번째 최신 메모",
  };
  const third = {
    ...second,
    version: 6,
    note: "세 번째 최신 메모",
  };
  const gateway = new FakeMetadataGateway();
  gateway.completeResults = [
    { data: { state: "version_conflict", item: second }, error: null },
    { data: { state: "version_conflict", item: third }, error: null },
    { data: { state: "succeeded", item: { id: JOB_ID } }, error: null },
  ];
  const fetched = metadataResult();
  const fetchedUrls: string[] = [];
  const fetcher: MetadataFetcher = (url) => {
    fetchedUrls.push(url);
    return Promise.resolve(fetched);
  };

  const summary = await runMetadataBatch(gateway, 10, fetcher);

  assertEquals(gateway.claimedLimits, [10]);
  assertEquals(fetchedUrls, [initial.item.url]);
  assertEquals(gateway.completeCalls.length, 3);
  assertEquals(
    gateway.completeCalls.map((call) => call.expectedVersion),
    [4, 5, 6],
  );
  assertEquals(
    gateway.completeCalls.map((call) => call.result),
    [fetched, fetched, fetched],
  );

  const enriched = prepareEnrichedSnapshot(initial.item, {
    ...fetched,
    extraction_meta: { ...fetched.extraction_meta },
  });
  assertEquals(gateway.completeCalls[0], {
    jobId: JOB_ID,
    leaseToken: LEASE_TOKEN,
    expectedVersion: 4,
    result: fetched,
    prepared: {
      snapshot_version: 4,
      normalized_fields: enriched.normalized_fields,
      alias_concepts: enriched.concept_index,
      cue_state: enriched.cue_state,
      cue_flags: enriched.cue_flags,
    },
  });
  assertEquals(
    Object.keys(gateway.completeCalls[0].prepared).sort(),
    [
      "alias_concepts",
      "cue_flags",
      "cue_state",
      "normalized_fields",
      "snapshot_version",
    ],
  );
  assertEquals(
    gateway.completeCalls.map((call) => call.prepared.normalized_fields.note),
    ["기존 메모", "두 번째 최신 메모", "세 번째 최신 메모"],
  );
  assertEquals(gateway.failCalls, []);
  assertEquals(summary, {
    claimed: 1,
    succeeded: 1,
    discarded: 0,
    retry_scheduled: 0,
    failed: 0,
    version_conflicts: 2,
    error_codes: [],
  });
});

Deno.test("metadata worker stops stale or revoked work without refetching or applying", async () => {
  const staleGateway = new FakeMetadataGateway();
  staleGateway.completeResults = [{
    data: { state: "discarded_stale" },
    error: null,
  }];
  let staleFetches = 0;
  const stale = await runMetadataBatch(staleGateway, 1, () => {
    staleFetches++;
    return Promise.resolve(metadataResult());
  });

  assertEquals(staleFetches, 1);
  assertEquals(staleGateway.completeCalls.length, 1);
  assertEquals(staleGateway.failCalls, []);
  assertEquals(stale.discarded, 1);
  assertEquals(stale.succeeded, 0);

  const revokedGateway = new FakeMetadataGateway();
  revokedGateway.failResult = {
    data: { state: "discarded_stale" },
    error: null,
  };
  let revokedFetches = 0;
  const revoked = await runMetadataBatch(revokedGateway, 1, () => {
    revokedFetches++;
    return Promise.reject(new MetadataFetchFailure("NETWORK_ERROR"));
  });

  assertEquals(revokedFetches, 1);
  assertEquals(revokedGateway.completeCalls, []);
  assertEquals(revokedGateway.failCalls.length, 1);
  assertEquals(revoked.discarded, 1);
  assertEquals(revoked.retry_scheduled, 0);
  assertEquals(revoked.error_codes, ["NETWORK_ERROR"]);
});

Deno.test("metadata worker reports fixed permanent codes and never sends returned unsupported content to complete", async () => {
  const gateway = new FakeMetadataGateway();
  gateway.failResult = { data: { state: "failed" }, error: null };
  let fetches = 0;
  const unsupported: MetadataResult = {
    ...metadataResult(),
    fetched_title: null,
    description: null,
    metadata_state: "unsupported",
    extraction_meta: {
      ...metadataResult().extraction_meta,
      final_url: null,
      error_code: "INVALID_CONTENT",
    },
  };

  const summary = await runMetadataBatch(gateway, 1, () => {
    fetches++;
    return Promise.resolve(unsupported);
  });

  assertEquals(fetches, 1);
  assertEquals(gateway.completeCalls, []);
  assertEquals(gateway.failCalls, [{
    jobId: JOB_ID,
    leaseToken: LEASE_TOKEN,
    errorCode: "INVALID_CONTENT",
    retryAfterSeconds: null,
  }]);
  assertEquals(summary.failed, 1);
  assertEquals(summary.error_codes, ["INVALID_CONTENT"]);
});

Deno.test("metadata worker forwards bounded Retry-After once and has no fetch retry loop", async () => {
  const gateway = new FakeMetadataGateway();
  let fetches = 0;

  const summary = await runMetadataBatch(gateway, 1, () => {
    fetches++;
    return Promise.reject(new MetadataFetchFailure("RATE_LIMITED", 321));
  });

  assertEquals(fetches, 1);
  assertEquals(gateway.completeCalls, []);
  assertEquals(gateway.failCalls, [{
    jobId: JOB_ID,
    leaseToken: LEASE_TOKEN,
    errorCode: "RATE_LIMITED",
    retryAfterSeconds: 321,
  }]);
  assertEquals(summary.retry_scheduled, 1);
  assertEquals(summary.error_codes, ["RATE_LIMITED"]);

  const excessiveGateway = new FakeMetadataGateway();
  excessiveGateway.failResult = { data: { state: "failed" }, error: null };
  let excessiveFetches = 0;
  const excessive = await runMetadataBatch(excessiveGateway, 1, () => {
    excessiveFetches++;
    return Promise.reject(
      new MetadataFetchFailure("RETRY_AFTER_EXCEEDED"),
    );
  });
  assertEquals(excessiveFetches, 1);
  assertEquals(excessiveGateway.completeCalls, []);
  assertEquals(excessiveGateway.failCalls[0].errorCode, "RETRY_AFTER_EXCEEDED");
  assertEquals(excessiveGateway.failCalls[0].retryAfterSeconds, null);
  assertEquals(excessive.failed, 1);
  assertEquals(excessive.error_codes, ["RETRY_AFTER_EXCEEDED"]);
});

Deno.test("metadata worker converts free-form failures and RPC details to allowlisted codes", async () => {
  const fetchGateway = new FakeMetadataGateway();
  const fetchSummary = await runMetadataBatch(
    fetchGateway,
    1,
    () =>
      Promise.reject(
        new Error(
          "GET https://private.example/token-value returned secret body",
        ),
      ),
  );

  assertEquals(fetchGateway.failCalls[0].errorCode, "INTERNAL_ERROR");
  assertEquals(fetchGateway.failCalls[0].retryAfterSeconds, null);
  assertEquals(
    JSON.stringify(fetchGateway.failCalls).includes("private.example"),
    false,
  );
  assertEquals(fetchSummary.error_codes, ["INTERNAL_ERROR"]);

  const rpcGateway = new FakeMetadataGateway();
  rpcGateway.completeResults = [{
    data: null,
    error: {
      code: "08006",
      message: "connection failed for https://private.example/token-value",
    },
  }];
  const rpcSummary = await runMetadataBatch(
    rpcGateway,
    1,
    () => Promise.resolve(metadataResult()),
  );

  assertEquals(rpcGateway.completeCalls.length, 1);
  assertEquals(rpcGateway.failCalls[0].errorCode, "INTERNAL_ERROR");
  assertEquals(
    JSON.stringify(rpcGateway.failCalls).includes("private.example"),
    false,
  );
  assertEquals(rpcSummary.error_codes, ["INTERNAL_ERROR"]);

  const invalidGateway = new FakeMetadataGateway();
  invalidGateway.failResult = { data: { state: "failed" }, error: null };
  invalidGateway.completeResults = [{
    data: null,
    error: { code: "P0001", message: "INVALID_PREPARED" },
  }];
  const invalidSummary = await runMetadataBatch(
    invalidGateway,
    1,
    () => Promise.resolve(metadataResult()),
  );
  assertEquals(invalidGateway.failCalls[0].errorCode, "INVALID_RESULT");
  assertEquals(invalidSummary.failed, 1);
  assertEquals(invalidSummary.error_codes, ["INVALID_RESULT"]);
});

Deno.test("metadata worker caps completion retries at two without another fetch", async () => {
  const gateway = new FakeMetadataGateway();
  const second = { ...metadataJob().item, version: 5 };
  const third = { ...metadataJob().item, version: 6 };
  const fourth = { ...metadataJob().item, version: 7 };
  gateway.completeResults = [second, third, fourth].map((item) => ({
    data: { state: "version_conflict", item },
    error: null,
  }));
  let fetches = 0;

  const summary = await runMetadataBatch(gateway, 1, () => {
    fetches++;
    return Promise.resolve(metadataResult());
  });

  assertEquals(fetches, 1);
  assertEquals(gateway.completeCalls.length, 3);
  assertEquals(gateway.failCalls.length, 1);
  assertEquals(gateway.failCalls[0].errorCode, "INTERNAL_ERROR");
  assertEquals(summary.version_conflicts, 3);
  assertEquals(summary.retry_scheduled, 1);
});

Deno.test("metadata worker refuses unreserved claims before any fetch", async () => {
  const gateway = new FakeMetadataGateway();
  gateway.claimResult = {
    data: {
      jobs: [{ ...metadataJob(), budget_reserved: false }],
    },
    error: null,
  };
  let fetches = 0;

  await assertRejects(
    () =>
      runMetadataBatch(gateway, 1, () => {
        fetches++;
        return Promise.resolve(metadataResult());
      }),
    Error,
    "METADATA_CLAIM_FAILED",
  );

  assertEquals(fetches, 0);
  assertEquals(gateway.claimedLimits, [1]);
  assertEquals(gateway.completeCalls, []);
  assertEquals(gateway.failCalls, []);
});

Deno.test("internal metadata endpoint uses exact service auth and a strict bounded body", async () => {
  for (
    const path of [
      "/v1/internal/metadata",
      "/library-api/v1/internal/metadata",
      "/functions/v1/library-api/v1/internal/metadata",
    ]
  ) {
    assertEquals(isInternalMetadataPath(path), true);
  }
  for (
    const path of [
      "/internal/metadata",
      "/v1/internal/metadata/extra",
      "/v1/internal/classify",
      "/v1/items",
    ]
  ) {
    assertEquals(isInternalMetadataPath(path), false);
  }

  const gateway = new FakeMetadataGateway();
  gateway.claimResult = { data: { jobs: [] }, error: null };
  const handler = createMetadataHandler(
    gateway,
    "service-secret",
    () => Promise.resolve(metadataResult()),
  );

  for (
    const authorization of [
      null,
      "Bearer caller-access-token",
      "bearer service-secret",
      "Bearer service-secret extra",
    ]
  ) {
    const headers = new Headers({ "content-type": "application/json" });
    if (authorization !== null) {
      headers.set("authorization", authorization);
    }
    const response = await handler(internalRequest(headers, { limit: 1 }));
    assertEquals(response.status, 401);
    assertEquals(await response.json(), { error_code: "UNAUTHENTICATED" });
  }
  assertEquals(gateway.claimedLimits, []);

  const accepted = await handler(internalRequest(
    new Headers({
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    }),
    { limit: 10 },
  ));
  assertEquals(accepted.status, 200);
  assertEquals(gateway.claimedLimits, [10]);
  assertEquals((await accepted.json() as { claimed: number }).claimed, 0);

  for (const body of [{ limit: 11 }, { limit: 1, token: "no" }, {}]) {
    const response = await handler(internalRequest(
      new Headers({
        authorization: "Bearer service-secret",
        "content-type": "application/json",
      }),
      body,
    ));
    assertEquals(response.status, 400, JSON.stringify(body));
    assertEquals(await response.json(), { error_code: "INVALID_BODY" });
  }

  const invalidContentType = await handler(
    new Request(
      "https://example.test/v1/internal/metadata",
      {
        method: "POST",
        headers: {
          authorization: "Bearer service-secret",
          "content-type": "text/plain",
        },
        body: JSON.stringify({ limit: 1 }),
      },
    ),
  );
  assertEquals(invalidContentType.status, 400);
  assertEquals(await invalidContentType.json(), {
    error_code: "INVALID_BODY",
  });

  const invalidQuery = await handler(
    new Request(
      "https://example.test/v1/internal/metadata?limit=1",
      {
        method: "POST",
        headers: {
          authorization: "Bearer service-secret",
          "content-type": "application/json",
        },
        body: JSON.stringify({ limit: 1 }),
      },
    ),
  );
  assertEquals(invalidQuery.status, 400);
  assertEquals(await invalidQuery.json(), {
    error_code: "INVALID_QUERY",
  });

  const wrongMethod = await handler(
    new Request(
      "https://example.test/v1/internal/metadata",
      {
        method: "GET",
        headers: { authorization: "Bearer service-secret" },
      },
    ),
  );
  assertEquals(wrongMethod.status, 405);
  assertEquals(wrongMethod.headers.get("allow"), "POST");

  const unavailableGateway = new FakeMetadataGateway();
  unavailableGateway.claimResult = {
    data: null,
    error: {
      code: "08006",
      message: "database failed for https://private.example/token-value",
    },
  };
  const unavailableHandler = createMetadataHandler(
    unavailableGateway,
    "service-secret",
    () => Promise.resolve(metadataResult()),
  );
  const unavailable = await unavailableHandler(internalRequest(
    new Headers({
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    }),
    { limit: 1 },
  ));
  assertEquals(unavailable.status, 503);
  assertEquals(await unavailable.json(), {
    error_code: "METADATA_WORKER_UNAVAILABLE",
  });
});

function metadataJob(): ClaimedMetadataJob {
  return {
    job_id: JOB_ID,
    lease_token: LEASE_TOKEN,
    owner_id: OWNER_ID,
    target_revision: 3,
    expected_version: 4,
    budget_reserved: true,
    item: {
      id: JOB_ID,
      version: 4,
      text_revision: 3,
      url: "https://blog.naver.com/example/123",
      user_title: null,
      fetched_title: null,
      shared_text: "공유한 본문",
      description: null,
      body_text: null,
      note: "기존 메모",
      category_refs: [{ id: CATEGORY_ID, name: "업무" }],
      metadata_state: "running",
      ocr_state: "ready",
      extraction_meta: {},
      active_asset: {
        ocr_text: "사진 텍스트",
        ocr_truncated: false,
      },
    },
  };
}

function metadataResult(
  errorCode: MetadataErrorCode | null = null,
): MetadataResult & MetadataCompletionResult {
  return {
    fetched_title: "가져온 제목",
    description: "가져온 설명",
    body_text: null,
    metadata_state: "ready",
    extraction_meta: {
      adapter_version: "naver-metadata-v1",
      final_url: "https://blog.naver.com/example/123",
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
      last_checked_at: "2026-09-15T12:00:00.000Z",
      error_code: errorCode,
    },
  };
}

function internalRequest(headers: Headers, body: unknown): Request {
  return new Request("https://example.test/v1/internal/metadata", {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
}

Deno.test("metadata worker batch bound rejects before claiming", async () => {
  const gateway = new FakeMetadataGateway();
  for (const limit of [0, 11, 1.5]) {
    await assertRejects(
      () =>
        runMetadataBatch(
          gateway,
          limit,
          () => Promise.resolve(metadataResult()),
        ),
      TypeError,
      "limit must be an integer between 1 and 10",
    );
  }
  assertEquals(gateway.claimedLimits, []);
  assert(gateway.completeCalls.length === 0);
});
