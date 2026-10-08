import { spawn } from "node:child_process";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import {
  localBackendConfig,
  localBackendFixture,
} from "./local-backend-fixture.mjs";

const DATABASE_CONTAINER = "supabase_db_link-vault";
const MAX_CAPTURE_BYTES = 64 * 1024;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HASH_PATTERN = /^[0-9a-f]{64}$/;
const RAW_NONCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const RUN_TAG = randomBytes(6).toString("hex");
const APP = Object.freeze({
  gate: `m5dc_gate_${RUN_TAG}`,
  accept: `m5dc_accept_${RUN_TAG}`,
  create: `m5dc_create_${RUN_TAG}`,
  authProbe: `m5dc_auth_probe_${RUN_TAG}`,
  claim: `m5dc_claim_${RUN_TAG}`,
  retention: `m5dc_retention_${RUN_TAG}`,
  inspect: `m5dc_inspect_${RUN_TAG}`,
  operation: `m5dc_operation_${RUN_TAG}`,
});
const OWNED_APPLICATION_NAMES = Object.values(APP);
const activeSqlSessions = new Set();
const ownedRequestIds = new Set();
const ownedChallengeIds = new Set();

function errorFor(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function fail(code) {
  throw errorFor(code);
}

function requireCondition(condition, code) {
  if (!condition) fail(code);
}

function executable(name) {
  return process.platform === "win32" ? `${name}.exe` : name;
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function requireUuid(value, code = "INVALID_UUID") {
  requireCondition(typeof value === "string" && UUID_PATTERN.test(value), code);
  return value.toLowerCase();
}

function requireHash(value, code = "INVALID_HASH") {
  requireCondition(typeof value === "string" && HASH_PATTERN.test(value), code);
  return value;
}

function sqlString(value) {
  requireCondition(typeof value === "string", "LOCAL_SQL_VALUE_INVALID");
  return `'${value.replaceAll("'", "''")}'`;
}

function sqlUuid(value) {
  return `${sqlString(requireUuid(value))}::uuid`;
}

function sqlUuidArray(values) {
  const entries = [...values].map((value) => sqlUuid(value));
  return entries.length === 0
    ? "array[]::uuid[]"
    : `array[${entries.join(", ")}]::uuid[]`;
}

function extractSqlState(stderr) {
  return /(?:ERROR|FATAL):\s*([A-Z0-9]{5})(?:\s|$)/.exec(stderr)?.[1] ?? null;
}

function markerLine(output, prefix) {
  return output.split(/\r?\n/).find((line) => line.startsWith(prefix)) ?? null;
}

class SqlSession {
  constructor(code, hardTimeoutMs) {
    this.code = code;
    this.stdout = "";
    this.stderr = "";
    this.stdoutBytes = 0;
    this.stderrBytes = 0;
    this.overflow = false;
    this.timedOut = false;
    this.spawnFailed = false;
    this.stdinError = null;
    this.closed = false;
    this.waiters = new Set();

    this.child = spawn(executable("docker"), [
      "exec",
      "-i",
      DATABASE_CONTAINER,
      "psql",
      "-XAtq",
      "-v",
      "ON_ERROR_STOP=1",
      "-v",
      "VERBOSITY=sqlstate",
      "-U",
      databaseUser,
      "-d",
      databaseName,
    ], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    activeSqlSessions.add(this);

    this.child.stdout.on("data", (chunk) => {
      this.stdoutBytes += chunk.length;
      if (this.stdoutBytes > MAX_CAPTURE_BYTES) {
        this.overflow = true;
        this.child.kill();
        return;
      }
      this.stdout += chunk.toString("utf8");
      this.notifyWaiters();
    });
    this.child.stderr.on("data", (chunk) => {
      this.stderrBytes += chunk.length;
      if (this.stderrBytes > MAX_CAPTURE_BYTES) {
        this.overflow = true;
        this.child.kill();
        return;
      }
      this.stderr += chunk.toString("utf8");
    });
    this.child.stdin.on("error", (error) => {
      this.stdinError = error?.code === "EPIPE" ? "EPIPE" : "FAILED";
    });
    this.child.once("error", () => {
      this.spawnFailed = true;
    });

    this.outcome = new Promise((resolvePromise) => {
      this.resolveOutcome = resolvePromise;
    });
    this.hardTimer = setTimeout(() => {
      this.timedOut = true;
      this.child.kill();
    }, hardTimeoutMs);
    this.child.once("close", (status, signal) => {
      clearTimeout(this.hardTimer);
      this.closed = true;
      activeSqlSessions.delete(this);
      this.notifyWaiters();
      this.resolveOutcome({
        status,
        signal,
        stdout: this.stdout,
        stderr: this.stderr,
        stdoutBytes: this.stdoutBytes,
        stderrBytes: this.stderrBytes,
        overflow: this.overflow,
        timedOut: this.timedOut,
        spawnFailed: this.spawnFailed,
        stdinError: this.stdinError,
      });
    });
  }

  notifyWaiters() {
    for (const waiter of [...this.waiters]) waiter();
  }

  async send(input) {
    requireCondition(
      !this.closed && this.child.stdin.writable,
      `${this.code}_STDIN_CLOSED`,
    );
    await new Promise((resolvePromise, reject) => {
      this.child.stdin.write(input, "utf8", (error) => {
        if (error) {
          reject(errorFor(
            error.code === "EPIPE"
              ? `${this.code}_EPIPE`
              : `${this.code}_WRITE_FAILED`,
          ));
        } else {
          resolvePromise();
        }
      });
    });
  }

  closeInput() {
    if (!this.closed && !this.child.stdin.destroyed) this.child.stdin.end();
  }

  waitForMarker(prefix, timeoutMs) {
    const existing = markerLine(this.stdout, prefix);
    if (existing !== null) return Promise.resolve(existing);
    return new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(check);
        reject(errorFor(`${this.code}_MARKER_TIMEOUT`));
      }, timeoutMs);
      const check = () => {
        const line = markerLine(this.stdout, prefix);
        if (line !== null) {
          clearTimeout(timer);
          this.waiters.delete(check);
          resolvePromise(line);
        } else if (this.closed) {
          clearTimeout(timer);
          this.waiters.delete(check);
          reject(errorFor(`${this.code}_MARKER_MISSING`));
        }
      };
      this.waiters.add(check);
      check();
    });
  }
}

