import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  scrypt as scryptCallback,
} from "node:crypto";
import { createReadStream } from "node:fs";
import { access, link, mkdir, open, rm, stat, unlink } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { promisify } from "node:util";

const scrypt = promisify(scryptCallback);
const ENCRYPTED_MAGIC = Buffer.from("LVAES001", "ascii");
const PACKAGE_MAGIC = Buffer.from("LVPACK01", "ascii");
const TAG_BYTES = 16;
const NONCE_BYTES = 12;
const SALT_BYTES = 16;
const MAX_HEADER_BYTES = 64 * 1024;
const MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
const COPY_CHUNK_BYTES = 256 * 1024;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const HASH_PATTERN = /^[0-9a-f]{64}$/;
const EXTENSION_NAME_PATTERN = /^[a-z][a-z0-9_-]{0,62}$/;
const EXTENSION_VERSION_PATTERN =
  /^[0-9]+(?:[.][0-9]+){0,7}(?:[-+][a-z0-9][a-z0-9._-]{0,31})?$/i;
const SCHEMA_NAME_PATTERN = /^[a-z_][a-z0-9_]{0,62}$/;
const BACKUP_SCHEMAS = Object.freeze(["public", "private", "auth", "storage"]);

export const BACKUP_FORMAT_VERSION = 1;
export const BACKUP_PASSWORD_ENV = "LINK_VAULT_BACKUP_PASSWORD";
export const BACKUP_KEY_ENV = "LINK_VAULT_BACKUP_KEY_BASE64";
export const LOCAL_PROJECT_ID = "link-vault";

function fail(code) {
  const error = new Error(code);
  error.code = code;
  throw error;
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index]);
}

function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function validateDatabaseExtensions(extensions) {
  if (
    !Array.isArray(extensions) || extensions.length < 1 ||
    extensions.length > 64
  ) {
    fail("DATABASE_EXTENSIONS_INVALID");
  }
  let priorName = "";
  for (const extension of extensions) {
    if (
      !exactKeys(extension, ["name", "version", "schema"]) ||
      typeof extension.name !== "string" ||
      !EXTENSION_NAME_PATTERN.test(extension.name) ||
      typeof extension.version !== "string" ||
      extension.version.length > 64 ||
      !EXTENSION_VERSION_PATTERN.test(extension.version) ||
      typeof extension.schema !== "string" ||
      !SCHEMA_NAME_PATTERN.test(extension.schema) ||
      extension.name <= priorName
    ) {
      fail("DATABASE_EXTENSIONS_INVALID");
    }
    priorName = extension.name;
  }
  return extensions;
}

function parseUtc(value, code) {
  const match = typeof value === "string"
    ? /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/.exec(value)
    : null;
  if (!match) fail(code);
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (
    !Number.isFinite(milliseconds) ||
    new Date(milliseconds).toISOString().slice(0, 19) !== match[1]
  ) fail(code);
  const microseconds = BigInt((match[2] ?? "").padEnd(6, "0"));
  return BigInt(milliseconds) * 1000n + microseconds;
}

export function assertCanonicalPackagePath(value) {
  if (
    typeof value !== "string" || value.length === 0 || value.length > 512 ||
    isAbsolute(value) || value.includes("\\") || value.startsWith("/") ||
    value.split("/").some((part) =>
      part === "" || part === "." || part === ".."
    ) ||
    !/^[A-Za-z0-9._/-]+$/.test(value)
  ) {
    fail("INVALID_PACKAGE_PATH");
  }
  return value;
}

export function assertOutsideWorktree(outputPath, worktree = process.cwd()) {
  if (typeof outputPath !== "string" || outputPath.length === 0) {
    fail("OUTPUT_PATH_REQUIRED");
  }
  const absolute = resolve(outputPath);
  const relation = relative(resolve(worktree), absolute);
  if (
    relation === "" ||
    (!relation.startsWith(`..${sep}`) && relation !== ".." &&
      !isAbsolute(relation))
  ) {
    fail("OUTPUT_MUST_BE_OUTSIDE_WORKTREE");
  }
  return absolute;
}

