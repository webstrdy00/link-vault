import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import { deflateSync } from "node:zlib";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const fixture = localBackendFixture("deletion");
const {
  request,
  user,
  approve,
  bootstrap,
  settleClassification,
  serviceKey,
} = fixture;
const API_PREFIX = "/functions/v1/library-api/v1";
const STORAGE_BUCKET = "library-images";
const MAX_IMAGE_BYTES = 2_000_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const createdAccounts = new Map();
const createdLedgerExports = new Set();
let checks = 0;

function check(condition, caseId) {
  assert.ok(condition, caseId);
  checks++;
  console.log(`PASS ${caseId}`);
}

function apiErrorCode(result) {
  return result.body?.error?.code ?? result.body?.error_code ??
    result.body?.code ?? null;
}

function isApiError(result, status, code) {
  return result.status === status && apiErrorCode(result) === code;
}

function hasExactKeys(value, keys) {
  return value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).sort().join("|") === [...keys].sort().join("|");
}

function validCountSummary(value, keys, arrayKeys = []) {
  return hasExactKeys(value, [...keys, ...arrayKeys]) &&
    keys.every((key) => Number.isInteger(value[key]) && value[key] >= 0) &&
    arrayKeys.every((key) =>
      Array.isArray(value[key]) &&
      value[key].every((entry) => typeof entry === "string")
    );
}

