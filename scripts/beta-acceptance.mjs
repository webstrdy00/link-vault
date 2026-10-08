import { closeSync, fstatSync, openSync, readSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { parse } from "csv-parse/sync";

export const CAPTURE_HEADERS = Object.freeze([
  "case_id",
  "source",
  "url",
  "app_version",
  "android_version",
  "one_ui_version",
  "mime",
  "has_extra_text",
  "has_stream",
  "received_url",
  "received_text_notes",
  "save_status",
  "title_status",
  "description_status",
  "body_status",
  "ocr_status",
  "save_ms",
  "analysis_ms",
  "failure_reason",
  "memory_cue",
  "expected_categories",
]);

export const RETRIEVAL_TASK_HEADERS = Object.freeze([
  "task_id",
  "case_id",
  "memory_cue",
  "expected_item_id",
  "expected_categories",
  "first_query",
  "filters",
  "search_hit_at_5",
  "route",
  "seconds_to_original",
  "success",
  "failure_reason",
  "tested_at",
]);

// These are the only accepted source labels; spelling and case are exact.
export const ACCEPTED_CAPTURE_SOURCE_COUNTS = Object.freeze({
  Threads: 8,
  Instagram: 8,
  "네이버 블로그": 8,
  "기타 공개 웹": 6,
});

export const BETA_ACCEPTANCE_LIMITS = Object.freeze({
  max_csv_bytes: 1024 * 1024,
  max_data_rows: 100,
  max_record_characters: 64 * 1024,
  max_cell_bytes: 8 * 1024,
  required_capture_rows: 30,
  required_task_rows: 10,
  required_successes_within_60_seconds: 8,
  maximum_success_seconds: 60,
});

export const PENDING_EXTERNAL_GATE_CODES = Object.freeze([
  "REAL_GOOGLE_SIGN_IN_UNVERIFIED",
  "GALAXY_S23_DEVICE_UNVERIFIED",
  "REAL_USER_30_LINKS_10_TASKS_ATTESTATION_PENDING",
  "OCR_ACCEPTANCE_UNVERIFIED",
  "CLASSIFICATION_ACCEPTANCE_UNVERIFIED",
  "SECURITY_ACCEPTANCE_UNVERIFIED",
  "OPERATIONS_ACCEPTANCE_UNVERIFIED",
]);

const CSV_SCHEMAS = Object.freeze({
  captures: Object.freeze({
    headers: CAPTURE_HEADERS,
    prefix: "CAPTURES",
  }),
  tasks: Object.freeze({
    headers: RETRIEVAL_TASK_HEADERS,
    prefix: "TASKS",
  }),
});

const BOOLEAN_VALUES = new Set(["true", "false"]);
const NONNEGATIVE_NUMBER = /^(?:\d+)(?:\.\d+)?$/;
const OFFSET_DATE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))$/;

export class BetaAcceptanceError extends Error {
  constructor(code) {
    super(code);
    this.name = "BetaAcceptanceError";
    this.code = code;
  }
}

function schemaFor(kind) {
  const schema = CSV_SCHEMAS[kind];
  if (!schema) throw new BetaAcceptanceError("CSV_KIND_INVALID");
  return schema;
}

function csvError(prefix, suffix) {
  return new BetaAcceptanceError(`${prefix}_CSV_${suffix}`);
}

function bytes(value) {
  return Buffer.byteLength(value, "utf8");
}

