import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  cleanupRestoreResources,
  itemDeletionAuditSql,
} from "./restore-library.mjs";

const REQUESTED_AT = "2026-09-16T01:00:00.000Z";
const CONTENT_FIELDS = [
  "original_url",
  "normalized_url",
  "url_hash",
  "source",
  "display_fallback",
  "shared_text",
  "user_title",
  "fetched_title",
  "description",
  "body_text",
  "note",
  "metadata_state",
];
const DEPENDENT_TABLES = [
  "public.item_categories",
  "public.item_category_controls",
  "public.item_classification",
  "public.item_search",
  "public.assets",
  "storage.objects",
];

function sanitizedItem() {
  return {
    ...Object.fromEntries(CONTENT_FIELDS.map((field) => [field, null])),
    deleted_at: REQUESTED_AT,
    extraction_meta: {},
  };
}

function auditClauses() {
  const sql = itemDeletionAuditSql().trim();
  const clauses = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < sql.length; index++) {
    if (sql[index] === "(") depth++;
    if (sql[index] === ")") depth--;
    assert.ok(depth >= 0, "balanced audit predicate");
    if (depth !== 0) continue;
    const separator = /^\s+or\s+/.exec(sql.slice(index));
    if (!separator) continue;
    clauses.push(sql.slice(start, index).trim());
    index += separator[0].length - 1;
    start = index + 1;
  }
  assert.equal(depth, 0);
  clauses.push(sql.slice(start).trim());
  return clauses;
}

// Mock correlated EXISTS results, not a PostgreSQL engine. This exercises the
// generated predicate's branches; SQL execution remains an integration check.
function mockedItemAuditFails({
  tombstone = REQUESTED_AT,
  item = null,
  jobs = [],
  dependents = [],
} = {}) {
  const results = auditClauses().map((clause) => {
    const match =
      /^(not\s+)?exists\s*\(\s*select 1 from ([a-z_]+\.[a-z_]+) as ([a-z_]+)\s+where ([\s\S]*)\)$/
        .exec(clause);
    assert.ok(match, `supported EXISTS clause: ${clause}`);
    const [, negated, table, alias, conditions] = match;
    let exists;
    if (table === "storage.objects") {
      assert.match(conditions, /object\.bucket_id = 'library-images'/);
      assert.match(
        conditions,
        /object\.name like deletion\.owner_id::text \|\| '\/' \|\| deletion\.item_id::text \|\| '\/%'/,
      );
    } else {
      assert.ok(conditions.includes(`${alias}.owner_id = deletion.owner_id`));
      const itemColumn = table === "public.items" ? "id" : "item_id";
      assert.ok(
        conditions.includes(`${alias}.${itemColumn} = deletion.item_id`),
      );
    }
    if (table === "private.item_deletion_tombstones") {
      assert.match(
        conditions,
        /tombstone\.deleted_at <= deletion\.requested_at/,
      );
      exists = tombstone !== null && tombstone <= REQUESTED_AT;
    } else if (table === "public.items") {
      const contentFields = [
        ...conditions.matchAll(/item\.([a-z_]+) is not null/g),
      ]
        .map((field) => field[1]);
      const testsContent = contentFields.length > 0;
      exists = item !== null && (!testsContent ||
        (/item\.deleted_at is null/.test(conditions) &&
          item.deleted_at === null) ||
        contentFields.some((field) => item[field] !== null) ||
        (/item\.extraction_meta <> '\{\}'::jsonb/.test(conditions) &&
          Object.keys(item.extraction_meta).length > 0));
    } else if (table === "public.processing_jobs") {
      assert.match(
        conditions,
        /job\.state in \('queued', 'running', 'retry'\)/,
      );
      assert.match(conditions, /job\.lease_until is not null/);
      assert.match(conditions, /job\.lease_token is not null/);
      exists = jobs.some((job) =>
        ["queued", "running", "retry"].includes(job.state) ||
        job.lease_until !== null || job.lease_token !== null
      );
    } else {
      assert.ok(DEPENDENT_TABLES.includes(table), `known dependent: ${table}`);
      exists = dependents.includes(table);
    }
    return negated ? !exists : exists;
  });
  assert.equal(results.length, 9);
  return results.some(Boolean);
}

test("item audit accepts a post-snapshot or physically purged item with a tombstone", () => {
  assert.equal(mockedItemAuditFails({ item: null }), false);
  assert.equal(mockedItemAuditFails({ item: sanitizedItem() }), false);
});

