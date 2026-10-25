import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  API_BASE,
  APPROVED_ORIGIN,
  createAccountDeletionController,
  GIS_URL,
  GOOGLE_CLIENT_ID,
  MAX_MANUAL_RETRIES,
  RETRY_WINDOW_MS,
} from "../website/account-deletion/account-deletion.js";

const NOW = Date.parse("2026-10-12T14:00:00Z");
const CHALLENGE_ID = "89d80a0a-594e-4943-bab6-a4b3a8130111";
const TOKEN = "testHeader.testPayload.testSignature";
const CONFIG = {
  apiBase: API_BASE,
  googleClientId: GOOGLE_CLIENT_ID,
  publishableKey: "sb_publishable_unit_test_public_key",
  enabled: true,
};
function response(status, data) {
  return { status, json: async () => structuredClone(data) };
}
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function challenge(purpose, overrides = {}) {
  return {
    challenge_id: CHALLENGE_ID,
    nonce: "n".repeat(43),
    purpose,
    expires_at: new Date(NOW + 300000).toISOString(),
    ...overrides,
  };
}
function status(state, overrides = {}) {
  return {
    state,
    requested_at: state === "not_requested"
      ? null
      : new Date(NOW - 60000).toISOString(),
    completed_at: state === "deleted" ? new Date(NOW).toISOString() : null,
    status_available_until: state === "deleted"
      ? new Date(NOW + 30 * 86400000).toISOString()
      : null,
    ...overrides,
  };
}
function fixture(
  {
    origin = APPROVED_ORIGIN,
    config = CONFIG,
    configStatus = 200,
    uuid,
    timeoutMs,
  } = {},
) {
  const calls = [];
  const changes = [];
  const replies = [];
  let now = NOW;
  let serial = 0;
  const controller = createAccountDeletionController({
    origin,
    clock: () => now,
    uuid: uuid ??
      (() => `89d80a0a-594e-4943-bab6-${String(++serial).padStart(12, "0")}`),
    timeoutMs,
    onChange: (state) => changes.push(state),
    fetch: async (url, init) => {
      calls.push({ url, ...init });
      if (url === "/account-deletion/config.json") {
        return response(configStatus, config);
      }
      assert.ok(replies.length > 0, "Missing injected response");
      const next = replies.shift();
      return typeof next === "function" ? next() : next;
    },
  });
  return {
    controller,
    calls,
    changes,
    replies,
    setClock: (value) => {
      now = value;
    },
    enqueue: (statusCode, data) => replies.push(response(statusCode, data)),
    async ready(purpose = "delete") {
      assert.equal(await controller.initialize(), true);
      controller.setConsent(true);
      this.enqueue(201, challenge(purpose));
      assert.equal(await controller.start(purpose), true);
      assert.equal(
        controller.acceptProof(controller.getState().generation, TOKEN),
        true,
      );
    },
  };
}

function assertPost(call, action, body) {
  assert.equal(call.url, `${API_BASE}/${action}`);
  assert.equal(call.method, "POST");
  assert.equal(call.credentials, "omit");
  assert.equal(call.cache, "no-store");
  assert.equal(call.redirect, "error");
  assert.equal(call.referrerPolicy, "no-referrer");
  assert.deepEqual(
    Object.keys(call.headers).sort(),
    ["Content-Type", "X-Request-Id", "apikey"].sort(),
  );
  assert.equal(call.headers["Content-Type"], "application/json");
  assert.equal(call.headers.apikey, CONFIG.publishableKey);
  assert.match(call.headers["X-Request-Id"], /^[0-9a-f-]{36}$/);
  assert.deepEqual(JSON.parse(call.body), body);
}