export function parseAcceptanceCsv(input, kind) {
  const { headers, prefix } = schemaFor(kind);
  if (!(typeof input === "string" || Buffer.isBuffer(input))) {
    throw csvError(prefix, "MALFORMED");
  }
  if (Buffer.byteLength(input) === 0) throw csvError(prefix, "EMPTY");
  if (Buffer.byteLength(input) > BETA_ACCEPTANCE_LIMITS.max_csv_bytes) {
    throw csvError(prefix, "TOO_LARGE");
  }

  let records;
  try {
    records = parse(input, {
      bom: true,
      columns: false,
      max_record_size: BETA_ACCEPTANCE_LIMITS.max_record_characters,
      relax_column_count: false,
      skip_empty_lines: true,
      trim: false,
    });
  } catch (error) {
    if (error?.code === "CSV_MAX_RECORD_SIZE") {
      throw csvError(prefix, "RECORD_TOO_LARGE");
    }
    throw csvError(prefix, "MALFORMED");
  }

  if (records.length === 0) throw csvError(prefix, "EMPTY");
  if (records.length - 1 > BETA_ACCEPTANCE_LIMITS.max_data_rows) {
    throw csvError(prefix, "ROW_LIMIT_EXCEEDED");
  }
  for (const record of records) {
    if (
      !Array.isArray(record) ||
      record.some(
        (cell) =>
          typeof cell !== "string" ||
          bytes(cell) > BETA_ACCEPTANCE_LIMITS.max_cell_bytes,
      )
    ) {
      throw csvError(prefix, "CELL_LIMIT_EXCEEDED");
    }
  }
  if (
    records[0].length !== headers.length ||
    records[0].some((header, index) => header !== headers[index])
  ) {
    throw csvError(prefix, "INVALID_HEADERS");
  }
  if (records.length === 1) throw csvError(prefix, "HEADER_ONLY");

  return records.slice(1).map((record) =>
    Object.fromEntries(headers.map((header, index) => [header, record[index]]))
  );
}