export function backupCredentialFromEnv(environment = process.env) {
  const password = environment[BACKUP_PASSWORD_ENV];
  const encodedKey = environment[BACKUP_KEY_ENV];
  if (password && encodedKey) fail("BACKUP_SECRET_AMBIGUOUS");
  if (!password && !encodedKey) fail("BACKUP_SECRET_MISSING");
  if (password) {
    if (Buffer.byteLength(password, "utf8") < 12) {
      fail("BACKUP_PASSWORD_TOO_SHORT");
    }
    return { kind: "password", password };
  }
  if (!/^[A-Za-z0-9+/]{43}=$/.test(encodedKey)) fail("BACKUP_KEY_INVALID");
  const key = Buffer.from(encodedKey, "base64");
  if (key.length !== 32 || key.toString("base64") !== encodedKey) {
    fail("BACKUP_KEY_INVALID");
  }
  return { kind: "raw", key };
}

async function deriveKey(credential, kdf) {
  if (credential?.kind === "raw") {
    if (!exactKeys(kdf, ["name"]) || kdf.name !== "raw") {
      fail("BACKUP_SECRET_TYPE_MISMATCH");
    }
    if (!Buffer.isBuffer(credential.key) || credential.key.length !== 32) {
      fail("BACKUP_KEY_INVALID");
    }
    return credential.key;
  }
  if (
    credential?.kind !== "password" || typeof credential.password !== "string"
  ) {
    fail("BACKUP_SECRET_INVALID");
  }
  if (
    !exactKeys(kdf, ["name", "salt", "N", "r", "p"]) || kdf.name !== "scrypt" ||
    kdf.N !== 32768 || kdf.r !== 8 || kdf.p !== 1 ||
    typeof kdf.salt !== "string"
  ) {
    fail("BACKUP_KDF_INVALID");
  }
  const salt = Buffer.from(kdf.salt, "base64");
  if (salt.length !== SALT_BYTES || salt.toString("base64") !== kdf.salt) {
    fail("BACKUP_KDF_INVALID");
  }
  return await scrypt(credential.password, salt, 32, {
    N: kdf.N,
    r: kdf.r,
    p: kdf.p,
    maxmem: 64 * 1024 * 1024,
  });
}

function encryptedHeader(credential) {
  const nonce = randomBytes(NONCE_BYTES);
  const kdf = credential.kind === "password"
    ? {
      name: "scrypt",
      salt: randomBytes(SALT_BYTES).toString("base64"),
      N: 32768,
      r: 8,
      p: 1,
    }
    : { name: "raw" };
  const header = {
    format: "link-vault-encrypted-backup",
    version: BACKUP_FORMAT_VERSION,
    cipher: "aes-256-gcm",
    nonce: nonce.toString("base64"),
    kdf,
  };
  const bytes = Buffer.from(JSON.stringify(header), "utf8");
  if (bytes.length > MAX_HEADER_BYTES) fail("BACKUP_HEADER_INVALID");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  const prefix = Buffer.concat([ENCRYPTED_MAGIC, length, bytes]);
  return { header, nonce, prefix };
}

async function publishExclusive(temporaryPath, destinationPath) {
  try {
    await link(temporaryPath, destinationPath);
    await unlink(temporaryPath);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    if (error?.code === "EEXIST") fail("OUTPUT_ALREADY_EXISTS");
    fail("OUTPUT_PUBLISH_FAILED");
  }
  try {
    const directory = await open(dirname(destinationPath), "r");
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  } catch {
    // The file itself is fsynced. Windows does not consistently permit directory handles.
  }
}

