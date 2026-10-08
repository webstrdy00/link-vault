import { assertEquals, assertRejects } from "@std/assert";
import {
  type AssetGateway,
  type AssetObjectDownload,
  type AssetRpcResult,
  AssetServiceUnavailableError,
  type ChangeAssetCall,
  type CleanupAssetCall,
  type CompleteAssetCall,
  completeAssetUpload,
  createAssetCleanupHandler,
  type DownloadAssetObjectCall,
  type FailAssetCleanupCall,
  isInternalAssetCleanupPath,
  type LookupAssetCall,
  prepareAssetIndex,
  readActiveAssetContent,
  readBoundedAssetResponse,
  type RejectAssetUploadCall,
  type RemoveAssetObjectCall,
  type ReplayAssetRequestCall,
  type ReserveAssetCall,
  runAssetCleanupBatch,
} from "./asset-service.ts";
import type { ItemUpdateSnapshot } from "./item-preparation.ts";

const OWNER_ID = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const ITEM_ID = "3d8752d2-47bb-4f4c-b2c7-7a3590eb02a9";
const ASSET_ID = "8ff75967-3313-4a63-8131-e606dd63b087";
const SECOND_ASSET_ID = "264c6a49-04a0-45c4-954f-773f97c0c4bb";
const REQUEST_ID = "5e51d680-b8d8-4f7a-a29f-d764f2965aa2";
const LEASE_ID = "71baadfe-6a88-43a3-b0fb-f111f18ef97f";
const SECOND_LEASE_ID = "37c657a6-23db-4dce-8839-3390b3898343";
const PNG = fromBase64(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
);

class FakeAssetGateway implements AssetGateway {
  replayResult: AssetRpcResult = { data: { found: false }, error: null };
  reserveResult: AssetRpcResult = {
    data: {
      http_status: 201,
      asset_id: ASSET_ID,
      object_path: assetPath(ASSET_ID),
      expires_at: "2026-09-15T12:00:00Z",
      max_bytes: 2_000_000,
    },
    error: null,
  };
  lookupResult: AssetRpcResult = {
    data: assetRecord(ASSET_ID, "reserved", "image/png", null),
    error: null,
  };
  downloadResult: AssetObjectDownload = {
    status: "found",
    bytes: PNG,
    mimeType: "image/png",
  };
  completeResult: AssetRpcResult = {
    data: { http_status: 200, item: { id: ITEM_ID, version: 5 } },
    error: null,
  };
  rejectResult: AssetRpcResult = {
    data: { http_status: 409, error_code: "ASSET_DECODE_FAILED" },
    error: null,
  };
  deleteResult: AssetRpcResult = {
    data: { http_status: 202, asset_id: ASSET_ID, state: "deleting" },
    error: null,
  };
  ocrResult: AssetRpcResult = {
    data: { http_status: 200, item: { id: ITEM_ID, version: 5 } },
    error: null,
  };
  claimResult: AssetRpcResult = {
    data: { http_status: 200, jobs: [] },
    error: null,
  };
  finishResult: AssetRpcResult = finishResult(ASSET_ID);
  failResult: AssetRpcResult = retryResult(ASSET_ID);
  removeErrorFor = new Set<string>();

  replayCalls: ReplayAssetRequestCall[] = [];
  reserveCalls: ReserveAssetCall[] = [];
  lookupCalls: LookupAssetCall[] = [];
  downloadCalls: DownloadAssetObjectCall[] = [];
  completeCalls: CompleteAssetCall[] = [];
  rejectCalls: RejectAssetUploadCall[] = [];
  deleteCalls: ChangeAssetCall[] = [];
  ocrCalls: ChangeAssetCall[] = [];
  claimedLimits: number[] = [];
  removeCalls: RemoveAssetObjectCall[] = [];
  finishCalls: CleanupAssetCall[] = [];
  failCalls: FailAssetCleanupCall[] = [];

  replayAssetRequest(call: ReplayAssetRequestCall): Promise<AssetRpcResult> {
    this.replayCalls.push(call);
    return Promise.resolve(this.replayResult);
  }

  reserveAsset(call: ReserveAssetCall): Promise<AssetRpcResult> {
    this.reserveCalls.push(call);
    return Promise.resolve(this.reserveResult);
  }

  lookupAsset(call: LookupAssetCall): Promise<AssetRpcResult> {
    this.lookupCalls.push(call);
    return Promise.resolve(this.lookupResult);
  }

  downloadAssetObject(
    call: DownloadAssetObjectCall,
  ): Promise<AssetObjectDownload> {
    this.downloadCalls.push(call);
    return Promise.resolve(this.downloadResult);
  }

  completeAsset(call: CompleteAssetCall): Promise<AssetRpcResult> {
    this.completeCalls.push(call);
    return Promise.resolve(this.completeResult);
  }

