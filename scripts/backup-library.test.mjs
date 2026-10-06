import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import { PassThrough, Writable } from "node:stream";
import test from "node:test";
import { createLibraryBackup } from "./backup-library.mjs";
import {
  BACKUP_KEY_ENV,
  decryptAndValidateArtifact,
  extractValidatedEntry,
} from "./backup-format.mjs";

const SNAPSHOT_ID = "00000003-000000A4-1";
const DATABASE_ID = "33333333-3333-4333-8333-333333333333";
const OWNER_ID = "11111111-1111-4111-8111-111111111111";
const ITEM_ID = "22222222-2222-4222-8222-222222222222";
const SNAPSHOT_TIME = "2026-09-16T00:00:00.123456Z";
const KEY = Buffer.alloc(32, 0x37);
const ENVIRONMENT = { [BACKUP_KEY_ENV]: KEY.toString("base64") };
const PRIVATE_MARKER = "private-source-error-and-object-content";
const CONFIG = {
  API_URL: "http://127.0.0.1:18021",
  DB_URL: "postgresql://postgres:fixture-only@127.0.0.1:18022/postgres",
  SERVICE_ROLE_KEY: "fixture-only-service-key",
};

function asset(index, actualBytes = 1) {
  const id = `${
    index.toString(16).padStart(8, "0")
  }-1111-4111-8111-111111111111`;
  return {
    id,
    owner_id: OWNER_ID,
    item_id: ITEM_ID,
    object_path: `${OWNER_ID}/${ITEM_ID}/${id}`,
    actual_bytes: actualBytes,
  };
}

function childProcess(onInput = () => {}) {
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.stdin = new Writable({
    write(chunk, _encoding, callback) {
      try {
        onInput(chunk.toString("utf8"), child);
        callback();
      } catch (error) {
        callback(error);
      }
    },
  });
  child.closed = false;
  child.finish = (status = 0, signal = null) => {
    if (child.closed) return;
    child.closed = true;
    child.stdout.end();
    child.stderr.end();
    queueMicrotask(() => child.emit("close", status, signal));
  };
  child.kill = (signal = "SIGTERM") => {
    child.killed = true;
    child.killSignal = signal;
    child.finish(1, signal);
    return true;
  };
  return child;
}