export async function encryptPackageFile(
  { plaintextPath, outputPath, credential },
) {
  const destination = resolve(outputPath);
  await mkdir(dirname(destination), { recursive: false }).catch((error) => {
    if (error?.code !== "EEXIST") fail("OUTPUT_DIRECTORY_UNAVAILABLE");
  });
  try {
    await access(destination);
    fail("OUTPUT_ALREADY_EXISTS");
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  const temporary = `${destination}.partial-${randomBytes(12).toString("hex")}`;
  const output = await open(temporary, "wx", 0o600).catch(() =>
    fail("OUTPUT_CREATE_FAILED")
  );
  try {
    const { header, nonce, prefix } = encryptedHeader(credential);
    const key = await deriveKey(credential, header.kdf);
    const cipher = createCipheriv("aes-256-gcm", key, nonce);
    cipher.setAAD(prefix);
    await output.write(prefix);
    for await (
      const chunk of createReadStream(plaintextPath, {
        highWaterMark: COPY_CHUNK_BYTES,
      })
    ) {
      const encrypted = cipher.update(chunk);
      if (encrypted.length) await output.write(encrypted);
    }
    const final = cipher.final();
    if (final.length) await output.write(final);
    await output.write(cipher.getAuthTag());
    await output.sync();
  } catch (error) {
    await output.close().catch(() => undefined);
    await rm(temporary, { force: true });
    if (error?.code) throw error;
    fail("BACKUP_ENCRYPTION_FAILED");
  }
  await output.close();
  await publishExclusive(temporary, destination);
}

async function readEncryptedPrefix(handle, size) {
  if (size < ENCRYPTED_MAGIC.length + 4 + TAG_BYTES + 1) {
    fail("BACKUP_TRUNCATED");
  }
  const fixed = Buffer.alloc(ENCRYPTED_MAGIC.length + 4);
  const fixedRead = await handle.read(fixed, 0, fixed.length, 0);
  if (fixedRead.bytesRead !== fixed.length) fail("BACKUP_TRUNCATED");
  if (!fixed.subarray(0, ENCRYPTED_MAGIC.length).equals(ENCRYPTED_MAGIC)) {
    fail("BACKUP_MAGIC_INVALID");
  }
  const headerLength = fixed.readUInt32BE(ENCRYPTED_MAGIC.length);
  if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
    fail("BACKUP_HEADER_INVALID");
  }
  if (fixed.length + headerLength + TAG_BYTES >= size) fail("BACKUP_TRUNCATED");
  const headerBytes = Buffer.alloc(headerLength);
  const headerRead = await handle.read(
    headerBytes,
    0,
    headerLength,
    fixed.length,
  );
  if (headerRead.bytesRead !== headerLength) fail("BACKUP_TRUNCATED");
  let header;
  try {
    header = JSON.parse(headerBytes.toString("utf8"));
  } catch {
    fail("BACKUP_HEADER_INVALID");
  }
  if (
    !exactKeys(header, ["format", "version", "cipher", "nonce", "kdf"]) ||
    header.format !== "link-vault-encrypted-backup" ||
    header.version !== BACKUP_FORMAT_VERSION || header.cipher !== "aes-256-gcm"
  ) {
    fail("BACKUP_HEADER_INVALID");
  }
  const nonce = Buffer.from(header.nonce, "base64");
  if (
    nonce.length !== NONCE_BYTES || nonce.toString("base64") !== header.nonce
  ) fail("BACKUP_HEADER_INVALID");
  return {
    header,
    nonce,
    prefix: Buffer.concat([fixed, headerBytes]),
    ciphertextOffset: fixed.length + headerLength,
    ciphertextLength: size - fixed.length - headerLength - TAG_BYTES,
  };
}

