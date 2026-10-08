import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import { deflateSync } from "node:zlib";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const fixture = localBackendFixture("enrichment");
const {
  request,
  user,
  approve,
  bootstrap,
  cleanup,
  settleClassification,
  serviceKey,
  anonKey,
} = fixture;
const API_PREFIX = "/functions/v1/library-api/v1";
const STORAGE_BUCKET = "library-images";
const MAX_IMAGE_BYTES = 2_000_000;
const OWNER_STORAGE_BYTES = 20_000_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PUBLIC_NAVER_FIXTURE =
  "https://blog.naver.com/PostView.naver?blogId=naverofficial&logNo=221163636811";
const METADATA_WORKER_KEYS = [
  "claimed",
  "discarded",
  "error_codes",
  "failed",
  "retry_scheduled",
  "succeeded",
  "version_conflicts",
];

const createdOwnerIds = new Set();
const ownedStoragePaths = new Set();
const saveCounts = new Map();
let checks = 0;

function check(condition, caseId) {
  assert.ok(condition, caseId);
  checks++;
  console.log(`PASS ${caseId}`);
}

function apiErrorCode(result) {
  return result.body?.error?.code ?? result.body?.error_code ?? null;
}

function isApiError(result, status, code) {
  return result.status === status && apiErrorCode(result) === code;
}

function isSuccessStatus(status) {
  return status >= 200 && status < 300;
}

function sameBytes(left, right) {
  if (!(left instanceof Uint8Array) || !(right instanceof Uint8Array)) {
    return false;
  }
  if (left.byteLength !== right.byteLength) return false;
  for (let index = 0; index < left.byteLength; index++) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ ((crc & 1) === 1 ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const typeBytes = Buffer.from(type, "ascii");
  const payload = Buffer.concat([typeBytes, Buffer.from(data)]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.byteLength);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(payload));
  return Buffer.concat([length, payload, checksum]);
}

function smallPng(red, green, blue) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(2, 4);
  header[8] = 8;
  header[9] = 6;
  const pixel = [red, green, blue, 255];
  const scanlines = Buffer.from([
    0,
    ...pixel,
    ...pixel,
    0,
    ...pixel,
    ...pixel,
  ]);
  return new Uint8Array(Buffer.concat([
    signature,
    pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(scanlines)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]));
}

function encodedObjectPath(objectPath) {
  return objectPath.split("/").map(encodeURIComponent).join("/");
}

function trackStoragePath(owner, objectPath) {
  assert.ok(createdOwnerIds.has(owner.id), "M4-STORAGE-OWNER-SCOPE");
  assert.ok(
    objectPath.startsWith(`${owner.id}/`),
    "M4-STORAGE-PATH-SCOPE",
  );
  ownedStoragePaths.add(objectPath);
}

async function createAccount() {
  const account = await user();
  createdOwnerIds.add(account.id);
  await approve(account);
  const bootstrapped = await bootstrap(account);
  assert.equal(bootstrapped.status, 200, "M4-ACCOUNT-BOOTSTRAP");
  return account;
}

async function api(
  account,
  path,
  { method = "GET", body, requestId = randomUUID(), responseType = "json" } =
    {},
) {
  return request(`${API_PREFIX}${path}`, {
    token: account.token,
    method,
    body,
    responseType,
    headers: method === "GET" ? {} : { "X-Request-Id": requestId },
  });
}

async function anonymousApi(path, responseType = "json") {
  return request(`${API_PREFIX}${path}`, {
    token: anonKey,
    responseType,
  });
}

async function resetCreateRate(account) {
  const result = await request(
    `/rest/v1/api_rate_buckets?owner_id=eq.${account.id}&operation=eq.create_item`,
    { token: serviceKey, method: "DELETE" },
  );
  assert.equal(result.status, 204, "M4-CREATE-RATE-FIXTURE");
}

async function resetMetadataFetchBudget(account, itemId) {
  const operation = encodeURIComponent(`metadata_fetch:${itemId}`);
  const result = await request(
    `/rest/v1/api_rate_buckets?owner_id=eq.${account.id}&operation=eq.${operation}`,
    { token: serviceKey, method: "DELETE" },
  );
  assert.equal(result.status, 204, "M4-METADATA-BUDGET-FIXTURE");
}

async function saveItem(account, body) {
  const count = saveCounts.get(account.id) ?? 0;
  if (count > 0 && count % 8 === 0) await resetCreateRate(account);
  const result = await api(account, "/items", { method: "POST", body });
  assert.equal(result.status, 201, "M4-CREATE-ITEM");
  saveCounts.set(account.id, count + 1);
  return result.body.item;
}

async function currentItem(account, itemId) {
  const result = await api(account, `/items/${itemId}`);
  assert.equal(result.status, 200, "M4-ITEM-DETAIL");
  return result.body;
}

async function stableItem(account, itemId) {
  const result = await settleClassification(account, itemId);
  assert.equal(result.status, 200, "M4-CLASSIFICATION-SETTLE");
  return result.body;
}

async function searchItems(account, query) {
  const parameters = new URLSearchParams({ q: query, limit: "50" });
  const result = await api(account, `/items?${parameters}`);
  assert.equal(result.status, 200, "M4-SEARCH");
  return result.body.items;
}

async function selectOwned(table, account, columns, filters = {}) {
  assert.ok(createdOwnerIds.has(account.id), "M4-SELECT-OWNER-SCOPE");
  const parameters = new URLSearchParams({
    owner_id: `eq.${account.id}`,
    select: columns,
    ...filters,
  });
  const result = await request(`/rest/v1/${table}?${parameters}`, {
    token: serviceKey,
  });
  assert.equal(result.status, 200, "M4-SERVICE-SELECT");
  return result.body;
}

async function patchOwned(table, account, filters, body) {
  assert.ok(createdOwnerIds.has(account.id), "M4-PATCH-OWNER-SCOPE");
  const parameters = new URLSearchParams({
    owner_id: `eq.${account.id}`,
    ...filters,
  });
  const result = await request(`/rest/v1/${table}?${parameters}`, {
    token: serviceKey,
    method: "PATCH",
    body,
  });
  assert.equal(result.status, 204, "M4-SERVICE-PATCH");
}

