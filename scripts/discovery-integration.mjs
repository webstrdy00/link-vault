import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { setTimeout as pause } from "node:timers/promises";
import fixtures from "../supabase/functions/library-api/discovery-fixtures.json" with { type: "json" };
import { buildIndex, cues } from "../supabase/functions/library-api/item-preparation.ts";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const fixture = localBackendFixture("discovery");
const { request, user, approve, bootstrap, cleanup, settleClassification, serviceKey } = fixture;
const prefix = "/functions/v1/library-api/v1";
let checks = 0;
const saves = [];
const saveCounts = new Map();
function check(condition, label) {
  assert.ok(condition, label);
  checks++;
  console.log(`PASS ${label}`);
}
function api(account, path, method = "GET", body, requestId = randomUUID()) {
  return request(`${prefix}${path}`, {
    token: account.token, method, body,
    headers: method === "GET" ? {} : { "X-Request-Id": requestId },
  });
}
async function resetFixtureRate(account) {
  // Seed only this disposable owner without waiting ten real minutes. The M2
  // suite independently proves the public ten-new-saves/minute limit.
  const result = await request(`/rest/v1/api_rate_buckets?owner_id=eq.${account.id}`, {
    token: serviceKey, method: "DELETE",
  });
  assert.equal(result.status, 204);
}
async function save(account, body) {
  const count = saveCounts.get(account.id) ?? 0;
  if (count > 0 && count % 8 === 0) await resetFixtureRate(account);
  const started = performance.now();
  const result = await api(account, "/items", "POST", body);
  const elapsed = performance.now() - started;
  const failureCode = result.body?.error?.code ?? result.body?.code;
  const safeCode = typeof failureCode === "string" && /^[A-Z_]{1,64}$/.test(failureCode)
    ? failureCode : "UNKNOWN";
  assert.equal(result.status, 201, `Create a disposable discovery item (${safeCode})`);
  saveCounts.set(account.id, count + 1);
  saves.push(elapsed);
  return result.body.item;
}
async function category(account, name) {
  const result = await api(account, "/categories", "POST", { name });
  assert.equal(result.status, 201, "Create a disposable custom category");
  return result.body;
}
async function search(account, parameters = {}) {
  const result = await api(account, `/items?${new URLSearchParams({ limit: "50", ...parameters })}`);
  assert.equal(result.status, 200, "Execute the real member-scoped SQL search");
  return result.body;
}
async function edit(account, itemId, changes, requestId = randomUUID()) {
  const current = await settleClassification(account, itemId);
  const result = await api(account, `/items/${itemId}`, "PATCH", {
    expected_version: current.body.version, ...changes,
  }, requestId);
  assert.equal(result.status, 200, "Apply a version-bound item edit");
  return result.body;
}
async function adminPatch(table, account, itemId, body, key = "item_id") {
  const result = await request(`/rest/v1/${table}?owner_id=eq.${account.id}&${key}=eq.${itemId}`, {
    token: serviceKey, method: "PATCH", body,
  });
  assert.equal(result.status, 204, "Update only a disposable reference fixture");
}
function p95(samples) {
  return [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * 0.95) - 1];
}
function operationalSql(sql) {
  return execFileSync("docker", [
    "exec", "supabase_db_link-vault", "psql", "-U", "postgres", "-d", "postgres",
    "-v", "ON_ERROR_STOP=1", "-At", "-c", sql,
  ], { encoding: "utf8", timeout: 15000, stdio: ["ignore", "pipe", "inherit"] }).trim();
}

