import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { buildWebAccountConfig } from "./build-web-account-config.mjs";
import {
  API_BASE,
  GOOGLE_CLIENT_ID,
  validConfig,
} from "../website/account-deletion/account-deletion.js";

const SCRIPT_URL = new URL("./build-web-account-config.mjs", import.meta.url);
const SCRIPT_PATH = fileURLToPath(SCRIPT_URL);
const REPOSITORY_PATH = fileURLToPath(new URL("../", import.meta.url));
const PUBLIC_KEY = "sb_publishable_SYNTHETIC_TEST_KEY_ONLY";
const SUCCESS =
  "Created validated public web account configuration; values were not printed.\n";
const ENVIRONMENT = Object.freeze({
  SUPABASE_URL: new URL(API_BASE).origin,
  SUPABASE_PUBLISHABLE_KEY: PUBLIC_KEY,
  GOOGLE_WEB_CLIENT_ID: GOOGLE_CLIENT_ID,
});

function jwt(role) {
  return [
    Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString(
      "base64url",
    ),
    Buffer.from(JSON.stringify({ role })).toString("base64url"),
    "synthetic-test-signature",
  ].join(".");
}

function temporaryDirectory(t) {
  const directory = mkdtempSync(join(tmpdir(), "web-account-config-test-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function runNode(args, environment = ENVIRONMENT, cwd = tmpdir()) {
  const env = { ...process.env };
  for (const name of Object.keys(ENVIRONMENT)) delete env[name];
  Object.assign(env, environment);
  const execution = spawnSync(process.execPath, args, {
    env,
    cwd,
    encoding: "utf8",
    timeout: 10_000,
    windowsHide: true,
  });
  assert.equal(execution.error, undefined);
  return execution;
}

function runCli(args, environment = ENVIRONMENT, cwd = tmpdir()) {
  return runNode([SCRIPT_PATH, ...args], environment, cwd);
}

function assertFailure(execution, code) {
  assert.equal(execution.status, 1);
  assert.equal(execution.stdout, "");
  assert.equal(execution.stderr, `${code}\n`);
}

for (const key of [PUBLIC_KEY, jwt("anon")]) {
  test(`builder accepts a synthetic ${key === PUBLIC_KEY ? "publishable" : "anon JWT"} key`, () => {
    const environment = Object.freeze({
      ...ENVIRONMENT,
      SUPABASE_PUBLISHABLE_KEY: key,
    });
    const config = buildWebAccountConfig(environment);
    assert.deepEqual(config, {
      apiBase: API_BASE,
      googleClientId: GOOGLE_CLIENT_ID,
      publishableKey: key,
      enabled: true,
    });
    assert.equal(validConfig(config), true);
  });
}

test("builder requires all environment values without reflecting their contents", () => {
  for (const name of Object.keys(ENVIRONMENT)) {
    for (const value of [undefined, null, "", 42]) {
      const environment = { ...ENVIRONMENT, [name]: value };
      assert.throws(() => buildWebAccountConfig(environment), {
        code: "WEB_ACCOUNT_CONFIG_ENVIRONMENT_REQUIRED",
        message: "WEB_ACCOUNT_CONFIG_ENVIRONMENT_REQUIRED",
      });
    }
  }
  assert.throws(() => buildWebAccountConfig(), {
    code: "WEB_ACCOUNT_CONFIG_ENVIRONMENT_REQUIRED",
  });
});

test("builder rejects every non-exact Supabase origin", () => {
  for (
    const origin of [
      "https://other.example",
      `${ENVIRONMENT.SUPABASE_URL}/`,
      `${ENVIRONMENT.SUPABASE_URL}/functions/v1`,
      `${ENVIRONMENT.SUPABASE_URL}?private-token`,
      `${ENVIRONMENT.SUPABASE_URL}#private-token`,
      "https://api.linkfilebox.com:443",
      "https://user:PRIVATE_PASSWORD@api.linkfilebox.com",
      "http://api.linkfilebox.com",
      "https://API.linkfilebox.com",
      ` ${ENVIRONMENT.SUPABASE_URL}`,
    ]
  ) {
    assert.throws(
      () => buildWebAccountConfig({ ...ENVIRONMENT, SUPABASE_URL: origin }),
      { code: "WEB_ACCOUNT_CONFIG_ORIGIN_NOT_APPROVED" },
    );
  }
});

test("builder rejects a different Google client and unsafe or malformed keys", () => {
  for (const client of ["PRIVATE_CLIENT_ID", `${GOOGLE_CLIENT_ID} `]) {
    assert.throws(
      () =>
        buildWebAccountConfig({ ...ENVIRONMENT, GOOGLE_WEB_CLIENT_ID: client }),
      { code: "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED" },
    );
  }
  for (
    const key of [
      "sb_secret_PRIVATE_SECRET_KEY",
      jwt("service_role"),
      jwt("authenticated"),
      jwt(null),
      "PRIVATE_RAW_API_KEY",
      "sb_publishable_",
      `${PUBLIC_KEY} `,
      "header.invalid-json.signature",
      `sb_publishable_${"a".repeat(4096)}`,
    ]
  ) {
    assert.throws(
      () =>
        buildWebAccountConfig({
          ...ENVIRONMENT,
          SUPABASE_PUBLISHABLE_KEY: key,
        }),
      {
        code: "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
        message: "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
      },
    );
  }
});

test("CLI writes only the public schema to an explicit external path without logging values", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "PRIVATE_OUTPUT_PATH.json");
  const execution = runCli(["--output", output]);
  assert.equal(execution.status, 0);
  assert.equal(execution.stdout, SUCCESS);
  assert.equal(execution.stderr, "");
  assert.deepEqual(
    JSON.parse(readFileSync(output, "utf8")),
    buildWebAccountConfig(ENVIRONMENT),
  );
  assert.deepEqual(readdirSync(directory), ["PRIVATE_OUTPUT_PATH.json"]);
  if (process.platform !== "win32") {
    assert.equal(statSync(output).mode & 0o777, 0o600);
  }
});

