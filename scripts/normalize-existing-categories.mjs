import { localBackendConfig } from "./local-backend-fixture.mjs";
import { norm } from "../supabase/functions/library-api/item-preparation.ts";

const OWNER_PAGE_SIZE = 100;
const MAX_OWNER_CATEGORIES = 38;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SAFE_BACKFILL_ERRORS = new Set([
  "CATEGORY_NORMALIZATION_COLLISION",
  "CATEGORY_NORMALIZATION_SNAPSHOT_INVALID",
]);

function fail(code) {
  throw new Error(code);
}

function errorCode(error, fallback) {
  return error instanceof Error && /^[A-Z][A-Z0-9_]*$/.test(error.message)
    ? error.message
    : fallback;
}

async function readJson(url, config, failureCode) {
  let response;
  try {
    response = await fetch(url, {
      headers: {
        apikey: config.SERVICE_ROLE_KEY,
        Authorization: `Bearer ${config.SERVICE_ROLE_KEY}`,
        Connection: "close",
      },
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    fail(failureCode);
  }

  if (!response.ok) fail(failureCode);

  try {
    return await response.json();
  } catch {
    fail(failureCode);
  }
}

async function* retainedOwnerIds(config) {
  let cursor = null;

  while (true) {
    const url = new URL("/rest/v1/profiles", config.API_URL);
    url.searchParams.set("select", "id");
    url.searchParams.set("order", "id.asc");
    url.searchParams.set("limit", String(OWNER_PAGE_SIZE));
    if (cursor !== null) url.searchParams.set("id", `gt.${cursor}`);

    const rows = await readJson(
      url,
      config,
      "CATEGORY_NORMALIZATION_OWNER_READ_FAILED",
    );
    if (!Array.isArray(rows) || rows.length > OWNER_PAGE_SIZE) {
      fail("CATEGORY_NORMALIZATION_OWNER_READ_FAILED");
    }

    for (const row of rows) {
      if (
        row === null || typeof row !== "object" || Array.isArray(row) ||
        !UUID_PATTERN.test(row.id) ||
        (cursor !== null && row.id <= cursor)
      ) {
        fail("CATEGORY_NORMALIZATION_OWNER_READ_FAILED");
      }
      cursor = row.id;
      yield row.id;
    }

    if (rows.length < OWNER_PAGE_SIZE) return;
  }
}

async function readOwnerSnapshot(config, ownerId) {
  const url = new URL("/rest/v1/categories", config.API_URL);
  url.searchParams.set(
    "select",
    "id,owner_id,name,normalization_version",
  );
  url.searchParams.set("owner_id", `eq.${ownerId}`);
  url.searchParams.set("order", "id.asc");
  url.searchParams.set("limit", String(MAX_OWNER_CATEGORIES + 1));

  const rows = await readJson(
    url,
    config,
    "CATEGORY_NORMALIZATION_READ_FAILED",
  );
  if (!Array.isArray(rows)) fail("CATEGORY_NORMALIZATION_READ_FAILED");
  if (rows.length > MAX_OWNER_CATEGORIES) {
    fail("CATEGORY_NORMALIZATION_SNAPSHOT_TOO_LARGE");
  }

  return rows.map((row) => {
    if (
      row === null || typeof row !== "object" || Array.isArray(row) ||
      !UUID_PATTERN.test(row.id) || row.owner_id !== ownerId ||
      typeof row.name !== "string" ||
      !Number.isInteger(row.normalization_version) ||
      (row.normalization_version !== 0 && row.normalization_version !== 1)
    ) {
      fail("CATEGORY_NORMALIZATION_READ_FAILED");
    }

    return {
      id: row.id,
      owner_id: ownerId,
      name: row.name,
      normalized_name: norm(row.name),
      normalization_version: row.normalization_version,
    };
  });
}

async function applyOwnerSnapshot(config, ownerId, snapshot) {
  const url = new URL(
    "/rest/v1/rpc/library_backfill_category_normalization",
    config.API_URL,
  );

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        apikey: config.SERVICE_ROLE_KEY,
        Authorization: `Bearer ${config.SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
        Connection: "close",
      },
      body: JSON.stringify({
        p_owner_id: ownerId,
        p_snapshot: snapshot,
      }),
      signal: AbortSignal.timeout(30_000),
    });
  } catch {
    fail("CATEGORY_NORMALIZATION_BACKFILL_FAILED");
  }

  let body = null;
  try {
    body = await response.json();
  } catch {
    // Response bodies are deliberately never surfaced.
  }

  if (!response.ok) {
    const safeCode = body && typeof body.message === "string" &&
        SAFE_BACKFILL_ERRORS.has(body.message)
      ? body.message
      : "CATEGORY_NORMALIZATION_BACKFILL_FAILED";
    fail(safeCode);
  }

  if (
    body === null || typeof body !== "object" || Array.isArray(body) ||
    !Number.isInteger(body.normalized_count) || body.normalized_count < 0 ||
    !Number.isInteger(body.indexed_item_count) || body.indexed_item_count < 0
  ) {
    fail("CATEGORY_NORMALIZATION_BACKFILL_FAILED");
  }

  return body;
}

async function main() {
  let config;
  try {
    config = localBackendConfig();
  } catch {
    fail("LOCAL_BACKEND_REQUIRED");
  }

  let ownerCount = 0;
  let normalizedCount = 0;
  let indexedItemCount = 0;
  const failures = new Map();

  for await (const ownerId of retainedOwnerIds(config)) {
    ownerCount++;
    try {
      const snapshot = await readOwnerSnapshot(config, ownerId);
      const result = await applyOwnerSnapshot(config, ownerId, snapshot);
      normalizedCount += result.normalized_count;
      indexedItemCount += result.indexed_item_count;
    } catch (error) {
      const code = errorCode(
        error,
        "CATEGORY_NORMALIZATION_BACKFILL_FAILED",
      );
      failures.set(code, (failures.get(code) ?? 0) + 1);
    }
  }

  console.log(
    `Category normalization complete: ${ownerCount} owners, ` +
      `${normalizedCount} categories, ${indexedItemCount} item indexes updated.`,
  );

  if (failures.size > 0) {
    const summary = [...failures.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([code, count]) => `${code}=${count}`)
      .join(",");
    console.error(`Category normalization failed: ${summary}.`);
    process.exitCode = 1;
  }
}

main().catch((error) => {
  const message = errorCode(
    error,
    "CATEGORY_NORMALIZATION_BACKFILL_FAILED",
  );
  console.error(message);
  process.exitCode = 1;
});
