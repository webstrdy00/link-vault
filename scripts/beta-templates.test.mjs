import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { parse } from "csv-parse/sync";
import {
  CAPTURE_HEADERS,
  loadAcceptanceCsv,
  RETRIEVAL_TASK_HEADERS,
} from "./beta-acceptance.mjs";

const SCRIPT_PATH = fileURLToPath(
  new URL("./beta-templates.mjs", import.meta.url),
);
const REPOSITORY_PATH = fileURLToPath(new URL("../", import.meta.url));
const CAPTURE_NAME = "device_capture_template.csv";
const TASK_NAME = "retrieval_tasks_template.csv";

function temporaryDirectory(t, parent = tmpdir()) {
  const directory = mkdtempSync(join(parent, "beta-templates-test-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function runCli(args, cwd = tmpdir()) {
  const execution = spawnSync(process.execPath, [SCRIPT_PATH, ...args], {
    cwd,
    encoding: "utf8",
    timeout: 10_000,
  });
  assert.equal(execution.error, undefined);
  return execution;
}

function assertFailure(execution, code) {
  assert.equal(execution.status, 1);
  assert.equal(execution.stdout, "");
  assert.equal(execution.stderr, `${code}\n`);
}

function createDirectoryAlias(t, target, alias) {
  try {
    symlinkSync(
      target,
      alias,
      process.platform === "win32" ? "junction" : "dir",
    );
    return true;
  } catch (error) {
    if (["EPERM", "EACCES", "ENOTSUP"].includes(error.code)) {
      t.skip("Host does not permit directory symlinks or junctions.");
      return false;
    }
    throw error;
  }
}

test("CLI creates only UTF-8 header rows matching both parser schemas", (t) => {
  const parent = temporaryDirectory(t);
  const directoryName = "PRIVATE-records with spaces 한국어";
  const directory = join(parent, directoryName);
  mkdirSync(directory);

  const execution = runCli(["--output-dir", directoryName], parent);
  assert.equal(execution.status, 0);
  assert.equal(execution.stdout, "Created header-only beta CSV templates.\n");
  assert.equal(execution.stderr, "");
  assert.deepEqual(readdirSync(directory).sort(), [CAPTURE_NAME, TASK_NAME]);
  for (
    const [name, headers, kind, code] of [
      [CAPTURE_NAME, CAPTURE_HEADERS, "captures", "CAPTURES_CSV_HEADER_ONLY"],
      [TASK_NAME, RETRIEVAL_TASK_HEADERS, "tasks", "TASKS_CSV_HEADER_ONLY"],
    ]
  ) {
    const filePath = join(directory, name);
    const bytes = readFileSync(filePath);
    assert.deepEqual(bytes, Buffer.from(`${headers.join(",")}\n`, "utf8"));
    assert.deepEqual(parse(bytes), [headers]);
    assert.throws(() => loadAcceptanceCsv(filePath, kind), {
      name: "BetaAcceptanceError",
      code,
    });
  }
});

test("CLI never overwrites either generated template on a second invocation", (t) => {
  const directory = temporaryDirectory(t);
  assert.equal(runCli(["--output-dir", directory]).status, 0);
  const before = [CAPTURE_NAME, TASK_NAME].map((name) =>
    readFileSync(join(directory, name))
  );

  assertFailure(
    runCli(["--output-dir", directory]),
    "BETA_TEMPLATES_OUTPUT_EXISTS",
  );
  [CAPTURE_NAME, TASK_NAME].forEach((name, index) => {
    assert.deepEqual(readFileSync(join(directory, name)), before[index]);
  });
});

test("a preexisting first output and unrelated user file remain untouched", (t) => {
  const directory = temporaryDirectory(t);
  const original = Buffer.from([0, 1, 255, 128, 10]);
  writeFileSync(join(directory, CAPTURE_NAME), original);
  writeFileSync(join(directory, "unrelated.txt"), "PRIVATE-user-notes");

  assertFailure(
    runCli(["--output-dir", directory]),
    "BETA_TEMPLATES_OUTPUT_EXISTS",
  );
  assert.deepEqual(readFileSync(join(directory, CAPTURE_NAME)), original);
  assert.equal(existsSync(join(directory, TASK_NAME)), false);
  assert.equal(
    readFileSync(join(directory, "unrelated.txt"), "utf8"),
    "PRIVATE-user-notes",
  );
});

test("a second-output conflict removes only the first file created by this run", (t) => {
  const directory = temporaryDirectory(t);
  const original = Buffer.from("PRIVATE-preexisting-task-evidence\n", "utf8");
  writeFileSync(join(directory, TASK_NAME), original);
  writeFileSync(join(directory, "unrelated.txt"), "PRIVATE-user-notes");

  assertFailure(
    runCli(["--output-dir", directory]),
    "BETA_TEMPLATES_OUTPUT_EXISTS",
  );
  assert.equal(existsSync(join(directory, CAPTURE_NAME)), false);
  assert.deepEqual(readFileSync(join(directory, TASK_NAME)), original);
  assert.equal(
    readFileSync(join(directory, "unrelated.txt"), "utf8"),
    "PRIVATE-user-notes",
  );
  assert.deepEqual(readdirSync(directory).sort(), [TASK_NAME, "unrelated.txt"]);
});

test("a directory at the second output is preserved while the first is rolled back", (t) => {
  const directory = temporaryDirectory(t);
  const conflict = join(directory, TASK_NAME);
  mkdirSync(conflict);
  writeFileSync(join(conflict, "user-notes.txt"), "PRIVATE-directory-content");

  assertFailure(
    runCli(["--output-dir", directory]),
    "BETA_TEMPLATES_OUTPUT_EXISTS",
  );
  assert.deepEqual(readdirSync(directory), [TASK_NAME]);
  assert.equal(
    readFileSync(join(conflict, "user-notes.txt"), "utf8"),
    "PRIVATE-directory-content",
  );
});

test("CLI rejects the repository root and descendants before creating files", (t) => {
  const directory = temporaryDirectory(t, REPOSITORY_PATH);
  for (const output of [REPOSITORY_PATH, directory, join(directory, "..")]) {
    assertFailure(
      runCli(["--output-dir", output]),
      "BETA_TEMPLATES_OUTPUT_DIRECTORY_IN_REPOSITORY",
    );
  }
  assert.deepEqual(readdirSync(directory), []);
});

test("CLI rejects external directory aliases resolving into the repository", (t) => {
  const directory = temporaryDirectory(t);
  const alias = join(directory, "PRIVATE-repository-alias");
  if (!createDirectoryAlias(t, REPOSITORY_PATH, alias)) return;

  for (const output of [alias, join(alias, "scripts")]) {
    assertFailure(
      runCli(["--output-dir", output]),
      "BETA_TEMPLATES_OUTPUT_DIRECTORY_IN_REPOSITORY",
    );
  }
  assert.equal(lstatSync(alias).isSymbolicLink(), true);
});

test("CLI allows an external directory alias without printing its path", (t) => {
  const directory = temporaryDirectory(t);
  const target = join(directory, "PRIVATE-external-target");
  const alias = join(directory, "PRIVATE-external-alias");
  mkdirSync(target);
  if (!createDirectoryAlias(t, target, alias)) return;

  const execution = runCli(["--output-dir", alias]);
  assert.equal(execution.status, 0);
  assert.equal(execution.stdout, "Created header-only beta CSV templates.\n");
  assert.equal(execution.stderr, "");
  assert.deepEqual(readdirSync(target).sort(), [CAPTURE_NAME, TASK_NAME]);
});

test("invalid CLI arguments return a fixed error without secrets or paths", (t) => {
  const directory = temporaryDirectory(t);
  const privatePath = join(directory, "PRIVATE_SECRET_PATH_TOKEN");
  for (
    const args of [
      [],
      ["--output-dir"],
      ["--output-dir", ""],
      ["--PRIVATE_SECRET_FLAG", privatePath],
      ["--output-dir", "--PRIVATE_SECRET_OPTION"],
      [`--output-dir=${privatePath}`],
      ["--output-dir", directory, "--output-dir", privatePath],
      ["--output-dir", directory, "PRIVATE_TRAILING_TOKEN"],
    ]
  ) {
    assertFailure(runCli(args), "BETA_TEMPLATES_INVALID_ARGUMENTS");
  }
  assert.deepEqual(readdirSync(directory), []);
});

test("missing paths and non-directories are rejected without creation or disclosure", (t) => {
  const directory = temporaryDirectory(t);
  const filePath = join(directory, "PRIVATE_SECRET_PATH_TOKEN.txt");
  const missingPath = join(directory, "PRIVATE_SECRET_MISSING_DIRECTORY");
  writeFileSync(filePath, "PRIVATE-user-file");

  for (const output of [missingPath, filePath]) {
    assertFailure(
      runCli(["--output-dir", output]),
      "BETA_TEMPLATES_OUTPUT_DIRECTORY_INVALID",
    );
  }
  assert.equal(existsSync(missingPath), false);
  assert.equal(readFileSync(filePath, "utf8"), "PRIVATE-user-file");
  assert.deepEqual(readdirSync(directory), ["PRIVATE_SECRET_PATH_TOKEN.txt"]);
});