test("CLI repeatably replaces the selected output and leaves unrelated files alone", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "config.json");
  writeFileSync(join(directory, "notes.txt"), "PRIVATE_USER_NOTES");
  assert.equal(
    runCli(["--output", "config.json"], ENVIRONMENT, directory).status,
    0,
  );
  const environment = { ...ENVIRONMENT, SUPABASE_PUBLISHABLE_KEY: jwt("anon") };
  const execution = runCli(["--output", output], environment);
  assert.equal(execution.status, 0);
  assert.equal(execution.stdout, SUCCESS);
  assert.equal(execution.stderr, "");
  assert.deepEqual(
    JSON.parse(readFileSync(output, "utf8")),
    buildWebAccountConfig(environment),
  );
  assert.equal(
    readFileSync(join(directory, "notes.txt"), "utf8"),
    "PRIVATE_USER_NOTES",
  );
  assert.deepEqual(readdirSync(directory).sort(), ["config.json", "notes.txt"]);
});

test("CLI validation failures preserve existing output and never log keys or raw configuration", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "PRIVATE_OUTPUT_PATH.json");
  const original = "PRIVATE_ORIGINAL_CONTENT\n";
  writeFileSync(output, original);
  for (
    const [environment, code] of [
      [{}, "WEB_ACCOUNT_CONFIG_ENVIRONMENT_REQUIRED"],
      [
        { ...ENVIRONMENT, SUPABASE_URL: "https://PRIVATE_HOST.example" },
        "WEB_ACCOUNT_CONFIG_ORIGIN_NOT_APPROVED",
      ],
      [
        { ...ENVIRONMENT, GOOGLE_WEB_CLIENT_ID: "PRIVATE_CLIENT_ID" },
        "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
      ],
      [
        {
          ...ENVIRONMENT,
          SUPABASE_PUBLISHABLE_KEY: "sb_secret_PRIVATE_SECRET",
        },
        "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
      ],
      [
        { ...ENVIRONMENT, SUPABASE_PUBLISHABLE_KEY: jwt("service_role") },
        "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
      ],
    ]
  ) {
    assertFailure(runCli(["--output", output], environment), code);
    assert.equal(readFileSync(output, "utf8"), original);
    assert.deepEqual(readdirSync(directory), ["PRIVATE_OUTPUT_PATH.json"]);
  }
});

