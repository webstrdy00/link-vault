import { assert, assertEquals, assertMatch } from "@std/assert";
import {
  type AuthVerification,
  type CategoryLookupCall,
  createHandler,
  type CreateItemCall,
  type MemberGateway,
  type RpcCall,
  type RpcResult,
  type ServiceRpcCall,
  type UpdateItemCall,
} from "./handler.ts";
import {
  type AccountDeletionGateway,
  type AccountDeletionRpcResult,
  AccountDeletionService,
} from "./account-deletion.ts";
import {
  type ClassificationGateway,
  type CompleteClassificationCall,
  createClassificationHandler,
  type FailClassificationCall,
  isInternalClassificationPath,
  runClassificationBatch,
  type WorkerRpcResult,
} from "./classification-worker.ts";
import {
  type AssetObjectDownload,
  type ChangeAssetCall,
  type CleanupAssetCall,
  type CompleteAssetCall,
  type DownloadAssetObjectCall,
  type FailAssetCleanupCall,
  type LookupAssetCall,
  type RejectAssetUploadCall,
  type RemoveAssetObjectCall,
  type ReplayAssetRequestCall,
  type ReserveAssetCall,
} from "./asset-service.ts";

const VALID_REQUEST_ID = "5e51d680-b8d8-4f7a-a29f-d764f2965aa2";
const VALID_ITEM_ID = "3d8752d2-47bb-4f4c-b2c7-7a3590eb02a9";
const VERIFIED_USER_ID = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const VALID_CATEGORY_ID = "264c6a49-04a0-45c4-954f-773f97c0c4bb";
const SECOND_CATEGORY_ID = "71baadfe-6a88-43a3-b0fb-f111f18ef97f";
const VALID_ASSET_ID = "8ff75967-3313-4a63-8131-e606dd63b087";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.test("private worker routing matches the configured versioned deployment path", () => {
  for (
    const path of [
      "/v1/internal/classify",
      "/library-api/v1/internal/classify",
      "/functions/v1/library-api/v1/internal/classify",
    ]
  ) assertEquals(isInternalClassificationPath(path), true);
  for (
    const path of [
      "/internal/classify",
      "/library-api/internal/classify",
      "/functions/v1/library-api/internal/classify",
      "/v1/internal/classify/extra",
      "/v1/items",
    ]
  ) assertEquals(isInternalClassificationPath(path), false);
});

class FakeGateway implements MemberGateway {
  healthy = true;
  health(): Promise<boolean> {
    return Promise.resolve(this.healthy);
  }
  verification: AuthVerification = {
    status: "verified",
    userId: VERIFIED_USER_ID,
  };
  rpcResult: RpcResult = { data: { state: "active" }, error: null };
  createResult: RpcResult = {
    data: { http_status: 201, duplicate: false, item: { id: VALID_ITEM_ID } },
    error: null,
  };
  categoryResult: RpcResult = { data: [], error: null };
  updateResult: RpcResult = {
    data: {
      http_status: 200,
      item: { id: VALID_ITEM_ID, version: 2 },
    },
    error: null,
  };
  serviceResult: RpcResult = {
    data: { http_status: 200, item: { id: VALID_ITEM_ID, version: 2 } },
    error: null,
  };
  replayAssetResult: RpcResult = { data: { found: false }, error: null };
  reserveAssetResult: RpcResult = {
    data: {
      http_status: 201,
      asset_id: VALID_ASSET_ID,
      object_path: `${VERIFIED_USER_ID}/${VALID_ITEM_ID}/${VALID_ASSET_ID}`,
      expires_at: "2026-09-15T12:00:00Z",
      max_bytes: 2_000_000,
    },
    error: null,
  };
  lookupAssetResult: RpcResult = {
    data: {
      id: VALID_ASSET_ID,
      owner_id: VERIFIED_USER_ID,
      item_id: VALID_ITEM_ID,
      state: "active",
      object_path: `${VERIFIED_USER_ID}/${VALID_ITEM_ID}/${VALID_ASSET_ID}`,
      reserved_mime_type: "image/png",
      mime_type: "image/png",
      actual_bytes: 4,
    },
    error: null,
  };
  assetDownload: AssetObjectDownload = {
    status: "found",
    bytes: new Uint8Array([1, 2, 3, 4]),
    mimeType: "image/png",
  };
  completeAssetResult: RpcResult = {
    data: { http_status: 200, item: { id: VALID_ITEM_ID, version: 2 } },
    error: null,
  };
  rejectAssetResult: RpcResult = {
    data: { http_status: 409, error_code: "ASSET_DECODE_FAILED" },
    error: null,
  };
  deleteAssetResult: RpcResult = {
    data: {
      http_status: 202,
      asset_id: VALID_ASSET_ID,
      state: "deleting",
    },
    error: null,
  };
  updateAssetOcrResult: RpcResult = {
    data: { http_status: 200, item: { id: VALID_ITEM_ID, version: 2 } },
    error: null,
  };
  cleanupClaimResult: RpcResult = {
    data: { http_status: 200, jobs: [] },
    error: null,
  };
  cleanupFinishResult: RpcResult = {
    data: {
      http_status: 200,
      state: "complete",
      asset_id: VALID_ASSET_ID,
      released_reserved_bytes: 0,
      released_used_bytes: 4,
    },
    error: null,
  };
  cleanupFailResult: RpcResult = {
    data: {
      http_status: 202,
      state: "retry",
      asset_id: VALID_ASSET_ID,
      next_run_at: "2026-09-15T12:01:00Z",
    },
    error: null,
  };
  removeAssetError: { message?: string } | null = null;
  verifiedTokens: string[] = [];
  rpcCalls: RpcCall[] = [];
  createCalls: CreateItemCall[] = [];
  categoryCalls: CategoryLookupCall[] = [];
  updateCalls: UpdateItemCall[] = [];
  serviceCalls: ServiceRpcCall[] = [];
  replayAssetCalls: ReplayAssetRequestCall[] = [];
  reserveAssetCalls: ReserveAssetCall[] = [];
  lookupAssetCalls: LookupAssetCall[] = [];
  downloadAssetCalls: DownloadAssetObjectCall[] = [];
  completeAssetCalls: CompleteAssetCall[] = [];
  rejectAssetCalls: RejectAssetUploadCall[] = [];
  deleteAssetCalls: ChangeAssetCall[] = [];
  updateAssetOcrCalls: ChangeAssetCall[] = [];
  cleanupClaimCalls: number[] = [];
  removeAssetCalls: RemoveAssetObjectCall[] = [];
  cleanupFinishCalls: CleanupAssetCall[] = [];
  cleanupFailCalls: FailAssetCleanupCall[] = [];

  verifyUser(accessToken: string): Promise<AuthVerification> {
    this.verifiedTokens.push(accessToken);
    return Promise.resolve(this.verification);
  }

  rpc(call: RpcCall): Promise<RpcResult> {
    this.rpcCalls.push(call);
    return Promise.resolve(this.rpcResult);
  }

  createItem(call: CreateItemCall): Promise<RpcResult> {
    this.createCalls.push(call);
    return Promise.resolve(this.createResult);
  }

  lookupCategories(call: CategoryLookupCall): Promise<RpcResult> {
    this.categoryCalls.push(call);
    return Promise.resolve(this.categoryResult);
  }

  updateItem(call: UpdateItemCall): Promise<RpcResult> {
    this.updateCalls.push(call);
    return Promise.resolve(this.updateResult);
  }

  serviceRpc(call: ServiceRpcCall): Promise<RpcResult> {
    this.serviceCalls.push(call);
    return Promise.resolve(this.serviceResult);
  }

  replayAssetRequest(call: ReplayAssetRequestCall): Promise<RpcResult> {
    this.replayAssetCalls.push(call);
    return Promise.resolve(this.replayAssetResult);
  }

  reserveAsset(call: ReserveAssetCall): Promise<RpcResult> {
    this.reserveAssetCalls.push(call);
    return Promise.resolve(this.reserveAssetResult);
  }

  lookupAsset(call: LookupAssetCall): Promise<RpcResult> {
    this.lookupAssetCalls.push(call);
    return Promise.resolve(this.lookupAssetResult);
  }

  downloadAssetObject(
    call: DownloadAssetObjectCall,
  ): Promise<AssetObjectDownload> {
    this.downloadAssetCalls.push(call);
    return Promise.resolve(this.assetDownload);
  }

  completeAsset(call: CompleteAssetCall): Promise<RpcResult> {
    this.completeAssetCalls.push(call);
    return Promise.resolve(this.completeAssetResult);
  }

  rejectAssetUpload(call: RejectAssetUploadCall): Promise<RpcResult> {
    this.rejectAssetCalls.push(call);
    return Promise.resolve(this.rejectAssetResult);
  }

  deleteAsset(call: ChangeAssetCall): Promise<RpcResult> {
    this.deleteAssetCalls.push(call);
    return Promise.resolve(this.deleteAssetResult);
  }

  updateAssetOcr(call: ChangeAssetCall): Promise<RpcResult> {
    this.updateAssetOcrCalls.push(call);
    return Promise.resolve(this.updateAssetOcrResult);
  }

  claimAssetCleanupJobs(limit: number): Promise<RpcResult> {
    this.cleanupClaimCalls.push(limit);
    return Promise.resolve(this.cleanupClaimResult);
  }

  removeAssetObject(
    call: RemoveAssetObjectCall,
  ): Promise<{ error: { message?: string } | null }> {
    this.removeAssetCalls.push(call);
    return Promise.resolve({ error: this.removeAssetError });
  }

  finishAssetCleanup(call: CleanupAssetCall): Promise<RpcResult> {
    this.cleanupFinishCalls.push(call);
    return Promise.resolve(this.cleanupFinishResult);
  }

  failAssetCleanup(call: FailAssetCleanupCall): Promise<RpcResult> {
    this.cleanupFailCalls.push(call);
    return Promise.resolve(this.cleanupFailResult);
  }
}

Deno.test("health is public and generic with either supported host path", async () => {
  const gateway = new FakeGateway();
  const handler = createHandler(gateway);

  for (
    const path of [
      "/library-api/v1/health",
      "/functions/v1/library-api/v1/health",
    ]
  ) {
    const response = await handler(request(path));
    assertEquals(response.status, 200);
    assertEquals(await response.json(), { status: "ok" });
  }

  assertEquals(gateway.verifiedTokens, []);
  assertEquals(gateway.rpcCalls, []);
  assertEquals(gateway.createCalls, []);
});

Deno.test("health reports dependency failures without exposing their details", async () => {
  const gateway = new FakeGateway();
  gateway.healthy = false;
  let response = await createHandler(gateway)(
    request("/library-api/v1/health"),
  );
  assertEquals(response.status, 503);
  assertEquals(await response.json(), { status: "unavailable" });
  gateway.health = () =>
    Promise.reject(new Error("private dependency fixture"));
  response = await createHandler(gateway)(request("/library-api/v1/health"));
  assertEquals(response.status, 503);
  assertEquals(await response.json(), { status: "unavailable" });
  assertEquals(gateway.verifiedTokens, []);
});

Deno.test("authenticated routes reject a missing bearer token", async () => {
  const gateway = new FakeGateway();
  const response = await createHandler(gateway)(request("/library-api/v1/me"));

  assertEquals(response.status, 401);
  assertEquals(await errorCode(response), "UNAUTHENTICATED");
  assertEquals(gateway.verifiedTokens, []);
  assertEquals(gateway.rpcCalls, []);
});

