import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import test from "node:test";

const workflow = readFileSync(
  new URL("../.github/workflows/ci.yml", import.meta.url),
  "utf8",
).replace(/\r\n/g, "\n").replace(/^[ \t]*#.*$/gm, "").replace(
  /[ \t]+#.*$/gm,
  "",
);
const manifest = JSON.parse(readFileSync(
  new URL("../package.json", import.meta.url),
  "utf8",
));
const backupWorkflow = readFileSync(
  new URL("../.github/workflows/backup.yml", import.meta.url),
  "utf8",
).replace(/\r\n/g, "\n");
const BACKUP_NOTIFY_TODAY = "2030-01-15";

// Inspect the small workflow's mapping blocks, not its whitespace or step names.
function block(source, key) {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = source.match(
    new RegExp(`^( *)["']?${escaped}["']?:[^\\n]*`, "m"),
  );
  assert.ok(match, `Missing workflow key: ${key}`);
  const lines = source.slice(match.index).split("\n");
  const end = lines.findIndex((line, index) =>
    index > 0 && line.trim() && line.match(/^ */)[0].length <= match[1].length
  );
  return lines.slice(0, end < 0 ? lines.length : end).join("\n");
}

function unquote(value) {
  return value.trim().replace(/^(["'])(.*)\1$/, "$2");
}

function scalar(source, key) {
  return unquote(block(source, key).split("\n")[0].split(/:(.*)/s)[1]);
}

function actionSteps(action) {
  return workflow.split(/(?=^ *- +(?:name|uses|run|id):)/m).filter((step) => {
    const use = step.match(/^ *(?:- +)?uses: +([^\s]+)/m);
    return use?.[1].startsWith(`${action}@`);
  });
}

function actionStep(action) {
  const steps = actionSteps(action);
  assert.equal(steps.length, 1, `Expected one setup step for ${action}`);
  return steps[0];
}

function runCommands() {
  const lines = workflow.split("\n");
  const commands = [];
  for (let index = 0; index < lines.length; index++) {
    const match = lines[index].match(/^( *)(- +)?run: *(.*)$/);
    if (!match) continue;
    if (!/^[|>][+-]?$/.test(match[3])) {
      commands.push(unquote(match[3]));
      continue;
    }
    const indentation = match[1].length + (match[2]?.length ?? 0);
    const body = [];
    while (
      index + 1 < lines.length &&
      (!lines[index + 1].trim() ||
        lines[index + 1].match(/^ */)[0].length > indentation)
    ) {
      body.push(lines[++index].trim());
    }
    commands.push(body.join(" ").trim());
  }
  return commands.map((command) => command.replace(/\s+/g, " "));
}

const commands = runCommands();

function backupNotifyScript() {
  const notify = block(block(backupWorkflow, "jobs"), "notify");
  const run = block(notify, "run");
  assert.equal(scalar(run, "run"), "|");
  const lines = run.split("\n").slice(1);
  const indentation = Math.min(
    ...lines.filter((line) => line.trim()).map((line) =>
      line.match(/^ */)[0].length
    ),
  );
  return lines.map((line) => line.slice(indentation)).join("\n");
}

function backupNotifyExecutables() {
  const windows = process.platform === "win32";
  const directories = (process.env.PATH ?? "").split(delimiter).filter((
    directory,
  ) =>
    directory &&
    (!windows || !/[\\/]Microsoft[\\/]WindowsApps(?:[\\/]|$)/i.test(directory))
  );
  const executable = (names, paths = directories) =>
    names.flatMap((name) => paths.map((directory) => resolve(directory, name)))
      .find((path) => existsSync(path));
  // Resolve Git's own Bash, never the Windows System32 WSL launcher.
  const gitDirectories = directories.filter((directory) =>
    existsSync(resolve(directory, "git.exe"))
  );
  const bash = windows
    ? executable(
      ["bash.exe"],
      gitDirectories.flatMap((directory) => [
        directory,
        resolve(directory, "..", "bin"),
      ]),
    )
    : executable(["bash"]);
  // Windows python3.exe can be a Store alias; use an installed interpreter.
  const python = executable(
    windows ? ["python.exe", "python3.exe"] : ["python3"],
  );
  assert.ok(
    bash,
    windows
      ? "Git Bash is required for notify tests"
      : "Bash is required for notify tests",
  );
  assert.ok(
    python,
    "An installed Python interpreter is required for notify tests",
  );
  return { bash, python };
}

function runBackupNotify(t, result, expiry, existing = "") {
  const { bash, python } = backupNotifyExecutables();
  const directory = mkdtempSync(join(tmpdir(), "backup-notify-test-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, "bin");
  const log = join(directory, "gh-calls");
  mkdirSync(bin);
  writeFileSync(log, "");
  writeFileSync(
    join(directory, "sitecustomize.py"),
    `import datetime

class FrozenDateTime(datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        instant = cls.fromisoformat("${BACKUP_NOTIFY_TODAY}T12:00:00+00:00")
        return instant.astimezone(tz) if tz else instant.replace(tzinfo=None)

datetime.datetime = FrozenDateTime
`,
  );
  writeFileSync(
    join(bin, "gh"),
    `#!/bin/bash
set -euo pipefail
printf '%s\\0' "$#" "$@" >> "$GH_CALLS"
case "$1 $2" in
  'issue list') printf '%s\\n' "$EXISTING_ISSUE" ;;
  'issue create') ;;
  *) exit 64 ;;
esac
`,
    { mode: 0o700 },
  );
  writeFileSync(
    join(directory, "notify.sh"),
    `
set -euo pipefail
python3() { "$NOTIFY_PYTHON" "$@"; }
gh() { "$BASH" "$PWD/bin/gh" "$@"; }
# Only the local gh stub is executable through PATH; no real CLI or network tools.
export PATH="$PWD/bin"
export GH_CALLS="$PWD/gh-calls"
${backupNotifyScript()}
`,
  );
  const execution = spawnSync(bash, ["--noprofile", "--norc", "./notify.sh"], {
    cwd: directory,
    encoding: "utf8",
    timeout: 10_000,
    env: {
      PATH: process.env.PATH,
      ...(process.env.SystemRoot ? { SystemRoot: process.env.SystemRoot } : {}),
      HOME: directory,
      NOTIFY_PYTHON: python.replace(/\\/g, "/"),
      PYTHONPATH: directory,
      PYTHONNOUSERSITE: "1",
      PYTHONDONTWRITEBYTECODE: "1",
      GH_TOKEN: "fixture-only-token",
      GH_REPO: "fixture-owner/fixture-repo",
      BACKUP_RESULT: result,
      SERVER_EXPIRES_ON: expiry,
      ASSIGNEE: "fixture-owner",
      RUN_URL: "https://example.invalid/actions/runs/1",
      EXISTING_ISSUE: existing,
    },
  });
  assert.equal(execution.error, undefined);
  assert.equal(execution.status, 0, execution.stderr);
  assert.equal(execution.stdout, "");
  const fields = readFileSync(log, "utf8").split("\0");
  assert.equal(fields.pop(), "");
  const calls = [];
  for (let index = 0; index < fields.length;) {
    const count = Number(fields[index++]);
    assert.ok(Number.isInteger(count) && count > 0);
    calls.push(fields.slice(index, index + count));
    index += count;
  }
  return calls;
}

function expiryInDays(days) {
  const expiry = new Date(`${BACKUP_NOTIFY_TODAY}T12:00:00Z`);
  expiry.setUTCDate(expiry.getUTCDate() + days);
  return expiry.toISOString().slice(0, 10);
}

function assertBackupAlert(calls, title, result, expiry, existing = "") {
  assert.deepEqual(calls[0], [
    "issue",
    "list",
    "--state",
    "open",
    "--search",
    `${title} in:title`,
    "--json",
    "number",
    "--jq",
    ".[0].number // empty",
  ]);
  assert.equal(calls.length, existing ? 1 : 2);
  if (!existing) {
    assert.deepEqual(calls[1].slice(0, 7), [
      "issue",
      "create",
      "--title",
      title,
      "--assignee",
      "fixture-owner",
      "--body",
    ]);
    assert.equal(calls[1].length, 8);
    assert.ok(calls[1][7].includes(`백업 결과: ${result}.`));
    assert.ok(calls[1][7].includes(`서버 만료 예정: ${expiry}.`));
    assert.ok(calls[1][7].includes("https://example.invalid/actions/runs/1"));
  }
}

test("failed backups alert even when resource expiry is missing or malformed", (t) => {
  for (const result of ["failure", "cancelled", "skipped"]) {
    for (const expiry of ["", "not-a-date", "2026-02-30"]) {
      assertBackupAlert(
        runBackupNotify(t, result, expiry),
        "[운영] 암호화 서버 백업 실행 실패",
        result,
        expiry,
      );
    }
  }
});

test("successful backups explicitly alert on missing or malformed resource expiry", (t) => {
  for (const expiry of ["", "not-a-date", "2026-02-30"]) {
    assertBackupAlert(
      runBackupNotify(t, "success", expiry),
      "[운영] 서버 만료 예정일 설정 누락 또는 오류",
      "success",
      expiry,
    );
  }
});

test("successful backups alert for expired and near expiry but not distant expiry", (t) => {
  for (const days of [-30, -1, 0, 1, 14]) {
    const expiry = expiryInDays(days);
    assertBackupAlert(
      runBackupNotify(t, "success", expiry),
      "[운영] 서버 이용 종료 전 연장 또는 이전 필요",
      "success",
      expiry,
    );
  }
  for (const days of [15, 365]) {
    assert.deepEqual(runBackupNotify(t, "success", expiryInDays(days)), []);
  }
});

test("backup notifications do not duplicate an existing open issue", (t) => {
  for (
    const [result, expiry, title] of [
      ["failure", "", "[운영] 암호화 서버 백업 실행 실패"],
      ["success", "not-a-date", "[운영] 서버 만료 예정일 설정 누락 또는 오류"],
      [
        "success",
        expiryInDays(14),
        "[운영] 서버 이용 종료 전 연장 또는 이전 필요",
      ],
    ]
  ) {
    assertBackupAlert(
      runBackupNotify(t, result, expiry, "42"),
      title,
      result,
      expiry,
      "42",
    );
  }
});

test("public deployment passes the same required Google audience to auth and functions", () => {
  const compose = readFileSync(
    new URL("../deployment/compose.public.yml", import.meta.url),
    "utf8",
  ).replace(/\r\n/g, "\n");
  const services = block(compose, "services");
  const functions = block(block(services, "functions"), "environment");
  const auth = block(block(services, "auth"), "environment");
  const audience = scalar(functions, "GOOGLE_WEB_CLIENT_ID");
  assert.equal(
    audience,
    "${GOOGLE_WEB_CLIENT_ID:?Google web client ID required}",
  );
  assert.equal(scalar(auth, "GOTRUE_EXTERNAL_GOOGLE_CLIENT_ID"), audience);
  assert.doesNotMatch(functions, /GOOGLE_CLIENT_SECRET/);
});

test("CI runs ordinary PRs and supports a credential-free reusable call", () => {
  const triggers = block(workflow, "on");
  block(triggers, "pull_request");
  const reusable = block(triggers, "workflow_call");
  assert.doesNotMatch(reusable, /\b(?:inputs|secrets):/);
  assert.doesNotMatch(
    triggers,
    /^ *(?:push|pull_request_target|workflow_run):/m,
  );
  const concurrency = block(workflow, "concurrency");
  assert.match(scalar(concurrency, "group"), /^ci-.*github\.workflow/);
  assert.match(scalar(concurrency, "group"), /github\.ref\b/);
  assert.equal(scalar(concurrency, "cancel-in-progress"), "true");
});

test("CI has read-only permissions and no production credentials or deployment", () => {
  assert.equal(workflow.match(/^ *permissions:/gm)?.length, 1);
  const permissions = block(workflow, "permissions");
  const entries = [...permissions.matchAll(/^ +([\w-]+): *([^\n]+)/gm)]
    .map((match) => [match[1], unquote(match[2])]);
  assert.deepEqual(entries, [["contents", "read"]]);
  assert.doesNotMatch(workflow, /\b(?:secrets|vars)\s*(?:\.|\[)/i);
  assert.doesNotMatch(workflow, /\b(?:environment|continue-on-error):/);
  assert.doesNotMatch(
    workflow,
    /\b(?:SUPABASE_SERVICE_ROLE_KEY|SUPABASE_ACCESS_TOKEN|ANDROID_KEYSTORE_PASSWORD)\b/,
  );
  for (const command of commands) {
    assert.doesNotMatch(
      command,
      /\|\|\s*true|supabase\s+(?:link|db\s+push|functions\s+deploy)|assembleRelease|bundleRelease|gh\s+release/,
    );
  }
  const checkouts = actionSteps("actions/checkout");
  assert.equal(checkouts.length, 2);
  for (const step of checkouts) {
    assert.equal(scalar(step, "persist-credentials"), "false");
  }
});

test("all remote actions are immutable and jobs are bounded hosted Linux jobs", () => {
  const uses = [...workflow.matchAll(/^ *(?:- +)?uses: +([^\s]+)/gm)];
  assert.ok(uses.length > 0);
  for (const [, reference] of uses) {
    assert.match(reference, /^[\w-]+\/[\w/-]+@[a-f0-9]{40}$/i);
  }
  const runners = [...workflow.matchAll(/^ +runs-on: *([^\n]+)/gm)];
  assert.equal(runners.length, 2);
  for (const [, runner] of runners) {
    assert.equal(unquote(runner), "ubuntu-latest");
  }
  const timeouts = [...workflow.matchAll(/^ +timeout-minutes: *(\d+)/gm)];
  assert.equal(timeouts.length, runners.length);
  for (const [, minutes] of timeouts) {
    assert.ok(Number(minutes) > 0 && Number(minutes) <= 30);
  }
});

test("Node uses version 24, locked install, npm cache, and registered offline tests", () => {
  const node = actionStep("actions/setup-node");
  assert.equal(scalar(node, "node-version"), "24");
  assert.equal(scalar(node, "cache"), "npm");
  assert.equal(scalar(node, "cache-dependency-path"), "package-lock.json");
  assert.ok(commands.some((command) => /^npm\s+ci$/.test(command)));
  for (
    const script of [
      "test:ci",
      "test:operations",
      "test:beta",
      "test:backend-start",
      "test:api",
    ]
  ) {
    assert.ok(manifest.scripts[script], `Unregistered script: ${script}`);
    const invocation = new RegExp(
      `(?:^|&&|;)\\s*npm\\s+run\\s+${script}(?:\\s|$|&&|;)`,
    );
    assert.ok(
      commands.some((command) => invocation.test(command)),
      `Missing CI command: ${script}`,
    );
  }
  assert.match(manifest.scripts["test:ci"], /\bnode\s+--test\b/);
  assert.ok(
    manifest.scripts["test:ci"].includes("scripts/ci-contract.test.mjs"),
  );
});

test("Deno matches the installed dependency and checks and lints with API config", () => {
  const deno = actionStep("denoland/setup-deno");
  assert.equal(scalar(deno, "deno-version"), manifest.devDependencies.deno);
  assert.equal(scalar(deno, "cache"), "true");
  for (const operation of ["check", "lint"]) {
    const command = commands.find((candidate) =>
      candidate.startsWith(`deno ${operation} `)
    );
    assert.ok(command, `Missing deno ${operation}`);
    assert.match(
      command,
      /--config(?:=|\s+)supabase\/functions\/library-api\/deno\.json(?:\s|$)/,
    );
    assert.match(
      command,
      /supabase\/functions\/library-api(?:\/index\.ts)?\s*$/,
    );
  }
});

test("Android uses Java 17, SDK 35, Gradle caching and bounded debug checks", () => {
  const java = actionStep("actions/setup-java");
  assert.equal(scalar(java, "java-version"), "17");
  assert.equal(scalar(java, "distribution"), "temurin");
  assert.match(
    scalar(actionStep("android-actions/setup-android"), "packages"),
    /\bplatforms;android-35\b/,
  );
  const gradle = actionStep("gradle/actions/setup-gradle");
  assert.match(
    scalar(gradle, "cache-read-only"),
    /github\.event_name\s*==\s*'pull_request'/,
  );
  const command = commands.find((candidate) =>
    /^bash\s+android\/gradlew\s+-p\s+android\b/.test(candidate)
  );
  assert.ok(command, "Use the checked-in Gradle wrapper via bash");
  for (const task of ["testDebugUnitTest", "lintDebug", "assembleDebug"]) {
    assert.ok(
      command.includes(`:app:${task}`),
      `Missing Android task: ${task}`,
    );
  }
  assert.match(command, /--max-workers(?:=|\s+)[12]\b/);
  assert.match(command, /--no-daemon\b/);
  assert.match(command, /--no-parallel\b/);
  assert.match(command, /-Dorg\.gradle\.jvmargs=["'][^"']*-Xmx[23]g\b/);
  assert.match(command, /-Pkotlin\.daemon\.jvmargs=-Xmx1g\b/);
});