function requireSqlSuccess(outcome, code) {
  if (
    outcome.status === 0 && outcome.signal === null && !outcome.overflow &&
    !outcome.timedOut && !outcome.spawnFailed && outcome.stdinError === null
  ) {
    return outcome.stdout.trim();
  }
  if (outcome.overflow) fail(`${code}_OUTPUT_LIMIT`);
  if (outcome.timedOut) fail(`${code}_TIMEOUT`);
  if (outcome.spawnFailed) fail(`${code}_SPAWN_FAILED`);
  if (outcome.stdinError === "EPIPE") fail(`${code}_EPIPE`);
  const state = extractSqlState(outcome.stderr);
  fail(`${code}_SQLSTATE_${state ?? "UNKNOWN"}`);
}

function sqlPreamble(
  applicationName,
  statementTimeoutMs,
  idleTimeoutMs = 15_000,
) {
  requireCondition(
    /^[a-z0-9_]{1,63}$/.test(applicationName),
    "LOCAL_SQL_APPLICATION_NAME_INVALID",
  );
  return `\\set ON_ERROR_STOP on
\\set VERBOSITY sqlstate
set client_min_messages = warning;
set application_name = ${sqlString(applicationName)};
set statement_timeout = '${statementTimeoutMs}ms';
set lock_timeout = '${statementTimeoutMs}ms';
set idle_in_transaction_session_timeout = '${idleTimeoutMs}ms';
`;
}

async function startSqlSession({
  code,
  applicationName,
  sql,
  statementTimeoutMs = 5_000,
  idleTimeoutMs = 15_000,
  hardTimeoutMs = 20_000,
  closeInput = false,
}) {
  const session = new SqlSession(code, hardTimeoutMs);
  try {
    await session.send(
      sqlPreamble(applicationName, statementTimeoutMs, idleTimeoutMs) + sql,
    );
    if (closeInput) session.closeInput();
    return session;
  } catch (error) {
    session.child.kill();
    await session.outcome;
    throw error;
  }
}

async function runSql({
  code,
  sql,
  applicationName = APP.operation,
  statementTimeoutMs = 5_000,
  hardTimeoutMs = 10_000,
}) {
  const session = await startSqlSession({
    code,
    applicationName,
    sql,
    statementTimeoutMs,
    hardTimeoutMs,
    closeInput: true,
  });
  return requireSqlSuccess(await session.outcome, code);
}

function markedJson(output, marker, code) {
  const line = markerLine(output, marker);
  requireCondition(line !== null, `${code}_MARKER_MISSING`);
  try {
    return JSON.parse(line.slice(marker.length));
  } catch {
    fail(`${code}_JSON_INVALID`);
  }
}

async function inspectJson(expression, code) {
  const output = await runSql({
    code,
    applicationName: APP.inspect,
    statementTimeoutMs: 1_500,
    hardTimeoutMs: 4_000,
    sql: `select 'INSPECT|' || (${expression})::text;\n`,
  });
  return markedJson(output, "INSPECT|", code);
}

async function waitForDatabaseObservation(
  expression,
  predicate,
  code,
  timeoutMs = 6_000,
) {
  const deadline = Date.now() + timeoutMs;
  do {
    const value = await inspectJson(expression, code);
    if (predicate(value)) return value;
    await pause(75);
  } while (Date.now() < deadline);
  fail(`${code}_NOT_OBSERVED`);
}

function apiErrorCode(result) {
  return result.body?.error?.code ?? result.body?.error_code ??
    result.body?.code ?? null;
}

const config = localBackendConfig();
let databaseUrl;
try {
  databaseUrl = new URL(config.DB_URL);
} catch {
  fail("LOCAL_DATABASE_CONFIG_INVALID");
}
requireCondition(
  databaseUrl.protocol === "postgresql:" &&
    databaseUrl.hostname === "127.0.0.1" &&
    databaseUrl.port === "18022" &&
    databaseUrl.pathname === "/postgres" &&
    databaseUrl.username === "postgres",
  "LOCAL_DATABASE_CONFIG_INVALID",
);
const databaseUser = decodeURIComponent(databaseUrl.username);
const databaseName = decodeURIComponent(databaseUrl.pathname.slice(1));
const fixture = localBackendFixture("deletion-concurrency");
const { request, user, approve, bootstrap } = fixture;
let account = null;
let checks = 0;

