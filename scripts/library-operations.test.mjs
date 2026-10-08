import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  evaluateOperationStatus,
  runLibraryOperations,
} from "./library-operations.mjs";

const healthy = () => ({
  checked_at: "2026-09-16T00:10:00+00:00",
  account_deletions_pending: 0,
  account_deletions_retry: 0,
  account_deletions_overdue: 0,
  item_deletions_pending: 0,
  item_deletions_overdue: 0,
  asset_cleanups_pending: 0,
  asset_cleanups_overdue: 0,
  asset_cleanups_retry: 0,
  expired_account_leases: 0,
  usage_mismatches: 0,
  untracked_storage_objects: 0,
  ledger_export_verified_at: "2026-09-16T00:00:00Z",
  maintenance_scheduled: true,
  maintenance_last_dispatch_succeeded_at: "2026-09-16T00:05:00Z",
});

test("recent dispatch cannot conceal cleanup deadlines or accounting failures", () => {
  const value = healthy();
  value.account_deletions_pending = 1;
  value.account_deletions_overdue = 1;
  value.usage_mismatches = 2;
  value.expired_account_leases = 1;
  assert.deepEqual(evaluateOperationStatus(value).warnings, [
    "DELETION_TARGET_EXCEEDED",
    "ACCOUNT_LEASE_RECOVERY_DUE",
    "USAGE_MISMATCH",
  ]);
});

test("paused or unprovisioned maintenance and missing ledger remain visible", () => {
  const value = healthy();
  value.maintenance_scheduled = false;
  value.maintenance_last_dispatch_succeeded_at = null;
  value.ledger_export_verified_at = null;
  value.untracked_storage_objects = 1;
  assert.deepEqual(evaluateOperationStatus(value).warnings, [
    "MAINTENANCE_SCHEDULE_MISSING",
    "MAINTENANCE_DISPATCH_STALE",
    "UNTRACKED_STORAGE_OBJECTS",
    "DELETION_LEDGER_NOT_EXPORTED",
  ]);
});

test("stale dispatch boundary is fifteen minutes and pending work stays healthy", () => {
  const value = healthy();
  value.checked_at = "2026-09-16T00:20:00Z";
  value.account_deletions_pending = 1;
  value.item_deletions_pending = 1;
  value.asset_cleanups_pending = 1;
  assert.deepEqual(evaluateOperationStatus(value).warnings, []);
  value.checked_at = "2026-09-16T00:20:00.001Z";
  assert.deepEqual(evaluateOperationStatus(value).warnings, [
    "MAINTENANCE_DISPATCH_STALE",
  ]);
});

test("each overdue counter independently emits the deletion warning", () => {
  for (
    const field of [
      "account_deletions_overdue",
      "item_deletions_overdue",
      "asset_cleanups_overdue",
    ]
  ) {
    const value = healthy();
    value[field] = 1;
    assert.deepEqual(
      evaluateOperationStatus(value).warnings,
      ["DELETION_TARGET_EXCEEDED"],
      field,
    );
  }
});

test("worker cleanup retries remain visible after successful HTTP dispatch", () => {
  const value = healthy();
  value.account_deletions_retry = 1;
  value.asset_cleanups_retry = 1;
  assert.deepEqual(evaluateOperationStatus(value).warnings, [
    "CLEANUP_RETRY_PENDING",
  ]);
});

test("output ignores raw messages and rejects data disguised as counters or dates", () => {
  const value = healthy();
  value.url = "https://private.invalid/example";
  value.error_message = "private fixture content";
  assert.equal(
    JSON.stringify(evaluateOperationStatus(value)).includes("private"),
    false,
  );
  assert.throws(
    () => evaluateOperationStatus({ ...value, checked_at: value.url }),
    /INVALID_OPERATION_STATUS/,
  );
  assert.throws(
    () =>
      evaluateOperationStatus({
        ...value,
        used_image_bytes: 1,
        usage_mismatches: -1,
      }),
    /INVALID_OPERATION_STATUS/,
  );
  assert.throws(
    () => evaluateOperationStatus({ ...value, account_deletions_pending: "1" }),
    /INVALID_OPERATION_STATUS/,
  );
});