Deno.test("bootstrap verifies the token and forwards caller authorization and request id", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = {
    data: {
      profile: { id: "member-id", state: "active" },
      limits: { items: 100, image_bytes: 20_000_000 },
      usage: {
        active_item_count: 0,
        used_image_bytes: 0,
        reserved_image_bytes: 0,
      },
      categories: [],
    },
    error: null,
  };

  const response = await createHandler(gateway)(bootstrapRequest("{}"));

  assertEquals(response.status, 200);
  assertEquals(gateway.verifiedTokens, ["caller-access-token"]);
  assertEquals(gateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "member_bootstrap",
    args: { p_request_id: VALID_REQUEST_ID },
  }]);
  assertEquals(response.headers.get("x-request-id"), VALID_REQUEST_ID);
  assertEquals(await response.json(), gateway.rpcResult.data);
});

Deno.test("bootstrap keeps its 8 KiB streamed body limit", async () => {
  const gateway = new FakeGateway();
  const body = byteStream(5_000, 4_000);
  const incoming = new Request(
    "https://example.test/library-api/v1/bootstrap",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body,
    },
  );
  assertEquals(incoming.headers.get("content-length"), null);

  const response = await createHandler(gateway)(incoming);

  assertEquals(response.status, 413);
  assertEquals(await errorCode(response), "PAYLOAD_TOO_LARGE");
  assertEquals(gateway.rpcCalls, []);
});

Deno.test("bootstrap requires a valid UUID request id", async () => {
  for (const suppliedId of [null, "not-a-uuid"]) {
    const gateway = new FakeGateway();
    const headers = new Headers({
      authorization: "Bearer caller-access-token",
      "content-type": "application/json",
    });
    if (suppliedId !== null) {
      headers.set("x-request-id", suppliedId);
    }

    const response = await createHandler(gateway)(request(
      "/library-api/v1/bootstrap",
      { method: "POST", headers, body: "{}" },
    ));
    const payload = await errorPayload(response);

    assertEquals(response.status, 400);
    assertEquals(payload.error.code, "INVALID_REQUEST_ID");
    assertMatch(payload.request_id, UUID_PATTERN);
    assert(payload.request_id !== suppliedId);
    assertEquals(gateway.rpcCalls, []);
  }
});

Deno.test("bootstrap rejects invalid JSON, types, fields, and content type", async () => {
  const cases = [
    { contentType: "application/json", body: "{", code: "INVALID_JSON" },
    { contentType: "application/json", body: "null", code: "INVALID_BODY" },
    { contentType: "application/json", body: "[]", code: "INVALID_BODY" },
    {
      contentType: "application/json",
      body: '{"unexpected":true}',
      code: "INVALID_BODY",
    },
    { contentType: "text/plain", body: "{}", code: "UNSUPPORTED_MEDIA_TYPE" },
  ];

  for (const testCase of cases) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(bootstrapRequest(
      testCase.body,
      testCase.contentType,
    ));

    assertEquals(
      response.status,
      testCase.code === "UNSUPPORTED_MEDIA_TYPE" ? 415 : 400,
    );
    assertEquals(await errorCode(response), testCase.code);
    assertEquals(gateway.rpcCalls, []);
  }
});

Deno.test("known paths return 405 for wrong methods and unknown paths return 404", async () => {
  const handler = createHandler(new FakeGateway());

  const wrongMethod = await handler(request(
    "/library-api/v1/bootstrap",
    { method: "GET" },
  ));
  assertEquals(wrongMethod.status, 405);
  assertEquals(wrongMethod.headers.get("allow"), "POST");
  assertEquals(await errorCode(wrongMethod), "METHOD_NOT_ALLOWED");

  const itemsWrongMethod = await handler(request(
    "/library-api/v1/items",
    { method: "DELETE" },
  ));
  assertEquals(itemsWrongMethod.status, 405);
  assertEquals(itemsWrongMethod.headers.get("allow"), "GET, POST");

  const detailWrongMethod = await handler(request(
    `/library-api/v1/items/${VALID_ITEM_ID}`,
    { method: "POST" },
  ));
  assertEquals(detailWrongMethod.status, 405);
  assertEquals(detailWrongMethod.headers.get("allow"), "GET, PATCH, DELETE");

  const unknownRoute = await handler(request("/library-api/v1/items/a/b"));
  assertEquals(unknownRoute.status, 404);
  assertEquals(await errorCode(unknownRoute), "NOT_FOUND");
});

Deno.test("member_me returns the pending approval minimum state", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: { state: "pending_approval" }, error: null };

  const response = await createHandler(gateway)(request(
    "/library-api/v1/me",
    { headers: { authorization: "Bearer caller-access-token" } },
  ));

  assertEquals(response.status, 200);
  assertEquals(await response.json(), { state: "pending_approval" });
  assertEquals(gateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "member_me",
    args: {},
  }]);
});

Deno.test("invalid credentials and auth dependency failures remain distinct", async () => {
  const invalidGateway = new FakeGateway();
  invalidGateway.verification = { status: "invalid" };
  const invalidResponse = await createHandler(invalidGateway)(
    authenticatedMeRequest(),
  );
  const invalidPayload = await errorPayload(invalidResponse);
  assertEquals(invalidResponse.status, 401);
  assertEquals(invalidPayload.error, {
    code: "UNAUTHENTICATED",
    message: "Authentication is required.",
    retryable: false,
  });

  const unavailableGateway = new FakeGateway();
  unavailableGateway.verification = { status: "unavailable" };
  const unavailableResponse = await createHandler(unavailableGateway)(
    authenticatedMeRequest(),
  );
  const unavailablePayload = await errorPayload(unavailableResponse);
  assertEquals(unavailableResponse.status, 503);
  assertEquals(unavailablePayload.error.code, "DEPENDENCY_UNAVAILABLE");
  assertEquals(unavailablePayload.error.retryable, true);
});

Deno.test("POST items takes owner identity only from verified auth and keeps the raw body", async () => {
  const gateway = new FakeGateway();
  const rawBody = {
    url: "HTTPS://Blog.NAVER.com:443/PostView.NAVER?utm_source=x&Keep=A#part",
    title: "  ＥＸＣＥＬ   카톡프사를  ",
    shared_text: "첫 줄 표시\n둘째 줄",
    note: "원문 그대로 저장",
    category_ids: [VALID_CATEGORY_ID],
  };

  const response = await createHandler(gateway)(itemRequest(rawBody));

  assertEquals(response.status, 201);
  assertEquals(await response.json(), {
    duplicate: false,
    item: { id: VALID_ITEM_ID },
  });
  assertEquals(gateway.rpcCalls, []);
  assertEquals(gateway.createCalls.length, 1);
  const call = gateway.createCalls[0];
  assertEquals(call.ownerId, VERIFIED_USER_ID);
  assertEquals(call.requestId, VALID_REQUEST_ID);
  assertEquals(call.body, rawBody);
  assertEquals(
    call.prepared.normalized_url,
    "https://blog.naver.com/PostView.NAVER?utm_source=x&Keep=A#part",
  );
  assertEquals(
    call.prepared.url_hash,
    "ae315e526051413e774419d722e2e45e354816b9e749fb871b8ecde9d453d112",
  );
  assertEquals(call.prepared.source, "naver_blog");
  assertEquals(call.prepared.display_fallback, "첫 줄 표시");
  assertEquals(call.prepared.metadata_allowed, true);
  assertEquals(call.prepared.normalized_fields, {
    user_title: "excel 카톡프사를",
    fetched_title: "",
    note: "원문 그대로 저장",
    ocr: "",
    shared: "첫 줄 표시 둘째 줄",
    description: "",
    body: "",
    categories: "",
    url: "https://blog.naver.com/postview.naver?utm_source=x&keep=a#part",
  });
  assertEquals(call.prepared.alias_concepts.user_title, [
    "excel",
    "kakaotalk",
    "profile_photo",
  ]);
  assertEquals(call.prepared.cue_state, "limited");
  assertEquals(call.prepared.cue_flags, ["short_text"]);
});

Deno.test("POST items rejects client owner and derived data before the service write", async () => {
  for (const field of ["owner_id", "normalized_url", "url_hash", "cue_state"]) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(itemRequest({
      url: "https://example.com/article",
      [field]: "attacker-controlled",
    }));

    assertEquals(response.status, 400);
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.createCalls, []);
  }
});

Deno.test("URL preparation preserves path, query, and fragment as duplicate-key data", async () => {
  const gateway = new FakeGateway();
  const handler = createHandler(gateway);
  const urls = [
    "HTTPS://EXAMPLE.com:443/a/../b/%7E?utm_source=A&x=%2f#One",
    "https://example.com/a/../b/%7E?utm_source=A&x=%2f#One",
    "https://example.com/a/../b/%7E?utm_source=B&x=%2f#One",
    "https://example.com/a/../b/%7E?utm_source=A&x=%2f#Two",
  ];

  for (const url of urls) {
    const response = await handler(itemRequest({ url }));
    assertEquals(response.status, 201);
  }

  assertEquals(
    gateway.createCalls.map((call) => call.prepared.normalized_url),
    [
      "https://example.com/a/../b/%7E?utm_source=A&x=%2f#One",
      "https://example.com/a/../b/%7E?utm_source=A&x=%2f#One",
      "https://example.com/a/../b/%7E?utm_source=B&x=%2f#One",
      "https://example.com/a/../b/%7E?utm_source=A&x=%2f#Two",
    ],
  );
  assertEquals(
    new Set(gateway.createCalls.map((call) => call.prepared.url_hash)).size,
    3,
  );
});

Deno.test("POST items validates raw field types, Unicode bounds, and categories", async () => {
  const tooManyCategories = Array.from(
    { length: 6 },
    (_, index) => `00000000-0000-0000-0000-00000000000${index}`,
  );
  const cases: unknown[] = [
    null,
    [],
    {},
    { url: null },
    { url: "https://example.com", title: null },
    { url: "https://example.com", shared_text: 1 },
    { url: "https://example.com", note: null },
    { url: "https://example.com", category_ids: null },
    { url: "https://example.com", category_ids: ["not-a-uuid"] },
    {
      url: "https://example.com",
      category_ids: [VALID_CATEGORY_ID, VALID_CATEGORY_ID.toUpperCase()],
    },
    { url: "https://example.com", category_ids: tooManyCategories },
    { url: "https://example.com", title: "🙂".repeat(301) },
    { url: "https://example.com", shared_text: "가".repeat(4_001) },
    { url: "https://example.com", note: "가".repeat(4_001) },
    { url: `https://example.com/${"a".repeat(4_100)}` },
  ];

  for (const body of cases) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(itemRequest(body));
    assertEquals(response.status, 400, JSON.stringify(body)?.slice(0, 100));
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.createCalls, []);
  }

  const boundaryGateway = new FakeGateway();
  const boundaryResponse = await createHandler(boundaryGateway)(itemRequest({
    url: "https://example.com",
    title: "🙂".repeat(300),
  }));
  assertEquals(boundaryResponse.status, 201);
  assertEquals(boundaryGateway.createCalls.length, 1);
});

Deno.test("POST items rejects invalid or unsafe URLs", async () => {
  const urls = [
    "not a URL",
    "ftp://example.com/file",
    "https://user:password@example.com/private",
    "https://example.com/a b",
    "https://example.com\\evil",
    " https://example.com",
    "https://example.com ",
  ];

  for (const url of urls) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(itemRequest({ url }));
    assertEquals(response.status, 400);
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.createCalls, []);
  }
});

Deno.test("POST items enforces UUID request ids and the 64 KiB streamed limit", async () => {
  const invalidIdGateway = new FakeGateway();
  const invalidIdResponse = await createHandler(invalidIdGateway)(request(
    "/library-api/v1/items",
    {
      method: "POST",
      headers: {
        authorization: "Bearer caller-access-token",
        "content-type": "application/json",
        "x-request-id": "not-a-uuid",
      },
      body: JSON.stringify({ url: "https://example.com" }),
    },
  ));
  assertEquals(invalidIdResponse.status, 400);
  assertEquals(await errorCode(invalidIdResponse), "INVALID_REQUEST_ID");

  const oversizedGateway = new FakeGateway();
  const oversizedResponse = await createHandler(oversizedGateway)(
    new Request(
      "https://example.test/library-api/v1/items",
      {
        method: "POST",
        headers: authenticatedJsonHeaders(),
        body: byteStream(40_000, 30_000),
      },
    ),
  );
  assertEquals(oversizedResponse.status, 413);
  assertEquals(await errorCode(oversizedResponse), "PAYLOAD_TOO_LARGE");
  assertEquals(oversizedGateway.createCalls, []);
});