test("deletion accessibility styling is page-scoped in the CSP-approved external stylesheet", () => {
  const html = readFileSync(
    new URL("../website/account-deletion/index.html", import.meta.url),
    "utf8",
  );
  const css = readFileSync(
    new URL("../website/site.css", import.meta.url),
    "utf8",
  );
  assert.match(html, /<link\b[^>]*rel="stylesheet"[^>]*href="\.\.\/site\.css"/);
  assert.match(html, /<body\b[^>]*class="account-deletion"/);
  assert.doesNotMatch(html, /<style\b|\bstyle\s*=/i);
  assert.match(
    css,
    /\.account-deletion \.account-controls button\s*\{[^}]*padding: 12px 16px;[^}]*font: inherit;/,
  );
  assert.match(
    css,
    /\.account-deletion \.account-controls button:disabled\s*\{[^}]*cursor: not-allowed;[^}]*opacity: \.6;/,
  );
  assert.match(
    css,
    /\.account-deletion \.account-controls button:focus-visible,\s*\.account-deletion \.account-controls input:focus-visible\s*\{[^}]*outline: 3px solid #075c48;[^}]*outline-offset: 4px;/,
  );
  assert.match(
    css,
    /\.account-deletion \.account-controls input\s*\{[^}]*width: 20px;[^}]*height: 20px;/,
  );
  assert.match(
    css,
    /\.account-deletion #account-message\s*\{[^}]*white-space: pre-line;/,
  );
  assert.match(
    css,
    /\.account-deletion #account-message\[data-error="true"\]\s*\{[^}]*border-left-color: #9b2424;/,
  );
});

test("live origin, API and Google web client are pinned; GIS is not loaded by the controller", async () => {
  assert.equal(APPROVED_ORIGIN, "https://linkfilebox.com");
  assert.equal(
    API_BASE,
    "https://api.linkfilebox.com/functions/v1/library-api/v1/web-account",
  );
  assert.equal(
    GOOGLE_CLIENT_ID,
    "486032328491-4sv6bg4ukablou6d2btfnhon30e10rsb.apps.googleusercontent.com",
  );
  assert.equal(GIS_URL, "https://accounts.google.com/gsi/client");
  for (
    const origin of [
      "null",
      "http://linkfilebox.com",
      "https://www.linkfilebox.com",
      "https://linkfilebox.com.evil.test",
      "http://localhost:8080",
      undefined,
    ]
  ) {
    const f = fixture({ origin: origin === undefined ? "" : origin });
    assert.equal(await f.controller.initialize(), false);
    assert.equal(await f.controller.start("status"), false);
    assert.deepEqual(f.calls, []);
  }
});

test("public config is fetched once without credentials and only explicit valid config enables requests", async () => {
  const f = fixture();
  assert.deepEqual(
    await Promise.all([f.controller.initialize(), f.controller.initialize()]),
    [true, true],
  );
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, "/account-deletion/config.json");
  assert.equal(f.calls[0].credentials, "omit");
  assert.equal(f.calls[0].cache, "no-store");
  const jwt = (claims) =>
    `header.${
      Buffer.from(JSON.stringify(claims)).toString("base64url")
    }.signature`;
  const legacy = fixture({
    config: { ...CONFIG, publishableKey: jwt({ role: "anon" }) },
  });
  assert.equal(await legacy.controller.initialize(), true);
  for (
    const bad of [
      null,
      {},
      { ...CONFIG, enabled: false },
      { ...CONFIG, enabled: "true" },
      { ...CONFIG, apiBase: `${API_BASE}/` },
      { ...CONFIG, apiBase: "https://other.test" },
      { ...CONFIG, googleClientId: "other.apps.googleusercontent.com" },
      { ...CONFIG, publishableKey: "" },
      { ...CONFIG, publishableKey: "sb_secret_not_public" },
      { ...CONFIG, publishableKey: jwt({ role: "service_role" }) },
      { ...CONFIG, publishableKey: jwt({ role: "authenticated" }) },
      { ...CONFIG, publishableKey: jwt({}) },
      { ...CONFIG, publishableKey: "header.invalid.signature" },
      { ...CONFIG, extra: "unapproved" },
    ]
  ) {
    const disabled = fixture({ config: bad });
    assert.equal(await disabled.controller.initialize(), false);
    disabled.controller.setConsent(true);
    assert.equal(await disabled.controller.start("delete"), false);
    assert.equal(await disabled.controller.start("status"), false);
    assert.equal(disabled.calls.length, 1);
    assert.equal(disabled.controller.getState().phase, "disabled");
  }
  const missing = fixture({ configStatus: 404 });
  assert.equal(await missing.controller.initialize(), false);
});