  rejectAssetUpload(call: RejectAssetUploadCall): Promise<AssetRpcResult> {
    this.rejectCalls.push(call);
    return Promise.resolve(this.rejectResult);
  }

  deleteAsset(call: ChangeAssetCall): Promise<AssetRpcResult> {
    this.deleteCalls.push(call);
    return Promise.resolve(this.deleteResult);
  }

  updateAssetOcr(call: ChangeAssetCall): Promise<AssetRpcResult> {
    this.ocrCalls.push(call);
    return Promise.resolve(this.ocrResult);
  }

  claimAssetCleanupJobs(limit: number): Promise<AssetRpcResult> {
    this.claimedLimits.push(limit);
    return Promise.resolve(this.claimResult);
  }

  removeAssetObject(
    call: RemoveAssetObjectCall,
  ): Promise<{ error: { message?: string } | null }> {
    this.removeCalls.push(call);
    return Promise.resolve({
      error: this.removeErrorFor.has(call.objectPath)
        ? { message: "raw provider secret must not escape" }
        : null,
    });
  }

  finishAssetCleanup(call: CleanupAssetCall): Promise<AssetRpcResult> {
    this.finishCalls.push(call);
    return Promise.resolve(
      call.assetId === ASSET_ID
        ? this.finishResult
        : finishResult(call.assetId),
    );
  }

  failAssetCleanup(call: FailAssetCleanupCall): Promise<AssetRpcResult> {
    this.failCalls.push(call);
    return Promise.resolve(
      call.assetId === ASSET_ID ? this.failResult : retryResult(call.assetId),
    );
  }
}

Deno.test("complete asset checks replay with server-derived identity before any storage work", async () => {
  const gateway = new FakeAssetGateway();
  gateway.replayResult = {
    data: {
      found: true,
      http_status: 200,
      item: { id: ITEM_ID, version: 5 },
    },
    error: null,
  };
  let snapshotLoads = 0;
  const body = {
    expected_version: 4,
    ocr_state: "not_requested" as const,
  };
  const result = await completeAssetUpload(gateway, {
    ownerId: OWNER_ID,
    itemId: ITEM_ID,
    assetId: ASSET_ID,
    requestId: REQUEST_ID,
    body,
    loadSnapshot() {
      snapshotLoads++;
      return Promise.resolve({ data: snapshot(), error: null });
    },
  });

  assertEquals(result.data, {
    http_status: 200,
    item: { id: ITEM_ID, version: 5 },
  });
  assertEquals(gateway.replayCalls, [{
    ownerId: OWNER_ID,
    itemId: ITEM_ID,
    assetId: ASSET_ID,
    requestId: REQUEST_ID,
    method: "POST",
    path: `/items/${ITEM_ID}/assets/${ASSET_ID}/complete`,
    body,
  }]);
  assertEquals(gateway.lookupCalls, []);
  assertEquals(gateway.downloadCalls, []);
  assertEquals(snapshotLoads, 0);
});

Deno.test("complete asset rejects malformed or injected replay results", async () => {
  for (
    const data of [
      { found: true, http_status: 200, item: { id: ITEM_ID }, extra: true },
      { found: true, http_status: 409, error_code: "ITEM_DELETED" },
      { found: false, http_status: 200 },
      { found: "false" },
    ]
  ) {
    const gateway = new FakeAssetGateway();
    gateway.replayResult = { data, error: null };
    await assertRejects(
      () => completeAssetUpload(gateway, completeInput()),
      AssetServiceUnavailableError,
    );
    assertEquals(gateway.downloadCalls, []);
  }
});

Deno.test("invalid image bytes use the atomic SQL reject path and never replace the old asset", async () => {
  const gateway = new FakeAssetGateway();
  gateway.downloadResult = {
    status: "found",
    bytes: new Uint8Array([0xff, 0xd8, 0xff]),
    mimeType: "image/jpeg",
  };
  gateway.lookupResult = {
    data: assetRecord(ASSET_ID, "reserved", "image/jpeg", null),
    error: null,
  };
  const result = await completeAssetUpload(gateway, completeInput());

  assertEquals(result.data, {
    http_status: 409,
    error_code: "ASSET_DECODE_FAILED",
  });
  assertEquals(gateway.completeCalls, []);
  assertEquals(gateway.rejectCalls.length, 1);
  assertEquals(gateway.rejectCalls[0].failureCode, "ASSET_DECODE_FAILED");
  assertEquals(gateway.rejectCalls[0].body, completeInput().body);
});

