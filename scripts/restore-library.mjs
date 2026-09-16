import { randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { setTimeout as pause } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import {
  backupCredentialFromEnv,
  decryptAndValidateArtifact,
  extractValidatedEntry,
  LOCAL_PROJECT_ID,
  validateDatabaseExtensions,
  validateRestorePair,
} from "./backup-format.mjs";
import { localBackendConfig } from "./local-backend-fixture.mjs";

const SOURCE_CONTAINER = `supabase_db_${LOCAL_PROJECT_ID}`;
const RESTORE_LABEL = "link-vault.restore-token";
const RESTORE_DATABASE = "link_vault_restore";
const MAX_CAPTURE_BYTES = 2 * 1024 * 1024;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const LEDGER_FRESHNESS_MICROSECONDS = 5n * 60n * 1_000_000n;
const SNAPSHOT_RETENTION_MICROSECONDS = 30n * 24n * 60n * 60n * 1_000_000n;

function fail(code) {
  const error = new Error(code);
  error.code = code;
  throw error;
}

function executable(name) {
  return process.platform === "win32" ? `${name}.exe` : name;
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index]);
}

function utcMicroseconds(value, code) {
  const match = typeof value === "string"
    ? /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/.exec(value)
    : null;
  if (!match) fail(code);
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (
    !Number.isFinite(milliseconds) ||
    new Date(milliseconds).toISOString().slice(0, 19) !== match[1]
  ) fail(code);
  return BigInt(milliseconds) * 1000n +
    BigInt((match[2] ?? "").padEnd(6, "0"));
}

export function assertRestoreFreshness({ snapshot, ledger, source }) {
  if (
    !exactKeys(source, ["databaseId", "currentHighWater", "databaseTime"]) ||
    typeof source.databaseId !== "string" ||
    !UUID_PATTERN.test(source.databaseId) ||
    typeof source.currentHighWater !== "string" ||
    !/^(?:0|[1-9][0-9]*)$/.test(source.currentHighWater)
  ) fail("SOURCE_FRESHNESS_INVALID");
  if (ledger.databaseId !== source.databaseId) fail("FOREIGN_SOURCE_DATABASE");
  if (
    !Number.isSafeInteger(ledger.throughSequence) ||
    ledger.throughSequence < 0
  ) fail("LEDGER_INVALID");
  if (BigInt(ledger.throughSequence) < BigInt(source.currentHighWater)) {
    fail("STALE_DELETION_LEDGER_SEQUENCE");
  }
  const databaseTime = utcMicroseconds(
    source.databaseTime,
    "SOURCE_FRESHNESS_INVALID",
  );
  const coveredThrough = utcMicroseconds(
    ledger.coveredThrough,
    "LEDGER_INVALID",
  );
  const snapshotTime = utcMicroseconds(
    snapshot.snapshotTime,
    "MANIFEST_INVALID",
  );
  if (coveredThrough > databaseTime) fail("DELETION_LEDGER_FROM_FUTURE");
  if (databaseTime - coveredThrough > LEDGER_FRESHNESS_MICROSECONDS) {
    fail("STALE_DELETION_LEDGER");
  }
  if (snapshotTime > databaseTime) fail("SNAPSHOT_FROM_FUTURE");
  if (databaseTime - snapshotTime > SNAPSHOT_RETENTION_MICROSECONDS) {
    fail("SNAPSHOT_EXPIRED");
  }
  return true;
}

