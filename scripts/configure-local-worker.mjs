import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { localBackendConfig } from "./local-backend-fixture.mjs";

const config = localBackendConfig();
const token = config.SERVICE_ROLE_KEY;
assert.ok(typeof token === "string" && token.length > 0);
const sqlString = (value) => `'${value.replaceAll("'", "''")}'`;
// Kong is the verified local Docker network alias, not an externally supplied host.
const workerUrl = "http://kong:8000/functions/v1/library-api/v1/internal/classify";
const sql = `
DO $configure$
DECLARE
  entry record;
  secret_id uuid;
BEGIN
  FOR entry IN SELECT * FROM (VALUES
    ('link_vault_worker_url', ${sqlString(workerUrl)}),
    ('link_vault_worker_token', ${sqlString(token)})
  ) AS configuration(name, value)
  LOOP
    SELECT id INTO secret_id FROM vault.secrets WHERE name = entry.name;
    IF FOUND THEN
      PERFORM vault.update_secret(secret_id, entry.value, entry.name,
        'Link Vault local development classification dispatch');
    ELSE
      PERFORM vault.create_secret(entry.value, entry.name,
        'Link Vault local development classification dispatch');
    END IF;
  END LOOP;
END
$configure$;
`;
// Secrets travel over stdin, never command arguments or a checked-in .env file.
const result = spawnSync("docker", [
  "exec", "-i", "supabase_db_link-vault", "psql", "-U", "postgres",
  "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-q",
], { input: sql, encoding: "utf8", timeout: 30000, windowsHide: true });
for (const output of [result.stdout, result.stderr]) {
  if (output?.trim()) console.log(output.replaceAll(token, "[redacted]"));
}
assert.equal(result.status, 0, "Configure the local classification scheduler");
console.log("Local classification scheduler configured; credentials were not printed.");