test("delete needs irreversible consent and a separate final send after Google proof", async () => {
  const f = fixture();
  await f.controller.initialize();
  assert.equal(await f.controller.start("delete"), false);
  assert.equal(f.calls.length, 1);
  f.controller.setConsent(true);
  f.enqueue(201, challenge("delete"));
  assert.equal(await f.controller.start("delete"), true);
  assertPost(f.calls[1], "challenge", { purpose: "delete" });
  assert.equal(await f.controller.send(), false);
  const options = f.controller.googleOptions(() => {});
  assert.equal(options.client_id, GOOGLE_CLIENT_ID);
  assert.equal(options.nonce, "n".repeat(43));
  assert.equal(options.auto_select, false);
  assert.equal(options.button_auto_select, false);
  assert.equal(options.use_fedcm_for_button, false);
  assert.equal(options.ux_mode, "popup");
  assert.equal(Object.hasOwn(options, "login_hint"), false);
  options.callback({ credential: TOKEN });
  assert.equal(f.controller.getState().phase, "proof");
  assert.equal(
    f.calls.length,
    2,
    "Google callback must not send a deletion request",
  );
  f.enqueue(202, { state: "deleting" });
  assert.equal(await f.controller.send(), true);
  assertPost(f.calls[2], "delete", {
    challenge_id: CHALLENGE_ID,
    google_id_token: TOKEN,
  });
  assert.notEqual(
    f.calls[1].headers["X-Request-Id"],
    f.calls[2].headers["X-Request-Id"],
  );
  assert.equal(f.controller.getState().result.state, "deleting");
  assert.match(f.controller.getState().message, /아직 삭제 완료가 아닙니다/);
  assert.equal(await f.controller.send(), false);
  assert.equal(f.controller.googleOptions(() => {}), null);
  assert.equal(
    JSON.stringify(f.changes).includes(TOKEN),
    false,
    "Proof never reaches observable UI state",
  );
  assert.equal(JSON.stringify(f.changes).includes("n".repeat(43)), false);
});

test("status needs fresh Google proof but no deletion consent or signup", async () => {
  for (const state of ["not_requested", "deleting", "deleted"]) {
    const f = fixture();
    await f.controller.initialize();
    f.enqueue(201, challenge("status"));
    assert.equal(await f.controller.start("status"), true);
    assert.equal(await f.controller.send(), false);
    assert.equal(
      f.controller.acceptProof(f.controller.getState().generation, TOKEN),
      true,
    );
    f.enqueue(200, status(state));
    assert.equal(await f.controller.send(), true);
    assertPost(f.calls[1], "challenge", { purpose: "status" });
    assertPost(f.calls[2], "status", {
      challenge_id: CHALLENGE_ID,
      google_id_token: TOKEN,
    });
    assert.deepEqual(f.controller.getState().result, status(state));
    assert.equal(
      f.calls.length,
      3,
      "Only config, challenge, and authenticated status endpoint are used",
    );
    const copy = f.controller.getState();
    copy.result.state = "changed";
    assert.equal(f.controller.getState().result.state, state);
  }
});