async function runProcess(command, args, {
  input,
  environment = process.env,
  maxBytes = MAX_CAPTURE_BYTES,
  allowFailure = false,
} = {}) {
  return await new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
      env: environment,
      windowsHide: true,
    });
    const stdout = [];
    const stderr = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let killedForLimit = false;
    child.stdout.on("data", (chunk) => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > maxBytes) {
        killedForLimit = true;
        child.kill();
      } else stdout.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      if (stderrBytes > maxBytes) {
        killedForLimit = true;
        child.kill();
      } else stderr.push(chunk);
    });
    child.once(
      "error",
      () =>
        reject(
          Object.assign(new Error("ISOLATED_PROCESS_FAILED"), {
            code: "ISOLATED_PROCESS_FAILED",
          }),
        ),
    );
    child.once("close", (status, signal) => {
      const result = {
        status,
        signal,
        stdout: Buffer.concat(stdout).toString("utf8").trim(),
        stderr: Buffer.concat(stderr).toString("utf8"),
      };
      if (!allowFailure && (status !== 0 || signal || killedForLimit)) {
        const stage = args.includes("pg_restore")
          ? "DUMP_RESTORE"
          : args.includes("psql")
          ? "SQL_QUERY"
          : args[0] === "run"
          ? "CONTAINER_START"
          : "PROCESS";
        const diagnostic = Buffer.concat(stderr).toString("utf8");
        const reason =
          /cannot drop[\s\S]*other objects depend/i.test(diagnostic)
            ? "DEPENDENCIES"
            : /role "[^"]+" does not exist/i.test(diagnostic)
            ? "MISSING_ROLE"
            : /schema "[^"]+" does not exist/i.test(diagnostic)
            ? "MISSING_SCHEMA"
            : /function [^\n]+ does not exist/i.test(diagnostic)
            ? "MISSING_FUNCTION"
            : /type [^\n]+ does not exist/i.test(diagnostic)
            ? "MISSING_TYPE"
            : /must be (?:the )?owner/i.test(diagnostic)
            ? "NOT_OWNER"
            : /extension[^\n]*(?:requires|not available)/i.test(diagnostic)
            ? "EXTENSION_DEPENDENCY"
            : /unrecognized configuration/i.test(diagnostic)
            ? "CONFIGURATION"
            : /syntax error/i.test(diagnostic)
            ? "SQL_SYNTAX"
            : /already exists/i.test(diagnostic)
            ? "ALREADY_EXISTS"
            : /permission denied/i.test(diagnostic)
            ? "PERMISSION"
            : /could not open input file/i.test(diagnostic)
            ? "MISSING_DUMP"
            : "FAILED";
        const vocabulary = new Set([
          "ERROR",
          "COULD",
          "NOT",
          "EXECUTE",
          "QUERY",
          "CANNOT",
          "DROP",
          "CREATE",
          "ALTER",
          "CHANGE",
          "OWNER",
          "SCHEMA",
          "RELATION",
          "TABLE",
          "FUNCTION",
          "TYPE",
          "EXTENSION",
          "MEMBER",
          "ROLE",
          "DATABASE",
          "DOES",
          "EXIST",
          "EXISTS",
          "REQUIRES",
          "DEPEND",
          "OBJECTS",
          "FILE",
          "DIRECTORY",
          "OPEN",
          "INPUT",
          "FORMAT",
          "ARCHIVE",
          "VERSION",
          "UNSUPPORTED",
          "HEADER",
          "SYNTAX",
          "INVALID",
          "PARAMETER",
          "CONFIGURATION",
          "PERMISSION",
          "DENIED",
          "CONNECTION",
          "SERVER",
          "CONSTRAINT",
          "VIOLATES",
          "NULL",
          "KEY",
          "DUPLICATE",
          "VALUE",
          "DATA",
          "IS",
          "RESERVED",
          "SYSTEM",
          "SELECT",
          "SET",
          "COMMENT",
          "ON",
          "GRANT",
          "REVOKE",
          "POLICY",
        ]);
        const failureLine = diagnostic.split(/\r?\n/).find((line) =>
          /error:/i.test(line)
        ) ?? "";
        const safeWords = failureLine.replace(/"[^"]*"|'[^']*'/g, "")
          .toUpperCase().match(/[A-Z]+/g)?.filter((word) =>
            vocabulary.has(word)
          ).slice(0, 16).join("_");
        const restoreCommand = /Command was:\s*([A-Z]+)(?:\s+([A-Z]+))?/.exec(
          diagnostic,
        );
        const commandKind = restoreCommand?.slice(1).filter((word) =>
          vocabulary.has(word)
        ).join("_");
        const code = `ISOLATED_${stage}_${
          reason === "FAILED" && safeWords ? safeWords : reason
        }${commandKind ? `_${commandKind}` : ""}`;
        reject(Object.assign(new Error(code), { code }));
      } else {
        resolvePromise(result);
      }
    });
    if (input !== undefined) child.stdin.end(input);
  });
}

async function verifiedSourceImage() {
  const config = localBackendConfig();
  let databaseUrl;
  try {
    databaseUrl = new URL(config.DB_URL);
  } catch {
    fail("LOCAL_DATABASE_CONFIG_INVALID");
  }
  if (
    databaseUrl.protocol !== "postgresql:" ||
    databaseUrl.hostname !== "127.0.0.1" ||
    databaseUrl.port !== "18022" || databaseUrl.pathname !== "/postgres"
  ) fail("LOCAL_DATABASE_CONFIG_INVALID");
  const docker = executable("docker");
  const running = await runProcess(docker, [
    "inspect",
    "--format",
    "{{.State.Running}}",
    SOURCE_CONTAINER,
  ]);
  if (running.stdout !== "true") fail("LOCAL_DATABASE_NOT_RUNNING");
  const name = await runProcess(docker, [
    "inspect",
    "--format",
    "{{.Name}}",
    SOURCE_CONTAINER,
  ]);
  if (name.stdout !== `/${SOURCE_CONTAINER}`) {
    fail("LOCAL_DATABASE_CONTAINER_INVALID");
  }
  const port = await runProcess(docker, ["port", SOURCE_CONTAINER, "5432/tcp"]);
  if (
    !port.stdout.split(/\r?\n/).some((line) =>
      /^(127\.0\.0\.1|0\.0\.0\.0):18022$/.test(line)
    )
  ) {
    fail("LOCAL_DATABASE_PORT_INVALID");
  }
  const image = await runProcess(docker, [
    "inspect",
    "--format",
    "{{.Image}}",
    SOURCE_CONTAINER,
  ]);
  if (!/^sha256:[0-9a-f]{64}$/.test(image.stdout)) {
    fail("LOCAL_DATABASE_IMAGE_INVALID");
  }
  const version = await runProcess(docker, [
    "exec",
    SOURCE_CONTAINER,
    "postgres",
    "--version",
  ]);
  if (!/^postgres \(PostgreSQL\) 17(?:\.|$)/.test(version.stdout)) {
    fail("LOCAL_DATABASE_VERSION_INVALID");
  }
  return image.stdout;
}

