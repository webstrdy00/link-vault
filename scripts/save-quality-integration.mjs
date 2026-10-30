import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import { localBackendFixture } from "./local-backend-fixture.mjs";

// Disposable accounts on an empty, loopback-only backend; never production.
const fixture = localBackendFixture("save-quality");
const prefix = "/functions/v1/library-api/v1";
let checks = 0;
function check(value, message) {
  assert.ok(value, message);
  checks++;
  console.log(`PASS ${message}`);
}
function api(account, path, method = "GET", body, requestId = randomUUID()) {
  return fixture.request(`${prefix}${path}`, {
    token: account.token,
    method,
    body,
    headers: method === "GET" ? {} : { "X-Request-Id": requestId },
  });
}
async function search(account, query) {
  const response = await api(account, `/items?${new URLSearchParams({ q: query, limit: "50" })}`);
  assert.equal(response.status, 200, "Search disposable member library");
  return response.body.items;
}

async function classifiedDetail(account, itemId) {
  try {
    return await fixture.settleClassification(account, itemId);
  } catch {
    const jobs = await fixture.request(
      `/rest/v1/processing_jobs?owner_id=eq.${account.id}&item_id=eq.${itemId}&kind=eq.classify&select=state,last_error_code`,
      { token: fixture.serviceKey },
    );
    // Print only bounded fixture state/error codes, never credentials or responses.
    const states = Array.isArray(jobs.body) ? jobs.body.map((job) => ({
      state: /^[a-z_]+$/.test(job.state ?? "") ? job.state : "unknown",
      code: /^[A-Z_]+$/.test(job.last_error_code ?? "") ? job.last_error_code : null,
    })) : [];
    throw new Error(`Fixture classification did not settle: ${JSON.stringify(states)}`);
  }
}