test("absent and sanitized items still require a timely tombstone", () => {
  for (const item of [null, sanitizedItem()]) {
    assert.equal(mockedItemAuditFails({ item, tombstone: null }), true);
    assert.equal(
      mockedItemAuditFails({
        item,
        tombstone: "2026-09-16T01:00:00.001Z",
      }),
      true,
    );
    assert.equal(
      mockedItemAuditFails({
        item,
        tombstone: "2026-09-16T00:59:59.999Z",
      }),
      false,
    );
  }
});

test("a tombstone does not conceal a live item or retained content", () => {
  assert.equal(
    mockedItemAuditFails({
      item: { ...sanitizedItem(), deleted_at: null },
    }),
    true,
  );
  for (const field of CONTENT_FIELDS) {
    assert.equal(
      mockedItemAuditFails({
        item: { ...sanitizedItem(), [field]: "retained fixture content" },
      }),
      true,
      field,
    );
  }
  assert.equal(
    mockedItemAuditFails({
      item: { ...sanitizedItem(), extraction_meta: { retained: true } },
    }),
    true,
  );
});

test("even an absent item fails audit when related content or objects remain", () => {
  for (const table of DEPENDENT_TABLES) {
    assert.equal(mockedItemAuditFails({ dependents: [table] }), true, table);
  }
});

test("item audit rejects active jobs and terminal jobs with outstanding leases", () => {
  const job = { state: "cancelled", lease_until: null, lease_token: null };
  assert.equal(mockedItemAuditFails({ jobs: [job] }), false);
  for (const state of ["queued", "running", "retry"]) {
    assert.equal(
      mockedItemAuditFails({ jobs: [{ ...job, state }] }),
      true,
      state,
    );
  }
  for (const field of ["lease_until", "lease_token"]) {
    assert.equal(
      mockedItemAuditFails({
        jobs: [{ ...job, [field]: "outstanding lease" }],
      }),
      true,
      field,
    );
  }
});

async function cleanupFixture(t, responses) {
  const temporaryDirectory = await mkdtemp(
    join(tmpdir(), "link-vault-restore-test-"),
  );
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const sentinel = join(temporaryDirectory, "database.dump");
  await writeFile(sentinel, "synthetic plaintext dump");
  const calls = [];
  const options = {
    containerStarted: true,
    containerName: "link-vault-restore-test",
    token: "owned-token",
    temporaryDirectory,
  };
  return {
    calls,
    options,
    sentinel,
    cleanup: async (containerStarted = true) => {
      await cleanupRestoreResources({ ...options, containerStarted }, {
        execute: async (_command, args, settings) => {
          assert.equal(settings.allowFailure, true);
          calls.push(args);
          assert.ok(responses.length > 0, "no unexpected Docker command");
          const response = responses.shift();
          if (response instanceof Error) throw response;
          return response;
        },
        remove: async (path, settings) => {
          calls.push(["directory"]);
          assert.equal(path, temporaryDirectory);
          assert.deepEqual(settings, { recursive: true, force: true });
          await rm(path, settings);
        },
      });
    },
  };
}

const missingContainer = () => ({
  status: 1,
  stdout: "",
  stderr: "No such object",
});
const ownedContainer = () => ({ status: 0, stdout: "owned-token" });

test("failed container creation cleans plaintext when the daemon confirms absence", async (t) => {
  const fixture = await cleanupFixture(t, [
    missingContainer(),
    { status: 0, stdout: "" },
  ]);
  const creationError = Object.assign(
    new Error("ISOLATED_CONTAINER_START_FAILED"),
    {
      code: "ISOLATED_CONTAINER_START_FAILED",
    },
  );
  await assert.rejects(async () => {
    try {
      throw creationError;
    } finally {
      await fixture.cleanup();
    }
  }, (error) => error === creationError);
  assert.deepEqual(fixture.calls, [
    [
      "inspect",
      "--format",
      '{{index .Config.Labels "link-vault.restore-token"}}',
      "link-vault-restore-test",
    ],
    [
      "container",
      "ls",
      "--all",
      "--filter",
      "name=^/link-vault-restore-test$",
      "--format",
      "{{.Names}}",
    ],
    ["directory"],
  ]);
  await assert.rejects(stat(fixture.options.temporaryDirectory), {
    code: "ENOENT",
  });
});