async function readSourceFreshness() {
  const sql = String.raw`
\set ON_ERROR_STOP on
begin isolation level repeatable read;
lock table private.deletion_ledger in share mode;
select pg_catalog.json_build_object(
  'databaseId', identity.database_id,
  'currentHighWater', greatest(
    coalesce((select max(ledger.sequence) from private.deletion_ledger as ledger), 0),
    coalesce((
      select max(ledger_export.through_sequence)
      from private.deletion_ledger_exports as ledger_export
    ), 0)
  )::text,
  'databaseTime', pg_catalog.to_char(
    pg_catalog.clock_timestamp() at time zone 'UTC',
    'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
  )
)::text
from private.deletion_ledger_identity as identity
where identity.singleton;
commit;`;
  let result;
  try {
    result = await runProcess(executable("docker"), [
      "exec",
      "-i",
      SOURCE_CONTAINER,
      "psql",
      "-XAtq",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "postgres",
      "-d",
      "postgres",
    ], { input: sql });
  } catch {
    fail("SOURCE_FRESHNESS_UNAVAILABLE");
  }
  let source;
  try {
    source = JSON.parse(result.stdout);
  } catch {
    fail("SOURCE_FRESHNESS_INVALID");
  }
  return source;
}

function sqlUuid(value) {
  return `'${value}'::uuid`;
}

function deletionValues(events) {
  if (events.length === 0) {
    return "select null::uuid, null::uuid, null::text, null::timestamptz where false";
  }
  return `values\n${
    events.map((event) => {
      return `(${sqlUuid(event.ownerId)}, ${
        event.itemId === null ? "null::uuid" : sqlUuid(event.itemId)
      }, '${event.kind}', '${event.requestedAt}'::timestamptz)`;
    }).join(",\n")
  }`;
}

function createDeletionTableSql(events) {
  return `
create temporary table restore_deletions (
  owner_id uuid not null,
  item_id uuid,
  kind text not null check (kind in ('item', 'account')),
  requested_at timestamptz not null,
  check ((kind = 'item' and item_id is not null) or (kind = 'account' and item_id is null))
) on commit drop;
insert into restore_deletions (owner_id, item_id, kind, requested_at)
${deletionValues(events)};
`;
}

function applyDeletionSql(events) {
  return `
\\set ON_ERROR_STOP on
begin;
-- Only the verified isolated restore database; matching files are never copied.
set local storage.allow_delete_query = 'true';
${createDeletionTableSql(events)}

delete from storage.objects as object
using restore_deletions as deletion
where object.bucket_id = 'library-images'
  and (
    (deletion.kind = 'account' and object.name like deletion.owner_id::text || '/%')
    or (deletion.kind = 'item' and object.name like deletion.owner_id::text || '/' || deletion.item_id::text || '/%')
  );

delete from private.asset_cleanup_receipts as receipt
using restore_deletions as deletion
where receipt.owner_id = deletion.owner_id
  and (deletion.kind = 'account' or receipt.item_id = deletion.item_id);

delete from public.assets as asset
using restore_deletions as deletion
where asset.owner_id = deletion.owner_id
  and (deletion.kind = 'account' or asset.item_id = deletion.item_id);

insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
select deletion.owner_id, deletion.item_id, deletion.requested_at
from restore_deletions as deletion
where deletion.kind = 'item'
on conflict (owner_id, item_id) do update
set deleted_at = least(
  private.item_deletion_tombstones.deleted_at,
  excluded.deleted_at
);

update public.items as item
set original_url = null,
    normalized_url = null,
    url_hash = null,
    source = null,
    display_fallback = null,
    shared_text = null,
    user_title = null,
    fetched_title = null,
    description = null,
    body_text = null,
    note = null,
    extraction_meta = '{}'::jsonb,
    metadata_state = null,
    version = case when item.deleted_at is null then item.version + 1 else item.version end,
    updated_at = greatest(item.updated_at, deletion.requested_at),
    deleted_at = least(coalesce(item.deleted_at, deletion.requested_at), deletion.requested_at)
from restore_deletions as deletion
where deletion.kind = 'item'
  and item.owner_id = deletion.owner_id
  and item.id = deletion.item_id;

update public.processing_jobs as job
set state = 'cancelled',
    lease_until = null,
    lease_token = null,
    last_error_code = null,
    updated_at = greatest(job.updated_at, deletion.requested_at)
from restore_deletions as deletion
where deletion.kind = 'item'
  and job.owner_id = deletion.owner_id
  and job.item_id = deletion.item_id
  and job.state in ('queued', 'running', 'retry');

delete from public.item_categories as selected
using restore_deletions as deletion
where deletion.kind = 'item'
  and selected.owner_id = deletion.owner_id
  and selected.item_id = deletion.item_id;

delete from public.item_category_controls as controls
using restore_deletions as deletion
where deletion.kind = 'item'
  and controls.owner_id = deletion.owner_id
  and controls.item_id = deletion.item_id;

delete from public.item_classification as classification
using restore_deletions as deletion
where deletion.kind = 'item'
  and classification.owner_id = deletion.owner_id
  and classification.item_id = deletion.item_id;

delete from public.item_search as search_record
using restore_deletions as deletion
where deletion.kind = 'item'
  and search_record.owner_id = deletion.owner_id
  and search_record.item_id = deletion.item_id;

delete from public.account_deletion_jobs as job
using restore_deletions as deletion
where deletion.kind = 'account' and job.owner_id = deletion.owner_id;

delete from private.category_normalization_authorizations as authorization_record
using restore_deletions as deletion
where deletion.kind = 'account' and authorization_record.owner_id = deletion.owner_id;

delete from private.auth_challenges as challenge
using restore_deletions as deletion
where deletion.kind = 'account' and challenge.owner_id = deletion.owner_id;

delete from public.beta_members as member
using restore_deletions as deletion
where deletion.kind = 'account' and member.owner_id = deletion.owner_id;

delete from public.profiles as profile
using restore_deletions as deletion
where deletion.kind = 'account' and profile.id = deletion.owner_id;

delete from auth.users as account
using restore_deletions as deletion
where deletion.kind = 'account' and account.id = deletion.owner_id;

delete from storage.objects as object
where object.bucket_id = 'library-images'
  and not exists (
    select 1 from public.assets as asset
    where asset.state = 'active' and asset.deleted_at is null
      and asset.object_path = object.name
  );

update public.library_usage as usage
set active_item_count = counts.active_items,
    used_image_bytes = counts.used_bytes,
    reserved_image_bytes = counts.reserved_bytes
from (
  select profile.id as owner_id,
    (select pg_catalog.count(*)::integer from public.items as item
      where item.owner_id = profile.id and item.deleted_at is null) as active_items,
    (select coalesce(pg_catalog.sum(asset.actual_bytes), 0)::bigint from public.assets as asset
      where asset.owner_id = profile.id and asset.actual_bytes is not null) as used_bytes,
    (select coalesce(pg_catalog.sum(asset.reserved_bytes), 0)::bigint from public.assets as asset
      where asset.owner_id = profile.id and asset.actual_bytes is null) as reserved_bytes
  from public.profiles as profile
) as counts
where usage.owner_id = counts.owner_id;
commit;
`;
}

