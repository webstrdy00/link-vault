import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdtemp, realpath, rm } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import {
  assertOutsideWorktree,
  backupCredentialFromEnv,
  BackupPackageWriter,
  decryptAndValidateArtifact,
  encryptPackageFile,
  LOCAL_PROJECT_ID,
  snapshotSchemas,
  validateDatabaseExtensions,
  validateLedgerManifest,
  validatePackageFile,
  validateSnapshotManifest,
} from "./backup-format.mjs";
import { localBackendConfig } from "./local-backend-fixture.mjs";

const SOURCE_CONTAINER = `supabase_db_${LOCAL_PROJECT_ID}`;
const STORAGE_BUCKET = "library-images";
const MAX_ASSET_BYTES = 2_000_000;
const MAX_OWNER_BYTES = 20_000_000;
const PAGE_SIZE = 1000;
const MAX_CAPTURE_BYTES = 2 * 1024 * 1024;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function fail(code) {
  const error = new Error(code);
  error.code = code;
  throw error;
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index]);
}

function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isoUtc(value, code) {
  const match = typeof value === "string"
    ? /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(?:Z|[+]00(?::00)?)$/
      .exec(value)
    : null;
  if (!match) fail(code);
  const time = Date.parse(`${match[1]}Z`);
  if (
    !Number.isFinite(time) ||
    new Date(time).toISOString().slice(0, 19) !== match[1]
  ) fail(code);
  return `${match[1]}.${(match[2] ?? "").padEnd(6, "0")}Z`;
}

function executable(name) {
  return process.platform === "win32" ? `${name}.exe` : name;
}

async function safeOutputPath(outputPath) {
  const destination = assertOutsideWorktree(outputPath);
  const canonicalParent = await realpath(dirname(destination)).catch(() =>
    fail("OUTPUT_DIRECTORY_UNAVAILABLE")
  );
  return assertOutsideWorktree(join(canonicalParent, basename(destination)));
}

async function runCaptured(
  command,
  args,
  { maxBytes = MAX_CAPTURE_BYTES } = {},
) {
  return await new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    const stdout = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    child.stdout.on("data", (chunk) => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > maxBytes) child.kill();
      else stdout.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      if (stderrBytes > maxBytes) child.kill();
    });
    child.once(
      "error",
      () =>
        reject(
          Object.assign(new Error("LOCAL_PROCESS_FAILED"), {
            code: "LOCAL_PROCESS_FAILED",
          }),
        ),
    );
    child.once("close", (status, signal) => {
      if (
        status !== 0 || signal || stdoutBytes > maxBytes ||
        stderrBytes > maxBytes
      ) {
        reject(
          Object.assign(new Error("LOCAL_PROCESS_FAILED"), {
            code: "LOCAL_PROCESS_FAILED",
          }),
        );
      } else {
        resolvePromise(Buffer.concat(stdout).toString("utf8").trim());
      }
    });
  });
}

async function verifyLocalSource(config) {
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
  const running = await runCaptured(docker, [
    "inspect",
    "--format",
    "{{.State.Running}}",
    SOURCE_CONTAINER,
  ]);
  if (running !== "true") fail("LOCAL_DATABASE_NOT_RUNNING");
  const name = await runCaptured(docker, [
    "inspect",
    "--format",
    "{{.Name}}",
    SOURCE_CONTAINER,
  ]);
  if (name !== `/${SOURCE_CONTAINER}`) fail("LOCAL_DATABASE_CONTAINER_INVALID");
  const port = await runCaptured(docker, [
    "port",
    SOURCE_CONTAINER,
    "5432/tcp",
  ]);
  if (
    !port.split(/\r?\n/).some((line) =>
      /^(127\.0\.0\.1|0\.0\.0\.0):18022$/.test(line)
    )
  ) {
    fail("LOCAL_DATABASE_PORT_INVALID");
  }
}

async function requestJson(config, path, { body = undefined } = {}) {
  if (
    typeof path !== "string" || !path.startsWith("/") || path.startsWith("//")
  ) fail("LOCAL_REQUEST_INVALID");
  const response = await fetch(`${config.API_URL}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      apikey: config.SERVICE_ROLE_KEY,
      Authorization: `Bearer ${config.SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
      Connection: "close",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "error",
    signal: AbortSignal.timeout(30_000),
  }).catch(() => fail("LOCAL_REQUEST_FAILED"));
  if (!response.ok) fail("LOCAL_REQUEST_FAILED");
  const text = await response.text();
  if (Buffer.byteLength(text, "utf8") > MAX_CAPTURE_BYTES) {
    fail("LOCAL_RESPONSE_TOO_LARGE");
  }
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    fail("LOCAL_RESPONSE_INVALID");
  }
}