async function api(accountValue, path, {
  method = "GET",
  body,
  requestId,
} = {}) {
  const headers = method === "GET" || requestId === undefined
    ? {}
    : { "X-Request-Id": requestId };
  return request(`/functions/v1/library-api/v1${path}`, {
    token: accountValue.token,
    method,
    body,
    headers,
  });
}

async function createEdgeChallenge(accountValue, requestId) {
  requireUuid(requestId, "CHALLENGE_REQUEST_ID_INVALID");
  ownedRequestIds.add(requestId);
  const result = await api(accountValue, "/account/delete-challenge", {
    method: "POST",
    body: {},
    requestId,
  });
  requireCondition(result.status === 201, "EDGE_CHALLENGE_STATUS_INVALID");
  const challengeId = requireUuid(
    result.body?.challenge_id,
    "EDGE_CHALLENGE_ID_INVALID",
  );
  requireCondition(
    typeof result.body?.nonce === "string" &&
      RAW_NONCE_PATTERN.test(result.body.nonce),
    "EDGE_CHALLENGE_NONCE_INVALID",
  );
  requireCondition(
    typeof result.body?.expires_at === "string" &&
      Number.isFinite(Date.parse(result.body.expires_at)),
    "EDGE_CHALLENGE_EXPIRY_INVALID",
  );
  ownedChallengeIds.add(challengeId);
  return { status: result.status, ...result.body, challenge_id: challengeId };
}

async function assertEmptyLocalDatabase() {
  // Content-free claims and cleanup receipts legitimately outlive deleted owners.
  // Preserve them; fixture isolation requires empty live business/member state.
  const empty = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'auth_users', (select count(*) from auth.users),
      'profiles', (select count(*) from public.profiles),
      'members', (select count(*) from public.beta_members),
      'items', (select count(*) from public.items),
      'assets', (select count(*) from public.assets),
      'api_requests', (select count(*) from public.api_requests),
      'rate_buckets', (select count(*) from public.api_rate_buckets),
      'processing_jobs', (select count(*) from public.processing_jobs),
      'account_jobs', (select count(*) from public.account_deletion_jobs),
      'challenges', (select count(*) from private.auth_challenges),
      'tombstones', (select count(*) from private.item_deletion_tombstones),
      'ledger_events', (select count(*) from private.deletion_ledger),
      'ledger_exports', (select count(*) from private.deletion_ledger_exports)
    )
  `,
    "EMPTY_LOCAL_PREFLIGHT",
  );
  requireCondition(
    Object.values(empty).every((value) => value === 0),
    "EMPTY_LOCAL_DATABASE_REQUIRED",
  );
}

async function caseRetainedExpiredChallenge() {
  const requestId = randomUUID();
  const first = await createEdgeChallenge(account, requestId);
  const nonceHash = requireHash(sha256(first.nonce), "EDGE_NONCE_HASH_INVALID");

  const agedOutput = await runSql({
    code: "AGE_CHALLENGE",
    sql: `
      with changed as (
        update private.auth_challenges
        set created_at = pg_catalog.clock_timestamp() - interval '6 minutes',
            expires_at = pg_catalog.clock_timestamp() - interval '1 minute'
        where owner_id = ${sqlUuid(account.id)}
          and request_id = ${sqlUuid(requestId)}
          and id = ${sqlUuid(first.challenge_id)}
          and nonce_hash = ${sqlString(nonceHash)}
        returning expires_at
      )
      select 'AGED|' || pg_catalog.jsonb_build_object(
        'rows', count(*),
        'expires_at', min(expires_at)
      )::text
      from changed;
    `,
  });
  const aged = markedJson(agedOutput, "AGED|", "AGE_CHALLENGE");
  requireCondition(
    aged.rows === 1 && typeof aged.expires_at === "string" &&
      Number.isFinite(Date.parse(aged.expires_at)) &&
      Date.parse(aged.expires_at) < Date.now(),
    "AGE_CHALLENGE_COUNT_INVALID",
  );

  const retentionOutput = await runSql({
    code: "RETAIN_EXPIRED_CHALLENGE",
    applicationName: APP.retention,
    sql: `
      select 'RETENTION|' ||
        public.library_run_retention(pg_catalog.clock_timestamp())::text;
    `,
  });
  const retention = markedJson(
    retentionOutput,
    "RETENTION|",
    "RETAIN_EXPIRED_CHALLENGE",
  );
  requireCondition(
    retention.http_status === 200 && retention.deleted_challenges === 0,
    "RETAIN_EXPIRED_CHALLENGE_RESULT_INVALID",
  );

  const replay = await createEdgeChallenge(account, requestId);
  requireCondition(
    replay.status === 201 &&
      replay.challenge_id === first.challenge_id &&
      replay.expires_at === aged.expires_at &&
      replay.nonce === first.nonce,
    "EXPIRED_CHALLENGE_REPLAY_CHANGED",
  );

  const bindingOutput = await runSql({
    code: "EXPIRED_BINDING",
    sql: `
      do $binding$
      declare
        matched boolean := false;
      begin
        begin
          perform public.library_check_delete_challenge_binding(
            ${sqlUuid(account.id)},
            ${sqlUuid(first.challenge_id)},
            ${sqlString(nonceHash)}
          );
        exception
          when sqlstate 'P0001' then
            if sqlerrm = 'DELETE_CHALLENGE_EXPIRED' then
              matched := true;
            else
              raise;
            end if;
        end;
        if not matched then
          raise exception using errcode = 'P0002', message = 'EXPECTED_BINDING_ERROR';
        end if;
      end
      $binding$;
      select 'BINDING|P0001';
    `,
  });
  requireCondition(
    markerLine(bindingOutput, "BINDING|") === "BINDING|P0001",
    "EXPIRED_BINDING_SQLSTATE_INVALID",
  );
  const retained = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'rows', (
        select count(*) from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)}
          and request_id = ${sqlUuid(requestId)}
          and id = ${sqlUuid(first.challenge_id)}
          and nonce_hash = ${sqlString(nonceHash)}
          and expires_at <= pg_catalog.clock_timestamp()
          and created_at > pg_catalog.clock_timestamp() - interval '7 days'
      )
    )
  `,
    "RETAINED_CHALLENGE_ROW",
  );
  requireCondition(retained.rows === 1, "RETAINED_CHALLENGE_ROW_INVALID");

  checks++;
  console.log(
    "PASS M5-DC-RETAINED-CHALLENGE status=201 sqlstate=P0001 counts=1",
  );
}