function expectedAssetValues(assets) {
  if (assets.length === 0) {
    return "select null::uuid, null::uuid, null::uuid, null::text, null::bigint where false";
  }
  return `values\n${
    assets.map((asset) => {
      return `(${sqlUuid(asset.ownerId)}, ${sqlUuid(asset.itemId)}, ${
        sqlUuid(asset.assetId)
      }, '${asset.storagePath}', ${asset.size}::bigint)`;
    }).join(",\n")
  }`;
}

function expectedFunctionsValues(counts) {
  return Object.entries(counts)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([schema, count]) => `('${schema}', ${count}::bigint)`)
    .join(",\n");
}

function expectedRlsValues(tables) {
  if (tables.length === 0) return "select null::text where false";
  return `values\n${tables.map((table) => `('${table}')`).join(",\n")}`;
}

function auditSql(snapshot, ledger, assets) {
  return `
\\set ON_ERROR_STOP on
-- Only temporary expectation tables are written; the entire audit rolls back.
begin;
${createDeletionTableSql(ledger.events)}
create temporary table expected_assets (
  owner_id uuid not null,
  item_id uuid not null,
  asset_id uuid not null,
  object_path text not null,
  actual_bytes bigint not null
) on commit drop;
insert into expected_assets (owner_id, item_id, asset_id, object_path, actual_bytes)
${expectedAssetValues(assets)};
create temporary table expected_functions (schema_name text not null, function_count bigint not null) on commit drop;
insert into expected_functions (schema_name, function_count) values
${expectedFunctionsValues(snapshot.schemaFunctionCounts)};
create temporary table expected_rls (qualified_name text) on commit drop;
insert into expected_rls (qualified_name)
${expectedRlsValues(snapshot.rlsTables)};

select pg_catalog.json_build_object(
  'deleted_rows', (
    select pg_catalog.count(*) from restore_deletions as deletion
    where (deletion.kind = 'item' and (
      not exists (
        select 1 from private.item_deletion_tombstones as tombstone
        where tombstone.owner_id = deletion.owner_id
          and tombstone.item_id = deletion.item_id
          and tombstone.deleted_at <= deletion.requested_at
      )
      or (
        not exists (
          select 1 from restore_deletions as account_deletion
          where account_deletion.kind = 'account'
            and account_deletion.owner_id = deletion.owner_id
        )
        and not exists (
          select 1 from public.items as item
          where item.owner_id = deletion.owner_id and item.id = deletion.item_id
        )
      )
      or exists (
        select 1 from public.items as item
        where item.owner_id = deletion.owner_id
          and item.id = deletion.item_id
          and (
            item.deleted_at is null
            or item.original_url is not null
            or item.normalized_url is not null
            or item.url_hash is not null
            or item.source is not null
            or item.display_fallback is not null
            or item.shared_text is not null
            or item.user_title is not null
            or item.fetched_title is not null
            or item.description is not null
            or item.body_text is not null
            or item.note is not null
            or item.extraction_meta <> '{}'::jsonb
            or item.metadata_state is not null
          )
      )
      or exists (
        select 1 from public.processing_jobs as job
        where job.owner_id = deletion.owner_id
          and job.item_id = deletion.item_id
          and (
            job.state in ('queued', 'running', 'retry')
            or job.lease_until is not null
            or job.lease_token is not null
          )
      )
      or exists (
        select 1 from public.item_categories as selected
        where selected.owner_id = deletion.owner_id and selected.item_id = deletion.item_id
      )
      or exists (
        select 1 from public.item_category_controls as controls
        where controls.owner_id = deletion.owner_id and controls.item_id = deletion.item_id
      )
      or exists (
        select 1 from public.item_classification as classification
        where classification.owner_id = deletion.owner_id
          and classification.item_id = deletion.item_id
      )
      or exists (
        select 1 from public.item_search as search_record
        where search_record.owner_id = deletion.owner_id
          and search_record.item_id = deletion.item_id
      )
      or exists (select 1 from public.assets as asset where asset.owner_id = deletion.owner_id and asset.item_id = deletion.item_id)
      or exists (select 1 from storage.objects as object where object.bucket_id = 'library-images'
        and object.name like deletion.owner_id::text || '/' || deletion.item_id::text || '/%')
    )) or (deletion.kind = 'account' and (
      exists (select 1 from public.profiles as profile where profile.id = deletion.owner_id)
      or exists (select 1 from public.beta_members as member where member.owner_id = deletion.owner_id)
      or exists (select 1 from public.library_usage as usage where usage.owner_id = deletion.owner_id)
      or exists (select 1 from public.categories as category where category.owner_id = deletion.owner_id)
      or exists (select 1 from public.items as item where item.owner_id = deletion.owner_id)
      or exists (select 1 from public.assets as asset where asset.owner_id = deletion.owner_id)
      or exists (select 1 from public.account_deletion_jobs as job where job.owner_id = deletion.owner_id)
      or exists (select 1 from private.auth_challenges as challenge where challenge.owner_id = deletion.owner_id)
      or exists (select 1 from private.category_normalization_authorizations as authorization_record
        where authorization_record.owner_id = deletion.owner_id)
      or exists (select 1 from auth.users as account where account.id = deletion.owner_id)
      or exists (select 1 from storage.objects as object where object.bucket_id = 'library-images'
        and object.name like deletion.owner_id::text || '/%')
    ))
  ),
  'asset_mismatches', (
    (select pg_catalog.count(*) from expected_assets as expected
      left join public.assets as asset on asset.owner_id = expected.owner_id and asset.item_id = expected.item_id
        and asset.id = expected.asset_id and asset.object_path = expected.object_path
        and asset.actual_bytes = expected.actual_bytes and asset.state = 'active' and asset.deleted_at is null
      where asset.id is null)
    +
    (select pg_catalog.count(*) from public.assets as asset
      left join expected_assets as expected on expected.owner_id = asset.owner_id and expected.item_id = asset.item_id
        and expected.asset_id = asset.id and expected.object_path = asset.object_path
        and expected.actual_bytes = asset.actual_bytes
      where asset.state = 'active' and asset.deleted_at is null and expected.asset_id is null)
  ),
  'storage_mismatches', (
    (select pg_catalog.count(*) from expected_assets as expected
      left join storage.objects as object on object.bucket_id = 'library-images'
        and object.name = expected.object_path
      left join public.assets as asset on asset.id = expected.asset_id
      where object.id is null or asset.storage_object_id is distinct from object.id)
    +
    (select pg_catalog.count(*) from storage.objects as object
      left join expected_assets as expected on expected.object_path = object.name
      where object.bucket_id = 'library-images' and expected.asset_id is null)
  ),
  'quota_mismatches', (
    select pg_catalog.count(*)
    from public.profiles as profile
    left join public.library_usage as usage on usage.owner_id = profile.id
    where usage.owner_id is null
      or usage.active_item_count <> (select pg_catalog.count(*)::integer from public.items as item
        where item.owner_id = profile.id and item.deleted_at is null)
      or usage.used_image_bytes <> (select coalesce(pg_catalog.sum(asset.actual_bytes), 0)::bigint from public.assets as asset
        where asset.owner_id = profile.id and asset.actual_bytes is not null)
      or usage.reserved_image_bytes <> (select coalesce(pg_catalog.sum(asset.reserved_bytes), 0)::bigint from public.assets as asset
        where asset.owner_id = profile.id and asset.actual_bytes is null)
  ),
  'bucket_mismatches', (
    select (case when pg_catalog.count(*) filter (where bucket.id = 'library-images' and not bucket.public) = 1 then 0 else 1 end)
      + pg_catalog.count(*) filter (where bucket.public)
    from storage.buckets as bucket
  ),
  'function_mismatches', (
    select pg_catalog.count(*) from expected_functions as expected
    where expected.function_count <> (
      select pg_catalog.count(*) from pg_catalog.pg_proc as proc
      join pg_catalog.pg_namespace as namespace on namespace.oid = proc.pronamespace
      where namespace.nspname = expected.schema_name
    )
  ),
  'rls_mismatches', (
    select pg_catalog.count(*) from expected_rls as expected
    where not exists (
      select 1 from pg_catalog.pg_class as class
      join pg_catalog.pg_namespace as namespace on namespace.oid = class.relnamespace
      where namespace.nspname || '.' || class.relname = expected.qualified_name
        and class.relkind in ('r', 'p') and class.relrowsecurity
    )
  )
)::text;
rollback;
`;
}