Deno.test("valid completion sends exact verified object and maps concept_index to alias_concepts", async () => {
  const gateway = new FakeAssetGateway();
  const rawOcr = "엑셀 ".repeat(10_001);
  const input = completeInput({
    expected_version: 4,
    ocr_state: "ready",
    ocr_text: rawOcr,
    ocr_truncated: true,
  });
  const result = await completeAssetUpload(gateway, input);

  assertEquals(result.data, {
    http_status: 200,
    item: { id: ITEM_ID, version: 5 },
  });
  assertEquals(gateway.completeCalls.length, 1);
  const call = gateway.completeCalls[0];
  assertEquals(call.verifiedObject, {
    object_path: assetPath(ASSET_ID),
    size: PNG.byteLength,
    mime_type: "image/png",
    width: 1,
    height: 1,
    present: true,
  });
  assertEquals(Object.keys(call.preparedIndex!).sort(), [
    "alias_concepts",
    "cue_flags",
    "cue_state",
    "normalized_fields",
    "snapshot_version",
  ]);
  assertEquals(call.preparedIndex!.snapshot_version, 4);
  assertEquals(call.preparedIndex!.alias_concepts.ocr, ["excel"]);
  assertEquals([...call.preparedIndex!.normalized_fields.ocr].length, 20_000);
  assertEquals(call.preparedIndex!.cue_flags.includes("truncated"), true);
});

Deno.test("prepared OCR index retains existing fields and maps aliases without raw fields", () => {
  const prepared = prepareAssetIndex(snapshot(), {
    ocr_state: "ready",
    ocr_text: "엑셀 작업표",
  });
  assertEquals(prepared.alias_concepts.ocr, ["excel"]);
  assertEquals(prepared.normalized_fields.user_title, "기존 제목");
  assertEquals(Object.hasOwn(prepared, "concept_index"), false);
  assertEquals(Object.hasOwn(prepared, "raw_ocr_text"), false);
});

Deno.test("active content validates owner, item, asset, canonical path, size, and stored MIME", async () => {
  const gateway = new FakeAssetGateway();
  gateway.lookupResult = {
    data: assetRecord(ASSET_ID, "active", "image/png", PNG.byteLength),
    error: null,
  };
  const content = await readActiveAssetContent(gateway, identity());
  assertEquals(content, { bytes: PNG, mimeType: "image/png" });

  const reserved = new FakeAssetGateway();
  assertEquals(await readActiveAssetContent(reserved, identity()), null);
  assertEquals(reserved.downloadCalls, []);

  const wrongOwner = new FakeAssetGateway();
  wrongOwner.lookupResult = {
    data: {
      ...assetRecord(ASSET_ID, "active", "image/png", PNG.byteLength),
      owner_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    },
    error: null,
  };
  await assertRejects(
    () => readActiveAssetContent(wrongOwner, identity()),
    AssetServiceUnavailableError,
  );
  assertEquals(wrongOwner.downloadCalls, []);

  const wrongPath = new FakeAssetGateway();
  wrongPath.lookupResult = {
    data: {
      ...assetRecord(ASSET_ID, "active", "image/png", PNG.byteLength),
      object_path: `${OWNER_ID}/${ITEM_ID}/../../other`,
    },
    error: null,
  };
  await assertRejects(
    () => readActiveAssetContent(wrongPath, identity()),
    AssetServiceUnavailableError,
  );
  assertEquals(wrongPath.downloadCalls, []);
});

Deno.test("bounded object reader streams bytes and stops above two million bytes", async () => {
  const found = await readBoundedAssetResponse(
    new Response(
      new Uint8Array([0, 1, 2, 255]),
      { headers: { "content-type": "image/png; charset=binary" } },
    ),
  );
  assertEquals(found, {
    status: "found",
    bytes: new Uint8Array([0, 1, 2, 255]),
    mimeType: "image/png",
  });

  const tooLarge = await readBoundedAssetResponse(
    new Response(
      byteStream(1_200_000, 900_001),
      { headers: { "content-type": "image/png" } },
    ),
  );
  assertEquals(tooLarge, { status: "too_large" });
  assertEquals(
    await readBoundedAssetResponse(new Response(null, { status: 404 })),
    { status: "missing" },
  );
});

Deno.test("cleanup delete failure schedules retry without finishing or releasing quota", async () => {
  const gateway = new FakeAssetGateway();
  gateway.claimResult = {
    data: { http_status: 200, jobs: [cleanupJob(ASSET_ID, LEASE_ID)] },
    error: null,
  };
  gateway.removeErrorFor.add(assetPath(ASSET_ID));

  const summary = await runAssetCleanupBatch(gateway, 1);

  assertEquals(summary, {
    claimed: 1,
    deleted: 0,
    completed: 0,
    retry_scheduled: 1,
    failed: 0,
    error_codes: ["STORAGE_DELETE_FAILED"],
  });
  assertEquals(gateway.finishCalls, []);
  assertEquals(gateway.failCalls, [{
    assetId: ASSET_ID,
    leaseToken: LEASE_ID,
    errorCode: "ASSET_STORAGE_DELETE_FAILED",
  }]);
  assertEquals(JSON.stringify(summary).includes("raw provider secret"), false);
});