export async function decryptPackageFile(
  { artifactPath, plaintextPath, credential },
) {
  const input = await open(resolve(artifactPath), "r").catch(() =>
    fail("BACKUP_INPUT_UNAVAILABLE")
  );
  const output = await open(resolve(plaintextPath), "wx", 0o600).catch(
    async () => {
      await input.close();
      fail("PLAINTEXT_TEMP_CREATE_FAILED");
    },
  );
  try {
    const metadata = await input.stat();
    const parsed = await readEncryptedPrefix(input, metadata.size);
    const key = await deriveKey(credential, parsed.header.kdf);
    const tag = Buffer.alloc(TAG_BYTES);
    const tagRead = await input.read(
      tag,
      0,
      TAG_BYTES,
      metadata.size - TAG_BYTES,
    );
    if (tagRead.bytesRead !== TAG_BYTES) fail("BACKUP_TRUNCATED");
    const decipher = createDecipheriv("aes-256-gcm", key, parsed.nonce);
    decipher.setAAD(parsed.prefix);
    decipher.setAuthTag(tag);
    let position = parsed.ciphertextOffset;
    let remaining = parsed.ciphertextLength;
    const chunk = Buffer.alloc(Math.min(COPY_CHUNK_BYTES, remaining));
    while (remaining > 0) {
      const wanted = Math.min(chunk.length, remaining);
      const read = await input.read(chunk, 0, wanted, position);
      if (read.bytesRead !== wanted) fail("BACKUP_TRUNCATED");
      const plaintext = decipher.update(chunk.subarray(0, wanted));
      if (plaintext.length) await output.write(plaintext);
      position += wanted;
      remaining -= wanted;
    }
    const final = decipher.final();
    if (final.length) await output.write(final);
    await output.sync();
  } catch (error) {
    await input.close().catch(() => undefined);
    await output.close().catch(() => undefined);
    await rm(plaintextPath, { force: true });
    if (error?.code) throw error;
    fail("BACKUP_AUTHENTICATION_FAILED");
  }
  await input.close();
  await output.close();
}

async function writeAll(handle, buffer, position = null) {
  let offset = 0;
  while (offset < buffer.length) {
    const result = await handle.write(
      buffer,
      offset,
      buffer.length - offset,
      position === null ? null : position + offset,
    );
    if (result.bytesWritten <= 0) fail("PACKAGE_WRITE_FAILED");
    offset += result.bytesWritten;
  }
}

function recordHeader(path, size, sha256) {
  return Buffer.from(JSON.stringify({ path, size, sha256 }), "utf8");
}

export class BackupPackageWriter {
  constructor(path, handle) {
    this.path = path;
    this.handle = handle;
    this.paths = new Set();
    this.finished = false;
  }

  static async create(path) {
    const handle = await open(path, "wx", 0o600).catch(() =>
      fail("PACKAGE_CREATE_FAILED")
    );
    await writeAll(handle, PACKAGE_MAGIC);
    return new BackupPackageWriter(path, handle);
  }

  async addStream(path, size, stream) {
    if (path === "manifest.json") fail("PACKAGE_RESERVED_PATH");
    return await this.#writeRecord(path, size, stream);
  }

  async #writeRecord(path, size, stream) {
    if (this.finished) fail("PACKAGE_ALREADY_FINISHED");
    assertCanonicalPackagePath(path);
    if (this.paths.has(path)) fail("PACKAGE_DUPLICATE_PATH");
    if (!Number.isSafeInteger(size) || size < 0) fail("PACKAGE_SIZE_INVALID");
    this.paths.add(path);
    const placeholder = recordHeader(path, size, "0".repeat(64));
    if (placeholder.length > MAX_HEADER_BYTES) fail("PACKAGE_HEADER_INVALID");
    const headerLength = Buffer.alloc(4);
    headerLength.writeUInt32BE(placeholder.length);
    await writeAll(this.handle, headerLength);
    const headerPosition = (await this.handle.stat()).size;
    await writeAll(this.handle, placeholder);
    const dataOffset = (await this.handle.stat()).size;
    const hash = createHash("sha256");
    let written = 0;
    for await (const value of stream) {
      const chunk = Buffer.isBuffer(value) ? value : Buffer.from(value);
      written += chunk.length;
      if (written > size) fail("PACKAGE_ENTRY_SIZE_MISMATCH");
      hash.update(chunk);
      await writeAll(this.handle, chunk);
    }
    if (written !== size) fail("PACKAGE_ENTRY_SIZE_MISMATCH");
    const sha256 = hash.digest("hex");
    const completed = recordHeader(path, size, sha256);
    if (completed.length !== placeholder.length) fail("PACKAGE_HEADER_INVALID");
    await writeAll(this.handle, completed, headerPosition);
    return { path, size, sha256, offset: dataOffset };
  }

  async addFile(path, sourcePath) {
    const metadata = await stat(sourcePath);
    if (!metadata.isFile() || !Number.isSafeInteger(metadata.size)) {
      fail("PACKAGE_SOURCE_INVALID");
    }
    return await this.addStream(
      path,
      metadata.size,
      createReadStream(sourcePath, { highWaterMark: COPY_CHUNK_BYTES }),
    );
  }

  async addBuffer(path, buffer) {
    const bytes = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
    async function* chunks() {
      yield bytes;
    }
    return await this.addStream(path, bytes.length, chunks());
  }

  async finish(manifest) {
    if (this.finished) fail("PACKAGE_ALREADY_FINISHED");
    const bytes = Buffer.from(JSON.stringify(manifest), "utf8");
    if (bytes.length > MAX_MANIFEST_BYTES) fail("MANIFEST_TOO_LARGE");
    const entry = await this.#writeRecord(
      "manifest.json",
      bytes.length,
      (async function* () {
        yield bytes;
      })(),
    );
    this.finished = true;
    const end = Buffer.alloc(4);
    await writeAll(this.handle, end);
    await this.handle.sync();
    await this.handle.close();
    return entry;
  }

  async abort() {
    await this.handle.close().catch(() => undefined);
    await rm(this.path, { force: true });
  }
}

