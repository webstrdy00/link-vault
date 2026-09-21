import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  ACCEPTED_CAPTURE_SOURCE_COUNTS,
  BETA_ACCEPTANCE_LIMITS,
  BetaAcceptanceError,
  CAPTURE_HEADERS,
  evaluateBetaAcceptance,
  loadAcceptanceCsv,
  parseAcceptanceCsv,
  PENDING_EXTERNAL_GATE_CODES,
  RETRIEVAL_TASK_HEADERS,
} from "./beta-acceptance.mjs";

const SCRIPT_PATH = fileURLToPath(
  new URL("./beta-acceptance.mjs", import.meta.url),
);

function emptyRecord(headers) {
  return Object.fromEntries(headers.map((header) => [header, ""]));
}

// Synthetic TEST-ONLY rows exercise mechanics; they are not real beta evidence.
function testOnlyFixture() {
  const sources = Object.entries(ACCEPTED_CAPTURE_SOURCE_COUNTS).flatMap(
    ([source, count]) => Array.from({ length: count }, () => source),
  );
  const captures = sources.map((source, index) => ({
    ...emptyRecord(CAPTURE_HEADERS),
    case_id: `TEST-ONLY-C${String(index + 1).padStart(2, "0")}`,
    source,
    url: `https://example.test/item/${index + 1}`,
    app_version: "TEST-ONLY-1.0",
    android_version: "TEST-ONLY-14",
    mime: "text/plain",
    has_extra_text: "false",
    has_stream: "false",
    received_url: `https://example.test/item/${index + 1}`,
    save_status: "saved",
    save_ms: "100",
    analysis_ms: "200",
    memory_cue: `TEST ONLY memory cue ${index + 1}`,
  }));
  const tasks = Array.from({ length: 10 }, (_, index) => ({
    ...emptyRecord(RETRIEVAL_TASK_HEADERS),
    task_id: `TEST-ONLY-T${String(index + 1).padStart(2, "0")}`,
    case_id: captures[index].case_id,
    memory_cue: captures[index].memory_cue,
    expected_item_id: `TEST-ONLY-I${String(index + 1).padStart(2, "0")}`,
    first_query: `TEST ONLY query ${index + 1}`,
    search_hit_at_5: index < 6 ? "true" : "",
    route: index < 6 ? "search" : "category",
    seconds_to_original: "60",
    success: "true",
    tested_at: "2026-09-20T12:00:00+09:00",
  }));
  return { captures, tasks };
}

function cloneFixture() {
  const fixture = testOnlyFixture();
  return {
    captures: fixture.captures.map((row) => ({ ...row })),
    tasks: fixture.tasks.map((row) => ({ ...row })),
  };
}

function markFailed(task) {
  task.success = "false";
  task.failure_reason = "TEST ONLY retrieval failure";
}

function quoteCsvCell(value) {
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function toCsv(headers, rows) {
  return `${headers.join(",")}\n${
    rows
      .map((row) =>
        headers.map((header) => quoteCsvCell(row[header])).join(",")
      )
      .join("\n")
  }\n`;
}

function errorCode(action) {
  try {
    action();
  } catch (error) {
    assert.ok(error instanceof BetaAcceptanceError);
    return error.code;
  }
  assert.fail("Expected an error");
}

test("synthetic TEST-ONLY 30/10 fixture passes only implemented local gates", () => {
  const { captures, tasks } = testOnlyFixture();
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, true);
  assert.equal(report.implemented_at_gates_passed, true);
  assert.equal(report.real_user_evidence_verified, false);
  assert.equal(report.external_beta_ready, false);
  assert.deepEqual(report.error_counts, {});
  assert.equal(report.local_summary.AT01.capture_count, 30);
  assert.equal(report.local_summary.AT01.coverage_count, 30);
  assert.equal(report.local_summary.AT01.actual_saved_count, 30);
  assert.deepEqual(
    report.local_summary.AT01.source_counts,
    ACCEPTED_CAPTURE_SOURCE_COUNTS,
  );
  assert.equal(report.local_summary.AT07.successes_within_60_seconds, 10);
  assert.equal(report.local_summary.AT08.search_task_count, 6);
  assert.equal(report.local_summary.AT08.search_hit_at_5_count, 6);
  assert.deepEqual(report.pending_gate_codes, PENDING_EXTERNAL_GATE_CODES);
});