async function accountStateSnapshot(challengeId) {
  return inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'auth_hash', (
        select pg_catalog.encode(
          extensions.digest(
            pg_catalog.convert_to(pg_catalog.to_jsonb(auth_user)::text, 'UTF8'),
            'sha256'
          ),
          'hex'
        )
        from auth.users as auth_user
        where auth_user.id = ${sqlUuid(account.id)}
      ),
      'profile_hash', (
        select pg_catalog.encode(
          extensions.digest(
            pg_catalog.convert_to(pg_catalog.to_jsonb(profile)::text, 'UTF8'),
            'sha256'
          ),
          'hex'
        )
        from public.profiles as profile
        where profile.id = ${sqlUuid(account.id)}
      ),
      'items', (
        select count(*) from public.items where owner_id = ${
      sqlUuid(account.id)
    }
      ),
      'account_jobs', (
        select count(*) from public.account_deletion_jobs
        where owner_id = ${sqlUuid(account.id)}
      ),
      'challenge_unused', (
        select used_at is null from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)} and id = ${sqlUuid(challengeId)}
      )
    )
  `,
    "ACCOUNT_STATE_SNAPSHOT",
  );
}

async function caseAuthForeignKeyLockCompatibility() {
  const validChallengeRequestId = randomUUID();
  const validChallenge = await createEdgeChallenge(
    account,
    validChallengeRequestId,
  );
  const sharedRequestId = randomUUID();
  ownedRequestIds.add(sharedRequestId);
  const acceptanceHash = requireHash(sha256(`accept:${randomUUID()}`));
  const newNonceHash = requireHash(sha256(`challenge:${randomUUID()}`));
  const before = await accountStateSnapshot(validChallenge.challenge_id);
  requireCondition(
    HASH_PATTERN.test(before.auth_hash) &&
      HASH_PATTERN.test(before.profile_hash) &&
      before.items === 0 && before.account_jobs === 0 &&
      before.challenge_unused === true,
    "ACCOUNT_STATE_BASELINE_INVALID",
  );

  const gate = await startSqlSession({
    code: "PROFILE_GATE",
    applicationName: APP.gate,
    statementTimeoutMs: 20_000,
    idleTimeoutMs: 30_000,
    hardTimeoutMs: 35_000,
    sql: `
      begin;
      select 1 from public.profiles
      where id = ${sqlUuid(account.id)}
      for update;
      select 'GATE_READY|' || pg_catalog.pg_backend_pid()::text;
    `,
  });
  await gate.waitForMarker("GATE_READY|", 5_000);

  const accept = await startSqlSession({
    code: "ACCOUNT_ACCEPT",
    applicationName: APP.accept,
    statementTimeoutMs: 20_000,
    idleTimeoutMs: 25_000,
    hardTimeoutMs: 30_000,
    closeInput: true,
    sql: `
      begin;
      select 'ACCEPT_STARTED|' || pg_catalog.pg_backend_pid()::text;
      do $accept$
      declare
        matched boolean := false;
      begin
        begin
          perform public.library_accept_account_deletion(
            ${sqlUuid(account.id)},
            ${sqlUuid(sharedRequestId)},
            ${sqlUuid(validChallenge.challenge_id)},
            ${sqlString(acceptanceHash)}
          );
        exception
          when sqlstate 'P0001' then
            if sqlerrm = 'IDEMPOTENCY_MISMATCH' then
              matched := true;
            else
              raise;
            end if;
        end;
        if not matched then
          raise exception using errcode = 'P0002', message = 'EXPECTED_ACCEPT_ERROR';
        end if;
      end
      $accept$;
      select 'ACCEPT_DONE|P0001|IDEMPOTENCY_MISMATCH';
      commit;
    `,
  });
  await accept.waitForMarker("ACCEPT_STARTED|", 5_000);

  const blockedAccept = await waitForDatabaseObservation(
    `
    coalesce((
      select pg_catalog.jsonb_build_object(
        'accept_pid', accepting.pid,
        'gate_pid', gating.pid,
        'blockers', pg_catalog.pg_blocking_pids(accepting.pid),
        'wait_type', accepting.wait_event_type,
        'auth_relation_lock', exists (
          select 1 from pg_catalog.pg_locks as held
          where held.pid = accepting.pid
            and held.locktype = 'relation'
            and held.relation = 'auth.users'::regclass
            and held.mode = 'RowShareLock'
            and held.granted
        ),
        'accept_profile_lock', exists (
          select 1 from pg_catalog.pg_locks as held
          where held.pid = accepting.pid
            and held.locktype = 'relation'
            and held.relation = 'public.profiles'::regclass
            and held.mode = 'RowShareLock'
            and held.granted
        ),
        'gate_profile_lock', exists (
          select 1 from pg_catalog.pg_locks as held
          where held.pid = gating.pid
            and held.locktype = 'relation'
            and held.relation = 'public.profiles'::regclass
            and held.mode = 'RowShareLock'
            and held.granted
        )
      )
      from pg_catalog.pg_stat_activity as accepting
      cross join pg_catalog.pg_stat_activity as gating
      where accepting.application_name = ${sqlString(APP.accept)}
        and gating.application_name = ${sqlString(APP.gate)}
        and gating.pid = any(pg_catalog.pg_blocking_pids(accepting.pid))
    ), 'null'::jsonb)
  `,
    (value) =>
      value !== null && Number.isInteger(value.accept_pid) &&
      Number.isInteger(value.gate_pid) &&
      Array.isArray(value.blockers) && value.blockers.length === 1 &&
      value.blockers[0] === value.gate_pid && value.wait_type === "Lock" &&
      value.auth_relation_lock === true && value.accept_profile_lock === true &&
      value.gate_profile_lock === true,
    "ACCOUNT_ACCEPT_PROFILE_BLOCK",
  );

  const createOutput = await runSql({
    code: "CONCURRENT_CHALLENGE_CREATE",
    applicationName: APP.create,
    statementTimeoutMs: 4_000,
    hardTimeoutMs: 7_000,
    sql: `
      select 'CREATE_DONE|' || public.library_create_delete_challenge(
        ${sqlUuid(account.id)},
        ${sqlUuid(sharedRequestId)},
        ${sqlString(newNonceHash)}
      )::text;
    `,
  });
  const created = markedJson(
    createOutput,
    "CREATE_DONE|",
    "CONCURRENT_CHALLENGE_CREATE",
  );
  requireCondition(
    created.http_status === 201,
    "CONCURRENT_CHALLENGE_STATUS_INVALID",
  );
  const concurrentChallengeId = requireUuid(
    created.challenge_id,
    "CONCURRENT_CHALLENGE_ID_INVALID",
  );
  ownedChallengeIds.add(concurrentChallengeId);
  const createdRow = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'rows', (
        select count(*) from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)}
          and request_id = ${sqlUuid(sharedRequestId)}
          and id = ${sqlUuid(concurrentChallengeId)}
          and nonce_hash = ${sqlString(newNonceHash)}
      )
    )
  `,
    "CONCURRENT_CHALLENGE_ROW",
  );
  requireCondition(createdRow.rows === 1, "CONCURRENT_CHALLENGE_ROW_INVALID");

  const authProbe = await startSqlSession({
    code: "AUTH_LOCK_PROBE",
    applicationName: APP.authProbe,
    statementTimeoutMs: 15_000,
    hardTimeoutMs: 25_000,
    closeInput: true,
    sql: `
      begin;
      select 'AUTH_PROBE_STARTED|' || pg_catalog.pg_backend_pid()::text;
      select 1 from auth.users
      where id = ${sqlUuid(account.id)}
      for update;
      select 'AUTH_PROBE_DONE';
      commit;
    `,
  });
  await authProbe.waitForMarker("AUTH_PROBE_STARTED|", 5_000);

  await waitForDatabaseObservation(
    `
    coalesce((
      select pg_catalog.jsonb_build_object(
        'probe_pid', probing.pid,
        'accept_pid', accepting.pid,
        'gate_pid', gating.pid,
        'probe_blockers', pg_catalog.pg_blocking_pids(probing.pid),
        'accept_blockers', pg_catalog.pg_blocking_pids(accepting.pid),
        'probe_wait_type', probing.wait_event_type,
        'accept_wait_type', accepting.wait_event_type
      )
      from pg_catalog.pg_stat_activity as probing
      cross join pg_catalog.pg_stat_activity as accepting
      cross join pg_catalog.pg_stat_activity as gating
      where probing.application_name = ${sqlString(APP.authProbe)}
        and accepting.application_name = ${sqlString(APP.accept)}
        and gating.application_name = ${sqlString(APP.gate)}
        and accepting.pid = any(pg_catalog.pg_blocking_pids(probing.pid))
        and gating.pid = any(pg_catalog.pg_blocking_pids(accepting.pid))
    ), 'null'::jsonb)
  `,
    (value) =>
      value !== null && value.accept_pid === blockedAccept.accept_pid &&
      value.gate_pid === blockedAccept.gate_pid &&
      Array.isArray(value.probe_blockers) &&
      value.probe_blockers.length === 1 &&
      value.probe_blockers[0] === value.accept_pid &&
      Array.isArray(value.accept_blockers) &&
      value.accept_blockers.length === 1 &&
      value.accept_blockers[0] === value.gate_pid &&
      value.probe_wait_type === "Lock" && value.accept_wait_type === "Lock",
    "AUTH_LOCK_CHAIN",
  );

  await gate.send("commit;\nselect 'GATE_RELEASED';\n");
  gate.closeInput();
  const gateOutcome = await gate.outcome;
  requireSqlSuccess(gateOutcome, "PROFILE_GATE_RELEASE");
  const [acceptOutcome, probeOutcome] = await Promise.all([
    accept.outcome,
    authProbe.outcome,
  ]);
  const acceptOutput = requireSqlSuccess(acceptOutcome, "ACCOUNT_ACCEPT");
  const probeOutput = requireSqlSuccess(probeOutcome, "AUTH_LOCK_PROBE");
  requireCondition(
    markerLine(acceptOutput, "ACCEPT_DONE|") ===
      "ACCEPT_DONE|P0001|IDEMPOTENCY_MISMATCH",
    "ACCOUNT_ACCEPT_ERROR_INVALID",
  );
  requireCondition(
    markerLine(probeOutput, "AUTH_PROBE_DONE") === "AUTH_PROBE_DONE",
    "AUTH_LOCK_PROBE_INCOMPLETE",
  );
  requireCondition(
    extractSqlState(acceptOutcome.stderr) !== "40P01" &&
      extractSqlState(probeOutcome.stderr) !== "40P01" &&
      extractSqlState(gateOutcome.stderr) !== "40P01",
    "DEADLOCK_SQLSTATE_OBSERVED",
  );

  const after = await accountStateSnapshot(validChallenge.challenge_id);
  requireCondition(
    after.auth_hash === before.auth_hash &&
      after.profile_hash === before.profile_hash &&
      after.items === before.items &&
      after.account_jobs === before.account_jobs &&
      after.challenge_unused === before.challenge_unused,
    "ACCOUNT_ACCEPT_MUTATED_STATE",
  );

  checks++;
  console.log(
    "PASS M5-DC-AUTH-FK status=201 sqlstate=P0001 deadlock_sqlstate=none counts=0",
  );
}