test("read-only execution prints only validated metrics and returns success for retained items", async () => {
  const value = {
    ...healthy(),
    item_deletions_pending: 2,
    url: "https://private.invalid/example",
    error_message: "private fixture content",
    SERVICE_ROLE_KEY: "fixture-only-service-key",
  };
  const calls = [];
  const output = [];
  const errors = [];
  assert.equal(
    await runLibraryOperations({
      request: async (path, body) => {
        calls.push({ path, body });
        return value;
      },
      output: (message) => output.push(message),
      error: (message) => errors.push(message),
    }),
    0,
  );
  assert.deepEqual(calls, [
    { path: "/rest/v1/rpc/library_operation_status", body: {} },
  ]);
  assert.deepEqual(output, [
    JSON.stringify(evaluateOperationStatus(value), null, 2),
  ]);
  assert.doesNotMatch(output[0], /private|fixture-only-service-key/);
  assert.deepEqual(errors, []);
});

test("each operational warning returns failure even after a recent dispatch", async () => {
  const cases = [
    ["maintenance_scheduled", false, "MAINTENANCE_SCHEDULE_MISSING"],
    [
      "maintenance_last_dispatch_succeeded_at",
      null,
      "MAINTENANCE_DISPATCH_STALE",
    ],
    ["account_deletions_overdue", 1, "DELETION_TARGET_EXCEEDED"],
    ["item_deletions_overdue", 1, "DELETION_TARGET_EXCEEDED"],
    ["asset_cleanups_overdue", 1, "DELETION_TARGET_EXCEEDED"],
    ["expired_account_leases", 1, "ACCOUNT_LEASE_RECOVERY_DUE"],
    ["account_deletions_retry", 1, "CLEANUP_RETRY_PENDING"],
    ["asset_cleanups_retry", 1, "CLEANUP_RETRY_PENDING"],
    ["usage_mismatches", 1, "USAGE_MISMATCH"],
    ["untracked_storage_objects", 1, "UNTRACKED_STORAGE_OBJECTS"],
    ["ledger_export_verified_at", null, "DELETION_LEDGER_NOT_EXPORTED"],
  ];
  for (const [field, value, warning] of cases) {
    const output = [];
    const errors = [];
    assert.equal(
      await runLibraryOperations({
        request: async () => ({ ...healthy(), [field]: value }),
        output: (message) => output.push(message),
        error: (message) => errors.push(message),
      }),
      1,
      field,
    );
    assert.equal(output.length, 1, field);
    assert.deepEqual(JSON.parse(output[0]).warnings, [warning], field);
    assert.deepEqual(errors, [], field);
  }
});

test("only --run dispatches maintenance and then reads status", async () => {
  const calls = [];
  const output = [];
  assert.equal(
    await runLibraryOperations({
      args: ["--run"],
      request: async (path, body) => {
        calls.push({ path, body });
        return calls.length === 1 ? { queued: true } : healthy();
      },
      output: (message) => output.push(message),
      error: () => assert.fail("successful execution must not print an error"),
    }),
    0,
  );
  assert.deepEqual(calls, [
    {
      path: "/functions/v1/library-api/v1/internal/maintenance",
      body: { limit: 10 },
    },
    { path: "/rest/v1/rpc/library_operation_status", body: {} },
  ]);
  assert.deepEqual(JSON.parse(output[0]).warnings, []);
});

test("invalid arguments fail without reading status or dispatching maintenance", async () => {
  for (const args of [["--unknown"], ["--run", "--run"], ["--run", "extra"]]) {
    const calls = [];
    const output = [];
    const errors = [];
    assert.equal(
      await runLibraryOperations({
        args,
        request: async (path, body) => {
          calls.push({ path, body });
          return healthy();
        },
        output: (message) => output.push(message),
        error: (message) => errors.push(message),
      }),
      1,
    );
    assert.deepEqual(calls, []);
    assert.deepEqual(output, []);
    assert.deepEqual(errors, ["LOCAL_OPERATIONS_FAILED"]);
  }
});

test("request and invalid-status failures return a fixed redacted error", async () => {
  for (
    const request of [
      async () => {
        throw new Error(
          "fixture-only-service-key https://private.invalid/example",
        );
      },
      async () => null,
      async () => ({
        ...healthy(),
        item_deletions_pending: "private fixture content",
      }),
      async () => ({
        ...healthy(),
        checked_at: "https://private.invalid/example",
      }),
    ]
  ) {
    const output = [];
    const errors = [];
    assert.equal(
      await runLibraryOperations({
        request,
        output: (message) => output.push(message),
        error: (message) => errors.push(message),
      }),
      1,
    );
    assert.deepEqual(output, []);
    assert.deepEqual(errors, ["LOCAL_OPERATIONS_FAILED"]);
  }
});