async function readExactly(
  handle,
  position,
  length,
  code = "PACKAGE_TRUNCATED",
) {
  const buffer = Buffer.alloc(length);
  let offset = 0;
  while (offset < length) {
    const result = await handle.read(
      buffer,
      offset,
      length - offset,
      position + offset,
    );
    if (result.bytesRead <= 0) fail(code);
    offset += result.bytesRead;
  }
  return buffer;
}

async function hashRange(handle, offset, size) {
  const hash = createHash("sha256");
  let position = offset;
  let remaining = size;
  const buffer = Buffer.alloc(
    Math.min(COPY_CHUNK_BYTES, Math.max(1, remaining)),
  );
  while (remaining > 0) {
    const wanted = Math.min(buffer.length, remaining);
    const result = await handle.read(buffer, 0, wanted, position);
    if (result.bytesRead !== wanted) fail("PACKAGE_TRUNCATED");
    hash.update(buffer.subarray(0, wanted));
    remaining -= wanted;
    position += wanted;
  }
  return hash.digest("hex");
}

export async function validatePackageFile(packagePath) {
  const handle = await open(packagePath, "r").catch(() =>
    fail("PACKAGE_INPUT_UNAVAILABLE")
  );
  try {
    const metadata = await handle.stat();
    if (
      !Number.isSafeInteger(metadata.size) ||
      metadata.size < PACKAGE_MAGIC.length + 4
    ) fail("PACKAGE_TRUNCATED");
    const magic = await readExactly(handle, 0, PACKAGE_MAGIC.length);
    if (!magic.equals(PACKAGE_MAGIC)) fail("PACKAGE_MAGIC_INVALID");
    const entries = new Map();
    let position = PACKAGE_MAGIC.length;
    let sawManifest = false;
    while (true) {
      if (position + 4 > metadata.size) fail("PACKAGE_TRUNCATED");
      const lengthBytes = await readExactly(handle, position, 4);
      position += 4;
      const headerLength = lengthBytes.readUInt32BE(0);
      if (headerLength === 0) break;
      if (
        headerLength > MAX_HEADER_BYTES ||
        position + headerLength > metadata.size
      ) fail("PACKAGE_HEADER_INVALID");
      const headerBytes = await readExactly(handle, position, headerLength);
      position += headerLength;
      let header;
      try {
        header = JSON.parse(headerBytes.toString("utf8"));
      } catch {
        fail("PACKAGE_HEADER_INVALID");
      }
      if (
        !exactKeys(header, ["path", "size", "sha256"]) ||
        !Number.isSafeInteger(header.size) || header.size < 0 ||
        !HASH_PATTERN.test(header.sha256)
      ) fail("PACKAGE_HEADER_INVALID");
      assertCanonicalPackagePath(header.path);
      if (entries.has(header.path) || sawManifest) {
        fail("PACKAGE_STRUCTURE_INVALID");
      }
      if (header.size > metadata.size - position) fail("PACKAGE_TRUNCATED");
      const digest = await hashRange(handle, position, header.size);
      if (digest !== header.sha256) fail("PACKAGE_HASH_MISMATCH");
      const entry = { ...header, offset: position };
      entries.set(header.path, entry);
      position += header.size;
      sawManifest = header.path === "manifest.json";
    }
    if (position !== metadata.size || !sawManifest) {
      fail("PACKAGE_STRUCTURE_INVALID");
    }
    const manifestEntry = entries.get("manifest.json");
    if (manifestEntry.size > MAX_MANIFEST_BYTES) fail("MANIFEST_TOO_LARGE");
    const manifestBytes = await readExactly(
      handle,
      manifestEntry.offset,
      manifestEntry.size,
    );
    let manifest;
    try {
      manifest = JSON.parse(manifestBytes.toString("utf8"));
    } catch {
      fail("MANIFEST_INVALID");
    }
    return { entries, manifest, size: metadata.size };
  } finally {
    await handle.close();
  }
}

