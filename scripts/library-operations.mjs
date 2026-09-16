import { pathToFileURL } from "node:url";
import { localBackendConfig } from "./local-backend-fixture.mjs";

const COUNT_FIELDS = [
  "account_deletions_pending",
  "account_deletions_retry",
  "account_deletions_overdue",
  "item_deletions_pending",
  "item_deletions_overdue",
  "asset_cleanups_pending",
  "asset_cleanups_overdue",
  "asset_cleanups_retry",
  "expired_account_leases",
  "usage_mismatches",
  "untracked_storage_objects",
];

export function evaluateOperationStatus(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("INVALID_OPERATION_STATUS");
  }
  const output = {};
  for (const field of COUNT_FIELDS) {
    if (!Number.isSafeInteger(value[field]) || value[field] < 0) {
      throw new Error("INVALID_OPERATION_STATUS");
    }
    output[field] = value[field];
  }
  if (typeof value.maintenance_scheduled !== "boolean") {
    throw new Error("INVALID_OPERATION_STATUS");
  }
  output.maintenance_scheduled = value.maintenance_scheduled;
  for (
    const field of [
      "checked_at",
      "ledger_export_verified_at",
      "maintenance_last_dispatch_succeeded_at",
    ]
  ) {
    const timestamp = value[field];
    if (timestamp === null && field !== "checked_at") {
      output[field] = null;
    } else if (
      typeof timestamp === "string" &&
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(
        timestamp,
      ) &&
      Number.isFinite(Date.parse(timestamp))
    ) {
      output[field] = timestamp;
    } else {
      throw new Error("INVALID_OPERATION_STATUS");
    }
  }
  const warnings = [];
  if (!output.maintenance_scheduled) {
    warnings.push("MAINTENANCE_SCHEDULE_MISSING");
  }
  const dispatch = output.maintenance_last_dispatch_succeeded_at;
  if (
    dispatch === null ||
    Date.parse(output.checked_at) - Date.parse(dispatch) > 15 * 60_000
  ) {
    warnings.push("MAINTENANCE_DISPATCH_STALE");
  }
  if (
    output.account_deletions_overdue || output.item_deletions_overdue ||
    output.asset_cleanups_overdue
  ) {
    warnings.push("DELETION_TARGET_EXCEEDED");
  }
  if (output.expired_account_leases) {
    warnings.push("ACCOUNT_LEASE_RECOVERY_DUE");
  }
  if (output.account_deletions_retry || output.asset_cleanups_retry) {
    warnings.push("CLEANUP_RETRY_PENDING");
  }
  if (output.usage_mismatches) warnings.push("USAGE_MISMATCH");
  if (output.untracked_storage_objects) {
    warnings.push("UNTRACKED_STORAGE_OBJECTS");
  }
  if (output.ledger_export_verified_at === null) {
    warnings.push("DELETION_LEDGER_NOT_EXPORTED");
  }
  return { ...output, warnings };
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args.length === 1 && args[0] !== "--run")) {
    throw new Error("INVALID_OPERATION_ARGUMENTS");
  }
  const config = localBackendConfig();
  async function request(path, body) {
    const response = await fetch(`${config.API_URL}${path}`, {
      method: "POST",
      headers: {
        apikey: config.ANON_KEY,
        Authorization: `Bearer ${config.SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
        Connection: "close",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(120_000),
    });
    if (!response.ok) throw new Error("LOCAL_OPERATION_REQUEST_FAILED");
    return await response.json();
  }
  if (args[0] === "--run") {
    await request("/functions/v1/library-api/v1/internal/maintenance", {
      limit: 10,
    });
  }
  const status = evaluateOperationStatus(
    await request("/rest/v1/rpc/library_operation_status", {}),
  );
  console.log(JSON.stringify(status, null, 2));
  // A successfully queued cron HTTP request is not proof that its worker succeeded.
  // Pending leases, deadlines and accounting are checked separately above.
  if (status.warnings.length > 0) process.exitCode = 1;
}

if (
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main().catch(() => {
    console.error("LOCAL_OPERATIONS_FAILED");
    process.exitCode = 1;
  });
}