test("failed explicit maintenance returns failure without a misleading status read", async () => {
  const calls = [];
  const output = [];
  const errors = [];
  assert.equal(
    await runLibraryOperations({
      args: ["--run"],
      request: async (path) => {
        calls.push(path);
        throw new Error("private maintenance response");
      },
      output: (message) => output.push(message),
      error: (message) => errors.push(message),
    }),
    1,
  );
  assert.deepEqual(calls, [
    "/functions/v1/library-api/v1/internal/maintenance",
  ]);
  assert.deepEqual(output, []);
  assert.deepEqual(errors, ["LOCAL_OPERATIONS_FAILED"]);
});

function spawnOperations(value, failure = null) {
  // Intercept credential discovery and HTTP before loading the real CLI; these
  // subprocesses never contact a backend or read production credentials.
  const mock = `
    import assert from "node:assert/strict";
    import childProcess from "node:child_process";
    import { syncBuiltinESMExports } from "node:module";
    childProcess.execSync = (command, options) => {
      assert.equal(command, "npx --no-install supabase status -o json");
      assert.deepEqual(options.stdio, ["ignore", "pipe", "pipe"]);
      return JSON.stringify({
        API_URL: "http://127.0.0.1:18021",
        ANON_KEY: "fixture-only-anon-key",
        SERVICE_ROLE_KEY: "fixture-only-service-key",
      });
    };
    syncBuiltinESMExports();
    globalThis.fetch = async (url, options) => {
      assert.equal(url, "http://127.0.0.1:18021/rest/v1/rpc/library_operation_status");
      assert.equal(options.method, "POST");
      assert.equal(options.headers.apikey, "fixture-only-anon-key");
      assert.equal(options.headers.Authorization, "Bearer fixture-only-service-key");
      assert.equal(options.headers["Content-Type"], "application/json");
      assert.equal(options.headers.Connection, "close");
      assert.equal(options.body, "{}");
      assert.ok(options.signal instanceof AbortSignal);
      const failure = ${JSON.stringify(failure)};
      if (failure === "request") throw new Error("fixture-only-service-key");
      return {
        ok: failure !== "http",
        json: async () => {
          if (failure === "json") throw new Error("private response body");
          return ${JSON.stringify(value)};
        },
      };
    };
  `;
  return spawnSync(process.execPath, [
    "--import",
    `data:text/javascript,${encodeURIComponent(mock)}`,
    fileURLToPath(new URL("./library-operations.mjs", import.meta.url)),
  ], { encoding: "utf8", timeout: 10_000 });
}

test("CLI entry point exits zero for retention pending and one for overdue work", () => {
  for (const [overdue, expectedExit] of [[0, 0], [1, 1]]) {
    const result = spawnOperations({
      ...healthy(),
      item_deletions_pending: 2,
      item_deletions_overdue: overdue,
      error_message: "private fixture content",
      SERVICE_ROLE_KEY: "fixture-only-service-key",
    });
    assert.equal(result.error, undefined);
    assert.equal(result.status, expectedExit);
    assert.equal(result.stderr, "");
    assert.deepEqual(
      JSON.parse(result.stdout).warnings,
      overdue ? ["DELETION_TARGET_EXCEEDED"] : [],
    );
    assert.doesNotMatch(result.stdout, /private|fixture-only-service-key/);
  }
});

test("CLI request, HTTP and JSON failures exit one without leaking credentials or content", () => {
  for (const failure of ["request", "http", "json"]) {
    const result = spawnOperations(healthy(), failure);
    assert.equal(result.error, undefined, failure);
    assert.equal(result.status, 1, failure);
    assert.equal(result.stdout, "", failure);
    assert.equal(result.stderr.trim(), "LOCAL_OPERATIONS_FAILED", failure);
  }
});

test("importing operation helpers neither discovers credentials nor sends requests", () => {
  const source = `
    import assert from "node:assert/strict";
    import childProcess from "node:child_process";
    import { syncBuiltinESMExports } from "node:module";
    let calls = 0;
    childProcess.execSync = () => { calls++; throw new Error("unexpected credential lookup"); };
    syncBuiltinESMExports();
    globalThis.fetch = () => { calls++; throw new Error("unexpected request"); };
    await import(${
    JSON.stringify(new URL("./library-operations.mjs", import.meta.url).href)
  });
    assert.equal(calls, 0);
  `;
  const result = spawnSync(process.execPath, [
    "--input-type=module",
    "--eval",
    source,
  ], {
    encoding: "utf8",
    timeout: 10_000,
  });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "");
});
