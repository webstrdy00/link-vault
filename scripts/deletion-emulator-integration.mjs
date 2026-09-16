import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { setTimeout as pause } from "node:timers/promises";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const {
  request,
  user,
  approve,
  bootstrap,
  settleClassification,
  serviceKey,
  anonKey,
} = localBackendFixture("deletion-emulator");
const API_PREFIX = "/functions/v1/library-api/v1";
const STORAGE_BUCKET = "library-images";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const parentEnvironment = { ...process.env };
const remoteScreenshot = "/data/local/tmp/link-vault-deletion.png";
const createdAccounts = new Map();

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function operationalSql(sql) {
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
    stdio: ["ignore", "pipe", "inherit"],
    windowsHide: true,
  }).trim();
}

function operationalJson(expression) {
  const raw = operationalSql(`select (${expression})::text;`);
  assert.notEqual(raw, "");
  return JSON.parse(raw);
}

function operationalCount(query) {
  const raw = operationalSql(
    `select count(*)::text from (${query}) as counted;`,
  );
  assert.match(raw, /^\d+$/);
  return Number(raw);
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function selectRunningEmulator() {
  const output = execFileSync("adb", ["devices"], {
    encoding: "utf8",
    timeout: 30_000,
  });
  const running = new Set(
    output.split(/\r?\n/)
      .map((line) => line.match(/^(emulator-\d+)\s+device$/)?.[1])
      .filter(Boolean),
  );
  const requested = process.env.ANDROID_SERIAL?.trim();
  if (requested) {
    assert.match(
      requested,
      /^emulator-\d+$/,
      "ANDROID_SERIAL must select an emulator",
    );
    assert.ok(
      running.has(requested),
      `ANDROID_SERIAL ${requested} is not running`,
    );
    return requested;
  }
  const selected = running.values().next().value;
  assert.ok(
    selected,
    "Start an Android emulator before this integration suite",
  );
  return selected;
}

function invokeGradle(args, environment, timeout) {
  const gradle = process.platform === "win32"
    ? "android\\gradlew.bat"
    : "./android/gradlew";
  return process.platform === "win32"
    ? spawnSync(process.env.ComSpec || "cmd.exe", [
      "/d",
      "/v:off",
      "/s",
      "/c",
      [gradle, ...args].join(" "),
    ], { env: environment, stdio: "inherit", timeout })
    : spawnSync(gradle, args, { env: environment, stdio: "inherit", timeout });
}

function assertProcessSucceeded(result, label) {
  assert.equal(
    result.error,
    undefined,
    `${label} exceeded its bounded process run`,
  );
  assert.equal(
    result.signal,
    null,
    `${label} must not be terminated by a signal`,
  );
  assert.equal(result.status, 0, `${label} must pass`);
}

function adb(serial, args, options = {}) {
  return execFileSync("adb", ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: 90_000,
    ...options,
  });
}

function instrumentationArguments(fixture) {
  return [
    "-e",
    "fixtureEmail",
    fixture.email,
    "-e",
    "fixturePassword",
    fixture.password,
    "-e",
    "fixtureOwnerId",
    fixture.ownerId,
    "-e",
    "acceptedItemId",
    fixture.acceptedItemId,
    "-e",
    "conflictItemId",
    fixture.conflictItemId,
    "-e",
    "unaffectedItemId",
    fixture.unaffectedItemId,
    "-e",
    "acceptedVersion",
    String(fixture.acceptedVersion),
    "-e",
    "conflictVersion",
    String(fixture.conflictVersion),
    "-e",
    "reviewedConflictVersion",
    String(fixture.reviewedConflictVersion),
    "-e",
    "unaffectedVersion",
    String(fixture.unaffectedVersion),
    "-e",
    "accountCleanupGenerationBefore",
    String(fixture.accountCleanupGenerationBefore),
    "-e",
    "deletedSearchTerm",
    fixture.deletedSearchTerm,
  ];
}