function validMaintenanceBody(body) {
  const accountKeys = [
    "claimed",
    "storage_objects_deleted",
    "business_purged",
    "auth_users_deleted",
    "completed",
    "retry_scheduled",
    "failed",
    "lease_lost",
  ];
  const assetKeys = [
    "claimed",
    "deleted",
    "completed",
    "retry_scheduled",
    "failed",
  ];
  const retentionKeys = [
    "deleted_api_requests",
    "deleted_rate_buckets",
    "deleted_challenges",
    "deleted_item_tombstones",
    "deleted_cancelled_jobs",
    "deleted_account_jobs",
    "deleted_ledger_events",
  ];
  return hasExactKeys(
    body,
    ["account_deletion", "asset_cleanup", "item_purge", "retention"],
  ) &&
    validCountSummary(body.account_deletion, accountKeys, ["error_codes"]) &&
    validCountSummary(body.asset_cleanup, assetKeys, ["error_codes"]) &&
    validCountSummary(body.item_purge, ["purged_items"]) &&
    validCountSummary(body.retention, retentionKeys);
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function requireUuid(value) {
  assert.match(value, UUID_PATTERN);
  return value.toLowerCase();
}

function requireHash(value) {
  assert.match(value, SHA256_PATTERN);
  return value;
}

function sqlString(value) {
  assert.equal(typeof value, "string");
  return `'${value.replaceAll("'", "''")}'`;
}

function operationalSql(sql) {
  try {
    return execFileSync("docker", [
      "exec",
      "supabase_db_link-vault",
      "psql",
      "-U",
      "postgres",
      "-d",
      "postgres",
      "-v",
      "ON_ERROR_STOP=1",
      "-v",
      "VERBOSITY=sqlstate",
      "-At",
      "-c",
      sql,
    ], {
      encoding: "utf8",
      timeout: 30_000,
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    }).trim();
  } catch (error) {
    const state = /ERROR:\s+([A-Z0-9]{5})\b/.exec(String(error?.stderr ?? ""))
      ?.[1];
    throw new Error(state ? `LOCAL_SQL_${state}` : "LOCAL_SQL_FAILED");
  }
}

function operationalJson(expression) {
  const raw = operationalSql(`select (${expression})::text;`);
  assert.notEqual(raw, "", "trusted SQL returned a JSON value");
  return JSON.parse(raw);
}

function operationalCount(query) {
  const raw = operationalSql(
    `select count(*)::text from (${query}) as counted;`,
  );
  assert.match(raw, /^\d+$/);
  return Number(raw);
}

function encodedObjectPath(objectPath) {
  return objectPath.split("/").map(encodeURIComponent).join("/");
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
  const scanlines = Buffer.from([0, ...pixel, ...pixel, 0, ...pixel, ...pixel]);
  return new Uint8Array(Buffer.concat([
    signature,
    pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(scanlines)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]));
}

async function assertEmptyFixtureDatabase() {
  const counts = operationalJson(`
    pg_catalog.jsonb_build_object(
      'auth_users', (select count(*) from auth.users),
      'profiles', (select count(*) from public.profiles),
      'members', (select count(*) from public.beta_members),
      'items', (select count(*) from public.items),
      'assets', (select count(*) from public.assets),
      'account_jobs', (select count(*) from public.account_deletion_jobs),
      'ledger_events', (select count(*) from private.deletion_ledger),
      'ledger_exports', (select count(*) from private.deletion_ledger_exports),
      'library_objects', (select count(*) from storage.objects where bucket_id = 'library-images')
    )
  `);
  assert.deepEqual(counts, {
    auth_users: 0,
    profiles: 0,
    members: 0,
    items: 0,
    assets: 0,
    account_jobs: 0,
    ledger_events: 0,
    ledger_exports: 0,
    library_objects: 0,
  }, "Deletion integration requires an empty local fixture database");
  check(true, "M5-EMPTY-LOCAL-FIXTURE-PREFLIGHT");
}

async function createAccount({ approved = false, bootstrapped = false } = {}) {
  const account = await user();
  createdAccounts.set(requireUuid(account.id), account);
  if (approved) await approve(account);
  if (bootstrapped) {
    const result = await bootstrap(account);
    assert.equal(result.status, 200, "bootstrap a created fixture owner");
  }
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

async function internalApi(path, body = { limit: 10 }) {
  return request(`${API_PREFIX}${path}`, {
    token: serviceKey,
    method: "POST",
    body,
  });
}

async function resetCreateRate(account) {
  const result = await request(
    `/rest/v1/api_rate_buckets?owner_id=eq.${account.id}&operation=eq.create_item`,
    { token: serviceKey, method: "DELETE" },
  );
  assert.equal(
    result.status,
    204,
    "reset only the fixture owner's create rate bucket",
  );
}

async function saveItem(account, body, requestId = randomUUID()) {
  const result = await api(account, "/items", {
    method: "POST",
    body,
    requestId,
  });
  if (result.status === 429) {
    await resetCreateRate(account);
    return saveItem(account, body, requestId);
  }
  assert.equal(
    result.status,
    201,
    "create a deletion fixture item through the real API",
  );
  assert.match(result.body.item.id, UUID_PATTERN);
  return { item: result.body.item, requestId };
}

async function itemDetail(account, itemId) {
  return api(account, `/items/${itemId}`);
}

async function searchItems(account, query) {
  const parameters = new URLSearchParams({ q: query, limit: "50" });
  const result = await api(account, `/items?${parameters}`);
  assert.equal(result.status, 200, "search the real owner projection");
  return result.body.items;
}

async function selectOwned(table, account, columns, filters = {}) {
  assert.ok(
    createdAccounts.has(account.id),
    "read only a created fixture owner",
  );
  const parameters = new URLSearchParams({
    owner_id: `eq.${account.id}`,
    select: columns,
    ...filters,
  });
  const result = await request(`/rest/v1/${table}?${parameters}`, {
    token: serviceKey,
  });
  assert.equal(result.status, 200, `read fixture-owned ${table}`);
  return result.body;
}

async function usage(account) {
  const rows = await selectOwned(
    "library_usage",
    account,
    "active_item_count,used_image_bytes,reserved_image_bytes",
  );
  assert.equal(rows.length, 1, "fixture owner has one usage row");
  return rows[0];
}

async function reserveAsset(account, itemId, expectedVersion) {
  const result = await api(account, `/items/${itemId}/assets/reserve`, {
    method: "POST",
    body: { expected_version: expectedVersion, mime_type: "image/png" },
  });
  assert.equal(result.status, 201, "reserve a real private Storage object");
  assert.ok(result.body.object_path.startsWith(`${account.id}/${itemId}/`));
  return result.body;
}

async function uploadObject(account, objectPath, bytes) {
  const result = await request(
    `/storage/v1/object/${STORAGE_BUCKET}/${encodedObjectPath(objectPath)}`,
    {
      token: account.token,
      method: "POST",
      body: bytes,
      headers: { "Content-Type": "image/png", "x-upsert": "false" },
    },
  );
  assert.equal(result.status, 200, "upload real PNG bytes through Storage");
}

async function readObject(objectPath) {
  return request(
    `/storage/v1/object/${STORAGE_BUCKET}/${encodedObjectPath(objectPath)}`,
    { token: serviceKey, responseType: "bytes" },
  );
}

async function completeAsset(account, item, reservation, bytes, ocrText) {
  await uploadObject(account, reservation.object_path, bytes);
  const body = {
    expected_version: item.version,
    ocr_state: "ready",
    ocr_text: ocrText,
  };
  const result = await api(
    account,
    `/items/${item.id}/assets/${reservation.asset_id}/complete`,
    { method: "POST", body },
  );
  assert.equal(result.status, 200, "complete a verified real PNG asset");
  assert.equal(result.body.active_asset.id, reservation.asset_id);
  assert.equal(result.body.active_asset.byte_size, bytes.byteLength);
  assert.equal(result.body.active_asset.ocr_text, ocrText);
  return result.body;
}

async function listOwnerStorageObjects(ownerId) {
  requireUuid(ownerId);
  const objects = [];
  const pendingPrefixes = [ownerId];
  const visited = new Set();
  while (pendingPrefixes.length > 0) {
    const prefix = pendingPrefixes.pop();
    if (visited.has(prefix)) continue;
    visited.add(prefix);
    assert.ok(prefix === ownerId || prefix.startsWith(`${ownerId}/`));
    let offset = 0;
    while (true) {
      const listed = await request(
        `/storage/v1/object/list/${STORAGE_BUCKET}`,
        {
          token: serviceKey,
          method: "POST",
          body: {
            prefix,
            limit: 100,
            offset,
            sortBy: { column: "name", order: "asc" },
          },
        },
      );
      assert.equal(
        listed.status,
        200,
        "list only a created owner's Storage prefix",
      );
      for (const entry of listed.body) {
        assert.match(entry.name, /^[^/]+$/);
        const path = `${prefix}/${entry.name}`;
        if (entry.id) objects.push(path);
        else pendingPrefixes.push(path);
      }
      if (listed.body.length < 100) break;
      offset += listed.body.length;
    }
  }
  assert.ok(objects.every((path) => path.startsWith(`${ownerId}/`)));
  return objects;
}

async function removeOwnerStorage(ownerId) {
  const paths = await listOwnerStorageObjects(ownerId);
  if (paths.length > 0) {
    const removed = await request(`/storage/v1/object/${STORAGE_BUCKET}`, {
      token: serviceKey,
      method: "DELETE",
      body: { prefixes: paths },
    });
    assert.equal(removed.status, 200, "remove only this run's Storage objects");
  }
  assert.deepEqual(await listOwnerStorageObjects(ownerId), []);
}

async function physicallyDeleteThenFinishAssets(account, expectedAssetIds) {
  const ids = expectedAssetIds.map((id) => sqlString(requireUuid(id))).join(
    ",",
  );
  for (let attempt = 0; attempt < 40; attempt++) {
    assert.equal((await internalApi("/internal/assets-cleanup")).status, 200);
    if (
      operationalCount(`
      select 1 from public.assets
      where owner_id = ${sqlString(account.id)}::uuid and id in (${ids})
    `) === 0
    ) break;
    await pause(250);
  }
  const receipt = operationalJson(`
    (select jsonb_build_object(
      'count', count(*), 'used', coalesce(sum(released_used_bytes), 0),
      'reserved', coalesce(sum(released_reserved_bytes), 0)
    ) from private.asset_cleanup_receipts
    where owner_id = ${sqlString(account.id)}::uuid and asset_id in (${ids}))
  `);
  assert.deepEqual(
    receipt,
    {
      count: expectedAssetIds.length,
      used: png.byteLength,
      reserved: MAX_IMAGE_BYTES,
    },
    "every asset has one durable physical-cleanup receipt even when waitUntil wins the race",
  );
}

async function markLatestJobsRunning(account, itemId) {
  requireUuid(account.id);
  requireUuid(itemId);
  // Unsupported example.test URLs intentionally create no metadata fetch.
  // Seed its leased job explicitly to test cancellation without external traffic.
  operationalSql(`
    insert into public.processing_jobs (
      owner_id, item_id, kind, target_revision, state, attempts, lease_until, lease_token
    )
    select item.owner_id, item.id, 'metadata', item.text_revision, 'running', 1,
      pg_catalog.clock_timestamp() + interval '3 minutes', gen_random_uuid()
    from public.items as item
    where item.owner_id = ${sqlString(account.id)}::uuid
      and item.id = ${sqlString(itemId)}::uuid
      and not exists (
        select 1 from public.processing_jobs as job
        where job.owner_id = item.owner_id and job.item_id = item.id and job.kind = 'metadata'
      );
  `);
  const changed = JSON.parse(operationalSql(`
      with latest as (
        select distinct on (kind) id
        from public.processing_jobs
        where owner_id = ${sqlString(account.id)}::uuid
          and item_id = ${sqlString(itemId)}::uuid
          and kind in ('classify', 'metadata')
        order by kind, target_revision desc, created_at desc, id desc
      ), updated as (
        update public.processing_jobs as job
        set state = 'running',
            attempts = job.attempts + 1,
            lease_until = pg_catalog.clock_timestamp() + interval '3 minutes',
            lease_token = gen_random_uuid(),
            last_error_code = null,
            updated_at = pg_catalog.clock_timestamp()
        from latest
        where job.id = latest.id
        returning job.kind
      )
      select coalesce(jsonb_agg(kind order by kind), '[]'::jsonb)::text from updated;
  `));
  assert.deepEqual(
    changed,
    ["classify", "metadata"],
    "seed one stale lease of each processing kind",
  );
}

async function deleteItem(
  account,
  itemId,
  expectedVersion,
  requestId = randomUUID(),
) {
  return api(account, `/items/${itemId}`, {
    method: "DELETE",
    requestId,
    body: { expected_version: expectedVersion },
  });
}

async function accountChallenge(account, requestId = randomUUID(), body = {}) {
  return api(account, "/account/delete-challenge", {
    method: "POST",
    requestId,
    body,
  });
}

async function serviceRpc(name, body) {
  return request(`/rest/v1/rpc/${name}`, {
    token: serviceKey,
    method: "POST",
    body,
  });
}

async function assertChallengeBinding(account, challenge) {
  const nonceHash = sha256(challenge.nonce);
  const binding = await serviceRpc("library_check_delete_challenge_binding", {
    p_owner_id: account.id,
    p_challenge_id: challenge.challenge_id,
    p_nonce_hash: nonceHash,
  });
  assert.equal(
    binding.status,
    200,
    "verify the actual challenge binding at the SQL boundary",
  );
  assert.deepEqual(binding.body, { http_status: 200, state: "valid" });
  return nonceHash;
}

async function trustedAcceptAccountDeletion(
  account,
  challenge,
  requestId = randomUUID(),
) {
  // Integration fixture boundary: the public endpoint below proves an opaque token is
  // refused. Production Google signatures are covered by account-deletion_test.ts.
  // Only after checking this real nonce binding do we seed the accepted job directly
  // through trusted local SQL so Storage -> business -> Auth cleanup can be exercised.
  await assertChallengeBinding(account, challenge);
  const fixtureBody = {
    challenge_id: challenge.challenge_id,
    google_id_token: "trusted-local-integration-boundary-not-a-google-token",
  };
  const requestHash = sha256(JSON.stringify(fixtureBody));
  requireHash(requestHash);
  const result = operationalJson(`
    public.library_accept_account_deletion(
      ${sqlString(requireUuid(account.id))}::uuid,
      ${sqlString(requireUuid(requestId))}::uuid,
      ${sqlString(requireUuid(challenge.challenge_id))}::uuid,
      ${sqlString(requestHash)}
    )
  `);
  assert.deepEqual(result, { http_status: 202, state: "deleting" });
  return { requestId, requestHash };
}

function accountJob(ownerId) {
  requireUuid(ownerId);
  return operationalJson(`
    coalesce(
      (select pg_catalog.to_jsonb(job) from public.account_deletion_jobs as job
       where owner_id = ${sqlString(ownerId)}::uuid),
      'null'::jsonb
    )
  `);
}

function authUserExists(ownerId) {
  requireUuid(ownerId);
  return operationalCount(
    `select 1 from auth.users where id = ${sqlString(ownerId)}::uuid`,
  ) === 1;
}

async function deleteAuthUser(ownerId) {
  const result = await request(`/auth/v1/admin/users/${requireUuid(ownerId)}`, {
    token: serviceKey,
    method: "DELETE",
  });
  assert.ok(
    [200, 404].includes(result.status),
    "delete only a created fixture Auth user",
  );
  assert.equal(authUserExists(ownerId), false, "created Auth user is absent");
}

async function claimOneAccountJob(ownerId) {
  const claimed = await serviceRpc("library_claim_account_deletion_jobs", {
    p_limit: 1,
  });
  assert.equal(claimed.status, 200, "claim one account deletion lease");
  assert.equal(claimed.body.jobs.length, 1);
  assert.equal(claimed.body.jobs[0].owner_id, ownerId);
  assert.match(claimed.body.jobs[0].lease_token, UUID_PATTERN);
  return claimed.body.jobs[0];
}

async function waitForPgNetResponse(requestId) {
  assert.match(String(requestId), /^\d+$/);
  for (let attempt = 0; attempt < 80; attempt++) {
    const response = operationalJson(`
      coalesce(
        (select pg_catalog.jsonb_build_object(
          'status_code', status_code,
          'content', content,
          'timed_out', timed_out,
          'error_msg', error_msg
        ) from net._http_response where id = ${Number(requestId)}),
        'null'::jsonb
      )
    `);
    if (response !== null) return response;
    await pause(250);
  }
  assert.fail(
    "manual pg_net maintenance dispatch did not return within 20 seconds",
  );
}

async function waitForAccountJobTerminal(ownerId) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const job = accountJob(ownerId);
    if (job?.state === "complete") return job;
    await pause(250);
  }
  assert.fail("account deletion job did not reach complete within 10 seconds");
}