Deno.test("prepared aliases use sealed boundaries and cue states", async () => {
  const aliasGateway = new FakeGateway();
  await createHandler(aliasGateway)(itemRequest({
    url: "https://example.com/article",
    title: "카톡방 기록",
    note: "프사기꾼 소식",
  }));
  assertEquals(
    aliasGateway.createCalls[0].prepared.alias_concepts.user_title,
    [],
  );
  assertEquals(aliasGateway.createCalls[0].prepared.alias_concepts.note, []);
  assertEquals(aliasGateway.createCalls[0].prepared.cue_state, "limited");

  const availableGateway = new FakeGateway();
  await createHandler(availableGateway)(itemRequest({
    url: "https://example.com/article",
    note:
      "안드로이드에서 다시 찾으려는 충분히 구체적인 업무 자료를 세 단어 이상으로 길게 기록한 사용자 메모입니다",
  }));
  assertEquals(
    availableGateway.createCalls[0].prepared.alias_concepts.note,
    ["android"],
  );
  assertEquals(availableGateway.createCalls[0].prepared.cue_state, "available");
  assertEquals(availableGateway.createCalls[0].prepared.cue_flags, []);

  const pendingGateway = new FakeGateway();
  await createHandler(pendingGateway)(itemRequest({
    url: "https://m.blog.naver.com/post/1",
  }));
  assertEquals(pendingGateway.createCalls[0].prepared.cue_state, "pending");

  const missingGateway = new FakeGateway();
  await createHandler(missingGateway)(itemRequest({
    url: "http://blog.naver.com/post/1",
  }));
  assertEquals(missingGateway.createCalls[0].prepared.metadata_allowed, false);
  assertEquals(missingGateway.createCalls[0].prepared.cue_state, "missing");

  const threadsGateway = new FakeGateway();
  await createHandler(threadsGateway)(itemRequest({
    url: "https://www.threads.com/@example/post/1",
  }));
  assertEquals(threadsGateway.createCalls[0].prepared.source, "threads");
  assertEquals(threadsGateway.createCalls[0].prepared.metadata_allowed, false);
});

Deno.test("POST items returns DB-selected new and duplicate statuses without internal fields", async () => {
  for (
    const result of [
      {
        http_status: 201,
        duplicate: false,
        item: { id: VALID_ITEM_ID, version: 1 },
      },
      {
        http_status: 200,
        duplicate: true,
        item: { id: VALID_ITEM_ID, version: 2 },
      },
    ] as const
  ) {
    const gateway = new FakeGateway();
    gateway.createResult = { data: result, error: null };

    const response = await createHandler(gateway)(itemRequest({
      url: "https://example.com/article",
    }));

    assertEquals(response.status, result.http_status);
    assertEquals(await response.json(), {
      duplicate: result.duplicate,
      item: result.item,
    });
  }
});

Deno.test("POST items accepts only path-appropriate exact committed conflicts", async () => {
  for (const errorCodeValue of ["ITEM_LIMIT_REACHED", "URL_HASH_COLLISION"]) {
    const gateway = new FakeGateway();
    let scheduled = 0;
    gateway.createResult = {
      data: { http_status: 409, error_code: errorCodeValue },
      error: null,
    };

    const response = await createHandler(gateway, () => scheduled++)(
      itemRequest({
        url: "https://example.com/article",
      }),
    );

    assertEquals(response.status, 409);
    assertEquals(await errorCode(response), errorCodeValue);
    assertEquals(scheduled, 0);
  }

  for (
    const data of [
      { http_status: 409, error_code: "CATEGORY_NAME_EXISTS" },
      { http_status: 409, error_code: "IDEMPOTENCY_MISMATCH" },
      {
        http_status: 409,
        error_code: "ITEM_LIMIT_REACHED",
        item_id: VALID_ITEM_ID,
      },
      { http_status: "409", error_code: "ITEM_LIMIT_REACHED" },
      { http_status: 409 },
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.createResult = { data, error: null };

    const response = await createHandler(gateway)(itemRequest({
      url: "https://example.com/article",
    }));

    assertEquals(response.status, 503, JSON.stringify(data));
    assertEquals(
      await errorCode(response),
      "DEPENDENCY_UNAVAILABLE",
      JSON.stringify(data),
    );
  }
});

Deno.test("GET items forwards a prepared plan and default filters through the caller JWT", async () => {
  for (
    const testCase of [
      { query: "", limit: 20, offset: 0 },
      { query: "?limit=50&offset=7", limit: 50, offset: 7 },
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.rpcResult = {
      data: { items: [{ id: VALID_ITEM_ID }], has_more: true },
      error: null,
    };
    const response = await createHandler(gateway)(request(
      `/library-api/v1/items${testCase.query}`,
      { headers: { authorization: "Bearer caller-access-token" } },
    ));

    assertEquals(response.status, 200);
    assertEquals(await response.json(), gateway.rpcResult.data);
    assertEquals(gateway.rpcCalls, [{
      authorization: "Bearer caller-access-token",
      functionName: "library_search_items",
      args: {
        p_plan: { query: "", terms: [], groups: [] },
        p_filters: {
          unclassified: false,
          aliases: true,
          needs_cues: false,
        },
        p_limit: testCase.limit,
        p_offset: testCase.offset,
      },
    }]);
    assertEquals(gateway.createCalls, []);
  }
});

Deno.test("GET items validates and forwards discovery filters without interpreting SQL text", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: { items: [], has_more: false }, error: null };
  const query = new URLSearchParams({
    q: "x');drop_table",
    category_id: VALID_CATEGORY_ID,
    unclassified: "false",
    source: "threads",
    date_from: "2026-09-12T09:00:00+09:00",
    date_to: "2026-09-13T00:00:00Z",
    aliases: "false",
    needs_cues: "true",
    limit: "7",
    offset: "3",
  });

  const response = await createHandler(gateway)(request(
    `/library-api/v1/items?${query}`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));

  assertEquals(response.status, 200);
  assertEquals(gateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "library_search_items",
    args: {
      p_plan: {
        query: "x');drop_table",
        terms: ["x');drop_table"],
        groups: [{ kind: "literal", value: "x');drop_table" }],
      },
      p_filters: {
        category_id: VALID_CATEGORY_ID,
        unclassified: false,
        source: "threads",
        date_from: "2026-09-12T00:00:00.000Z",
        date_to: "2026-09-13T00:00:00.000Z",
        aliases: false,
        needs_cues: true,
      },
      p_limit: 7,
      p_offset: 3,
    },
  }]);
});