test("challenge rejects malformed, wrong-purpose, elapsed and overlong expiry replies", async () => {
  for (
    const bad of [
      null,
      [],
      {},
      challenge("status"),
      challenge("delete", { challenge_id: "bad" }),
      challenge("delete", { nonce: "n".repeat(42) }),
      challenge("delete", { nonce: `${"n".repeat(42)}=` }),
      challenge("delete", { expires_at: null }),
      challenge("delete", { expires_at: "not a timestamp" }),
      challenge("delete", { expires_at: new Date(NOW).toISOString() }),
      challenge("delete", { expires_at: new Date(NOW - 1).toISOString() }),
      challenge("delete", {
        expires_at: new Date(NOW + RETRY_WINDOW_MS + 30001).toISOString(),
      }),
      { ...challenge("delete"), unexpected: "value" },
    ]
  ) {
    const f = fixture();
    await f.controller.initialize();
    f.controller.setConsent(true);
    f.enqueue(201, bad);
    assert.equal(await f.controller.start("delete"), false);
    assert.equal(f.controller.getState().phase, "error");
    assert.equal(
      f.controller.acceptProof(f.controller.getState().generation, TOKEN),
      false,
    );
    assert.equal(await f.controller.send(), false);
  }
  const f = fixture();
  await f.controller.initialize();
  f.controller.setConsent(true);
  const delayed = deferred();
  f.replies.push(() => delayed.promise);
  const start = f.controller.start("delete");
  f.setClock(NOW + 300000);
  delayed.resolve(response(201, challenge("delete")));
  assert.equal(
    await start,
    false,
    "Expiry is checked on receipt, not request start",
  );
});

test("expiry races at Google callback and final send invalidate proof", async () => {
  const callback = fixture();
  await callback.controller.initialize();
  callback.enqueue(201, challenge("status"));
  await callback.controller.start("status");
  let calls = 0;
  const options = callback.controller.googleOptions(() => calls++);
  callback.setClock(NOW + 300000);
  options.callback({ credential: TOKEN });
  assert.equal(calls, 0);
  assert.equal(callback.controller.getState().phase, "error");
  assert.equal(await callback.controller.send(), false);
  const final = fixture();
  await final.ready();
  final.setClock(NOW + 300000);
  assert.equal(await final.controller.send(), false);
  assert.equal(final.calls.length, 2);
});

test("duplicate challenge, callback, send and retry clicks cannot create duplicate requests", async () => {
  const f = fixture();
  await f.controller.initialize();
  f.controller.setConsent(true);
  const delayedChallenge = deferred();
  f.replies.push(() => delayedChallenge.promise);
  const started = f.controller.start("delete");
  assert.equal(await f.controller.start("delete"), false);
  assert.equal(await f.controller.start("status"), false);
  delayedChallenge.resolve(response(201, challenge("delete")));
  assert.equal(await started, true);
  let callbackCalls = 0;
  const options = f.controller.googleOptions(() => callbackCalls++);
  options.callback({ credential: TOKEN });
  options.callback({ credential: "different.payload.signature" });
  assert.equal(callbackCalls, 1);
  const delayedDelete = deferred();
  f.replies.push(() => delayedDelete.promise);
  const sent = f.controller.send();
  assert.equal(await f.controller.send(), false);
  assert.equal(await f.controller.retry(), false);
  delayedDelete.reject(new Error("Lost response"));
  assert.equal(await sent, false);
  const delayedRetry = deferred();
  f.replies.push(() => delayedRetry.promise);
  const retried = f.controller.retry();
  assert.equal(await f.controller.retry(), false);
  assert.equal(await f.controller.start("status"), false);
  delayedRetry.resolve(response(202, { state: "deleting" }));
  assert.equal(await retried, true);
  assert.equal(f.calls.length, 4);
  assert.equal(JSON.parse(f.calls[2].body).google_id_token, TOKEN);
});

