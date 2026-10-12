import { assert, assertEquals, assertNotEquals } from "@std/assert";
import { exportJWK, generateKeyPair, SignJWT } from "jose";
import {
  createGoogleIdTokenVerifierForUnitTests,
  createServiceCredentialNonceDeriver,
  GoogleIdTokenVerificationUnavailableError,
  type GoogleIdTokenVerifier,
  MAX_ACCOUNT_DELETE_BODY_BYTES,
} from "./account-deletion.ts";
import {
  createWebAccountDeletionHandler,
  createWebAccountNonceDerivers,
  isWebAccountDeletionPath,
  WEB_ACCOUNT_ORIGIN,
  WebAccountDeletionService,
  type WebAccountGateway,
  type WebAccountRpcName,
} from "./web-account-deletion.ts";

const NOW = new Date("2026-10-12T14:00:00Z");
const SECONDS = NOW.getTime() / 1000;
const AUDIENCE = "browser-client.apps.googleusercontent.com";
const CHALLENGE = "89d80a0a-594e-4943-bab6-a4b3a8130111";
const REQUEST = "89d80a0a-594e-4943-bab6-a4b3a8130222";
const NONCE = "n".repeat(43);
const SUBJECT = "104000000000000000001";
const URL_BASE =
  "https://api.linkfilebox.com/functions/v1/library-api/v1/web-account/";
const signingKeys = generateKeyPair("RS256", { extractable: true });

async function token(
  overrides: Record<string, unknown> = {},
  useUntrustedKey = false,
): Promise<string> {
  const keys = useUntrustedKey
    ? await generateKeyPair("RS256")
    : await signingKeys;
  return new SignJWT({
    iss: "https://accounts.google.com",
    aud: AUDIENCE,
    sub: SUBJECT,
    iat: SECONDS,
    exp: SECONDS + 300,
    nonce: NONCE,
    ...overrides,
  }).setProtectedHeader({ alg: "RS256", kid: "browser-key" }).sign(
    keys.privateKey,
  );
}

class Gateway implements WebAccountGateway {
  calls: { name: WebAccountRpcName; args: Record<string, unknown> }[] = [];
  begin: unknown = {
    http_status: 200,
    state: "verify",
    created_at: NOW.toISOString(),
  };
  finish: unknown = null;
  rpcError: { code: string; message: string } | null = null;
  throwing = false;
  rpc(name: WebAccountRpcName, args: Record<string, unknown>) {
    this.calls.push({ name, args });
    if (this.throwing) {
      return Promise.reject(new Error("private database address and secret"));
    }
    const data = name === "library_web_account_challenge"
      ? {
        http_status: 201,
        challenge_id: CHALLENGE,
        expires_at: new Date(NOW.getTime() + 300000).toISOString(),
      }
      : name === "library_web_account_begin"
      ? this.begin
      : this.finish ??
        (args.p_purpose === "delete"
          ? { http_status: 202, state: "deleting" }
          : status("deleting"));
    return Promise.resolve({ data, error: this.rpcError });
  }
}

function status(state: "not_requested" | "deleting" | "deleted") {
  return {
    http_status: 200,
    state,
    requested_at: state === "not_requested" ? null : NOW.toISOString(),
    completed_at: state === "deleted"
      ? new Date(NOW.getTime() + 60000).toISOString()
      : null,
    status_available_until: state === "deleted"
      ? new Date(NOW.getTime() + 60000 + 30 * 86400000).toISOString()
      : null,
  };
}

async function fixture(verifier?: GoogleIdTokenVerifier) {
  const gateway = new Gateway();
  const keys = await signingKeys;
  const trusted = verifier ?? createGoogleIdTokenVerifierForUnitTests({
    audience: AUDIENCE,
    jwks: {
      keys: [{
        ...await exportJWK(keys.publicKey),
        kid: "browser-key",
        alg: "RS256",
        use: "sig",
      }],
    },
    now: () => NOW,
  });
  const service = new WebAccountDeletionService(gateway, trusted, {
    delete: { derive: () => Promise.resolve(NONCE) },
    status: { derive: () => Promise.resolve("s".repeat(43)) },
  }, () => NOW);
  let scheduled = 0;
  return {
    gateway,
    service,
    handler: createWebAccountDeletionHandler(service, () => {
      scheduled++;
    }),
    scheduled: () => scheduled,
  };
}