Deno.test("GET items expands sealed aliases and maps query limits", async () => {
  const aliasGateway = new FakeGateway();
  aliasGateway.rpcResult = {
    data: { items: [], has_more: false },
    error: null,
  };
  const aliasResponse = await createHandler(aliasGateway)(request(
    "/library-api/v1/items?q=%EC%B9%B4%ED%86%A1",
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(aliasResponse.status, 200);
  assertEquals(aliasGateway.rpcCalls[0].args.p_plan, {
    query: "카톡",
    terms: ["카톡"],
    groups: [{ kind: "concept", value: "kakaotalk" }],
  });

  const limitGateway = new FakeGateway();
  const queryLimitResponse = await createHandler(limitGateway)(request(
    `/library-api/v1/items?q=${"a".repeat(201)}`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(queryLimitResponse.status, 400);
  assertEquals(await errorCode(queryLimitResponse), "QUERY_LIMIT");
  assertEquals(limitGateway.rpcCalls, []);
});

Deno.test("GET items rejects unknown, contradictory, and malformed filters", async () => {
  const queries = [
    "?category_id=abc",
    `?category_id=${VALID_CATEGORY_ID}&unclassified=true`,
    "?source=tiktok",
    "?unclassified=1",
    "?aliases=True",
    "?needs_cues=yes",
    "?date_from=2026-02-30T00%3A00%3A00Z",
    "?date_to=2026-09-13",
    "?date_from=2026-09-13T00%3A00%3A00Z&date_to=2026-09-13T00%3A00%3A00Z",
    "?future=true",
    "?limit=0",
    "?limit=51",
    "?limit=-1",
    "?limit=1.5",
    "?offset=-1",
    "?offset=2147483648",
    "?limit=20&limit=21",
  ];

  for (const query of queries) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(request(
      `/library-api/v1/items${query}`,
      { headers: { authorization: "Bearer caller-access-token" } },
    ));
    assertEquals(response.status, 400, query);
    assertEquals(await errorCode(response), "INVALID_QUERY");
    assertEquals(gateway.rpcCalls, []);
  }
});

Deno.test("GET item detail validates UUID and uses the caller JWT RPC", async () => {
  const invalidGateway = new FakeGateway();
  const invalidResponse = await createHandler(invalidGateway)(request(
    "/library-api/v1/items/not-a-uuid",
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(invalidResponse.status, 400);
  assertEquals(await errorCode(invalidResponse), "INVALID_BODY");
  assertEquals(invalidGateway.rpcCalls, []);

  const gateway = new FakeGateway();
  gateway.rpcResult = {
    data: { id: VALID_ITEM_ID, active_asset: null },
    error: null,
  };
  const response = await createHandler(gateway)(request(
    `/functions/v1/library-api/v1/items/${VALID_ITEM_ID}`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    ...(gateway.rpcResult.data as Record<string, unknown>),
    classification_explanations: [],
  });
  assertEquals(gateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "library_get_item",
    args: { p_item_id: VALID_ITEM_ID },
  }]);
});

Deno.test("GET item detail explains only current sealed rules and opt-in aliases", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = {
    data: {
      ...itemSnapshot(),
      text_revision: 4,
      rules_version: "rules-v2.0.0",
      classification_reasons: [{
        code: "travel",
        score: 3,
        rules: [{ id: "travel:strong:0", fields: ["fetched_title"] }],
      }],
    },
    error: null,
  };
  const response = await createHandler(gateway)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}?q=%EC%B9%B4%ED%86%A1`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  const payload = await response.json() as Record<string, unknown>;

  assertEquals(response.status, 200);
  assertEquals(payload.classification_explanations, [{
    category_code: "travel",
    rule_id: "travel:strong:0",
    field: "fetched_title",
    expression: "여행",
  }]);
  assertEquals(payload.alias_explanations, [{
    concept_id: "kakaotalk",
    field: "user_title",
    expression: "카톡",
  }]);

  gateway.rpcResult = {
    data: {
      ...itemSnapshot(),
      text_revision: 4,
      rules_version: "rules-v1.0.0",
      classification_reasons: [{
        code: "travel",
        score: 99,
        rules: [{ id: "travel:strong:0", fields: ["fetched_title"] }],
      }],
    },
    error: null,
  };
  const staleResponse = await createHandler(gateway)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  const stalePayload = await staleResponse.json() as Record<string, unknown>;
  assertEquals(stalePayload.classification_explanations, []);
  assertEquals(Object.hasOwn(stalePayload, "alias_explanations"), false);

  const limitedGateway = new FakeGateway();
  const limitedResponse = await createHandler(limitedGateway)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}?q=${"가".repeat(201)}`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(limitedResponse.status, 400);
  assertEquals(await errorCode(limitedResponse), "QUERY_LIMIT");
  assertEquals(limitedGateway.rpcCalls, []);
});

Deno.test("category routes keep reads on caller JWT and writes on verified owner service RPCs", async () => {
  const listGateway = new FakeGateway();
  listGateway.rpcResult = {
    data: { categories: [], count: 0, unclassified_count: 2 },
    error: null,
  };
  const listResponse = await createHandler(listGateway)(request(
    "/library-api/v1/categories",
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(listResponse.status, 200);
  assertEquals(listGateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "library_list_categories",
    args: {},
  }]);
  assertEquals(listGateway.serviceCalls, []);

  const createGateway = new FakeGateway();
  createGateway.serviceResult = {
    data: {
      http_status: 201,
      category: { id: VALID_CATEGORY_ID, name: "  ＴＥＳＴ  " },
    },
    error: null,
  };
  const createResponse = await createHandler(createGateway)(
    discoveryMutationRequest("/library-api/v1/categories", "POST", {
      name: "  ＴＥＳＴ  ",
    }),
  );
  assertEquals(createResponse.status, 201);
  assertEquals(await createResponse.json(), {
    id: VALID_CATEGORY_ID,
    name: "  ＴＥＳＴ  ",
  });
  assertEquals(createGateway.verifiedTokens, ["caller-access-token"]);
  assertEquals(createGateway.serviceCalls, [{
    functionName: "library_create_category",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: {
      p_body: { name: "  ＴＥＳＴ  " },
      p_normalized_name: "test",
    },
  }]);

  const renameGateway = new FakeGateway();
  renameGateway.serviceResult = {
    data: {
      http_status: 200,
      category: { id: VALID_CATEGORY_ID, name: "업무" },
    },
    error: null,
  };
  const renameResponse = await createHandler(renameGateway)(
    discoveryMutationRequest(
      `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      "PATCH",
      { name: "업무" },
    ),
  );
  assertEquals(renameResponse.status, 200);
  assertEquals(renameGateway.serviceCalls[0], {
    functionName: "library_rename_category",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: {
      p_category_id: VALID_CATEGORY_ID,
      p_body: { name: "업무" },
      p_normalized_name: "업무",
    },
  });
});

Deno.test("category mutations strictly validate names, ids, request ids, and empty delete bodies", async () => {
  for (
    const body of [
      null,
      {},
      { name: null },
      { name: "" },
      { name: " \t " },
      { name: "a".repeat(31) },
      { name: "valid", owner_id: VERIFIED_USER_ID },
    ]
  ) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(
      discoveryMutationRequest("/library-api/v1/categories", "POST", body),
    );
    assertEquals(response.status, 400, JSON.stringify(body));
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.serviceCalls, []);
  }

  const missingRequestIdGateway = new FakeGateway();
  const missingRequestIdResponse = await createHandler(missingRequestIdGateway)(
    request("/library-api/v1/categories", {
      method: "POST",
      headers: {
        authorization: "Bearer caller-access-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({ name: "업무" }),
    }),
  );
  assertEquals(missingRequestIdResponse.status, 400);
  assertEquals(await errorCode(missingRequestIdResponse), "INVALID_REQUEST_ID");
  assertEquals(missingRequestIdGateway.serviceCalls, []);

  const invalidIdGateway = new FakeGateway();
  const invalidIdResponse = await createHandler(invalidIdGateway)(
    discoveryMutationRequest(
      "/library-api/v1/categories/not-a-uuid",
      "DELETE",
      {},
    ),
  );
  assertEquals(invalidIdResponse.status, 400);
  assertEquals(invalidIdGateway.serviceCalls, []);

  const deleteGateway = new FakeGateway();
  deleteGateway.serviceResult = {
    data: { http_status: 204 },
    error: null,
  };
  const deleteResponse = await createHandler(deleteGateway)(
    discoveryMutationRequest(
      `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      "DELETE",
      {},
    ),
  );
  assertEquals(deleteResponse.status, 204);
  assertEquals(deleteResponse.headers.get("content-type"), null);
  assertEquals(await deleteResponse.text(), "");
  assertEquals(deleteGateway.serviceCalls, [{
    functionName: "library_delete_category",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: { p_category_id: VALID_CATEGORY_ID, p_body: {} },
  }]);

  const nonEmptyDeleteGateway = new FakeGateway();
  const nonEmptyDeleteResponse = await createHandler(nonEmptyDeleteGateway)(
    discoveryMutationRequest(
      `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      "DELETE",
      { unexpected: true },
    ),
  );
  assertEquals(nonEmptyDeleteResponse.status, 400);
  assertEquals(nonEmptyDeleteGateway.serviceCalls, []);
});

Deno.test("cue dismissal and reclassification use exact owner-scoped bodies and trusted statuses", async () => {
  const cueGateway = new FakeGateway();
  cueGateway.serviceResult = {
    data: { http_status: 200, item: { id: VALID_ITEM_ID, version: 5 } },
    error: null,
  };
  const cueResponse = await createHandler(cueGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/cue-dismiss`,
      "POST",
      { expected_version: 4, text_revision: 3 },
    ),
  );
  assertEquals(cueResponse.status, 200);
  assertEquals(await cueResponse.json(), { id: VALID_ITEM_ID, version: 5 });
  assertEquals(cueGateway.serviceCalls, [{
    functionName: "library_cue_dismiss",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: {
      p_item_id: VALID_ITEM_ID,
      p_body: { expected_version: 4, text_revision: 3 },
    },
  }]);

  let scheduled = 0;
  const reclassifyGateway = new FakeGateway();
  reclassifyGateway.serviceResult = {
    data: {
      http_status: 202,
      job_id: SECOND_CATEGORY_ID,
      item_id: VALID_ITEM_ID,
    },
    error: null,
  };
  const reclassifyResponse = await createHandler(
    reclassifyGateway,
    () => scheduled++,
  )(discoveryMutationRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/reclassify`,
    "POST",
    { expected_version: 5 },
  ));
  assertEquals(reclassifyResponse.status, 202);
  assertEquals(await reclassifyResponse.json(), {
    job_id: SECOND_CATEGORY_ID,
    item_id: VALID_ITEM_ID,
  });
  assertEquals(scheduled, 1);
  assertEquals(reclassifyGateway.verifiedTokens, ["caller-access-token"]);
  assertEquals(reclassifyGateway.serviceCalls[0].ownerId, VERIFIED_USER_ID);

  const unknownFieldGateway = new FakeGateway();
  const unknownFieldResponse = await createHandler(unknownFieldGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/cue-dismiss`,
      "POST",
      {
        expected_version: 4,
        text_revision: 3,
        owner_id: VERIFIED_USER_ID,
      },
    ),
  );
  assertEquals(unknownFieldResponse.status, 400);
  assertEquals(await errorCode(unknownFieldResponse), "INVALID_BODY");
  assertEquals(unknownFieldGateway.serviceCalls, []);
});

Deno.test("metadata retry uses the verified owner, raw request identity, and an exact version body", async () => {
  const gateway = new FakeGateway();
  gateway.serviceResult = {
    data: { http_status: 202, job_id: SECOND_CATEGORY_ID },
    error: null,
  };
  let scheduled = 0;

  const response = await createHandler(gateway, () => scheduled++)(
    discoveryMutationRequest(
      `/functions/v1/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
      "POST",
      { expected_version: 7 },
    ),
  );

  assertEquals(response.status, 202);
  assertEquals(await response.json(), { job_id: SECOND_CATEGORY_ID });
  assertEquals(response.headers.get("x-request-id"), VALID_REQUEST_ID);
  assertEquals(scheduled, 1);
  assertEquals(gateway.verifiedTokens, ["caller-access-token"]);
  assertEquals(gateway.serviceCalls, [{
    functionName: "library_retry_metadata",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: {
      p_item_id: VALID_ITEM_ID,
      p_body: { expected_version: 7 },
    },
  }]);
});

Deno.test("metadata retry routing, request id, query, and body validation are strict", async () => {
  for (
    const body of [
      null,
      {},
      { expected_version: 0 },
      { expected_version: 1.5 },
      { expected_version: 2_147_483_648 },
      { expected_version: 1, owner_id: VERIFIED_USER_ID },
      { expected_version: 1, metadata_state: "queued" },
    ]
  ) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
        "POST",
        body,
      ),
    );
    assertEquals(response.status, 400, JSON.stringify(body));
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.serviceCalls, []);
  }

  for (const requestId of [null, "not-a-uuid"]) {
    const gateway = new FakeGateway();
    const headers = new Headers({
      authorization: "Bearer caller-access-token",
      "content-type": "application/json",
    });
    if (requestId !== null) {
      headers.set("x-request-id", requestId);
    }
    const response = await createHandler(gateway)(request(
      `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
      {
        method: "POST",
        headers,
        body: JSON.stringify({ expected_version: 1 }),
      },
    ));
    assertEquals(response.status, 400);
    assertEquals(await errorCode(response), "INVALID_REQUEST_ID");
    assertEquals(gateway.serviceCalls, []);
  }

  const queryGateway = new FakeGateway();
  const queryResponse = await createHandler(queryGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata?force=true`,
      "POST",
      { expected_version: 1 },
    ),
  );
  assertEquals(queryResponse.status, 400);
  assertEquals(await errorCode(queryResponse), "INVALID_QUERY");
  assertEquals(queryGateway.serviceCalls, []);

  const invalidIdGateway = new FakeGateway();
  const invalidIdResponse = await createHandler(invalidIdGateway)(
    discoveryMutationRequest(
      "/library-api/v1/items/not-a-uuid/retry-metadata",
      "POST",
      { expected_version: 1 },
    ),
  );
  assertEquals(invalidIdResponse.status, 400);
  assertEquals(await errorCode(invalidIdResponse), "INVALID_BODY");
  assertEquals(invalidIdGateway.serviceCalls, []);

  const wrongMethodGateway = new FakeGateway();
  const wrongMethodResponse = await createHandler(wrongMethodGateway)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
    {
      method: "GET",
      headers: { authorization: "Bearer caller-access-token" },
    },
  ));
  assertEquals(wrongMethodResponse.status, 405);
  assertEquals(wrongMethodResponse.headers.get("allow"), "POST");
  assertEquals(wrongMethodGateway.verifiedTokens, []);

  const extraPathGateway = new FakeGateway();
  const extraPathResponse = await createHandler(extraPathGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata/extra`,
      "POST",
      { expected_version: 1 },
    ),
  );
  assertEquals(extraPathResponse.status, 404);
  assertEquals(extraPathGateway.verifiedTokens, []);
});