async function sourceDatabaseMetadata() {
  const sql = String.raw`
select pg_catalog.json_build_object(
  'database_id', (
    select identity.database_id
    from private.deletion_ledger_identity as identity
    where identity.singleton
  ),
  'snapshot_time', pg_catalog.to_char(pg_catalog.clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'extensions', coalesce((
    select pg_catalog.json_agg(
      pg_catalog.json_build_object(
        'name', installed_extension.extname,
        'version', installed_extension.extversion,
        'schema', namespace.nspname
      )
      order by installed_extension.extname
    )
    from pg_catalog.pg_extension as installed_extension
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = installed_extension.extnamespace
  ), '[]'::json),
  'function_counts', (
    select pg_catalog.json_object_agg(names.schema_name, names.function_count order by names.schema_name)
    from (
      select wanted.schema_name, pg_catalog.count(proc.oid)::integer as function_count
      from (values ('public'), ('private'), ('auth'), ('storage')) as wanted(schema_name)
      left join pg_catalog.pg_namespace as namespace on namespace.nspname = wanted.schema_name
      left join pg_catalog.pg_proc as proc on proc.pronamespace = namespace.oid
      group by wanted.schema_name
    ) as names
  ),
  'rls_tables', coalesce((
    select pg_catalog.json_agg(namespace.nspname || '.' || class.relname order by namespace.nspname, class.relname)
    from pg_catalog.pg_class as class
    join pg_catalog.pg_namespace as namespace on namespace.oid = class.relnamespace
    where namespace.nspname in ('public', 'private', 'auth', 'storage')
      and class.relkind in ('r', 'p') and class.relrowsecurity
  ), '[]'::json)
)::text;`;
  const output = await runCaptured(executable("docker"), [
    "exec",
    SOURCE_CONTAINER,
    "psql",
    "-XAt",
    "-v",
    "ON_ERROR_STOP=1",
    "-U",
    "postgres",
    "-d",
    "postgres",
    "-c",
    sql,
  ]);
  let value;
  try {
    value = JSON.parse(output);
  } catch {
    fail("DATABASE_METADATA_INVALID");
  }
  if (
    !exactKeys(value, [
      "database_id",
      "snapshot_time",
      "extensions",
      "function_counts",
      "rls_tables",
    ]) ||
    !isUuid(value.database_id) ||
    !value.function_counts || typeof value.function_counts !== "object" ||
    Array.isArray(value.function_counts) ||
    !Array.isArray(value.rls_tables)
  ) fail("DATABASE_METADATA_INVALID");
  validateDatabaseExtensions(value.extensions);
  const schemas = snapshotSchemas();
  for (const schema of schemas) {
    if (
      !Number.isSafeInteger(value.function_counts[schema]) ||
      value.function_counts[schema] < 0
    ) fail("DATABASE_METADATA_INVALID");
  }
  if (
    Object.keys(value.function_counts).sort().join(",") !==
      schemas.sort().join(",")
  ) fail("DATABASE_METADATA_INVALID");
  const snapshotTime = isoUtc(value.snapshot_time, "DATABASE_METADATA_INVALID");
  return {
    databaseId: value.database_id,
    snapshotTime,
    extensions: value.extensions,
    functionCounts: value.function_counts,
    rlsTables: value.rls_tables,
  };
}

async function dumpDatabase(destination) {
  const schemas = snapshotSchemas();
  const args = [
    "exec",
    SOURCE_CONTAINER,
    "pg_dump",
    "-Fc",
    ...schemas.flatMap((schema) => ["--schema", schema]),
    "-U",
    "postgres",
    "-d",
    "postgres",
  ];
  const child = spawn(executable("docker"), args, {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  const output = createWriteStream(destination, { flags: "wx", mode: 0o600 });
  const completed = new Promise((resolvePromise, reject) => {
    let stderrBytes = 0;
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
      if (stderrBytes > MAX_CAPTURE_BYTES) child.kill();
    });
    child.once("error", () => {
      reject(
        Object.assign(new Error("DATABASE_DUMP_FAILED"), {
          code: "DATABASE_DUMP_FAILED",
        }),
      );
    });
    child.once("close", (status, signal) => {
      if (status !== 0 || signal || stderrBytes > MAX_CAPTURE_BYTES) {
        reject(
          Object.assign(new Error("DATABASE_DUMP_FAILED"), {
            code: "DATABASE_DUMP_FAILED",
          }),
        );
      } else {
        resolvePromise();
      }
    });
  });
  try {
    await Promise.all([pipeline(child.stdout, output), completed]);
  } catch {
    child.kill();
    fail("DATABASE_DUMP_FAILED");
  }
}