test("cancel and consent revocation wipe proof and stale callbacks cannot affect a new generation", async () => {
  const f = fixture();
  await f.controller.initialize();
  f.controller.setConsent(true);
  f.enqueue(201, challenge("delete"));
  await f.controller.start("delete");
  let callbacks = 0;
  const stale = f.controller.googleOptions(() => callbacks++);
  f.controller.cancel();
  assert.equal(await f.controller.send(), false);
  f.enqueue(201, challenge("status", { nonce: "s".repeat(43) }));
  await f.controller.start("status");
  stale.callback({ credential: TOKEN });
  assert.equal(callbacks, 0);
  assert.equal(f.controller.getState().phase, "authenticating");
  f.controller.cancel();
  await f.ready();
  f.controller.setConsent(false);
  assert.equal(f.controller.getState().phase, "idle");
  assert.equal(await f.controller.send(), false);
  assert.equal(f.controller.googleOptions(() => {}), null);
});

test("cancel aborts in-flight work; a late reply cannot complete the replacement flow", async () => {
  const f = fixture();
  await f.ready();
  const delayed = deferred();
  f.replies.push(() => delayed.promise);
  const sent = f.controller.send();
  const abortSignal = f.calls.at(-1).signal;
  f.controller.cancel();
  assert.equal(abortSignal.aborted, true);
  assert.match(
    f.controller.getState().message,
    /이미 전송한 요청은 철회되지 않습니다/,
  );
  f.enqueue(201, challenge("status"));
  await f.controller.start("status");
  delayed.resolve(response(202, { state: "deleting" }));
  assert.equal(await sent, false);
  assert.equal(f.controller.getState().phase, "authenticating");
  assert.equal(f.controller.getState().result, null);
});

test("cancel during challenge ignores the late nonce and does not authorize Google authentication", async () => {
  const f = fixture();
  await f.controller.initialize();
  const delayed = deferred();
  f.replies.push(() => delayed.promise);
  const started = f.controller.start("status");
  f.controller.cancel();
  delayed.resolve(response(201, challenge("status")));
  assert.equal(await started, false);
  assert.equal(f.controller.getState().phase, "idle");
  assert.equal(f.controller.googleOptions(() => {}), null);
});

test("ambiguous deletion is not completion and manual retry preserves exact body and request UUID", async () => {
  const f = fixture();
  await f.ready();
  f.setClock(NOW + 299999);
  f.replies.push(() => {
    throw new Error("Connection lost; private details must not be displayed");
  });
  assert.equal(await f.controller.send(), false);
  const original = f.calls.at(-1);
  assert.equal(f.controller.getState().phase, "ambiguous");
  assert.equal(f.controller.getState().result, null);
  assert.equal(f.controller.getState().canRetry, true);
  assert.doesNotMatch(f.controller.getState().message, /private details/);
  f.setClock(NOW + 300001);
  f.enqueue(202, { state: "deleting" });
  assert.equal(
    await f.controller.retry(),
    true,
    "An accepted request may replay after challenge expiry within the bounded retry window",
  );
  const retried = f.calls.at(-1);
  assert.equal(retried.body, original.body);
  assert.deepEqual(retried.headers, original.headers);
  assert.equal(retried.url, original.url);
  assert.equal(f.controller.getState().result.state, "deleting");
  assert.equal(await f.controller.retry(), false);
  f.enqueue(
    201,
    challenge("status", { expires_at: new Date(NOW + 600000).toISOString() }),
  );
  assert.equal(await f.controller.start("status"), true);
  f.controller.acceptProof(f.controller.getState().generation, TOKEN);
  f.enqueue(200, status("deleting"));
  assert.equal(await f.controller.send(), true);
  assert.notEqual(
    f.calls.at(-1).headers["X-Request-Id"],
    original.headers["X-Request-Id"],
  );
});