export function loadAcceptanceCsv(filePath, kind) {
  const { prefix } = schemaFor(kind);
  let descriptor;
  let input;
  try {
    descriptor = openSync(filePath, "r");
    const stat = fstatSync(descriptor);
    if (!stat.isFile()) throw csvError(prefix, "READ_FAILED");
    if (stat.size > BETA_ACCEPTANCE_LIMITS.max_csv_bytes) {
      throw csvError(prefix, "TOO_LARGE");
    }
    const buffer = Buffer.allocUnsafe(
      BETA_ACCEPTANCE_LIMITS.max_csv_bytes + 1,
    );
    let length = 0;
    while (length < buffer.length) {
      const bytesRead = readSync(
        descriptor,
        buffer,
        length,
        buffer.length - length,
        null,
      );
      if (bytesRead === 0) break;
      length += bytesRead;
    }
    if (length > BETA_ACCEPTANCE_LIMITS.max_csv_bytes) {
      throw csvError(prefix, "TOO_LARGE");
    }
    input = buffer.subarray(0, length);
  } catch (error) {
    if (error instanceof BetaAcceptanceError) throw error;
    throw csvError(prefix, "READ_FAILED");
  } finally {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch {
        // The read result or fixed read error remains authoritative and redacted.
      }
    }
  }
  return parseAcceptanceCsv(input, kind);
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isPresent(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function isHttpUrl(value) {
  if (!isPresent(value) || value !== value.trim()) return false;
  try {
    const parsed = new URL(value);
    return (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      parsed.hostname.length > 0 &&
      parsed.username === "" &&
      parsed.password === ""
    );
  } catch {
    return false;
  }
}

function isOptionalBoolean(value) {
  return value === "" || BOOLEAN_VALUES.has(value);
}

function isNonnegativeNumber(value) {
  return (
    typeof value === "string" &&
    NONNEGATIVE_NUMBER.test(value) &&
    Number.isFinite(Number(value))
  );
}

function isOptionalNonnegativeNumber(value) {
  return value === "" || isNonnegativeNumber(value);
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function daysInMonth(year, month) {
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function isExplicitOffsetDate(value) {
  if (typeof value !== "string") return false;
  const match = OFFSET_DATE.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (
    month < 1 || month > 12 ||
    day < 1 || day > daysInMonth(year, month) ||
    hour > 23 || minute > 59 || second > 59
  ) return false;
  if (match[8] !== "Z") {
    const offsetHour = Number(match[10]);
    const offsetMinute = Number(match[11]);
    if (
      offsetHour > 14 || offsetMinute > 59 ||
      (offsetHour === 14 && offsetMinute !== 0)
    ) return false;
  }
  return Number.isFinite(Date.parse(value));
}

function orderedErrorCounts(errorCounts) {
  return Object.fromEntries(
    [...errorCounts.entries()].sort(([left], [right]) =>
      left.localeCompare(right)
    ),
  );
}

function addError(errorCounts, code, count = 1) {
  errorCounts.set(code, (errorCounts.get(code) ?? 0) + count);
}

function captureSourceCounts(captures) {
  const counts = Object.fromEntries(
    Object.keys(ACCEPTED_CAPTURE_SOURCE_COUNTS).map((source) => [source, 0]),
  );
  for (const capture of captures) {
    if (isRecord(capture) && Object.hasOwn(counts, capture.source)) {
      counts[capture.source] += 1;
    }
  }
  return counts;
}

export function evaluateBetaAcceptance(captureRows, taskRows) {
  const errorCounts = new Map();
  const captures = Array.isArray(captureRows) ? captureRows : [];
  const tasks = Array.isArray(taskRows) ? taskRows : [];
  if (!Array.isArray(captureRows)) {
    addError(errorCounts, "CAPTURE_DATA_INVALID");
  }
  if (!Array.isArray(taskRows)) addError(errorCounts, "TASK_DATA_INVALID");
  if (captures.length !== BETA_ACCEPTANCE_LIMITS.required_capture_rows) {
    addError(errorCounts, "CAPTURE_ROW_COUNT_INVALID");
  }
  if (tasks.length !== BETA_ACCEPTANCE_LIMITS.required_task_rows) {
    addError(errorCounts, "TASK_ROW_COUNT_INVALID");
  }

  const caseIds = new Set();
  const captureByCaseId = new Map();
  let actualSavedCount = 0;
  let explicitInputErrorCount = 0;
  let silentFailureCount = 0;

  for (const capture of captures) {
    if (!isRecord(capture)) {
      addError(errorCounts, "CAPTURE_ROW_INVALID");
      silentFailureCount += 1;
      continue;
    }
    const caseId = capture.case_id;
    if (!isPresent(caseId)) {
      addError(errorCounts, "CAPTURE_CASE_ID_REQUIRED");
    } else if (caseIds.has(caseId)) {
      addError(errorCounts, "CAPTURE_CASE_ID_DUPLICATE");
    } else {
      caseIds.add(caseId);
      captureByCaseId.set(caseId, capture);
    }
    if (!Object.hasOwn(ACCEPTED_CAPTURE_SOURCE_COUNTS, capture.source)) {
      addError(errorCounts, "CAPTURE_SOURCE_INVALID");
    }
    if (!isHttpUrl(capture.url)) {
      addError(errorCounts, "CAPTURE_URL_INVALID");
    }
    for (
      const [field, code] of [
        ["app_version", "CAPTURE_APP_VERSION_REQUIRED"],
        ["android_version", "CAPTURE_ANDROID_VERSION_REQUIRED"],
        ["mime", "CAPTURE_MIME_REQUIRED"],
        ["memory_cue", "CAPTURE_MEMORY_CUE_REQUIRED"],
      ]
    ) {
      if (!isPresent(capture[field])) addError(errorCounts, code);
    }
    for (const field of ["has_extra_text", "has_stream"]) {
      if (!isOptionalBoolean(capture[field])) {
        addError(errorCounts, "CAPTURE_BOOLEAN_INVALID");
      }
    }
    for (const field of ["save_ms", "analysis_ms"]) {
      if (!isOptionalNonnegativeNumber(capture[field])) {
        addError(errorCounts, "CAPTURE_NUMERIC_INVALID");
      }
    }

    if (capture.save_status === "saved") {
      actualSavedCount += 1;
    } else if (capture.save_status === "input_error") {
      if (isPresent(capture.failure_reason)) {
        explicitInputErrorCount += 1;
      } else {
        addError(errorCounts, "CAPTURE_FAILURE_REASON_REQUIRED");
        silentFailureCount += 1;
      }
    } else {
      addError(errorCounts, "CAPTURE_SAVE_STATUS_INVALID");
      silentFailureCount += 1;
    }
  }

  const sourceCounts = captureSourceCounts(captures);
  let sourceCountMismatchCount = 0;
  for (
    const [source, requiredCount] of Object.entries(
      ACCEPTED_CAPTURE_SOURCE_COUNTS,
    )
  ) {
    if (sourceCounts[source] !== requiredCount) sourceCountMismatchCount += 1;
  }
  if (sourceCountMismatchCount > 0) {
    addError(
      errorCounts,
      "CAPTURE_SOURCE_COUNT_INVALID",
      sourceCountMismatchCount,
    );
  }

  const taskIds = new Set();
  let successWithin60Count = 0;
  let successFlagCount = 0;
  let searchTaskCount = 0;
  let categoryTaskCount = 0;
  let searchHitAt5Count = 0;

  for (const task of tasks) {
    if (!isRecord(task)) {
      addError(errorCounts, "TASK_ROW_INVALID");
      continue;
    }
    const taskId = task.task_id;
    if (!isPresent(taskId)) {
      addError(errorCounts, "TASK_ID_REQUIRED");
    } else if (taskIds.has(taskId)) {
      addError(errorCounts, "TASK_ID_DUPLICATE");
    } else {
      taskIds.add(taskId);
    }

    const referencedCapture = captureByCaseId.get(task.case_id);
    if (!isPresent(task.case_id)) {
      addError(errorCounts, "TASK_CASE_ID_REQUIRED");
    } else if (!referencedCapture) {
      addError(errorCounts, "TASK_FOREIGN_CASE_REF");
    }
    if (!isPresent(task.memory_cue)) {
      addError(errorCounts, "TASK_MEMORY_CUE_REQUIRED");
    } else if (
      referencedCapture &&
      (!isPresent(referencedCapture.memory_cue) ||
        referencedCapture.memory_cue !== task.memory_cue)
    ) {
      addError(errorCounts, "TASK_MEMORY_CUE_NOT_PREDECLARED");
    }
    if (!isPresent(task.expected_item_id)) {
      addError(errorCounts, "TASK_EXPECTED_ITEM_ID_REQUIRED");
    }

    const routeValid = task.route === "search" || task.route === "category";
    if (!routeValid) {
      addError(errorCounts, "TASK_ROUTE_INVALID");
    } else if (task.route === "search") {
      searchTaskCount += 1;
    } else {
      categoryTaskCount += 1;
    }

    const successValid = BOOLEAN_VALUES.has(task.success);
    if (!successValid) addError(errorCounts, "TASK_SUCCESS_INVALID");
    if (
      task.success === "true" &&
      referencedCapture &&
      referencedCapture.save_status !== "saved"
    ) {
      addError(errorCounts, "TASK_SUCCESS_CAPTURE_NOT_SAVED");
    }
    if (task.success === "false" && !isPresent(task.failure_reason)) {
      addError(errorCounts, "TASK_FAILURE_REASON_REQUIRED");
    }
    const secondsValid = isNonnegativeNumber(task.seconds_to_original);
    if (!secondsValid) addError(errorCounts, "TASK_SECONDS_INVALID");
    if (!isExplicitOffsetDate(task.tested_at)) {
      addError(errorCounts, "TASK_TESTED_AT_INVALID");
    }

    if (task.route === "search") {
      if (!isPresent(task.first_query)) {
        addError(errorCounts, "TASK_SEARCH_QUERY_REQUIRED");
      }
      if (!BOOLEAN_VALUES.has(task.search_hit_at_5)) {
        addError(errorCounts, "TASK_SEARCH_HIT_INVALID");
      } else if (task.search_hit_at_5 === "true") {
        searchHitAt5Count += 1;
      }
    } else if (
      task.route === "category" &&
      !isOptionalBoolean(task.search_hit_at_5)
    ) {
      addError(errorCounts, "TASK_SEARCH_HIT_INVALID");
    } else if (!routeValid && !BOOLEAN_VALUES.has(task.search_hit_at_5)) {
      addError(errorCounts, "TASK_SEARCH_HIT_INVALID");
    }

    if (task.success === "true") successFlagCount += 1;
    if (
      task.success === "true" &&
      secondsValid &&
      Number(task.seconds_to_original) <=
        BETA_ACCEPTANCE_LIMITS.maximum_success_seconds &&
      routeValid &&
      referencedCapture?.save_status === "saved"
    ) {
      successWithin60Count += 1;
    }
  }

  const validEvidence = errorCounts.size === 0;
  const coverageCount = actualSavedCount + explicitInputErrorCount;
  const at01Passed = validEvidence &&
    captures.length === BETA_ACCEPTANCE_LIMITS.required_capture_rows &&
    coverageCount === BETA_ACCEPTANCE_LIMITS.required_capture_rows &&
    silentFailureCount === 0;
  const at07Passed = validEvidence &&
    tasks.length === BETA_ACCEPTANCE_LIMITS.required_task_rows &&
    successWithin60Count >=
      BETA_ACCEPTANCE_LIMITS.required_successes_within_60_seconds;
  const implementedAtGatesPassed = validEvidence && at01Passed && at07Passed;

  return {
    schema_version: "beta-acceptance-v1",
    evidence_scope: "local_at01_at07_at08_aggregate_only",
    valid_evidence: validEvidence,
    implemented_at_gates_passed: implementedAtGatesPassed,
    real_user_evidence_verified: false,
    external_beta_ready: false,
    error_counts: orderedErrorCounts(errorCounts),
    pending_gate_codes: [...PENDING_EXTERNAL_GATE_CODES],
    local_summary: {
      AT01: {
        passed: at01Passed,
        required_capture_count: BETA_ACCEPTANCE_LIMITS.required_capture_rows,
        capture_count: captures.length,
        coverage_count: coverageCount,
        actual_saved_count: actualSavedCount,
        actual_saved_target_met:
          actualSavedCount === BETA_ACCEPTANCE_LIMITS.required_capture_rows,
        explicit_input_error_count: explicitInputErrorCount,
        silent_failure_count: silentFailureCount,
        required_source_counts: { ...ACCEPTED_CAPTURE_SOURCE_COUNTS },
        source_counts: sourceCounts,
      },
      AT07: {
        passed: at07Passed,
        required_task_count: BETA_ACCEPTANCE_LIMITS.required_task_rows,
        task_count: tasks.length,
        required_successes_within_60_seconds:
          BETA_ACCEPTANCE_LIMITS.required_successes_within_60_seconds,
        successes_within_60_seconds: successWithin60Count,
      },
      AT08: {
        recorded: validEvidence,
        overall_success_count: successFlagCount,
        search_task_count: searchTaskCount,
        search_hit_at_5_count: searchHitAt5Count,
        category_task_count: categoryTaskCount,
      },
    },
  };
}

function failureReport(code) {
  return {
    schema_version: "beta-acceptance-v1",
    evidence_scope: "local_at01_at07_at08_aggregate_only",
    valid_evidence: false,
    implemented_at_gates_passed: false,
    real_user_evidence_verified: false,
    external_beta_ready: false,
    error_counts: { [code]: 1 },
    pending_gate_codes: [...PENDING_EXTERNAL_GATE_CODES],
  };
}

function parseArguments(args) {
  if (args.length !== 4) {
    throw new BetaAcceptanceError("BETA_ACCEPTANCE_INVALID_ARGUMENTS");
  }
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];
    if (
      (flag !== "--captures" && flag !== "--tasks") ||
      values.has(flag) ||
      typeof value !== "string" || value.length === 0
    ) {
      throw new BetaAcceptanceError("BETA_ACCEPTANCE_INVALID_ARGUMENTS");
    }
    values.set(flag, value);
  }
  if (!values.has("--captures") || !values.has("--tasks")) {
    throw new BetaAcceptanceError("BETA_ACCEPTANCE_INVALID_ARGUMENTS");
  }
  return {
    capturesPath: values.get("--captures"),
    tasksPath: values.get("--tasks"),
  };
}

async function main() {
  try {
    const { capturesPath, tasksPath } = parseArguments(process.argv.slice(2));
    const captures = loadAcceptanceCsv(capturesPath, "captures");
    const tasks = loadAcceptanceCsv(tasksPath, "tasks");
    const report = evaluateBetaAcceptance(captures, tasks);
    console.log(JSON.stringify(report, null, 2));
    if (!report.implemented_at_gates_passed) process.exitCode = 1;
  } catch (error) {
    const code = error instanceof BetaAcceptanceError
      ? error.code
      : "BETA_ACCEPTANCE_INTERNAL_ERROR";
    console.error(JSON.stringify(failureReport(code), null, 2));
    process.exitCode = 1;
  }
}

if (
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main();
}