function invokeInstrumentation(serial, method, fixture, timeout = 10 * 60_000) {
  const result = spawnSync("adb", [
    "-s",
    serial,
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "class",
    `com.linkvault.app.DeletionIntegrationTest#${method}`,
    ...instrumentationArguments(fixture),
    "com.linkvault.app.test/androidx.test.runner.AndroidJUnitRunner",
  ], {
    encoding: "utf8",
    timeout,
    maxBuffer: 4 * 1024 * 1024,
  });
  assertProcessSucceeded(result, `deletion instrumentation phase ${method}`);
  assert.match(
    result.stdout,
    /OK \(1 test\)/,
    `${method} must execute exactly one test`,
  );
  assert.doesNotMatch(
    result.stdout,
    /FAILURES!!!/,
    `${method} must not report failures`,
  );
}

function readReceipt(serial) {
  const raw = adb(serial, [
    "exec-out",
    "run-as",
    "com.linkvault.app",
    "cat",
    "files/deletion-fixture-receipt.json",
  ]);
  return JSON.parse(raw);
}

async function api(
  account,
  path,
  { method = "GET", body, requestId = randomUUID() } = {},
) {
  return request(`${API_PREFIX}${path}`, {
    token: account.token,
    method,
    body,
    headers: method === "GET" ? {} : { "X-Request-Id": requestId },
  });
}

async function createAccount({ approved = true, bootstrapped = true } = {}) {
  const account = await user();
  assert.match(account.id, UUID_PATTERN);
  createdAccounts.set(account.id, account);
  if (approved) await approve(account);
  if (bootstrapped) {
    const result = await bootstrap(account);
    assert.equal(
      result.status,
      200,
      "bootstrap the approved emulator fixture owner",
    );
  }
  return account;
}

async function createItem(account, body) {
  const response = await api(account, "/items", { method: "POST", body });
  assert.equal(
    response.status,
    201,
    "seed a saved item through the actual API",
  );
  assert.match(response.body.item.id, UUID_PATTERN);
  const settled = await settleClassification(account, response.body.item.id);
  assert.equal(settled.status, 200, "settle the saved item before UI deletion");
  return settled.body;
}

async function itemDetail(account, itemId) {
  return api(account, `/items/${itemId}`);
}

async function searchItems(account, query) {
  const parameters = new URLSearchParams({ q: query, limit: "50" });
  const result = await api(account, `/items?${parameters}`);
  assert.equal(result.status, 200, "search the actual deletion fixture owner");
  return result.body.items;
}

async function listOwnerStorageObjects(ownerId) {
  assert.match(ownerId, UUID_PATTERN);
  const objects = [];
  const pending = [ownerId];
  const visited = new Set();
  while (pending.length > 0) {
    const prefix = pending.pop();
    if (visited.has(prefix)) continue;
    visited.add(prefix);
    assert.ok(prefix === ownerId || prefix.startsWith(`${ownerId}/`));
    let offset = 0;
    while (true) {
      const response = await request(
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
      assert.equal(response.status, 200, "list only this run's owner prefix");
      for (const entry of response.body) {
        assert.match(entry.name, /^[^/]+$/);
        const path = `${prefix}/${entry.name}`;
        if (entry.id) objects.push(path);
        else pending.push(path);
      }
      if (response.body.length < 100) break;
      offset += response.body.length;
    }
  }
  return objects;
}

async function removeOwnerStorage(ownerId) {
  const paths = await listOwnerStorageObjects(ownerId);
  if (paths.length > 0) {
    const response = await request(`/storage/v1/object/${STORAGE_BUCKET}`, {
      token: serviceKey,
      method: "DELETE",
      body: { prefixes: paths },
    });
    assert.equal(
      response.status,
      200,
      "delete only created-owner Storage objects",
    );
  }
  assert.deepEqual(await listOwnerStorageObjects(ownerId), []);
}

async function deleteCreatedAuthUser(ownerId) {
  const response = await request(`/auth/v1/admin/users/${ownerId}`, {
    token: serviceKey,
    method: "DELETE",
  });
  assert.ok([200, 404].includes(response.status));
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
        "clear asset rows only after physical Storage absence",
      );
    } catch (error) {
      failures.push(error);
    }
    if (!storageCleared) continue;
    try {
      await deleteCreatedAuthUser(ownerId);
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
  if (failures.length > 0) {
    throw new AggregateError(failures, "emulator fixture cleanup failed");
  }
}