function request(
  action: string,
  body: unknown,
  init: RequestInit = {},
): Request {
  const headers = new Headers({
    origin: WEB_ACCOUNT_ORIGIN,
    "content-type": "application/json",
    "x-request-id": REQUEST,
    apikey: "public-project-key",
  });
  new Headers(init.headers).forEach((value, key) => headers.set(key, value));
  return new Request(URL_BASE + action, {
    ...init,
    method: init.method ?? "POST",
    headers,
    body: init.body ?? JSON.stringify(body),
  });
}
async function proofBody(overrides: Record<string, unknown> = {}) {
  return { challenge_id: CHALLENGE, google_id_token: await token(overrides) };
}
async function errorCode(response: Response): Promise<string> {
  return (await response.json()).error.code;
}
async function hash(value: string) {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
    ),
    (v) => v.toString(16).padStart(2, "0"),
  ).join("");
}

Deno.test("browser route isolation does not capture native or internal paths", () => {
  for (
    const path of [
      "/library-api/v1/web-account/delete",
      "/functions/v1/library-api/v1/web-account/status",
      "/library-api/v1/web-account/unknown",
    ]
  ) assert(isWebAccountDeletionPath(path));
  for (
    const path of [
      "/library-api/v1/account/delete",
      "/library-api/v1/internal/maintenance",
      "/library-api/v1/web-account-evil/delete",
    ]
  ) assertEquals(isWebAccountDeletionPath(path), false);
});

Deno.test("real nonce derivation separates browser purposes and the unchanged native protocol", async () => {
  const credential = "unit-test-service-credential-with-at-least-32-bytes";
  const nonces = createWebAccountNonceDerivers(credential);
  const deletion = await nonces.delete.derive(REQUEST, REQUEST);
  const statusNonce = await nonces.status.derive(REQUEST, REQUEST);
  const native = await createServiceCredentialNonceDeriver(credential).derive(
    REQUEST,
    REQUEST,
  );
  assert(/^[A-Za-z0-9_-]{43}$/.test(deletion));
  assertEquals(await nonces.delete.derive(REQUEST, REQUEST), deletion);
  assertNotEquals(deletion, statusNonce);
  assertNotEquals(deletion, native);
  assertNotEquals(statusNonce, native);
  assertNotEquals(await nonces.delete.derive(REQUEST, CHALLENGE), deletion);
});

Deno.test("challenge requires no session, returns raw nonce, stores only its hex digest", async () => {
  const f = await fixture();
  const response = await f.handler(request("challenge", { purpose: "delete" }));
  assertEquals(response.status, 201);
  const body = await response.json();
  assertEquals(body.nonce, NONCE);
  assertEquals(body.purpose, "delete");
  assertEquals(body.challenge_id, CHALLENGE);
  assertEquals(
    response.headers.get("access-control-allow-origin"),
    WEB_ACCOUNT_ORIGIN,
  );
  assertEquals(response.headers.get("cache-control"), "no-store");
  assertEquals(response.headers.get("access-control-allow-credentials"), null);
  assertEquals(f.gateway.calls[0].args, {
    p_request_id: REQUEST,
    p_purpose: "delete",
    p_nonce_hash: await hash(NONCE),
  });
  const second = await f.handler(request("challenge", { purpose: "status" }));
  assertNotEquals((await second.json()).nonce, NONCE);
  assertEquals(f.scheduled(), 0);
});