async function caseClaimOwnershipDuringRetention() {
  const requestId = randomUUID();
  ownedRequestIds.add(requestId);
  const challengeId = randomUUID();
  ownedChallengeIds.add(challengeId);
  const requestHash = requireHash(sha256("POST /account/delete-challenge\n{}"));
  const nonceHash = requireHash(sha256(`retained:${randomUUID()}`));

  const seedOutput = await runSql({
    code: "SEED_ORPHAN_CLAIM",
    sql: `
      do $seed$
      begin
        if exists (
          select 1 from private.request_id_claims
          where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
        ) or exists (
          select 1 from public.api_requests
          where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
        ) or exists (
          select 1 from private.auth_challenges
          where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
        ) or exists (
          select 1 from public.account_deletion_jobs
          where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
        ) then
          raise exception using errcode = 'P0002', message = 'FIXTURE_ID_COLLISION';
        end if;

        insert into private.request_id_claims (
          owner_id, request_id, method_path, request_hash, claimed_at, retain_until
        ) values (
          ${sqlUuid(account.id)},
          ${sqlUuid(requestId)},
          'POST /account/delete-challenge',
          ${sqlString(requestHash)},
          pg_catalog.clock_timestamp() - interval '8 days',
          pg_catalog.clock_timestamp() - interval '1 minute'
        );
      end
      $seed$;
      select 'CLAIM_SEEDED|1';
    `,
  });
  requireCondition(
    markerLine(seedOutput, "CLAIM_SEEDED|") === "CLAIM_SEEDED|1",
    "SEED_ORPHAN_CLAIM_INVALID",
  );

  const claimSession = await startSqlSession({
    code: "CLAIM_HOLDER",
    applicationName: APP.claim,
    statementTimeoutMs: 12_000,
    idleTimeoutMs: 20_000,
    hardTimeoutMs: 25_000,
    sql: `
      begin;
      select 'CLAIM_STARTED|' || pg_catalog.pg_backend_pid()::text;
      select private.claim_request_id(
        ${sqlUuid(account.id)},
        ${sqlUuid(requestId)},
        'POST /account/delete-challenge',
        ${sqlString(requestHash)},
        pg_catalog.clock_timestamp(),
        pg_catalog.clock_timestamp() + interval '7 days'
      );
      select 'CLAIM_HELD';
    `,
  });
  await claimSession.waitForMarker("CLAIM_HELD", 5_000);

  const retentionOutput = await runSql({
    code: "CONCURRENT_CLAIM_RETENTION",
    applicationName: APP.retention,
    statementTimeoutMs: 4_000,
    hardTimeoutMs: 7_000,
    sql: `
      select 'RETENTION_DONE|' ||
        public.library_run_retention(pg_catalog.clock_timestamp())::text;
    `,
  });
  const retention = markedJson(
    retentionOutput,
    "RETENTION_DONE|",
    "CONCURRENT_CLAIM_RETENTION",
  );
  requireCondition(
    retention.http_status === 200,
    "CONCURRENT_RETENTION_STATUS_INVALID",
  );

  const heldClaim = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'claim_rows', (
        select count(*) from private.request_id_claims
        where owner_id = ${sqlUuid(account.id)}
          and request_id = ${sqlUuid(requestId)}
          and method_path = 'POST /account/delete-challenge'
          and request_hash = ${sqlString(requestHash)}
          and claimed_at < pg_catalog.clock_timestamp() - interval '8 days'
          and retain_until <= pg_catalog.clock_timestamp()
      ),
      'api_requests', (
        select count(*) from public.api_requests
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      ),
      'challenges', (
        select count(*) from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      ),
      'account_jobs', (
        select count(*) from public.account_deletion_jobs
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      )
    )
  `,
    "HELD_CLAIM_STATE",
  );
  requireCondition(
    heldClaim.claim_rows === 1 && heldClaim.api_requests === 0 &&
      heldClaim.challenges === 0 && heldClaim.account_jobs === 0,
    "HELD_CLAIM_DELETED_BY_RETENTION",
  );

  await claimSession.send(`
    insert into private.auth_challenges (
      id, owner_id, request_id, nonce_hash, purpose, created_at, expires_at
    ) values (
      ${sqlUuid(challengeId)},
      ${sqlUuid(account.id)},
      ${sqlUuid(requestId)},
      ${sqlString(nonceHash)},
      'account_delete',
      pg_catalog.clock_timestamp(),
      pg_catalog.clock_timestamp() + interval '5 minutes'
    );
    commit;
    select 'CLAIM_COMMITTED';
  `);
  claimSession.closeInput();
  const claimOutput = requireSqlSuccess(
    await claimSession.outcome,
    "CLAIM_HOLDER_COMMIT",
  );
  requireCondition(
    markerLine(claimOutput, "CLAIM_COMMITTED") === "CLAIM_COMMITTED",
    "CLAIM_DOMAIN_COMMIT_INVALID",
  );

  const itemCountBefore = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'items', (select count(*) from public.items where owner_id = ${
      sqlUuid(account.id)
    }),
      'claim_rows', (
        select count(*) from private.request_id_claims
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      ),
      'challenge_rows', (
        select count(*) from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)}
          and request_id = ${sqlUuid(requestId)}
          and id = ${sqlUuid(challengeId)}
          and nonce_hash = ${sqlString(nonceHash)}
      )
    )
  `,
    "CLAIM_DOMAIN_STATE",
  );
  requireCondition(
    itemCountBefore.items === 0 && itemCountBefore.claim_rows === 1 &&
      itemCountBefore.challenge_rows === 1,
    "CLAIM_DOMAIN_STATE_INVALID",
  );

  const conflictingItem = await api(account, "/items", {
    method: "POST",
    requestId,
    body: {
      url: `https://example.test/deletion-concurrency-${randomUUID()}`,
      title: "deletion concurrency fixture",
    },
  });
  requireCondition(
    conflictingItem.status === 409 &&
      apiErrorCode(conflictingItem) === "IDEMPOTENCY_MISMATCH",
    "CROSS_ROUTE_IDEMPOTENCY_STATUS_INVALID",
  );
  const finalState = await inspectJson(
    `
    pg_catalog.jsonb_build_object(
      'items', (select count(*) from public.items where owner_id = ${
      sqlUuid(account.id)
    }),
      'api_requests', (
        select count(*) from public.api_requests
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      ),
      'claim_rows', (
        select count(*) from private.request_id_claims
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      ),
      'challenge_rows', (
        select count(*) from private.auth_challenges
        where owner_id = ${sqlUuid(account.id)} and request_id = ${
      sqlUuid(requestId)
    }
      )
    )
  `,
    "CROSS_ROUTE_FINAL_STATE",
  );
  requireCondition(
    finalState.items === 0 && finalState.api_requests === 0 &&
      finalState.claim_rows === 1 && finalState.challenge_rows === 1,
    "CROSS_ROUTE_ITEM_MUTATION_OBSERVED",
  );

  checks++;
  console.log(
    "PASS M5-DC-CLAIM-OWNERSHIP status=409 retention_status=200 counts=0",
  );
}

