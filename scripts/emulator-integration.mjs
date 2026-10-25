import assert from "node:assert/strict";
import { execFileSync, execSync, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";

const config = JSON.parse(execSync("npx --no-install supabase status -o json", {
  encoding: "utf8",
  stdio: ["ignore", "pipe", "pipe"],
}));
assert.equal(
  config.API_URL,
  "http://127.0.0.1:18021",
  "Local fixture runner only",
);
const fixtureEmail = `emulator-${randomUUID()}@example.test`;
const fixturePassword = `Local-emulator-${randomUUID()}`;
const fixtureKind = process.env.LINK_VAULT_TEST_FIXTURE ?? "generic";
const verifyListDeletion = process.env.LINK_VAULT_TEST_DELETE === "1";
const verifyDetailEditing = process.env.LINK_VAULT_TEST_DETAIL_EDIT === "1";
const verifySourceBrowsing = process.env.LINK_VAULT_TEST_SOURCE_BROWSE === "1";
assert.ok(
  [verifyDetailEditing, verifyListDeletion, verifySourceBrowsing].filter(
    Boolean,
  ).length <= 1,
  "Choose one device scenario",
);
const verifyUncachedDeletion =
  process.env.LINK_VAULT_TEST_DELETE_UNCACHED === "1";
assert.ok(
  !verifyUncachedDeletion || verifyListDeletion,
  "Uncached deletion requires delete verification",
);
const fixtures = {
  generic: {
    url: `https://example.com/?capture-fixture=${randomUUID()}`,
    title: "Example Domain",
    metadata: "partial",
    classification: "unclassified",
  },
  threads: {
    url: "https://www.threads.com/@nasa/post/DdXJKzbkqPg",
    title: "You've got mail: a postcard from Mars!",
    metadata: "ready",
    classification: "unclassified",
  },
  "instagram-travel": {
    url: "https://www.instagram.com/visitjeju.kr/p/DPDtncZEueq/",
    title: "가을에 제주여행 가고 싶은 사람",
    metadata: "ready",
    classification: "automatic",
  },
};
assert.ok(Object.hasOwn(fixtures, fixtureKind), "Unknown device test fixture");
const fixture = fixtures[fixtureKind];
const fixtureUrl = fixture.url;
let userId;
let accessToken;
const devices = execFileSync("adb", ["devices"], { encoding: "utf8" });
// Physical-device testing is explicitly opt-in; never select a user's tablet
// merely because it happens to be connected.
const requestedSerial = process.env.LINK_VAULT_TEST_DEVICE_SERIAL;
const serial = requestedSerial ??
  devices.match(/^(emulator-\d+)\s+device$/m)?.[1];
assert.ok(
  serial,
  "Start an emulator or explicitly set LINK_VAULT_TEST_DEVICE_SERIAL",
);
assert.ok(
  devices.split(/\r?\n/).some((line) => {
    const [id, state] = line.trim().split(/\s+/);
    return id === serial && state === "device";
  }),
  "The selected test device must be connected and authorized",
);
const physicalDevice = !serial.startsWith("emulator-");
if (physicalDevice) {
  const mappings = execFileSync("adb", ["-s", serial, "reverse", "--list"], {
    encoding: "utf8",
  });
  assert.ok(
    mappings.split(/\r?\n/).some((line) => {
      const fields = line.trim().split(/\s+/);
      return fields[1] === "tcp:18021" && fields[2] === "tcp:18021";
    }),
    "Physical tests require an explicit adb reverse tcp:18021 tcp:18021 mapping",
  );
}

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
  }).catch((error) => {
    throw new Error(
      `Local fixture request failed: ${method} ${route.split("?")[0]}`,
      { cause: error },
    );
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
  let seededItemId = "";
  if (verifyUncachedDeletion || verifyDetailEditing) {
    const bootstrap = await api("/functions/v1/library-api/v1/bootstrap", {
      token: accessToken,
      method: "POST",
      body: {},
      headers: { "X-Request-Id": randomUUID() },
    });
    assert.equal(bootstrap.status, 200);
    const seeded = await api("/functions/v1/library-api/v1/items", {
      token: accessToken,
      method: "POST",
      headers: { "X-Request-Id": randomUUID() },
      body: {
        url: `https://list-delete.invalid/${randomUUID()}`,
        title: verifyDetailEditing ? "상세 편집 검증" : "목록 삭제 검증",
        ...(verifyDetailEditing
          ? { note: "현재 저장된 내용\n두 번째 줄도 유지합니다." }
          : {}),
      },
    });
    assert.equal(seeded.status, 201);
    seededItemId = seeded.data.item.id;
    const classified = await api(
      "/functions/v1/library-api/v1/internal/classify",
      {
        method: "POST",
        body: { limit: 20 },
      },
    );
    assert.equal(classified.status, 200);
    const stable = await api(
      `/functions/v1/library-api/v1/items/${seededItemId}`,
      {
        token: accessToken,
      },
    );
    assert.equal(stable.status, 200);
    assert.notEqual(stable.data.classification_state, "pending");
  }

  const sourceFixtureIds = {};
  if (verifySourceBrowsing) {
    const bootstrap = await api("/functions/v1/library-api/v1/bootstrap", {
      token: accessToken,
      method: "POST",
      body: {},
      headers: { "X-Request-Id": randomUUID() },
    });
    assert.equal(bootstrap.status, 200);
    const sourceUrls = {
      instagram: `https://www.instagram.com/p/${randomUUID()}/`,
      threads: `https://www.threads.com/@source_fixture/post/${randomUUID()}`,
      naver_blog:
        `https://blog.naver.com/source_fixture/223000000000?case=${randomUUID()}`,
      other: `https://source-fixture.invalid/${randomUUID()}`,
    };
    for (const [source, url] of Object.entries(sourceUrls)) {
      const created = await api("/functions/v1/library-api/v1/items", {
        token: accessToken,
        method: "POST",
        headers: { "X-Request-Id": randomUUID() },
        body: { url, title: `출처 검증 ${source}` },
      });
      assert.equal(created.status, 201);
      assert.equal(created.data.item.source, source);
      sourceFixtureIds[source] = created.data.item.id;
    }
  }

  const gradle = process.platform === "win32"
    ? "android\\gradlew.bat"
    : "./android/gradlew";
  const args = [
    "-p",
    "android",
    ":app:connectedDebugAndroidTest",
    "--console=plain",
    "-PlocalBackendTests=true",
    "-PisolatedDeviceTests=true",
    "-Pandroid.testInstrumentationRunnerArguments.class=com.linkvault.app.LocalLibraryIntegrationTest",
    `-Pandroid.testInstrumentationRunnerArguments.fixtureEmail=${fixtureEmail}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixturePassword=${fixturePassword}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureUrl=${fixtureUrl}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureKind=${fixtureKind}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureDelete=${verifyListDeletion}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureDetailEdit=${verifyDetailEditing}`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureSourceIds=${
      Object.entries(sourceFixtureIds).map(([source, id]) => `${source}:${id}`)
        .join(",")
    }`,
    `-Pandroid.testInstrumentationRunnerArguments.fixtureSeededItemId=${seededItemId}`,
  ];
  const env = {
    ...process.env,
    ANDROID_SERIAL: serial,
    SUPABASE_URL: physicalDevice
      ? "http://127.0.0.1:18021"
      : "http://10.0.2.2:18021",
    SUPABASE_PUBLISHABLE_KEY: config.ANON_KEY,
    // No Google token is fabricated. This suite imports real local GoTrue sessions
    // via the test-only SDK client and does not exercise the Google provider.
    GOOGLE_WEB_CLIENT_ID: "local-instrumentation.apps.googleusercontent.com",
  };
  let diagnosticRequest;
  let lastDiagnostic;
  const diagnosticStarted = Date.now();
  const inspectProgress = async () => {
    try {
      if (physicalDevice) {
        const mappings = execFileSync("adb", [
          "-s",
          serial,
          "reverse",
          "--list",
        ], {
          encoding: "utf8",
          timeout: 5000,
          stdio: ["ignore", "pipe", "pipe"],
        });
        if (
          !mappings.split(/\r?\n/).some((line) => {
            const fields = line.trim().split(/\s+/);
            return fields[1] === "tcp:18021" && fields[2] === "tcp:18021";
          })
        ) {
          execFileSync("adb", [
            "-s",
            serial,
            "reverse",
            "tcp:18021",
            "tcp:18021",
          ], {
            timeout: 5000,
            stdio: ["ignore", "pipe", "pipe"],
          });
          console.log("DEVICE_FIXTURE_USB_TUNNEL_RESTORED");
        }
      }
      if (process.env.LINK_VAULT_TEST_DIAGNOSTICS !== "1") return;
      const items = await api("/functions/v1/library-api/v1/items", {
        token: accessToken,
      });
      const states = [];
      for (const item of items.data?.items ?? []) {
        const detail = await api(
          `/functions/v1/library-api/v1/items/${item.id}`,
          {
            token: accessToken,
          },
        );
        states.push({
          metadata: detail.data?.metadata_state,
          classification: detail.data?.classification_state,
          expectedTitlePresent:
            detail.data?.fetched_title?.includes(fixture.title) ?? false,
          error: detail.data?.extraction_meta?.error_code ?? null,
        });
      }
      const snapshot = JSON.stringify({ status: items.status, states });
      if (snapshot !== lastDiagnostic) {
        console.log(
          "DEVICE_FIXTURE_PROGRESS",
          Date.now() - diagnosticStarted,
          snapshot,
        );
        lastDiagnostic = snapshot;
      }
    } catch (error) {
      console.error("DEVICE_FIXTURE_PROGRESS_UNAVAILABLE", error.name);
    }
  };
  const monitor =
    physicalDevice || process.env.LINK_VAULT_TEST_DIAGNOSTICS === "1"
      ? setInterval(() => {
        if (!diagnosticRequest) {
          diagnosticRequest = inspectProgress().finally(() => {
            diagnosticRequest = undefined;
          });
        }
      }, 10000)
      : null;
  const child = process.platform === "win32"
    ? spawn(process.env.ComSpec || "cmd.exe", [
      "/d",
      "/s",
      "/c",
      [gradle, ...args].join(" "),
    ], { env, stdio: "inherit" })
    : spawn(gradle, args, { env, stdio: "inherit" });
  let exitCode;
  try {
    exitCode = await new Promise((resolve, reject) => {
      child.once(
        "error",
        () => reject(new Error("Device test process could not start")),
      );
      child.once("close", resolve);
    });
  } finally {
    if (monitor) clearInterval(monitor);
    await diagnosticRequest;
  }
  if (exitCode !== 0) {
    const failedItems = await api("/functions/v1/library-api/v1/items", {
      token: accessToken,
    });
    for (const item of failedItems.data?.items ?? []) {
      const failedDetail = await api(
        `/functions/v1/library-api/v1/items/${item.id}`,
        { token: accessToken },
      );
      console.error(JSON.stringify({
        case: fixtureKind,
        metadataState: failedDetail.data?.metadata_state,
        classificationState: failedDetail.data?.classification_state,
        expectedTitlePresent:
          failedDetail.data?.fetched_title?.includes(fixture.title) ?? false,
        extractionError: failedDetail.data?.extraction_meta?.error_code ?? null,
      }));
    }
  }
  assert.equal(exitCode, 0, "device integration test must pass");
  const items = await api("/functions/v1/library-api/v1/items", {
    token: accessToken,
  });
  assert.equal(items.status, 200);
  if (verifySourceBrowsing) {
    assert.equal(items.data.items.length, 4);
    for (const [source, id] of Object.entries(sourceFixtureIds)) {
      const filtered = await api(
        `/functions/v1/library-api/v1/items?source=${source}`,
        { token: accessToken },
      );
      assert.equal(filtered.status, 200);
      assert.deepEqual(filtered.data.items.map((item) => item.id), [id]);
    }
    console.log(
      "PASS library source shortcuts -> server-filtered results, repeat navigation, and all-source reset",
    );
  } else if (verifyDetailEditing) {
    assert.equal(items.data.items.length, 1);
    const edited = await api(
      `/functions/v1/library-api/v1/items/${seededItemId}`,
      { token: accessToken },
    );
    assert.equal(edited.status, 200);
    assert.equal(edited.data.user_title, "수정한 제목");
    assert.equal(edited.data.note, "수정한 내용\n줄바꿈도 보존합니다.");
    assert.ok(
      edited.data.category_refs.some((category) => category.name === "여행"),
    );
    console.log(
      "PASS direct category selection and prefilled title/content editing persist on server",
    );
  } else if (verifyListDeletion) {
    assert.equal(
      items.data.items.length,
      0,
      "confirmed list deletion persists on the server",
    );
    console.log(
      "PASS device list delete: cancel preserves, confirm deletes, restart remains deleted",
    );
  } else {
    assert.equal(items.data.items.length, 1, "device created exactly one item");
    const detail = await api(
      `/functions/v1/library-api/v1/items/${items.data.items[0].id}`,
      { token: accessToken },
    );
    assert.equal(detail.status, 200);
    assert.equal(detail.data.url, fixtureUrl, "original URL persisted");
    assert.equal(
      detail.data.note,
      null,
      "URL-only UI save requires no note",
    );
    assert.equal(
      detail.data.user_title,
      null,
      "URL-only UI save requires no manual title",
    );
    assert.ok(
      detail.data.fetched_title?.includes(fixture.title),
      "public title reached the saved item",
    );
    assert.equal(
      detail.data.metadata_state,
      fixture.metadata,
      "metadata completeness matches the public fixture",
    );
    assert.equal(
      detail.data.classification_state,
      fixture.classification,
      "automatic classification settled",
    );
    if (fixtureKind === "instagram-travel") {
      assert.ok(
        detail.data.category_refs.some((category) => category.name === "여행"),
      );
    }
  }
  console.log(
    "PASS device UI -> real Auth session -> Edge API -> PostgreSQL -> reload",
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