test("AT01 separates complete coverage from thirty actual saves", () => {
  const { captures, tasks } = cloneFixture();
  captures[0].save_status = "input_error";
  captures[0].failure_reason = "TEST ONLY explicit input failure";
  markFailed(tasks[0]);
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, true);
  assert.equal(report.local_summary.AT01.passed, true);
  assert.equal(report.local_summary.AT01.coverage_count, 30);
  assert.equal(report.local_summary.AT01.actual_saved_count, 29);
  assert.equal(report.local_summary.AT01.actual_saved_target_met, false);
  assert.equal(report.local_summary.AT01.explicit_input_error_count, 1);
});

test("AT01 rejects an input error without an explicit reason", () => {
  const { captures, tasks } = cloneFixture();
  captures[0].save_status = "input_error";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.implemented_at_gates_passed, false);
  assert.equal(report.error_counts.CAPTURE_FAILURE_REASON_REQUIRED, 1);
  assert.equal(report.local_summary.AT01.silent_failure_count, 1);
});

test("success cannot claim an input-error capture, while explained failure can", () => {
  const contradictory = cloneFixture();
  contradictory.captures[0].save_status = "input_error";
  contradictory.captures[0].failure_reason = "TEST ONLY input failure";
  let report = evaluateBetaAcceptance(
    contradictory.captures,
    contradictory.tasks,
  );
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.TASK_SUCCESS_CAPTURE_NOT_SAVED, 1);
  assert.equal(report.local_summary.AT07.successes_within_60_seconds, 9);

  markFailed(contradictory.tasks[0]);
  report = evaluateBetaAcceptance(
    contradictory.captures,
    contradictory.tasks,
  );
  assert.equal(report.valid_evidence, true);
  assert.equal(report.local_summary.AT01.passed, true);
  assert.equal(report.local_summary.AT07.passed, true);
});

test("search tasks require a query and unsuccessful tasks require a reason", () => {
  const { captures, tasks } = cloneFixture();
  tasks[0].first_query = "";
  tasks[6].success = "false";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.TASK_SEARCH_QUERY_REQUIRED, 1);
  assert.equal(report.error_counts.TASK_FAILURE_REASON_REQUIRED, 1);
});

test("capture device context, MIME, and predeclared memory cue are required", () => {
  const { captures, tasks } = cloneFixture();
  captures[0].app_version = "";
  captures[1].android_version = "";
  captures[2].mime = "";
  captures[3].memory_cue = "";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.CAPTURE_APP_VERSION_REQUIRED, 1);
  assert.equal(report.error_counts.CAPTURE_ANDROID_VERSION_REQUIRED, 1);
  assert.equal(report.error_counts.CAPTURE_MIME_REQUIRED, 1);
  assert.equal(report.error_counts.CAPTURE_MEMORY_CUE_REQUIRED, 1);
  assert.equal(report.error_counts.TASK_MEMORY_CUE_NOT_PREDECLARED, 1);
  assert.equal(report.error_counts.CAPTURE_ONE_UI_VERSION_REQUIRED, undefined);
});

test("capture URLs reject user information and missing hosts", () => {
  const { captures, tasks } = cloneFixture();
  captures[0].url = "https://private-user:private-password@example.test/item";
  captures[1].url = "http://";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.CAPTURE_URL_INVALID, 2);
  assert.equal(JSON.stringify(report).includes("private-user"), false);
});

test("AT07 threshold is eight qualified successes, with sixty inclusive", () => {
  const seven = cloneFixture();
  seven.tasks.slice(7).forEach(markFailed);
  let report = evaluateBetaAcceptance(seven.captures, seven.tasks);
  assert.equal(report.valid_evidence, true);
  assert.equal(report.local_summary.AT07.successes_within_60_seconds, 7);
  assert.equal(report.local_summary.AT07.passed, false);

  const eight = cloneFixture();
  eight.tasks.slice(8).forEach(markFailed);
  eight.tasks[7].seconds_to_original = "60";
  report = evaluateBetaAcceptance(eight.captures, eight.tasks);
  assert.equal(report.local_summary.AT07.successes_within_60_seconds, 8);
  assert.equal(report.local_summary.AT07.passed, true);

  eight.tasks[7].seconds_to_original = "60.001";
  report = evaluateBetaAcceptance(eight.captures, eight.tasks);
  assert.equal(report.local_summary.AT07.successes_within_60_seconds, 7);
  assert.equal(report.local_summary.AT07.passed, false);
});

test("foreign capture references and non-predeclared memory cues fail closed", () => {
  const foreign = cloneFixture();
  foreign.tasks[0].case_id = "TEST-ONLY-NOT-A-CAPTURE";
  let report = evaluateBetaAcceptance(foreign.captures, foreign.tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.TASK_FOREIGN_CASE_REF, 1);

  const mismatch = cloneFixture();
  mismatch.tasks[0].memory_cue = "TEST ONLY changed after capture";
  report = evaluateBetaAcceptance(mismatch.captures, mismatch.tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.TASK_MEMORY_CUE_NOT_PREDECLARED, 1);
});