async function waitForClose(session, timeoutMs) {
  const result = await Promise.race([
    session.outcome,
    pause(timeoutMs).then(() => null),
  ]);
  if (result !== null) return result;
  session.child.kill();
  const killed = await Promise.race([
    session.outcome,
    pause(2_000).then(() => null),
  ]);
  if (killed === null) fail("LOCAL_SQL_CHILD_DID_NOT_CLOSE");
  return killed;
}

async function terminateOwnedBackends() {
  const names = OWNED_APPLICATION_NAMES.map(sqlString).join(", ");
  await runSql({
    code: "TERMINATE_TEST_BACKENDS",
    applicationName: `m5dc_cleanup_${RUN_TAG}`,
    statementTimeoutMs: 3_000,
    hardTimeoutMs: 6_000,
    sql: `
      select 'TERMINATED|' || count(*)::text
      from (
        select pg_catalog.pg_terminate_backend(activity.pid)
        from pg_catalog.pg_stat_activity as activity
        where activity.application_name in (${names})
          and activity.pid <> pg_catalog.pg_backend_pid()
      ) as terminated;
    `,
  });
}

async function closeAllChildConnections() {
  const failures = [];
  const open = [...activeSqlSessions];
  if (open.length > 0) {
    try {
      await terminateOwnedBackends();
    } catch (error) {
      failures.push(error);
    }
  }
  for (const session of open) {
    try {
      await waitForClose(session, 3_000);
    } catch (error) {
      failures.push(error);
    }
  }
  try {
    const remaining = await inspectJson(
      `
      pg_catalog.jsonb_build_object(
        'connections', (
          select count(*) from pg_catalog.pg_stat_activity
          where application_name in (${
        OWNED_APPLICATION_NAMES.map(sqlString).join(", ")
      })
            and pid <> pg_catalog.pg_backend_pid()
        )
      )
    `,
      "VERIFY_TEST_BACKENDS_CLOSED",
    );
    requireCondition(remaining.connections === 0, "TEST_BACKENDS_STILL_OPEN");
  } catch (error) {
    failures.push(error);
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, "SQL child cleanup failed");
  }
}

