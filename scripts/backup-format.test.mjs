import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  BACKUP_KEY_ENV,
  BackupPackageWriter,
  decryptAndValidateArtifact,
  encryptPackageFile,
  validateDatabaseExtensions,
} from "./backup-format.mjs";
import {
  assertRestoreFreshness,
  cleanupRestoreResources,
  restoreLibrary,
  validateRestoreArtifacts,
} from "./restore-library.mjs";

const DATABASE_ID = "11111111-1111-4111-8111-111111111111";
const FOREIGN_DATABASE_ID = "22222222-2222-4222-8222-222222222222";
const EXPORT_ID = "33333333-3333-4333-8333-333333333333";
const KEY = Buffer.alloc(32, 0x37);
const CREDENTIAL = { kind: "raw", key: KEY };
const ENVIRONMENT = { [BACKUP_KEY_ENV]: KEY.toString("base64") };
const DATABASE_EXTENSIONS = Object.freeze([
  { name: "pg_cron", version: "1.6.4", schema: "pg_catalog" },
  { name: "pg_net", version: "0.20.4", schema: "extensions" },
  { name: "pg_stat_statements", version: "1.11", schema: "extensions" },
  { name: "pgcrypto", version: "1.3", schema: "extensions" },
  { name: "plpgsql", version: "1.0", schema: "pg_catalog" },
  { name: "supabase_vault", version: "0.3.1", schema: "vault" },
  { name: "uuid-ossp", version: "1.1", schema: "extensions" },
]);

