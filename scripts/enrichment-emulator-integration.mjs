import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const {
  request,
  user,
  approve,
  bootstrap,
  cleanup,
  settleClassification,
  serviceKey,
  anonKey,
} = localBackendFixture("enrichment-emulator");

const API_PREFIX = "/functions/v1/library-api/v1";
const STORAGE_BUCKET = "library-images";
const MAX_IMAGE_BYTES = 2_000_000;
const parentEnvironment = { ...process.env };
const remoteScreenshot = "/data/local/tmp/link-vault-enrichment.png";

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
      "ANDROID_SERIAL must select an emulator, never a physical device",
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
    : spawnSync(gradle, args, {
      env: environment,
      stdio: "inherit",
      timeout,
    });
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
    `com.linkvault.app.EnrichmentIntegrationTest#${method}`,
    "-e",
    "fixtureOwnerId",
    fixture.ownerId,
    "-e",
    "fixtureItemId",
    fixture.itemId,
    "-e",
    "fixtureUrl",
    fixture.url,
    "com.linkvault.app.test/androidx.test.runner.AndroidJUnitRunner",
  ], {
    encoding: "utf8",
    timeout,
    maxBuffer: 4 * 1024 * 1024,
  });
  assertProcessSucceeded(result, `enrichment instrumentation phase ${method}`);
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
    "files/enrichment-fixture-receipt.json",
  ]);
  return JSON.parse(raw);
}

function normalizeOcr(value) {
  return String(value ?? "").toUpperCase().replace(/\s+/g, "");
}

function assertExpectedOcr(value, koreanWord, label) {
  assert.ok(
    normalizeOcr(value).includes("LINKVAULT"),
    `${label} includes actual Latin OCR`,
  );
  assert.match(
    String(value ?? ""),
    new RegExp(koreanWord),
    `${label} includes actual Korean OCR`,
  );
}

async function createItem(account, body) {
  const response = await request(`${API_PREFIX}/items`, {
    token: account.token,
    method: "POST",
    body,
    headers: { "X-Request-Id": randomUUID() },
  });
  assert.equal(
    response.status,
    201,
    "seed the owned fixture through the real API",
  );
  assert.match(response.body.item.id, /^[0-9a-f-]{36}$/i);
  return response.body.item;
}

async function itemDetail(account, itemId) {
  const response = await request(`${API_PREFIX}/items/${itemId}`, {
    token: account.token,
  });
  assert.equal(response.status, 200, "read the real fixture item");
  return response.body;
}

async function searchItems(account, query) {
  const parameters = new URLSearchParams({ q: query, limit: "50" });
  const response = await request(`${API_PREFIX}/items?${parameters}`, {
    token: account.token,
  });
  assert.equal(response.status, 200, "search the real owner projection");
  return response.body.items;
}

async function proxyContent(account, itemId, assetId) {
  return request(`${API_PREFIX}/items/${itemId}/assets/${assetId}/content`, {
    token: account.token,
    responseType: "bytes",
  });
}

async function oneOwnerRow(table, ownerId, filters, select) {
  const parameters = new URLSearchParams({
    owner_id: `eq.${ownerId}`,
    ...filters,
    select,
  });
  const response = await request(`/rest/v1/${table}?${parameters}`, {
    token: serviceKey,
  });
  assert.equal(response.status, 200, `read ${table} verification state`);
  assert.equal(
    response.body.length,
    1,
    `expected one ${table} verification row`,
  );
  return response.body[0];
}