function isSuppressed(asset, events) {
  return events.some((event) => {
    return event.ownerId === asset.ownerId &&
      (event.kind === "account" || event.itemId === asset.itemId);
  });
}

async function queryContainer(containerName, sql) {
  const result = await runProcess(executable("docker"), [
    "exec",
    "-i",
    containerName,
    "psql",
    "-XAtq",
    "-U",
    "postgres",
    "-d",
    RESTORE_DATABASE,
  ], { input: sql });
  return result.stdout;
}

async function startIsolatedContainer(
  { image, containerName, token, dataDirectory, restoreDirectory },
) {
  const password = randomBytes(32).toString("base64url");
  await runProcess(executable("docker"), [
    "run",
    "--detach",
    "--name",
    containerName,
    "--label",
    `${RESTORE_LABEL}=${token}`,
    "--network",
    "none",
    "--env",
    "POSTGRES_PASSWORD",
    "--mount",
    `type=bind,source=${dataDirectory},target=/var/lib/postgresql/data`,
    "--mount",
    `type=bind,source=${restoreDirectory},target=/restore,readonly`,
    image,
  ], { environment: { ...process.env, POSTGRES_PASSWORD: password } });
  const isolation = await runProcess(executable("docker"), [
    "inspect",
    "--format",
    "{{.HostConfig.NetworkMode}}|{{json .HostConfig.PortBindings}}",
    containerName,
  ]);
  if (!/^none[|](?:null|[{][}])$/.test(isolation.stdout)) {
    fail("RESTORE_CONTAINER_NOT_ISOLATED");
  }
  await waitForIsolatedDatabase(containerName);
}

