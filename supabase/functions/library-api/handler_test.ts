import { assert, assertEquals, assertMatch } from "@std/assert";
import {
  type AuthVerification,
  type CategoryLookupCall,
  createHandler,
  type CreateItemCall,
  type MemberGateway,
  type RpcCall,
  type RpcResult,
  type UpdateItemCall,
} from "./handler.ts";

const VALID_REQUEST_ID = "5e51d680-b8d8-4f7a-a29f-d764f2965aa2";
const VALID_ITEM_ID = "3d8752d2-47bb-4f4c-b2c7-7a3590eb02a9";
const VERIFIED_USER_ID = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const VALID_CATEGORY_ID = "264c6a49-04a0-45c4-954f-773f97c0c4bb";
const SECOND_CATEGORY_ID = "71baadfe-6a88-43a3-b0fb-f111f18ef97f";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

class FakeGateway implements MemberGateway {
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
  verifiedTokens: string[] = [];
  rpcCalls: RpcCall[] = [];
  createCalls: CreateItemCall[] = [];
  categoryCalls: CategoryLookupCall[] = [];
  updateCalls: UpdateItemCall[] = [];

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
  assertEquals(detailWrongMethod.headers.get("allow"), "GET, PATCH");

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

Deno.test("GET items forwards bounded pagination through the caller JWT", async () => {
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
      functionName: "library_list_items",
      args: { p_limit: testCase.limit, p_offset: testCase.offset },
    }]);
    assertEquals(gateway.createCalls, []);
  }
});

Deno.test("GET items rejects unknown future filters and invalid pagination", async () => {
  const queries = [
    "?q=excel",
    "?category_id=abc",
    "?source=threads",
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
  assertEquals(await response.json(), gateway.rpcResult.data);
  assertEquals(gateway.rpcCalls, [{
    authorization: "Bearer caller-access-token",
    functionName: "library_get_item",
    args: { p_item_id: VALID_ITEM_ID },
  }]);
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
    { message: "CATEGORY_LIMIT_REACHED", status: 400 },
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