try {
  const a = await user();
  const b = await user();
  await approve(a);
  await approve(b);
  assert.equal((await bootstrap(a)).status, 200);
  assert.equal((await bootstrap(b)).status, 200);
  const initial = await api(a, "/categories");
  check(initial.status === 200 && initial.body.categories.length === 8,
    "eight owner-specific system categories are available");
  const system = initial.body.categories.find((value) => value.system_code === "work");
  assert.ok(system);
  const customId = randomUUID();
  const custom = await api(a, "/categories", "POST", { name: "ＦＯＣＵＳ" }, customId);
  assert.equal(custom.status, 201);
  const customReplay = await api(a, "/categories", "POST", { name: "ＦＯＣＵＳ" }, customId);
  check(customReplay.status === 201 && customReplay.body.id === custom.body.id,
    "category creation has immutable idempotent identity");
  const changedIdentity = await api(a, "/categories", "POST", { name: "focus" }, customId);
  check(changedIdentity.status === 409 && changedIdentity.body.error.code === "IDEMPOTENCY_MISMATCH",
    "normalization does not permit changing a frozen request body");
  const duplicateNameId = randomUUID();
  const duplicateName = await api(a, "/categories", "POST", { name: " focus " }, duplicateNameId);
  check(duplicateName.status === 409 && duplicateName.body.error.code === "CATEGORY_NAME_EXISTS",
    "NFKC case and whitespace duplicates are rejected by owner");
  const rewrittenFailure = await api(a, "/categories", "POST", { name: "새 이름" }, duplicateNameId);
  check(rewrittenFailure.status === 409 && rewrittenFailure.body.error.code === "IDEMPOTENCY_MISMATCH",
    "a failed category request cannot change its body under the same ID");
  for (const method of ["PATCH", "DELETE"]) {
    const protectedCategory = await api(a, `/categories/${system.id}`, method,
      method === "PATCH" ? { name: "changed" } : {});
    check(protectedCategory.status === 403 && protectedCategory.body.error.code === "SYSTEM_CATEGORY_READONLY",
      `system categories reject ${method}`);
  }
  check((await api(b, `/categories/${custom.body.id}`, "PATCH", { name: "foreign" })).status === 404,
    "another member cannot discover or rename a category");

  const manualCreated = await save(a, {
    url: `https://example.test/manual-create-${randomUUID()}`,
    category_ids: [custom.body.id],
  });
  check(manualCreated.manual_override &&
      (await search(a, { q: "focus" })).items.some((item) => item.id === manualCreated.id),
    "POST with owned categories builds its canonical category index inside the transaction");
  const expandedCategory = await category(a, "ﷺ".repeat(30));
  const expanded = await save(a, {
    url: `https://example.test/expanded-${randomUUID()}`,
    title: "İ".repeat(300), note: "ﷺ".repeat(4000),
    category_ids: [expandedCategory.id],
  });
  check(expanded.user_title === "İ".repeat(300) && expanded.note === "ﷺ".repeat(4000) &&
      (await search(a, { q: "İ" })).items.some((item) => item.id === expanded.id),
    "valid raw Unicode maxima remain intact and searchable after expanding normalization");

  const literal = await save(a, {
    url: `https://www.instagram.com/p/${randomUUID()}`,
    title: "카톡 프사 설정",
  });
  const split = await save(a, {
    url: `https://www.threads.net/@fixture/post/${randomUUID()}`,
    title: "카톡 소개", note: "프사 절차",
  });
  const alias = await save(a, {
    url: `https://blog.naver.com/fixture/${randomUUID()}`,
    title: "카카오톡 프로필 사진 변경",
  });
  const empty = await save(a, { url: `https://example.test/cue-${randomUUID()}` });
  const classified = await save(a, {
    url: `https://example.test/classify-${randomUUID()}`, title: "엑셀 수식 활용",
  });
  const foreign = await save(b, {
    url: `https://example.test/foreign-${randomUUID()}`, title: "카톡 프사 설정",
  });
  const ranked = await search(a, { q: "카톡 프사" });
  check(JSON.stringify(ranked.items.map((item) => item.id)) === JSON.stringify([literal.id, split.id, alias.id]),
    "all direct matches precede a higher-scoring alias-only match");
  check(!ranked.items.some((item) => item.id === foreign.id), "search never leaks the other member's item");
  const paged = [];
  for (let offset = 0; offset < 3; offset++) {
    const page = await search(a, { q: "카톡 프사", limit: "1", offset: String(offset) });
    paged.push(page.items[0].id);
    assert.equal(page.has_more, offset < 2);
  }
  check(JSON.stringify(paged) === JSON.stringify([literal.id, split.id, alias.id]),
    "one combined offset crosses the literal-alias page boundary without duplication");
  const literalOnly = await search(a, { q: "카톡 프사", aliases: "false" });
  check(literalOnly.items.length === 2 && literalOnly.items.every((item) => item.match_type === "literal"),
    "aliases=false keeps only direct results");
  const disclosure = await api(a, `/items/${alias.id}?q=${encodeURIComponent("카톡 프사")}`);
  check(disclosure.status === 200 && disclosure.body.alias_explanations.some((value) => value.expression === "카카오톡"),
    "alias disclosure identifies an expression present in the current item");
  check((await search(a, { q: "카톡 프사", source: "instagram" })).items[0]?.id === literal.id,
    "source filtering intersects the query");
  await adminPatch("items", a, literal.id, { created_at: "2020-01-01T00:00:00Z" }, "id");
  await adminPatch("items", a, split.id, { created_at: "2020-01-02T00:00:00Z" }, "id");
  const dated = await search(a, {
    q: "카톡 프사", date_from: "2020-01-01T00:00:00Z", date_to: "2020-01-02T00:00:00Z",
  });
  check(dated.items.length === 1 && dated.items[0].id === literal.id,
    "date bounds are inclusive from and exclusive to");

  let detail = (await settleClassification(a, classified.id)).body;
  check(detail.category_refs.some((value) => value.system_code === "work") && detail.classification_state === "automatic",
    "the real leased classifier assigns an automatic category");
  check(detail.classification_reasons.length > 0 && detail.classification_explanations.some((value) => value.expression === "엑셀"),
    "current-revision rule reasons have actual matching expressions");
  detail = await edit(a, classified.id, { category_ids: [custom.body.id] });
  check(detail.manual_override === true && detail.category_refs.length === 1,
    "explicit category editing locks automatic replacement");
  const priorRevision = detail.text_revision;
  const renamed = await api(a, `/categories/${custom.body.id}`, "PATCH", { name: "개인 자료" });
  check(renamed.status === 200, "custom category renaming succeeds");
  const stickyNameFailure = await api(a, "/categories", "POST", { name: " focus " }, duplicateNameId);
  check(stickyNameFailure.status === 409 && stickyNameFailure.body.error.code === "CATEGORY_NAME_EXISTS",
    "an exact failed request stays failed after its original name conflict is removed");
  detail = (await api(a, `/items/${classified.id}`)).body;
  check(detail.text_revision === priorRevision && (await search(a, { q: "개인 자료" })).items.some((item) => item.id === classified.id),
    "renaming refreshes category search without changing text revision");
  check(!(await search(a, { q: "focus" })).items.some((item) => item.id === classified.id),
    "renaming removes the old normalized category search text");
  const reapplyId = randomUUID();
  const reapplyBody = { expected_version: detail.version };
  const reapply = await api(a, `/items/${classified.id}/reclassify`, "POST", reapplyBody, reapplyId);
  const reapplyReplay = await api(a, `/items/${classified.id}/reclassify`, "POST", reapplyBody, reapplyId);
  check(reapply.status === 202 && reapplyReplay.status === 202 && reapply.body.job_id === reapplyReplay.body.job_id,
    "reclassification acceptance replays the same durable job");
  detail = (await settleClassification(a, classified.id)).body;
  check(!detail.manual_override && detail.category_refs.some((value) => value.id === custom.body.id) &&
      detail.category_refs.some((value) => value.system_code === "work"),
    "reapplication preserves custom categories while recomputing system categories");
  await edit(a, classified.id, { category_ids: [] });
  detail = (await api(a, `/items/${classified.id}`)).body;
  check(detail.manual_override && detail.category_refs.length === 0,
    "an explicitly empty selection is a valid manual unclassified state");

  const cue = (await api(a, `/items/${empty.id}`)).body;
  check(cue.cue_state === "missing" && !cue.cue_prompt_dismissed, "URL-only content exposes missing search cues");
  const dismissId = randomUUID();
  const dismissBody = { expected_version: cue.version, text_revision: cue.text_revision };
  const dismissed = await api(a, `/items/${empty.id}/cue-dismiss`, "POST", dismissBody, dismissId);
  check(dismissed.status === 200 && dismissed.body.cue_prompt_dismissed && dismissed.body.text_revision === cue.text_revision,
    "cue dismissal is server-persisted for exactly one revision");
  check((await search(a, { needs_cues: "true" })).items.some((item) => item.id === empty.id),
    "dismissed prompts remain reachable through the explicit cue filter");
  const staleDismissId = randomUUID();
  const conflict = await api(a, `/items/${empty.id}/cue-dismiss`, "POST", dismissBody, staleDismissId);
  const mismatch = await api(a, `/items/${empty.id}/cue-dismiss`, "POST", {
    ...dismissBody, expected_version: dismissed.body.version,
  }, staleDismissId);
  check(conflict.status === 409 && mismatch.status === 409 && mismatch.body.error.code === "IDEMPOTENCY_MISMATCH",
    "cue conflicts retain raw immutable request identity");
  const revisedCue = await edit(a, empty.id, { note: "메모" });
  check(revisedCue.text_revision > cue.text_revision && !revisedCue.cue_prompt_dismissed,
    "a new text revision does not inherit an old dismissal");
  const excelCategory = await category(a, "엑셀");
  await edit(a, empty.id, { category_ids: [excelCategory.id] });
  check(!(await search(a, { q: "excel" })).items.some((item) => item.id === empty.id),
    "custom category names cannot become alias concepts");
  const selected = await search(a, { category_id: excelCategory.id, needs_cues: "true" });
  check(selected.items.some((item) => item.id === empty.id), "category and cue filters combine with AND");
  const deletionId = randomUUID();
  const deletion = await api(a, `/categories/${excelCategory.id}`, "DELETE", {}, deletionId);
  const deletionReplay = await api(a, `/categories/${excelCategory.id}`, "DELETE", {}, deletionId);
  check(deletion.status === 204 && deletion.body === null && deletionReplay.status === 204,
    "category DELETE returns an actual empty 204 and has a durable replay");
  check((await api(a, `/items/${empty.id}`)).status === 200 &&
      !(await search(a, { q: "엑셀" })).items.some((item) => item.id === empty.id),
    "deleting a category preserves its item and removes category search text");

  for (const testCase of fixtures.search) {
    const input = testCase.item;
    const item = await save(a, { url: input.url ?? `https://example.test/${testCase.id}-${randomUUID()}` });
    // Frozen reference `title` is fetched_title only in this test adapter.
    // The two OCR cases seed the index synthetically; this does not implement
    // or certify an attachment/OCR ingestion pipeline (M4 remains deferred).
    const source = {
      user_title: input.user_title ?? "", fetched_title: input.title ?? "",
      note: input.note ?? "", shared: input.shared ?? "",
      description: input.description ?? "", body: input.body ?? "",
      ocr: input.ocr_active === false ? "" : input.ocr ?? "",
      categories: "", url: input.url ?? item.url,
    };
    const index = buildIndex(source);
    const cueIndex = cues(source);
    await adminPatch("items", a, item.id, {
      fetched_title: source.fetched_title || null, user_title: source.user_title || null,
      note: source.note || null, shared_text: source.shared || null,
      description: source.description || null, body_text: source.body || null,
    }, "id");
    await adminPatch("item_search", a, item.id, {
      normalized_fields: index.fields, alias_concepts: index.concepts,
      cue_state: cueIndex.state, cue_flags: cueIndex.flags,
    });
    const results = await search(a, { q: testCase.q });
    const matched = results.items.find((candidate) => candidate.id === item.id);
    check(Boolean(matched) === testCase.expected && (!matched || matched.match_type === testCase.mode),
      `sealed SQL search parity ${testCase.id}`);
  }
  for (const q of ["x".repeat(201), Array(11).fill("word").join(" ")]) {
    const invalid = await api(a, `/items?q=${encodeURIComponent(q)}`);
    check(invalid.status === 400 && invalid.body.error.code === "QUERY_LIMIT", "query limits reject rather than truncate");
  }
  check((await api(a, `/items?category_id=${custom.body.id}&unclassified=true`)).status === 400,
    "incompatible category filters fail explicitly");
  check((await api(a, "/items?date_from=2026-02-30T00%3A00%3A00Z")).status === 400,
    "an impossible calendar date is rejected");
  const internalDenied = await api(a, "/internal/classify", "POST", { limit: 20 });
  check(internalDenied.status === 401 || internalDenied.status === 403,
    "a real member JWT cannot invoke the private worker");
  const directWrite = await request("/rest/v1/rpc/library_create_category", {
    token: a.token, method: "POST", body: {
      p_owner_id: a.id, p_request_id: randomUUID(), p_body: { name: "denied" }, p_normalized_name: "denied",
    },
  });
  check(directWrite.status === 403, "member JWTs cannot call service-only category mutations directly");

  const limitCategories = [];
  for (let index = 0; index < 29; index++) limitCategories.push(await category(b, `경계${index}`));
  const contenderNames = ["마지막하나", "마지막둘"];
  const contenderIds = [randomUUID(), randomUUID()];
  const contenders = await Promise.all(contenderNames.map((name, index) =>
    api(b, "/categories", "POST", { name }, contenderIds[index])));
  check(contenders.filter((result) => result.status === 201).length === 1 &&
      contenders.filter((result) => result.status === 409).length === 1,
    "concurrent custom-category creation cannot exceed thirty");
  const loser = contenders.findIndex((result) => result.status === 409);
  assert.equal((await api(b, `/categories/${limitCategories[0].id}`, "DELETE", {})).status, 204);
  const stickyLimit = await api(b, "/categories", "POST",
    { name: contenderNames[loser] }, contenderIds[loser]);
  check(stickyLimit.status === 409 && stickyLimit.body.error.code === "CATEGORY_LIMIT_REACHED",
    "a capacity-conflicted request stays bound after capacity is released");
  check((await api(b, "/categories", "POST", { name: contenderNames[loser] })).status === 201,
    "a newly confirmed request can use the released category capacity");

  // Deliberately enqueue through the DB, not a mutation endpoint: only pg_cron
  // can wake this idle job. This distinguishes durable scheduling from waitUntil.
  const scheduledItem = (await settleClassification(a, literal.id)).body;
  const networkMarker = Number(operationalSql("select coalesce(max(id), 0) from net._http_response"));
  assert.ok(Number.isSafeInteger(networkMarker) && networkMarker >= 0);
  await adminPatch("item_classification", a, literal.id, { state: "pending", reasons: [] });
  await adminPatch("items", a, literal.id, { version: scheduledItem.version + 1 }, "id");
  const queued = await request("/rest/v1/processing_jobs", {
    token: serviceKey, method: "POST", headers: { Prefer: "return=representation" },
    body: { owner_id: a.id, item_id: literal.id, kind: "classify", target_revision: scheduledItem.text_revision },
  });
  assert.equal(queued.status, 201);
  const jobId = queued.body[0].id;
  let scheduled = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    const job = await request(`/rest/v1/processing_jobs?id=eq.${jobId}&select=state,last_error_code`, { token: serviceKey });
    assert.equal(job.status, 200);
    assert.ok(!["failed", "cancelled"].includes(job.body[0]?.state),
      `Scheduled fixture terminated: ${job.body[0]?.state} ${job.body[0]?.last_error_code ?? ""}`);
    if (job.body[0]?.state === "succeeded") {
      const receipts = JSON.parse(operationalSql(
        `select coalesce(json_agg(content), '[]'::json) from net._http_response where id > ${networkMarker} and status_code = 200`,
      ));
      scheduled = receipts.some((content) => {
        try {
          const receipt = JSON.parse(content);
          return receipt.claimed > 0 && receipt.succeeded > 0;
        } catch {
          return false; // An unrelated non-JSON HTTP response is not worker proof.
        }
      });
      if (scheduled) break;
    }
    if (attempt % 10 === 0) console.log("Checking durable scheduled classification delivery...");
    await pause(1500);
  }
  check(scheduled, "pg_cron delivers an idle classification job without a mutation wake-up");

  while ((saveCounts.get(a.id) ?? 0) < 100) {
    const number = saveCounts.get(a.id);
    await save(a, {
      url: `https://example.test/performance-${randomUUID()}`,
      title: `목록 성능 표본 ${number}`, note: "정상 연결 저장 색인 비용 측정",
    });
  }
  const firstPage = await search(a);
  const secondPage = await search(a, { offset: "50" });
  check(firstPage.items.length === 50 && secondPage.items.length === 50 && !secondPage.has_more &&
      new Set([...firstPage.items, ...secondPage.items].map((item) => item.id)).size === 100,
    "performance dataset contains one member's actual one hundred items");
  for (let warmup = 0; warmup < 3; warmup++) await search(a, { q: "표본" });
  const times = [];
  for (let sample = 0; sample < 30; sample++) {
    const started = performance.now();
    await search(a, { q: ["표본", "카톡 프사", "엑셀", "자료"][sample % 4], limit: "20" });
    times.push(performance.now() - started);
  }
  const searchP95 = p95(times);
  const saveP95 = p95(saves.slice(-30));
  console.log(`Local warm API: search n=30 p95=${searchP95.toFixed(1)}ms; save+index n=30 p95=${saveP95.toFixed(1)}ms`);
  check(searchP95 <= 1000, "AT11 local active-service 100-item search p95 is at most one second");
  check(saveP95 <= 3000, "AT11 local active-service save and index p95 is at most three seconds");
} finally {
  await cleanup();
}
console.log(`${checks} real local discovery checks passed; Google, S23, OCR ingestion and production acceptance remain separate.`);