async function cleanupCreatedOwners() {
  const failures = [];
  for (const ownerId of createdAccounts.keys()) {
    let storageCleared = false;
    try {
      await removeOwnerStorage(ownerId);
      storageCleared = true;
      const assets = await request(`/rest/v1/assets?owner_id=eq.${ownerId}`, {
        token: serviceKey,
        method: "DELETE",
      });
      assert.equal(
        assets.status,
        204,
        "remove only physically cleared fixture asset rows",
      );
    } catch (error) {
      failures.push(error);
    }
    if (!storageCleared) continue;
    try {
      await deleteAuthUser(ownerId);
      operationalSql(`
        delete from private.deletion_ledger where owner_id = ${
        sqlString(ownerId)
      }::uuid;
        delete from public.account_deletion_jobs where owner_id = ${
        sqlString(ownerId)
      }::uuid;
        delete from private.item_deletion_tombstones where owner_id = ${
        sqlString(ownerId)
      }::uuid;
        delete from private.asset_cleanup_receipts where owner_id = ${
        sqlString(ownerId)
      }::uuid;
      `);
    } catch (error) {
      failures.push(error);
    }
  }
  for (const exportId of createdLedgerExports) {
    try {
      operationalSql(`
        delete from private.deletion_ledger_exports
        where export_id = ${sqlString(requireUuid(exportId))}::uuid;
      `);
    } catch (error) {
      failures.push(error);
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, "created-owner cleanup failed");
  }
}