async function cleanupOwnedRows() {
  if (account === null) return;
  const ownerId = sqlUuid(account.id);
  const requests = sqlUuidArray(ownedRequestIds);
  const challenges = sqlUuidArray(ownedChallengeIds);
  const output = await runSql({
    code: "CLEANUP_OWNED_ROWS",
    statementTimeoutMs: 5_000,
    hardTimeoutMs: 10_000,
    sql: `
      begin;
      delete from private.deletion_ledger
      where owner_id = ${ownerId} and request_id = any(${requests});
      delete from public.account_deletion_jobs
      where owner_id = ${ownerId} and request_id = any(${requests});
      delete from private.auth_challenges
      where owner_id = ${ownerId}
        and (id = any(${challenges}) or request_id = any(${requests}));
      delete from private.request_id_claims
      where owner_id = ${ownerId} and request_id = any(${requests});
      commit;
      select 'CLEANUP|' || pg_catalog.jsonb_build_object(
        'challenges', (
          select count(*) from private.auth_challenges
          where owner_id = ${ownerId}
            and (id = any(${challenges}) or request_id = any(${requests}))
        ),
        'claims', (
          select count(*) from private.request_id_claims
          where owner_id = ${ownerId} and request_id = any(${requests})
        ),
        'jobs', (
          select count(*) from public.account_deletion_jobs
          where owner_id = ${ownerId} and request_id = any(${requests})
        )
      )::text;
    `,
  });
  const remaining = markedJson(output, "CLEANUP|", "CLEANUP_OWNED_ROWS");
  requireCondition(
    remaining.challenges === 0 && remaining.claims === 0 &&
      remaining.jobs === 0,
    "CLEANUP_OWNED_ROWS_INCOMPLETE",
  );
}