test("invalid numeric, boolean, and explicit-offset date fields fail closed", () => {
  const { captures, tasks } = cloneFixture();
  captures[0].save_ms = "NaN";
  captures[1].has_stream = "yes";
  tasks[0].seconds_to_original = "-1";
  tasks[1].success = "yes";
  tasks[2].search_hit_at_5 = "yes";
  tasks[3].tested_at = "2026-09-20T12:00:00";
  tasks[4].tested_at = "2026-02-30T12:00:00+09:00";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.CAPTURE_NUMERIC_INVALID, 1);
  assert.equal(report.error_counts.CAPTURE_BOOLEAN_INVALID, 1);
  assert.equal(report.error_counts.TASK_SECONDS_INVALID, 1);
  assert.equal(report.error_counts.TASK_SUCCESS_INVALID, 1);
  assert.equal(report.error_counts.TASK_SEARCH_HIT_INVALID, 1);
  assert.equal(report.error_counts.TASK_TESTED_AT_INVALID, 2);
});

test("quoted CSV preserves commas while enforcing the exact headers", () => {
  const { captures } = cloneFixture();
  captures[0].url = "https://example.test/item/1?label=alpha,beta";
  captures[0].memory_cue = "TEST ONLY alpha, beta";
  const parsed = parseAcceptanceCsv(
    toCsv(CAPTURE_HEADERS, captures),
    "captures",
  );
  assert.equal(parsed[0].url, captures[0].url);
  assert.equal(parsed[0].memory_cue, captures[0].memory_cue);

  const reordered = [...CAPTURE_HEADERS];
  [reordered[0], reordered[1]] = [reordered[1], reordered[0]];
  assert.equal(
    errorCode(() => parseAcceptanceCsv(toCsv(reordered, captures), "captures")),
    "CAPTURES_CSV_INVALID_HEADERS",
  );
});