function mockedSource(initialAssets, options = {}) {
  const state = {
    events: [],
    commands: [],
    processOptions: [],
    exporterInput: [],
    inventorySql: [],
    inventoryOffsets: [],
    downloads: [],
    currentAssets: [...initialAssets],
    snapshotAssets: null,
    holder: null,
    rolledBack: false,
    bodyCanceled: false,
    dumpPath: null,
    dumpChild: null,
    inventoryChild: null,
  };
  const snapshotId = options.snapshotId ?? SNAPSHOT_ID;
  const respond = (child, output, status = 0) => {
    queueMicrotask(() => {
      if (output) child.stdout.write(output);
      child.finish(status);
    });
    return child;
  };
  const dependencies = {
    backendConfig: () => CONFIG,
    spawnProcess(command, args, processOptions) {
      assert.match(command, /^docker(?:[.]exe)?$/);
      assert.equal(processOptions.windowsHide, true);
      state.commands.push(args);
      state.processOptions.push(processOptions);
      if (!args.includes("-i")) {
        assert.equal(processOptions.killSignal, "SIGKILL");
        assert.equal(
          processOptions.timeout,
          args.includes("pg_dump") ? 120_000 : 30_000,
        );
      }
      if (args[0] === "inspect") {
        return respond(
          childProcess(),
          args.includes("{{.State.Running}}")
            ? "true\n"
            : "/supabase_db_link-vault\n",
        );
      }
      if (args[0] === "port") {
        return respond(childProcess(), "127.0.0.1:18022\n");
      }
      assert.equal(args[0], "exec");
      assert.ok(args.includes("supabase_db_link-vault"));
      if (args.includes("-i")) {
        assert.ok(args.includes("psql"));
        assert.ok(args.includes("-XqAt"));
        assert.deepEqual(processOptions.stdio, ["pipe", "pipe", "pipe"]);
        state.holder = childProcess((input, child) => {
          state.exporterInput.push(input);
          if (input.includes("pg_export_snapshot()")) {
            assert.match(
              input,
              /^BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;/,
            );
            assert.match(input, /select pg_catalog[.]jsonb_build_object/);
            assert.match(input, /pg_catalog[.]transaction_timestamp\(\)/);
            assert.ok(input.includes(
              "SET LOCAL idle_in_transaction_session_timeout = '300000ms';",
            ));
            assert.ok(input.includes("SET LOCAL lock_timeout = '10000ms';"));
            assert.ok(
              input.includes("SET LOCAL statement_timeout = '30000ms';"),
            );
            state.events.push("export");
            state.snapshotAssets = [...state.currentAssets];
            if (options.snapshotFailure) {
              queueMicrotask(() =>
                child.emit("error", new Error(PRIVATE_MARKER))
              );
              return;
            }
            const metadata = JSON.stringify({
              snapshot_id: snapshotId,
              database_id: DATABASE_ID,
              snapshot_time: SNAPSHOT_TIME,
              extensions: [
                { name: "plpgsql", version: "1.0", schema: "pg_catalog" },
              ],
              function_counts: { public: 1, private: 1, auth: 0, storage: 0 },
              rls_tables: ["public.assets", "public.items"],
            });
            child.stdout.write(metadata.slice(0, 17));
            child.stdout.write(`${metadata.slice(17)}\n`);
          } else {
            assert.equal(input, "ROLLBACK;\n\\q\n");
            state.rolledBack = true;
            state.events.push("rollback");
            child.finish(options.rollbackFailure ? 1 : 0);
          }
        });
        return state.holder;
      }
      if (args.includes("pg_dump")) {
        assert.equal(state.holder.closed, false);
        assert.deepEqual(
          args.slice(2, 7),
          ["timeout", "-s", "KILL", "110", "pg_dump"],
        );
        assert.ok(args.includes(`--snapshot=${SNAPSHOT_ID}`));
        assert.ok(args.includes("--lock-wait-timeout=10000ms"));
        assert.ok(args.includes("-Fc"));
        state.events.push("dump");
        const child = childProcess();
        state.dumpChild = child;
        const pipe = child.stdout.pipe;
        child.stdout.pipe = function (destination, ...pipeArguments) {
          state.dumpPath = destination.path;
          return pipe.call(this, destination, ...pipeArguments);
        };
        state.currentAssets = options.afterDump ?? state.currentAssets;
        if (options.dumpTimeout) {
          // Simulate Node's configured spawn deadline without wall-clock waits.
          queueMicrotask(() => {
            child.stdout.write("partial dump");
            child.kill(processOptions.killSignal);
          });
          return child;
        }
        if (options.dumpLockTimeout) {
          queueMicrotask(() => child.stderr.write(PRIVATE_MARKER));
          return respond(child, "", 1);
        }
        if (options.dumpFailure) {
          queueMicrotask(() => child.stderr.write(PRIVATE_MARKER));
          return respond(child, "partial dump", 1);
        }
        // Synthetic dump content records the rows selected by the imported
        // snapshot; it is not a PostgreSQL archive or a restore acceptance test.
        return respond(
          child,
          JSON.stringify({
            snapshotId: SNAPSHOT_ID,
            activeAssetIds: state.snapshotAssets.map((row) => row.id),
          }),
        );
      }
      assert.ok(args.includes("psql"));
      assert.ok(args.includes("-XqAt"));
      const sql = args[args.indexOf("-c") + 1];
      assert.match(sql, /^\s*begin isolation level repeatable read read only;/);
      assert.ok(sql.includes(`set transaction snapshot '${SNAPSHOT_ID}';`));
      assert.ok(sql.includes("set local lock_timeout = '10000ms';"));
      assert.ok(sql.includes("set local statement_timeout = '30000ms';"));
      assert.match(sql, /where state = 'active'/);
      assert.match(sql, /order by owner_id, item_id, id/);
      assert.match(sql, /rollback;$/);
      assert.equal(state.holder.closed, false);
      state.inventorySql.push(sql);
      const offset = Number(/limit 1000 offset (\d+)/.exec(sql)?.[1]);
      assert.ok(Number.isSafeInteger(offset));
      state.inventoryOffsets.push(offset);
      state.events.push(`inventory:${offset}`);
      if (options.loseExporter) state.holder.finish(1);
      if (options.inventoryTimeout) {
        const child = childProcess();
        state.inventoryChild = child;
        queueMicrotask(() => {
          child.stdout.write("[]");
          child.kill(processOptions.killSignal);
        });
        return child;
      }
      if (options.invalidInventory) {
        return respond(childProcess(), PRIVATE_MARKER);
      }
      if (options.inventoryFailure) {
        const child = childProcess();
        queueMicrotask(() => child.stderr.write(PRIVATE_MARKER));
        return respond(child, "", 1);
      }
      state.currentAssets = options.afterInventory ?? state.currentAssets;
      return respond(
        childProcess(),
        JSON.stringify(state.snapshotAssets.slice(offset, offset + 1000)),
      );
    },
    async fetchAsset(url, requestOptions) {
      assert.equal(state.holder.closed, true);
      assert.equal(state.rolledBack, true);
      assert.equal(requestOptions.method, "GET");
      assert.equal(requestOptions.redirect, "error");
      assert.equal(requestOptions.headers["Accept-Encoding"], "identity");
      assert.equal(requestOptions.headers.apikey, CONFIG.SERVICE_ROLE_KEY);
      assert.equal(
        requestOptions.headers.Authorization,
        `Bearer ${CONFIG.SERVICE_ROLE_KEY}`,
      );
      const prefix =
        `${CONFIG.API_URL}/storage/v1/object/authenticated/library-images/`;
      assert.ok(url.startsWith(prefix), "never re-enumerate through live REST");
      const path = url.slice(prefix.length);
      state.downloads.push(path);
      state.events.push("download");
      const selected = state.snapshotAssets.find((row) =>
        row.object_path === path
      );
      assert.ok(
        selected,
        "download only immutable paths chosen by the snapshot",
      );
      const missing = options.missingObject;
      const body = new ReadableStream({
        start(controller) {
          if (missing) {
            controller.enqueue(Buffer.from(PRIVATE_MARKER));
          } else {
            const size = options.truncatedObject
              ? selected.actual_bytes - 1
              : selected.actual_bytes;
            controller.enqueue(Buffer.alloc(size, 0x61));
          }
          controller.close();
        },
        cancel() {
          state.bodyCanceled = true;
        },
      });
      const contentLength = options.lengthMismatch
        ? selected.actual_bytes + 1
        : selected.actual_bytes;
      return new Response(body, {
        status: missing ? 404 : 200,
        headers: options.truncatedObject
          ? {}
          : { "content-length": String(contentLength) },
      });
    },
  };
  return { state, dependencies };
}