Deno.test("metadata retry maps only trusted statuses and sticky conflict sentinels", async () => {
  for (
    const testCase of [
      { message: "BETA_ACCESS_REQUIRED", status: 403 },
      { message: "ACCOUNT_DELETING", status: 403 },
      { message: "ITEM_NOT_FOUND", status: 404 },
      { message: "ITEM_DELETED", status: 410 },
      { message: "RATE_LIMITED", status: 429 },
      { message: "METADATA_UNSUPPORTED", status: 409 },
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.serviceResult = {
      data: null,
      error: { code: "P0001", message: testCase.message },
    };
    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
        "POST",
        { expected_version: 4 },
      ),
    );
    assertEquals(response.status, testCase.status, testCase.message);
    assertEquals(await errorCode(response), testCase.message);
    assertEquals(
      response.headers.get("retry-after"),
      testCase.status === 429 ? "60" : null,
    );
  }

  const conflictGateway = new FakeGateway();
  conflictGateway.serviceResult = {
    data: { http_status: 409, error_code: "VERSION_CONFLICT" },
    error: null,
  };
  const conflictResponse = await createHandler(conflictGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
      "POST",
      { expected_version: 3 },
    ),
  );
  assertEquals(conflictResponse.status, 409);
  assertEquals(await errorCode(conflictResponse), "VERSION_CONFLICT");

  for (
    const data of [
      {
        http_status: 409,
        error_code: "VERSION_CONFLICT",
        job_id: SECOND_CATEGORY_ID,
      },
      { http_status: 202, job_id: "not-a-uuid" },
      {
        http_status: 202,
        job_id: SECOND_CATEGORY_ID,
        item_id: VALID_ITEM_ID,
      },
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.serviceResult = { data, error: null };
    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
        "POST",
        { expected_version: 3 },
      ),
    );
    assertEquals(response.status, 503, JSON.stringify(data));
    assertEquals(await errorCode(response), "DEPENDENCY_UNAVAILABLE");
  }

  const unavailableGateway = new FakeGateway();
  unavailableGateway.serviceResult = {
    data: null,
    error: {
      code: "08006",
      message: "database https://private.example/secret failed",
    },
  };
  const unavailableResponse = await createHandler(unavailableGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/retry-metadata`,
      "POST",
      { expected_version: 3 },
    ),
  );
  assertEquals(unavailableResponse.status, 503);
  assertEquals(await unavailableResponse.json(), {
    error: {
      code: "DEPENDENCY_UNAVAILABLE",
      message: "A required service is temporarily unavailable.",
      retryable: true,
    },
    request_id: VALID_REQUEST_ID,
  });
});

Deno.test("discovery mutations map exact committed conflicts and category policy sentinels", async () => {
  const conflictGateway = new FakeGateway();
  conflictGateway.serviceResult = {
    data: { http_status: 409, error_code: "VERSION_CONFLICT" },
    error: null,
  };
  const conflictResponse = await createHandler(conflictGateway)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}/cue-dismiss`,
      "POST",
      { expected_version: 4, text_revision: 3 },
    ),
  );
  assertEquals(conflictResponse.status, 409);
  assertEquals(await errorCode(conflictResponse), "VERSION_CONFLICT");

  for (
    const testCase of [
      {
        path: "/library-api/v1/categories",
        method: "POST",
        body: { name: "중복" },
        code: "CATEGORY_NAME_EXISTS",
      },
      {
        path: "/library-api/v1/categories",
        method: "POST",
        body: { name: "한도" },
        code: "CATEGORY_LIMIT_REACHED",
      },
      {
        path: `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
        method: "PATCH",
        body: { name: "중복" },
        code: "CATEGORY_NAME_EXISTS",
      },
    ] as const
  ) {
    const gateway = new FakeGateway();
    gateway.serviceResult = {
      data: { http_status: 409, error_code: testCase.code },
      error: null,
    };
    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        testCase.path,
        testCase.method,
        testCase.body,
      ),
    );
    assertEquals(response.status, 409);
    assertEquals(await errorCode(response), testCase.code);
  }

  for (
    const testCase of [
      { code: "SYSTEM_CATEGORY_READONLY", status: 403 },
      { code: "CATEGORY_LIMIT_REACHED", status: 409 },
      { code: "CATEGORY_NAME_EXISTS", status: 409 },
      { code: "CATEGORY_NOT_FOUND", status: 404 },
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.serviceResult = {
      data: null,
      error: { code: "P0001", message: testCase.code },
    };
    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
        "PATCH",
        { name: "새 이름" },
      ),
    );
    assertEquals(response.status, testCase.status);
    assertEquals(await errorCode(response), testCase.code);
  }
});

Deno.test("category mutations reject malformed and path-inappropriate committed conflicts", async () => {
  const cases = [
    {
      path: "/library-api/v1/categories",
      method: "POST",
      body: { name: "새 분류" },
      data: { http_status: 409, error_code: "VERSION_CONFLICT" },
    },
    {
      path: `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      method: "PATCH",
      body: { name: "새 분류" },
      data: { http_status: 409, error_code: "CATEGORY_LIMIT_REACHED" },
    },
    {
      path: `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      method: "PATCH",
      body: { name: "새 분류" },
      data: {
        http_status: 409,
        error_code: "CATEGORY_NAME_EXISTS",
        category_id: VALID_CATEGORY_ID,
      },
    },
    {
      path: `/library-api/v1/categories/${VALID_CATEGORY_ID}`,
      method: "DELETE",
      body: {},
      data: { http_status: 409, error_code: "CATEGORY_NAME_EXISTS" },
    },
  ] as const;

  for (const testCase of cases) {
    const gateway = new FakeGateway();
    gateway.serviceResult = { data: testCase.data, error: null };

    const response = await createHandler(gateway)(
      discoveryMutationRequest(
        testCase.path,
        testCase.method,
        testCase.body,
      ),
    );

    assertEquals(response.status, 503, JSON.stringify(testCase.data));
    assertEquals(
      await errorCode(response),
      "DEPENDENCY_UNAVAILABLE",
      JSON.stringify(testCase.data),
    );
  }
});

Deno.test("PATCH item requires a positive expected version and one editable field", async () => {
  const invalidBodies: unknown[] = [
    {},
    { expected_version: null, title: "new" },
    { expected_version: 0, title: "new" },
    { expected_version: -1, title: "new" },
    { expected_version: 1.5, title: "new" },
    { expected_version: 2_147_483_648, title: "new" },
    { expected_version: 1 },
    { expected_version: 1, category_ids: null },
    { expected_version: 1, category_ids: "not-an-array" },
    { expected_version: 1, title: [] },
    { expected_version: 1, note: {} },
  ];

  for (const body of invalidBodies) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(updateRequest(body));
    assertEquals(response.status, 400, JSON.stringify(body));
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.rpcCalls, []);
    assertEquals(gateway.updateCalls, []);
  }
});

Deno.test("PATCH item rejects immutable, identity, and prepared fields", async () => {
  for (
    const field of [
      "url",
      "shared_text",
      "owner_id",
      "owner",
      "prepared",
      "normalized_fields",
    ]
  ) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(updateRequest({
      expected_version: 1,
      title: "new",
      [field]: "forbidden",
    }));

    assertEquals(response.status, 400, field);
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.rpcCalls, []);
    assertEquals(gateway.updateCalls, []);
  }
});

Deno.test("PATCH item distinguishes null, string, and omitted text fields", async () => {
  const cases = [
    {
      body: { expected_version: 4, title: null },
      title: "",
      note: "기존 안드로이드 메모",
    },
    {
      body: { expected_version: 4, title: "새 엑셀 제목" },
      title: "새 엑셀 제목",
      note: "기존 안드로이드 메모",
    },
    {
      body: { expected_version: 4, note: null },
      title: "기존 카톡 제목",
      note: "",
    },
  ];

  for (const testCase of cases) {
    const gateway = new FakeGateway();
    gateway.rpcResult = { data: itemSnapshot(), error: null };

    const response = await createHandler(gateway)(updateRequest(testCase.body));

    assertEquals(response.status, 200);
    assertEquals(gateway.updateCalls.length, 1);
    const call = gateway.updateCalls[0];
    assertEquals(call.body, testCase.body);
    assertEquals(
      call.prepared?.normalized_fields.user_title,
      testCase.title,
    );
    assertEquals(call.prepared?.normalized_fields.note, testCase.note);
    assertEquals(
      call.prepared?.normalized_fields.categories,
      "업무 자료",
    );
    assertEquals(gateway.categoryCalls, []);
  }
});

Deno.test("PATCH preparation rebuilds every source and removes stale aliases", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: itemSnapshot(), error: null };

  const response = await createHandler(gateway)(updateRequest({
    expected_version: 4,
    title: null,
  }));

  assertEquals(response.status, 200);
  const prepared = gateway.updateCalls[0].prepared!;
  assertEquals(prepared.snapshot_version, 4);
  assertEquals(
    prepared.normalized_url,
    "https://blog.naver.com/PostView.naver?Keep=A#part",
  );
  assertEquals(prepared.source, "naver_blog");
  assertEquals(prepared.display_fallback, "공유 원문 첫 줄");
  assertEquals(prepared.metadata_allowed, true);
  assertEquals(prepared.normalized_fields, {
    user_title: "",
    fetched_title: "확보한 여행 제목",
    note: "기존 안드로이드 메모",
    ocr: "사진 속 쇼핑 정보",
    shared: "공유 원문 첫 줄 둘째 줄",
    description: "설명 텍스트",
    body: "본문 텍스트",
    categories: "업무 자료",
    url: "https://blog.naver.com/postview.naver?keep=a#part",
  });
  assertEquals(prepared.alias_concepts.user_title, []);
  assertEquals(prepared.alias_concepts.note, ["android"]);
  assertEquals(prepared.cue_state, "available");
  assertEquals(prepared.cue_flags, ["truncated"]);
});

Deno.test("PATCH item resolves selected category names as the verified owner in request order", async () => {
  const gateway = new FakeGateway();
  const verifiedOwnerId = "37c657a6-23db-4dce-8839-3390b3898343";
  gateway.verification = {
    status: "verified",
    userId: verifiedOwnerId,
  };
  gateway.rpcResult = { data: itemSnapshot(), error: null };
  gateway.categoryResult = {
    data: [
      { id: SECOND_CATEGORY_ID, name: "둘째" },
      { id: VALID_CATEGORY_ID, name: "첫째" },
    ],
    error: null,
  };
  gateway.updateResult = {
    data: {
      http_status: 200,
      item: { id: VALID_ITEM_ID, version: 5, user_title: "기존 카톡 제목" },
    },
    error: null,
  };

  const response = await createHandler(gateway)(updateRequest({
    expected_version: 4,
    category_ids: [VALID_CATEGORY_ID, SECOND_CATEGORY_ID],
  }));

  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    id: VALID_ITEM_ID,
    version: 5,
    user_title: "기존 카톡 제목",
  });
  assertEquals(gateway.categoryCalls, [{
    ownerId: verifiedOwnerId,
    categoryIds: [VALID_CATEGORY_ID, SECOND_CATEGORY_ID],
  }]);
  assertEquals(
    gateway.updateCalls[0].prepared?.normalized_fields.categories,
    "첫째 둘째",
  );
  assertEquals(gateway.updateCalls[0].ownerId, verifiedOwnerId);
  assertEquals(gateway.updateCalls[0].itemId, VALID_ITEM_ID);
  assertEquals(gateway.updateCalls[0].requestId, VALID_REQUEST_ID);
});

Deno.test("PATCH item treats an explicit empty category array as a direct clear", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: itemSnapshot(), error: null };

  const response = await createHandler(gateway)(updateRequest({
    expected_version: 4,
    category_ids: [],
  }));

  assertEquals(response.status, 200);
  assertEquals(gateway.categoryCalls, []);
  assertEquals(
    gateway.updateCalls[0].prepared?.normalized_fields.categories,
    "",
  );
  assertEquals(gateway.updateCalls[0].body.category_ids, []);
});

Deno.test("PATCH item rejects category ids absent from the owned lookup", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: itemSnapshot(), error: null };
  gateway.categoryResult = {
    data: [{ id: VALID_CATEGORY_ID, name: "첫째" }],
    error: null,
  };

  const response = await createHandler(gateway)(updateRequest({
    expected_version: 4,
    category_ids: [VALID_CATEGORY_ID, SECOND_CATEGORY_ID],
  }));

  assertEquals(response.status, 400);
  assertEquals(await errorCode(response), "INVALID_CATEGORY_IDS");
  assertEquals(gateway.updateCalls, []);
});

Deno.test("PATCH forwards stale expected versions and missing snapshots to the trusted update RPC", async () => {
  const staleGateway = new FakeGateway();
  staleGateway.rpcResult = {
    data: { ...itemSnapshot(), version: 9 },
    error: null,
  };
  const staleResponse = await createHandler(staleGateway)(updateRequest({
    expected_version: 4,
    note: "재전송",
  }));

  assertEquals(staleResponse.status, 200);
  assertEquals(staleGateway.updateCalls[0].body.expected_version, 4);
  assertEquals(staleGateway.updateCalls[0].prepared?.snapshot_version, 9);

  const missingGateway = new FakeGateway();
  missingGateway.rpcResult = {
    data: null,
    error: { code: "P0001", message: "ITEM_NOT_FOUND" },
  };
  missingGateway.updateResult = {
    data: null,
    error: { code: "P0001", message: "ITEM_DELETED" },
  };
  const missingResponse = await createHandler(missingGateway)(updateRequest({
    expected_version: 4,
    note: "재전송",
  }));

  assertEquals(missingResponse.status, 410);
  assertEquals(await errorCode(missingResponse), "ITEM_DELETED");
  assertEquals(missingGateway.categoryCalls, []);
  assertEquals(missingGateway.updateCalls.length, 1);
  assertEquals(missingGateway.updateCalls[0].prepared, null);
});

Deno.test("PATCH maps an exact committed version-conflict result to the normal error envelope", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = { data: itemSnapshot(), error: null };
  gateway.updateResult = {
    data: { http_status: 409, error_code: "VERSION_CONFLICT" },
    error: null,
  };

  const response = await createHandler(gateway)(updateRequest({
    expected_version: 3,
    note: "충돌한 메모",
  }));

  assertEquals(response.status, 409);
  assertEquals(await response.json(), {
    error: {
      code: "VERSION_CONFLICT",
      message: "The item changed before this update was applied.",
      retryable: false,
    },
    request_id: VALID_REQUEST_ID,
  });
  assertEquals(gateway.updateCalls.length, 1);
});

Deno.test("PATCH rejects malformed committed conflict results as dependency failures", async () => {
  const malformedResults: unknown[] = [
    {
      http_status: 409,
      error_code: "VERSION_CONFLICT",
      item_id: VALID_ITEM_ID,
    },
    { http_status: 409, error_code: "IDEMPOTENCY_MISMATCH" },
    { http_status: 409 },
    { http_status: "409", error_code: "VERSION_CONFLICT" },
  ];

  for (const data of malformedResults) {
    const gateway = new FakeGateway();
    gateway.rpcResult = { data: itemSnapshot(), error: null };
    gateway.updateResult = { data, error: null };

    const response = await createHandler(gateway)(updateRequest({
      expected_version: 3,
      note: "충돌한 메모",
    }));

    assertEquals(response.status, 503, JSON.stringify(data));
    assertEquals(
      await errorCode(response),
      "DEPENDENCY_UNAVAILABLE",
      JSON.stringify(data),
    );
  }
});

Deno.test("PATCH item enforces request ids and its 64 KiB streamed limit", async () => {
  for (const suppliedId of [null, "not-a-uuid"]) {
    const invalidIdGateway = new FakeGateway();
    const headers = new Headers({
      authorization: "Bearer caller-access-token",
      "content-type": "application/json",
    });
    if (suppliedId !== null) {
      headers.set("x-request-id", suppliedId);
    }
    const invalidIdResponse = await createHandler(invalidIdGateway)(request(
      `/library-api/v1/items/${VALID_ITEM_ID}`,
      {
        method: "PATCH",
        headers,
        body: JSON.stringify({ expected_version: 1, title: "new" }),
      },
    ));
    assertEquals(invalidIdResponse.status, 400);
    assertEquals(await errorCode(invalidIdResponse), "INVALID_REQUEST_ID");
    assertEquals(invalidIdGateway.rpcCalls, []);
  }

  const oversizedGateway = new FakeGateway();
  const oversizedResponse = await createHandler(oversizedGateway)(
    new Request(
      `https://example.test/library-api/v1/items/${VALID_ITEM_ID}`,
      {
        method: "PATCH",
        headers: authenticatedJsonHeaders(),
        body: byteStream(40_000, 30_000),
      },
    ),
  );
  assertEquals(oversizedResponse.status, 413);
  assertEquals(await errorCode(oversizedResponse), "PAYLOAD_TOO_LARGE");
  assertEquals(oversizedGateway.rpcCalls, []);
  assertEquals(oversizedGateway.updateCalls, []);
});