Deno.test("strict same-origin OPTIONS on all browser routes bypasses proof and database", async () => {
  const handler = createWebAccountDeletionHandler(undefined);
  for (const action of ["challenge", "delete", "status"]) {
    const response = await handler(
      new Request(URL_BASE + action, {
        method: "OPTIONS",
        headers: {
          origin: WEB_ACCOUNT_ORIGIN,
          "access-control-request-method": "POST",
          "access-control-request-headers":
            "Content-Type, X-Request-Id, apikey",
        },
      }),
    );
    assertEquals(response.status, 204);
    assertEquals(response.headers.get("access-control-allow-methods"), "POST");
    assertEquals(
      response.headers.get("access-control-allow-headers"),
      "content-type, x-request-id, apikey",
    );
    assertEquals(response.headers.get("cache-control"), "no-store");
    assert(response.headers.get("vary")!.includes("Origin"));
  }
});

Deno.test("unapproved/missing/null origins never allocate challenges or return permissive CORS", async () => {
  const f = await fixture();
  for (
    const origin of [
      undefined,
      "null",
      "http://linkfilebox.com",
      "https://www.linkfilebox.com",
      "https://linkfilebox.com.evil.example",
    ]
  ) {
    const headers = new Headers({
      "content-type": "application/json",
      "x-request-id": REQUEST,
    });
    if (origin !== undefined) headers.set("origin", origin);
    for (const method of ["POST", "OPTIONS"]) {
      const response = await f.handler(
        new Request(URL_BASE + "challenge", {
          method,
          headers,
          body: method === "POST" ? '{"purpose":"delete"}' : undefined,
        }),
      );
      assertEquals(response.status, 403);
      assertEquals(await errorCode(response), "ORIGIN_NOT_ALLOWED");
      assertEquals(response.headers.get("access-control-allow-origin"), null);
    }
  }
  assertEquals(f.gateway.calls, []);
});

Deno.test("preflight rejects credentials, unlisted headers, and non-POST methods", async () => {
  const f = await fixture();
  for (
    const [method, header] of [
      ["GET", "content-type"],
      ["POST", "authorization"],
      ["POST", "x-job-id"],
      ["DELETE", "apikey"],
    ]
  ) {
    const response = await f.handler(
      new Request(URL_BASE + "status", {
        method: "OPTIONS",
        headers: {
          origin: WEB_ACCOUNT_ORIGIN,
          "access-control-request-method": method,
          "access-control-request-headers": header,
        },
      }),
    );
    assertEquals(response.status, 403);
    assertEquals(await errorCode(response), "PREFLIGHT_NOT_ALLOWED");
  }
  assertEquals(f.gateway.calls, []);
});

Deno.test("method, unknown route, and missing request identity failures do not reach SQL", async () => {
  const f = await fixture();
  const get = await f.handler(
    new Request(URL_BASE + "status", {
      headers: { origin: WEB_ACCOUNT_ORIGIN },
    }),
  );
  assertEquals(get.status, 405);
  assertEquals(await errorCode(get), "METHOD_NOT_ALLOWED");
  assertEquals((await f.handler(request("unknown", {}))).status, 404);
  const missingId = await f.handler(
    new Request(URL_BASE + "challenge", {
      method: "POST",
      headers: {
        origin: WEB_ACCOUNT_ORIGIN,
        "content-type": "application/json",
      },
      body: '{"purpose":"status"}',
    }),
  );
  assertEquals(missingId.status, 400);
  assert(/^[0-9a-f-]{36}$/.test(missingId.headers.get("x-request-id")!));
  assertEquals(f.gateway.calls, []);
});