test("retry is manual, bounded by attempts and time; cancellation prevents later replay", async () => {
  const attempts = fixture();
  await attempts.ready();
  attempts.replies.push(() => {
    throw new Error("lost");
  });
  await attempts.controller.send();
  assert.equal(attempts.calls.length, 3, "No automatic retry");
  for (let i = 0; i < MAX_MANUAL_RETRIES; i++) {
    attempts.replies.push(() => {
      throw new Error("lost");
    });
    assert.equal(await attempts.controller.retry(), false);
  }
  assert.equal(attempts.controller.getState().phase, "error");
  assert.equal(attempts.controller.getState().canRetry, false);
  assert.equal(await attempts.controller.retry(), false);
  assert.equal(attempts.calls.length, 3 + MAX_MANUAL_RETRIES);
  const timed = fixture();
  await timed.ready();
  timed.enqueue(503, { error: { message: "private backend details" } });
  await timed.controller.send();
  timed.setClock(NOW + RETRY_WINDOW_MS);
  timed.controller.expire();
  assert.equal(await timed.controller.retry(), false);
  assert.equal(timed.controller.getState().phase, "error");
  assert.equal(timed.calls.length, 3);
  const canceled = fixture();
  await canceled.ready();
  canceled.enqueue(503, {});
  await canceled.controller.send();
  canceled.controller.cancel();
  assert.equal(await canceled.controller.retry(), false);
  assert.equal(canceled.controller.getState().consent, false);
});

test("401, 404 and conflict replies never mean completion or authorize token reuse", async () => {
  for (const purpose of ["delete", "status"]) {
    for (const code of [400, 401, 403, 404, 409]) {
      const f = fixture();
      await f.ready(purpose);
      f.enqueue(code, {
        state: "deleted",
        error: { message: "private address" },
      });
      assert.equal(await f.controller.send(), false);
      assert.equal(f.controller.getState().phase, "error");
      assert.equal(f.controller.getState().result, null);
      assert.equal(await f.controller.retry(), false);
      assert.equal(await f.controller.send(), false);
      assert.doesNotMatch(f.controller.getState().message, /private address/);
      if (code === 404 && purpose === "status") {
        assert.match(
          f.controller.getState().message,
          /삭제 완료를 뜻하지 않습니다/,
        );
      }
    }
  }
});

test("malformed deletion success and status shapes are ambiguous, never authoritative completion", async () => {
  for (
    const [purpose, code, data] of [
      ["delete", 200, { state: "deleted" }],
      ["delete", 202, { state: "deleted" }],
      ["delete", 202, { state: "deleting", email: "private" }],
      ["delete", 202, null],
      ["status", 200, { state: "deleted" }],
      ["status", 202, status("deleted")],
      [
        "status",
        200,
        status("not_requested", { requested_at: new Date(NOW).toISOString() }),
      ],
      [
        "status",
        200,
        status("deleting", { completed_at: new Date(NOW).toISOString() }),
      ],
      [
        "status",
        200,
        status("deleting", {
          status_available_until: new Date(NOW + 1).toISOString(),
        }),
      ],
      [
        "status",
        200,
        status("deleted", {
          completed_at: new Date(NOW - 60001).toISOString(),
        }),
      ],
      [
        "status",
        200,
        status("deleted", {
          status_available_until: new Date(NOW).toISOString(),
        }),
      ],
      ["status", 200, status("deleted", { requested_at: "invalid" })],
      ["status", 200, { ...status("deleted"), private_email: "private" }],
    ]
  ) {
    const f = fixture();
    await f.ready(purpose);
    f.enqueue(code, data);
    assert.equal(await f.controller.send(), false);
    assert.equal(f.controller.getState().phase, "ambiguous");
    assert.equal(f.controller.getState().result, null);
  }
  const broken = fixture();
  await broken.ready();
  broken.replies.push({
    status: 202,
    json: async () => {
      throw new Error("Truncated JSON");
    },
  });
  assert.equal(await broken.controller.send(), false);
  assert.equal(broken.controller.getState().phase, "ambiguous");
});