function validateEntryReference(reference, entries, expectedPath) {
  if (!exactKeys(reference, ["path", "size", "sha256"])) {
    fail("MANIFEST_INVALID");
  }
  if (expectedPath !== undefined && reference.path !== expectedPath) {
    fail("MANIFEST_INVALID");
  }
  assertCanonicalPackagePath(reference.path);
  const entry = entries.get(reference.path);
  if (
    !entry || entry.size !== reference.size || entry.sha256 !== reference.sha256
  ) fail("MANIFEST_ENTRY_MISMATCH");
}

export function validateSnapshotManifest(manifest, entries) {
  if (
    !exactKeys(manifest, [
      "formatVersion",
      "kind",
      "sourceProjectId",
      "databaseId",
      "createdUtc",
      "snapshotTime",
      "database",
      "assets",
      "schemaFunctionCounts",
      "rlsTables",
    ])
  ) fail("MANIFEST_INVALID");
  if (
    manifest.formatVersion !== BACKUP_FORMAT_VERSION ||
    manifest.kind !== "snapshot" ||
    manifest.sourceProjectId !== LOCAL_PROJECT_ID ||
    !isUuid(manifest.databaseId)
  ) fail("MANIFEST_INVALID");
  parseUtc(manifest.createdUtc, "MANIFEST_INVALID");
  parseUtc(manifest.snapshotTime, "MANIFEST_INVALID");
  if (
    !exactKeys(manifest.database, [
      "path",
      "size",
      "sha256",
      "format",
      "schemas",
      "extensions",
    ])
  ) fail("MANIFEST_INVALID");
  if (
    manifest.database.format !== "postgres-custom" ||
    JSON.stringify(manifest.database.schemas) !== JSON.stringify(BACKUP_SCHEMAS)
  ) fail("MANIFEST_INVALID");
  validateDatabaseExtensions(manifest.database.extensions);
  validateEntryReference(
    {
      path: manifest.database.path,
      size: manifest.database.size,
      sha256: manifest.database.sha256,
    },
    entries,
    "database.dump",
  );
  if (!Array.isArray(manifest.assets)) fail("MANIFEST_INVALID");
  const assetPaths = new Set();
  let priorPath = "";
  for (const asset of manifest.assets) {
    if (
      !exactKeys(asset, [
        "ownerId",
        "itemId",
        "assetId",
        "storagePath",
        "path",
        "size",
        "sha256",
      ])
    ) {
      fail("MANIFEST_INVALID");
    }
    if (
      !isUuid(asset.ownerId) || !isUuid(asset.itemId) || !isUuid(asset.assetId)
    ) fail("MANIFEST_INVALID");
    const storagePath = `${asset.ownerId}/${asset.itemId}/${asset.assetId}`;
    const packagePath = `library-images/${storagePath}`;
    if (
      asset.storagePath !== storagePath || asset.path !== packagePath ||
      asset.path <= priorPath
    ) fail("MANIFEST_INVALID");
    validateEntryReference(
      { path: asset.path, size: asset.size, sha256: asset.sha256 },
      entries,
      packagePath,
    );
    if (
      assetPaths.has(asset.path) || asset.size < 1 || asset.size > 2_000_000
    ) fail("MANIFEST_INVALID");
    assetPaths.add(asset.path);
    priorPath = asset.path;
  }
  const permittedPaths = new Set([
    "database.dump",
    "manifest.json",
    ...assetPaths,
  ]);
  if (
    entries.size !== permittedPaths.size ||
    [...entries.keys()].some((path) => !permittedPaths.has(path))
  ) {
    fail("MANIFEST_ENTRY_MISMATCH");
  }
  if (
    !manifest.schemaFunctionCounts ||
    typeof manifest.schemaFunctionCounts !== "object" ||
    Array.isArray(manifest.schemaFunctionCounts)
  ) {
    fail("MANIFEST_INVALID");
  }
  for (const schema of BACKUP_SCHEMAS) {
    if (
      !Number.isSafeInteger(manifest.schemaFunctionCounts[schema]) ||
      manifest.schemaFunctionCounts[schema] < 0
    ) fail("MANIFEST_INVALID");
  }
  if (
    Object.keys(manifest.schemaFunctionCounts).sort().join(",") !==
      [...BACKUP_SCHEMAS].sort().join(",")
  ) fail("MANIFEST_INVALID");
  if (
    !Array.isArray(manifest.rlsTables) || manifest.rlsTables.some((value) => {
      return typeof value !== "string" ||
        !/^(public|private|auth|storage)\.[a-z_][a-z0-9_]*$/.test(value);
    })
  ) fail("MANIFEST_INVALID");
  if (
    [...manifest.rlsTables].sort().join("\n") !==
      manifest.rlsTables.join("\n") ||
    new Set(manifest.rlsTables).size !== manifest.rlsTables.length
  ) {
    fail("MANIFEST_INVALID");
  }
  return manifest;
}