test("an attempted creation that left an owned container removes it before plaintext", async (t) => {
  const fixture = await cleanupFixture(t, [ownedContainer(), {
    status: 0,
    stdout: "",
  }]);
  await fixture.cleanup();
  assert.deepEqual(fixture.calls.map((args) => args[0]), [
    "inspect",
    "rm",
    "directory",
  ]);
  assert.deepEqual(fixture.calls[1], [
    "rm",
    "--force",
    "link-vault-restore-test",
  ]);
  await assert.rejects(stat(fixture.options.temporaryDirectory), {
    code: "ENOENT",
  });
});

test("no creation attempt needs no Docker probe before removing plaintext", async (t) => {
  const fixture = await cleanupFixture(t, []);
  await fixture.cleanup(false);
  assert.deepEqual(fixture.calls, [["directory"]]);
  await assert.rejects(stat(fixture.options.temporaryDirectory), {
    code: "ENOENT",
  });
});

for (
  const [name, listed] of [
    ["daemon unavailable", {
      status: 1,
      stdout: "",
      stderr: "connection failed",
    }],
    ["container still exists", {
      status: 0,
      stdout: "link-vault-restore-test",
    }],
    ["listing interrupted", { status: 0, stdout: "", signal: "SIGTERM" }],
    ["listing has no output field", { status: 0 }],
  ]
) {
  test(`unconfirmed absence preserves plaintext: ${name}`, async (t) => {
    const fixture = await cleanupFixture(t, [missingContainer(), listed]);
    await assert.rejects(fixture.cleanup(), {
      code: "RESTORE_CONTAINER_CLEANUP_UNCONFIRMED",
    });
    assert.deepEqual(fixture.calls.map((args) => args[0]), [
      "inspect",
      "container",
    ]);
    assert.equal(
      await readFile(fixture.sentinel, "utf8"),
      "synthetic plaintext dump",
    );
  });
}

for (
  const [name, responses] of [
    ["inspection", [new Error("mock transport failed")]],
    ["listing", [missingContainer(), new Error("mock transport failed")]],
  ]
) {
  test(`transport error during ${name} preserves plaintext`, async (t) => {
    const fixture = await cleanupFixture(t, responses);
    await assert.rejects(fixture.cleanup(), /mock transport failed/);
    assert.equal(
      await readFile(fixture.sentinel, "utf8"),
      "synthetic plaintext dump",
    );
    assert.ok(
      !fixture.calls.some((args) => ["rm", "directory"].includes(args[0])),
    );
  });
}

test("a foreign ownership label cannot trigger container or plaintext removal", async (t) => {
  const fixture = await cleanupFixture(t, [{
    status: 0,
    stdout: "foreign-token",
  }]);
  await assert.rejects(fixture.cleanup(), {
    code: "RESTORE_CONTAINER_OWNERSHIP_LOST",
  });
  assert.deepEqual(fixture.calls.map((args) => args[0]), ["inspect"]);
  assert.equal(
    await readFile(fixture.sentinel, "utf8"),
    "synthetic plaintext dump",
  );
});

test("an interrupted inspection cannot establish ownership", async (t) => {
  const fixture = await cleanupFixture(t, [
    { ...ownedContainer(), signal: "SIGTERM" },
    { status: 0, stdout: "link-vault-restore-test" },
  ]);
  await assert.rejects(fixture.cleanup(), {
    code: "RESTORE_CONTAINER_CLEANUP_UNCONFIRMED",
  });
  assert.deepEqual(fixture.calls.map((args) => args[0]), [
    "inspect",
    "container",
  ]);
  assert.equal(
    await readFile(fixture.sentinel, "utf8"),
    "synthetic plaintext dump",
  );
});

for (
  const [name, removed] of [
    ["removal failed", { status: 1, stdout: "" }],
    ["removal interrupted", { status: 0, stdout: "", signal: "SIGTERM" }],
  ]
) {
  test(`unconfirmed container removal preserves plaintext: ${name}`, async (t) => {
    const fixture = await cleanupFixture(t, [ownedContainer(), removed]);
    await assert.rejects(fixture.cleanup(), {
      code: "RESTORE_CONTAINER_CLEANUP_FAILED",
    });
    assert.deepEqual(fixture.calls.map((args) => args[0]), ["inspect", "rm"]);
    assert.equal(
      await readFile(fixture.sentinel, "utf8"),
      "synthetic plaintext dump",
    );
  });
}