Deno.test("asset reservation uses only verified owner identity and returns flat fields", async () => {
  const gateway = new FakeGateway();
  const response = await createHandler(gateway)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/reserve`,
    "POST",
    { expected_version: 4, mime_type: "image/png" },
  ));

  assertEquals(response.status, 201);
  assertEquals(await response.json(), {
    asset_id: VALID_ASSET_ID,
    object_path: `${VERIFIED_USER_ID}/${VALID_ITEM_ID}/${VALID_ASSET_ID}`,
    expires_at: "2026-09-15T12:00:00Z",
    max_bytes: 2_000_000,
  });
  assertEquals(gateway.reserveAssetCalls, [{
    ownerId: VERIFIED_USER_ID,
    itemId: VALID_ITEM_ID,
    requestId: VALID_REQUEST_ID,
    body: { expected_version: 4, mime_type: "image/png" },
  }]);
});

Deno.test("asset mutation bodies reject client identity, paths, dimensions, and invalid OCR states", async () => {
  const cases = [
    {
      path: `/library-api/v1/items/${VALID_ITEM_ID}/assets/reserve`,
      method: "POST" as const,
      body: {
        expected_version: 4,
        mime_type: "image/png",
        owner_id: VERIFIED_USER_ID,
      },
    },
    {
      path:
        `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/complete`,
      method: "POST" as const,
      body: {
        expected_version: 4,
        ocr_state: "ready",
        ocr_text: "text",
        object_path: "attacker/path",
      },
    },
    {
      path:
        `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/complete`,
      method: "POST" as const,
      body: {
        expected_version: 4,
        ocr_state: "ready",
        ocr_text: "text",
        width: 1,
        height: 1,
      },
    },
    {
      path:
        `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/ocr`,
      method: "PATCH" as const,
      body: {
        expected_version: 4,
        ocr_state: "not_requested",
      },
    },
    {
      path:
        `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/ocr`,
      method: "PATCH" as const,
      body: {
        expected_version: 4,
        ocr_state: "failed",
        ocr_truncated: true,
      },
    },
  ];

  for (const testCase of cases) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(assetRequest(
      testCase.path,
      testCase.method,
      testCase.body,
    ));
    assertEquals(response.status, 400);
    assertEquals(await errorCode(response), "INVALID_BODY");
    assertEquals(gateway.reserveAssetCalls, []);
    assertEquals(gateway.replayAssetCalls, []);
    assertEquals(gateway.updateAssetOcrCalls, []);
  }
});

Deno.test("complete asset trusts a strict replay before object download and unwraps the item", async () => {
  const gateway = new FakeGateway();
  gateway.replayAssetResult = {
    data: {
      found: true,
      http_status: 200,
      item: {
        id: VALID_ITEM_ID,
        version: 5,
        active_asset: { id: VALID_ASSET_ID },
      },
    },
    error: null,
  };
  const body = {
    expected_version: 4,
    ocr_state: "ready",
    ocr_text: "OCR text",
    ocr_truncated: true,
  };
  const response = await createHandler(gateway)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/complete`,
    "POST",
    body,
  ));

  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    id: VALID_ITEM_ID,
    version: 5,
    active_asset: { id: VALID_ASSET_ID },
  });
  assertEquals(gateway.replayAssetCalls, [{
    ownerId: VERIFIED_USER_ID,
    itemId: VALID_ITEM_ID,
    assetId: VALID_ASSET_ID,
    requestId: VALID_REQUEST_ID,
    method: "POST",
    path: `/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/complete`,
    body,
  }]);
  assertEquals(gateway.lookupAssetCalls, []);
  assertEquals(gateway.downloadAssetCalls, []);
  assertEquals(gateway.completeAssetCalls, []);
});

Deno.test("asset sticky conflicts accept only exact route sentinels", async () => {
  const sticky = new FakeGateway();
  sticky.reserveAssetResult = {
    data: { http_status: 409, error_code: "STORAGE_LIMIT_REACHED" },
    error: null,
  };
  const stickyResponse = await createHandler(sticky)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/reserve`,
    "POST",
    { expected_version: 4, mime_type: "image/jpeg" },
  ));
  assertEquals(stickyResponse.status, 409);
  assertEquals(await errorCode(stickyResponse), "STORAGE_LIMIT_REACHED");

  const malformed = new FakeGateway();
  malformed.reserveAssetResult = {
    data: {
      http_status: 409,
      error_code: "STORAGE_LIMIT_REACHED",
      injected: true,
    },
    error: null,
  };
  const malformedResponse = await createHandler(malformed)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/reserve`,
    "POST",
    { expected_version: 4, mime_type: "image/jpeg" },
  ));
  assertEquals(malformedResponse.status, 503);
  assertEquals(await errorCode(malformedResponse), "DEPENDENCY_UNAVAILABLE");
});

Deno.test("asset OCR and delete rebuild the index and expose only public result shapes", async () => {
  const ocrGateway = new FakeGateway();
  ocrGateway.rpcResult = { data: itemSnapshot(), error: null };
  const ocrResponse = await createHandler(ocrGateway)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/ocr`,
    "PATCH",
    {
      expected_version: 4,
      ocr_state: "ready",
      ocr_text: "엑셀 작업표",
      ocr_truncated: false,
    },
  ));
  assertEquals(ocrResponse.status, 200);
  assertEquals(await ocrResponse.json(), {
    id: VALID_ITEM_ID,
    version: 2,
  });
  assertEquals(ocrGateway.updateAssetOcrCalls.length, 1);
  assertEquals(
    ocrGateway.updateAssetOcrCalls[0].preparedIndex!.alias_concepts.ocr,
    ["excel"],
  );

  const deleteGateway = new FakeGateway();
  deleteGateway.rpcResult = { data: itemSnapshot(), error: null };
  const deleteResponse = await createHandler(deleteGateway)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}`,
    "DELETE",
    { expected_version: 4 },
  ));
  assertEquals(deleteResponse.status, 202);
  assertEquals(await deleteResponse.json(), { asset_id: VALID_ASSET_ID });
  assertEquals(deleteGateway.deleteAssetCalls.length, 1);
  assertEquals(
    deleteGateway.deleteAssetCalls[0].preparedIndex!.normalized_fields.ocr,
    "",
  );
});

Deno.test("asset mutators let the atomic service transaction distinguish a deleted item", async () => {
  const gateway = new FakeGateway();
  gateway.rpcResult = {
    data: null,
    error: { code: "P0001", message: "ITEM_NOT_FOUND" },
  };
  gateway.deleteAssetResult = {
    data: null,
    error: { code: "P0001", message: "ITEM_DELETED" },
  };
  const response = await createHandler(gateway)(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}`,
    "DELETE",
    { expected_version: 4 },
  ));

  assertEquals(response.status, 410);
  assertEquals(await errorCode(response), "ITEM_DELETED");
  assertEquals(gateway.deleteAssetCalls.length, 1);
  assertEquals(gateway.deleteAssetCalls[0].preparedIndex, null);
});

Deno.test("active asset content is caller-authorized binary with stored MIME and hardening headers", async () => {
  const unauthenticated = await createHandler(new FakeGateway())(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/content`,
  ));
  assertEquals(unauthenticated.status, 401);

  const gateway = new FakeGateway();
  gateway.rpcResult = { data: itemSnapshot(), error: null };
  const response = await createHandler(gateway)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/content`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(response.status, 200);
  assertEquals(
    new Uint8Array(await response.arrayBuffer()),
    new Uint8Array([1, 2, 3, 4]),
  );
  assertEquals(response.headers.get("content-type"), "image/png");
  assertEquals(response.headers.get("cache-control"), "no-store");
  assertEquals(response.headers.get("x-content-type-options"), "nosniff");
  assertEquals(gateway.rpcCalls[0].functionName, "library_get_item");
  assertEquals(gateway.lookupAssetCalls, [{
    ownerId: VERIFIED_USER_ID,
    itemId: VALID_ITEM_ID,
    assetId: VALID_ASSET_ID,
  }]);

  const reserved = new FakeGateway();
  reserved.rpcResult = {
    data: { ...itemSnapshot(), active_asset: null },
    error: null,
  };
  const denied = await createHandler(reserved)(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/content`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(denied.status, 404);
  assertEquals(await errorCode(denied), "ASSET_NOT_FOUND");
  assertEquals(reserved.lookupAssetCalls, []);
});

