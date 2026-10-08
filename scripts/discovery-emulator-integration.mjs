import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const {
  request,
  user,
  approve,
  bootstrap,
  cleanup,
  serviceKey,
  anonKey,
} = localBackendFixture("discovery-emulator");

const parentEnvironment = { ...process.env };
const workerTerminalStates = new Set(["succeeded", "failed", "cancelled"]);
const pause = (milliseconds) =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

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
      `ANDROID_SERIAL ${requested} is not a running emulator`,
    );
    return requested;
  }
  const selected = running.values().next().value;
  assert.ok(selected, "Start an Android emulator before this integration suite");
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

function assertGradleSucceeded(result, label) {
  assert.equal(
    result.error,
    undefined,
    `${label} did not finish within its bounded process run`,
  );
  assert.equal(result.signal, null, `${label} must not be terminated by a signal`);
  assert.equal(result.status, 0, `${label} must pass`);
}

async function createItem(account, body) {
  const response = await request("/functions/v1/library-api/v1/items", {
    token: account.token,
    method: "POST",
    body,
    headers: { "X-Request-Id": randomUUID() },
  });
  assert.equal(response.status, 201, "seed a new discovery fixture through the real API");
  assert.match(response.body.item.id, /^[0-9a-f-]{36}$/i);
  return response.body.item.id;
}

async function waitForDurableWorkerTerminal(ownerId, itemIds) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const response = await request(
      `/rest/v1/processing_jobs?owner_id=eq.${ownerId}` +
        `&item_id=in.(${itemIds.join(",")})&kind=eq.classify` +
        "&select=id,item_id,state,target_revision,created_at" +
        "&order=created_at.desc,id.desc",
      { token: serviceKey },
    );
    assert.equal(response.status, 200, "read durable classification jobs");
    const latestByItem = new Map();
    for (const job of response.body) {
      if (!latestByItem.has(job.item_id)) latestByItem.set(job.item_id, job);
    }
    if (
      itemIds.every((itemId) =>
        workerTerminalStates.has(latestByItem.get(itemId)?.state)
      )
    ) {
      const terminal = itemIds.map((itemId) => latestByItem.get(itemId));
      assert.ok(
        terminal.every((job) => job.state === "succeeded"),
        "each latest durable classification job must succeed",
      );
      return terminal;
    }
    await pause(500);
  }
  assert.fail("classification jobs did not reach durable terminal states within 60 seconds");
}

async function oneServiceRow(table, filters, select) {
  const response = await request(
    `/rest/v1/${table}?${filters}&select=${select}`,
    { token: serviceKey },
  );
  assert.equal(response.status, 200, `read ${table} verification row`);
  assert.equal(response.body.length, 1, `expected exactly one ${table} verification row`);
  return response.body[0];
}

async function assertCurrentDerivedRevisions(ownerId, itemId) {
  const item = await oneServiceRow(
    "items",
    `owner_id=eq.${ownerId}&id=eq.${itemId}`,
    "id,text_revision",
  );
  const search = await oneServiceRow(
    "item_search",
    `owner_id=eq.${ownerId}&item_id=eq.${itemId}`,
    "item_id,text_revision,search_version,normalized_fields,cue_state",
  );
  const classification = await oneServiceRow(
    "item_classification",
    `owner_id=eq.${ownerId}&item_id=eq.${itemId}`,
    "item_id,target_revision,rules_version,state",
  );
  assert.equal(search.text_revision, item.text_revision, "search revision is current");
  assert.equal(
    classification.target_revision,
    item.text_revision,
    "classification revision is current",
  );
  assert.equal(search.search_version, "search-v2.0.0");
  assert.equal(classification.rules_version, "rules-v2.0.0");
  assert.notEqual(classification.state, "pending");
  return { item, search, classification };
}