Deno.test("bounded strict JSON/request inputs reject job IDs, emails, unknown keys, cookies, media types and queries", async () => {
  const f = await fixture();
  for (
    const body of [
      {},
      { purpose: "anything" },
      { purpose: "delete", email: "same@example.com" },
      [],
      null,
    ]
  ) {
    assertEquals((await f.handler(request("challenge", body))).status, 400);
  }
  for (
    const body of [
      { challenge_id: CHALLENGE },
      { challenge_id: CHALLENGE, google_id_token: "x", job_id: REQUEST },
      { challenge_id: "bad", google_id_token: "x" },
      { challenge_id: CHALLENGE, google_id_token: "x".repeat(16385) },
    ]
  ) {
    assertEquals((await f.handler(request("status", body))).status, 400);
  }
  for (
    const headers of [{ "x-request-id": "not-a-uuid" }, {
      cookie: "session=untrusted",
    }] as Record<string, string>[]
  ) {
    assertEquals(
      (await f.handler(
        request("challenge", { purpose: "status" }, { headers }),
      )).status,
      400,
    );
  }
  assertEquals(
    (await f.handler(
      request("challenge?email=same@example.com", { purpose: "delete" }),
    )).status,
    400,
  );
  assertEquals(
    (await f.handler(
      request("challenge", { purpose: "delete" }, {
        headers: { "content-type": "text/plain" },
      }),
    )).status,
    415,
  );
  assertEquals(
    (await f.handler(request("challenge", {}, { body: "{" }))).status,
    400,
  );
  assertEquals(
    (await f.handler(
      request("challenge", {}, {
        body: "x".repeat(MAX_ACCOUNT_DELETE_BODY_BYTES + 1),
      }),
    )).status,
    413,
  );
  assertEquals(f.gateway.calls, []);
});

Deno.test("chunked oversize and invalid UTF8 bodies fail without consulting the database", async () => {
  const f = await fixture();
  const headers = {
    origin: WEB_ACCOUNT_ORIGIN,
    "content-type": "application/json",
    "x-request-id": REQUEST,
  };
  for (
    const bytes of [
      new Uint8Array(MAX_ACCOUNT_DELETE_BODY_BYTES + 1),
      new Uint8Array([0xff, 0xfe]),
    ]
  ) {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes);
        controller.close();
      },
    });
    const response = await f.handler(
      new Request(URL_BASE + "challenge", {
        method: "POST",
        headers,
        body: stream,
      }),
    );
    assertEquals(
      response.status,
      bytes.length > MAX_ACCOUNT_DELETE_BODY_BYTES ? 413 : 400,
    );
  }
  assertEquals(f.gateway.calls, []);
});

Deno.test("202 accepts transactional deletion but never reports completion or exposes proofs", async () => {
  const f = await fixture();
  const body = await proofBody();
  const response = await f.handler(request("delete", body));
  assertEquals(response.status, 202);
  assertEquals(await response.json(), { state: "deleting" });
  assertEquals(f.scheduled(), 1);
  const finish = f.gateway.calls[1];
  assertEquals(finish.args.p_google_subject, SUBJECT);
  assertEquals(finish.args.p_nonce_hash, await hash(NONCE));
  assertEquals(
    finish.args.p_request_hash,
    await hash(
      JSON.stringify({
        purpose: "delete",
        challenge_id: CHALLENGE,
        google_id_token: body.google_id_token,
      }),
    ),
  );
  assertEquals(
    JSON.stringify(f.gateway.calls).includes(body.google_id_token),
    false,
  );
  assertEquals(Object.hasOwn(finish.args, "p_owner_id"), false);
});

Deno.test("an endless empty-chunk source is bounded without depending on timer scheduling", async () => {
  const f = await fixture();
  let reads = 0;
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      reads++;
      controller.enqueue(new Uint8Array());
    },
    cancel() {
      cancelled = true;
    },
  });
  const response = await f.handler(
    new Request(URL_BASE + "challenge", {
      method: "POST",
      headers: {
        origin: WEB_ACCOUNT_ORIGIN,
        "content-type": "application/json",
        "x-request-id": REQUEST,
      },
      body: stream,
    }),
  );
  assertEquals(response.status, 413);
  assert(reads <= MAX_ACCOUNT_DELETE_BODY_BYTES + 3);
  assert(cancelled);
  assertEquals(f.gateway.calls, []);
});

