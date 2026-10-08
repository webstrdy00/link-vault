import { execFileSync } from "node:child_process";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as pause } from "node:timers/promises";
import { deflateSync } from "node:zlib";
import {
  createLibraryBackup,
  exportDeletionLedger,
} from "./backup-library.mjs";
import {
  backupCredentialFromEnv,
  decryptAndValidateArtifact,
} from "./backup-format.mjs";
import { localBackendFixture } from "./local-backend-fixture.mjs";
import { restoreLibrary } from "./restore-library.mjs";

let request;
let user;
let approve;
let bootstrap;
let settleClassification;
let serviceKey;
const API_PREFIX = "/functions/v1/library-api/v1";
const STORAGE_BUCKET = "library-images";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FIXED_CODE_PATTERN = /^[A-Z0-9_]+$/;
const createdAccounts = new Map();
const createdExportIds = new Set();
let checks = 0;

function initializeFixture() {
  ({
    request,
    user,
    approve,
    bootstrap,
    settleClassification,
    serviceKey,
  } = localBackendFixture("backup"));
}

function fail(code) {
  const error = new Error(code);
  error.code = code;
  throw error;
}

function check(condition, code) {
  if (!condition) fail(code);
  checks++;
  console.log(`PASS ${code}`);
}

function exactKeys(value, keys) {
  return value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).sort().join("|") === [...keys].sort().join("|");
}

function requireUuid(value) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    fail("BACKUP_FIXTURE_UUID_INVALID");
  }
  return value.toLowerCase();
}

function sqlString(value) {
  if (typeof value !== "string") fail("BACKUP_FIXTURE_SQL_VALUE_INVALID");
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
      "-At",
      "-c",
      sql,
    ], {
      encoding: "utf8",
      timeout: 30_000,
      maxBuffer: 2 * 1024 * 1024,
      stdio: ["ignore", "pipe", "ignore"],
      windowsHide: true,
    }).trim();
  } catch {
    fail("BACKUP_FIXTURE_DATABASE_QUERY_FAILED");
  }
}

function operationalJson(expression) {
  const output = operationalSql(`select (${expression})::text;`);
  if (output === "") fail("BACKUP_FIXTURE_DATABASE_RESULT_INVALID");
  try {
    return JSON.parse(output);
  } catch {
    fail("BACKUP_FIXTURE_DATABASE_RESULT_INVALID");
  }
}