function validateAssetRow(row) {
  if (
    !exactKeys(row, [
      "id",
      "owner_id",
      "item_id",
      "object_path",
      "actual_bytes",
    ])
  ) fail("ASSET_METADATA_INVALID");
  if (!isUuid(row.id) || !isUuid(row.owner_id) || !isUuid(row.item_id)) {
    fail("ASSET_METADATA_INVALID");
  }
  const storagePath = `${row.owner_id}/${row.item_id}/${row.id}`;
  if (
    row.object_path !== storagePath ||
    !Number.isSafeInteger(row.actual_bytes) ||
    row.actual_bytes < 1 || row.actual_bytes > MAX_ASSET_BYTES
  ) fail("ASSET_METADATA_INVALID");
  return {
    ...row,
    storagePath,
    packagePath: `${STORAGE_BUCKET}/${storagePath}`,
  };
}

async function listActiveAssets(config) {
  const assets = [];
  for (let offset = 0;; offset += PAGE_SIZE) {
    const query = new URLSearchParams({
      select: "id,owner_id,item_id,object_path,actual_bytes",
      state: "eq.active",
      order: "owner_id.asc,item_id.asc,id.asc",
      limit: String(PAGE_SIZE),
      offset: String(offset),
    });
    const page = await requestJson(config, `/rest/v1/assets?${query}`);
    if (!Array.isArray(page)) fail("ASSET_METADATA_INVALID");
    for (const row of page) assets.push(validateAssetRow(row));
    if (page.length < PAGE_SIZE) break;
  }
  let prior = "";
  const ownerBytes = new Map();
  for (const asset of assets) {
    if (asset.packagePath <= prior) fail("ASSET_METADATA_INVALID");
    const total = (ownerBytes.get(asset.owner_id) ?? 0) + asset.actual_bytes;
    if (!Number.isSafeInteger(total) || total > MAX_OWNER_BYTES) {
      fail("ASSET_OWNER_LIMIT_EXCEEDED");
    }
    ownerBytes.set(asset.owner_id, total);
    prior = asset.packagePath;
  }
  return assets;
}

function encodedStoragePath(path) {
  return path.split("/").map(encodeURIComponent).join("/");
}

async function assetResponse(config, asset) {
  const response = await fetch(
    `${config.API_URL}/storage/v1/object/authenticated/${STORAGE_BUCKET}/${
      encodedStoragePath(asset.storagePath)
    }`,
    {
      method: "GET",
      headers: {
        apikey: config.SERVICE_ROLE_KEY,
        Authorization: `Bearer ${config.SERVICE_ROLE_KEY}`,
        "Accept-Encoding": "identity",
        Connection: "close",
      },
      redirect: "error",
      signal: AbortSignal.timeout(30_000),
    },
  ).catch(() => fail("ASSET_DOWNLOAD_FAILED"));
  if (
    !response.ok || !response.body || response.headers.get("content-encoding")
  ) fail("ASSET_DOWNLOAD_FAILED");
  const contentLength = response.headers.get("content-length");
  if (contentLength !== null && Number(contentLength) !== asset.actual_bytes) {
    fail("ASSET_SIZE_MISMATCH");
  }
  return response.body;
}

async function verifyWrittenArtifact(
  { outputPath, temporaryDirectory, credential, expectedKind },
) {
  const validationDirectory = join(temporaryDirectory, "verify");
  const verified = await decryptAndValidateArtifact({
    artifactPath: outputPath,
    temporaryDirectory: validationDirectory,
    credential,
    expectedKind,
  });
  await rm(verified.packagePath, { force: true });
  return verified.manifest;
}