async function assertCurrentClassification(account, itemId) {
  const settled = await settleClassification(account, itemId);
  assert.equal(
    settled.status,
    200,
    "settle classification at the current text revision",
  );
  const detail = settled.body;
  const search = await oneOwnerRow(
    "item_search",
    account.id,
    { item_id: `eq.${itemId}` },
    "item_id,text_revision,search_version,normalized_fields",
  );
  const classification = await oneOwnerRow(
    "item_classification",
    account.id,
    { item_id: `eq.${itemId}` },
    "item_id,target_revision,rules_version,state",
  );
  const jobResponse = await request(
    "/rest/v1/processing_jobs?" + new URLSearchParams({
      owner_id: `eq.${account.id}`,
      item_id: `eq.${itemId}`,
      kind: "eq.classify",
      target_revision: `eq.${detail.text_revision}`,
      select: "id,state,target_revision",
      order: "created_at.desc,id.desc",
      limit: "1",
    }),
    { token: serviceKey },
  );
  assert.equal(jobResponse.status, 200, "read current classification job");
  assert.equal(
    jobResponse.body.length,
    1,
    "current revision has a durable classification job",
  );
  assert.equal(
    jobResponse.body[0].state,
    "succeeded",
    "current classification job succeeded",
  );
  assert.equal(jobResponse.body[0].target_revision, detail.text_revision);
  assert.equal(
    search.text_revision,
    detail.text_revision,
    "search projection is current",
  );
  assert.equal(
    classification.target_revision,
    detail.text_revision,
    "classification is current",
  );
  assert.equal(search.search_version, "search-v2.0.0");
  assert.equal(classification.rules_version, "rules-v2.0.0");
  return detail;
}

async function waitForAssetCleanup(ownerId) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const parameters = new URLSearchParams({
      owner_id: `eq.${ownerId}`,
      select: "id",
    });
    const assets = await request(`/rest/v1/assets?${parameters}`, {
      token: serviceKey,
    });
    assert.equal(
      assets.status,
      200,
      "read only the fixture owner's asset rows",
    );
    if (assets.body.length === 0) return;
    const worker = await request(`${API_PREFIX}/internal/assets-cleanup`, {
      token: serviceKey,
      method: "POST",
      body: { limit: 10 },
    });
    assert.equal(worker.status, 200, "run the real asset cleanup worker");
    await pause(250);
  }
  assert.fail(
    "fixture asset cleanup did not finish within its bounded polling window",
  );
}

async function listOwnerStorageObjects(ownerId) {
  const objects = [];
  const pendingPrefixes = [ownerId];
  const visited = new Set();
  while (pendingPrefixes.length > 0) {
    const prefix = pendingPrefixes.pop();
    if (visited.has(prefix)) continue;
    visited.add(prefix);
    assert.ok(
      prefix === ownerId || prefix.startsWith(`${ownerId}/`),
      "storage listing stays inside the created owner prefix",
    );
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
        "list only the created owner's storage prefix",
      );
      assert.ok(Array.isArray(listed.body), "storage listing is an array");
      for (const entry of listed.body) {
        assert.match(
          entry.name,
          /^[^/]+$/,
          "storage list entry is a direct child",
        );
        const path = `${prefix}/${entry.name}`;
        if (entry.id) objects.push(path);
        else pendingPrefixes.push(path);
      }
      if (listed.body.length < 100) break;
      offset += listed.body.length;
    }
  }
  assert.ok(
    objects.every((path) => path.startsWith(`${ownerId}/`)),
    "every final storage path belongs to the created owner",
  );
  return objects;
}

async function removeOwnerStorageObjects(ownerId) {
  const paths = await listOwnerStorageObjects(ownerId);
  if (paths.length > 0) {
    const removed = await request(`/storage/v1/object/${STORAGE_BUCKET}`, {
      token: serviceKey,
      method: "DELETE",
      body: { prefixes: paths },
    });
    assert.equal(removed.status, 200, "remove only this run's storage objects");
  }
  assert.deepEqual(
    await listOwnerStorageObjects(ownerId),
    [],
    "created owner storage is empty before Auth cleanup",
  );
  const assets = await request(`/rest/v1/assets?owner_id=eq.${ownerId}`, {
    token: serviceKey,
    method: "DELETE",
  });
  assert.equal(
    assets.status,
    204,
    "remove only physically cleared fixture asset rows",
  );
}

function retainFailureScreenshot(serial) {
  const outputDirectory = resolve(
    "android",
    "app",
    "build",
    "reports",
    "enrichment-emulator",
  );
  const outputFile = resolve(outputDirectory, "link-vault-enrichment.png");
  mkdirSync(outputDirectory, { recursive: true });
  try {
    try {
      adb(serial, ["shell", "test", "-s", remoteScreenshot]);
    } catch {
      adb(serial, ["shell", "screencap", "-p", remoteScreenshot]);
    }
    adb(serial, ["pull", remoteScreenshot, outputFile]);
    assert.ok(
      existsSync(outputFile) && statSync(outputFile).size > 0,
      "failure screenshot is retained on the host",
    );
    return outputFile;
  } finally {
    try {
      adb(serial, ["shell", "rm", "-f", remoteScreenshot]);
    } catch {
      // Preserve the primary integration failure and the already-pulled host artifact.
    }
  }
}