let primaryFailure = null;
try {
  await assertEmptyLocalDatabase();
  account = await user();
  requireUuid(account.id, "FIXTURE_OWNER_ID_INVALID");
  await approve(account);
  const bootstrapRequestId = randomUUID();
  ownedRequestIds.add(bootstrapRequestId);
  const bootstrapped = await bootstrap(account, bootstrapRequestId);
  requireCondition(
    bootstrapped.status === 200,
    "FIXTURE_BOOTSTRAP_STATUS_INVALID",
  );

  await caseRetainedExpiredChallenge();
  await caseAuthForeignKeyLockCompatibility();
  await caseClaimOwnershipDuringRetention();
} catch (error) {
  primaryFailure = error instanceof Error
    ? error
    : errorFor("UNEXPECTED_FAILURE");
} finally {
  const cleanupFailures = [];
  try {
    await closeAllChildConnections();
  } catch (error) {
    cleanupFailures.push(error);
  }
  try {
    await cleanupOwnedRows();
  } catch (error) {
    cleanupFailures.push(error);
  }
  try {
    await fixture.cleanup();
  } catch (error) {
    cleanupFailures.push(error);
  }
  if (cleanupFailures.length > 0) {
    const cleanupFailure = new AggregateError(
      cleanupFailures,
      "Deletion concurrency cleanup failed",
    );
    primaryFailure = primaryFailure === null
      ? cleanupFailure
      : new AggregateError(
        [primaryFailure, cleanupFailure],
        "Deletion concurrency integration failed",
      );
  }
}

if (primaryFailure !== null) throw primaryFailure;
requireCondition(checks === 3, "SCENARIO_COUNT_INVALID");
console.log("3 deletion concurrency scenarios passed.");