async function temporary(t) {
  const directory = await mkdtemp(
    join(tmpdir(), "link-vault-backup-format-test-"),
  );
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

test("uncertain container inspection preserves mounted files and rejects verification", async (t) => {
  const temporaryDirectory = await temporary(t);
  const sentinel = join(temporaryDirectory, "database-sentinel");
  await writeFile(sentinel, "owned database");
  const calls = [];
  const execute = async (_command, args) => {
    calls.push(args[0]);
    return { status: 1, stdout: "" };
  };
  await assert.rejects(async () => {
    try {
      return { status: "verified" };
    } finally {
      await cleanupRestoreResources({
        containerStarted: true,
        containerName: "link-vault-restore-test",
        token: "owned-token",
        temporaryDirectory,
      }, { execute });
    }
  }, { code: "RESTORE_CONTAINER_CLEANUP_UNCONFIRMED" });
  assert.deepEqual(calls, ["inspect"]);
  assert.equal(await readFile(sentinel, "utf8"), "owned database");
});

test("confirmed owned-container removal precedes temporary-directory removal", async (t) => {
  const temporaryDirectory = await temporary(t);
  const calls = [];
  await cleanupRestoreResources({
    containerStarted: true,
    containerName: "link-vault-restore-test",
    token: "owned-token",
    temporaryDirectory,
  }, {
    execute: async (_command, args) => {
      calls.push(args[0]);
      return { status: 0, stdout: args[0] === "inspect" ? "owned-token" : "" };
    },
    remove: async (path, options) => {
      calls.push("directory");
      await rm(path, options);
    },
  });
  assert.deepEqual(calls, ["inspect", "rm", "directory"]);
  await assert.rejects(stat(temporaryDirectory), { code: "ENOENT" });
});

function snapshotManifest(database, databaseId = DATABASE_ID) {
  return {
    formatVersion: 1,
    kind: "snapshot",
    sourceProjectId: "link-vault",
    databaseId,
    createdUtc: "2026-09-16T00:00:00.000Z",
    snapshotTime: "2026-09-16T00:00:00.000Z",
    database: {
      path: database.path,
      size: database.size,
      sha256: database.sha256,
      format: "postgres-custom",
      schemas: ["public", "private", "auth", "storage"],
      extensions: DATABASE_EXTENSIONS,
    },
    assets: [],
    schemaFunctionCounts: { public: 1, private: 1, auth: 0, storage: 0 },
    rlsTables: ["public.items"],
  };
}

function ledgerManifest({
  databaseId = DATABASE_ID,
  coveredFrom = "2026-09-15T00:00:00.000Z",
  coveredThrough = "2026-09-16T01:00:00.000Z",
} = {}) {
  return {
    formatVersion: 1,
    kind: "deletion-ledger",
    databaseId,
    exportId: EXPORT_ID,
    coveredFrom,
    coveredThrough,
    throughSequence: 0,
    events: [],
  };
}

test("accepts the authenticated source extension inventory", () => {
  assert.equal(
    validateDatabaseExtensions(DATABASE_EXTENSIONS),
    DATABASE_EXTENSIONS,
  );
});

test("rejects unsafe, unsorted, and duplicate extension inventory fields", () => {
  const invalidInventories = [
    DATABASE_EXTENSIONS.map((extension, index) => {
      return index === 0 ? { ...extension, name: "PG_CRON" } : extension;
    }),
    DATABASE_EXTENSIONS.map((extension, index) => {
      return index === 0
        ? { ...extension, version: "1.6.4';drop schema public;--" }
        : extension;
    }),
    DATABASE_EXTENSIONS.map((extension, index) => {
      return index === 0 ? { ...extension, schema: "not-safe" } : extension;
    }),
    [
      DATABASE_EXTENSIONS[1],
      DATABASE_EXTENSIONS[0],
      ...DATABASE_EXTENSIONS.slice(2),
    ],
    [DATABASE_EXTENSIONS[0], DATABASE_EXTENSIONS[0]],
  ];
  for (const extensions of invalidInventories) {
    assert.throws(
      () => validateDatabaseExtensions(extensions),
      { code: "DATABASE_EXTENSIONS_INVALID" },
    );
  }
});

async function encryptedSnapshot(
  directory,
  { databaseId = DATABASE_ID, mutatePackage } = {},
) {
  const packagePath = join(
    directory,
    `snapshot-${randomBytes(4).toString("hex")}.pack`,
  );
  const artifactPath = join(
    directory,
    `snapshot-${randomBytes(4).toString("hex")}.lvbackup`,
  );
  const writer = await BackupPackageWriter.create(packagePath);
  const database = await writer.addBuffer(
    "database.dump",
    Buffer.from("custom-format-database"),
  );
  await writer.finish(snapshotManifest(database, databaseId));
  if (mutatePackage) await mutatePackage(packagePath);
  await encryptPackageFile({
    plaintextPath: packagePath,
    outputPath: artifactPath,
    credential: CREDENTIAL,
  });
  return artifactPath;
}

async function encryptedLedger(directory, options = {}) {
  const packagePath = join(
    directory,
    `ledger-${randomBytes(4).toString("hex")}.pack`,
  );
  const artifactPath = join(
    directory,
    `ledger-${randomBytes(4).toString("hex")}.lvbackup`,
  );
  const writer = await BackupPackageWriter.create(packagePath);
  await writer.finish(ledgerManifest(options));
  await encryptPackageFile({
    plaintextPath: packagePath,
    outputPath: artifactPath,
    credential: CREDENTIAL,
  });
  return artifactPath;
}

async function rejectsCode(action, code) {
  await assert.rejects(
    action,
    (error) => error?.code === code || error?.message === code,
  );
}

function freshnessInput({
  databaseId = DATABASE_ID,
  sourceDatabaseId = DATABASE_ID,
  throughSequence = 12,
  currentHighWater = "12",
  coveredThrough = "2026-09-16T00:05:00.000000Z",
  snapshotTime = "2026-08-17T00:10:00.000000Z",
  databaseTime = "2026-09-16T00:10:00.000000Z",
} = {}) {
  return {
    snapshot: { snapshotTime },
    ledger: { databaseId, throughSequence, coveredThrough },
    source: {
      databaseId: sourceDatabaseId,
      currentHighWater,
      databaseTime,
    },
  };
}

test("freshness accepts exact five-minute and thirty-day boundaries", () => {
  assert.equal(assertRestoreFreshness(freshnessInput()), true);
});

test("freshness rejects a ledger below the current deletion high-water", () => {
  assert.throws(
    () =>
      assertRestoreFreshness(freshnessInput({
        throughSequence: 11,
        currentHighWater: "12",
      })),
    { code: "STALE_DELETION_LEDGER_SEQUENCE" },
  );
});

test("freshness rejects a ledger older than five DB-clock minutes", () => {
  assert.throws(
    () =>
      assertRestoreFreshness(freshnessInput({
        coveredThrough: "2026-09-16T00:04:59.999999Z",
      })),
    { code: "STALE_DELETION_LEDGER" },
  );
});

test("freshness rejects ledger coverage from the DB-clock future", () => {
  assert.throws(
    () =>
      assertRestoreFreshness(freshnessInput({
        coveredThrough: "2026-09-16T00:10:00.000001Z",
      })),
    { code: "DELETION_LEDGER_FROM_FUTURE" },
  );
});

test("freshness rejects a source database UUID mismatch", () => {
  assert.throws(
    () =>
      assertRestoreFreshness(freshnessInput({
        sourceDatabaseId: FOREIGN_DATABASE_ID,
      })),
    { code: "FOREIGN_SOURCE_DATABASE" },
  );
});

test("freshness rejects a snapshot older than thirty DB-clock days", () => {
  assert.throws(
    () =>
      assertRestoreFreshness(freshnessInput({
        snapshotTime: "2026-08-17T00:09:59.999999Z",
      })),
    { code: "SNAPSHOT_EXPIRED" },
  );
});

test("authenticates and validates a snapshot package", async (t) => {
  const directory = await temporary(t);
  const artifactPath = await encryptedSnapshot(directory);
  const verified = await decryptAndValidateArtifact({
    artifactPath,
    temporaryDirectory: join(directory, "verified"),
    credential: CREDENTIAL,
    expectedKind: "snapshot",
  });
  assert.equal(verified.manifest.databaseId, DATABASE_ID);
  assert.equal(verified.entries.size, 2);
});

test("rejects ciphertext tampering before package validation", async (t) => {
  const directory = await temporary(t);
  const artifactPath = await encryptedSnapshot(directory);
  const bytes = await readFile(artifactPath);
  bytes[Math.floor(bytes.length / 2)] ^= 0x80;
  await writeFile(artifactPath, bytes);
  await assert.rejects(() =>
    decryptAndValidateArtifact({
      artifactPath,
      temporaryDirectory: join(directory, "tampered"),
      credential: CREDENTIAL,
      expectedKind: "snapshot",
    })
  );
});

test("rejects the wrong encryption key", async (t) => {
  const directory = await temporary(t);
  const artifactPath = await encryptedSnapshot(directory);
  await assert.rejects(() =>
    decryptAndValidateArtifact({
      artifactPath,
      temporaryDirectory: join(directory, "wrong-key"),
      credential: { kind: "raw", key: Buffer.alloc(32, 0x49) },
      expectedKind: "snapshot",
    })
  );
});

test("rejects a truncated encrypted artifact", async (t) => {
  const directory = await temporary(t);
  const artifactPath = await encryptedSnapshot(directory);
  const bytes = await readFile(artifactPath);
  await writeFile(artifactPath, bytes.subarray(0, bytes.length - 9));
  await assert.rejects(() =>
    decryptAndValidateArtifact({
      artifactPath,
      temporaryDirectory: join(directory, "truncated"),
      credential: CREDENTIAL,
      expectedKind: "snapshot",
    })
  );
});

test("rejects package path traversal", async (t) => {
  const directory = await temporary(t);
  const artifactPath = await encryptedSnapshot(directory, {
    mutatePackage: async (packagePath) => {
      const bytes = await readFile(packagePath);
      const safe = Buffer.from("database.dump");
      const unsafe = Buffer.from("../escape.bin");
      assert.equal(safe.length, unsafe.length);
      const index = bytes.indexOf(safe);
      assert.ok(index >= 0);
      unsafe.copy(bytes, index);
      await writeFile(packagePath, bytes);
    },
  });
  await rejectsCode(() =>
    decryptAndValidateArtifact({
      artifactPath,
      temporaryDirectory: join(directory, "traversal"),
      credential: CREDENTIAL,
      expectedKind: "snapshot",
    }), "INVALID_PACKAGE_PATH");
  await assert.rejects(() => readFile(join(directory, "escape.bin")), {
    code: "ENOENT",
  });
});

test("rejects a foreign-project ledger with a different database UUID", async (t) => {
  const directory = await temporary(t);
  const backupPath = await encryptedSnapshot(directory);
  const ledgerPath = await encryptedLedger(directory, {
    databaseId: FOREIGN_DATABASE_ID,
  });
  await rejectsCode(
    () =>
      validateRestoreArtifacts({
        backupPath,
        ledgerPath,
        environment: ENVIRONMENT,
      }),
    "FOREIGN_DELETION_LEDGER",
  );
});

test("rejects a missing deletion ledger", async (t) => {
  const directory = await temporary(t);
  const backupPath = await encryptedSnapshot(directory);
  await rejectsCode(() =>
    validateRestoreArtifacts({
      backupPath,
      ledgerPath: join(directory, "missing-ledger.lvbackup"),
      environment: ENVIRONMENT,
    }), "BACKUP_INPUT_UNAVAILABLE");
});

test("rejects ledger coverage that starts after the snapshot", async (t) => {
  const directory = await temporary(t);
  const backupPath = await encryptedSnapshot(directory);
  const ledgerPath = await encryptedLedger(directory, {
    coveredFrom: "2026-09-16T00:00:00.001Z",
    coveredThrough: "2026-09-16T02:00:00.000Z",
  });
  await rejectsCode(
    () =>
      validateRestoreArtifacts({
        backupPath,
        ledgerPath,
        environment: ENVIRONMENT,
      }),
    "STALE_DELETION_LEDGER",
  );
});

test("restore authenticates both artifacts before any Docker or extraction work", async (t) => {
  const directory = await temporary(t);
  const backupPath = await encryptedSnapshot(directory);
  const ledgerPath = await encryptedLedger(directory);
  const bytes = await readFile(ledgerPath);
  bytes[bytes.length - 1] ^= 0x01;
  await writeFile(ledgerPath, bytes);
  await assert.rejects(() =>
    restoreLibrary({ backupPath, ledgerPath, environment: ENVIRONMENT })
  );
  await assert.rejects(() => readFile(join(directory, "database.dump")), {
    code: "ENOENT",
  });
});