test("missing and header-only files return fixed errors without paths", () => {
  const directory = mkdtempSync(join(tmpdir(), "beta-acceptance-test-"));
  const missing = join(directory, "PRIVATE-token-missing.csv");
  const headerOnly = join(directory, "header-only.csv");
  const oversized = join(directory, "oversized.csv");
  try {
    writeFileSync(headerOnly, `${CAPTURE_HEADERS.join(",")}\n`);
    writeFileSync(
      oversized,
      Buffer.alloc(BETA_ACCEPTANCE_LIMITS.max_csv_bytes + 1, "x"),
    );
    const missingCode = errorCode(() => loadAcceptanceCsv(missing, "captures"));
    assert.equal(missingCode, "CAPTURES_CSV_READ_FAILED");
    assert.equal(missingCode.includes(missing), false);
    assert.equal(
      errorCode(() => loadAcceptanceCsv(headerOnly, "captures")),
      "CAPTURES_CSV_HEADER_ONLY",
    );
    assert.equal(
      errorCode(() => loadAcceptanceCsv(oversized, "captures")),
      "CAPTURES_CSV_TOO_LARGE",
    );
    assert.equal(
      errorCode(() => loadAcceptanceCsv(directory, "captures")),
      "CAPTURES_CSV_READ_FAILED",
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("malformed CSV and configured bounds fail closed with fixed codes", () => {
  assert.equal(
    errorCode(() =>
      parseAcceptanceCsv(
        `${CAPTURE_HEADERS.join(",")}\n"unterminated`,
        "captures",
      )
    ),
    "CAPTURES_CSV_MALFORMED",
  );
  assert.equal(
    errorCode(() =>
      parseAcceptanceCsv(
        Buffer.alloc(BETA_ACCEPTANCE_LIMITS.max_csv_bytes + 1, "x"),
        "tasks",
      )
    ),
    "TASKS_CSV_TOO_LARGE",
  );
  const tooManyRows = Array.from(
    { length: BETA_ACCEPTANCE_LIMITS.max_data_rows + 1 },
    () => RETRIEVAL_TASK_HEADERS.map(() => "").join(","),
  ).join("\n");
  assert.equal(
    errorCode(() =>
      parseAcceptanceCsv(
        `${RETRIEVAL_TASK_HEADERS.join(",")}\n${tooManyRows}\n`,
        "tasks",
      )
    ),
    "TASKS_CSV_ROW_LIMIT_EXCEEDED",
  );
  const oversizedCell = {
    ...emptyRecord(CAPTURE_HEADERS),
    case_id: "x".repeat(BETA_ACCEPTANCE_LIMITS.max_cell_bytes + 1),
  };
  assert.equal(
    errorCode(() =>
      parseAcceptanceCsv(
        toCsv(CAPTURE_HEADERS, [oversizedCell]),
        "captures",
      )
    ),
    "CAPTURES_CSV_CELL_LIMIT_EXCEEDED",
  );
});

test("CLI exits zero only when the implemented local gates pass", () => {
  const directory = mkdtempSync(join(tmpdir(), "beta-acceptance-cli-test-"));
  const capturePath = join(directory, "captures.csv");
  const taskPath = join(directory, "tasks.csv");
  const { captures, tasks } = cloneFixture();
  try {
    writeFileSync(capturePath, toCsv(CAPTURE_HEADERS, captures));
    writeFileSync(taskPath, toCsv(RETRIEVAL_TASK_HEADERS, tasks));
    let execution = spawnSync(
      process.execPath,
      [SCRIPT_PATH, "--captures", capturePath, "--tasks", taskPath],
      { encoding: "utf8" },
    );
    assert.equal(execution.status, 0);
    assert.equal(JSON.parse(execution.stdout).external_beta_ready, false);

    tasks.slice(7).forEach(markFailed);
    writeFileSync(taskPath, toCsv(RETRIEVAL_TASK_HEADERS, tasks));
    execution = spawnSync(
      process.execPath,
      [SCRIPT_PATH, "--captures", capturePath, "--tasks", taskPath],
      { encoding: "utf8" },
    );
    assert.equal(execution.status, 1);
    const report = JSON.parse(execution.stdout);
    assert.equal(report.valid_evidence, true);
    assert.equal(report.local_summary.AT07.passed, false);
    assert.equal(report.external_beta_ready, false);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("duplicate capture and task IDs are counted without echoing IDs", () => {
  const { captures, tasks } = cloneFixture();
  captures[1].case_id = captures[0].case_id;
  tasks[1].task_id = tasks[0].task_id;
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.CAPTURE_CASE_ID_DUPLICATE, 1);
  assert.equal(report.error_counts.TASK_ID_DUPLICATE, 1);
  assert.equal(JSON.stringify(report).includes(captures[0].case_id), false);
  assert.equal(JSON.stringify(report).includes(tasks[0].task_id), false);
});

test("each exact source count is required", () => {
  const { captures, tasks } = cloneFixture();
  const otherIndex = captures.findIndex(
    (row) => row.source === "기타 공개 웹",
  );
  captures[otherIndex].source = "Threads";
  const report = evaluateBetaAcceptance(captures, tasks);
  assert.equal(report.valid_evidence, false);
  assert.equal(report.error_counts.CAPTURE_SOURCE_COUNT_INVALID, 2);
  assert.equal(report.local_summary.AT01.source_counts.Threads, 9);
  assert.equal(report.local_summary.AT01.source_counts["기타 공개 웹"], 5);
});

test("reports and CLI errors redact source, URL, notes, query, token, and path", () => {
  const { captures, tasks } = cloneFixture();
  const secrets = [
    "PRIVATE_SOURCE_VALUE",
    "private-url-token",
    "PRIVATE_NOTE_VALUE",
    "PRIVATE_QUERY_VALUE",
    "PRIVATE_FILTER_TOKEN",
  ];
  captures[0].source = secrets[0];
  captures[0].url = `https://example.test/${secrets[1]}`;
  captures[0].received_text_notes = secrets[2];
  tasks[0].first_query = secrets[3];
  tasks[0].filters = secrets[4];
  const serialized = JSON.stringify(evaluateBetaAcceptance(captures, tasks));
  for (const secret of secrets) {
    assert.equal(serialized.includes(secret), false);
  }

  const privateCapturePath = join(
    tmpdir(),
    "PRIVATE_CAPTURE_PATH_TOKEN-does-not-exist.csv",
  );
  const privateTaskPath = join(
    tmpdir(),
    "PRIVATE_TASK_PATH_TOKEN-does-not-exist.csv",
  );
  const execution = spawnSync(
    process.execPath,
    [
      SCRIPT_PATH,
      "--captures",
      privateCapturePath,
      "--tasks",
      privateTaskPath,
    ],
    { encoding: "utf8" },
  );
  assert.equal(execution.status, 1);
  const output = `${execution.stdout}${execution.stderr}`;
  assert.equal(output.includes(privateCapturePath), false);
  assert.equal(output.includes(privateTaskPath), false);
  assert.match(output, /"external_beta_ready": false/);
  assert.match(output, /"CAPTURES_CSV_READ_FAILED": 1/);
});