async function usage(account) {
  const rows = await selectOwned(
    "library_usage",
    account,
    "used_image_bytes,reserved_image_bytes",
  );
  assert.equal(rows.length, 1, "M4-USAGE-ROW");
  return rows[0];
}

async function reserveAsset(
  account,
  itemId,
  expectedVersion,
  { mimeType = "image/png", requestId = randomUUID() } = {},
) {
  const result = await api(account, `/items/${itemId}/assets/reserve`, {
    method: "POST",
    requestId,
    body: { expected_version: expectedVersion, mime_type: mimeType },
  });
  if (result.status === 201) {
    trackStoragePath(account, result.body.object_path);
  }
  return result;
}

async function uploadObject(account, objectPath, bytes, mimeType) {
  return request(
    `/storage/v1/object/${STORAGE_BUCKET}/${encodedObjectPath(objectPath)}`,
    {
      token: account.token,
      method: "POST",
      body: bytes,
      headers: { "Content-Type": mimeType, "x-upsert": "false" },
    },
  );
}

async function readObject(token, objectPath) {
  return request(
    `/storage/v1/object/${STORAGE_BUCKET}/${encodedObjectPath(objectPath)}`,
    { token, responseType: "bytes" },
  );
}

async function readPublicObject(objectPath) {
  return request(
    `/storage/v1/object/public/${STORAGE_BUCKET}/${
      encodedObjectPath(objectPath)
    }`,
    { token: anonKey, responseType: "bytes" },
  );
}

async function proxyContent(account, itemId, assetId) {
  return api(account, `/items/${itemId}/assets/${assetId}/content`, {
    responseType: "bytes",
  });
}

async function runAssetCleanup(account, assetIds, caseId) {
  const uniqueIds = [...new Set(assetIds)];
  assert.ok(uniqueIds.length > 0, "M4-CLEANUP-TARGETS");
  for (let attempt = 0; attempt < 16; attempt++) {
    const worker = await request(`${API_PREFIX}/internal/assets-cleanup`, {
      token: serviceKey,
      method: "POST",
      body: { limit: 10 },
    });
    assert.equal(worker.status, 200, "M4-ASSET-CLEANUP-WORKER");
    const rows = await selectOwned(
      "assets",
      account,
      "id,state,cleanup_error_code",
      { id: `in.(${uniqueIds.join(",")})` },
    );
    if (rows.length === 0) return;
    if (attempt % 4 === 0) console.log(`LIVE ${caseId}`);
    await pause(250);
  }
  throw new Error(`${caseId}-BOUNDED-CLEANUP`);
}

async function expireAssets(account, assetIds) {
  await patchOwned(
    "assets",
    account,
    { id: `in.(${assetIds.join(",")})`, state: "eq.reserved" },
    { reservation_expires_at: "2000-01-01T00:00:00Z" },
  );
}

function validMetadataWorkerBody(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    return false;
  }
  const keys = Object.keys(body).sort();
  if (keys.join("|") !== [...METADATA_WORKER_KEYS].sort().join("|")) {
    return false;
  }
  return METADATA_WORKER_KEYS.filter((key) => key !== "error_codes").every(
    (key) => Number.isInteger(body[key]) && body[key] >= 0,
  ) && Array.isArray(body.error_codes) && body.error_codes.every(
    (code) => typeof code === "string",
  );
}

async function metadataWorker() {
  const result = await request(`${API_PREFIX}/internal/metadata`, {
    token: serviceKey,
    method: "POST",
    body: { limit: 10 },
  });
  assert.equal(result.status, 200, "M4-METADATA-WORKER");
  assert.ok(validMetadataWorkerBody(result.body), "M4-METADATA-WORKER-BODY");
  return result.body;
}

async function metadataJob(account, jobId) {
  const rows = await selectOwned(
    "processing_jobs",
    account,
    "id,state,attempts,last_error_code,next_run_at",
    { id: `eq.${jobId}`, kind: "eq.metadata" },
  );
  assert.equal(rows.length, 1, "M4-METADATA-JOB-ROW");
  return rows[0];
}

async function settleMetadataJob(account, jobId, caseId) {
  let explicitlyClaimed = 0;
  for (let attempt = 0; attempt < 30; attempt++) {
    let job = await metadataJob(account, jobId);
    if (["succeeded", "failed", "cancelled"].includes(job.state)) {
      return { job, explicitlyClaimed };
    }
    if (job.state === "retry") {
      await patchOwned(
        "processing_jobs",
        account,
        { id: `eq.${jobId}`, kind: "eq.metadata" },
        { next_run_at: "2000-01-01T00:00:00Z" },
      );
    }
    const receipt = await metadataWorker();
    explicitlyClaimed += receipt.claimed;
    job = await metadataJob(account, jobId);
    if (["succeeded", "failed", "cancelled"].includes(job.state)) {
      return { job, explicitlyClaimed };
    }
    if (attempt % 3 === 0) console.log(`LIVE ${caseId}`);
    await pause(500);
  }
  throw new Error(`${caseId}-BOUNDED-METADATA`);
}

async function settleActiveMetadata(account, itemId, caseId) {
  let explicitlyClaimed = 0;
  for (let cycle = 0; cycle < 6; cycle++) {
    const active = await selectOwned(
      "processing_jobs",
      account,
      "id,state",
      {
        item_id: `eq.${itemId}`,
        kind: "eq.metadata",
        state: "in.(queued,running,retry)",
      },
    );
    if (active.length === 0) return explicitlyClaimed;
    const settled = await settleMetadataJob(account, active[0].id, caseId);
    explicitlyClaimed += settled.explicitlyClaimed;
  }
  throw new Error(`${caseId}-ACTIVE-JOBS`);
}

async function insertMetadataJob(account, item) {
  const result = await request("/rest/v1/processing_jobs", {
    token: serviceKey,
    method: "POST",
    headers: { Prefer: "return=representation" },
    body: {
      owner_id: account.id,
      item_id: item.id,
      kind: "metadata",
      target_revision: item.text_revision,
      state: "queued",
    },
  });
  assert.equal(result.status, 201, "M4-METADATA-JOB-INSERT");
  assert.ok(UUID_PATTERN.test(result.body[0]?.id ?? ""), "M4-METADATA-JOB-ID");
  return result.body[0].id;
}

