import assert from "node:assert/strict";
import { execFileSync, execSync, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";

const config = JSON.parse(execSync("npx --no-install supabase status -o json", {
  encoding: "utf8",
  stdio: ["ignore", "pipe", "pipe"],
}));
assert.equal(
  config.API_URL,
  "http://127.0.0.1:54321",
  "Local fixture runner only",
);
const fixtureEmail = `emulator-${randomUUID()}@example.test`;
const fixturePassword = `Local-emulator-${randomUUID()}`;
const fixtureUrl = `https://example.com/emulator-${randomUUID()}`;
let userId;
let accessToken;
const devices = execFileSync("adb", ["devices"], { encoding: "utf8" });
const emulator = devices.match(/^(emulator-\d+)\s+device$/m)?.[1];
assert.ok(emulator, "Start an Android emulator before this integration suite");

async function api(
  route,
  { method = "GET", token = config.SERVICE_ROLE_KEY, body, headers = {} } = {},
) {
  const response = await fetch(`${config.API_URL}${route}`, {
    method,
    headers: {
      apikey: config.ANON_KEY,
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      // The Gradle/device run can outlive the local gateway's keep-alive timeout.
      Connection: "close",
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : null };
}
try {
  const user = await api("/auth/v1/admin/users", {
    method: "POST",
    body: {
      email: fixtureEmail,
      password: fixturePassword,
      email_confirm: true,
    },
  });
  assert.equal(user.status, 200);
  userId = user.data.id;
  const approved = await api("/rest/v1/beta_members", {
    method: "POST",
    body: {
      owner_id: userId,
      enabled: true,
      approved_at: new Date().toISOString(),
    },
  });
  assert.equal(approved.status, 201);
  const session = await api("/auth/v1/token?grant_type=password", {
    token: config.ANON_KEY,
    method: "POST",
    body: { email: fixtureEmail, password: fixturePassword },
  });
  assert.equal(session.status, 200);
  accessToken = session.data.access_token;

  const gradle = process.platform === "win32"
    ? "android\\gradlew.bat"
    : "./android/gradlew";
  const args = [
    "-p",
    "android",
    ":app:connectedDebugAndroidTest",
    "--console=plain",
    "-PlocalBackendTests=true",
    "-Pandroid.testInstrumentationRunnerArguments.class=com.linkvault.app.LocalLibraryIntegrationTest",
    `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${fixtureEmail}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${fixturePassword}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureUrl=${fixtureUrl}`,
  ];
  const env = {
    ...process.env,
    ANDROID_SERIAL: emulator,
    SUPABASE_URL: "http://10.0.2.2:54321",
    SUPABASE_PUBLISHABLE_KEY: config.ANON_KEY,
    // No Google token is fabricated. This suite imports real local GoTrue sessions
    // via the test-only SDK client and does not exercise the Google provider.
    GOOGLE_WEB_CLIENT_ID: "local-instrumentation.apps.googleusercontent.com",
  };
  const result = process.platform === "win32"
    ? spawnSync(process.env.ComSpec || "cmd.exe", [
      "/d",
      "/s",
      "/c",
      [gradle, ...args].join(" "),
    ], { env, stdio: "inherit" })
    : spawnSync(gradle, args, { env, stdio: "inherit" });
  assert.equal(result.status, 0, "emulator integration test must pass");
  const items = await api("/functions/v1/library-api/v1/items", {
    token: accessToken,
  });
  assert.equal(items.status, 200);
  assert.equal(items.data.items.length, 1, "emulator created exactly one item");
  const detail = await api(
    `/functions/v1/library-api/v1/items/${items.data.items[0].id}`,
    { token: accessToken },
  );
  assert.equal(detail.status, 200);
  assert.equal(detail.data.url, fixtureUrl, "original URL persisted");
  assert.equal(
    detail.data.note,
    "에뮬레이터 실제 서버 저장 검증",
    "actual UI note persisted",
  );
  console.log(
    "PASS emulator UI -> real Auth session -> Edge API -> PostgreSQL -> reload",
  );
  console.log(
    "Google Credential Manager/provider exchange remains a separate verification.",
  );
} finally {
  if (userId) {
    const deleted = await api(`/auth/v1/admin/users/${userId}`, {
      method: "DELETE",
    });
    assert.equal(deleted.status, 200, "remove only this runner fixture user");
  }
}