async function createChallenge(account) {
  const response = await api(account, "/account/delete-challenge", {
    method: "POST",
    body: {},
  });
  assert.equal(
    response.status,
    201,
    "create the real account deletion challenge",
  );
  assert.match(response.body.challenge_id, UUID_PATTERN);
  assert.match(response.body.nonce, /^[A-Za-z0-9_-]{43}$/);
  return response.body;
}

function trustedAcceptAccountDeletion(account, challenge) {
  // The emulator has a real local email session, not a fabricated Google identity.
  // Verify the actual API nonce binding, then cross the explicit trusted SQL fixture
  // boundary so the Android restore path can observe a genuinely deleting account.
  const nonceHash = sha256(challenge.nonce);
  const binding = operationalJson(`
    public.library_check_delete_challenge_binding(
      ${sqlString(account.id)}::uuid,
      ${sqlString(challenge.challenge_id)}::uuid,
      ${sqlString(nonceHash)}
    )
  `);
  assert.deepEqual(binding, { http_status: 200, state: "valid" });
  const fixtureBody = {
    challenge_id: challenge.challenge_id,
    google_id_token: "trusted-emulator-integration-boundary-not-a-google-token",
  };
  // Keep this fixture's Auth account readable until the cold app observes /me.
  // Scheduling is released before the real cleanup-worker acceptance below.
  operationalSql(`
    do $fixture$
    declare accepted jsonb;
    begin
    accepted := public.library_accept_account_deletion(
      ${sqlString(account.id)}::uuid,
      ${sqlString(randomUUID())}::uuid,
      ${sqlString(challenge.challenge_id)}::uuid,
      ${sqlString(sha256(JSON.stringify(fixtureBody)))}
    );
    if accepted <> '{"http_status":202,"state":"deleting"}'::jsonb then
      raise exception 'FIXTURE_ACCOUNT_ACCEPTANCE_FAILED';
    end if;
    update public.account_deletion_jobs
    set next_run_at = pg_catalog.clock_timestamp() + interval '30 minutes'
    where owner_id = ${sqlString(account.id)}::uuid;
    end
    $fixture$;
  `);
}

async function waitForAccountCleanup(ownerId) {
  operationalSql(`
    update public.account_deletion_jobs
    set next_run_at = pg_catalog.clock_timestamp()
    where owner_id = ${
    sqlString(ownerId)
  }::uuid and state in ('queued', 'retry');
  `);
  for (let attempt = 0; attempt < 30; attempt++) {
    const worker = await request(`${API_PREFIX}/internal/maintenance`, {
      token: serviceKey,
      method: "POST",
      body: { limit: 10 },
    });
    assert.equal(worker.status, 200, "run the actual account deletion worker");
    const job = operationalJson(`
      coalesce(
        (select pg_catalog.to_jsonb(job) from public.account_deletion_jobs as job
         where owner_id = ${sqlString(ownerId)}::uuid),
        'null'::jsonb
      )
    `);
    if (job?.state === "complete") return job;
    operationalSql(`
      update public.account_deletion_jobs
      set next_run_at = pg_catalog.clock_timestamp() - interval '1 second'
      where owner_id = ${sqlString(ownerId)}::uuid and state = 'retry';
    `);
    await pause(250);
  }
  assert.fail(
    "account cleanup did not complete within its bounded worker window",
  );
}

function retainFixtureScreenshot(serial) {
  try {
    adb(serial, ["shell", "test", "-s", remoteScreenshot]);
  } catch {
    return null;
  }
  const directory = resolve(
    "android",
    "app",
    "build",
    "reports",
    "deletion-emulator",
  );
  const outputFile = resolve(directory, "link-vault-deletion.png");
  mkdirSync(directory, { recursive: true });
  adb(serial, ["pull", remoteScreenshot, outputFile]);
  assert.ok(existsSync(outputFile) && statSync(outputFile).size > 0);
  try {
    adb(serial, ["shell", "rm", "-f", remoteScreenshot]);
  } catch {
    // The retained host screenshot is already durable; preserve the primary failure.
  }
  return outputFile;
}