async function waitForIsolatedDatabase(containerName) {
  const sourceProcesses = await runProcess(executable("docker"), [
    "exec",
    SOURCE_CONTAINER,
    "ps",
    "-o",
    "pid,comm",
  ]);
  const postmasterName = /^\s*1\s+([A-Za-z0-9_.-]+)\s*$/m.exec(
    sourceProcesses.stdout,
  )?.[1];
  if (!postmasterName?.includes("postgres")) fail("SOURCE_POSTMASTER_INVALID");
  for (let attempt = 0; attempt < 60; attempt++) {
    // The entrypoint's temporary initialization server also accepts connections.
    // Wait for its final exec before replacing the isolated bootstrap database.
    const entrypoint = await runProcess(executable("docker"), [
      "exec",
      containerName,
      "ps",
      "-o",
      "pid,comm",
    ], { allowFailure: true });
    const ready = await runProcess(executable("docker"), [
      "exec",
      containerName,
      "pg_isready",
      "-q",
      "-U",
      "postgres",
      "-d",
      "postgres",
    ], { allowFailure: true });
    const isolatedPostmaster = /^\s*1\s+([A-Za-z0-9_.-]+)\s*$/m.exec(
      entrypoint.stdout,
    )?.[1];
    if (
      entrypoint.status === 0 && isolatedPostmaster === postmasterName &&
      ready.status === 0
    ) return;
    await pause(500);
  }
  fail("ISOLATED_DATABASE_START_TIMEOUT");
}

async function cleanupOwnedContainer(containerName, token, execute) {
  const inspected = await execute(executable("docker"), [
    "inspect",
    "--format",
    `{{index .Config.Labels "${RESTORE_LABEL}"}}`,
    containerName,
  ], { allowFailure: true });
  if (inspected.status !== 0) fail("RESTORE_CONTAINER_CLEANUP_UNCONFIRMED");
  if (inspected.stdout !== token) fail("RESTORE_CONTAINER_OWNERSHIP_LOST");
  const removed = await execute(executable("docker"), [
    "rm",
    "--force",
    containerName,
  ], { allowFailure: true });
  if (removed.status !== 0) fail("RESTORE_CONTAINER_CLEANUP_FAILED");
}

export async function cleanupRestoreResources(
  { containerStarted, containerName, token, temporaryDirectory },
  { execute = runProcess, remove = rm } = {},
) {
  if (containerStarted) {
    await cleanupOwnedContainer(containerName, token, execute);
  }
  // Never remove bind-mounted files while container removal is indeterminate.
  await remove(temporaryDirectory, { recursive: true, force: true });
}

async function verifyOwnedRestoreContainer(containerName, token) {
  const inspected = await runProcess(executable("docker"), [
    "inspect",
    "--format",
    `{{.Name}}|{{index .Config.Labels "${RESTORE_LABEL}"}}|{{.HostConfig.NetworkMode}}|{{json .HostConfig.PortBindings}}`,
    containerName,
  ]);
  const expectedPrefix = `/${containerName}|${token}|none|`;
  if (
    !inspected.stdout.startsWith(expectedPrefix) ||
    !["null", "{}"].includes(inspected.stdout.slice(expectedPrefix.length))
  ) {
    fail("RESTORE_CONTAINER_OWNERSHIP_LOST");
  }
}

async function runRestoreAdminSql(containerName, database, sql) {
  const result = await runProcess(executable("docker"), [
    "exec",
    "-i",
    containerName,
    "psql",
    "-XAtq",
    "-v",
    "ON_ERROR_STOP=1",
    "-U",
    "supabase_admin",
    "-d",
    database,
  ], { input: sql });
  return result.stdout;
}