function operationalCount(query) {
  const output = operationalSql(
    `select pg_catalog.count(*)::text from (${query}) as counted;`,
  );
  if (!/^\d+$/.test(output)) fail("BACKUP_FIXTURE_DATABASE_RESULT_INVALID");
  return Number(output);
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function apiErrorCode(result) {
  return result.body?.error?.code ?? result.body?.error_code ??
    result.body?.code ?? null;
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

function sameBytes(left, right) {
  return left instanceof Uint8Array &&
    right instanceof Uint8Array &&
    left.byteLength === right.byteLength &&
    Buffer.compare(Buffer.from(left), Buffer.from(right)) === 0;
}

async function assertEmptyFixtureDatabase() {
  const keys = [
    "auth_users",
    "profiles",
    "members",
    "items",
    "assets",
    "account_jobs",
    "ledger_events",
    "ledger_exports",
    "ledger_export_events",
    "auth_challenges",
    "item_tombstones",
    "category_authorizations",
    "library_objects",
  ];
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
      'ledger_export_events', (select count(*) from private.deletion_ledger_export_events),
      'auth_challenges', (select count(*) from private.auth_challenges),
      'item_tombstones', (select count(*) from private.item_deletion_tombstones),
      'category_authorizations', (
        select count(*) from private.category_normalization_authorizations
      ),
      'library_objects', (
        select count(*) from storage.objects where bucket_id = 'library-images'
      )
    )
  `);
  check(
    exactKeys(counts, keys) && keys.every((key) => counts[key] === 0),
    "BACKUP_EMPTY_LOCAL_FIXTURE_REQUIRED",
  );
}

async function createApprovedAccount() {
  const account = await user();
  createdAccounts.set(requireUuid(account.id), account);
  await approve(account);
  const result = await bootstrap(account);
  if (result.status !== 200) fail("BACKUP_FIXTURE_BOOTSTRAP_FAILED");
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

async function serviceRpc(name, body) {
  return request(`/rest/v1/rpc/${name}`, {
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
  if (result.status !== 204) fail("BACKUP_FIXTURE_RATE_RESET_FAILED");
}

async function saveItem(account, body) {
  const requestId = randomUUID();
  let result = await api(account, "/items", {
    method: "POST",
    body,
    requestId,
  });
  if (result.status === 429) {
    await resetCreateRate(account);
    result = await api(account, "/items", { method: "POST", body, requestId });
  }
  if (
    result.status !== 201 || !UUID_PATTERN.test(result.body?.item?.id ?? "")
  ) {
    fail("BACKUP_FIXTURE_ITEM_CREATE_FAILED");
  }
  return { item: result.body.item, requestId, body };
}

async function stableItem(account, itemId) {
  const result = await settleClassification(account, itemId);
  if (result.status !== 200 || result.body?.id !== itemId) {
    fail("BACKUP_FIXTURE_CLASSIFICATION_FAILED");
  }
  return result.body;
}

async function reserveAsset(account, item) {
  const result = await api(account, `/items/${item.id}/assets/reserve`, {
    method: "POST",
    body: { expected_version: item.version, mime_type: "image/png" },
  });
  if (
    result.status !== 201 ||
    !UUID_PATTERN.test(result.body?.asset_id ?? "") ||
    !result.body?.object_path?.startsWith(`${account.id}/${item.id}/`)
  ) {
    fail("BACKUP_FIXTURE_ASSET_RESERVE_FAILED");
  }
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
  if (result.status !== 200) fail("BACKUP_FIXTURE_STORAGE_UPLOAD_FAILED");
}

async function readObject(objectPath) {
  return request(
    `/storage/v1/object/${STORAGE_BUCKET}/${encodedObjectPath(objectPath)}`,
    { token: serviceKey, responseType: "bytes" },
  );
}

async function completeAsset(account, item, reservation, bytes, ocrText) {
  await uploadObject(account, reservation.object_path, bytes);
  const result = await api(
    account,
    `/items/${item.id}/assets/${reservation.asset_id}/complete`,
    {
      method: "POST",
      body: {
        expected_version: item.version,
        ocr_state: "ready",
        ocr_text: ocrText,
      },
    },
  );
  if (
    result.status !== 200 ||
    result.body?.active_asset?.id !== reservation.asset_id ||
    result.body?.active_asset?.byte_size !== bytes.byteLength ||
    result.body?.active_asset?.ocr_text !== ocrText
  ) {
    fail("BACKUP_FIXTURE_ASSET_COMPLETE_FAILED");
  }
  return result.body;
}

async function attachAsset(account, item, bytes, ocrText) {
  const reservation = await reserveAsset(account, item);
  await completeAsset(account, item, reservation, bytes, ocrText);
  const stable = await stableItem(account, item.id);
  if (
    stable.active_asset?.id !== reservation.asset_id ||
    stable.active_asset?.ocr_text !== ocrText
  ) {
    fail("BACKUP_FIXTURE_ASSET_DETAIL_INVALID");
  }
  return { item: stable, reservation };
}

async function usage(account) {
  const parameters = new URLSearchParams({
    owner_id: `eq.${account.id}`,
    select: "active_item_count,used_image_bytes,reserved_image_bytes",
  });
  const result = await request(`/rest/v1/library_usage?${parameters}`, {
    token: serviceKey,
  });
  if (result.status !== 200 || result.body?.length !== 1) {
    fail("BACKUP_FIXTURE_USAGE_INVALID");
  }
  return result.body[0];
}

async function deleteItem(account, item) {
  const result = await api(account, `/items/${item.id}`, {
    method: "DELETE",
    body: { expected_version: item.version },
  });
  if (
    result.status !== 202 ||
    result.body?.item_id !== item.id ||
    result.body?.state !== "deleting"
  ) {
    fail("BACKUP_FIXTURE_ITEM_DELETE_FAILED");
  }
}

async function createAccountChallenge(account) {
  const result = await api(account, "/account/delete-challenge", {
    method: "POST",
    body: {},
  });
  if (
    result.status !== 201 ||
    !UUID_PATTERN.test(result.body?.challenge_id ?? "") ||
    !/^[A-Za-z0-9_-]{43}$/.test(result.body?.nonce ?? "")
  ) {
    fail("BACKUP_FIXTURE_ACCOUNT_CHALLENGE_FAILED");
  }
  return result.body;
}

async function trustedAcceptAccountDeletion(account, challenge) {
  const nonceHash = sha256(challenge.nonce);
  const binding = await serviceRpc("library_check_delete_challenge_binding", {
    p_owner_id: account.id,
    p_challenge_id: challenge.challenge_id,
    p_nonce_hash: nonceHash,
  });
  if (
    binding.status !== 200 ||
    binding.body?.http_status !== 200 ||
    binding.body?.state !== "valid"
  ) {
    fail("BACKUP_FIXTURE_DELETE_CHALLENGE_BINDING_FAILED");
  }

  const fixtureBody = {
    challenge_id: challenge.challenge_id,
    google_id_token: "trusted-local-backup-fixture-not-a-google-token",
  };
  const publicAttempt = await api(account, "/account/delete", {
    method: "POST",
    body: fixtureBody,
  });
  if (
    publicAttempt.status !== 403 ||
    apiErrorCode(publicAttempt) !== "GOOGLE_IDENTITY_REQUIRED"
  ) {
    fail("BACKUP_FIXTURE_PUBLIC_GOOGLE_BOUNDARY_FAILED");
  }

  // This is trusted fixture seeding, not Google verification and not a public product
  // path. The real Edge endpoint above must reject this opaque value. Cryptographic
  // Google proof cases remain in account-deletion_test.ts; deletion-integration.mjs
  // directly verifies the Storage -> business rows -> Auth worker ordering.
  const requestId = randomUUID();
  const requestHash = sha256(JSON.stringify(fixtureBody));
  const accepted = operationalJson(`
    public.library_accept_account_deletion(
      ${sqlString(requireUuid(account.id))}::uuid,
      ${sqlString(requireUuid(requestId))}::uuid,
      ${sqlString(requireUuid(challenge.challenge_id))}::uuid,
      ${sqlString(requestHash)}
    )
  `);
  if (accepted?.http_status !== 202 || accepted?.state !== "deleting") {
    fail("BACKUP_FIXTURE_ACCOUNT_ACCEPT_FAILED");
  }
}

function deletedOwnerCounts(ownerId) {
  const owner = sqlString(requireUuid(ownerId));
  return operationalJson(`
    pg_catalog.jsonb_build_object(
      'auth_users', (select count(*) from auth.users where id = ${owner}::uuid),
      'profiles', (select count(*) from public.profiles where id = ${owner}::uuid),
      'items', (select count(*) from public.items where owner_id = ${owner}::uuid),
      'assets', (select count(*) from public.assets where owner_id = ${owner}::uuid),
      'storage_objects', (
        select count(*) from storage.objects
        where bucket_id = 'library-images' and name like ${owner} || '/%'
      ),
      'complete_jobs', (
        select count(*) from public.account_deletion_jobs
        where owner_id = ${owner}::uuid and state = 'complete'
      )
    )
  `);
}

async function runMaintenanceUntilAccountDeleted(account) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const maintenance = await internalApi("/internal/maintenance");
    if (maintenance.status !== 200) fail("BACKUP_FIXTURE_MAINTENANCE_FAILED");
    const counts = deletedOwnerCounts(account.id);
    if (
      counts.auth_users === 0 &&
      counts.profiles === 0 &&
      counts.items === 0 &&
      counts.assets === 0 &&
      counts.storage_objects === 0 &&
      counts.complete_jobs === 1
    ) {
      return;
    }
    await pause(250);
  }
  fail("BACKUP_FIXTURE_ACCOUNT_DELETE_TIMEOUT");
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
    if (prefix !== ownerId && !prefix.startsWith(`${ownerId}/`)) {
      fail("BACKUP_FIXTURE_STORAGE_SCOPE_INVALID");
    }
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
      if (listed.status !== 200 || !Array.isArray(listed.body)) {
        fail("BACKUP_FIXTURE_STORAGE_LIST_FAILED");
      }
      for (const entry of listed.body) {
        if (typeof entry.name !== "string" || entry.name.includes("/")) {
          fail("BACKUP_FIXTURE_STORAGE_LIST_INVALID");
        }
        const objectPath = `${prefix}/${entry.name}`;
        if (entry.id) objects.push(objectPath);
        else pendingPrefixes.push(objectPath);
      }
      if (listed.body.length < 100) break;
      offset += listed.body.length;
    }
  }
  if (!objects.every((objectPath) => objectPath.startsWith(`${ownerId}/`))) {
    fail("BACKUP_FIXTURE_STORAGE_SCOPE_INVALID");
  }
  return objects;
}

async function removeOwnerStorage(ownerId) {
  const objectPaths = await listOwnerStorageObjects(ownerId);
  if (objectPaths.length > 0) {
    const removed = await request(`/storage/v1/object/${STORAGE_BUCKET}`, {
      token: serviceKey,
      method: "DELETE",
      body: { prefixes: objectPaths },
    });
    if (removed.status !== 200) fail("BACKUP_FIXTURE_STORAGE_CLEANUP_FAILED");
  }
  if ((await listOwnerStorageObjects(ownerId)).length !== 0) {
    fail("BACKUP_FIXTURE_STORAGE_CLEANUP_FAILED");
  }
}

async function deleteOwnedAuthUser(ownerId) {
  const result = await request(`/auth/v1/admin/users/${requireUuid(ownerId)}`, {
    token: serviceKey,
    method: "DELETE",
  });
  if (![200, 404].includes(result.status)) {
    fail("BACKUP_FIXTURE_AUTH_CLEANUP_FAILED");
  }
  if (
    operationalCount(
      `select 1 from auth.users where id = ${sqlString(ownerId)}::uuid`,
    ) !== 0
  ) {
    fail("BACKUP_FIXTURE_AUTH_CLEANUP_FAILED");
  }
}

async function cleanupCreatedOwners() {
  let cleanupFailed = false;
  for (const ownerId of createdAccounts.keys()) {
    let storageAndAssetsCleared = false;
    try {
      await removeOwnerStorage(ownerId);
      const assets = await request(`/rest/v1/assets?owner_id=eq.${ownerId}`, {
        token: serviceKey,
        method: "DELETE",
      });
      if (assets.status !== 204) fail("BACKUP_FIXTURE_ASSET_CLEANUP_FAILED");
      storageAndAssetsCleared = true;
    } catch {
      cleanupFailed = true;
    }
    if (!storageAndAssetsCleared) continue;
    try {
      await deleteOwnedAuthUser(ownerId);
    } catch {
      cleanupFailed = true;
    }
  }

  if (createdAccounts.size > 0) {
    try {
      const owners = [...createdAccounts.keys()]
        .map((ownerId) => `${sqlString(requireUuid(ownerId))}::uuid`)
        .join(", ");
      const exports = [...createdExportIds]
        .map((exportId) => `${sqlString(requireUuid(exportId))}::uuid`)
        .join(", ");
      operationalSql(`
        delete from private.deletion_ledger_exports as ledger_export
        where ${
        exports === "" ? "false" : `ledger_export.export_id in (${exports})`
      }
           or exists (
             select 1 from private.deletion_ledger_export_events as event
             where event.export_id = ledger_export.export_id
               and event.owner_id in (${owners})
           );
        delete from private.deletion_ledger where owner_id in (${owners});
        delete from public.account_deletion_jobs where owner_id in (${owners});
        delete from private.item_deletion_tombstones where owner_id in (${owners});
        delete from private.asset_cleanup_receipts where owner_id in (${owners});
        delete from private.category_normalization_authorizations where owner_id in (${owners});
        delete from private.auth_challenges where owner_id in (${owners});
      `);
    } catch {
      cleanupFailed = true;
    }
  }

  if (cleanupFailed) fail("BACKUP_FIXTURE_CLEANUP_FAILED");
}

function backupEnvironment() {
  const environment = {
    ...process.env,
    LINK_VAULT_BACKUP_KEY_BASE64: randomBytes(32).toString("base64"),
  };
  delete environment.LINK_VAULT_BACKUP_PASSWORD;
  return environment;
}

function additionalAuditSql({
  accountA,
  accountB,
  accountC,
  deletedCreate,
  deletedItem,
  retainedItem,
  retainedAsset,
  bytes,
  ocrText,
}) {
  const ownerA = sqlString(requireUuid(accountA.id));
  const ownerB = sqlString(requireUuid(accountB.id));
  const ownerC = sqlString(requireUuid(accountC.id));
  const deletedItemId = sqlString(requireUuid(deletedItem.id));
  const deletedCreateRequestId = sqlString(
    requireUuid(deletedCreate.requestId),
  );
  const deletedCreateBody = sqlString(JSON.stringify(deletedCreate.body));
  const retainedItemId = sqlString(requireUuid(retainedItem.id));
  const retainedAssetId = sqlString(requireUuid(retainedAsset.asset_id));
  const retainedPath = sqlString(retainedAsset.object_path);
  const retainedOcr = sqlString(ocrText);
  // ITEM_DELETED is the handler's 410 replay path; ITEM_NOT_FOUND (404) fails this audit.
  return `
\\set QUIET on
do $audit$
declare
  replay_outcome text := 'RETURNED';
begin
  begin
    perform public.library_create_item(
      ${ownerA}::uuid,
      ${deletedCreateRequestId}::uuid,
      ${deletedCreateBody}::jsonb,
      '{}'::jsonb
    );
  exception
    when sqlstate 'P0001' then replay_outcome := sqlerrm;
  end;
  if replay_outcome <> 'ITEM_DELETED' then
    raise exception using errcode = 'P0001', message = 'RESTORE_CREATE_REPLAY_NOT_GONE';
  end if;
end
$audit$;
\\unset QUIET
select pg_catalog.json_build_object(
  'a_auth', (select count(*) from auth.users where id = ${ownerA}::uuid),
  'a_profile', (
    select count(*) from public.profiles where id = ${ownerA}::uuid and state = 'active'
  ),
  'a_member', (
    select count(*) from public.beta_members
    where owner_id = ${ownerA}::uuid and enabled and approved_at is not null
  ),
  'a_items', (select count(*) from public.items where owner_id = ${ownerA}::uuid),
  'a_minimal_deleted_item', (
    select count(*) from public.items
    where owner_id = ${ownerA}::uuid
      and id = ${deletedItemId}::uuid
      and deleted_at is not null
      and original_url is null
      and normalized_url is null
      and url_hash is null
      and source is null
      and display_fallback is null
      and shared_text is null
      and user_title is null
      and fetched_title is null
      and description is null
      and body_text is null
      and note is null
      and extraction_meta = '{}'::jsonb
      and metadata_state is null
  ),
  'a_deleted_item_derived', (
    (select count(*) from public.item_search
      where owner_id = ${ownerA}::uuid and item_id = ${deletedItemId}::uuid) +
    (select count(*) from public.item_classification
      where owner_id = ${ownerA}::uuid and item_id = ${deletedItemId}::uuid) +
    (select count(*) from public.item_category_controls
      where owner_id = ${ownerA}::uuid and item_id = ${deletedItemId}::uuid) +
    (select count(*) from public.item_categories
      where owner_id = ${ownerA}::uuid and item_id = ${deletedItemId}::uuid) +
    (select count(*) from public.processing_jobs
      where owner_id = ${ownerA}::uuid
        and item_id = ${deletedItemId}::uuid
        and state in ('queued', 'running', 'retry')) +
    (select count(*) from public.assets where owner_id = ${ownerA}::uuid) +
    (select count(*) from storage.objects
      where bucket_id = 'library-images'
        and name like ${ownerA} || '/' || ${deletedItemId} || '/%')
  ),
  'a_tombstone', (
    select count(*) from private.item_deletion_tombstones
    where owner_id = ${ownerA}::uuid and item_id = ${deletedItemId}::uuid
  ),
  'a_usage', (
    select count(*) from public.library_usage
    where owner_id = ${ownerA}::uuid
      and active_item_count = 0
      and used_image_bytes = 0
      and reserved_image_bytes = 0
  ),
  'a_create_replay_410', 1,
  'b_auth', (select count(*) from auth.users where id = ${ownerB}::uuid),
  'b_profile', (
    select count(*) from public.profiles where id = ${ownerB}::uuid and state = 'active'
  ),
  'b_items', (
    select count(*) from public.items
    where owner_id = ${ownerB}::uuid and id = ${retainedItemId}::uuid and deleted_at is null
  ),
  'b_asset', (
    select count(*) from public.assets
    where owner_id = ${ownerB}::uuid
      and item_id = ${retainedItemId}::uuid
      and id = ${retainedAssetId}::uuid
      and object_path = ${retainedPath}
      and state = 'active'
      and deleted_at is null
      and actual_bytes = ${bytes}
  ),
  'b_ocr', (
    select count(*) from public.assets
    where id = ${retainedAssetId}::uuid
      and ocr_state = 'ready'
      and ocr_text = ${retainedOcr}
  ),
  'b_storage', (
    select count(*) from storage.objects
    where bucket_id = 'library-images' and name = ${retainedPath}
  ),
  'b_usage', (
    select count(*) from public.library_usage as usage
    where usage.owner_id = ${ownerB}::uuid
      and usage.active_item_count = 1
      and usage.used_image_bytes = ${bytes}
      and usage.reserved_image_bytes = 0
      and usage.active_item_count = (
        select count(*)::integer from public.items as item
        where item.owner_id = usage.owner_id and item.deleted_at is null
      )
      and usage.used_image_bytes = (
        select coalesce(sum(asset.actual_bytes), 0)::bigint from public.assets as asset
        where asset.owner_id = usage.owner_id
          and asset.state = 'active'
          and asset.deleted_at is null
      )
  ),
  'c_auth', (select count(*) from auth.users where id = ${ownerC}::uuid),
  'c_profile', (select count(*) from public.profiles where id = ${ownerC}::uuid),
  'c_business', (
    (select count(*) from public.beta_members where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.library_usage where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.categories where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.items where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.item_search where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.item_classification where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.item_category_controls where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.item_categories where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.processing_jobs where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.api_requests where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.api_rate_buckets where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.assets where owner_id = ${ownerC}::uuid) +
    (select count(*) from public.account_deletion_jobs where owner_id = ${ownerC}::uuid) +
    (select count(*) from private.auth_challenges where owner_id = ${ownerC}::uuid) +
    (select count(*) from private.category_normalization_authorizations
      where owner_id = ${ownerC}::uuid)
  ),
  'c_storage', (
    select count(*) from storage.objects
    where bucket_id = 'library-images' and name like ${ownerC} || '/%'
  )
)::text;
`;
}

function validateAdditionalAudit(text) {
  const expected = {
    a_auth: 1,
    a_profile: 1,
    a_member: 1,
    a_items: 1,
    a_minimal_deleted_item: 1,
    a_deleted_item_derived: 0,
    a_tombstone: 1,
    a_usage: 1,
    a_create_replay_410: 1,
    b_auth: 1,
    b_profile: 1,
    b_items: 1,
    b_asset: 1,
    b_ocr: 1,
    b_storage: 1,
    b_usage: 1,
    c_auth: 0,
    c_profile: 0,
    c_business: 0,
    c_storage: 0,
  };
  let actual;
  try {
    actual = JSON.parse(text);
  } catch {
    fail("BACKUP_RESTORE_ADDITIONAL_AUDIT_INVALID");
  }
  check(
    exactKeys(actual, Object.keys(expected)) &&
      Object.entries(expected).every(([key, value]) => actual[key] === value),
    "BACKUP_RESTORE_ADDITIONAL_AUDIT_COUNTS",
  );
}

async function exerciseBackupRestore(temporaryDirectory) {
  await assertEmptyFixtureDatabase();
  const accountA = await createApprovedAccount();
  const accountB = await createApprovedAccount();
  const accountC = await createApprovedAccount();
  const pngA = smallPng(184, 72, 48);
  const pngB = smallPng(36, 112, 204);
  const ocrA = "item deletion image proof";
  const ocrB = "retained backup OCR proof";
  check(
    pngA.byteLength > 0 && pngA.byteLength <= 2_000_000 &&
      pngB.byteLength > 0 && pngB.byteLength <= 2_000_000,
    "BACKUP_FIXTURE_VALID_PNG_BOUNDS",
  );

  const deletedCreate = await saveItem(accountA, {
    url: `https://example.test/backup-deleted-${randomUUID()}`,
    title: "Backup deletion target",
  });
  const retainedCreate = await saveItem(accountB, {
    url: `https://example.test/backup-retained-${randomUUID()}`,
    title: "Backup retained target",
  });
  const accountCreate = await saveItem(accountC, {
    url: `https://example.test/backup-account-${randomUUID()}`,
    title: "Backup account deletion companion",
  });

  let deletedItem = await stableItem(accountA, deletedCreate.item.id);
  let retainedItem = await stableItem(accountB, retainedCreate.item.id);
  let accountItem = await stableItem(accountC, accountCreate.item.id);
  const deletedAttachment = await attachAsset(
    accountA,
    deletedItem,
    pngA,
    ocrA,
  );
  deletedItem = deletedAttachment.item;
  const retainedAttachment = await attachAsset(
    accountB,
    retainedItem,
    pngB,
    ocrB,
  );
  retainedItem = retainedAttachment.item;
  // Classification is terminal before these versions become backup/deletion expectations.
  accountItem = await stableItem(accountC, accountItem.id);

  const usageA = await usage(accountA);
  const usageB = await usage(accountB);
  const usageC = await usage(accountC);
  const sourceDeletedObject = await readObject(
    deletedAttachment.reservation.object_path,
  );
  const sourceRetainedObject = await readObject(
    retainedAttachment.reservation.object_path,
  );
  check(
    usageA.active_item_count === 1 &&
      usageA.used_image_bytes === pngA.byteLength &&
      usageA.reserved_image_bytes === 0 &&
      usageB.active_item_count === 1 &&
      usageB.used_image_bytes === pngB.byteLength &&
      usageB.reserved_image_bytes === 0 &&
      usageC.active_item_count === 1 &&
      usageC.used_image_bytes === 0 &&
      usageC.reserved_image_bytes === 0 &&
      sourceDeletedObject.status === 200 &&
      sameBytes(sourceDeletedObject.body, pngA) &&
      sourceRetainedObject.status === 200 &&
      sameBytes(sourceRetainedObject.body, pngB),
    "BACKUP_FIXTURE_REAL_SOURCE_READY",
  );

  const environment = backupEnvironment();
  const backupPath = join(temporaryDirectory, "library.snapshot.enc");
  const ledgerPath = join(temporaryDirectory, "deletion-ledger.enc");
  const oldLedgerPath = join(temporaryDirectory, "old-deletion-ledger.enc");
  const backup = await createLibraryBackup({
    outputPath: backupPath,
    environment,
  });
  check(
    exactKeys(backup, [
      "status",
      "kind",
      "assets",
      "assetBytes",
      "databaseBytes",
    ]) &&
      backup.status === "ok" &&
      backup.kind === "snapshot" &&
      backup.assets === 2 &&
      backup.assetBytes === pngA.byteLength + pngB.byteLength &&
      Number.isSafeInteger(backup.databaseBytes) &&
      backup.databaseBytes > 0,
    "BACKUP_REAL_SNAPSHOT_SUMMARY",
  );
  await exportDeletionLedger({ outputPath: oldLedgerPath, environment });
  const oldLedger = await decryptAndValidateArtifact({
    artifactPath: oldLedgerPath,
    temporaryDirectory: join(temporaryDirectory, "old-ledger-authenticated"),
    credential: backupCredentialFromEnv(environment),
    expectedKind: "deletion-ledger",
  });
  createdExportIds.add(requireUuid(oldLedger.manifest.exportId));

  await deleteItem(accountA, deletedItem);
  const deletedDetail = await api(accountA, `/items/${deletedItem.id}`);
  check(
    deletedDetail.status === 404 &&
      apiErrorCode(deletedDetail) === "ITEM_NOT_FOUND" &&
      operationalCount(`
        select 1 from private.deletion_ledger
        where owner_id = ${sqlString(accountA.id)}::uuid
          and item_id = ${sqlString(deletedItem.id)}::uuid
          and kind = 'item'
      `) === 1,
    "BACKUP_POST_SNAPSHOT_ITEM_LEDGER_EVENT",
  );

  const challenge = await createAccountChallenge(accountC);
  await trustedAcceptAccountDeletion(accountC, challenge);
  await runMaintenanceUntilAccountDeleted(accountC);
  const removedObject = await readObject(
    deletedAttachment.reservation.object_path,
  );
  const unaffectedBeforeRestore = await api(
    accountB,
    `/items/${retainedItem.id}`,
  );
  const accountABeforeRestore = deletedOwnerCounts(accountA.id);
  const accountCBeforeRestore = deletedOwnerCounts(accountC.id);
  const deletionLedgerSubjects = operationalJson(`
    pg_catalog.jsonb_build_object(
      'a_item', (
        select count(*) from private.deletion_ledger
        where owner_id = ${sqlString(accountA.id)}::uuid
          and item_id = ${sqlString(deletedItem.id)}::uuid
          and kind = 'item'
      ),
      'a_account', (
        select count(*) from private.deletion_ledger
        where owner_id = ${sqlString(accountA.id)}::uuid and kind = 'account'
      ),
      'b_events', (
        select count(*) from private.deletion_ledger
        where owner_id = ${sqlString(accountB.id)}::uuid
      ),
      'c_item', (
        select count(*) from private.deletion_ledger
        where owner_id = ${sqlString(accountC.id)}::uuid
          and item_id = ${sqlString(accountItem.id)}::uuid
          and kind = 'item'
      ),
      'c_account', (
        select count(*) from private.deletion_ledger
        where owner_id = ${sqlString(accountC.id)}::uuid
          and item_id is null
          and kind = 'account'
      )
    )
  `);
  check(
    ![200, 206].includes(removedObject.status) &&
      unaffectedBeforeRestore.status === 200 &&
      unaffectedBeforeRestore.body?.active_asset?.id ===
        retainedAttachment.reservation.asset_id &&
      unaffectedBeforeRestore.body?.active_asset?.ocr_text === ocrB &&
      accountABeforeRestore.auth_users === 1 &&
      accountABeforeRestore.profiles === 1 &&
      accountABeforeRestore.items === 1 &&
      accountABeforeRestore.assets === 0 &&
      accountABeforeRestore.storage_objects === 0 &&
      accountABeforeRestore.complete_jobs === 0 &&
      accountCBeforeRestore.auth_users === 0 &&
      accountCBeforeRestore.profiles === 0 &&
      accountCBeforeRestore.items === 0 &&
      accountCBeforeRestore.assets === 0 &&
      accountCBeforeRestore.storage_objects === 0 &&
      accountCBeforeRestore.complete_jobs === 1 &&
      exactKeys(
        deletionLedgerSubjects,
        ["a_item", "a_account", "b_events", "c_item", "c_account"],
      ) &&
      deletionLedgerSubjects.a_item === 1 &&
      deletionLedgerSubjects.a_account === 0 &&
      deletionLedgerSubjects.b_events === 0 &&
      deletionLedgerSubjects.c_item === 1 &&
      deletionLedgerSubjects.c_account === 1,
    "BACKUP_POST_SNAPSHOT_DELETIONS_SOURCE_ISOLATED",
  );
  const deletionEventCount = operationalCount(`
    select 1 from private.deletion_ledger
    where owner_id in (
      ${sqlString(accountA.id)}::uuid,
      ${sqlString(accountC.id)}::uuid
    )
  `);

  let oldLedgerFailure;
  try {
    await restoreLibrary({
      backupPath,
      ledgerPath: oldLedgerPath,
      environment,
    });
  } catch (error) {
    oldLedgerFailure = error?.code;
  }
  check(
    oldLedgerFailure === "STALE_DELETION_LEDGER_SEQUENCE",
    "BACKUP_OLD_LEDGER_CANNOT_RESURRECT_POST_SNAPSHOT_DELETIONS",
  );

  const ledger = await exportDeletionLedger({
    outputPath: ledgerPath,
    environment,
  });
  check(
    exactKeys(ledger, ["status", "kind", "events"]) &&
      ledger.status === "ok" &&
      ledger.kind === "deletion-ledger" &&
      ledger.events === deletionEventCount,
    "BACKUP_SEPARATE_POST_DELETE_LEDGER_SUMMARY",
  );
  const exportIds = operationalJson(`
    coalesce(
      (select pg_catalog.jsonb_agg(distinct export_id::text order by export_id::text)
       from private.deletion_ledger_exports
       where export_id in (
         select export_id from private.deletion_ledger_export_events
         where owner_id in (
           ${sqlString(accountA.id)}::uuid,
           ${sqlString(accountC.id)}::uuid
         )
       )),
      '[]'::jsonb
    )
  `);
  check(
    Array.isArray(exportIds) && exportIds.length === 1 &&
      UUID_PATTERN.test(exportIds[0]),
    "BACKUP_LEDGER_EXPORT_OWNERSHIP_TRACKED",
  );
  createdExportIds.add(requireUuid(exportIds[0]));

  const auditSql = additionalAuditSql({
    accountA,
    accountB,
    accountC,
    deletedCreate,
    deletedItem,
    retainedItem,
    retainedAsset: retainedAttachment.reservation,
    bytes: pngB.byteLength,
    ocrText: ocrB,
  });
  const restored = await restoreLibrary({
    backupPath,
    ledgerPath,
    environment,
    additionalAudit: async ({ query }) => {
      const text = await query(auditSql);
      validateAdditionalAudit(text);
      return true;
    },
  });
  check(
    exactKeys(restored, [
      "status",
      "network",
      "deletionEvents",
      "restoredAssets",
      "restoredAssetBytes",
      "auditFailures",
    ]) &&
      restored.status === "verified" &&
      restored.network === "none" &&
      restored.deletionEvents === deletionEventCount &&
      restored.restoredAssets === 1 &&
      restored.restoredAssetBytes === pngB.byteLength &&
      restored.auditFailures === 0,
    "BACKUP_ISOLATED_RESTORE_SUMMARY",
  );

  const sourceRetainedAfter = await api(accountB, `/items/${retainedItem.id}`);
  const sourceDeletedAfter = await api(accountA, `/items/${deletedItem.id}`);
  const sourceUsageAfter = await usage(accountB);
  const sourceObjectAfter = await readObject(
    retainedAttachment.reservation.object_path,
  );
  const accountAAfter = deletedOwnerCounts(accountA.id);
  const accountCAfter = deletedOwnerCounts(accountC.id);
  check(
    sourceRetainedAfter.status === 200 &&
      sourceRetainedAfter.body?.active_asset?.id ===
        retainedAttachment.reservation.asset_id &&
      sourceRetainedAfter.body?.active_asset?.ocr_text === ocrB &&
      sourceDeletedAfter.status === 404 &&
      apiErrorCode(sourceDeletedAfter) === "ITEM_NOT_FOUND" &&
      sourceUsageAfter.active_item_count === 1 &&
      sourceUsageAfter.used_image_bytes === pngB.byteLength &&
      sourceUsageAfter.reserved_image_bytes === 0 &&
      sourceObjectAfter.status === 200 &&
      sameBytes(sourceObjectAfter.body, pngB) &&
      accountAAfter.auth_users === 1 &&
      accountAAfter.profiles === 1 &&
      accountAAfter.items === 1 &&
      accountAAfter.assets === 0 &&
      accountAAfter.storage_objects === 0 &&
      accountAAfter.complete_jobs === 0 &&
      accountCAfter.auth_users === 0 &&
      accountCAfter.profiles === 0 &&
      accountCAfter.items === 0 &&
      accountCAfter.assets === 0 &&
      accountCAfter.storage_objects === 0 &&
      accountCAfter.complete_jobs === 1,
    "BACKUP_RESTORE_SOURCE_UNCHANGED",
  );
}

async function main() {
  initializeFixture();
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-backup-integration-"),
  );
  let primaryFailure;
  try {
    await exerciseBackupRestore(temporaryDirectory);
  } catch (error) {
    primaryFailure = error;
  } finally {
    try {
      await cleanupCreatedOwners();
    } catch (error) {
      primaryFailure ??= error;
    }
    try {
      await rm(temporaryDirectory, { recursive: true, force: true });
    } catch (error) {
      primaryFailure ??= Object.assign(
        new Error("BACKUP_FIXTURE_TEMP_CLEANUP_FAILED"),
        {
          code: "BACKUP_FIXTURE_TEMP_CLEANUP_FAILED",
          cause: error,
        },
      );
    }
  }
  if (primaryFailure) throw primaryFailure;
  console.log(`${checks} real local backup and restore checks passed.`);
}

main().catch((error) => {
  const code =
    typeof error?.code === "string" && FIXED_CODE_PATTERN.test(error.code)
      ? error.code
      : "BACKUP_INTEGRATION_FAILED";
  process.stderr.write(`${code}\n`);
  process.exitCode = 1;
});