export async function createLibraryBackup(
  { outputPath, environment = process.env } = {},
) {
  const destination = await safeOutputPath(outputPath);
  const credential = backupCredentialFromEnv(environment);
  const config = localBackendConfig();
  await verifyLocalSource(config);
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-backup-"),
  );
  const packagePath = join(temporaryDirectory, "snapshot.pack");
  const dumpPath = join(temporaryDirectory, "database.dump");
  let writer;
  try {
    const databaseMetadata = await sourceDatabaseMetadata();
    await dumpDatabase(dumpPath);
    const assets = await listActiveAssets(config);
    writer = await BackupPackageWriter.create(packagePath);
    const database = await writer.addFile("database.dump", dumpPath);
    const manifestAssets = [];
    for (const asset of assets) {
      const body = await assetResponse(config, asset);
      const entry = await writer.addStream(
        asset.packagePath,
        asset.actual_bytes,
        body,
      );
      manifestAssets.push({
        ownerId: asset.owner_id,
        itemId: asset.item_id,
        assetId: asset.id,
        storagePath: asset.storagePath,
        path: entry.path,
        size: entry.size,
        sha256: entry.sha256,
      });
    }
    const manifest = {
      formatVersion: 1,
      kind: "snapshot",
      sourceProjectId: LOCAL_PROJECT_ID,
      databaseId: databaseMetadata.databaseId,
      createdUtc: databaseMetadata.snapshotTime,
      snapshotTime: databaseMetadata.snapshotTime,
      database: {
        path: database.path,
        size: database.size,
        sha256: database.sha256,
        format: "postgres-custom",
        schemas: snapshotSchemas(),
        extensions: databaseMetadata.extensions,
      },
      assets: manifestAssets,
      schemaFunctionCounts: databaseMetadata.functionCounts,
      rlsTables: databaseMetadata.rlsTables,
    };
    validateSnapshotManifest(
      manifest,
      new Map([
        [database.path, database],
        ...manifestAssets.map((asset) => [asset.path, asset]),
        ["manifest.json", {
          path: "manifest.json",
          size: 0,
          sha256: "0".repeat(64),
        }],
      ]),
    );
    await writer.finish(manifest);
    writer = null;
    await encryptPackageFile({
      plaintextPath: packagePath,
      outputPath: destination,
      credential,
    });
    let verified;
    try {
      verified = await verifyWrittenArtifact({
        outputPath: destination,
        temporaryDirectory,
        credential,
        expectedKind: "snapshot",
      });
    } catch (error) {
      await rm(destination, { force: true });
      throw error;
    }
    const assetBytes = verified.assets.reduce(
      (sum, asset) => sum + asset.size,
      0,
    );
    return {
      status: "ok",
      kind: "snapshot",
      assets: verified.assets.length,
      assetBytes,
      databaseBytes: verified.database.size,
    };
  } finally {
    if (writer) await writer.abort();
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
}

async function readLedgerExport(config, value) {
  if (
    !exactKeys(value, [
      "export_id",
      "database_id",
      "coverage_from",
      "exported_through",
      "through_sequence",
    ])
  ) fail("LEDGER_EXPORT_INVALID");
  if (
    !isUuid(value.export_id) || !isUuid(value.database_id) ||
    !Number.isSafeInteger(value.through_sequence) || value.through_sequence < 0
  ) fail("LEDGER_EXPORT_INVALID");
  const events = [];
  let afterSequence = 0;
  let priorSequence = 0;
  while (true) {
    const page = await requestJson(
      config,
      "/rest/v1/rpc/library_read_deletion_ledger_export",
      {
        body: {
          p_export_id: value.export_id,
          p_after_sequence: afterSequence,
          p_limit: PAGE_SIZE,
        },
      },
    );
    if (
      !exactKeys(page, ["events", "next_sequence", "has_more"]) ||
      !Array.isArray(page.events) || typeof page.has_more !== "boolean" ||
      !Number.isSafeInteger(page.next_sequence) ||
      page.next_sequence < afterSequence ||
      page.next_sequence > value.through_sequence
    ) fail("LEDGER_EXPORT_INVALID");
    for (const event of page.events) {
      if (
        !exactKeys(event, [
          "sequence",
          "event_id",
          "kind",
          "owner_id",
          "item_id",
          "request_id",
          "requested_at",
        ])
      ) fail("LEDGER_EXPORT_INVALID");
      if (
        !Number.isSafeInteger(event.sequence) ||
        event.sequence <= priorSequence ||
        event.sequence > page.next_sequence || !isUuid(event.event_id) ||
        !isUuid(event.owner_id) ||
        (event.request_id !== null && !isUuid(event.request_id))
      ) fail("LEDGER_EXPORT_INVALID");
      events.push({
        sequence: event.sequence,
        eventId: event.event_id,
        ownerId: event.owner_id,
        itemId: event.item_id,
        kind: event.kind,
        requestedAt: isoUtc(event.requested_at, "LEDGER_EXPORT_INVALID"),
      });
      priorSequence = event.sequence;
    }
    if (page.has_more) {
      if (page.next_sequence <= afterSequence) fail("LEDGER_EXPORT_INVALID");
      afterSequence = page.next_sequence;
      continue;
    }
    if (page.next_sequence !== value.through_sequence) {
      fail("LEDGER_EXPORT_INCOMPLETE");
    }
    break;
  }
  return {
    formatVersion: 1,
    kind: "deletion-ledger",
    databaseId: value.database_id,
    exportId: value.export_id,
    coveredFrom: isoUtc(value.coverage_from, "LEDGER_EXPORT_INVALID"),
    coveredThrough: isoUtc(value.exported_through, "LEDGER_EXPORT_INVALID"),
    throughSequence: value.through_sequence,
    events,
  };
}