Deno.test("real Google verifier rejects invalid signature, issuer, audience, nonce format, expired and stale proof", async () => {
  const cases: Record<string, unknown>[] = [
    { iss: "https://evil.example" },
    { aud: "other.apps.googleusercontent.com" },
    { aud: [AUDIENCE] },
    { nonce: "f".repeat(64) },
    { exp: SECONDS - 100 },
    { iat: SECONDS + 100 },
    { iat: SECONDS - 331, exp: SECONDS + 600 },
    { sub: "bad subject" },
  ];
  for (const overrides of cases) {
    const f = await fixture();
    const response = await f.handler(
      request("delete", await proofBody(overrides)),
    );
    assertEquals(response.status, 401);
    assertEquals(await errorCode(response), "GOOGLE_PROOF_INVALID");
    assertEquals(
      f.gateway.calls.length,
      1,
      "begin counts attempt; failed proof never finishes",
    );
    assertEquals(f.scheduled(), 0);
  }
  const f = await fixture();
  assertEquals(
    (await f.handler(
      request("delete", {
        challenge_id: CHALLENGE,
        google_id_token: await token({}, true),
      }),
    )).status,
    401,
  );
  assertEquals(f.gateway.calls.length, 1);
});

Deno.test("verified proof issued before the current server challenge is rejected", async () => {
  const f = await fixture();
  const response = await f.handler(
    request("status", await proofBody({ iat: SECONDS - 31 })),
  );
  assertEquals(response.status, 401);
  assertEquals(f.gateway.calls.length, 1);
});

Deno.test("wrong Google subject cannot authorize victim status or create a new Auth account", async () => {
  const f = await fixture();
  f.gateway.finish = { http_status: 404, error_code: "STATUS_NOT_FOUND" };
  const response = await f.handler(
    request(
      "status",
      await proofBody({
        sub: "104000000000000000002",
        email: "victim@example.com",
      }),
    ),
  );
  assertEquals(response.status, 404);
  assertEquals(await errorCode(response), "STATUS_NOT_FOUND");
  assertEquals(
    f.gateway.calls[1].args.p_google_subject,
    "104000000000000000002",
  );
  assertEquals(f.gateway.calls.map((call) => call.name), [
    "library_web_account_begin",
    "library_web_account_finish",
  ]);
  const unknown = await fixture();
  unknown.gateway.finish = {
    http_status: 404,
    error_code: "ACCOUNT_NOT_FOUND",
  };
  assertEquals(
    (await unknown.handler(
      request("delete", await proofBody({ sub: "unknown-google-subject" })),
    )).status,
    404,
  );
  assertEquals(unknown.scheduled(), 0);
});

Deno.test("nonce and purpose binding failures surface from SQL without accepting deletion", async () => {
  const f = await fixture();
  f.gateway.finish = { http_status: 409, error_code: "WEB_CHALLENGE_INVALID" };
  const response = await f.handler(
    request("delete", await proofBody({ nonce: "z".repeat(43) })),
  );
  assertEquals(response.status, 409);
  assertEquals(
    f.gateway.calls[1].args.p_nonce_hash,
    await hash("z".repeat(43)),
  );
  f.gateway.begin = { http_status: 409, error_code: "WEB_CHALLENGE_INVALID" };
  assertEquals(
    (await f.handler(request("status", await proofBody()))).status,
    409,
  );
  assertEquals(
    f.gateway.calls.length,
    3,
    "wrong purpose never verifies/finishes",
  );
  assertEquals(f.scheduled(), 0);
});

Deno.test("exact replay uses cached acceptance without verifying an expired token; mismatch fails", async () => {
  let verifications = 0;
  const f = await fixture({
    verify() {
      verifications++;
      throw new Error("expired");
    },
  });
  f.gateway.begin = { http_status: 202, state: "deleting" };
  const response = await f.handler(
    request("delete", await proofBody({ exp: SECONDS - 100 })),
  );
  assertEquals(response.status, 202);
  assertEquals(await response.json(), { state: "deleting" });
  assertEquals(verifications, 0);
  f.gateway.begin = { http_status: 409, error_code: "IDEMPOTENCY_MISMATCH" };
  const mismatch = await f.handler(
    request("delete", await proofBody({ sub: "different-subject" })),
  );
  assertEquals(mismatch.status, 409);
  assertEquals(await errorCode(mismatch), "IDEMPOTENCY_MISMATCH");
  assertEquals(verifications, 0);
});

