import { assertEquals, assertRejects } from "@std/assert";
import type {
  AssetObjectDownload,
  AssetRpcResult,
  ChangeAssetCall,
  CleanupAssetCall,
  CompleteAssetCall,
  DownloadAssetObjectCall,
  FailAssetCleanupCall,
  LookupAssetCall,
  RejectAssetUploadCall,
  RemoveAssetObjectCall,
  ReplayAssetRequestCall,
  ReserveAssetCall,
} from "./asset-service.ts";
import {
  type AccountDeletionJobCall,
  type AccountStorageObject,
  createMaintenanceHandler,
  type DeletionGateway,
  type DeletionRpcResult,
  type FailAccountDeletionCall,
  isInternalMaintenancePath,
  type MaintenanceGateway,
  runDeletionBatch,
  runMaintenanceBatch,
} from "./deletion-worker.ts";

const OWNER_ONE = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const OWNER_TWO = "3d8752d2-47bb-4f4c-b2c7-7a3590eb02a9";
const REQUEST_ONE = "5e51d680-b8d8-4f7a-a29f-d764f2965aa2";
const REQUEST_TWO = "264c6a49-04a0-45c4-954f-773f97c0c4bb";
const LEASE_ONE = "8ff75967-3313-4a63-8131-e606dd63b087";
const LEASE_TWO = "71baadfe-6a88-43a3-b0fb-f111f18ef97f";
const OBJECT_ID = "80b470ca-233e-4c31-a379-47495d2e5a4c";

Deno.test("account deletion removes Storage before business data and Auth, then finalizes", async () => {
  const gateway = new FakeDeletionGateway();
  gateway.jobs = [job(OWNER_ONE, REQUEST_ONE, LEASE_ONE)];
  gateway.prepareResults = [
    prepareResult([{
      object_id: OBJECT_ID,
      bucket_id: "legacy",
      object_path: "old/file",
    }]),
    prepareResult([]),
  ];

  const result = await runDeletionBatch(gateway, 1);

  assertEquals(result, {
    claimed: 1,
    storage_objects_deleted: 1,
    business_purged: 1,
    auth_users_deleted: 1,
    completed: 1,
    retry_scheduled: 0,
    failed: 0,
    lease_lost: 0,
    error_codes: [],
  });
  assertEquals(gateway.events, [
    "claim:1",
    `prepare:${OWNER_ONE}`,
    "storage:legacy:old/file",
    `prepare:${OWNER_ONE}`,
    `purge:${OWNER_ONE}`,
    `auth:${OWNER_ONE}`,
    `finalize:${OWNER_ONE}`,
  ]);
});

Deno.test("already absent Auth user is idempotently finalized", async () => {
  const gateway = new FakeDeletionGateway();
  gateway.jobs = [job(OWNER_ONE, REQUEST_ONE, LEASE_ONE, true)];
  gateway.authStatuses.set(OWNER_ONE, "already_absent");

  const result = await runDeletionBatch(gateway, 1);

  assertEquals(result.completed, 1);
  assertEquals(result.auth_users_deleted, 0);
  assertEquals(gateway.purgeCalls, []);
  assertEquals(gateway.finalizeCalls, [{
    ownerId: OWNER_ONE,
    leaseToken: LEASE_ONE,
  }]);
});

Deno.test("Storage failure schedules retry and does not block the next account job", async () => {
  const gateway = new FakeDeletionGateway();
  gateway.jobs = [
    job(OWNER_ONE, REQUEST_ONE, LEASE_ONE),
    job(OWNER_TWO, REQUEST_TWO, LEASE_TWO),
  ];
  gateway.prepareByOwner.set(
    OWNER_ONE,
    prepareResult([{
      object_id: OBJECT_ID,
      bucket_id: "library-images",
      object_path: "bad/path",
    }]),
  );
  gateway.storageFailures.add("bad/path");

  const result = await runDeletionBatch(gateway, 2);

  assertEquals(result.claimed, 2);
  assertEquals(result.retry_scheduled, 1);
  assertEquals(result.completed, 1);
  assertEquals(result.error_codes, ["ACCOUNT_STORAGE_DELETE_FAILED"]);
  assertEquals(gateway.purgeCalls.map((call) => call.ownerId), [OWNER_TWO]);
  assertEquals(gateway.authCalls, [OWNER_TWO]);
  assertEquals(gateway.failCalls, [{
    ownerId: OWNER_ONE,
    leaseToken: LEASE_ONE,
    errorCode: "ACCOUNT_STORAGE_DELETE_FAILED",
  }]);
});