export async function exportDeletionLedger(
  { outputPath, environment = process.env } = {},
) {
  const destination = await safeOutputPath(outputPath);
  const credential = backupCredentialFromEnv(environment);
  const config = localBackendConfig();
  await verifyLocalSource(config);
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-ledger-"),
  );
  const packagePath = join(temporaryDirectory, "ledger.pack");
  let writer;
  try {
    const exported = await requestJson(
      config,
      "/rest/v1/rpc/library_export_deletion_ledger",
      { body: {} },
    );
    const manifest = await readLedgerExport(config, exported);
    writer = await BackupPackageWriter.create(packagePath);
    await writer.finish(manifest);
    writer = null;
    const validatedPackage = await validatePackageFile(packagePath);
    validateLedgerManifest(validatedPackage.manifest, validatedPackage.entries);
    await encryptPackageFile({
      plaintextPath: packagePath,
      outputPath: destination,
      credential,
    });
    let verified;
    try {
      verified = await verifyWrittenArtifact({
        outputPath: destination,
        temporaryDirectory,
        credential,
        expectedKind: "deletion-ledger",
      });
    } catch (error) {
      await rm(destination, { force: true });
      throw error;
    }
    if (
      verified.exportId !== manifest.exportId ||
      verified.databaseId !== manifest.databaseId
    ) fail("LEDGER_VERIFICATION_FAILED");
    const acknowledgement = await requestJson(
      config,
      "/rest/v1/rpc/library_ack_deletion_ledger_export",
      {
        body: { p_export_id: manifest.exportId },
      },
    );
    if (
      !exactKeys(acknowledgement, ["export_id", "acknowledged"]) ||
      acknowledgement.export_id !== manifest.exportId ||
      acknowledgement.acknowledged !== true
    ) {
      fail("LEDGER_ACKNOWLEDGEMENT_FAILED");
    }
    return {
      status: "ok",
      kind: "deletion-ledger",
      events: manifest.events.length,
    };
  } finally {
    if (writer) await writer.abort();
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
}

function parseCli(arguments_) {
  if (
    arguments_.length !== 3 || arguments_[1] !== "--output" || !arguments_[2]
  ) fail("USAGE");
  if (!["snapshot", "ledger"].includes(arguments_[0])) fail("USAGE");
  return { command: arguments_[0], outputPath: arguments_[2] };
}

async function main() {
  const options = parseCli(process.argv.slice(2));
  const receipt = options.command === "snapshot"
    ? await createLibraryBackup({ outputPath: options.outputPath })
    : await exportDeletionLedger({ outputPath: options.outputPath });
  process.stdout.write(`${JSON.stringify(receipt)}\n`);
}

if (
  resolve(process.argv[1] ?? "") === resolve(fileURLToPath(import.meta.url))
) {
  main().catch((error) => {
    const code =
      typeof error?.code === "string" && /^[A-Z0-9_]+$/.test(error.code)
        ? error.code
        : "BACKUP_FAILED";
    process.stderr.write(`${code}\n`);
    process.exitCode = 1;
  });
}
