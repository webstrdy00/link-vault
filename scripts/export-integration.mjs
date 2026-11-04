import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import {
  localBackendConfig,
  localBackendFixture,
} from "./local-backend-fixture.mjs";

const config = localBackendConfig();
const fixture = localBackendFixture("export");
const {
  request,
  user,
  approve,
  bootstrap,
  cleanup,
  settleClassification,
  serviceKey,
  anonKey,
} = fixture;
const prefix = "/functions/v1/library-api/v1";
const itemKeys = [
  "url",
  "title",
  "user_title",
  "note",
  "source",
  "categories",
  "saved_at",
].sort();
let checks = 0;
function check(condition, label) {
  assert.ok(condition, label);
  checks++;
  console.log(`PASS ${label}`);
}
function api(account, path, method = "GET", body) {
  return request(`${prefix}${path}`, {
    token: account.token,
    method,
    body,
    headers: method === "GET" ? {} : { "X-Request-Id": randomUUID() },
  });
}
async function save(account, body) {
  const result = await api(account, "/items", "POST", body);
  assert.equal(
    result.status,
    201,
    "create an owned export fixture through the real API",
  );
  return result.body.item;
}
async function patch(table, filter, body) {
  const result = await request(`/rest/v1/${table}?${filter}`, {
    token: serviceKey,
    method: "PATCH",
    body,
  });
  assert.equal(result.status, 204, "update only a disposable export fixture");
}
async function insertBatches(table, rows) {
  for (let offset = 0; offset < rows.length; offset += 100) {
    const result = await request(`/rest/v1/${table}`, {
      token: serviceKey,
      method: "POST",
      body: rows.slice(offset, offset + 100),
    });
    assert.equal(
      result.status,
      201,
      "insert only this run's explicit large snapshot fixtures",
    );
  }
}
async function exported(account) {
  const response = await fetch(`${config.API_URL}${prefix}/items/export`, {
    headers: { apikey: anonKey, Authorization: `Bearer ${account.token}` },
    redirect: "error",
    signal: AbortSignal.timeout(300_000),
  });
  assert.equal(response.status, 200, "download the authenticated export");
  assert.match(
    response.headers.get("content-type"),
    /^application\/json(?:\s*;|$)/i,
  );
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.match(response.headers.get("content-disposition"), /^attachment;/);
  assert.match(response.headers.get("x-request-id"), /^[0-9a-f-]{36}$/i);
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const digest = createHash("sha256");
  let text = "";
  let bytes = 0;
  let chunks = 0;
  for await (const chunk of response.body) {
    bytes += chunk.byteLength;
    assert.ok(
      bytes <= 64 * 1024 * 1024,
      "legal maximum fixture fits the bounded client export contract",
    );
    digest.update(chunk);
    text += decoder.decode(chunk, { stream: true });
    chunks++;
  }
  text += decoder.decode();
  // The verifier may parse its synthetic snapshot; the API and Android transfer
  // must not buffer it. Transport unit tests cover lazy forwarding separately.
  const body = JSON.parse(text);
  assert.deepEqual(Object.keys(body), ["items"]);
  assert.ok(Array.isArray(body.items));
  for (const item of body.items) {
    assert.deepEqual(Object.keys(item).sort(), itemKeys);
    assert.equal(typeof item.url, "string");
    assert.equal(typeof item.title, "string");
    assert.ok(item.user_title === null || typeof item.user_title === "string");
    assert.ok(item.note === null || typeof item.note === "string");
    assert.equal(typeof item.source, "string");
    assert.ok(
      Array.isArray(item.categories) &&
        item.categories.every((name) => typeof name === "string"),
    );
    assert.ok(Number.isFinite(Date.parse(item.saved_at)));
  }
  return { body, bytes, chunks, hash: digest.digest("hex") };
}

