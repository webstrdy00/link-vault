import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
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
