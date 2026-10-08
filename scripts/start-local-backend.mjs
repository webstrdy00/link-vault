import { spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { pathToFileURL } from "node:url";

// Never forward the CLI status envelope: it can contain service credentials.
const excluded =
  "studio,imgproxy,realtime,logflare,vector,supavisor,postgres-meta";
const healthUrl = "http://127.0.0.1:18021/functions/v1/library-api/v1/health";

export async function waitForBackendHealth({
  request = fetch,
  wait = delay,
  attempts = 30,
} = {}) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await request(healthUrl, {
        signal: AbortSignal.timeout(2000),
        redirect: "error",
        headers: { Connection: "close" },
      });
      // The Edge health route checks database readiness, not just the gateway.
      if (response.status === 200) {
        const body = await response.json();
        if (body?.status === "ok") return true;
      } else {
        await response.body?.cancel();
      }
    } catch {
      // Network failures and response bodies must never reach diagnostics.
    }
    if (attempt + 1 < attempts) await wait(1000);
  }
  return false;
}

export async function startLocalBackend({
  execute = spawnSync,
  health = waitForBackendHealth,
} = {}) {
  const command = process.platform === "win32"
    ? process.env.ComSpec || "cmd.exe"
    : "supabase";
  const args = process.platform === "win32"
    ? ["/d", "/s", "/c", `supabase start -x ${excluded}`]
    : ["start", "-x", excluded];
  let result;
  try {
    result = execute(command, args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 240_000,
      maxBuffer: 4 * 1024 * 1024,
    });
  } catch {
    return { ok: false, code: "LOCAL_BACKEND_START_FAILED" };
  }
  if (result.status !== 0 || result.error) {
    return { ok: false, code: "LOCAL_BACKEND_START_FAILED" };
  }
  try {
    if (await health()) return { ok: true };
  } catch {
    // Keep injected or unexpected health-check errors content-free as well.
  }
  return { ok: false, code: "LOCAL_BACKEND_HEALTH_UNAVAILABLE" };
}

if (
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const result = await startLocalBackend();
  if (result.ok) {
    console.log(
      "Local backend and Edge database health verified; credentials are not printed.",
    );
  } else {
    console.error(
      `${result.code}: check Docker Desktop, Edge runtime, and the configured local ports.`,
    );
    process.exitCode = 1;
  }
}
