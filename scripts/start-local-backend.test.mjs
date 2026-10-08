import assert from "node:assert/strict";
import test from "node:test";
import {
  startLocalBackend,
  waitForBackendHealth,
} from "./start-local-backend.mjs";

test("successful CLI cannot conceal a stopped Edge runtime", async () => {
  assert.deepEqual(
    await startLocalBackend({
      execute: () => ({ status: 0, stdout: "private credentials", stderr: "" }),
      health: async () => false,
    }),
    { ok: false, code: "LOCAL_BACKEND_HEALTH_UNAVAILABLE" },
  );
});

test("successful CLI requires actual healthy service response", async () => {
  let checked = false;
  assert.deepEqual(
    await startLocalBackend({
      execute: () => ({ status: 0 }),
      health: async () => {
        checked = true;
        return true;
      },
    }),
    { ok: true },
  );
  assert.equal(checked, true);
});

test("CLI failure does not probe or expose credentials", async () => {
  const result = await startLocalBackend({
    execute: () => ({ status: 1, stdout: "secret", stderr: "private URL" }),
    health: () => {
      assert.fail("health must not run after failed start");
    },
  });
  assert.deepEqual(result, { ok: false, code: "LOCAL_BACKEND_START_FAILED" });
});

test("execution and health exceptions are fixed-code failures", async () => {
  assert.deepEqual(
    await startLocalBackend({
      execute: () => {
        throw Error("secret");
      },
    }),
    { ok: false, code: "LOCAL_BACKEND_START_FAILED" },
  );
  assert.deepEqual(
    await startLocalBackend({
      execute: () => ({ status: 0 }),
      health: () => {
        throw Error("secret");
      },
    }),
    { ok: false, code: "LOCAL_BACKEND_HEALTH_UNAVAILABLE" },
  );
});

test("health retries transient failures and requires the expected JSON status", async () => {
  const replies = [
    () => {
      throw Error("private transport information");
    },
    () => new Response("unavailable", { status: 503 }),
    () => new Response("not JSON", { status: 200 }),
    () => Response.json({ status: "error" }),
    () => Response.json({ status: "ok" }),
  ];
  let waits = 0;
  let calls = 0;
  assert.equal(
    await waitForBackendHealth({
      attempts: 5,
      wait: async () => {
        waits++;
      },
      request: async (url, options) => {
        assert.equal(
          url,
          "http://127.0.0.1:18021/functions/v1/library-api/v1/health",
        );
        assert.equal(options.redirect, "error");
        assert.ok(options.signal instanceof AbortSignal);
        return replies[calls++]();
      },
    }),
    true,
  );
  assert.equal(calls, 5);
  assert.equal(waits, 4);
});

test("health exhaustion is bounded and cancels rejected response bodies", async () => {
  let cancelled = 0;
  let calls = 0;
  let waits = 0;
  assert.equal(
    await waitForBackendHealth({
      attempts: 3,
      wait: async () => {
        waits++;
      },
      request: async () => {
        calls++;
        return {
          status: 503,
          body: {
            cancel: async () => {
              cancelled++;
            },
          },
        };
      },
    }),
    false,
  );
  assert.equal(calls, 3);
  assert.equal(cancelled, 3);
  assert.equal(waits, 2);
});