let primaryFailure;
let serial;
let originalWifi;
let originalData;
let restoreDefaultApk = false;
let screenshotRetained = false;
try {
  try {
    const preflight = operationalJson(`
      pg_catalog.jsonb_build_object(
        'auth_users', (select count(*) from auth.users),
        'profiles', (select count(*) from public.profiles),
        'items', (select count(*) from public.items),
        'assets', (select count(*) from public.assets),
        'library_objects', (select count(*) from storage.objects where bucket_id = 'library-images')
      )
    `);
    assert.deepEqual(preflight, {
      auth_users: 0,
      profiles: 0,
      items: 0,
      assets: 0,
      library_objects: 0,
    }, "emulator integration requires an empty local fixture database");

    serial = selectRunningEmulator();
    originalWifi = adb(serial, [
      "shell",
      "settings",
      "get",
      "global",
      "wifi_on",
    ]).trim();
    originalData = adb(serial, [
      "shell",
      "settings",
      "get",
      "global",
      "mobile_data",
    ]).trim();
    adb(serial, ["shell", "rm", "-f", remoteScreenshot]);

    const owner = await createAccount();
    const other = await createAccount();
    const deletedSearchTerm = `M5삭제검색${randomUUID().replaceAll("-", "")}`;
    const accepted = await createItem(owner, {
      url: `https://example.test/android-delete-accepted-${randomUUID()}`,
      title: `${deletedSearchTerm} accepted`,
    });
    const conflict = await createItem(owner, {
      url: `https://example.test/android-delete-conflict-${randomUUID()}`,
      title: `${deletedSearchTerm} conflict`,
    });
    const unaffected = await createItem(owner, {
      url: `https://example.test/android-delete-unaffected-${randomUUID()}`,
      title: "owner unaffected",
    });
    const foreign = await createItem(other, {
      url: `https://example.test/android-delete-foreign-${randomUUID()}`,
      title: "foreign unaffected",
    });

    const fixture = {
      email: owner.email,
      password: owner.password,
      ownerId: owner.id,
      acceptedItemId: accepted.id,
      conflictItemId: conflict.id,
      unaffectedItemId: unaffected.id,
      acceptedVersion: accepted.version,
      conflictVersion: conflict.version,
      reviewedConflictVersion: conflict.version,
      unaffectedVersion: unaffected.version,
      deletedSearchTerm,
    };
    const configuredEnvironment = {
      ...process.env,
      ANDROID_SERIAL: serial,
      SUPABASE_URL: "http://10.0.2.2:18021",
      SUPABASE_PUBLISHABLE_KEY: anonKey,
      GOOGLE_WEB_CLIENT_ID:
        "local-deletion-instrumentation.apps.googleusercontent.com",
    };
    const firstPhaseArguments = [
      "-p",
      "android",
      ":app:connectedDebugAndroidTest",
      "--console=plain",
      "-PlocalBackendTests=true",
      "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
      "-Pandroid.testInstrumentationRunnerArguments.class=" +
      "com.linkvault.app.DeletionIntegrationTest#queueConfirmedItemDeletesOffline",
      `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${fixture.email}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${fixture.password}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixtureOwnerId=${fixture.ownerId}`,
      `-Pandroid.testInstrumentationRunnerArguments.acceptedItemId=${fixture.acceptedItemId}`,
      `-Pandroid.testInstrumentationRunnerArguments.conflictItemId=${fixture.conflictItemId}`,
      `-Pandroid.testInstrumentationRunnerArguments.unaffectedItemId=${fixture.unaffectedItemId}`,
      `-Pandroid.testInstrumentationRunnerArguments.acceptedVersion=${fixture.acceptedVersion}`,
      `-Pandroid.testInstrumentationRunnerArguments.conflictVersion=${fixture.conflictVersion}`,
      `-Pandroid.testInstrumentationRunnerArguments.reviewedConflictVersion=${fixture.reviewedConflictVersion}`,
      `-Pandroid.testInstrumentationRunnerArguments.unaffectedVersion=${fixture.unaffectedVersion}`,
      `-Pandroid.testInstrumentationRunnerArguments.deletedSearchTerm=${fixture.deletedSearchTerm}`,
    ];
    restoreDefaultApk = true;
    assertProcessSucceeded(
      invokeGradle(firstPhaseArguments, configuredEnvironment, 15 * 60_000),
      "offline item deletion queue phase",
    );

    let receipt = readReceipt(serial);
    assert.equal(receipt.ownerId, owner.id);
    assert.match(receipt.acceptedDeleteRequestId, UUID_PATTERN);
    assert.match(receipt.conflictDeleteRequestId, UUID_PATTERN);
    assert.notEqual(
      receipt.acceptedDeleteRequestId,
      receipt.conflictDeleteRequestId,
    );
    assert.equal((await itemDetail(owner, accepted.id)).status, 200);
    assert.equal((await itemDetail(owner, conflict.id)).status, 200);
    assert.equal(
      operationalCount(
        `select 1 from public.api_requests where owner_id = ${
          sqlString(owner.id)
        }::uuid and request_id in (` +
          `${sqlString(receipt.acceptedDeleteRequestId)}::uuid, ${
            sqlString(receipt.conflictDeleteRequestId)
          }::uuid)`,
      ),
      0,
      "offline DELETE UUIDs have not reached the server",
    );

    adb(serial, ["shell", "am", "force-stop", "com.linkvault.app"]);
    const conflictEdit = await api(owner, `/items/${conflict.id}`, {
      method: "PATCH",
      body: {
        expected_version: conflict.version,
        title: `${deletedSearchTerm} reviewed`,
      },
    });
    assert.equal(
      conflictEdit.status,
      200,
      "create the real server-side version conflict",
    );
    const reviewedConflict = await settleClassification(owner, conflict.id);
    assert.equal(
      reviewedConflict.status,
      200,
      "settle derived changes before reviewing deletion",
    );
    fixture.reviewedConflictVersion = reviewedConflict.body.version;

    invokeInstrumentation(
      serial,
      "reconnectOriginalDeletesAndReviewConflict",
      fixture,
    );
    receipt = readReceipt(serial);
    assert.equal(receipt.acceptedTombstone, true);
    assert.equal(receipt.conflictTombstone, true);
    assert.equal(receipt.lateCacheFenced, true);
    assert.match(receipt.reviewedDeleteRequestId, UUID_PATTERN);
    assert.notEqual(
      receipt.reviewedDeleteRequestId,
      receipt.conflictDeleteRequestId,
    );
    assert.equal(
      receipt.reviewedDeletePayload,
      `{"expected_version":${reviewedConflict.body.version}}`,
    );
    assert.equal((await itemDetail(owner, accepted.id)).status, 404);
    assert.equal((await itemDetail(owner, conflict.id)).status, 404);
    assert.equal((await itemDetail(owner, unaffected.id)).status, 200);
    assert.equal((await itemDetail(other, foreign.id)).status, 200);
    assert.ok(
      !(await searchItems(owner, deletedSearchTerm)).some((item) =>
        item.id === accepted.id || item.id === conflict.id
      ),
      "deleted items are absent from the real search projection",
    );

    invokeInstrumentation(
      serial,
      "accountConfirmationCancellationAndProviderFailureDefer",
      fixture,
    );
    receipt = readReceipt(serial);
    assert.equal(receipt.accountPendingBeforeAccept, true);
    assert.ok(Number.isSafeInteger(receipt.accountCleanupGenerationBefore));
    fixture.accountCleanupGenerationBefore =
      receipt.accountCleanupGenerationBefore;
    assert.match(receipt.accountPendingRequestId, UUID_PATTERN);
    assert.equal(
      operationalCount(
        `select 1 from public.account_deletion_jobs where owner_id = ${
          sqlString(owner.id)
        }::uuid`,
      ),
      0,
      "cancellation/provider failure did not start account deletion",
    );

    adb(serial, ["shell", "am", "force-stop", "com.linkvault.app"]);
    const challenge = await createChallenge(owner);
    trustedAcceptAccountDeletion(owner, challenge);
    assert.equal(
      operationalCount(
        `select 1 from public.api_requests where owner_id = ${
          sqlString(owner.id)
        }::uuid and request_id = ` +
          `${sqlString(receipt.accountPendingRequestId)}::uuid`,
      ),
      0,
      "offline account outbox request was not accepted before restart",
    );

    adb(serial, ["shell", "svc", "wifi", "enable"]);
    adb(serial, ["shell", "svc", "data", "enable"]);
    invokeInstrumentation(
      serial,
      "restoreServerAcceptedAccountDeletionClearsPrivateState",
      fixture,
    );
    receipt = readReceipt(serial);
    assert.equal(receipt.acceptedAccountLocalCleared, true);
    assert.equal(receipt.acceptedAccountOutboxRestarted, false);
    assert.equal(
      operationalCount(
        `select 1 from public.api_requests where owner_id = ${
          sqlString(owner.id)
        }::uuid and request_id = ` +
          `${sqlString(receipt.accountPendingRequestId)}::uuid`,
      ),
      0,
      "server-accepted account deletion never accepted the old local outbox request",
    );
    const terminal = await waitForAccountCleanup(owner.id);
    assert.equal(terminal.state, "complete");
    assert.equal(
      operationalCount(
        `select 1 from auth.users where id = ${sqlString(owner.id)}::uuid`,
      ),
      0,
      "the real worker deleted the fixture Auth user",
    );
    assert.equal((await itemDetail(other, foreign.id)).status, 200);

    const missingEnvironment = {
      ...process.env,
      ANDROID_SERIAL: serial,
      SUPABASE_URL: "http://10.0.2.2:18021",
      SUPABASE_PUBLISHABLE_KEY: anonKey,
      GOOGLE_WEB_CLIENT_ID: "",
    };
    const missingConfigBuild = invokeGradle(
      [
        "-p",
        "android",
        ":app:connectedDebugAndroidTest",
        "--console=plain",
        "-PlocalBackendTests=true",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "-Pandroid.testInstrumentationRunnerArguments.class=" +
        "com.linkvault.app.DeletionIntegrationTest#missingGoogleConfigurationDefersDeletion",
      ],
      missingEnvironment,
      15 * 60_000,
    );
    assertProcessSucceeded(
      missingConfigBuild,
      "missing Google configuration deferral phase",
    );
    assert.equal((await itemDetail(other, foreign.id)).status, 200);

    console.log(
      "PASS real Android confirmation/cancel -> offline immutable DELETE -> force-stop -> accepted eviction/tombstone -> conflict review/new UUID -> provider deferral -> server deleting restore/local purge",
    );
    console.log(
      "COVERAGE The emulator uses real local email Auth and deliberately does not claim a successful production Google exchange; cryptographic Google proof coverage is in the Edge unit suite.",
    );
  } catch (error) {
    primaryFailure = error;
  }
} finally {
  const finalizationFailures = [];
  if (primaryFailure && serial) {
    try {
      screenshotRetained = retainFixtureScreenshot(serial) !== null;
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  try {
    await cleanupCreatedOwners();
  } catch (error) {
    finalizationFailures.push(error);
  }
  if (serial && originalWifi !== undefined && originalData !== undefined) {
    try {
      adb(serial, [
        "shell",
        "svc",
        "wifi",
        originalWifi === "1" ? "enable" : "disable",
      ]);
      adb(serial, [
        "shell",
        "svc",
        "data",
        originalData === "1" ? "enable" : "disable",
      ]);
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (restoreDefaultApk) {
    const restored = invokeGradle(
      ["-p", "android", ":app:assembleDebug", "--console=plain"],
      parentEnvironment,
      8 * 60_000,
    );
    try {
      assertProcessSucceeded(
        restored,
        "default parent-environment debug APK restore",
      );
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (!screenshotRetained && serial && finalizationFailures.length > 0) {
    try {
      screenshotRetained = retainFixtureScreenshot(serial) !== null;
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (finalizationFailures.length > 0) {
    primaryFailure = primaryFailure
      ? new AggregateError(
        [primaryFailure, ...finalizationFailures],
        "Deletion emulator integration and finalization failed",
      )
      : new AggregateError(
        finalizationFailures,
        "Deletion emulator integration finalization failed",
      );
  }
}

if (primaryFailure) throw primaryFailure;
