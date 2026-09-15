import { spawnSync } from "node:child_process";

// Keep Storage enabled for private attachment uploads. Never forward the CLI
// status envelope: it contains service credentials even on a successful start.
const excluded = "studio,imgproxy,realtime,logflare,vector,supavisor,postgres-meta";
const command = process.platform === "win32" ? process.env.ComSpec || "cmd.exe" : "supabase";
const args = process.platform === "win32"
  ? ["/d", "/s", "/c", `supabase start -x ${excluded}`]
  : ["start", "-x", excluded];
const result = spawnSync(command, args, {
  encoding: "utf8",
  stdio: ["ignore", "pipe", "pipe"],
  timeout: 240_000,
  maxBuffer: 4 * 1024 * 1024,
});

if (result.status !== 0 || result.error) {
  let code = "LOCAL_BACKEND_START_FAILED";
  try {
    const envelope = JSON.parse(result.stdout || "{}");
    const candidate = envelope?.error?.code;
    if (typeof candidate === "string" && /^[A-Z][A-Za-z0-9_]{0,79}$/.test(candidate)) {
      code = candidate;
    }
  } catch {
    // Do not log arbitrary CLI output, URLs, or credentials on error.
  }
  console.error(`${code}: check Docker Desktop and the configured local ports.`);
  process.exitCode = 1;
} else {
  console.log("Local backend started with private Storage; credentials are not printed.");
}
