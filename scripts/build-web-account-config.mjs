import { randomUUID } from "node:crypto";
import {
  closeSync,
  fsyncSync,
  openSync,
  realpathSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import {
  basename,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  API_BASE,
  GOOGLE_CLIENT_ID,
  validConfig,
} from "../website/account-deletion/account-deletion.js";

class WebAccountConfigError extends Error {
  constructor(code) {
    super(code);
    this.name = "WebAccountConfigError";
    this.code = code;
  }
}

export function buildWebAccountConfig(environment) {
  const names = [
    "SUPABASE_URL",
    "SUPABASE_PUBLISHABLE_KEY",
    "GOOGLE_WEB_CLIENT_ID",
  ];
  if (
    names.some((name) =>
      typeof environment?.[name] !== "string" || environment[name].length === 0
    )
  ) {
    throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_ENVIRONMENT_REQUIRED");
  }
  if (environment.SUPABASE_URL !== new URL(API_BASE).origin) {
    throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_ORIGIN_NOT_APPROVED");
  }
  const config = {
    apiBase: API_BASE,
    googleClientId: environment.GOOGLE_WEB_CLIENT_ID,
    publishableKey: environment.SUPABASE_PUBLISHABLE_KEY,
    enabled: true,
  };
  if (config.googleClientId !== GOOGLE_CLIENT_ID || !validConfig(config)) {
    throw new WebAccountConfigError(
      "WEB_ACCOUNT_CONFIG_NOT_PUBLIC_OR_APPROVED",
    );
  }
  return config;
}

function outputPath(args) {
  if (
    args.length !== 2 || args[0] !== "--output" ||
    !args[1] || args[1].startsWith("-")
  ) {
    throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_INVALID_ARGUMENTS");
  }
  const output = resolve(args[1]);
  let directory;
  try {
    directory = realpathSync(dirname(output));
    if (!statSync(directory).isDirectory()) throw new Error();
  } catch {
    throw new WebAccountConfigError(
      "WEB_ACCOUNT_CONFIG_OUTPUT_DIRECTORY_INVALID",
    );
  }
  const repository = realpathSync(
    fileURLToPath(new URL("../", import.meta.url)),
  );
  const fromRepository = relative(repository, directory);
  if (
    fromRepository === "" ||
    (!isAbsolute(fromRepository) && fromRepository !== ".." &&
      !fromRepository.startsWith(`..${sep}`))
  ) {
    throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_OUTPUT_IN_REPOSITORY");
  }
  return join(directory, basename(output));
}

function writeConfig(output, config) {
  const temporary = join(
    dirname(output),
    `.web-account-config-${randomUUID()}.tmp`,
  );
  let created = false;
  try {
    const descriptor = openSync(temporary, "wx", 0o600);
    created = true;
    try {
      writeFileSync(descriptor, `${JSON.stringify(config, null, 2)}\n`, "utf8");
      fsyncSync(descriptor);
    } finally {
      closeSync(descriptor);
    }
    // Same-directory rename publishes a complete file, replacing only this output.
    renameSync(temporary, output);
    created = false;
  } catch {
    throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_WRITE_FAILED");
  } finally {
    if (created) {
      try {
        unlinkSync(temporary);
      } catch {
        throw new WebAccountConfigError("WEB_ACCOUNT_CONFIG_CLEANUP_FAILED");
      }
    }
  }
}

function main() {
  try {
    const output = outputPath(process.argv.slice(2));
    const config = buildWebAccountConfig(process.env);
    writeConfig(output, config);
    console.log(
      "Created validated public web account configuration; values were not printed.",
    );
  } catch (error) {
    console.error(
      error instanceof WebAccountConfigError
        ? error.code
        : "WEB_ACCOUNT_CONFIG_INTERNAL_ERROR",
    );
    process.exitCode = 1;
  }
}

if (
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main();
}