async function removeOwnedStorage() {
  const prefixes = [...ownedStoragePaths];
  if (prefixes.length === 0) return;
  for (const objectPath of prefixes) {
    assert.ok(
      [...createdOwnerIds].some((ownerId) =>
        objectPath.startsWith(`${ownerId}/`)
      ),
      "M4-FINAL-STORAGE-SCOPE",
    );
  }
  const result = await request(`/storage/v1/object/${STORAGE_BUCKET}`, {
    token: serviceKey,
    method: "DELETE",
    body: { prefixes },
  });
  assert.equal(result.status, 200, "M4-FINAL-STORAGE-DELETE");
  for (const ownerId of createdOwnerIds) {
    const removed = await request(`/rest/v1/assets?owner_id=eq.${ownerId}`, {
      token: serviceKey,
      method: "DELETE",
    });
    assert.equal(removed.status, 204, "M4-FINAL-ASSET-ROWS");
  }
}

const pngOne = smallPng(32, 96, 192);
const pngTwo = smallPng(208, 80, 48);
const replacementBytes = new Uint8Array(Buffer.from(
  "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA",
  "base64",
));
const initialOcr = "카카오톡 프로필 사진 변경";
const updatedOcr = "엑셀 수식 활용";
const conflictOcr = "첨부 교체 충돌 표식";
const replacementOcr = "최종 첨부 검색 표식";
let primaryFailure;

