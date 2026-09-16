import assert from "node:assert/strict";
import test from "node:test";
import { evaluateOperationStatus } from "./library-operations.mjs";

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

test("stale dispatch boundary is fifteen minutes and preserves pending work", () => {
  const value = healthy();
  value.checked_at = "2026-09-16T00:20:00Z";
  value.item_deletions_pending = 1;
  assert.deepEqual(evaluateOperationStatus(value).warnings, []);
  value.checked_at = "2026-09-16T00:20:00.001Z";
  assert.deepEqual(evaluateOperationStatus(value).warnings, [
    "MAINTENANCE_DISPATCH_STALE",
  ]);
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