let primaryFailure;
let account;
let serial;
let originalWifi;
let originalData;
let restoreDefaultApk = false;
let screenshotRetained = false;
try {
  try {
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

    account = await user();
    await approve(account);
    const bootstrapped = await bootstrap(account);
    assert.equal(
      bootstrapped.status,
      200,
      "bootstrap the approved fixture account",
    );

    const fixtureUrl = `https://example.test/enrichment-${randomUUID()}`;
    const created = await createItem(account, {
      url: fixtureUrl,
      title: "첨부 통합 검증",
    });
    const initial = await settleClassification(account, created.id);
    assert.equal(
      initial.status,
      200,
      "settle the seeded current classification version",
    );
    assert.notEqual(initial.body.classification_state, "pending");

    const fixture = {
      ownerId: account.id,
      itemId: created.id,
      url: fixtureUrl,
    };
    const instrumentationEnvironment = {
      ...process.env,
      ANDROID_SERIAL: serial,
      SUPABASE_URL: "http://10.0.2.2:18021",
      SUPABASE_PUBLISHABLE_KEY: anonKey,
      GOOGLE_WEB_CLIENT_ID: "local-instrumentation.apps.googleusercontent.com",
    };
    const queueArguments = [
      "-p",
      "android",
      ":app:connectedDebugAndroidTest",
      "--console=plain",
      "-PlocalBackendTests=true",
      "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
      "-Pandroid.testInstrumentationRunnerArguments.class=" +
      "com.linkvault.app.EnrichmentIntegrationTest#queueSharedImageOffline",
      `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${account.email}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${account.password}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixtureOwnerId=${account.id}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixtureItemId=${created.id}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixtureUrl=${fixtureUrl}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixtureVersion=${initial.body.version}`,
    ];
    restoreDefaultApk = true;
    const queued = invokeGradle(
      queueArguments,
      instrumentationEnvironment,
      15 * 60_000,
    );
    assertProcessSucceeded(queued, "offline shared-image queue phase");

    let receipt = readReceipt(serial);
    assert.equal(receipt.ownerId, account.id);
    assert.equal(receipt.itemId, created.id);
    assert.equal(
      receipt.initialBaseVersion,
      initial.body.version,
      "UI captured the current item version",
    );
    assert.ok(
      receipt.initialPreparedBytes > 0 &&
        receipt.initialPreparedBytes <= MAX_IMAGE_BYTES,
    );
    assertExpectedOcr(receipt.initialOcrText, "서울", "offline prepared row");
    const offlineDetail = await itemDetail(account, created.id);
    assert.equal(
      offlineDetail.active_asset,
      null,
      "offline UI never claims server completion",
    );
    assert.equal(offlineDetail.version, initial.body.version);

    adb(serial, ["shell", "am", "force-stop", "com.linkvault.app"]);
    invokeInstrumentation(serial, "restoreUploadSearchAndReplace", fixture);

    receipt = readReceipt(serial);
    assert.match(receipt.initialAssetId, /^[0-9a-f-]{36}$/i);
    assert.match(receipt.replacementAssetId, /^[0-9a-f-]{36}$/i);
    assert.notEqual(receipt.replacementAssetId, receipt.initialAssetId);
    assert.ok(
      receipt.replacementTextRevision > receipt.initialTextRevision,
      "replacement increases the server text revision",
    );
    assertExpectedOcr(
      receipt.replacementOcrText,
      "제주",
      "replacement prepared row",
    );

    const replacementDetail = await assertCurrentClassification(
      account,
      created.id,
    );
    const active = replacementDetail.active_asset;
    assert.ok(active, "replacement is the real current active asset");
    assert.equal(active.id, receipt.replacementAssetId);
    assert.notEqual(active.id, receipt.initialAssetId);
    assert.equal(active.mime_type, "image/png");
    assert.equal(active.width, 1_600);
    assert.equal(active.height, 800);
    assert.ok(active.byte_size > 0 && active.byte_size <= MAX_IMAGE_BYTES);
    assert.equal(replacementDetail.ocr_state, "ready");
    assertExpectedOcr(active.ocr_text, "제주", "server active asset");
    assert.ok(normalizeOcr(active.ocr_text).includes("LINKVAULT"));
    assert.ok(
      !String(active.ocr_text).includes("서울"),
      "old OCR is absent from the current asset",
    );

    const activeBytes = await proxyContent(account, created.id, active.id);
    assert.equal(
      activeBytes.status,
      200,
      "owner reads the current asset through the private proxy",
    );
    assert.equal(activeBytes.body.byteLength, active.byte_size);
    assert.deepEqual(
      [...activeBytes.body.slice(0, 8)],
      [137, 80, 78, 71, 13, 10, 26, 10],
      "private proxy returned the verified PNG bytes",
    );
    assert.equal(
      (await proxyContent(account, created.id, receipt.initialAssetId)).status,
      404,
      "replaced asset is immediately invisible through the private proxy",
    );
    assert.ok(
      (await searchItems(account, "LINK 제주")).some((item) =>
        item.id === created.id
      ),
      "current Latin and Hangul OCR words are searchable",
    );
    assert.ok(
      !(await searchItems(account, "서울")).some((item) =>
        item.id === created.id
      ),
      "old OCR words are not searchable after replacement",
    );

    invokeInstrumentation(
      serial,
      "deleteActiveImageThroughInlineConfirmation",
      fixture,
    );
    receipt = readReceipt(serial);
    assert.equal(receipt.deleteConfirmed, true);
    const deletedDetail = await assertCurrentClassification(
      account,
      created.id,
    );
    assert.equal(deletedDetail.active_asset, null);
    assert.equal(deletedDetail.has_attachment, false);
    assert.equal(deletedDetail.ocr_state, "not_requested");
    assert.ok(
      deletedDetail.text_revision > receipt.replacementTextRevision,
      "delete increases the server text revision",
    );
    assert.equal(
      (await proxyContent(account, created.id, receipt.initialAssetId)).status,
      404,
      "old asset remains hidden after delete",
    );
    assert.equal(
      (await proxyContent(account, created.id, receipt.replacementAssetId))
        .status,
      404,
      "deleted active asset is hidden through the private proxy",
    );
    assert.ok(
      !(await searchItems(account, "LINK")).some((item) =>
        item.id === created.id
      ) &&
        !(await searchItems(account, "제주")).some((item) =>
          item.id === created.id
        ),
      "deleted OCR text is absent from current search",
    );

    await waitForAssetCleanup(account.id);
    const usage = await oneOwnerRow(
      "library_usage",
      account.id,
      {},
      "owner_id,used_image_bytes,reserved_image_bytes",
    );
    assert.equal(usage.used_image_bytes, 0);
    assert.equal(usage.reserved_image_bytes, 0);

    console.log(
      "PASS real shared content URI -> explicit target/action -> offline Room/file -> force-stop -> upload/OCR/search -> failed-state OCR retry -> replacement -> inline delete -> private cleanup",
    );
    console.log(
      "COVERAGE metadata UI is independent; real metadata and owner isolation are verified by the backend suite. Google provider exchange and physical-device acceptance remain unverified.",
    );
  } catch (error) {
    primaryFailure = error;
  }
} finally {
  const finalizationFailures = [];
  if (primaryFailure && serial) {
    try {
      retainFailureScreenshot(serial);
      screenshotRetained = true;
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (account) {
    try {
      await removeOwnerStorageObjects(account.id);
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  try {
    await cleanup();
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
      retainFailureScreenshot(serial);
      screenshotRetained = true;
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (finalizationFailures.length > 0) {
    primaryFailure = primaryFailure
      ? new AggregateError(
        [primaryFailure, ...finalizationFailures],
        "Enrichment emulator integration and finalization failed",
      )
      : new AggregateError(
        finalizationFailures,
        "Enrichment emulator integration finalization failed",
      );
  }
}
if (primaryFailure) throw primaryFailure;
