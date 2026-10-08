import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { localBackendFixture } from "./local-backend-fixture.mjs";

const { request, user, approve, bootstrap, cleanup, settleClassification, serviceKey } =
  localBackendFixture();
let checks = 0;

function check(condition, label) {
  assert.ok(condition, label);
  checks++;
  console.log(`PASS ${label}`);
}
try {
  const health = await request("/functions/v1/library-api/v1/health");
  check(
    health.status === 200 && health.body.status === "ok",
    "Edge function health",
  );
  const invalid = await request("/functions/v1/library-api/v1/me", {
    token: "not-a-jwt",
  });
  check(invalid.status === 401, "invalid JWT rejected by real auth");
  const a = await user();
  const b = await user();
  const pending = await request("/functions/v1/library-api/v1/me", {
    token: a.token,
  });
  check(
    pending.status === 200 && pending.body.state === "pending_approval",
    "unapproved account has minimum state",
  );
  const denied = await bootstrap(a);
  check(
    denied.status === 403 && denied.body.error.code === "BETA_ACCESS_REQUIRED",
    "bootstrap requires beta approval",
  );
  await approve(a);
  await approve(b);
  const requestId = randomUUID();
  const [first, repeated] = await Promise.all([
    bootstrap(a, requestId),
    bootstrap(a, requestId),
  ]);
  check(
    first.status === 200 && repeated.status === 200,
    "concurrent bootstrap succeeds idempotently",
  );
  check(
    first.body.profile.id === a.id && repeated.body.profile.id === a.id,
    "bootstrap binds caller identity",
  );
  check(
    first.body.categories.length === 8 && repeated.body.categories.length === 8,
    "exactly eight system categories",
  );
  check(first.body.usage.active_item_count === 0, "new member usage is empty");
  await bootstrap(b);
  const itemRequest = randomUUID();
  const itemBody = {
    url: "https://example.com/article?utm_source=integration&id=17#section",
    title: "통합 검증 링크",
    note: "카톡 프사 설정",
  };
  const createItem = (account, body, id = randomUUID()) =>
    request("/functions/v1/library-api/v1/items", {
      token: account.token,
      method: "POST",
      body,
      headers: { "X-Request-Id": id },
    });
  const [saved, replay] = await Promise.all([
    createItem(a, itemBody, itemRequest),
    createItem(a, itemBody, itemRequest),
  ]);
  check(
    saved.status === 201 && replay.status === 201,
    "concurrent save retry commits once",
  );
  check(
    saved.body.item.id === replay.body.item.id,
    "save retry returns same item identity",
  );
  const duplicate = await createItem(a, {
    url: "https://EXAMPLE.COM:443/article?utm_source=integration&id=17#section",
    note: "should not overwrite previous note",
  });
  check(
    duplicate.status === 200 && duplicate.body.duplicate === true,
    "normalized URL duplicate detected",
  );
  const mismatched = await createItem(
    a,
    { ...itemBody, note: "different" },
    itemRequest,
  );
  check(
    mismatched.status === 409 &&
      mismatched.body.error.code === "IDEMPOTENCY_MISMATCH",
    "changed request body cannot reuse request ID",
  );
  const detail = await settleClassification(a, saved.body.item.id);
  check(
    detail.status === 200 && detail.body.note === itemBody.note,
    "duplicate save does not overwrite original note",
  );
  const patchItem = (account, body, id = randomUUID()) =>
    request(`/functions/v1/library-api/v1/items/${saved.body.item.id}`, {
      token: account.token,
      method: "PATCH",
      body,
      headers: { "X-Request-Id": id },
    });
  const editId = randomUUID();
  const editBody = {
    expected_version: detail.body.version,
    note: "엑셀 수정 메모",
    title: null,
  };
  const edited = await patchItem(a, editBody, editId);
  check(
    edited.status === 200 && edited.body.note === editBody.note &&
      edited.body.user_title === null,
    "PATCH updates note and clears the optional title",
  );
  const replayedEdit = await patchItem(a, editBody, editId);
  check(
    replayedEdit.status === 200 &&
      replayedEdit.body.version >= edited.body.version &&
      replayedEdit.body.note === editBody.note,
    "successful PATCH replay wins before stale version checking",
  );
  const changedEdit = await patchItem(a, {
    ...editBody,
    note: "changed request",
  }, editId);
  check(
    changedEdit.status === 409 &&
      changedEdit.body.error.code === "IDEMPOTENCY_MISMATCH",
    "PATCH request ID cannot be reused for changed input",
  );
  const staleEditId = randomUUID();
  const staleEdit = await patchItem(a, editBody, staleEditId);
  check(
    staleEdit.status === 409 &&
      staleEdit.body.error.code === "VERSION_CONFLICT",
    "new stale-version edit stops instead of overwriting",
  );
  const rewrittenConflict = await patchItem(a, {
    ...editBody,
    expected_version: edited.body.version,
  }, staleEditId);
  check(
    rewrittenConflict.status === 409 &&
      rewrittenConflict.body.error.code === "IDEMPOTENCY_MISMATCH",
    "a conflicted request cannot change its body while reusing the same ID",
  );
  // Classification legitimately advances item.version. Bind this deliberate
  // edit race to the current snapshot, not a pre-classification version.
  const currentEdit = await settleClassification(a, saved.body.item.id);
  const concurrentEdits = await Promise.all([
    patchItem(a, {
      expected_version: currentEdit.body.version,
      note: "경쟁 메모 하나",
    }),
    patchItem(a, {
      expected_version: currentEdit.body.version,
      note: "경쟁 메모 둘",
    }),
  ]);
  check(
    concurrentEdits.filter((result) => result.status === 200).length === 1 &&
      concurrentEdits.filter((result) => result.status === 409).length === 1,
    "two edits of the same version commit only one winner",
  );
  const winner = await settleClassification(a, saved.body.item.id);
  const cleared = await patchItem(a, {
    expected_version: winner.body.version,
    note: null,
  });
  check(
    cleared.status === 200 && cleared.body.note === null,
    "explicit null clears the stored note",
  );
  const indexAfterClear = await request(
    `/rest/v1/item_search?item_id=eq.${saved.body.item.id}&select=normalized_fields,alias_concepts`,
    { token: a.token },
  );
  check(
    indexAfterClear.status === 200 && indexAfterClear.body.length === 1 &&
      indexAfterClear.body[0].normalized_fields.note === "" &&
      indexAfterClear.body[0].alias_concepts.note.length === 0,
    "note clearing removes normalized text and alias concepts in the same write",
  );
  const foreignEdit = await patchItem(b, {
    expected_version: cleared.body.version,
    note: "foreign",
  });
  check(foreignEdit.status === 404, "another account cannot edit the item");
  const forbiddenUrl = await patchItem(a, {
    expected_version: cleared.body.version,
    url: "https://example.org/replacement",
  });
  check(forbiddenUrl.status === 400, "PATCH cannot replace the original URL");
  const forbiddenDetail = await request(
    `/functions/v1/library-api/v1/items/${saved.body.item.id}`,
    { token: b.token },
  );
  check(
    forbiddenDetail.status === 404,
    "other account item detail not disclosed",
  );
  const fakeOwner = await createItem(a, { ...itemBody, owner_id: b.id });
  check(fakeOwner.status === 400, "client supplied owner rejected");
  const forgedWrite = await request("/rest/v1/rpc/library_create_item", {
    token: a.token,
    method: "POST",
    body: {
      p_owner_id: b.id,
      p_request_id: randomUUID(),
      p_body: itemBody,
      p_prepared: {},
    },
  });
  check(
    forgedWrite.status === 403,
    "derived-data write RPC restricted to server",
  );
  const savedB = await createItem(b, {
    url: "https://example.com/private-b",
    note: "B only",
  });
  check(savedB.status === 201, "second member saves own item");
  const listed = await request(
    "/functions/v1/library-api/v1/items?limit=20&offset=0",
    { token: a.token },
  );
  check(
    listed.status === 200 && listed.body.items.length === 1 &&
      listed.body.items[0].id === saved.body.item.id,
    "list contains only caller saved item",
  );
  const hiddenB = await request(
    `/rest/v1/item_search?owner_id=eq.${b.id}&select=item_id`,
    { token: a.token },
  );
  check(
    hiddenB.status === 200 && hiddenB.body.length === 0,
    "search derivatives are also owner isolated",
  );
  const invalidPage = await request(
    "/functions/v1/library-api/v1/items?limit=51",
    { token: a.token },
  );
  check(invalidPage.status === 400, "list page limit enforced");
  const resetFixtureRateWindow = async () => {
    // Advance only this fixture's rate window administratively so the test need
    // not wait ten real minutes to reach 99 actual rows. Clients cannot do this.
    const reset = await request(
      `/rest/v1/api_rate_buckets?owner_id=eq.${a.id}`,
      {
        token: serviceKey,
        method: "DELETE",
      },
    );
    assert.equal(reset.status, 204);
  };
  for (let index = 0; index < 98; index++) {
    if (index % 10 === 0) await resetFixtureRateWindow();
    const seeded = await createItem(a, {
      url: `https://example.com/quota-${index}`,
    });
    assert.equal(seeded.status, 201, "create actual quota fixture row");
    if (index === 9) {
      const rateLimited = await createItem(a, {
        url: "https://example.com/rate-rejected",
      });
      check(
        rateLimited.status === 429,
        "eleventh new save in one minute is rate limited",
      );
    }
  }
  await resetFixtureRateWindow();
  const quotaRace = await Promise.all([
    createItem(a, { url: "https://example.com/quota-race-one" }),
    createItem(a, { url: "https://example.com/quota-race-two" }),
  ]);
  check(
    quotaRace.filter((result) => result.status === 201).length === 1 &&
      quotaRace.filter((result) =>
          result.status === 409 &&
          result.body.error.code === "ITEM_LIMIT_REACHED"
        ).length === 1,
    "two concurrent saves at 99 items admit only one",
  );
  const quotaUsage = await request("/functions/v1/library-api/v1/me", {
    token: a.token,
  });
  check(
    quotaUsage.status === 200 &&
      quotaUsage.body.usage.active_item_count === 100,
    "usage stays at one hundred after the quota race",
  );
  const quotaPage = await request(
    "/functions/v1/library-api/v1/items?limit=50&offset=50",
    { token: a.token },
  );
  check(
    quotaPage.status === 200 && quotaPage.body.items.length === 50 &&
      !quotaPage.body.has_more,
    "one hundred actual rows paginate without overflow",
  );
  const cross = await request(
    `/rest/v1/categories?owner_id=eq.${b.id}&select=id`,
    { token: a.token },
  );
  check(
    cross.status === 200 && cross.body.length === 0,
    "RLS blocks cross-account categories",
  );
  const own = await request(
    `/rest/v1/categories?owner_id=eq.${a.id}&select=id`,
    { token: a.token },
  );
  check(
    own.status === 200 && own.body.length === 8,
    "RLS allows own active categories",
  );
  const write = await request("/rest/v1/categories", {
    token: a.token,
    method: "POST",
    body: { owner_id: a.id, name: "forbidden", kind: "custom" },
  });
  check(write.status === 403, "direct category writes denied");
  const revoke = await request(`/rest/v1/beta_members?owner_id=eq.${a.id}`, {
    token: serviceKey,
    method: "PATCH",
    body: { enabled: false },
  });
  assert.equal(revoke.status, 204);
  const revoked = await bootstrap(a);
  check(revoked.status === 403, "revocation blocks existing JWT bootstrap");
  const hidden = await request("/rest/v1/categories?select=id", {
    token: a.token,
  });
  check(
    hidden.status === 200 && hidden.body.length === 0,
    "revocation hides existing member data",
  );
  const deleting = await request(`/rest/v1/profiles?id=eq.${b.id}`, {
    token: serviceKey,
    method: "PATCH",
    body: {
      state: "deleting",
      deletion_requested_at: new Date().toISOString(),
    },
  });
  assert.equal(deleting.status, 204);
  const deletedBootstrap = await bootstrap(b);
  check(
    deletedBootstrap.status === 403 &&
      deletedBootstrap.body.error.code === "ACCOUNT_DELETING",
    "deleting state cannot bootstrap again",
  );
  const deletedMe = await request("/functions/v1/library-api/v1/me", {
    token: b.token,
  });
  check(
    deletedMe.status === 200 && deletedMe.body.state === "deleting",
    "deleting account receives only state",
  );
  for (let index = 0; index < 4; index++) await approve(await user());
  const contenders = [await user(), await user()];
  const approvals = await Promise.all(
    contenders.map((account) =>
      request("/rest/v1/beta_members", {
        token: serviceKey,
        method: "POST",
        body: {
          owner_id: account.id,
          enabled: true,
          approved_at: new Date().toISOString(),
        },
      })
    ),
  );
  check(
    approvals.filter((result) => result.status === 201).length === 1 &&
      approvals.filter((result) => result.status === 400).length === 1,
    "concurrent seventh approval cannot exceed six enabled members",
  );
} finally {
  await cleanup();
}
console.log(
  `${checks} real local Auth/API/RLS checks passed; Google provider not exercised.`,
);
