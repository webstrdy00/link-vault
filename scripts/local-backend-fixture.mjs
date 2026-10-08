import assert from "node:assert/strict";
import { execSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";

// Status includes credentials. Keep it in memory and never print it.
export function localBackendConfig() {
  let status;
  try {
    status = execSync("npx --no-install supabase status -o json", {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch {
    throw new Error("Local Supabase status is unavailable; start the local backend.");
  }
  const config = JSON.parse(status);
  const url = new URL(config.API_URL);
  assert.equal(url.protocol, "http:");
  assert.equal(url.hostname, "127.0.0.1", "Fixtures only mutate local Supabase");
  assert.equal(url.port, "18021");
  assert.equal(url.username, "");
  assert.equal(url.password, "");
  return config;
}

export function localBackendFixture(prefix = "member") {
  assert.match(prefix, /^[a-z-]+$/);
  const config = localBackendConfig();
  const createdUsers = [];
  const serviceKey = config.SERVICE_ROLE_KEY;
  const anonKey = config.ANON_KEY;
  let checkedEmptyDatabase = false;

  async function request(path, {
    token = anonKey,
    method = "GET",
    body,
    headers = {},
    responseType = "json",
  } = {}) {
    assert.ok(path.startsWith("/") && !path.startsWith("//"));
    const response = await fetch(`${config.API_URL}${path}`, {
      method,
      headers: {
        apikey: anonKey,
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        Connection: "close",
        ...headers,
      },
      body: body === undefined ? undefined
        : body instanceof Uint8Array ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    if (responseType === "bytes") {
      return {
        status: response.status,
        body: new Uint8Array(await response.arrayBuffer()),
        contentType: response.headers.get("content-type"),
      };
    }
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }

  async function user() {
    if (!checkedEmptyDatabase) {
      for (const [table, key] of [["profiles", "id"], ["beta_members", "owner_id"]]) {
        const existing = await request(`/rest/v1/${table}?select=${key}&limit=1`, {
          token: serviceKey,
        });
        assert.equal(existing.status, 200, "Inspect local fixture safety boundary");
        assert.equal(
          existing.body.length,
          0,
          "Use an empty local member database; existing data is preserved",
        );
      }
      checkedEmptyDatabase = true;
    }
    const email = `${prefix}-${randomUUID()}@example.test`;
    const password = `Local-fixture-${randomUUID()}!`;
    const created = await request("/auth/v1/admin/users", {
      token: serviceKey,
      method: "POST",
      body: { email, password, email_confirm: true },
    });
    assert.equal(created.status, 200, "create local fixture user");
    createdUsers.push(created.body.id);
    // Real GoTrue email sessions exercise plumbing, never the Google provider.
    const session = await request("/auth/v1/token?grant_type=password", {
      method: "POST",
      body: { email, password },
    });
    assert.equal(session.status, 200, "issue real local fixture session");
    return { id: created.body.id, token: session.body.access_token, email, password };
  }

  async function approve(account) {
    const result = await request("/rest/v1/beta_members", {
      token: serviceKey,
      method: "POST",
      body: {
        owner_id: account.id,
        enabled: true,
        approved_at: new Date().toISOString(),
      },
    });
    assert.equal(result.status, 201, "approve local fixture member");
  }

  function bootstrap(account, id = randomUUID()) {
    return request("/functions/v1/library-api/v1/bootstrap", {
      token: account.token,
      method: "POST",
      body: {},
      headers: { "X-Request-Id": id },
    });
  }

  async function settleClassification(account, itemId) {
    for (let attempt = 0; attempt < 20; attempt++) {
      const detail = await request(`/functions/v1/library-api/v1/items/${itemId}`, {
        token: account.token,
      });
      assert.equal(detail.status, 200, "Read the current classification snapshot");
      if (detail.body.classification_state !== "pending") return detail;
      const batch = await request("/functions/v1/library-api/v1/internal/classify", {
        token: serviceKey,
        method: "POST",
        body: { limit: 20 },
      });
      assert.equal(batch.status, 200, "Run the real local classification worker");
      await pause(250);
    }
    throw new Error("Local classification did not reach a terminal state");
  }

  async function cleanup() {
    const failures = [];
    for (const id of createdUsers) {
      try {
        const result = await request(`/auth/v1/admin/users/${id}`, {
          token: serviceKey,
          method: "DELETE",
        });
        assert.equal(result.status, 200, "remove only this run fixture account");
      } catch (error) {
        failures.push(error);
      }
    }
    if (failures.length) throw new AggregateError(failures, "Fixture cleanup failed");
  }

  return {
    request, user, approve, bootstrap, cleanup, settleClassification, serviceKey, anonKey,
  };
}