async function fixture(t, assets, options) {
  const directory = await mkdtemp(join(tmpdir(), "link-vault-backup-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const outputPath = join(directory, "snapshot.enc");
  const source = mockedSource(assets, options);
  return {
    ...source,
    outputPath,
    directory,
    backup: () =>
      createLibraryBackup(
        { outputPath, environment: ENVIRONMENT },
        source.dependencies,
      ),
  };
}

async function inspectArtifact(fixture) {
  return await decryptAndValidateArtifact({
    artifactPath: fixture.outputPath,
    temporaryDirectory: join(fixture.directory, "inspect"),
    credential: { kind: "raw", key: KEY },
    expectedKind: "snapshot",
  });
}

async function assertFailure(fixture, code) {
  await assert.rejects(fixture.backup, (error) => {
    assert.equal(error.code, code);
    assert.equal(error.message, code);
    assert.equal(error.message.includes(PRIVATE_MARKER), false);
    return true;
  });
  assert.equal(fixture.state.holder.closed, true);
  assert.equal(fixture.state.downloads.length, 0);
  await assert.rejects(stat(fixture.outputPath), { code: "ENOENT" });
  if (fixture.state.dumpPath) {
    await assert.rejects(stat(dirname(fixture.state.dumpPath)), {
      code: "ENOENT",
    });
  }
}

test("dump and inventory share a snapshot across concurrent activation and deletion", async (t) => {
  const before = [asset(1), asset(2)];
  const fixture_ = await fixture(t, before, {
    afterDump: [asset(2), asset(3)],
  });
  const receipt = await fixture_.backup();
  assert.equal(receipt.assets, 2);
  assert.equal(receipt.assetBytes, 2);
  const artifact = await inspectArtifact(fixture_);
  assert.equal(artifact.manifest.snapshotTime, SNAPSHOT_TIME);
  assert.equal(artifact.manifest.databaseId, DATABASE_ID);
  assert.deepEqual(
    artifact.manifest.assets.map((row) => row.assetId),
    before.map((row) => row.id),
  );
  const dumpPath = await extractValidatedEntry({
    packagePath: artifact.packagePath,
    entry: artifact.entries.get("database.dump"),
    destinationRoot: join(fixture_.directory, "dump-inspection"),
  });
  const dump = JSON.parse(await readFile(dumpPath, "utf8"));
  assert.deepEqual(
    dump.activeAssetIds,
    artifact.manifest.assets.map((row) => row.assetId),
  );
  assert.equal(dump.snapshotId, SNAPSHOT_ID);
  assert.deepEqual(
    fixture_.state.downloads,
    before.map((row) => row.object_path),
  );
  assert.deepEqual(fixture_.state.events, [
    "export",
    "dump",
    "inventory:0",
    "rollback",
    "download",
    "download",
  ]);
  await assert.rejects(stat(dirname(fixture_.state.dumpPath)), {
    code: "ENOENT",
  });
});

test("activation after an empty snapshot cannot add a dump-missing asset", async (t) => {
  const fixture_ = await fixture(t, [], { afterDump: [asset(1)] });
  const receipt = await fixture_.backup();
  const artifact = await inspectArtifact(fixture_);
  assert.equal(receipt.assets, 0);
  assert.deepEqual(artifact.manifest.assets, []);
  assert.deepEqual(fixture_.state.downloads, []);
  assert.deepEqual(fixture_.state.events, [
    "export",
    "dump",
    "inventory:0",
    "rollback",
  ]);
});

test("every inventory page imports the same still-live snapshot", async (t) => {
  const before = Array.from({ length: 1001 }, (_, index) => asset(index + 1));
  const fixture_ = await fixture(t, before, {
    afterDump: [asset(2001)],
    afterInventory: [],
  });
  const receipt = await fixture_.backup();
  assert.equal(receipt.assets, 1001);
  assert.equal(receipt.assetBytes, 1001);
  assert.deepEqual(fixture_.state.inventoryOffsets, [0, 1000]);
  assert.equal(fixture_.state.inventorySql.length, 2);
  const artifact = await inspectArtifact(fixture_);
  assert.deepEqual(
    artifact.manifest.assets.map((row) => row.assetId),
    before.map((row) => row.id),
  );
  assert.equal(fixture_.state.rolledBack, true);
});

test("unsafe snapshot identifiers never reach dump or inventory commands", async (t) => {
  const unsafe = `snapshot';select '${PRIVATE_MARKER}';--`;
  const fixture_ = await fixture(t, [asset(1)], { snapshotId: unsafe });
  await assertFailure(fixture_, "DATABASE_METADATA_INVALID");
  assert.equal(fixture_.state.rolledBack, true);
  assert.equal(
    fixture_.state.commands.some((args) => args.includes("pg_dump")),
    false,
  );
  assert.equal(JSON.stringify(fixture_.state.commands).includes(unsafe), false);
  assert.equal(fixture_.state.exporterInput.join("").includes(unsafe), false);
});

test("dump failure releases the snapshot and removes partial plaintext", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { dumpFailure: true });
  await assertFailure(fixture_, "DATABASE_DUMP_FAILED");
  assert.equal(fixture_.state.rolledBack, true);
  assert.ok(fixture_.state.dumpPath);
  assert.deepEqual(fixture_.state.inventoryOffsets, []);
});