Deno.test("ownership conflict never deletes a foreign object or purges the account", async () => {
  const gateway = new FakeDeletionGateway();
  gateway.jobs = [job(OWNER_ONE, REQUEST_ONE, LEASE_ONE)];
  gateway.prepareByOwner.set(OWNER_ONE, prepareResult([], 1));

  const result = await runDeletionBatch(gateway, 1);

  assertEquals(result.retry_scheduled, 1);
  assertEquals(result.error_codes, ["ACCOUNT_STORAGE_OWNERSHIP_CONFLICT"]);
  assertEquals(gateway.storageCalls, []);
  assertEquals(gateway.purgeCalls, []);
  assertEquals(gateway.authCalls, []);
});

Deno.test("stale lease stops processing without attempting a stale failure transition", async () => {
  const gateway = new FakeDeletionGateway();
  gateway.jobs = [job(OWNER_ONE, REQUEST_ONE, LEASE_ONE)];
  gateway.prepareByOwner.set(OWNER_ONE, {
    data: { http_status: 409, error_code: "LEASE_LOST" },
    error: null,
  });

  const result = await runDeletionBatch(gateway, 1);

  assertEquals(result.lease_lost, 1);
  assertEquals(result.error_codes, ["LEASE_LOST"]);
  assertEquals(gateway.failCalls, []);
  assertEquals(gateway.purgeCalls, []);
  assertEquals(gateway.authCalls, []);
});

Deno.test("deletion batch validates its bounded limit", async () => {
  const gateway = new FakeDeletionGateway();
  await assertRejects(() => runDeletionBatch(gateway, 0), TypeError);
  await assertRejects(() => runDeletionBatch(gateway, 11), TypeError);
  assertEquals(gateway.claimedLimits, []);
});

Deno.test("maintenance path and handler require exact service authentication and body", async () => {
  assertEquals(isInternalMaintenancePath("/v1/internal/maintenance"), true);
  assertEquals(
    isInternalMaintenancePath(
      "/functions/v1/library-api/v1/internal/maintenance",
    ),
    true,
  );
  assertEquals(
    isInternalMaintenancePath("/v1/internal/maintenance/extra"),
    false,
  );

  const gateway = new FakeMaintenanceGateway();
  const handler = createMaintenanceHandler(gateway, "service-secret");
  const wrongMethod = await handler(request("/v1/internal/maintenance", {
    method: "GET",
  }));
  assertEquals(wrongMethod.status, 405);
  assertEquals(wrongMethod.headers.get("allow"), "POST");

  const denied = await handler(request("/v1/internal/maintenance", {
    method: "POST",
    headers: {
      authorization: "Bearer caller-token",
      "content-type": "application/json",
    },
    body: JSON.stringify({ limit: 1 }),
  }));
  assertEquals(denied.status, 401);
  assertEquals(await denied.json(), { error_code: "UNAUTHENTICATED" });

  const invalid = await handler(request("/v1/internal/maintenance?force=true", {
    method: "POST",
    headers: {
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    },
    body: JSON.stringify({ limit: 1 }),
  }));
  assertEquals(invalid.status, 400);
  assertEquals(await invalid.json(), { error_code: "INVALID_QUERY" });

  const outOfBounds = await handler(request("/v1/internal/maintenance", {
    method: "POST",
    headers: {
      authorization: "Bearer service-secret",
      "content-type": "application/json",
    },
    body: JSON.stringify({ limit: 11 }),
  }));
  assertEquals(outOfBounds.status, 400);
  assertEquals(await outOfBounds.json(), { error_code: "INVALID_BODY" });
});

Deno.test("maintenance runs account, asset, item purge, then retention with one bound", async () => {
  const gateway = new FakeMaintenanceGateway();
  const result = await runMaintenanceBatch(gateway, 3);

  assertEquals(gateway.events, [
    "claim:3",
    "asset-claim:3",
    "item-purge:3",
    "retention",
  ]);
  assertEquals(result.item_purge, { purged_items: 2 });
  assertEquals(result.retention.deleted_challenges, 3);
});

class FakeDeletionGateway implements DeletionGateway {
  jobs: unknown[] = [];
  prepareResults: DeletionRpcResult[] = [];
  prepareByOwner = new Map<string, DeletionRpcResult>();
  storageFailures = new Set<string>();
  authStatuses = new Map<string, "deleted" | "already_absent" | "failed">();
  claimedLimits: number[] = [];
  prepareCalls: AccountDeletionJobCall[] = [];
  storageCalls: AccountStorageObject[] = [];
  purgeCalls: AccountDeletionJobCall[] = [];
  authCalls: string[] = [];
  finalizeCalls: AccountDeletionJobCall[] = [];
  failCalls: FailAccountDeletionCall[] = [];
  events: string[] = [];

  claimAccountDeletionJobs(limit: number): Promise<DeletionRpcResult> {
    this.claimedLimits.push(limit);
    this.events.push(`claim:${limit}`);
    return Promise.resolve({
      data: { http_status: 200, jobs: this.jobs },
      error: null,
    });
  }

  prepareAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult> {
    this.prepareCalls.push(call);
    this.events.push(`prepare:${call.ownerId}`);
    return Promise.resolve(
      this.prepareResults.shift() ?? this.prepareByOwner.get(call.ownerId) ??
        prepareResult([]),
    );
  }

  removeAccountStorageObject(
    call: AccountStorageObject,
  ): Promise<{ status: "deleted" | "already_absent" | "failed" }> {
    this.storageCalls.push(call);
    this.events.push(`storage:${call.bucketId}:${call.objectPath}`);
    return Promise.resolve({
      status: this.storageFailures.has(call.objectPath) ? "failed" : "deleted",
    });
  }

  purgeAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult> {
    this.purgeCalls.push(call);
    this.events.push(`purge:${call.ownerId}`);
    return Promise.resolve({
      data: {
        http_status: 200,
        state: "ready_for_auth_delete",
        released_reserved_bytes: 0,
        released_used_bytes: 0,
      },
      error: null,
    });
  }

  deleteAuthUser(
    ownerId: string,
  ): Promise<{ status: "deleted" | "already_absent" | "failed" }> {
    this.authCalls.push(ownerId);
    this.events.push(`auth:${ownerId}`);
    return Promise.resolve({
      status: this.authStatuses.get(ownerId) ?? "deleted",
    });
  }

  finalizeAccountDeletion(
    call: AccountDeletionJobCall,
  ): Promise<DeletionRpcResult> {
    this.finalizeCalls.push(call);
    this.events.push(`finalize:${call.ownerId}`);
    return Promise.resolve({
      data: { http_status: 200, state: "complete" },
      error: null,
    });
  }

  failAccountDeletion(
    call: FailAccountDeletionCall,
  ): Promise<DeletionRpcResult> {
    this.failCalls.push(call);
    this.events.push(`fail:${call.ownerId}:${call.errorCode}`);
    return Promise.resolve({
      data: {
        http_status: 202,
        state: "retry",
        next_run_at: "2026-09-16T00:05:00Z",
        attempts: 1,
        error_code: call.errorCode,
      },
      error: null,
    });
  }
}

class FakeMaintenanceGateway extends FakeDeletionGateway
  implements MaintenanceGateway {
  replayAssetRequest(_call: ReplayAssetRequestCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  reserveAsset(_call: ReserveAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  lookupAsset(_call: LookupAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  downloadAssetObject(
    _call: DownloadAssetObjectCall,
  ): Promise<AssetObjectDownload> {
    return Promise.resolve({ status: "missing" });
  }
  completeAsset(_call: CompleteAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  rejectAssetUpload(_call: RejectAssetUploadCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  deleteAsset(_call: ChangeAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  updateAssetOcr(_call: ChangeAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  claimAssetCleanupJobs(limit: number): Promise<AssetRpcResult> {
    this.events.push(`asset-claim:${limit}`);
    return Promise.resolve({
      data: { http_status: 200, jobs: [] },
      error: null,
    });
  }
  removeAssetObject(
    _call: RemoveAssetObjectCall,
  ): Promise<{ error: { code?: string; message?: string } | null }> {
    return Promise.resolve({ error: null });
  }
  finishAssetCleanup(_call: CleanupAssetCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  failAssetCleanup(_call: FailAssetCleanupCall): Promise<AssetRpcResult> {
    return Promise.resolve({ data: null, error: null });
  }
  purgeDeletedItems(limit: number): Promise<DeletionRpcResult> {
    this.events.push(`item-purge:${limit}`);
    return Promise.resolve({
      data: { purged_items: 2 },
      error: null,
    });
  }
  runRetention(): Promise<DeletionRpcResult> {
    this.events.push("retention");
    return Promise.resolve({
      data: {
        http_status: 200,
        deleted_api_requests: 1,
        deleted_rate_buckets: 2,
        deleted_challenges: 3,
        deleted_item_tombstones: 4,
        deleted_cancelled_jobs: 5,
        deleted_account_jobs: 6,
        deleted_ledger_events: 7,
      },
      error: null,
    });
  }
}

function job(
  ownerId: string,
  requestId: string,
  leaseToken: string,
  businessPurged = false,
) {
  return {
    owner_id: ownerId,
    request_id: requestId,
    attempts: 1,
    lease_token: leaseToken,
    lease_until: "2026-09-16T00:05:00Z",
    business_purged: businessPurged,
  };
}

function prepareResult(
  objects: { object_id: string; bucket_id: string; object_path: string }[],
  ownershipConflictCount = 0,
): DeletionRpcResult {
  return {
    data: {
      http_status: 200,
      state: "storage_cleanup",
      objects,
      object_count: objects.length,
      ownership_conflict_count: ownershipConflictCount,
      asset_count: objects.length,
    },
    error: null,
  };
}

function request(path: string, init: RequestInit): Request {
  return new Request(`https://example.test${path}`, init);
}