try {
  const owner = await createAccount();
  const other = await createAccount();
  const quotaOwner = await createAccount();
  const workerOwner = await createAccount();

  const cleanupDenied = await api(owner, "/internal/assets-cleanup", {
    method: "POST",
    body: { limit: 10 },
  });
  check(
    cleanupDenied.status === 401 &&
      apiErrorCode(cleanupDenied) === "UNAUTHENTICATED",
    "M4-ASSET-CLEANUP-MEMBER-DENIED",
  );
  const metadataDenied = await api(owner, "/internal/metadata", {
    method: "POST",
    body: { limit: 10 },
  });
  check(
    metadataDenied.status === 401 &&
      apiErrorCode(metadataDenied) === "UNAUTHENTICATED",
    "M4-METADATA-MEMBER-DENIED",
  );

  const categoryResult = await api(owner, "/categories", {
    method: "POST",
    body: { name: "첨부 분류" },
  });
  assert.equal(categoryResult.status, 201, "M4-CUSTOM-CATEGORY");
  const customCategoryId = categoryResult.body.id;
  assert.ok(UUID_PATTERN.test(customCategoryId), "M4-CUSTOM-CATEGORY-ID");

  const created = await saveItem(owner, {
    url: `https://example.test/enrichment-${randomUUID()}`,
    title: "첨부 통합 검증",
    category_ids: [customCategoryId],
  });
  let item = await stableItem(owner, created.id);
  const initialVersion = item.version;
  const reserveRequestId = randomUUID();
  const reserveBody = {
    expected_version: initialVersion,
    mime_type: "image/png",
  };
  const reserved = await api(owner, `/items/${item.id}/assets/reserve`, {
    method: "POST",
    body: reserveBody,
    requestId: reserveRequestId,
  });
  assert.equal(reserved.status, 201, "M4-RESERVE-CREATE");
  trackStoragePath(owner, reserved.body.object_path);
  check(
    UUID_PATTERN.test(reserved.body.asset_id) &&
      reserved.body.object_path ===
        `${owner.id}/${item.id}/${reserved.body.asset_id}` &&
      reserved.body.max_bytes === MAX_IMAGE_BYTES &&
      Number.isFinite(Date.parse(reserved.body.expires_at)) &&
      Date.parse(reserved.body.expires_at) > Date.now(),
    "M4-RESERVE-CONTRACT",
  );
  const reserveReplay = await api(owner, `/items/${item.id}/assets/reserve`, {
    method: "POST",
    body: reserveBody,
    requestId: reserveRequestId,
  });
  check(
    reserveReplay.status === 201 &&
      reserveReplay.body.asset_id === reserved.body.asset_id &&
      reserveReplay.body.object_path === reserved.body.object_path &&
      reserveReplay.body.expires_at === reserved.body.expires_at,
    "M4-RESERVE-REPLAY",
  );
  const reserveChanged = await api(owner, `/items/${item.id}/assets/reserve`, {
    method: "POST",
    body: { ...reserveBody, mime_type: "image/jpeg" },
    requestId: reserveRequestId,
  });
  check(
    isApiError(reserveChanged, 409, "IDEMPOTENCY_MISMATCH"),
    "M4-RESERVE-IDENTITY-FROZEN",
  );
  item = await currentItem(owner, item.id);
  check(
    item.version === initialVersion && item.active_asset === null,
    "M4-RESERVE-PRESERVES-VERSION",
  );
  const usageAfterReserve = await usage(owner);
  check(
    usageAfterReserve.used_image_bytes === 0 &&
      usageAfterReserve.reserved_image_bytes === MAX_IMAGE_BYTES,
    "M4-RESERVE-EXACT-TWO-MEGABYTES",
  );

  const foreignUpload = await uploadObject(
    other,
    reserved.body.object_path,
    pngOne,
    "image/png",
  );
  check(
    !isSuccessStatus(foreignUpload.status),
    "M4-STORAGE-FOREIGN-WRITE-DENIED",
  );
  const unregisteredPath = `${reserved.body.object_path}-unregistered`;
  trackStoragePath(owner, unregisteredPath);
  const wrongPathUpload = await uploadObject(
    owner,
    unregisteredPath,
    pngOne,
    "image/png",
  );
  check(!isSuccessStatus(wrongPathUpload.status), "M4-STORAGE-EXACT-PATH-ONLY");
  const upload = await uploadObject(
    owner,
    reserved.body.object_path,
    pngOne,
    "image/png",
  );
  assert.equal(upload.status, 200, "M4-STORAGE-UPLOAD");
  const overwrite = await uploadObject(
    owner,
    reserved.body.object_path,
    pngTwo,
    "image/png",
  );
  check(!isSuccessStatus(overwrite.status), "M4-STORAGE-NO-UPSERT");
  const serviceUploaded = await readObject(
    serviceKey,
    reserved.body.object_path,
  );
  check(
    serviceUploaded.status === 200 &&
      serviceUploaded.contentType?.startsWith("image/png") &&
      sameBytes(serviceUploaded.body, pngOne),
    "M4-STORAGE-UPLOAD-BYTES",
  );
  const ownerDirectRead = await readObject(
    owner.token,
    reserved.body.object_path,
  );
  check(
    !isSuccessStatus(ownerDirectRead.status),
    "M4-STORAGE-DIRECT-OWNER-READ-DENIED",
  );
  const foreignDirectRead = await readObject(
    other.token,
    reserved.body.object_path,
  );
  check(
    !isSuccessStatus(foreignDirectRead.status),
    "M4-STORAGE-FOREIGN-READ-DENIED",
  );
  const publicDirectRead = await readPublicObject(reserved.body.object_path);
  check(
    !isSuccessStatus(publicDirectRead.status),
    "M4-STORAGE-PUBLIC-READ-DENIED",
  );
  const ownerList = await request(`/storage/v1/object/list/${STORAGE_BUCKET}`, {
    token: owner.token,
    method: "POST",
    body: { prefix: owner.id, limit: 100, offset: 0 },
  });
  check(
    !isSuccessStatus(ownerList.status) ||
      (Array.isArray(ownerList.body) && ownerList.body.length === 0),
    "M4-STORAGE-LIST-DENIED",
  );

  const reservedOwnerProxy = await proxyContent(
    owner,
    item.id,
    reserved.body.asset_id,
  );
  check(reservedOwnerProxy.status === 404, "M4-RESERVED-PROXY-OWNER-DENIED");
  const reservedForeignProxy = await proxyContent(
    other,
    item.id,
    reserved.body.asset_id,
  );
  check(
    reservedForeignProxy.status === 404,
    "M4-RESERVED-PROXY-FOREIGN-DENIED",
  );
  const reservedPublicProxy = await anonymousApi(
    `/items/${item.id}/assets/${reserved.body.asset_id}/content`,
    "bytes",
  );
  check(reservedPublicProxy.status === 401, "M4-RESERVED-PROXY-PUBLIC-DENIED");

  const completeRequestId = randomUUID();
  const completeBody = {
    expected_version: initialVersion,
    ocr_state: "ready",
    ocr_text: initialOcr,
  };
  const completed = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/complete`,
    { method: "POST", body: completeBody, requestId: completeRequestId },
  );
  check(
    completed.status === 200 &&
      completed.body.id === item.id &&
      completed.body.active_asset?.id === reserved.body.asset_id &&
      completed.body.active_asset?.mime_type === "image/png" &&
      completed.body.active_asset?.byte_size === pngOne.byteLength &&
      completed.body.active_asset?.width === 2 &&
      completed.body.active_asset?.height === 2 &&
      completed.body.active_asset?.ocr_text === initialOcr &&
      completed.body.ocr_state === "ready",
    "M4-COMPLETE-UNWRAPPED-VALID-PNG",
  );
  const completeReplay = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/complete`,
    { method: "POST", body: completeBody, requestId: completeRequestId },
  );
  check(
    completeReplay.status === 200 &&
      completeReplay.body.active_asset?.id === reserved.body.asset_id,
    "M4-COMPLETE-REPLAY",
  );
  const completeChanged = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/complete`,
    {
      method: "POST",
      body: { ...completeBody, ocr_truncated: true },
      requestId: completeRequestId,
    },
  );
  check(
    isApiError(completeChanged, 409, "IDEMPOTENCY_MISMATCH"),
    "M4-COMPLETE-IDENTITY-FROZEN",
  );
  const usageAfterComplete = await usage(owner);
  check(
    usageAfterComplete.used_image_bytes === pngOne.byteLength &&
      usageAfterComplete.reserved_image_bytes === 0,
    "M4-COMPLETE-MOVES-RESERVED-TO-USED",
  );
  item = completeReplay.body;
  check(
    item.manual_override === true &&
      item.classification_state === "manual" &&
      item.category_refs.some((reference) => reference.id === customCategoryId),
    "M4-MANUAL-CLASSIFICATION-PRESERVED",
  );
  let ownerProxy = await proxyContent(owner, item.id, reserved.body.asset_id);
  check(
    ownerProxy.status === 200 &&
      ownerProxy.contentType?.startsWith("image/png") &&
      sameBytes(ownerProxy.body, pngOne),
    "M4-ACTIVE-PROXY-OWNER-BYTES",
  );
  check(
    (await proxyContent(other, item.id, reserved.body.asset_id)).status === 404,
    "M4-ACTIVE-PROXY-FOREIGN-HIDDEN",
  );
  check(
    (await anonymousApi(
      `/items/${item.id}/assets/${reserved.body.asset_id}/content`,
      "bytes",
    )).status === 401,
    "M4-ACTIVE-PROXY-PUBLIC-DENIED",
  );
  check(
    (await searchItems(owner, "카톡 프사")).some((candidate) =>
      candidate.id === item.id
    ),
    "M4-OCR-ALIAS-SEARCH-ONE",
  );

  const ocrRequestId = randomUUID();
  const ocrBody = {
    expected_version: item.version,
    ocr_state: "ready",
    ocr_text: updatedOcr,
  };
  const ocrUpdated = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    { method: "PATCH", body: ocrBody, requestId: ocrRequestId },
  );
  assert.equal(ocrUpdated.status, 200, "M4-OCR-UPDATE");
  const ocrReplay = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    { method: "PATCH", body: ocrBody, requestId: ocrRequestId },
  );
  check(
    ocrReplay.status === 200 &&
      ocrReplay.body.active_asset?.ocr_text === updatedOcr,
    "M4-OCR-REPLAY",
  );
  const ocrChanged = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: { ...ocrBody, ocr_text: conflictOcr },
      requestId: ocrRequestId,
    },
  );
  check(
    isApiError(ocrChanged, 409, "IDEMPOTENCY_MISMATCH"),
    "M4-OCR-IDENTITY-FROZEN",
  );
  check(
    !(await searchItems(owner, "카톡 프사")).some((candidate) =>
      candidate.id === item.id
    ) &&
      (await searchItems(owner, "엑셀")).some((candidate) =>
        candidate.id === item.id
      ),
    "M4-OCR-SEARCH-REVISION",
  );
  item = ocrReplay.body;

  const failedOcr = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: { expected_version: item.version, ocr_state: "failed" },
    },
  );
  check(
    failedOcr.status === 200 &&
      failedOcr.body.has_attachment === true &&
      failedOcr.body.ocr_state === "failed" &&
      failedOcr.body.active_asset?.id === reserved.body.asset_id &&
      failedOcr.body.active_asset?.ocr_text === null,
    "M4-OCR-FAILED-IMAGE-PERSISTS",
  );
  const retriedOcr = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: {
        expected_version: failedOcr.body.version,
        ocr_state: "ready",
        ocr_text: updatedOcr,
      },
    },
  );
  check(
    retriedOcr.status === 200 && retriedOcr.body.ocr_state === "ready",
    "M4-OCR-FAILED-RETRY",
  );
  const longOcr = "가".repeat(20_001);
  const truncatedOcr = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: {
        expected_version: retriedOcr.body.version,
        ocr_state: "ready",
        ocr_text: longOcr,
        ocr_truncated: false,
      },
    },
  );
  check(
    truncatedOcr.status === 200 &&
      truncatedOcr.body.active_asset?.ocr_text?.length === 20_000 &&
      truncatedOcr.body.active_asset?.ocr_truncated === true,
    "M4-OCR-TRUNCATION-BOUND",
  );
  item = truncatedOcr.body;

  const wrongMimeReservation = await reserveAsset(owner, item.id, item.version);
  assert.equal(wrongMimeReservation.status, 201, "M4-WRONG-MIME-RESERVE");
  const wrongMimeUpload = await uploadObject(
    owner,
    wrongMimeReservation.body.object_path,
    pngTwo,
    "image/jpeg",
  );
  assert.equal(wrongMimeUpload.status, 200, "M4-WRONG-MIME-UPLOAD");
  const wrongMimeComplete = await api(
    owner,
    `/items/${item.id}/assets/${wrongMimeReservation.body.asset_id}/complete`,
    {
      method: "POST",
      body: { expected_version: item.version, ocr_state: "not_requested" },
    },
  );
  check(
    isApiError(wrongMimeComplete, 409, "ASSET_MIME_MISMATCH") &&
      (await currentItem(owner, item.id)).active_asset?.id ===
        reserved.body.asset_id,
    "M4-WRONG-MIME-NO-REPLACEMENT",
  );
  await runAssetCleanup(
    owner,
    [wrongMimeReservation.body.asset_id],
    "M4-WRONG-MIME-CLEANUP",
  );
  const usageAfterWrongMimeCleanup = await usage(owner);
  check(
    !isSuccessStatus(
      (await readObject(serviceKey, wrongMimeReservation.body.object_path))
        .status,
    ) &&
      usageAfterWrongMimeCleanup.used_image_bytes === pngOne.byteLength &&
      usageAfterWrongMimeCleanup.reserved_image_bytes === 0,
    "M4-WRONG-MIME-PHYSICAL-CLEANUP",
  );

  item = await currentItem(owner, item.id);
  const invalidReservation = await reserveAsset(owner, item.id, item.version);
  assert.equal(invalidReservation.status, 201, "M4-INVALID-RESERVE");
  const invalidBytes = new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7]);
  const invalidUpload = await uploadObject(
    owner,
    invalidReservation.body.object_path,
    invalidBytes,
    "image/png",
  );
  assert.equal(invalidUpload.status, 200, "M4-INVALID-UPLOAD");
  const invalidComplete = await api(
    owner,
    `/items/${item.id}/assets/${invalidReservation.body.asset_id}/complete`,
    {
      method: "POST",
      body: { expected_version: item.version, ocr_state: "failed" },
    },
  );
  check(
    isApiError(invalidComplete, 409, "ASSET_DECODE_FAILED") &&
      (await currentItem(owner, item.id)).active_asset?.id ===
        reserved.body.asset_id,
    "M4-INVALID-DECODE-NO-REPLACEMENT",
  );
  await runAssetCleanup(
    owner,
    [invalidReservation.body.asset_id],
    "M4-INVALID-CLEANUP",
  );
  const usageAfterInvalidCleanup = await usage(owner);
  check(
    !isSuccessStatus(
      (await readObject(serviceKey, invalidReservation.body.object_path))
        .status,
    ) &&
      usageAfterInvalidCleanup.used_image_bytes === pngOne.byteLength &&
      usageAfterInvalidCleanup.reserved_image_bytes === 0,
    "M4-INVALID-PHYSICAL-CLEANUP",
  );

  item = await currentItem(owner, item.id);
  const conflictedReservation = await reserveAsset(
    owner,
    item.id,
    item.version,
  );
  assert.equal(conflictedReservation.status, 201, "M4-CONFLICT-RESERVE");
  assert.equal(
    (await uploadObject(
      owner,
      conflictedReservation.body.object_path,
      pngTwo,
      "image/png",
    )).status,
    200,
    "M4-CONFLICT-UPLOAD",
  );
  const pendingRows = await selectOwned(
    "assets",
    owner,
    "id,state",
    { item_id: `eq.${item.id}`, state: "in.(active,reserved)" },
  );
  check(
    pendingRows.filter((row) => row.state === "active").length === 1 &&
      pendingRows.filter((row) => row.state === "reserved").length === 1,
    "M4-ONE-ACTIVE-ONE-PENDING",
  );
  const secondPending = await reserveAsset(owner, item.id, item.version);
  check(
    isApiError(secondPending, 409, "ASSET_RESERVATION_EXISTS"),
    "M4-ONE-PENDING-ENFORCED",
  );
  const conflictBump = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: {
        expected_version: item.version,
        ocr_state: "ready",
        ocr_text: conflictOcr,
      },
    },
  );
  assert.equal(conflictBump.status, 200, "M4-CONFLICT-VERSION-BUMP");
  const conflictCompleteId = randomUUID();
  const conflictCompleteBody = {
    expected_version: item.version,
    ocr_state: "not_requested",
  };
  const versionConflict = await api(
    owner,
    `/items/${item.id}/assets/${conflictedReservation.body.asset_id}/complete`,
    {
      method: "POST",
      body: conflictCompleteBody,
      requestId: conflictCompleteId,
    },
  );
  const versionConflictReplay = await api(
    owner,
    `/items/${item.id}/assets/${conflictedReservation.body.asset_id}/complete`,
    {
      method: "POST",
      body: conflictCompleteBody,
      requestId: conflictCompleteId,
    },
  );
  const versionConflictChanged = await api(
    owner,
    `/items/${item.id}/assets/${conflictedReservation.body.asset_id}/complete`,
    {
      method: "POST",
      body: { ...conflictCompleteBody, ocr_state: "failed" },
      requestId: conflictCompleteId,
    },
  );
  check(
    isApiError(versionConflict, 409, "VERSION_CONFLICT") &&
      isApiError(versionConflictReplay, 409, "VERSION_CONFLICT") &&
      isApiError(versionConflictChanged, 409, "IDEMPOTENCY_MISMATCH"),
    "M4-COMPLETE-CONFLICT-RECEIPT-FROZEN",
  );
  const conflictUsageObserved = await usage(owner);
  const conflictedRows = await selectOwned(
    "assets",
    owner,
    "id,state,cleanup_reason",
    { id: `eq.${conflictedReservation.body.asset_id}` },
  );
  const conflictedObject = await readObject(
    serviceKey,
    conflictedReservation.body.object_path,
  );
  const conflictUsageSettled = conflictedRows.length === 0 &&
      conflictUsageObserved.reserved_image_bytes !== 0
    ? await usage(owner)
    : conflictUsageObserved;
  check(
    conflictUsageObserved.used_image_bytes === pngOne.byteLength &&
      [0, MAX_IMAGE_BYTES].includes(
        conflictUsageObserved.reserved_image_bytes,
      ) &&
      conflictedRows.length <= 1 &&
      (
        conflictedRows.length === 1
          ? conflictedRows[0].state === "deleting" &&
            conflictedRows[0].cleanup_reason === "version_conflict" &&
            conflictUsageObserved.reserved_image_bytes === MAX_IMAGE_BYTES
          : !isSuccessStatus(conflictedObject.status) &&
            conflictUsageSettled.reserved_image_bytes === 0
      ),
    "M4-COMPLETE-CONFLICT-STORED",
  );
  ownerProxy = await proxyContent(owner, item.id, reserved.body.asset_id);
  check(
    ownerProxy.status === 200 && sameBytes(ownerProxy.body, pngOne),
    "M4-OLD-ACTIVE-RETAINED-AFTER-CONFLICT",
  );
  item = await currentItem(owner, item.id);
  const replacement = await reserveAsset(owner, item.id, item.version, {
    mimeType: "image/webp",
  });
  assert.equal(replacement.status, 201, "M4-NEW-RESERVE-AFTER-CONFLICT");
  assert.equal(
    (await uploadObject(
      owner,
      replacement.body.object_path,
      replacementBytes,
      "image/webp",
    )).status,
    200,
    "M4-REPLACEMENT-UPLOAD",
  );
  check(
    (await readObject(serviceKey, reserved.body.object_path)).status === 200,
    "M4-OLD-FILE-RETAINED-BEFORE-COMPLETE",
  );
  const replacementComplete = await api(
    owner,
    `/items/${item.id}/assets/${replacement.body.asset_id}/complete`,
    {
      method: "POST",
      body: {
        expected_version: item.version,
        ocr_state: "failed",
      },
    },
  );
  if (replacementComplete.status !== 200) {
    console.error(
      `M4-WEBP-COMPLETE status=${replacementComplete.status} code=${
        replacementComplete.body?.error?.code ?? "UNKNOWN"
      }`,
    );
  }
  check(
    replacementComplete.status === 200 &&
      replacementComplete.body.active_asset?.id === replacement.body.asset_id &&
      replacementComplete.body.active_asset?.mime_type === "image/webp" &&
      replacementComplete.body.active_asset?.width === 1 &&
      replacementComplete.body.active_asset?.height === 1 &&
      replacementComplete.body.ocr_state === "failed",
    "M4-REPLACEMENT-COMPLETE-FAILED-OCR-PERSISTS",
  );
  const oldOcrRejected = await api(
    owner,
    `/items/${item.id}/assets/${reserved.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: {
        expected_version: replacementComplete.body.version,
        ocr_state: "ready",
        ocr_text: conflictOcr,
      },
    },
  );
  check(
    isApiError(oldOcrRejected, 409, "ASSET_NOT_ACTIVE") ||
      isApiError(oldOcrRejected, 404, "ASSET_NOT_FOUND"),
    "M4-OLD-ASSET-OCR-REJECTED",
  );
  const replacementOcrRetry = await api(
    owner,
    `/items/${item.id}/assets/${replacement.body.asset_id}/ocr`,
    {
      method: "PATCH",
      body: {
        expected_version: replacementComplete.body.version,
        ocr_state: "ready",
        ocr_text: replacementOcr,
      },
    },
  );
  check(
    replacementOcrRetry.status === 200 &&
      replacementOcrRetry.body.active_asset?.id === replacement.body.asset_id &&
      replacementOcrRetry.body.ocr_state === "ready",
    "M4-REPLACEMENT-OCR-RETRY",
  );
  check(
    (await proxyContent(owner, item.id, reserved.body.asset_id)).status ===
        404 &&
      sameBytes(
        (await proxyContent(owner, item.id, replacement.body.asset_id)).body,
        replacementBytes,
      ),
    "M4-REPLACEMENT-PROXY-SWITCH",
  );
  const usageBeforeReplacementCleanup = await usage(owner);
  const oldObjectBeforeReplacementCleanup = await readObject(
    serviceKey,
    reserved.body.object_path,
  );
  const conflictedObjectBeforeReplacementCleanup = await readObject(
    serviceKey,
    conflictedReservation.body.object_path,
  );
  check(
    [
      replacementBytes.byteLength,
      pngOne.byteLength + replacementBytes.byteLength,
    ].includes(usageBeforeReplacementCleanup.used_image_bytes) &&
      [0, MAX_IMAGE_BYTES].includes(
        usageBeforeReplacementCleanup.reserved_image_bytes,
      ) &&
      (
        usageBeforeReplacementCleanup.used_image_bytes !==
          replacementBytes.byteLength ||
        !isSuccessStatus(oldObjectBeforeReplacementCleanup.status)
      ) &&
      (
        usageBeforeReplacementCleanup.reserved_image_bytes !== 0 ||
        !isSuccessStatus(conflictedObjectBeforeReplacementCleanup.status)
      ),
    "M4-REPLACEMENT-ACCOUNTING-BEFORE-CLEANUP",
  );
  await runAssetCleanup(
    owner,
    [reserved.body.asset_id, conflictedReservation.body.asset_id],
    "M4-REPLACEMENT-CLEANUP",
  );
  const usageAfterReplacementCleanup = await usage(owner);
  check(
    usageAfterReplacementCleanup.used_image_bytes ===
        replacementBytes.byteLength &&
      usageAfterReplacementCleanup.reserved_image_bytes === 0,
    "M4-REPLACEMENT-ACCOUNTING-AFTER-CLEANUP",
  );
  check(
    !isSuccessStatus(
      (await readObject(serviceKey, reserved.body.object_path)).status,
    ) &&
      !isSuccessStatus(
        (await readObject(serviceKey, conflictedReservation.body.object_path))
          .status,
      ),
    "M4-REPLACED-FILES-PHYSICALLY-CLEANED",
  );

  item = await currentItem(owner, item.id);
  const deleteRequestId = randomUUID();
  const deleteBody = { expected_version: item.version };
  const deleted = await api(
    owner,
    `/items/${item.id}/assets/${replacement.body.asset_id}`,
    { method: "DELETE", body: deleteBody, requestId: deleteRequestId },
  );
  const deleteReplay = await api(
    owner,
    `/items/${item.id}/assets/${replacement.body.asset_id}`,
    { method: "DELETE", body: deleteBody, requestId: deleteRequestId },
  );
  const deleteChanged = await api(
    owner,
    `/items/${item.id}/assets/${replacement.body.asset_id}`,
    {
      method: "DELETE",
      body: { expected_version: item.version + 1 },
      requestId: deleteRequestId,
    },
  );
  check(
    deleted.status === 202 &&
      deleted.body.asset_id === replacement.body.asset_id &&
      deleteReplay.status === 202 &&
      deleteReplay.body.asset_id === replacement.body.asset_id &&
      isApiError(deleteChanged, 409, "IDEMPOTENCY_MISMATCH"),
    "M4-DELETE-REPLAY-IDENTITY-FROZEN",
  );
  const detailAfterDelete = await currentItem(owner, item.id);
  check(
    detailAfterDelete.has_attachment === false &&
      detailAfterDelete.active_asset === null &&
      detailAfterDelete.ocr_state === "not_requested" &&
      !(await searchItems(owner, replacementOcr)).some(
        (candidate) => candidate.id === item.id,
      ) &&
      (await proxyContent(owner, item.id, replacement.body.asset_id)).status ===
        404,
    "M4-DELETE-HIDES-ASSET-AND-OCR",
  );
  const deleteUsageObserved = await usage(owner);
  const deletedObjectObserved = await readObject(
    serviceKey,
    replacement.body.object_path,
  );
  check(
    [0, replacementBytes.byteLength].includes(
      deleteUsageObserved.used_image_bytes,
    ) &&
      deleteUsageObserved.reserved_image_bytes === 0 &&
      (
        deleteUsageObserved.used_image_bytes !== 0 ||
        !isSuccessStatus(deletedObjectObserved.status)
      ),
    "M4-DELETE-CLEANUP-RACE-SAFE-ACCOUNTING",
  );
  await runAssetCleanup(
    owner,
    [replacement.body.asset_id],
    "M4-DELETE-CLEANUP",
  );
  let ownerUsage = await usage(owner);
  check(
    ownerUsage.used_image_bytes === 0 &&
      ownerUsage.reserved_image_bytes === 0 &&
      !isSuccessStatus(
        (await readObject(serviceKey, replacement.body.object_path)).status,
      ),
    "M4-DELETE-CLEANUP-TERMINAL",
  );
  const emptyCleanup = await request(`${API_PREFIX}/internal/assets-cleanup`, {
    token: serviceKey,
    method: "POST",
    body: { limit: 10 },
  });
  assert.equal(emptyCleanup.status, 200, "M4-EMPTY-ASSET-CLEANUP");
  ownerUsage = await usage(owner);
  check(
    ownerUsage.used_image_bytes === 0 && ownerUsage.reserved_image_bytes === 0,
    "M4-CLEANUP-COUNTERS-RELEASE-ONCE",
  );

  const quotaItems = [];
  for (let index = 0; index < 11; index++) {
    const quotaItem = await saveItem(quotaOwner, {
      url: `https://example.test/quota-${randomUUID()}`,
    });
    quotaItems.push(await stableItem(quotaOwner, quotaItem.id));
  }
  const quotaReservations = [];
  for (let index = 0; index < 10; index++) {
    const result = await reserveAsset(
      quotaOwner,
      quotaItems[index].id,
      quotaItems[index].version,
    );
    assert.equal(result.status, 201, "M4-QUOTA-RESERVE");
    quotaReservations.push(result.body);
  }
  const quotaUsage = await usage(quotaOwner);
  check(
    quotaUsage.used_image_bytes === 0 &&
      quotaUsage.reserved_image_bytes === OWNER_STORAGE_BYTES,
    "M4-QUOTA-EXACT-RESERVED-ACCOUNTING",
  );
  const quotaRejected = await reserveAsset(
    quotaOwner,
    quotaItems[10].id,
    quotaItems[10].version,
  );
  check(
    isApiError(quotaRejected, 409, "STORAGE_LIMIT_REACHED"),
    "M4-QUOTA-ELEVENTH-RESERVATION-DENIED",
  );
  const tooLarge = new Uint8Array(MAX_IMAGE_BYTES + 1);
  const tooLargeUpload = await uploadObject(
    quotaOwner,
    quotaReservations[0].object_path,
    tooLarge,
    "image/png",
  );
  check(
    !isSuccessStatus(tooLargeUpload.status),
    "M4-STORAGE-MAX-BYTES-ENFORCED",
  );
  const quotaAssetIds = quotaReservations.map((asset) => asset.asset_id);
  await expireAssets(quotaOwner, quotaAssetIds);
  const expiredUpload = await uploadObject(
    quotaOwner,
    quotaReservations[0].object_path,
    pngOne,
    "image/png",
  );
  check(!isSuccessStatus(expiredUpload.status), "M4-EXPIRED-UPLOAD-DENIED");
  await runAssetCleanup(
    quotaOwner,
    quotaAssetIds,
    "M4-EXPIRY-CLEANUP",
  );
  const quotaAfterCleanup = await usage(quotaOwner);
  const expiredObjects = await Promise.all(
    quotaReservations.map((asset) => readObject(serviceKey, asset.object_path)),
  );
  check(
    quotaAfterCleanup.used_image_bytes === 0 &&
      quotaAfterCleanup.reserved_image_bytes === 0 &&
      expiredObjects.every((result) => !isSuccessStatus(result.status)),
    "M4-EXPIRY-CLEANUP-RELEASES-RESERVATIONS",
  );

  const unsupportedRetry = await api(
    owner,
    `/items/${item.id}/retry-metadata`,
    {
      method: "POST",
      body: { expected_version: detailAfterDelete.version },
    },
  );
  check(
    isApiError(unsupportedRetry, 409, "METADATA_UNSUPPORTED"),
    "M4-METADATA-UNSUPPORTED",
  );
  const invalidMetadataId = await api(
    owner,
    "/items/not-a-uuid/retry-metadata",
    {
      method: "POST",
      body: { expected_version: 1 },
    },
  );
  check(invalidMetadataId.status === 400, "M4-METADATA-UUID-VALIDATION");

  const publicMetadataItem = await saveItem(owner, {
    url: PUBLIC_NAVER_FIXTURE,
  });
  await stableItem(owner, publicMetadataItem.id);
  await settleActiveMetadata(
    owner,
    publicMetadataItem.id,
    "M4-PUBLIC-METADATA-IDLE",
  );
  const publicMetadataDetail = await currentItem(owner, publicMetadataItem.id);
  const invalidMetadataBody = await api(
    owner,
    `/items/${publicMetadataItem.id}/retry-metadata`,
    { method: "POST", body: { expected_version: "invalid" } },
  );
  check(invalidMetadataBody.status === 400, "M4-METADATA-BODY-VALIDATION");
  const foreignMetadata = await api(
    other,
    `/items/${publicMetadataItem.id}/retry-metadata`,
    {
      method: "POST",
      body: { expected_version: publicMetadataDetail.version },
    },
  );
  check(foreignMetadata.status === 404, "M4-METADATA-OWNER-ISOLATION");
  const retryMetadata = await api(
    owner,
    `/items/${publicMetadataItem.id}/retry-metadata`,
    {
      method: "POST",
      body: { expected_version: publicMetadataDetail.version },
    },
  );
  check(
    retryMetadata.status === 202 &&
      UUID_PATTERN.test(retryMetadata.body.job_id),
    "M4-METADATA-PUBLIC-RETRY",
  );
  const retriedJob = await metadataJob(owner, retryMetadata.body.job_id);
  check(
    ["queued", "running", "retry", "succeeded", "failed"].includes(
      retriedJob.state,
    ),
    "M4-METADATA-DURABLE-JOB",
  );
  const manualBuckets = await selectOwned(
    "api_rate_buckets",
    owner,
    "operation,request_count",
    { operation: `eq.metadata_manual:${publicMetadataItem.id}` },
  );
  check(
    manualBuckets.length === 1 && manualBuckets[0].request_count === 1,
    "M4-METADATA-MANUAL-BUDGET-RECORDED",
  );

  const workerMetadataItem = await saveItem(workerOwner, {
    url: PUBLIC_NAVER_FIXTURE,
  });
  await stableItem(workerOwner, workerMetadataItem.id);
  await settleActiveMetadata(
    workerOwner,
    workerMetadataItem.id,
    "M4-METADATA-INITIAL-DRAIN",
  );
  let workerDetail = await currentItem(workerOwner, workerMetadataItem.id);
  // Prior opportunistic work may have consumed this disposable item's daily
  // allowance. Reset only its fetch bucket so the explicit, unscheduled job
  // below is guaranteed to exercise the real worker rather than a budget gate.
  await resetMetadataFetchBudget(workerOwner, workerMetadataItem.id);
  const explicitJobId = await insertMetadataJob(workerOwner, workerDetail);
  await patchOwned(
    "items",
    workerOwner,
    { id: `eq.${workerDetail.id}` },
    { metadata_state: "queued" },
  );
  const terminal = await settleMetadataJob(
    workerOwner,
    explicitJobId,
    "M4-METADATA-EXPLICIT-WORKER",
  );
  const explicitlyClaimed = terminal.explicitlyClaimed;
  const terminalJob = terminal.job;
  workerDetail = await currentItem(workerOwner, workerMetadataItem.id);
  check(explicitlyClaimed > 0, "M4-METADATA-EXPLICIT-WORKER-CLAIMED");
  if (
    terminalJob.state !== "succeeded" || workerDetail.metadata_state !== "ready"
  ) {
    console.error(JSON.stringify({
      case: "M4-METADATA-TERMINAL",
      job_state: terminalJob.state,
      metadata_state: workerDetail.metadata_state,
      error_code: terminalJob.last_error_code,
      extraction_error: workerDetail.extraction_meta?.error_code,
    }));
  }
  check(
    terminalJob.state === "succeeded" &&
      workerDetail.metadata_state === "ready" &&
      terminalJob.last_error_code == null &&
      typeof workerDetail.fetched_title === "string" &&
      workerDetail.fetched_title.length > 0,
    "M4-METADATA-TERMINAL-SUCCESS",
  );
} catch (error) {
  primaryFailure = error;
  throw error;
} finally {
  const cleanupFailures = [];
  try {
    await removeOwnedStorage();
  } catch (error) {
    cleanupFailures.push(error);
  }
  try {
    await cleanup();
  } catch (error) {
    cleanupFailures.push(error);
  }
  if (cleanupFailures.length > 0) {
    throw new AggregateError(
      [...(primaryFailure ? [primaryFailure] : []), ...cleanupFailures],
      "M4-FIXTURE-CLEANUP",
    );
  }
}

console.log(`${checks} real local enrichment checks passed.`);