try {
  const owner = await fixture.user();
  const other = await fixture.user();
  for (const account of [owner, other]) {
    await fixture.approve(account);
    assert.equal((await fixture.bootstrap(account)).status, 200);
  }
  const cases = [
    { text: "제주여행 맛집추천", categories: ["travel", "food"] },
    { text: "제주 여행 숙소 추천", categories: ["travel"] },
    { text: "카카오톡 프로필 사진 변경", categories: [] },
    { text: "엑셀 XLOOKUP 사용법", categories: ["work"] },
    { text: "요리법은 없고 여행 얘기만 있어요", categories: ["travel"] },
    { text: "Python regius is a nonvenomous snake", categories: [] },
  ];
  const saved = [];
  for (const [index, sample] of cases.entries()) {
    const body = {
      url: `https://example.invalid/save-quality/${randomUUID()}`,
      shared_text: sample.text,
      ...(index === 1 ? { title: "내 제주 숙소 목록", note: "다음 주 예약 확인" } : {}),
    };
    const requestId = randomUUID();
    const created = await api(owner, "/items", "POST", body, requestId);
    assert.equal(created.status, 201, `Create scenario ${index + 1}`);
    const id = created.body.item.id;
    const detail = (await classifiedDetail(owner, id)).body;
    const codes = detail.category_refs.map((category) => category.system_code).sort();
    check(JSON.stringify(codes) === JSON.stringify([...sample.categories].sort()),
      `scenario ${index + 1}: exact automatic categories`);
    check(detail.shared_text === sample.text && detail.content_excerpt === sample.text,
      `scenario ${index + 1}: raw shared text retained and useful preview returned`);
    check(index === 1 ? detail.note === body.note && detail.note_excerpt === body.note
      : detail.note_excerpt === null,
    `scenario ${index + 1}: manual memo remains separate from content`);
    const replay = await api(owner, "/items", "POST", body, requestId);
    check(replay.status === 201 && replay.body.item.id === id,
      `scenario ${index + 1}: replay preserves one item`);
    saved.push({ id, body });
  }
  const queries = [
    ["제주여행", saved[1].id],
    ["지난번 저장한 제주 숙소", saved[1].id],
    ["#맛집추천", saved[0].id],
    ["카톡 프사", saved[2].id],
    ["아까 저장한 엑셀", saved[3].id],
  ];
  for (const [query, id] of queries) {
    const results = await search(owner, query);
    check(results.some((item) => item.id === id), `retrieval: ${query}`);
    const item = results.find((item) => item.id === id);
    check(typeof item.content_excerpt === "string" && item.content_excerpt.length > 0,
      `search preview: ${query}`);
  }
  const otherResults = await search(other, "제주");
  check(otherResults.length === 0, "another member cannot retrieve these items");
  check((await api(other, `/items/${saved[0].id}`)).status === 404,
    "another member cannot open the saved detail");
  const detail = (await classifiedDetail(owner, saved[1].id)).body;
  check(detail.user_title === "내 제주 숙소 목록" && detail.note === "다음 주 예약 확인",
    "automatic classification does not overwrite authored title or memo");
  if (process.argv.includes("--live")) {
    const liveOwner = await fixture.user();
    await fixture.approve(liveOwner);
    assert.equal((await fixture.bootstrap(liveOwner)).status, 200);
    const posts = [
      {
        url: "https://www.threads.com/@nasa/post/DdXJKzbkqPg",
        phrase: "postcard from Mars",
        query: "postcard",
      },
      {
        url: "https://x.com/NASAWebb/status/1999132374647427401",
        phrase: "Webb detected",
        query: "TOI-561",
      },
      {
        url: "https://www.facebook.com/NASA/posts/first-stop-mars-next-stop-psyche-on-may-15-our-psyche-spacecraft-swung-by-mars-o/1530907268404644",
        phrase: "First stop, Mars",
        query: "Psyche",
      },
      {
        url: "https://bsky.app/profile/stsci.edu/post/3mxysey6oqx2l",
        phrase: "NASA's Nancy Grace Roman",
        query: "Roman",
      },
      {
        url: "https://www.instagram.com/visitjeju.kr/p/DPDtncZEueq/",
        phrase: "가을에 제주여행",
        query: "제주여행",
      },
    ];
    for (const post of posts) {
      const created = await api(liveOwner, "/items", "POST", { url: post.url });
      assert.equal(created.status, 201, "Store public URL without authored title or memo");
      post.id = created.body.item.id;
    }
    for (let batch = 0; batch < 3; batch++) {
      const result = await fixture.request(`${prefix}/internal/metadata`, {
        token: fixture.serviceKey,
        method: "POST",
        body: { limit: 10 },
      });
      assert.equal(result.status, 200, "Execute real metadata worker on local fixture jobs");
      if (result.body.claimed === 0) break;
    }
    for (const [index, post] of posts.entries()) {
      // Background workers may have claimed a job before the explicit worker call.
      // Await its terminal state, not merely the end of our own worker request.
      // The local fixture does not configure an external cron worker. Pump eligible
      // jobs while respecting their DB next_run_at (network retries are 60s,
      // then 300s). A live check must not fail before those ordinary retries.
      const deadline = Date.now() + 420_000;
      let nextPump = 0;
      let nextProgress = 0;
      while (Date.now() < deadline) {
        const polled = await api(liveOwner, `/items/${post.id}`);
        assert.equal(polled.status, 200);
        if (!["queued", "running"].includes(polled.body.metadata_state)) break;
        if (Date.now() >= nextProgress) {
          console.log(`Waiting for live ${index + 1}: ${polled.body.metadata_state}`);
          nextProgress = Date.now() + 30_000;
        }
        if (polled.body.metadata_state === "queued" && Date.now() >= nextPump) {
          const batch = await fixture.request(`${prefix}/internal/metadata`, {
            token: fixture.serviceKey,
            method: "POST",
            body: { limit: 10 },
          });
          assert.equal(batch.status, 200, "Pump only eligible local metadata retries");
          nextPump = Date.now() + 5_000;
        }
        await pause(250);
      }
      const current = (await classifiedDetail(liveOwner, post.id)).body;
      if (!["ready", "partial"].includes(current.metadata_state) ||
        !current.display_title?.includes(post.phrase)) {
        console.error(JSON.stringify({
          scenario: index + 1,
          source: new URL(post.url).hostname,
          state: current.metadata_state,
          code: current.extraction_meta?.error_code,
          title: current.display_title,
        }));
      }
      check(["ready", "partial"].includes(current.metadata_state) &&
        current.display_title?.includes(post.phrase),
      `live ${index + 1}: persisted representative title from external page`);
      check(current.note === null && current.content_excerpt?.includes(post.phrase) &&
        current.description?.includes(post.phrase),
      `live ${index + 1}: collected content is visible without manual memo`);
      const found = (await search(liveOwner, post.query)).find((item) => item.id === post.id);
      check(found?.content_excerpt === current.content_excerpt,
        `live ${index + 1}: collected text can be retrieved with a useful card preview`);
      if (index === 4) {
        check(current.category_refs.some((category) => category.system_code === "travel"),
          "live travel post is automatically classified after metadata enrichment");
      }
    }
  }
} finally {
  await fixture.cleanup();
}
console.log(`${checks} real local save-quality checks passed; rendered Android and Google sharing acceptance remain separate.`);