function quoteIdentifier(value) {
  return `"${value}"`;
}

function extensionInitializationSql(extensions) {
  validateDatabaseExtensions(extensions);
  const schemas = [...new Set(extensions.map((extension) => extension.schema))]
    .filter((schema) => !["pg_catalog", "public"].includes(schema))
    .sort();
  return [
    "\\set ON_ERROR_STOP on",
    ...schemas.map((schema) =>
      `create schema if not exists ${quoteIdentifier(schema)};`
    ),
    ...extensions.map((extension) => {
      return `create extension if not exists ${
        quoteIdentifier(extension.name)
      } ` +
        `with schema ${quoteIdentifier(extension.schema)} ` +
        `version '${extension.version}' cascade;`;
    }),
  ].join("\n");
}

async function initializeRestoreDatabase(containerName, token, extensions) {
  validateDatabaseExtensions(extensions);
  // Never replace the bootstrap DB while its extension workers are connected.
  // Only this run's verified isolated container may receive the fresh database.
  await verifyOwnedRestoreContainer(containerName, token);
  await runRestoreAdminSql(
    containerName,
    "template1",
    `create database ${RESTORE_DATABASE} with template template0 owner supabase_admin;`,
  );
  await runRestoreAdminSql(
    containerName,
    "template1",
    `alter system set cron.database_name = '${RESTORE_DATABASE}';`,
  );
  await verifyOwnedRestoreContainer(containerName, token);
  await runProcess(executable("docker"), ["restart", containerName]);
  await waitForIsolatedDatabase(containerName);
  await runRestoreAdminSql(
    containerName,
    RESTORE_DATABASE,
    extensionInitializationSql(extensions),
  );
  const inventoryText = await runRestoreAdminSql(
    containerName,
    RESTORE_DATABASE,
    String.raw`
select coalesce(
  pg_catalog.json_agg(
    pg_catalog.json_build_object(
      'name', installed_extension.extname,
      'version', installed_extension.extversion,
      'schema', namespace.nspname
    )
    order by installed_extension.extname
  ),
  '[]'::json
)::text
from pg_catalog.pg_extension as installed_extension
join pg_catalog.pg_namespace as namespace
  on namespace.oid = installed_extension.extnamespace;`,
  );
  let installed;
  try {
    installed = JSON.parse(inventoryText);
  } catch {
    fail("RESTORE_EXTENSION_INVENTORY_INVALID");
  }
  validateDatabaseExtensions(installed);
  if (JSON.stringify(installed) !== JSON.stringify(extensions)) {
    fail("RESTORE_EXTENSION_INVENTORY_MISMATCH");
  }
}

async function restoreDump(containerName) {
  await runProcess(executable("docker"), [
    "exec",
    containerName,
    "pg_restore",
    "--exit-on-error",
    "--clean",
    "--if-exists",
    "-U",
    "supabase_admin",
    "-d",
    RESTORE_DATABASE,
    "/restore/database.dump",
  ], { maxBytes: MAX_CAPTURE_BYTES });
}

function validateAuditReceipt(value) {
  const keys = [
    "deleted_rows",
    "asset_mismatches",
    "storage_mismatches",
    "quota_mismatches",
    "bucket_mismatches",
    "function_mismatches",
    "rls_mismatches",
  ];
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail("RESTORE_AUDIT_INVALID");
  }
  if (Object.keys(value).sort().join(",") !== keys.sort().join(",")) {
    fail("RESTORE_AUDIT_INVALID");
  }
  for (const key of keys) {
    if (!Number.isSafeInteger(value[key]) || value[key] !== 0) {
      fail("RESTORE_AUDIT_FAILED");
    }
  }
}

async function openRestoreArtifacts(
  { backupPath, ledgerPath, environment, temporaryDirectory },
) {
  if (!backupPath || !ledgerPath) fail("RESTORE_INPUT_REQUIRED");
  if (resolve(backupPath) === resolve(ledgerPath)) {
    fail("RESTORE_INPUTS_MUST_BE_SEPARATE");
  }
  const credential = backupCredentialFromEnv(environment);
  const ownedTemporary = temporaryDirectory;
  try {
    const snapshot = await decryptAndValidateArtifact({
      artifactPath: backupPath,
      temporaryDirectory: join(ownedTemporary, "snapshot-authenticated"),
      credential,
      expectedKind: "snapshot",
    });
    let ledger;
    try {
      ledger = await decryptAndValidateArtifact({
        artifactPath: ledgerPath,
        temporaryDirectory: join(ownedTemporary, "ledger-authenticated"),
        credential,
        expectedKind: "deletion-ledger",
      });
    } catch (error) {
      await rm(snapshot.packagePath, { force: true });
      throw error;
    }
    validateRestorePair(snapshot.manifest, ledger.manifest);
    return { snapshot, ledger };
  } catch (error) {
    throw error;
  }
}

