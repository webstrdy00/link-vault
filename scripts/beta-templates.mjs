import {
  closeSync,
  openSync,
  realpathSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { isAbsolute, join, relative, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { CAPTURE_HEADERS, RETRIEVAL_TASK_HEADERS } from "./beta-acceptance.mjs";

class BetaTemplatesError extends Error {
  constructor(code) {
    super(code);
    this.name = "BetaTemplatesError";
    this.code = code;
  }
}

function outputDirectory(args) {
  if (
    args.length !== 2 || args[0] !== "--output-dir" ||
    !args[1] || args[1].startsWith("-")
  ) {
    throw new BetaTemplatesError("BETA_TEMPLATES_INVALID_ARGUMENTS");
  }

  let directory;
  try {
    directory = realpathSync(args[1]);
    if (!statSync(directory).isDirectory()) {
      throw new Error();
    }
  } catch {
    throw new BetaTemplatesError("BETA_TEMPLATES_OUTPUT_DIRECTORY_INVALID");
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
    throw new BetaTemplatesError(
      "BETA_TEMPLATES_OUTPUT_DIRECTORY_IN_REPOSITORY",
    );
  }
  return directory;
}

function writeTemplates(directory) {
  const createdPaths = [];
  try {
    for (
      const [name, headers] of [
        ["device_capture_template.csv", CAPTURE_HEADERS],
        ["retrieval_tasks_template.csv", RETRIEVAL_TASK_HEADERS],
      ]
    ) {
      const filePath = join(directory, name);
      const descriptor = openSync(filePath, "wx", 0o600);
      createdPaths.push(filePath);
      try {
        writeFileSync(descriptor, `${headers.join(",")}\n`, "utf8");
      } finally {
        closeSync(descriptor);
      }
    }
  } catch (error) {
    let cleanupFailed = false;
    for (const filePath of createdPaths) {
      try {
        unlinkSync(filePath);
      } catch (cleanupError) {
        if (cleanupError.code !== "ENOENT") cleanupFailed = true;
      }
    }
    const code = cleanupFailed
      ? "BETA_TEMPLATES_CLEANUP_FAILED"
      : error.code === "EEXIST"
      ? "BETA_TEMPLATES_OUTPUT_EXISTS"
      : "BETA_TEMPLATES_WRITE_FAILED";
    throw new BetaTemplatesError(code);
  }
}

function main() {
  try {
    writeTemplates(outputDirectory(process.argv.slice(2)));
    console.log("Created header-only beta CSV templates.");
  } catch (error) {
    console.error(
      error instanceof BetaTemplatesError
        ? error.code
        : "BETA_TEMPLATES_INTERNAL_ERROR",
    );
    process.exitCode = 1;
  }
}

if (
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main();
}