export function validateLedgerManifest(manifest, entries) {
  if (
    !exactKeys(manifest, [
      "formatVersion",
      "kind",
      "databaseId",
      "exportId",
      "coveredFrom",
      "coveredThrough",
      "throughSequence",
      "events",
    ])
  ) fail("LEDGER_INVALID");
  if (
    manifest.formatVersion !== BACKUP_FORMAT_VERSION ||
    manifest.kind !== "deletion-ledger" ||
    !isUuid(manifest.databaseId) || !isUuid(manifest.exportId) ||
    !Number.isSafeInteger(manifest.throughSequence) ||
    manifest.throughSequence < 0
  ) fail("LEDGER_INVALID");
  const from = parseUtc(manifest.coveredFrom, "LEDGER_INVALID");
  const through = parseUtc(manifest.coveredThrough, "LEDGER_INVALID");
  if (from > through || !Array.isArray(manifest.events)) fail("LEDGER_INVALID");
  const ids = new Set();
  let priorSequence = 0;
  for (const event of manifest.events) {
    if (
      !exactKeys(event, [
        "sequence",
        "eventId",
        "ownerId",
        "itemId",
        "kind",
        "requestedAt",
      ])
    ) fail("LEDGER_INVALID");
    if (
      !Number.isSafeInteger(event.sequence) ||
      event.sequence <= priorSequence ||
      event.sequence > manifest.throughSequence || !isUuid(event.eventId) ||
      !isUuid(event.ownerId) || !["item", "account"].includes(event.kind)
    ) fail("LEDGER_INVALID");
    if (
      (event.kind === "item" && !isUuid(event.itemId)) ||
      (event.kind === "account" && event.itemId !== null)
    ) fail("LEDGER_INVALID");
    const requested = parseUtc(event.requestedAt, "LEDGER_INVALID");
    if (requested > through || ids.has(event.eventId)) fail("LEDGER_INVALID");
    ids.add(event.eventId);
    priorSequence = event.sequence;
  }
  if (entries.size !== 1 || !entries.has("manifest.json")) {
    fail("LEDGER_INVALID");
  }
  return manifest;
}