export async function validateRestoreArtifacts(
  { backupPath, ledgerPath, environment = process.env } = {},
) {
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-restore-validate-"),
  );
  try {
    const validated = await openRestoreArtifacts({
      backupPath,
      ledgerPath,
      environment,
      temporaryDirectory,
    });
    return {
      status: "format-and-crypto-validated",
      snapshot: validated.snapshot.manifest,
      ledger: validated.ledger.manifest,
    };
  } finally {
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
}

export async function restoreLibrary({
  backupPath,
  ledgerPath,
  environment = process.env,
  additionalAudit,
} = {}) {
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-restore-"),
  );
  const containerName = `link-vault-restore-${randomUUID()}`;
  const token = randomUUID();
  let containerStarted = false;
  let validation;
  try {
    validation = await openRestoreArtifacts({
      backupPath,
      ledgerPath,
      environment,
      temporaryDirectory,
    });
    const { snapshot, ledger } = validation;
    const image = await verifiedSourceImage();
    assertRestoreFreshness({
      snapshot: snapshot.manifest,
      ledger: ledger.manifest,
      source: await readSourceFreshness(),
    });
    const restoreDirectory = join(temporaryDirectory, "restore-input");
    const dataDirectory = join(temporaryDirectory, "postgres-data");
    const storageDirectory = join(temporaryDirectory, "storage");
    await mkdir(restoreDirectory, { recursive: true });
    await mkdir(dataDirectory, { recursive: true });
    await mkdir(storageDirectory, { recursive: true });
    await extractValidatedEntry({
      packagePath: snapshot.packagePath,
      entry: snapshot.entries.get("database.dump"),
      destinationRoot: restoreDirectory,
    });
    containerStarted = true;
    await startIsolatedContainer({
      image,
      containerName,
      token,
      dataDirectory,
      restoreDirectory,
    });
    await initializeRestoreDatabase(
      containerName,
      token,
      snapshot.manifest.database.extensions,
    );
    await restoreDump(containerName);
    await queryContainer(
      containerName,
      applyDeletionSql(ledger.manifest.events),
    );
    const retainedAssets = snapshot.manifest.assets.filter((asset) =>
      !isSuppressed(asset, ledger.manifest.events)
    );
    for (const asset of retainedAssets) {
      await extractValidatedEntry({
        packagePath: snapshot.packagePath,
        entry: snapshot.entries.get(asset.path),
        destinationRoot: storageDirectory,
      });
    }
    const auditText = await queryContainer(
      containerName,
      auditSql(snapshot.manifest, ledger.manifest, retainedAssets),
    );
    let audit;
    try {
      audit = JSON.parse(auditText);
    } catch {
      fail("RESTORE_AUDIT_INVALID");
    }
    validateAuditReceipt(audit);
    if (additionalAudit !== undefined) {
      if (typeof additionalAudit !== "function") {
        fail("ADDITIONAL_AUDIT_INVALID");
      }
      const accepted = await additionalAudit({
        query: async (sql) => {
          if (
            typeof sql !== "string" ||
            Buffer.byteLength(sql, "utf8") > MAX_CAPTURE_BYTES
          ) fail("ADDITIONAL_AUDIT_INVALID");
          return await queryContainer(containerName, sql);
        },
      });
      if (accepted !== true) fail("ADDITIONAL_AUDIT_FAILED");
    }
    assertRestoreFreshness({
      snapshot: snapshot.manifest,
      ledger: ledger.manifest,
      source: await readSourceFreshness(),
    });
    const assetBytes = retainedAssets.reduce(
      (sum, asset) => sum + asset.size,
      0,
    );
    return {
      status: "verified",
      network: "none",
      deletionEvents: ledger.manifest.events.length,
      restoredAssets: retainedAssets.length,
      restoredAssetBytes: assetBytes,
      auditFailures: 0,
    };
  } catch (error) {
    if (containerStarted) {
      try {
        const logs = await runProcess(executable("docker"), [
          "logs",
          "--tail",
          "80",
          containerName,
        ], { allowFailure: true });
        const signal = /terminated by signal (6|7|9|11)\b/.exec(
          logs.stdout + logs.stderr,
        )?.[1];
        if (signal && typeof error.code === "string") {
          error.code = `${error.code}_DATABASE_SIGNAL_${signal}`;
        }
      } catch {
        // Preserve the primary fixed failure code; never publish database logs.
      }
    }
    throw error;
  } finally {
    await cleanupRestoreResources({
      containerStarted,
      containerName,
      token,
      temporaryDirectory,
    });
  }
}

function parseCli(arguments_) {
  if (
    arguments_.length !== 4 || arguments_[0] !== "--backup" || !arguments_[1] ||
    arguments_[2] !== "--ledger" || !arguments_[3]
  ) fail("USAGE");
  return { backupPath: arguments_[1], ledgerPath: arguments_[3] };
}

async function main() {
  const options = parseCli(process.argv.slice(2));
  const receipt = await restoreLibrary(options);
  process.stdout.write(`${JSON.stringify(receipt)}\n`);
}

if (
  resolve(process.argv[1] ?? "") === resolve(fileURLToPath(import.meta.url))
) {
  main().catch((error) => {
    const code =
      typeof error?.code === "string" && /^[A-Z0-9_]+$/.test(error.code)
        ? error.code
        : "RESTORE_FAILED";
    process.stderr.write(`${code}\n`);
    process.exitCode = 1;
  });
}
