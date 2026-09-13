import assert from "node:assert/strict";
import { execFileSync, execSync, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";

const config = JSON.parse(execSync("npx --no-install supabase status -o json", {
  encoding: "utf8",
  stdio: ["ignore", "pipe", "pipe"],
}));
assert.equal(config.API_URL, "http://127.0.0.1:54321");
const devices = execFileSync("adb", ["devices"], { encoding: "utf8" });
const serial = devices.match(/^(emulator-\d+)\s+device$/m)?.[1];
assert.ok(serial, "An emulator is required; physical devices are not targeted");
const email = `offline-${randomUUID()}@example.test`;
const password = `Local-offline-${randomUUID()}`;
const url = `https://example.com/offline-${randomUUID()}`;
let userId;
let token;
const adb = (...args) =>
  execFileSync("adb", ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: 90000,
  });
async function screenXml() {
  let result = "";
  for (let attempt = 0; attempt < 3; attempt++) {
    result = adb(
      "shell",
      "uiautomator",
      "dump",
      "/data/local/tmp/link-vault-ui.xml",
    );
    if (result.includes("dumped to:")) {
      return adb("shell", "cat", "/data/local/tmp/link-vault-ui.xml");
    }
    console.log("Waiting for an idle emulator UI hierarchy...");
    await pause(2000);
  }
  throw new Error(`UI hierarchy capture failed: ${result}`);
}
function tapText(xml, text) {
  const node = xml.match(
    new RegExp(
      `text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`,
    ),
  );
  assert.ok(node, `Visible control missing: ${text}`);
  adb(
    "shell",
    "input",
    "tap",
    String(Math.floor((+node[1] + +node[3]) / 2)),
    String(Math.floor((+node[2] + +node[4]) / 2)),
  );
}
async function api(
  route,
  {
    method = "GET",
    bearer = token ?? config.SERVICE_ROLE_KEY,
    body,
    headers = {},
  } = {},
) {
  const response = await fetch(`${config.API_URL}${route}`, {
    method,
    headers: {
      apikey: config.ANON_KEY,
      Authorization: `Bearer ${bearer}`,
      "Content-Type": "application/json",
      Connection: "close",
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : null };
}
async function signIn() {
  const result = await api("/auth/v1/token?grant_type=password", {
    method: "POST",
    bearer: config.ANON_KEY,
    body: { email, password },
  });
  assert.equal(result.status, 200);
  token = result.data.access_token;
}
async function listItems() {
  const result = await api("/functions/v1/library-api/v1/items");
  assert.equal(result.status, 200);
  return result.data.items;
}
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const originallyWifi = adb("shell", "settings", "get", "global", "wifi_on")
  .trim();
const originallyData = adb("shell", "settings", "get", "global", "mobile_data")
  .trim();
try {
  const created = await api("/auth/v1/admin/users", {
    method: "POST",
    body: { email, password, email_confirm: true },
  });
  assert.equal(created.status, 200);
  userId = created.data.id;
  const approved = await api("/rest/v1/beta_members", {
    method: "POST",
    bearer: config.SERVICE_ROLE_KEY,
    body: {
      owner_id: userId,
      enabled: true,
      approved_at: new Date().toISOString(),
    },
  });
  assert.equal(approved.status, 201);
  await signIn();
  const bootstrap = await api("/functions/v1/library-api/v1/bootstrap", {
    method: "POST",
    body: {},
    headers: { "X-Request-Id": randomUUID() },
  });
  assert.equal(bootstrap.status, 200);
  const seeded = await api("/functions/v1/library-api/v1/items", {
    method: "POST",
    body: {
      url: "https://example.com/online-cache",
      title: "온라인 캐시 기준",
    },
    headers: { "X-Request-Id": randomUUID() },
  });
  assert.equal(seeded.status, 201);
  const gradle = process.platform === "win32"
    ? "android\\gradlew.bat"
    : "./android/gradlew";
  const args = [
    "-p",
    "android",
    ":app:connectedDebugAndroidTest",
    "--console=plain",
    "-PlocalBackendTests=true",
    // UTP normally uninstalls both APKs; the next phase needs the same app data.
    "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
    "-Pandroid.testInstrumentationRunnerArguments.class=com.linkvault.app.OfflineLibraryIntegrationTest#queueWhileOffline",
    `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${email}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${password}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureUrl=${url}`,
  ];
  const env = {
    ...process.env,
    ANDROID_SERIAL: serial,
    SUPABASE_URL: "http://10.0.2.2:54321",
    SUPABASE_PUBLISHABLE_KEY: config.ANON_KEY,
    GOOGLE_WEB_CLIENT_ID: "local-instrumentation.apps.googleusercontent.com",
  };
  const build = process.platform === "win32"
    ? spawnSync(process.env.ComSpec || "cmd.exe", [
      "/d",
      "/s",
      "/c",
      [gradle, ...args].join(" "),
    ], { env, stdio: "inherit" })
    : spawnSync(gradle, args, { env, stdio: "inherit" });
  assert.equal(build.status, 0, "offline queue phase must pass");
  const receipt = JSON.parse(
    adb(
      "exec-out",
      "run-as",
      "com.linkvault.app",
      "cat",
      "files/m2-fixture-receipt.json",
    ),
  );
  assert.equal(receipt.ownerId, userId);
  assert.equal(
    (await listItems()).length,
    1,
    "offline save must not claim server completion",
  );
  adb("shell", "am", "force-stop", "com.linkvault.app");
  adb("shell", "am", "start", "-W", "-n", "com.linkvault.app/.MainActivity");
  tapText(await screenXml(), "회원 계정");
  const recoveryDeadline = Date.now() + 45000;
  let recoveryScreen = "";
  while (Date.now() < recoveryDeadline) {
    recoveryScreen = await screenXml();
    if (recoveryScreen.includes("로그인 세션을 새로 고치지 못했어요")) break;
    console.log(
      "Waiting for actual expired-session recovery failure while offline...",
    );
    await pause(2000);
  }
  assert.ok(
    recoveryScreen.includes("로그인 세션을 새로 고치지 못했어요"),
    "real app must enter recoverable session failure before reconnection",
  );
  adb("shell", "svc", "wifi", "enable");
  adb("shell", "svc", "data", "enable");
  let saved;
  const deadline = Date.now() + 150000;
  while (Date.now() < deadline) {
    const items = await listItems();
    saved = items.find((item) => item.url === url);
    if (saved) break;
    console.log(
      "Waiting for persisted WorkManager upload after process restart...",
    );
    await pause(2000);
  }
  assert.ok(
    saved,
    "restarted app must deliver the persisted pending operation",
  );
  const all = await listItems();
  assert.equal(
    all.length,
    2,
    "exactly one queued item is created after restart",
  );
  const recorded = await api(
    `/rest/v1/api_requests?owner_id=eq.${userId}&request_id=eq.${receipt.requestId}&select=request_id`,
    {
      bearer: config.SERVICE_ROLE_KEY,
    },
  );
  assert.equal(recorded.status, 200);
  assert.equal(
    recorded.data.length,
    1,
    "worker reused the original persisted request ID",
  );
  const result = adb(
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "class",
    "com.linkvault.app.OfflineLibraryIntegrationTest#verifyRestoredSaveAndEdit",
    "-e",
    "fixtureItemId",
    saved.id,
    "com.linkvault.app.test/androidx.test.runner.AndroidJUnitRunner",
  );
  assert.match(
    result,
    /OK \(1 test\)/,
    "restored detail and edit UI phase must pass",
  );
  assert.doesNotMatch(result, /FAILURES!!!/);
  await signIn();
  const detail = await api(`/functions/v1/library-api/v1/items/${saved.id}`);
  assert.equal(detail.status, 200);
  assert.equal(detail.data.note, "재시작 후 편집 검증");
  assert.equal(
    (await listItems()).length,
    2,
    "confirmed logout never uploads the discarded offline request",
  );
  console.log(
    "PASS offline Room queue -> force-stop -> real refresh failure -> reconnect -> original-ID write -> UI edit -> confirmed local cleanup",
  );
} finally {
  adb("shell", "svc", "wifi", originallyWifi === "1" ? "enable" : "disable");
  adb("shell", "svc", "data", originallyData === "1" ? "enable" : "disable");
  adb("shell", "rm", "-f", "/data/local/tmp/link-vault-ui.xml");
  if (userId) {
    const removed = await api(`/auth/v1/admin/users/${userId}`, {
      method: "DELETE",
      bearer: config.SERVICE_ROLE_KEY,
    });
    assert.equal(removed.status, 200, "only this runner fixture is removed");
  }
}