Deno.test("cleanup continues a partial batch and reports only fixed safe codes", async () => {
  const gateway = new FakeAssetGateway();
  gateway.claimResult = {
    data: {
      http_status: 200,
      jobs: [
        cleanupJob(ASSET_ID, LEASE_ID),
        cleanupJob(SECOND_ASSET_ID, SECOND_LEASE_ID),
      ],
    },
    error: null,
  };
  gateway.removeErrorFor.add(assetPath(ASSET_ID));

  const summary = await runAssetCleanupBatch(gateway, 2);

  assertEquals(summary.claimed, 2);
  assertEquals(summary.deleted, 1);
  assertEquals(summary.completed, 1);
  assertEquals(summary.retry_scheduled, 1);
  assertEquals(summary.failed, 0);
  assertEquals(summary.error_codes, ["STORAGE_DELETE_FAILED"]);
  assertEquals(gateway.finishCalls, [{
    assetId: SECOND_ASSET_ID,
    leaseToken: SECOND_LEASE_ID,
  }]);
});

Deno.test("internal asset cleanup endpoint uses exact path, service auth, and limits", async () => {
  for (
    const path of [
      "/v1/internal/assets-cleanup",
      "/library-api/v1/internal/assets-cleanup",
      "/functions/v1/library-api/v1/internal/assets-cleanup",
    ]
  ) assertEquals(isInternalAssetCleanupPath(path), true);
  assertEquals(
    isInternalAssetCleanupPath("/v1/internal/assets-cleanup/extra"),
    false,
  );

  const gateway = new FakeAssetGateway();
  const handler = createAssetCleanupHandler(gateway, "service-secret");
  const unauthorized = await handler(
    new Request(
      "https://example.test/v1/internal/assets-cleanup",
      {
        method: "POST",
        headers: {
          authorization: "Bearer member-token",
          "content-type": "application/json",
        },
        body: '{"limit":1}',
      },
    ),
  );
  assertEquals(unauthorized.status, 401);

  const accepted = await handler(
    new Request(
      "https://example.test/v1/internal/assets-cleanup",
      {
        method: "POST",
        headers: {
          authorization: "Bearer service-secret",
          "content-type": "application/json",
        },
        body: '{"limit":10}',
      },
    ),
  );
  assertEquals(accepted.status, 200);
  assertEquals(gateway.claimedLimits, [10]);
});

function completeInput(
  body: {
    expected_version: number;
    ocr_state: "ready" | "failed" | "not_requested";
    ocr_text?: string | null;
    ocr_truncated?: boolean;
  } = { expected_version: 4, ocr_state: "not_requested" },
) {
  return {
    ...identity(),
    requestId: REQUEST_ID,
    body,
    loadSnapshot: () => Promise.resolve({ data: snapshot(), error: null }),
  };
}

function identity() {
  return { ownerId: OWNER_ID, itemId: ITEM_ID, assetId: ASSET_ID };
}

function assetPath(assetId: string): string {
  return `${OWNER_ID}/${ITEM_ID}/${assetId}`;
}

function assetRecord(
  assetId: string,
  state: "reserved" | "active" | "deleting",
  mimeType: string,
  actualBytes: number | null,
) {
  return {
    id: assetId,
    owner_id: OWNER_ID,
    item_id: ITEM_ID,
    state,
    object_path: assetPath(assetId),
    reserved_mime_type: mimeType,
    mime_type: state === "active" ? mimeType : null,
    actual_bytes: actualBytes,
  };
}

function snapshot(): ItemUpdateSnapshot & { id: string } {
  return {
    id: ITEM_ID,
    version: 4,
    url: "https://blog.naver.com/PostView.naver",
    user_title: "기존 제목",
    fetched_title: "기존 메타데이터",
    shared_text: null,
    description: null,
    body_text: null,
    note: null,
    category_refs: [],
    metadata_state: "ready",
    ocr_state: "not_requested",
    extraction_meta: {},
    active_asset: null,
  };
}

function cleanupJob(assetId: string, leaseToken: string) {
  return {
    asset_id: assetId,
    lease_token: leaseToken,
    owner_id: OWNER_ID,
    item_id: ITEM_ID,
    bucket_id: "library-images",
    object_path: assetPath(assetId),
  };
}

function finishResult(assetId: string): AssetRpcResult {
  return {
    data: {
      http_status: 200,
      state: "complete",
      asset_id: assetId,
      released_reserved_bytes: 0,
      released_used_bytes: PNG.byteLength,
    },
    error: null,
  };
}

function retryResult(assetId: string): AssetRpcResult {
  return {
    data: {
      http_status: 202,
      state: "retry",
      asset_id: assetId,
      next_run_at: "2026-09-15T12:01:00Z",
    },
    error: null,
  };
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

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}