test("invalid or oversized Google callback and UUID collisions cannot send a proof", async () => {
  for (
    const token of [
      undefined,
      "",
      "not-a-jwt",
      `header.${"p".repeat(16384)}.signature`,
    ]
  ) {
    const f = fixture();
    await f.controller.initialize();
    f.enqueue(201, challenge("status"));
    await f.controller.start("status");
    assert.equal(
      f.controller.acceptProof(f.controller.getState().generation, token),
      false,
    );
    assert.equal(f.controller.getState().phase, "error");
    assert.equal(await f.controller.send(), false);
  }
  const collision = fixture({ uuid: () => CHALLENGE_ID });
  await collision.ready();
  assert.equal(await collision.controller.send(), false);
  assert.equal(collision.calls.length, 2);
  const invalid = fixture({ uuid: () => "bad" });
  await invalid.controller.initialize();
  assert.equal(await invalid.controller.start("status"), false);
  assert.equal(invalid.calls.length, 1);
});

test("a new proof cannot reuse the previous proof UUID, even across distinct challenges", async () => {
  const ids = [
    "89d80a0a-594e-4943-bab6-a4b3a8130001",
    "89d80a0a-594e-4943-bab6-a4b3a8130002",
    "89d80a0a-594e-4943-bab6-a4b3a8130003",
    "89d80a0a-594e-4943-bab6-a4b3a8130002",
  ];
  const f = fixture({ uuid: () => ids.shift() });
  await f.ready("status");
  f.enqueue(200, status("not_requested"));
  assert.equal(await f.controller.send(), true);
  f.enqueue(
    201,
    challenge("status", {
      challenge_id: "89d80a0a-594e-4943-bab6-a4b3a8130999",
    }),
  );
  assert.equal(await f.controller.start("status"), true);
  assert.equal(
    f.controller.acceptProof(f.controller.getState().generation, TOKEN),
    true,
  );
  assert.equal(await f.controller.send(), false);
  assert.equal(
    f.calls.length,
    4,
    "Reused UUID must not reach the proof endpoint",
  );
});

test("status response loss replays its exact body and UUID; Google loader failure wipes the challenge", async () => {
  const f = fixture();
  await f.ready("status");
  f.replies.push(() => {
    throw new Error("lost");
  });
  assert.equal(await f.controller.send(), false);
  const original = f.calls.at(-1);
  f.enqueue(200, status("deleted"));
  assert.equal(await f.controller.retry(), true);
  assert.equal(f.calls.at(-1).body, original.body);
  assert.deepEqual(f.calls.at(-1).headers, original.headers);
  f.enqueue(201, challenge("status"));
  await f.controller.start("status");
  const current = f.controller.getState().generation;
  f.controller.authenticationFailed(current - 1);
  assert.equal(f.controller.getState().phase, "authenticating");
  f.controller.authenticationFailed(current);
  assert.equal(f.controller.googleOptions(() => {}), null);
  assert.equal(f.controller.acceptProof(current, TOKEN), false);
});

test("clearing a finished or failed deletion never claims the earlier request was not sent", async () => {
  for (const code of [202, 401]) {
    const f = fixture();
    await f.ready();
    f.enqueue(code, code === 202 ? { state: "deleting" } : {});
    await f.controller.send();
    f.controller.cancel();
    assert.match(
      f.controller.getState().message,
      /이미 전송한 요청은 철회되지 않습니다/,
    );
    assert.doesNotMatch(
      f.controller.getState().message,
      /삭제 요청을 보내지 않았습니다/,
    );
  }
});

test("a request timeout is ambiguous and aborts transport without inventing completion", async () => {
  const f = fixture({ timeoutMs: 5 });
  await f.ready();
  f.replies.push(() => new Promise(() => {}));
  assert.equal(await f.controller.send(), false);
  assert.equal(f.calls.at(-1).signal.aborted, true);
  assert.equal(f.controller.getState().phase, "ambiguous");
  assert.equal(f.controller.getState().result, null);
  f.controller.cancel();
});