try {
  const a = await user();
  const b = await user();
  const pending = await user();
  for (const account of [a, b]) {
    await approve(account);
    assert.equal((await bootstrap(account)).status, 200);
  }
  check(
    (await request(`${prefix}/items/export`)).status === 401,
    "anonymous export is rejected",
  );
  check(
    (await api(pending, "/items/export")).status === 403,
    "unapproved member export is rejected",
  );
  check(
    (await exported(a)).body.items.length === 0,
    "empty owned library produces a complete empty snapshot",
  );
  const category = await api(a, "/categories", "POST", { name: "내 분류" });
  assert.equal(category.status, 201);
  const authored = await save(a, {
    url: `https://example.test/export-authored-${randomUUID()}`,
    title: "사용자 제목\n둘째 줄",
    note: '첫 줄\n"인용"과 \\ 경로\t𠮷',
    category_ids: [category.body.id],
  });
  const automatic = await save(a, {
    url: `https://example.test/export-auto-${randomUUID()}`,
  });
  await patch("items", `owner_id=eq.${a.id}&id=eq.${automatic.id}`, {
    fetched_title: "서버가 확보한 제목",
    description: "EXCLUDED_DESCRIPTION",
    body_text: "EXCLUDED_BODY",
    shared_text: "EXCLUDED_SHARED",
  });
  const deleted = await save(a, {
    url: `https://example.test/export-deleted-${randomUUID()}`,
  });
  const current = await settleClassification(a, deleted.id);
  assert.equal(
    (await api(a, `/items/${deleted.id}`, "DELETE", {
      expected_version: current.body.version,
    })).status,
    202,
  );
  await save(b, {
    url: authored.url,
    title: "다른 회원 제목",
    note: "FOREIGN_OWNER_ONLY",
  });
  const first = await exported(a);
  check(
    first.body.items.length === 2 &&
      !first.body.items.some((item) => item.url === deleted.url),
    "snapshot contains every active owned link and excludes soft-deleted links",
  );
  const own = first.body.items.find((item) => item.url === authored.url);
  check(
    own.title === authored.display_title &&
      own.user_title === authored.user_title &&
      own.note === authored.note &&
      Date.parse(own.saved_at) === Date.parse(authored.created_at),
    "full authored title, note, escapes, supplementary Unicode and saved time survive unchanged",
  );
  check(
    own.categories.length === 1 && own.categories[0] === "내 분류",
    "export uses visible category names rather than internal identifiers",
  );
  const auto = first.body.items.find((item) => item.url === automatic.url);
  check(
    auto.title === "서버가 확보한 제목" && auto.user_title === null &&
      auto.note === null,
    "automatically titled links retain a usable title and raw authored nulls",
  );
  const firstText = JSON.stringify(first.body);
  check(
    !firstText.includes("EXCLUDED_") &&
      !firstText.includes("FOREIGN_OWNER_ONLY"),
    "shared text, automatic content and same-URL other-owner data cannot enter the whitelist",
  );
  const foreign = await exported(b);
  check(
    foreign.body.items.length === 1 &&
      foreign.body.items[0].note === "FOREIGN_OWNER_ONLY",
    "another real caller receives only its own snapshot",
  );
  check(
    (await api(a, `/items/export?owner_id=${b.id}`)).status === 400,
    "query parameters cannot choose an export owner or silently filter data",
  );
  check(
    (await api(a, "/items/export", "POST", { owner_id: b.id })).status === 405,
    "export path rejects mutation methods before they can be mistaken for an item",
  );
  check(
    (await request("/rest/v1/rpc/library_export_items", {
      token: serviceKey,
      method: "POST",
      body: {},
    })).status === 403,
    "service-role RPC access cannot impersonate an exporting member",
  );

  // These are explicit maximum-field database fixtures, not 998 user/API saves.
  // Missing derived search rows deliberately prove export does not depend on
  // search-index readiness. The member suite separately proves 1000 API saves.
  const largeNote = "\u0001".repeat(3999) + "𠮷";
  const largeTitle = "𠮷".repeat(300);
  const largeRows = Array.from({ length: 998 }, (_, index) => {
    const start = `https://example.test/export-large-${index}-${randomUUID()}/`;
    const url = start + "a".repeat(4096 - start.length);
    return {
      id: randomUUID(),
      owner_id: a.id,
      original_url: url,
      normalized_url: url,
      url_hash: createHash("sha256").update(url).digest("hex"),
      source: "other",
      display_fallback: "example.test",
      user_title: largeTitle,
      note: largeNote,
      metadata_state: "unsupported",
      created_at: "2020-01-01T00:00:00Z",
    };
  });
  await insertBatches("items", largeRows);
  await insertBatches(
    "item_categories",
    largeRows.map((row) => ({
      owner_id: a.id,
      item_id: row.id,
      category_id: category.body.id,
      origin: "manual",
    })),
  );
  await patch("library_usage", `owner_id=eq.${a.id}`, {
    active_item_count: 1000,
  });
  const large = await exported(a);
  check(
    large.body.items.length === 1000 &&
      new Set(large.body.items.map((item) => item.url)).size === 1000,
    "all one thousand active links export once even without derived search rows",
  );
  const ordered = [...largeRows].sort((left, right) =>
    left.id.localeCompare(right.id)
  );
  for (let index = 0; index < ordered.length; index++) {
    const item = large.body.items[index];
    assert.ok(
      item.url === ordered[index].original_url,
      "equal saved times use stable hidden-ID ordering",
    );
    assert.ok(
      item.user_title === largeTitle && item.title === largeTitle &&
        item.note === largeNote,
      "maximum legal title, URL and escaped note fields are never truncated",
    );
    assert.deepEqual(item.categories, ["내 분류"]);
  }
  check(
    large.bytes > 30_000_000 && large.chunks > 1,
    "large escaped UTF-8 snapshot traverses the real Edge response stream",
  );
  console.log(
    `Synthetic export: items=1000 bytes=${large.bytes} chunks=${large.chunks} sha256=${large.hash}`,
  );
  const [concurrentSnapshot] = await Promise.all([
    exported(a),
    patch("categories", `owner_id=eq.${a.id}&id=eq.${category.body.id}`, {
      name: "변경된 분류",
      normalized_name: "변경된 분류",
    }),
  ]);
  const renamedItems = concurrentSnapshot.body.items.filter((item) =>
    item.user_title === largeTitle || item.url === authored.url
  );
  const labels = new Set(renamedItems.flatMap((item) => item.categories));
  check(
    concurrentSnapshot.body.items.length === 1000 &&
      renamedItems.length === 999 &&
      renamedItems.every((item) => item.categories.length === 1) &&
      labels.size === 1 &&
      ["내 분류", "변경된 분류"].includes([...labels][0]),
    "one export never mixes category names across a concurrent committed rename",
  );
  await patch("beta_members", `owner_id=eq.${a.id}`, { enabled: false });
  check(
    (await api(a, "/items/export")).status === 403,
    "revoked membership blocks an existing JWT export",
  );
  await patch("profiles", `id=eq.${b.id}`, { state: "deleting" });
  check(
    (await api(b, "/items/export")).status === 403,
    "deleting account cannot export its old active rows",
  );
} finally {
  await cleanup();
}
console.log(
  `${checks} real local export checks passed; external SAF providers and production acceptance remain separate.`,
);