test("CLI requires exactly one explicit output option and does not disclose arguments", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "PRIVATE_OUTPUT_PATH.json");
  for (
    const args of [
      [],
      [output],
      ["--output"],
      ["--output", ""],
      ["--output", "--PRIVATE_OPTION"],
      ["--PRIVATE_OPTION", output],
      [`--output=${output}`],
      ["--output", output, "PRIVATE_EXTRA_ARGUMENT"],
    ]
  ) {
    assertFailure(runCli(args), "WEB_ACCOUNT_CONFIG_INVALID_ARGUMENTS");
  }
  assert.deepEqual(readdirSync(directory), []);
});

test("CLI rejects missing parent directories and repository destinations", (t) => {
  const directory = temporaryDirectory(t);
  assertFailure(
    runCli([
      "--output",
      join(directory, "PRIVATE_MISSING_PARENT", "config.json"),
    ]),
    "WEB_ACCOUNT_CONFIG_OUTPUT_DIRECTORY_INVALID",
  );
  for (
    const output of [
      join(REPOSITORY_PATH, "config.json"),
      join(REPOSITORY_PATH, "website", "account-deletion", "config.json"),
    ]
  ) {
    assertFailure(
      runCli(["--output", output]),
      "WEB_ACCOUNT_CONFIG_OUTPUT_IN_REPOSITORY",
    );
  }
  assert.deepEqual(readdirSync(directory), []);
});

test("CLI rejects an external directory alias pointing into the repository", (t) => {
  const directory = temporaryDirectory(t);
  const alias = join(directory, "PRIVATE_SOURCE_ALIAS");
  try {
    symlinkSync(
      REPOSITORY_PATH,
      alias,
      process.platform === "win32" ? "junction" : "dir",
    );
  } catch (error) {
    if (["EPERM", "EACCES", "ENOTSUP"].includes(error.code)) {
      t.skip("Host does not permit directory symlinks or junctions.");
      return;
    }
    throw error;
  }
  assertFailure(
    runCli(["--output", join(alias, "config.json")]),
    "WEB_ACCOUNT_CONFIG_OUTPUT_IN_REPOSITORY",
  );
});

test("a failed atomic rename removes the temporary file and preserves the destination", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "PRIVATE_EXISTING_DIRECTORY");
  mkdirSync(output);
  writeFileSync(join(output, "notes.txt"), "PRIVATE_USER_NOTES");
  assertFailure(
    runCli(["--output", output]),
    "WEB_ACCOUNT_CONFIG_WRITE_FAILED",
  );
  assert.deepEqual(readdirSync(directory), ["PRIVATE_EXISTING_DIRECTORY"]);
  assert.equal(
    readFileSync(join(output, "notes.txt"), "utf8"),
    "PRIVATE_USER_NOTES",
  );
});

test("importing the library has no CLI output, environment validation, or file side effects", (t) => {
  const directory = temporaryDirectory(t);
  const output = join(directory, "PRIVATE_IMPORT_OUTPUT.json");
  const source = [
    `process.argv = [process.execPath, "unrelated-entry.mjs", "--output", ${
      JSON.stringify(output)
    }];`,
    `await import(${JSON.stringify(SCRIPT_URL.href)});`,
  ].join("\n");
  for (
    const environment of [ENVIRONMENT, {
      ...ENVIRONMENT,
      SUPABASE_PUBLISHABLE_KEY: "sb_secret_PRIVATE_SECRET",
    }]
  ) {
    const execution = runNode(
      ["--input-type=module", "--eval", source],
      environment,
    );
    assert.equal(execution.status, 0);
    assert.equal(execution.stdout, "");
    assert.equal(execution.stderr, "");
    assert.equal(existsSync(output), false);
    assert.deepEqual(readdirSync(directory), []);
  }
});