Deno.test("asset routes enforce UUIDs, exact methods, no query, and the OCR body cap", async () => {
  const handler = createHandler(new FakeGateway());
  const invalidItem = await handler(assetRequest(
    `/library-api/v1/items/not-a-uuid/assets/reserve`,
    "POST",
    { expected_version: 1, mime_type: "image/png" },
  ));
  assertEquals(invalidItem.status, 400);
  const invalidAsset = await handler(assetRequest(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/not-a-uuid/ocr`,
    "PATCH",
    { expected_version: 1, ocr_state: "failed" },
  ));
  assertEquals(invalidAsset.status, 400);
  const wrongMethod = await handler(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/content`,
    { method: "POST" },
  ));
  assertEquals(wrongMethod.status, 405);
  assertEquals(wrongMethod.headers.get("allow"), "GET");
  const query = await handler(request(
    `/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/content?download=1`,
    { headers: { authorization: "Bearer caller-access-token" } },
  ));
  assertEquals(query.status, 400);
  assertEquals(await errorCode(query), "INVALID_QUERY");

  const oversized = await handler(
    new Request(
      `https://example.test/library-api/v1/items/${VALID_ITEM_ID}/assets/${VALID_ASSET_ID}/ocr`,
      {
        method: "PATCH",
        headers: authenticatedJsonHeaders(),
        body: byteStream(70_000, 70_000),
      },
    ),
  );
  assertEquals(oversized.status, 413);
  assertEquals(await errorCode(oversized), "PAYLOAD_TOO_LARGE");
});

Deno.test("RPC policy failures map to stable statuses and rate limits carry Retry-After", async () => {
  const cases = [
    { message: "UNAUTHENTICATED", status: 401 },
    { message: "BETA_ACCESS_REQUIRED", status: 403 },
    { message: "ACCOUNT_DELETING", status: 403 },
    { message: "ITEM_NOT_FOUND", status: 404 },
    { message: "INVALID_REQUEST_ID", status: 400 },
    { message: "INVALID_BODY", status: 400 },
    { message: "INVALID_PREPARED", status: 400 },
    { message: "INVALID_CATEGORY_IDS", status: 400 },
    { message: "CATEGORY_LIMIT_REACHED", status: 409 },
    { message: "CATEGORY_NORMALIZATION_REQUIRED", status: 503 },
    { message: "ITEM_LIMIT_REACHED", status: 409 },
    { message: "IDEMPOTENCY_MISMATCH", status: 409 },
    { message: "URL_HASH_COLLISION", status: 409 },
    { message: "VERSION_CONFLICT", status: 409 },
    { message: "ITEM_DELETED", status: 410 },
    { message: "RATE_LIMITED", status: 429 },
  ];

  for (const testCase of cases) {
    const gateway = new FakeGateway();
    gateway.createResult = {
      data: null,
      error: { code: "P0001", message: testCase.message },
    };

    const response = await createHandler(gateway)(itemRequest({
      url: "https://example.com/article",
    }));

    assertEquals(response.status, testCase.status, testCase.message);
    assertEquals(await errorCode(response), testCase.message);
    assertEquals(
      response.headers.get("retry-after"),
      testCase.status === 429 ? "60" : null,
    );
  }
});

Deno.test("unexpected RPC failures and malformed successes are dependency failures", async () => {
  const rpcFailureGateway = new FakeGateway();
  rpcFailureGateway.rpcResult = {
    data: null,
    error: { code: "08006", message: "database offline" },
  };
  const rpcFailureResponse = await createHandler(rpcFailureGateway)(
    authenticatedMeRequest(),
  );
  assertEquals(rpcFailureResponse.status, 503);
  assertEquals(await errorCode(rpcFailureResponse), "DEPENDENCY_UNAVAILABLE");

  const malformedGateway = new FakeGateway();
  malformedGateway.createResult = {
    data: {
      http_status: 202,
      duplicate: false,
      item: { id: VALID_ITEM_ID },
    },
    error: null,
  };
  const malformedResponse = await createHandler(malformedGateway)(itemRequest({
    url: "https://example.com",
  }));
  assertEquals(malformedResponse.status, 503);
  assertEquals(await errorCode(malformedResponse), "DEPENDENCY_UNAVAILABLE");
});

class FakeClassificationGateway implements ClassificationGateway {
  claimResult: WorkerRpcResult = {
    data: { jobs: [classificationJob()] },
    error: null,
  };
  completeResults: WorkerRpcResult[] = [{
    data: { state: "succeeded" },
    error: null,
  }];
  failResult: WorkerRpcResult = {
    data: { state: "retry" },
    error: null,
  };
  claimedLimits: number[] = [];
  completeCalls: CompleteClassificationCall[] = [];
  failCalls: FailClassificationCall[] = [];

  claimClassificationJobs(limit: number): Promise<WorkerRpcResult> {
    this.claimedLimits.push(limit);
    return Promise.resolve(this.claimResult);
  }

  completeClassificationJob(
    call: CompleteClassificationCall,
  ): Promise<WorkerRpcResult> {
    this.completeCalls.push(call);
    return Promise.resolve(
      this.completeResults.shift() ?? {
        data: { state: "discarded" },
        error: null,
      },
    );
  }

  failClassificationJob(
    call: FailClassificationCall,
  ): Promise<WorkerRpcResult> {
    this.failCalls.push(call);
    return Promise.resolve(this.failResult);
  }
}

Deno.test("classification worker claims snapshots and retries version-only conflicts in memory", async () => {
  const gateway = new FakeClassificationGateway();
  gateway.completeResults = [
    {
      data: {
        state: "version_conflict",
        item: { ...classificationJob().item, version: 5, note: "새 업무 메모" },
      },
      error: null,
    },
    { data: { state: "succeeded" }, error: null },
  ];

  const result = await runClassificationBatch(gateway, 7);

  assertEquals(gateway.claimedLimits, [7]);
  assertEquals(gateway.completeCalls.length, 2);
  assertEquals(
    gateway.completeCalls.map((call) => call.expectedVersion),
    [4, 5],
  );
  assertEquals(gateway.completeCalls[0].result.target_revision, 3);
  assertEquals(gateway.completeCalls[0].result.rules_version, "rules-v2.0.0");
  assertEquals(result, {
    claimed: 1,
    succeeded: 1,
    discarded: 0,
    retry_scheduled: 0,
    failed: 0,
    version_conflicts: 1,
    error_codes: [],
  });
});

Deno.test("classification worker treats discarded leases as normal and reports transient failures lease-aware", async () => {
  const discardedGateway = new FakeClassificationGateway();
  discardedGateway.completeResults = [{
    data: { state: "discarded" },
    error: null,
  }];
  const discarded = await runClassificationBatch(discardedGateway, 1);
  assertEquals(discarded.discarded, 1);
  assertEquals(discarded.error_codes, []);
  assertEquals(discardedGateway.failCalls, []);

  const transientGateway = new FakeClassificationGateway();
  transientGateway.completeResults = [{
    data: null,
    error: { code: "08006", message: "connection unavailable" },
  }];
  const transient = await runClassificationBatch(transientGateway, 1);
  assertEquals(transient.retry_scheduled, 1);
  assertEquals(transient.error_codes, ["INTERNAL_ERROR"]);
  assertEquals(transientGateway.failCalls, [{
    jobId: VALID_ITEM_ID,
    leaseToken: VALID_REQUEST_ID,
    retryable: true,
    errorCode: "INTERNAL_ERROR",
  }]);
});

Deno.test("internal classification endpoint rejects member tokens and accepts only exact service auth", async () => {
  const gateway = new FakeClassificationGateway();
  gateway.claimResult = { data: { jobs: [] }, error: null };
  const handler = createClassificationHandler(gateway, "service-secret");

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
    const response = await handler(request("/v1/internal/classify", {
      method: "POST",
      headers,
      body: JSON.stringify({ limit: 1 }),
    }));
    assertEquals(response.status, 401);
    assertEquals(await response.json(), { error_code: "UNAUTHENTICATED" });
  }
  assertEquals(gateway.claimedLimits, []);

  const response = await handler(request("/v1/internal/classify", {
    method: "POST",
    headers: {
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    },
    body: JSON.stringify({ limit: 20 }),
  }));
  assertEquals(response.status, 200);
  assertEquals(gateway.claimedLimits, [20]);
  const payload = await response.json() as Record<string, unknown>;
  assertEquals(payload.claimed, 0);

  const invalidResponse = await handler(request("/v1/internal/classify", {
    method: "POST",
    headers: {
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    },
    body: JSON.stringify({ limit: 21, token: "not-accepted" }),
  }));
  assertEquals(invalidResponse.status, 400);
  assertEquals(await invalidResponse.json(), { error_code: "INVALID_BODY" });
});

Deno.test("category raw character limits do not truncate expanding Unicode normalization", async () => {
  for (
    const [name, normalized] of [
      ["ﷺ".repeat(30), "صلى الله عليه وسلم".repeat(30)],
      ["İ".repeat(30), "i̇".repeat(30)],
    ]
  ) {
    const gateway = new FakeGateway();
    gateway.serviceResult = {
      data: { http_status: 201, category: { id: VALID_CATEGORY_ID, name } },
      error: null,
    };
    const response = await createHandler(gateway)(
      discoveryMutationRequest("/library-api/v1/categories", "POST", { name }),
    );
    assertEquals(response.status, 201);
    assertEquals(gateway.serviceCalls[0].args.p_body, { name });
    assertEquals(
      gateway.serviceCalls[0].args.p_normalized_name,
      normalized,
    );
  }
});