test("pg_dump lock timeout releases exporter locks instead of awaiting queued DDL", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { dumpLockTimeout: true });
  await assertFailure(fixture_, "DATABASE_DUMP_FAILED");
  const dumpCommand = fixture_.state.commands.find((args) =>
    args.includes("pg_dump")
  );
  assert.ok(dumpCommand.includes("--lock-wait-timeout=10000ms"));
  assert.deepEqual(fixture_.state.events, ["export", "dump", "rollback"]);
  assert.equal(fixture_.state.rolledBack, true);
});

test("dump process deadline kills a stalled client and cleans partial plaintext", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { dumpTimeout: true });
  await assertFailure(fixture_, "DATABASE_DUMP_FAILED");
  const dumpIndex = fixture_.state.commands.findIndex((args) =>
    args.includes("pg_dump")
  );
  assert.equal(fixture_.state.processOptions[dumpIndex].timeout, 120_000);
  assert.equal(fixture_.state.dumpChild.killed, true);
  assert.equal(fixture_.state.dumpChild.killSignal, "SIGKILL");
  assert.equal(fixture_.state.rolledBack, true);
  assert.deepEqual(fixture_.state.inventoryOffsets, []);
});

test("inventory process deadline rejects partial results and releases the snapshot", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { inventoryTimeout: true });
  await assertFailure(fixture_, "LOCAL_PROCESS_FAILED");
  assert.equal(fixture_.state.processOptions.at(-1).timeout, 30_000);
  assert.equal(fixture_.state.inventoryChild.killed, true);
  assert.equal(fixture_.state.inventoryChild.killSignal, "SIGKILL");
  assert.equal(fixture_.state.rolledBack, true);
  assert.deepEqual(fixture_.state.events, [
    "export",
    "dump",
    "inventory:0",
    "rollback",
  ]);
});