const png = smallPng(42, 96, 180);
const deletionOcr = "M5 삭제 즉시 검색 제거";
let primaryFailure;

try {
  await assertEmptyFixtureDatabase();

  const actorA = await createAccount({ approved: true, bootstrapped: true });
  const actorB = await createAccount({ approved: true, bootstrapped: true });
  const actorAOther = await saveItem(actorA, {
    url: `https://example.test/a-unaffected-${randomUUID()}`,
    title: "A unaffected",
  });
  const actorBOther = await saveItem(actorB, {
    url: `https://example.test/b-unaffected-${randomUUID()}`,
    title: "B unaffected",
  });
  await settleClassification(actorA, actorAOther.item.id);
  await settleClassification(actorB, actorBOther.item.id);
  const actorBBefore = await usage(actorB);

  const targetUrl = `https://example.test/deleted-source-${randomUUID()}`;
  const targetBody = { url: targetUrl, title: "Delete target" };
  const target = await saveItem(actorA, targetBody);
  let targetItem = (await settleClassification(actorA, target.item.id)).body;
  const activeReservation = await reserveAsset(
    actorA,
    targetItem.id,
    targetItem.version,
  );
  targetItem = await completeAsset(
    actorA,
    targetItem,
    activeReservation,
    png,
    deletionOcr,
  );
  targetItem = (await settleClassification(actorA, targetItem.id)).body;
  check(
    (await searchItems(actorA, "M5 삭제")).some((item) =>
      item.id === targetItem.id
    ),
    "M5-ITEM-OCR-INDEX-REAL-BEFORE-DELETE",
  );
  const pendingReservation = await reserveAsset(
    actorA,
    targetItem.id,
    targetItem.version,
  );
  await uploadObject(actorA, pendingReservation.object_path, png);
  await markLatestJobsRunning(actorA, targetItem.id);
  const usageBeforeDelete = await usage(actorA);
  check(
    usageBeforeDelete.active_item_count === 2 &&
      usageBeforeDelete.used_image_bytes === png.byteLength &&
      usageBeforeDelete.reserved_image_bytes === MAX_IMAGE_BYTES,
    "M5-ITEM-COUNTERS-BEFORE-DELETE",
  );

  const deleteRequestId = randomUUID();
  const accepted = await deleteItem(
    actorA,
    targetItem.id,
    targetItem.version,
    deleteRequestId,
  );
  check(
    accepted.status === 202 &&
      JSON.stringify(accepted.body) === JSON.stringify({
          item_id: targetItem.id,
          state: "deleting",
        }),
    "M5-ITEM-DELETE-202-EXACT",
  );
  const acceptedReplay = await deleteItem(
    actorA,
    targetItem.id,
    targetItem.version,
    deleteRequestId,
  );
  check(
    acceptedReplay.status === 202 &&
      JSON.stringify(acceptedReplay.body) === JSON.stringify(accepted.body),
    "M5-ITEM-DELETE-202-REPLAY",
  );
  const acceptedMismatch = await deleteItem(
    actorA,
    targetItem.id,
    targetItem.version + 1,
    deleteRequestId,
  );
  check(
    isApiError(acceptedMismatch, 409, "IDEMPOTENCY_MISMATCH"),
    "M5-ITEM-DELETE-ACCEPTED-IDENTITY-FROZEN",
  );
  check(
    isApiError(
      await deleteItem(actorA, targetItem.id, targetItem.version, randomUUID()),
      404,
      "ITEM_NOT_FOUND",
    ),
    "M5-ITEM-DELETE-FRESH-404",
  );
  check(
    (await itemDetail(actorA, targetItem.id)).status === 404 &&
      (await itemDetail(actorB, targetItem.id)).status === 404 &&
      !(await searchItems(actorA, deletionOcr)).some((item) =>
        item.id === targetItem.id
      ),
    "M5-ITEM-IMMEDIATE-HIDE-AND-OCR-INDEX-REMOVE",
  );

  const deletedRows = await selectOwned(
    "items",
    actorA,
    "id,original_url,shared_text,user_title,note,metadata_state,deleted_at",
    { id: `eq.${targetItem.id}` },
  );
  const deletedJobs = await selectOwned(
    "processing_jobs",
    actorA,
    "kind,state,lease_until,lease_token,last_error_code",
    { item_id: `eq.${targetItem.id}` },
  );
  const deletedAssets = await selectOwned(
    "assets",
    actorA,
    "id,state,ocr_state,ocr_text,cleanup_reason",
    { item_id: `eq.${targetItem.id}` },
  );
  const relevantDeletedJobs = deletedJobs.filter((job) =>
    ["classify", "metadata"].includes(job.kind)
  );
  check(
    deletedRows.length === 1 &&
      deletedRows[0].original_url === null &&
      deletedRows[0].shared_text === null &&
      deletedRows[0].user_title === null &&
      deletedRows[0].note === null &&
      deletedRows[0].metadata_state === null &&
      deletedRows[0].deleted_at !== null &&
      relevantDeletedJobs.every((job) =>
        !["queued", "running", "retry"].includes(job.state) &&
        (job.state !== "cancelled" ||
          (job.lease_until === null && job.lease_token === null))
      ) &&
      relevantDeletedJobs.some((job) =>
        job.kind === "classify" && job.state === "cancelled"
      ) &&
      relevantDeletedJobs.some((job) =>
        job.kind === "metadata" && job.state === "cancelled"
      ) &&
      deletedAssets.length <= 2 &&
      deletedAssets.every((asset) =>
        asset.state === "deleting" &&
        asset.ocr_state === "not_requested" &&
        asset.ocr_text === null &&
        asset.cleanup_reason === "item_delete"
      ),
    "M5-ITEM-SCRUB-CANCEL-LEASES-ASSETS",
  );
  const staleComplete = await api(
    actorA,
    `/items/${targetItem.id}/assets/${pendingReservation.asset_id}/complete`,
    {
      method: "POST",
      body: {
        expected_version: targetItem.version,
        ocr_state: "not_requested",
      },
    },
  );
  check(
    isApiError(staleComplete, 410, "ITEM_DELETED"),
    "M5-ITEM-STALE-ASSET-COMPLETE-NO-RESURRECTION",
  );
  assert.equal((await internalApi("/internal/classify")).status, 200);
  assert.equal((await internalApi("/internal/metadata")).status, 200);
  check(
    (await selectOwned("item_search", actorA, "item_id", {
          item_id: `eq.${targetItem.id}`,
        })).length === 0 &&
      (await selectOwned("item_classification", actorA, "item_id", {
          item_id: `eq.${targetItem.id}`,
        })).length === 0 &&
      (await selectOwned("item_categories", actorA, "item_id", {
          item_id: `eq.${targetItem.id}`,
        })).length === 0,
    "M5-ITEM-STALE-WORKERS-NO-RESURRECTION",
  );

  const afterLogicalDelete = operationalJson(`
    (select jsonb_build_object(
      'active', usage.active_item_count,
      'used', usage.used_image_bytes,
      'reserved', usage.reserved_image_bytes,
      'charged_used', coalesce((select sum(actual_bytes) from public.assets where owner_id = usage.owner_id), 0),
      'charged_reserved', coalesce((select sum(reserved_bytes) from public.assets where owner_id = usage.owner_id and actual_bytes is null), 0)
    ) from public.library_usage as usage where usage.owner_id = ${
    sqlString(actorA.id)
  }::uuid)
  `);
  check(
    afterLogicalDelete.active === 1 &&
      afterLogicalDelete.used === afterLogicalDelete.charged_used &&
      afterLogicalDelete.reserved === afterLogicalDelete.charged_reserved,
    "M5-ITEM-CHARGED-ASSETS-MATCH-USAGE-DURING-CLEANUP",
  );
  await physicallyDeleteThenFinishAssets(
    actorA,
    [activeReservation.asset_id, pendingReservation.asset_id],
  );
  const afterPhysicalDelete = await usage(actorA);
  check(
    ![200, 206].includes(
      (await readObject(activeReservation.object_path)).status,
    ) &&
      ![200, 206].includes(
        (await readObject(pendingReservation.object_path)).status,
      ) &&
      afterPhysicalDelete.used_image_bytes === 0 &&
      afterPhysicalDelete.reserved_image_bytes === 0,
    "M5-ITEM-STORAGE-ABSENT-BEFORE-USAGE-RELEASE",
  );
  const oldCreateReplayAfterPhysicalPurge = await api(actorA, "/items", {
    method: "POST",
    body: targetBody,
    requestId: target.requestId,
  });
  check(
    isApiError(oldCreateReplayAfterPhysicalPurge, 410, "ITEM_DELETED"),
    "M5-ITEM-SOURCE-POST-OLD-UUID-410-AFTER-PHYSICAL-PURGE",
  );

  const conflict = await saveItem(actorA, {
    url: `https://example.test/delete-conflict-${randomUUID()}`,
    title: "old version",
  });
  const conflictStable =
    (await settleClassification(actorA, conflict.item.id)).body;
  const edited = await api(actorA, `/items/${conflict.item.id}`, {
    method: "PATCH",
    body: { expected_version: conflictStable.version, title: "new version" },
  });
  assert.equal(edited.status, 200, "bump a version before the delete conflict");
  const conflictRequestId = randomUUID();
  const firstConflict = await deleteItem(
    actorA,
    conflict.item.id,
    conflictStable.version,
    conflictRequestId,
  );
  const conflictReplay = await deleteItem(
    actorA,
    conflict.item.id,
    conflictStable.version,
    conflictRequestId,
  );
  const conflictMismatch = await deleteItem(
    actorA,
    conflict.item.id,
    edited.body.version,
    conflictRequestId,
  );
  check(
    isApiError(firstConflict, 409, "VERSION_CONFLICT") &&
      isApiError(conflictReplay, 409, "VERSION_CONFLICT") &&
      isApiError(conflictMismatch, 409, "IDEMPOTENCY_MISMATCH"),
    "M5-ITEM-DELETE-CONFLICT-STICKY-REPLAY-MISMATCH",
  );
  const reviewedConflict =
    (await settleClassification(actorA, conflict.item.id)).body;
  const freshConflictDelete = await deleteItem(
    actorA,
    conflict.item.id,
    reviewedConflict.version,
    randomUUID(),
  );
  assert.equal(
    freshConflictDelete.status,
    202,
    "a reviewed current version deletes with a new UUID",
  );

  const actorBAfter = await usage(actorB);
  check(
    JSON.stringify(actorBAfter) === JSON.stringify(actorBBefore) &&
      (await itemDetail(actorB, actorBOther.item.id)).status === 200 &&
      (await itemDetail(actorA, actorAOther.item.id)).status === 200,
    "M5-ITEM-ACTOR-ISOLATION-UNAFFECTED-ROWS-COUNTERS",
  );

  operationalSql(`
    update public.api_requests
    set created_at = pg_catalog.clock_timestamp() - interval '8 days'
    where owner_id = ${sqlString(actorA.id)}::uuid
      and (
        request_id in (
          ${sqlString(target.requestId)}::uuid,
          ${sqlString(deleteRequestId)}::uuid
        )
        or response_body ->> 'item_id' = ${sqlString(targetItem.id)}
      );
  `);
  const retentionBeforePurge = await serviceRpc("library_run_retention", {
    p_now: new Date().toISOString(),
  });
  assert.equal(
    retentionBeforePurge.status,
    200,
    "expire the target's old request receipts",
  );
  operationalSql(`
    update public.items
    set deleted_at = pg_catalog.clock_timestamp() - interval '31 days'
    where owner_id = ${sqlString(actorA.id)}::uuid
      and id = ${sqlString(targetItem.id)}::uuid;
  `);
  const maintenanceDenied = await api(actorA, "/internal/maintenance", {
    method: "POST",
    body: { limit: 10 },
  });
  check(
    maintenanceDenied.status === 401 &&
      apiErrorCode(maintenanceDenied) === "UNAUTHENTICATED",
    "M5-MAINTENANCE-MEMBER-DENIED",
  );
  const maintenance = await internalApi("/internal/maintenance");
  check(
    maintenance.status === 200 &&
      validMaintenanceBody(maintenance.body) &&
      maintenance.body.item_purge.purged_items === 1,
    "M5-MAINTENANCE-REAL-ENDPOINT",
  );
  check(
    operationalCount(
      `select 1 from public.items where owner_id = ${
        sqlString(actorA.id)
      }::uuid and id = ${sqlString(targetItem.id)}::uuid`,
    ) === 0,
    "M5-ITEM-PURGE-TERMINAL",
  );
  check(
    isApiError(
      await deleteItem(actorA, targetItem.id, targetItem.version, randomUUID()),
      404,
      "ITEM_NOT_FOUND",
    ),
    "M5-ITEM-FINAL-DELETE-404-AFTER-PURGE",
  );

  const profileless = await createAccount();
  const challengeRequestId = randomUUID();
  const profilelessChallenge = await accountChallenge(
    profileless,
    challengeRequestId,
  );
  assert.equal(
    profilelessChallenge.status,
    201,
    "create an actual profileless deletion challenge",
  );
  const challengeReplay = await accountChallenge(
    profileless,
    challengeRequestId,
  );
  check(
    challengeReplay.status === 201 &&
      challengeReplay.body.challenge_id ===
        profilelessChallenge.body.challenge_id &&
      challengeReplay.body.nonce === profilelessChallenge.body.nonce &&
      challengeReplay.body.expires_at === profilelessChallenge.body.expires_at,
    "M5-ACCOUNT-CHALLENGE-STABLE-BY-UUID",
  );
  assert.match(profilelessChallenge.body.nonce, /^[A-Za-z0-9_-]{43}$/);
  const nonceColumns = operationalSql(`
    select pg_catalog.string_agg(column_name, ',' order by ordinal_position)
    from information_schema.columns
    where table_schema = 'private' and table_name = 'auth_challenges';
  `).split(",");
  const storedNonceHash = operationalSql(`
    select nonce_hash from private.auth_challenges
    where id = ${sqlString(profilelessChallenge.body.challenge_id)}::uuid;
  `);
  check(
    nonceColumns.includes("nonce_hash") &&
      !nonceColumns.includes("nonce") &&
      storedNonceHash === sha256(profilelessChallenge.body.nonce) &&
      storedNonceHash !== profilelessChallenge.body.nonce,
    "M5-ACCOUNT-NONCE-HASH-ONLY-IN-DATABASE",
  );

  const malformedChallenge = await accountChallenge(profileless, randomUUID(), {
    extra: true,
  });
  const malformedDelete = await api(profileless, "/account/delete", {
    method: "POST",
    body: { challenge_id: profilelessChallenge.body.challenge_id },
  });
  const publicInvalidProof = await api(profileless, "/account/delete", {
    method: "POST",
    body: {
      challenge_id: profilelessChallenge.body.challenge_id,
      google_id_token: "test-dummy-opaque-google-jwt",
    },
  });
  check(
    isApiError(malformedChallenge, 400, "INVALID_REQUEST") &&
      isApiError(malformedDelete, 400, "INVALID_REQUEST") &&
      publicInvalidProof.status === 403 &&
      apiErrorCode(publicInvalidProof) === "GOOGLE_IDENTITY_REQUIRED" &&
      operationalCount(
          `select 1 from public.profiles where id = ${
            sqlString(profileless.id)
          }::uuid`,
        ) === 0 &&
      accountJob(profileless.id) === null,
    "M5-ACCOUNT-PUBLIC-INVALID-PROOF-REFUSED-UNCHANGED",
  );

  const pending = await createAccount();
  const pendingMember = await request("/rest/v1/beta_members", {
    token: serviceKey,
    method: "POST",
    body: { owner_id: pending.id, enabled: false },
  });
  assert.equal(pendingMember.status, 201, "seed a real pending member fixture");
  const pendingChallenge = await accountChallenge(pending);
  assert.equal(
    pendingChallenge.status,
    201,
    "pending member can request deletion challenge",
  );

  const revoked = await createAccount({ approved: true, bootstrapped: true });
  const revokedItemSeed = await saveItem(revoked, {
    url: `https://example.test/revoked-delete-${randomUUID()}`,
    title: "revoked cleanup",
  });
  let revokedItem =
    (await settleClassification(revoked, revokedItemSeed.item.id)).body;
  const revokedReservation = await reserveAsset(
    revoked,
    revokedItem.id,
    revokedItem.version,
  );
  revokedItem = await completeAsset(
    revoked,
    revokedItem,
    revokedReservation,
    png,
    "revoked owner OCR",
  );
  const revoke = await request(
    `/rest/v1/beta_members?owner_id=eq.${revoked.id}`,
    {
      token: serviceKey,
      method: "PATCH",
      body: { enabled: false },
    },
  );
  assert.equal(
    revoke.status,
    204,
    "revoke the fixture member before challenge",
  );
  const revokedChallenge = await accountChallenge(revoked);
  assert.equal(
    revokedChallenge.status,
    201,
    "revoked member can request deletion challenge",
  );

  const wrongOwnerBinding = await serviceRpc(
    "library_check_delete_challenge_binding",
    {
      p_owner_id: pending.id,
      p_challenge_id: profilelessChallenge.body.challenge_id,
      p_nonce_hash: sha256(profilelessChallenge.body.nonce),
    },
  );
  const expiredChallenge = await accountChallenge(profileless);
  assert.equal(expiredChallenge.status, 201);
  operationalSql(`
    update private.auth_challenges
    set created_at = pg_catalog.clock_timestamp() - interval '10 minutes',
        expires_at = pg_catalog.clock_timestamp() - interval '1 second'
    where id = ${sqlString(expiredChallenge.body.challenge_id)}::uuid;
  `);
  const expiredBinding = await serviceRpc(
    "library_check_delete_challenge_binding",
    {
      p_owner_id: profileless.id,
      p_challenge_id: expiredChallenge.body.challenge_id,
      p_nonce_hash: sha256(expiredChallenge.body.nonce),
    },
  );
  check(
    wrongOwnerBinding.status === 400 &&
      wrongOwnerBinding.body?.message === "DELETE_CHALLENGE_INVALID" &&
      expiredBinding.status === 400 &&
      expiredBinding.body?.message === "DELETE_CHALLENGE_EXPIRED" &&
      operationalCount(
          `select 1 from public.profiles where id in (` +
            `${sqlString(profileless.id)}::uuid, ${
              sqlString(pending.id)
            }::uuid)`,
        ) === 0 &&
      accountJob(profileless.id) === null &&
      accountJob(pending.id) === null,
    "M5-ACCOUNT-WRONG-OWNER-EXPIRED-CHALLENGES",
  );

  await trustedAcceptAccountDeletion(revoked, revokedChallenge.body);
  const usedBinding = await serviceRpc(
    "library_check_delete_challenge_binding",
    {
      p_owner_id: revoked.id,
      p_challenge_id: revokedChallenge.body.challenge_id,
      p_nonce_hash: sha256(revokedChallenge.body.nonce),
    },
  );
  check(
    usedBinding.status === 400 &&
      usedBinding.body?.message === "DELETE_CHALLENGE_USED",
    "M5-ACCOUNT-CHALLENGE-ONE-TIME-USE",
  );
  const memberJobRead = await request(
    `/rest/v1/account_deletion_jobs?owner_id=eq.${revoked.id}&select=owner_id`,
    { token: revoked.token },
  );
  const oldJwtProfile = await request(
    `/rest/v1/profiles?id=eq.${revoked.id}&select=id,state`,
    {
      token: revoked.token,
    },
  );
  const oldJwtLibrary = await api(revoked, "/items");
  const oldJwtMe = await api(revoked, "/me");
  check(
    [401, 403].includes(memberJobRead.status) &&
      oldJwtProfile.status === 200 && oldJwtProfile.body.length === 0 &&
      isApiError(oldJwtLibrary, 403, "ACCOUNT_DELETING") &&
      oldJwtMe.status === 200 && oldJwtMe.body.state === "deleting",
    "M5-ACCOUNT-OLD-JWT-RLS-AND-STATE-DENIAL",
  );
  const acceptedUsage = operationalJson(`
    (select pg_catalog.to_jsonb(usage) - 'owner_id' - 'updated_at'
     from public.library_usage as usage where owner_id = ${
    sqlString(revoked.id)
  }::uuid)
  `);
  check(
    acceptedUsage.active_item_count === 0 &&
      acceptedUsage.used_image_bytes === png.byteLength &&
      acceptedUsage.reserved_image_bytes === 0 &&
      (await readObject(revokedReservation.object_path)).status === 200,
    "M5-ACCOUNT-ACCEPT-HIDES-ITEM-KEEPS-BYTES",
  );

  const firstLease = await claimOneAccountJob(revoked.id);
  const prepared = await serviceRpc("library_prepare_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: firstLease.lease_token,
  });
  const blockedPurge = await serviceRpc("library_purge_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: firstLease.lease_token,
  });
  check(
    prepared.status === 200 &&
      prepared.body.object_count === 1 &&
      prepared.body.objects[0].object_path === revokedReservation.object_path &&
      blockedPurge.status === 200 &&
      blockedPurge.body.http_status === 409 &&
      blockedPurge.body.error_code === "STORAGE_OBJECTS_PRESENT" &&
      authUserExists(revoked.id) &&
      operationalCount(
          `select 1 from public.items where owner_id = ${
            sqlString(revoked.id)
          }::uuid`,
        ) === 1,
    "M5-ACCOUNT-STORAGE-MUST-PRECEDE-BUSINESS-PURGE",
  );
  const failedLease = await serviceRpc("library_fail_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: firstLease.lease_token,
    p_error_code: "ACCOUNT_STORAGE_DELETE_FAILED",
  });
  assert.equal(failedLease.status, 200);
  assert.equal(failedLease.body.state, "retry");
  operationalSql(`
    update public.account_deletion_jobs
    set next_run_at = pg_catalog.clock_timestamp() - interval '1 second'
    where owner_id = ${sqlString(revoked.id)}::uuid;
  `);
  const secondLease = await claimOneAccountJob(revoked.id);
  const staleLease = await serviceRpc("library_prepare_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: firstLease.lease_token,
  });
  check(
    secondLease.lease_token !== firstLease.lease_token &&
      secondLease.attempts === firstLease.attempts + 1 &&
      staleLease.status === 200 && staleLease.body.error_code === "LEASE_LOST",
    "M5-ACCOUNT-INTERRUPTED-RESUME-STALE-LEASE",
  );

  await removeOwnerStorage(revoked.id);
  check(
    ![200, 206].includes(
      (await readObject(revokedReservation.object_path)).status,
    ),
    "M5-ACCOUNT-OWNED-STORAGE-PHYSICALLY-ABSENT",
  );
  const purged = await serviceRpc("library_purge_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: secondLease.lease_token,
  });
  check(
    purged.status === 200 &&
      purged.body.state === "ready_for_auth_delete" &&
      purged.body.released_used_bytes === png.byteLength &&
      authUserExists(revoked.id) &&
      operationalCount(
          `select 1 from public.items where owner_id = ${
            sqlString(revoked.id)
          }::uuid`,
        ) === 0 &&
      operationalCount(
          `select 1 from public.library_usage where owner_id = ${
            sqlString(revoked.id)
          }::uuid`,
        ) === 0,
    "M5-ACCOUNT-BUSINESS-PURGE-PRECEDES-AUTH",
  );
  const prematureFinalize = await serviceRpc(
    "library_finalize_account_deletion",
    {
      p_owner_id: revoked.id,
      p_lease_token: secondLease.lease_token,
    },
  );
  assert.equal(prematureFinalize.body.error_code, "AUTH_USER_PRESENT");
  await deleteAuthUser(revoked.id);
  const finalized = await serviceRpc("library_finalize_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: secondLease.lease_token,
  });
  const staleFinalize = await serviceRpc("library_finalize_account_deletion", {
    p_owner_id: revoked.id,
    p_lease_token: firstLease.lease_token,
  });
  check(
    finalized.status === 200 && finalized.body.state === "complete" &&
      staleFinalize.body.error_code === "LEASE_LOST" &&
      operationalCount(
          `select 1 from public.profiles where id = ${
            sqlString(revoked.id)
          }::uuid`,
        ) === 0 &&
      operationalCount(
          `select 1 from private.deletion_ledger where owner_id = ${
            sqlString(revoked.id)
          }::uuid and kind = 'account'`,
        ) === 1 &&
      operationalCount(
          `select 1 from private.deletion_ledger where owner_id = ${
            sqlString(revoked.id)
          }::uuid and kind = 'item'`,
        ) === 1,
    "M5-ACCOUNT-AUTH-THEN-FINALIZE-LEDGER",
  );

  const freshProfilelessChallenge = await accountChallenge(profileless);
  const freshPendingChallenge = await accountChallenge(pending);
  assert.equal(freshProfilelessChallenge.status, 201);
  assert.equal(freshPendingChallenge.status, 201);
  await trustedAcceptAccountDeletion(
    profileless,
    freshProfilelessChallenge.body,
  );
  await trustedAcceptAccountDeletion(pending, freshPendingChallenge.body);
  check(
    accountJob(profileless.id)?.state === "queued" &&
      accountJob(pending.id)?.state === "queued",
    "M5-ACCOUNT-PROFILELESS-PENDING-ACCEPTED-SQL",
  );
  const cronRow = operationalJson(`
    (select pg_catalog.jsonb_build_object(
      'active', active,
      'schedule', schedule,
      'command', command
    ) from cron.job where jobname = 'link-vault-maintenance')
  `);
  assert.deepEqual(cronRow, {
    active: true,
    schedule: "*/5 * * * *",
    command: "select private.dispatch_maintenance_jobs();",
  }, "maintenance cron is independently durable");
  const dispatchRequestId = operationalSql(
    "select private.dispatch_maintenance_jobs()::text;",
  );
  const dispatchResponse = await waitForPgNetResponse(dispatchRequestId);
  let dispatchBody = null;
  try {
    dispatchBody = JSON.parse(dispatchResponse.content);
  } catch {
    // The assertion below reports only fixed diagnostics, never the credential-bearing request.
  }
  check(
    dispatchResponse.status_code === 200 &&
      dispatchResponse.timed_out === false &&
      dispatchResponse.error_msg === null &&
      validMaintenanceBody(dispatchBody) &&
      dispatchBody.account_deletion.claimed === 2 &&
      dispatchBody.account_deletion.completed === 2 &&
      dispatchBody.account_deletion.auth_users_deleted === 2 &&
      dispatchBody.account_deletion.failed === 0 &&
      dispatchBody.account_deletion.retry_scheduled === 0 &&
      dispatchBody.account_deletion.lease_lost === 0,
    "M5-MAINTENANCE-MANUAL-PG-NET-FRESH-HTTP",
  );
  const profilelessTerminal = await waitForAccountJobTerminal(profileless.id);
  const pendingTerminal = await waitForAccountJobTerminal(pending.id);
  check(
    profilelessTerminal.state === "complete" &&
      pendingTerminal.state === "complete" &&
      !authUserExists(profileless.id) &&
      !authUserExists(pending.id),
    "M5-MAINTENANCE-ACCOUNT-JOBS-AUTH-TERMINAL",
  );

  operationalSql(`
    update private.deletion_ledger
    set requested_at = pg_catalog.clock_timestamp() - interval '31 days'
    where owner_id = ${sqlString(revoked.id)}::uuid and kind = 'account';
    update public.account_deletion_jobs
    set requested_at = pg_catalog.clock_timestamp() - interval '33 days',
        business_purged_at = pg_catalog.clock_timestamp() - interval '32 days',
        completed_at = pg_catalog.clock_timestamp() - interval '31 days',
        next_run_at = pg_catalog.clock_timestamp() - interval '31 days'
    where owner_id = ${sqlString(revoked.id)}::uuid and state = 'complete';
  `);
  const retentionWithoutExport = await serviceRpc("library_run_retention", {
    p_now: new Date().toISOString(),
  });
  assert.equal(retentionWithoutExport.status, 200);
  check(
    operationalCount(
          `select 1 from private.deletion_ledger where owner_id = ${
            sqlString(revoked.id)
          }::uuid`,
        ) === 2 && accountJob(revoked.id)?.state === "complete",
    "M5-ACCOUNT-LEDGER-RETENTION-FAILS-CLOSED-WITHOUT-EXPORT",
  );
  const ledgerExport = await serviceRpc("library_export_deletion_ledger", {});
  assert.equal(
    ledgerExport.status,
    200,
    "create a bounded deletion ledger export",
  );
  createdLedgerExports.add(requireUuid(ledgerExport.body.export_id));
  const exportedEvents = await serviceRpc(
    "library_read_deletion_ledger_export",
    {
      p_export_id: ledgerExport.body.export_id,
      p_after_sequence: 0,
      p_limit: 1000,
    },
  );
  assert.equal(
    exportedEvents.status,
    200,
    "read the deletion ledger export before acknowledgement",
  );
  assert.ok(
    exportedEvents.body.events.some((event) =>
      event.kind === "account" && event.owner_id === revoked.id
    ),
    "export contains the completed account deletion event",
  );
  const acknowledgedExport = await serviceRpc(
    "library_ack_deletion_ledger_export",
    {
      p_export_id: ledgerExport.body.export_id,
    },
  );
  assert.deepEqual(acknowledgedExport.body, {
    export_id: ledgerExport.body.export_id,
    acknowledged: true,
  });
  const retentionAfterExport = await serviceRpc("library_run_retention", {
    p_now: new Date().toISOString(),
  });
  check(
    retentionAfterExport.status === 200 &&
      operationalCount(
          `select 1 from private.deletion_ledger where owner_id = ${
            sqlString(revoked.id)
          }::uuid and kind = 'account'`,
        ) === 0 &&
      operationalCount(
          `select 1 from private.deletion_ledger where owner_id = ${
            sqlString(revoked.id)
          }::uuid and kind = 'item'`,
        ) === 1 &&
      accountJob(revoked.id) === null,
    "M5-ACCOUNT-LEDGER-RETENTION-AFTER-VERIFIED-EXPORT",
  );

  const operationStatus = await serviceRpc("library_operation_status", {});
  check(
    operationStatus.status === 200 &&
      operationStatus.body.usage_mismatches === 0 &&
      operationStatus.body.expired_account_leases === 0 &&
      operationStatus.body.maintenance_scheduled === true,
    "M5-DELETION-OPERATIONAL-COUNTERS-HEALTHY",
  );

  console.log(
    "COVERAGE Real local email sessions intentionally have no linked Google identity; the public opaque-token refusal is exercised. Wrong-owner, expired, and reused bindings are verified at the trusted SQL boundary because reaching them through the public endpoint requires a valid Google proof. Cryptographic Google success/mismatch/nonce signatures stay in account-deletion_test.ts and require no production Google call.",
  );
} catch (error) {
  primaryFailure = error;
} finally {
  try {
    await cleanupCreatedOwners();
  } catch (error) {
    primaryFailure = primaryFailure
      ? new AggregateError(
        [primaryFailure, error],
        "Deletion integration and cleanup failed",
      )
      : error;
  }
}

if (primaryFailure) throw primaryFailure;
console.log(`${checks} real local deletion checks passed.`);