export function validateRestorePair(snapshot, ledger) {
  if (snapshot.databaseId !== ledger.databaseId) {
    fail("FOREIGN_DELETION_LEDGER");
  }
  const snapshotTime = parseUtc(snapshot.snapshotTime, "MANIFEST_INVALID");
  const coveredFrom = parseUtc(ledger.coveredFrom, "LEDGER_INVALID");
  const coveredThrough = parseUtc(ledger.coveredThrough, "LEDGER_INVALID");
  if (coveredFrom > snapshotTime || coveredThrough < snapshotTime) {
    fail("STALE_DELETION_LEDGER");
  }
  return { snapshot, ledger };
}

export async function decryptAndValidateArtifact(
  { artifactPath, temporaryDirectory, credential, expectedKind },
) {
  await mkdir(temporaryDirectory, { recursive: true });
  const plaintextPath = join(
    temporaryDirectory,
    `authenticated-${randomBytes(12).toString("hex")}.pack`,
  );
  try {
    await decryptPackageFile({ artifactPath, plaintextPath, credential });
    const validated = await validatePackageFile(plaintextPath);
    if (expectedKind === "snapshot") {
      validateSnapshotManifest(validated.manifest, validated.entries);
    } else if (expectedKind === "deletion-ledger") {
      validateLedgerManifest(validated.manifest, validated.entries);
    } else fail("EXPECTED_ARTIFACT_KIND_INVALID");
    return { ...validated, packagePath: plaintextPath };
  } catch (error) {
    await rm(plaintextPath, { force: true });
    throw error;
  }
}

export async function extractValidatedEntry(
  { packagePath, entry, destinationRoot },
) {
  assertCanonicalPackagePath(entry?.path);
  if (
    !Number.isSafeInteger(entry?.offset) ||
    !Number.isSafeInteger(entry?.size) || !HASH_PATTERN.test(entry?.sha256)
  ) {
    fail("ENTRY_NOT_VALIDATED");
  }
  const root = resolve(destinationRoot);
  const destination = resolve(root, ...entry.path.split("/"));
  const relation = relative(root, destination);
  if (
    relation === "" || relation.startsWith(`..${sep}`) || relation === ".." ||
    isAbsolute(relation)
  ) fail("INVALID_PACKAGE_PATH");
  await mkdir(dirname(destination), { recursive: true });
  const source = await open(packagePath, "r");
  const output = await open(destination, "wx", 0o600).catch(async () => {
    await source.close();
    fail("EXTRACTION_TARGET_EXISTS");
  });
  try {
    const hash = createHash("sha256");
    let position = entry.offset;
    let remaining = entry.size;
    const buffer = Buffer.alloc(
      Math.min(COPY_CHUNK_BYTES, Math.max(1, remaining)),
    );
    while (remaining > 0) {
      const wanted = Math.min(buffer.length, remaining);
      const result = await source.read(buffer, 0, wanted, position);
      if (result.bytesRead !== wanted) fail("PACKAGE_TRUNCATED");
      const chunk = buffer.subarray(0, wanted);
      hash.update(chunk);
      await writeAll(output, chunk);
      position += wanted;
      remaining -= wanted;
    }
    if (hash.digest("hex") !== entry.sha256) fail("PACKAGE_HASH_MISMATCH");
    await output.sync();
  } catch (error) {
    await output.close().catch(() => undefined);
    await source.close().catch(() => undefined);
    await rm(destination, { force: true });
    throw error;
  }
  await output.close();
  await source.close();
  return destination;
}

export function snapshotSchemas() {
  return [...BACKUP_SCHEMAS];
}