test("failed inventory imports cannot publish a successful archive", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { inventoryFailure: true });
  await assertFailure(fixture_, "LOCAL_PROCESS_FAILED");
  assert.equal(fixture_.state.rolledBack, true);
});

test("invalid inventory output remains private and still releases the snapshot", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { invalidInventory: true });
  await assertFailure(fixture_, "ASSET_METADATA_INVALID");
  assert.equal(fixture_.state.rolledBack, true);
});

test("an unexpectedly lost exporter invalidates an otherwise successful dump", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { loseExporter: true });
  await assertFailure(fixture_, "DATABASE_SNAPSHOT_FAILED");
});

test("rollback failure prevents publication and still removes temporary files", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { rollbackFailure: true });
  await assertFailure(fixture_, "DATABASE_SNAPSHOT_FAILED");
  assert.equal(fixture_.state.rolledBack, true);
});

test("snapshot process errors expose only a fixed code", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { snapshotFailure: true });
  await assertFailure(fixture_, "DATABASE_SNAPSHOT_FAILED");
  assert.equal(fixture_.state.holder.killed, true);
});

test("a missing snapshot-selected object fails without live inventory fallback", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], {
    afterDump: [],
    missingObject: true,
  });
  await assert.rejects(fixture_.backup, { code: "ASSET_DOWNLOAD_FAILED" });
  assert.deepEqual(fixture_.state.downloads, [asset(1).object_path]);
  assert.deepEqual(fixture_.state.inventoryOffsets, [0]);
  assert.equal(fixture_.state.rolledBack, true);
  assert.equal(fixture_.state.bodyCanceled, true);
  await assert.rejects(stat(fixture_.outputPath), { code: "ENOENT" });
  await assert.rejects(stat(dirname(fixture_.state.dumpPath)), {
    code: "ENOENT",
  });
});

test("length-mismatched object bodies are canceled without publishing", async (t) => {
  const fixture_ = await fixture(t, [asset(1)], { lengthMismatch: true });
  await assert.rejects(fixture_.backup, { code: "ASSET_SIZE_MISMATCH" });
  assert.equal(fixture_.state.bodyCanceled, true);
  assert.equal(fixture_.state.rolledBack, true);
  await assert.rejects(stat(fixture_.outputPath), { code: "ENOENT" });
  await assert.rejects(stat(dirname(fixture_.state.dumpPath)), {
    code: "ENOENT",
  });
});

test("truncated immutable object streams fail package validation and clean up", async (t) => {
  const fixture_ = await fixture(t, [asset(1, 2)], { truncatedObject: true });
  await assert.rejects(fixture_.backup, {
    code: "PACKAGE_ENTRY_SIZE_MISMATCH",
  });
  assert.equal(fixture_.state.rolledBack, true);
  await assert.rejects(stat(fixture_.outputPath), { code: "ENOENT" });
  await assert.rejects(stat(dirname(fixture_.state.dumpPath)), {
    code: "ENOENT",
  });
});