Deno.test("fresh Google-only status survives absent Auth and represents authoritative terminal job data", async () => {
  for (const state of ["deleting", "deleted", "not_requested"] as const) {
    const f = await fixture();
    f.gateway.finish = status(state);
    const response = await f.handler(request("status", await proofBody()));
    assertEquals(response.status, 200);
    const expected = status(state);
    const { http_status: _, ...body } = expected;
    assertEquals(await response.json(), body);
    assertEquals(f.scheduled(), 0);
  }
});

Deno.test("dependency/expired/retention/rate errors remain visible, no-store, and never become deleted", async () => {
  for (
    const [code, statusCode] of [
      ["WEB_CHALLENGE_INVALID", 409],
      ["STATUS_NOT_FOUND", 404],
      ["RATE_LIMITED", 429],
      ["DEPENDENCY_UNAVAILABLE", 503],
      ["ACCOUNT_DELETING", 403],
      ["ACCOUNT_ALREADY_DELETED", 409],
    ] as const
  ) {
    const f = await fixture();
    f.gateway.begin = { http_status: statusCode, error_code: code };
    const response = await f.handler(request("status", await proofBody()));
    assertEquals(response.status, statusCode);
    const body = await response.json();
    assertEquals(body.error.code, code);
    assertEquals(
      body.error.retryable,
      statusCode === 429 || statusCode === 503,
    );
    assertEquals(Object.hasOwn(body, "state"), false);
    assertEquals(response.headers.get("cache-control"), "no-store");
    assertEquals(
      response.headers.get("access-control-allow-origin"),
      WEB_ACCOUNT_ORIGIN,
    );
    assertEquals(
      response.headers.get("retry-after"),
      statusCode === 429 ? "60" : null,
    );
  }
  const f = await fixture();
  f.gateway.rpcError = {
    code: "XX000",
    message: "secret SQL/token/private hostname",
  };
  const response = await f.handler(request("delete", await proofBody()));
  assertEquals(response.status, 503);
  assertEquals((await response.text()).includes("secret"), false);
  f.gateway.rpcError = null;
  f.gateway.throwing = true;
  const unavailable = await f.handler(
    request("challenge", { purpose: "delete" }),
  );
  assertEquals(unavailable.status, 503);
  assertEquals((await unavailable.text()).includes("private database"), false);
});

Deno.test("JWKS unavailability and missing production config fail closed with readable CORS errors", async () => {
  const f = await fixture({
    verify() {
      throw new GoogleIdTokenVerificationUnavailableError();
    },
  });
  assertEquals(
    (await f.handler(request("delete", await proofBody()))).status,
    503,
  );
  assertEquals(f.gateway.calls.length, 1);
  const response = await createWebAccountDeletionHandler(undefined)(
    request("challenge", { purpose: "status" }),
  );
  assertEquals(response.status, 503);
  assertEquals(await errorCode(response), "DEPENDENCY_UNAVAILABLE");
  assertEquals(
    response.headers.get("access-control-allow-origin"),
    WEB_ACCOUNT_ORIGIN,
  );
});

Deno.test("malformed RPC completion schemas cannot be mistaken for deletion completion", async () => {
  for (
    const result of [
      { http_status: 200, state: "deleted" },
      { ...status("deleted"), completed_at: null },
      { ...status("deleted"), completed_at: "2020-01-01T00:00:00Z" },
      { ...status("deleting"), completed_at: NOW.toISOString() },
      { ...status("not_requested"), requested_at: NOW.toISOString() },
      { ...status("deleted"), owner_id: REQUEST },
    ]
  ) {
    const f = await fixture();
    f.gateway.finish = result;
    assertEquals(
      (await f.handler(request("status", await proofBody()))).status,
      503,
    );
  }
  const f = await fixture();
  f.gateway.finish = { http_status: 202, state: "deleted" };
  assertEquals(
    (await f.handler(request("delete", await proofBody()))).status,
    503,
  );
  assertEquals(f.scheduled(), 0);
});