let primaryFailure;
let restoreDefaultApk = false;
try {
  try {
    const serial = selectRunningEmulator();
    const account = await user();
    await approve(account);
    const bootstrapped = await bootstrap(account);
    assert.equal(bootstrapped.status, 200, "bootstrap the approved account before app launch");

    const literalItemId = await createItem(account, {
      url: `https://example.com/discovery-literal-${randomUUID()}`,
      title: "카톡 프사 안내",
    });
    const aliasItemId = await createItem(account, {
      url: `https://example.com/discovery-alias-${randomUUID()}`,
      title: "카카오톡 프로필 사진 엑셀",
    });
    const cueItemId = await createItem(account, {
      url: `https://example.com/discovery-cue-${randomUUID()}`,
    });

    await waitForDurableWorkerTerminal(account.id, [literalItemId, aliasItemId]);

    const instrumentationArguments = [
      "-p",
      "android",
      ":app:connectedDebugAndroidTest",
      "--console=plain",
      "-PlocalBackendTests=true",
      "-Pandroid.testInstrumentationRunnerArguments.class=com.linkvault.app.DiscoveryIntegrationTest",
      `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${account.email}`,
      `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${account.password}`,
      `-Pandroid.testInstrumentationRunnerArguments.literalItemId=${literalItemId}`,
      `-Pandroid.testInstrumentationRunnerArguments.aliasItemId=${aliasItemId}`,
      `-Pandroid.testInstrumentationRunnerArguments.cueItemId=${cueItemId}`,
    ];
    const instrumentationEnvironment = {
      ...process.env,
      ANDROID_SERIAL: serial,
      SUPABASE_URL: "http://10.0.2.2:18021",
      SUPABASE_PUBLISHABLE_KEY: anonKey,
      // This suite imports a real local GoTrue email session. It never
      // fabricates a Google token or claims to cover the Google provider.
      GOOGLE_WEB_CLIENT_ID: "local-instrumentation.apps.googleusercontent.com",
    };
    restoreDefaultApk = true;
    const instrumentation = invokeGradle(
      instrumentationArguments,
      instrumentationEnvironment,
      12 * 60_000,
    );
    assertGradleSucceeded(instrumentation, "discovery emulator integration test");

    await waitForDurableWorkerTerminal(account.id, [aliasItemId, cueItemId]);

    const categories = await request(
      `/rest/v1/categories?owner_id=eq.${account.id}` +
        "&select=id,name,normalized_name,kind,system_code",
      { token: serviceKey },
    );
    assert.equal(categories.status, 200, "read fixture categories for host verification");
    const customCategories = categories.body.filter((category) =>
      category.kind === "custom" && category.name === "에뮬레이터분류"
    );
    assert.equal(customCategories.length, 1, "UI created exactly one custom category");
    const customCategory = customCategories[0];
    const workCategory = categories.body.find((category) =>
      category.kind === "system" && category.system_code === "work"
    );
    assert.ok(workCategory, "bootstrap includes the work system category");

    const aliasDetail = await request(
      `/functions/v1/library-api/v1/items/${aliasItemId}`,
      { token: account.token },
    );
    assert.equal(aliasDetail.status, 200);
    assert.equal(aliasDetail.body.classification_state, "automatic");
    assert.equal(aliasDetail.body.manual_override, false);
    const aliasCategoryIds = new Set(
      aliasDetail.body.category_refs.map((category) => category.id),
    );
    assert.ok(aliasCategoryIds.has(customCategory.id), "custom category survives reclassification");
    assert.ok(aliasCategoryIds.has(workCategory.id), "automatic work category is reapplied");

    const aliasAssignments = await request(
      `/rest/v1/item_categories?owner_id=eq.${account.id}` +
        `&item_id=eq.${aliasItemId}&select=category_id,origin`,
      { token: serviceKey },
    );
    assert.equal(aliasAssignments.status, 200);
    assert.ok(
      aliasAssignments.body.some((row) =>
        row.category_id === customCategory.id && row.origin === "manual"
      ),
      "custom assignment remains manual",
    );
    assert.ok(
      aliasAssignments.body.some((row) =>
        row.category_id === workCategory.id && row.origin === "auto"
      ),
      "work assignment is a completed automatic result",
    );

    const cueDetail = await request(
      `/functions/v1/library-api/v1/items/${cueItemId}`,
      { token: account.token },
    );
    assert.equal(cueDetail.status, 200);
    assert.equal(cueDetail.body.note, "메모", "UI edit reached the host database");
    assert.equal(cueDetail.body.cue_state, "limited");
    assert.equal(
      cueDetail.body.cue_prompt_dismissed,
      false,
      "a new text revision re-enables the cue prompt",
    );
    const cueControls = await oneServiceRow(
      "item_category_controls",
      `owner_id=eq.${account.id}&item_id=eq.${cueItemId}`,
      "item_id,cue_dismissed_revision,manual_override",
    );
    assert.notEqual(
      cueControls.cue_dismissed_revision,
      cueDetail.body.text_revision,
      "the old cue dismissal is not current after the memo revision",
    );

    const literalDerived = await assertCurrentDerivedRevisions(account.id, literalItemId);
    const aliasDerived = await assertCurrentDerivedRevisions(account.id, aliasItemId);
    const cueDerived = await assertCurrentDerivedRevisions(account.id, cueItemId);
    assert.match(
      aliasDerived.search.normalized_fields.categories,
      /업무·학습/,
      "automatic category is present in the current normalized search row",
    );
    assert.match(
      aliasDerived.search.normalized_fields.categories,
      /에뮬레이터분류/,
      "preserved custom category is present in the current normalized search row",
    );
    assert.equal(
      cueDerived.search.normalized_fields.note,
      "메모",
      "the current search revision contains the edited note",
    );
    assert.equal(literalDerived.classification.state, "unclassified");

    console.log(
      "PASS real M3 UI search/alias/category/reclassify/cue revision flow -> Edge API -> durable worker -> PostgreSQL",
    );
    console.log(
      "Google provider exchange and physical S23 compatibility remain separate verification.",
    );
  } catch (error) {
    primaryFailure = error;
  }
} finally {
  const finalizationFailures = [];
  try {
    await cleanup();
  } catch (error) {
    finalizationFailures.push(error);
  }
  if (restoreDefaultApk) {
    const restored = invokeGradle(
      ["-p", "android", ":app:assembleDebug", "--console=plain"],
      parentEnvironment,
      8 * 60_000,
    );
    try {
      assertGradleSucceeded(restored, "default parent-environment debug APK restore");
    } catch (error) {
      finalizationFailures.push(error);
    }
  }
  if (finalizationFailures.length > 0) {
    primaryFailure = primaryFailure
      ? new AggregateError(
        [primaryFailure, ...finalizationFailures],
        "Discovery integration run and finalization failed",
      )
      : new AggregateError(finalizationFailures, "Discovery integration finalization failed");
  }
}
if (primaryFailure) throw primaryFailure;