Deno.test("item DELETE is versioned, owner-scoped, and returns the frozen receipt", async () => {
  const gateway = new FakeGateway();
  gateway.serviceResult = {
    data: {
      http_status: 202,
      item_id: VALID_ITEM_ID,
      state: "deleting",
    },
    error: null,
  };
  let scheduled = 0;
  const response = await createHandler(gateway, () => scheduled++)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}`,
      "DELETE",
      { expected_version: 4 },
    ),
  );

  assertEquals(response.status, 202);
  assertEquals(await response.json(), {
    item_id: VALID_ITEM_ID,
    state: "deleting",
  });
  assertEquals(gateway.serviceCalls, [{
    functionName: "library_delete_item",
    ownerId: VERIFIED_USER_ID,
    requestId: VALID_REQUEST_ID,
    args: {
      p_item_id: VALID_ITEM_ID,
      p_body: { expected_version: 4 },
    },
  }]);
  assertEquals(scheduled, 1);
});

Deno.test("item DELETE rejects query, malformed bodies, and missing request identity", async () => {
  for (
    const testCase of [
      {
        path: `/library-api/v1/items/${VALID_ITEM_ID}?force=true`,
        headers: authenticatedJsonHeaders(),
        body: { expected_version: 4 },
        code: "INVALID_QUERY",
      },
      {
        path: `/library-api/v1/items/${VALID_ITEM_ID}`,
        headers: authenticatedJsonHeaders(),
        body: { expected_version: 4, force: true },
        code: "INVALID_BODY",
      },
      {
        path: `/library-api/v1/items/${VALID_ITEM_ID}`,
        headers: new Headers({
          authorization: "Bearer caller-access-token",
          "content-type": "application/json",
        }),
        body: { expected_version: 4 },
        code: "INVALID_REQUEST_ID",
      },
    ]
  ) {
    const gateway = new FakeGateway();
    const response = await createHandler(gateway)(request(testCase.path, {
      method: "DELETE",
      headers: testCase.headers,
      body: JSON.stringify(testCase.body),
    }));
    assertEquals(response.status, 400);
    assertEquals(await errorCode(response), testCase.code);
    assertEquals(gateway.serviceCalls, []);
  }
});

Deno.test("item DELETE preserves sticky conflict and hidden not-found sentinels", async () => {
  const conflict = new FakeGateway();
  conflict.serviceResult = {
    data: { http_status: 409, error_code: "VERSION_CONFLICT" },
    error: null,
  };
  const conflictResponse = await createHandler(conflict)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}`,
      "DELETE",
      { expected_version: 3 },
    ),
  );
  assertEquals(conflictResponse.status, 409);
  assertEquals(await errorCode(conflictResponse), "VERSION_CONFLICT");

  const hidden = new FakeGateway();
  hidden.serviceResult = {
    data: null,
    error: { code: "P0001", message: "ITEM_NOT_FOUND" },
  };
  const hiddenResponse = await createHandler(hidden)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}`,
      "DELETE",
      { expected_version: 4 },
    ),
  );
  assertEquals(hiddenResponse.status, 404);
  assertEquals(await errorCode(hiddenResponse), "ITEM_NOT_FOUND");

  const mismatchedReceipt = new FakeGateway();
  mismatchedReceipt.serviceResult = {
    data: {
      http_status: 202,
      item_id: VALID_CATEGORY_ID,
      state: "deleting",
    },
    error: null,
  };
  const mismatchedResponse = await createHandler(mismatchedReceipt)(
    discoveryMutationRequest(
      `/library-api/v1/items/${VALID_ITEM_ID}`,
      "DELETE",
      { expected_version: 4 },
    ),
  );
  assertEquals(mismatchedResponse.status, 503);
  assertEquals(await errorCode(mismatchedResponse), "DEPENDENCY_UNAVAILABLE");
});

Deno.test("account deletion uses the trusted Auth user and unwrapped responses", async () => {
  const gateway = accountVerifiedGateway("google-subject");
  const accountGateway = new FakeAccountDeletionGateway();
  let scheduled = 0;
  const handler = createHandler(
    gateway,
    () => scheduled++,
    fakeAccountDeletionService(accountGateway, "google-subject"),
  );

  const challenge = await handler(request(
    "/library-api/v1/account/delete-challenge",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: "{}",
    },
  ));
  assertEquals(challenge.status, 201);
  assertEquals(await challenge.json(), {
    challenge_id: VALID_CATEGORY_ID,
    nonce: "n".repeat(43),
    expires_at: "2026-09-16T00:05:00Z",
  });
  assertEquals(accountGateway.createCalls[0].ownerId, VERIFIED_USER_ID);

  const accepted = await handler(request("/library-api/v1/account/delete", {
    method: "POST",
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify({
      challenge_id: VALID_CATEGORY_ID,
      google_id_token: "signed-google-proof",
    }),
  }));
  assertEquals(accepted.status, 202);
  assertEquals(await accepted.json(), { state: "deleting" });
  assertEquals(accountGateway.acceptCalls[0].ownerId, VERIFIED_USER_ID);
  assertEquals(scheduled, 1);
});

Deno.test("account deletion fails closed for missing capability and Google mismatch", async () => {
  const gateway = accountVerifiedGateway("linked-subject");
  const unavailable = await createHandler(gateway)(request(
    "/library-api/v1/account/delete-challenge",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: "{}",
    },
  ));
  assertEquals(unavailable.status, 503);
  assertEquals(await errorCode(unavailable), "DEPENDENCY_UNAVAILABLE");

  const accountGateway = new FakeAccountDeletionGateway();
  const mismatch = await createHandler(
    gateway,
    undefined,
    fakeAccountDeletionService(accountGateway, "different-subject"),
  )(request("/library-api/v1/account/delete", {
    method: "POST",
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify({
      challenge_id: VALID_CATEGORY_ID,
      google_id_token: "signed-google-proof",
    }),
  }));
  assertEquals(mismatch.status, 403);
  assertEquals(await errorCode(mismatch), "GOOGLE_IDENTITY_MISMATCH");
  assertEquals(accountGateway.acceptCalls, []);
});

Deno.test("account deletion maps rejected Google proof without reflecting the token", async () => {
  const gateway = accountVerifiedGateway("google-subject");
  const accountGateway = new FakeAccountDeletionGateway();
  const service = new AccountDeletionService(
    accountGateway,
    { verify: () => Promise.reject(new Error("provider detail")) },
    { derive: () => Promise.resolve("n".repeat(43)) },
  );
  const response = await createHandler(gateway, undefined, service)(request(
    "/library-api/v1/account/delete",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: JSON.stringify({
        challenge_id: VALID_CATEGORY_ID,
        google_id_token: "secret-raw-token",
      }),
    },
  ));

  assertEquals(response.status, 401);
  const payload = await response.text();
  assertMatch(payload, /GOOGLE_PROOF_INVALID/);
  assertEquals(payload.includes("secret-raw-token"), false);
  assertEquals(payload.includes("provider detail"), false);
  assertEquals(accountGateway.acceptCalls, []);
});

Deno.test("account deletion enforces method, query, body, and authentication", async () => {
  const service = fakeAccountDeletionService(
    new FakeAccountDeletionGateway(),
    "google-subject",
  );
  const wrongMethod = await createHandler(
    new FakeGateway(),
    undefined,
    service,
  )(request("/library-api/v1/account/delete", { method: "GET" }));
  assertEquals(wrongMethod.status, 405);

  const unauthenticated = await createHandler(
    new FakeGateway(),
    undefined,
    service,
  )(request("/library-api/v1/account/delete-challenge", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
  }));
  assertEquals(unauthenticated.status, 401);

  const gateway = accountVerifiedGateway("google-subject");
  const query = await createHandler(gateway, undefined, service)(request(
    "/library-api/v1/account/delete-challenge?extra=true",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: "{}",
    },
  ));
  assertEquals(query.status, 400);
  assertEquals(await errorCode(query), "INVALID_QUERY");

  const body = await createHandler(gateway, undefined, service)(request(
    "/library-api/v1/account/delete-challenge",
    {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: JSON.stringify({ extra: true }),
    },
  ));
  assertEquals(body.status, 400);
  assertEquals(await errorCode(body), "INVALID_REQUEST");
});

class FakeAccountDeletionGateway implements AccountDeletionGateway {
  createCalls: {
    ownerId: string;
    requestId: string;
    nonceHash: string;
  }[] = [];
  acceptCalls: {
    ownerId: string;
    requestId: string;
    requestHash: string;
    challengeId: string;
  }[] = [];

  createDeleteChallenge(
    call: { ownerId: string; requestId: string; nonceHash: string },
  ): Promise<AccountDeletionRpcResult> {
    this.createCalls.push(call);
    return Promise.resolve({
      data: {
        http_status: 201,
        challenge_id: VALID_CATEGORY_ID,
        expires_at: "2026-09-16T00:05:00Z",
      },
      error: null,
    });
  }

  replayAccountDeletion(): Promise<AccountDeletionRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }

  checkDeleteChallengeBinding(): Promise<AccountDeletionRpcResult> {
    return Promise.resolve({
      data: {
        http_status: 200,
        state: "valid",
      },
      error: null,
    });
  }

  acceptAccountDeletion(
    call: {
      ownerId: string;
      requestId: string;
      requestHash: string;
      challengeId: string;
    },
  ): Promise<AccountDeletionRpcResult> {
    this.acceptCalls.push(call);
    return Promise.resolve({
      data: { http_status: 202, state: "deleting" },
      error: null,
    });
  }
}

function accountVerifiedGateway(googleSubject: string): FakeGateway {
  const gateway = new FakeGateway();
  gateway.verification = {
    status: "verified",
    userId: VERIFIED_USER_ID,
    user: {
      id: VERIFIED_USER_ID,
      identities: [{
        provider: "google",
        provider_id: googleSubject,
        identity_data: { sub: googleSubject },
      }],
    },
  };
  return gateway;
}

function fakeAccountDeletionService(
  gateway: AccountDeletionGateway,
  proofSubject: string,
): AccountDeletionService {
  return new AccountDeletionService(
    gateway,
    {
      verify: () =>
        Promise.resolve({ subject: proofSubject, nonce: "0".repeat(64) }),
    },
    { derive: () => Promise.resolve("n".repeat(43)) },
  );
}

function request(path: string, init: RequestInit = {}): Request {
  return new Request(`https://example.test${path}`, init);
}

function bootstrapRequest(
  body: string,
  contentType = "application/json",
): Request {
  return request("/functions/v1/library-api/v1/bootstrap", {
    method: "POST",
    headers: {
      ...Object.fromEntries(authenticatedJsonHeaders()),
      "content-type": contentType,
    },
    body,
  });
}

function itemRequest(body: unknown): Request {
  return request("/functions/v1/library-api/v1/items", {
    method: "POST",
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify(body),
  });
}

function updateRequest(body: unknown): Request {
  return request(`/functions/v1/library-api/v1/items/${VALID_ITEM_ID}`, {
    method: "PATCH",
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify(body),
  });
}

function discoveryMutationRequest(
  path: string,
  method: "POST" | "PATCH" | "DELETE",
  body: unknown,
): Request {
  return request(path, {
    method,
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify(body),
  });
}

function assetRequest(
  path: string,
  method: "POST" | "PATCH" | "DELETE",
  body: unknown,
): Request {
  return request(path, {
    method,
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify(body),
  });
}

function classificationJob() {
  return {
    job_id: VALID_ITEM_ID,
    lease_token: VALID_REQUEST_ID,
    owner_id: VERIFIED_USER_ID,
    target_revision: 3,
    item: {
      version: 4,
      text_revision: 3,
      user_title: "프로젝트 업무",
      fetched_title: null,
      note: "회의 자료",
      shared_text: null,
      description: null,
      body_text: null,
      ocr_text: null,
    },
  };
}

function itemSnapshot(): Record<string, unknown> {
  return {
    id: VALID_ITEM_ID,
    version: 4,
    url: "HTTPS://Blog.NAVER.com:443/PostView.naver?Keep=A#part",
    display_title: "기존 카톡 제목",
    source: "naver_blog",
    note_excerpt: "기존 안드로이드 메모",
    category_refs: [
      { id: VALID_CATEGORY_ID, name: "업무 자료", origin: "manual" },
    ],
    has_attachment: true,
    metadata_state: "ready",
    ocr_state: "ready",
    classification_state: "manual",
    cue_state: "available",
    cue_flags: [],
    match_type: null,
    user_title: "기존 카톡 제목",
    fetched_title: "확보한 여행 제목",
    shared_text: "공유 원문 첫 줄\n둘째 줄",
    description: "설명 텍스트",
    body_text: "본문 텍스트",
    note: "기존 안드로이드 메모",
    extraction_meta: { body_truncated: true },
    active_asset: {
      id: "8ff75967-3313-4a63-8131-e606dd63b087",
      ocr_text: "사진 속 쇼핑 정보",
      ocr_truncated: false,
    },
  };
}

function authenticatedJsonHeaders(): Headers {
  return new Headers({
    authorization: "Bearer caller-access-token",
    "content-type": "application/json",
    "x-request-id": VALID_REQUEST_ID,
  });
}

function authenticatedMeRequest(): Request {
  return request("/library-api/v1/me", {
    headers: { authorization: "Bearer caller-access-token" },
  });
}

function byteStream(...sizes: number[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const size of sizes) {
        controller.enqueue(new Uint8Array(size));
      }
      controller.close();
    },
  });
}

interface ErrorPayload {
  error: { code: string; message: string; retryable: boolean };
  request_id: string;
}

async function errorPayload(response: Response): Promise<ErrorPayload> {
  return await response.json() as ErrorPayload;
}

async function errorCode(response: Response): Promise<string> {
  return (await errorPayload(response)).error.code;
}
