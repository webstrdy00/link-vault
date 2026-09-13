import rules from "./rules.json" with { type: "json" };

export interface ItemPreparationInput {
  url: string;
  shared_text?: string;
  title?: string;
  note?: string;
  category_ids?: string[];
}

export interface ItemUpdateInput {
  expected_version: number;
  title?: string | null;
  note?: string | null;
  category_ids?: string[];
}

export interface ItemCategoryReference {
  id: string;
  name: string;
}

export interface ItemUpdateSnapshot {
  version: number;
  url: string;
  user_title: string | null;
  fetched_title: string | null;
  shared_text: string | null;
  description: string | null;
  body_text: string | null;
  note: string | null;
  category_refs: ItemCategoryReference[];
  metadata_state: string;
  ocr_state: string;
  extraction_meta: Record<string, unknown>;
  active_asset: null | {
    ocr_text?: string | null;
    ocr_truncated?: boolean;
  };
}

type SearchField =
  | "user_title"
  | "fetched_title"
  | "note"
  | "ocr"
  | "shared"
  | "description"
  | "body"
  | "categories"
  | "url";

type TopicField = Exclude<SearchField, "categories" | "url">;

export interface PreparedItem {
  normalized_url: string;
  url_hash: string;
  source: "instagram" | "threads" | "naver_blog" | "other";
  display_fallback: string;
  normalized_fields: Record<SearchField, string>;
  alias_concepts: Record<TopicField, string[]>;
  cue_state: "pending" | "missing" | "limited" | "available";
  cue_flags: string[];
  metadata_allowed: boolean;
}

export interface PreparedItemUpdate extends PreparedItem {
  snapshot_version: number;
}

interface AliasRule {
  id: string;
  forms: string[];
}

type IndexItem = Partial<Record<SearchField, string | null>> & {
  processing?: boolean;
  metadata_failed?: boolean;
  ocr_failed?: boolean;
  truncated?: boolean;
};

const aliases = rules.aliases as AliasRule[];
const compounds = rules.compounds as Record<string, string[]>;
const suffixes = new Set(rules.suffixes as string[]);
const topicFields: TopicField[] = [
  "user_title",
  "fetched_title",
  "note",
  "shared",
  "description",
  "body",
  "ocr",
];
const metadataHosts = new Set(["blog.naver.com", "m.blog.naver.com"]);

export class InvalidItemUrlError extends Error {}

export async function prepareItem(
  body: ItemPreparationInput,
): Promise<PreparedItem> {
  const { normalizedUrl, parsed } = normalizeUrl(body.url);
  const metadataAllowed = parsed.protocol === "https:" &&
    parsed.port === "" && metadataHosts.has(parsed.hostname);
  const indexItem: IndexItem = {
    user_title: body.title,
    fetched_title: "",
    note: body.note,
    ocr: "",
    shared: body.shared_text,
    description: "",
    body: "",
    categories: "",
    url: normalizedUrl,
    processing: metadataAllowed,
  };
  const index = buildIndex(indexItem);
  const cue = cues(indexItem);

  return {
    normalized_url: normalizedUrl,
    url_hash: await sha256(normalizedUrl),
    source: sourceFor(parsed.hostname),
    display_fallback: displayFallback(body.shared_text, parsed.hostname),
    normalized_fields: index.fields,
    alias_concepts: index.concepts,
    cue_state: cue.state,
    cue_flags: cue.flags,
    metadata_allowed: metadataAllowed,
  };
}

export async function prepareItemUpdate(
  snapshot: ItemUpdateSnapshot,
  body: ItemUpdateInput,
  selectedCategories: ItemCategoryReference[],
): Promise<PreparedItemUpdate> {
  const { normalizedUrl, parsed } = normalizeUrl(snapshot.url);
  const metadataAllowed = parsed.protocol === "https:" &&
    parsed.port === "" && metadataHosts.has(parsed.hostname);
  const userTitle = Object.hasOwn(body, "title")
    ? body.title
    : snapshot.user_title;
  const note = Object.hasOwn(body, "note") ? body.note : snapshot.note;
  const indexItem: IndexItem = {
    user_title: userTitle,
    fetched_title: snapshot.fetched_title,
    note,
    ocr: snapshot.active_asset?.ocr_text,
    shared: snapshot.shared_text,
    description: snapshot.description,
    body: snapshot.body_text,
    categories: selectedCategories.map((category) => category.name).join(" "),
    url: normalizedUrl,
    processing: snapshot.metadata_state === "queued" ||
      snapshot.metadata_state === "running" ||
      snapshot.ocr_state === "queued" ||
      snapshot.ocr_state === "running",
    metadata_failed: snapshot.metadata_state === "failed",
    ocr_failed: snapshot.ocr_state === "failed",
    truncated: snapshot.active_asset?.ocr_truncated === true ||
      snapshot.extraction_meta.title_truncated === true ||
      snapshot.extraction_meta.description_truncated === true ||
      snapshot.extraction_meta.body_truncated === true,
  };
  const index = buildIndex(indexItem);
  const cue = cues(indexItem);

  return {
    normalized_url: normalizedUrl,
    url_hash: await sha256(normalizedUrl),
    source: sourceFor(parsed.hostname),
    display_fallback: displayFallback(
      snapshot.shared_text ?? undefined,
      parsed.hostname,
    ),
    normalized_fields: index.fields,
    alias_concepts: index.concepts,
    cue_state: cue.state,
    cue_flags: cue.flags,
    metadata_allowed: metadataAllowed,
    snapshot_version: snapshot.version,
  };
}

export function norm(value: unknown): string {
  return String(value ?? "").normalize("NFKC").toLowerCase().replace(
    /\s+/gu,
    " ",
  ).trim();
}

function normalizeUrl(rawUrl: string): { normalizedUrl: string; parsed: URL } {
  const input = rawUrl.trim();
  const match = /^(https?):\/\/([^/?#]*)([\s\S]*)$/i.exec(input);
  if (
    rawUrl !== input || input.length === 0 || match === null ||
    /\s/u.test(input) ||
    input.includes("\\")
  ) {
    throw new InvalidItemUrlError("URL must be an HTTP or HTTPS URL.");
  }

  let parsed: URL;
  try {
    parsed = new URL(input);
  } catch {
    throw new InvalidItemUrlError("URL must be an HTTP or HTTPS URL.");
  }
  if (
    (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
    parsed.username !== "" || parsed.password !== "" ||
    parsed.hostname === ""
  ) {
    throw new InvalidItemUrlError("URL must be an HTTP or HTTPS URL.");
  }

  return {
    normalizedUrl: `${parsed.protocol}//${parsed.host}${match[3]}`,
    parsed,
  };
}

// Ported without fuzzy-rule changes from the sealed rules-v2 reference engine:
// docs/관심 정보 통합 보관함/검증자료/2026-09-11-rules-v2/engine.mjs.
function buildIndex(item: IndexItem): {
  fields: Record<SearchField, string>;
  concepts: Record<TopicField, string[]>;
} {
  const fields = activeFields(item);
  const concepts = Object.fromEntries(topicFields.map((field) => {
    const found = new Set<string>();
    for (const alias of aliases) {
      if (alias.forms.some((form) => hasTerm(fields[field], form))) {
        found.add(alias.id);
      }
    }
    for (const [form, ids] of Object.entries(compounds)) {
      if (hasTerm(fields[field], form)) {
        ids.forEach((id) => found.add(id));
      }
    }
    return [field, [...found].sort()];
  })) as Record<TopicField, string[]>;
  return { fields, concepts };
}

function activeFields(item: IndexItem): Record<SearchField, string> {
  return Object.fromEntries(
    [
      "user_title",
      "fetched_title",
      "note",
      "ocr",
      "shared",
      "description",
      "body",
      "categories",
      "url",
    ].map((field) => [field, norm(item[field as SearchField])]),
  ) as Record<SearchField, string>;
}

function hasTerm(text: string, term: string): boolean {
  const normalizedText = norm(text);
  const normalizedTerm = norm(term);
  let at = normalizedText.indexOf(normalizedTerm);
  while (at >= 0) {
    if (boundary(normalizedText, at, at + normalizedTerm.length)) {
      return true;
    }
    at = normalizedText.indexOf(normalizedTerm, at + 1);
  }
  return false;
}

function boundary(text: string, start: number, end: number): boolean {
  if (start !== 0 && isWordCharacter(text[start - 1])) {
    return false;
  }
  if (!isWordCharacter(text[end])) {
    return true;
  }
  let suffixEnd = end;
  while (suffixEnd < text.length && isWordCharacter(text[suffixEnd])) {
    suffixEnd++;
  }
  return suffixes.has(text.slice(end, suffixEnd));
}

function isWordCharacter(character: string | undefined): boolean {
  return character !== undefined && /[\p{L}\p{M}\p{N}_]/u.test(character);
}

function cues(
  item: IndexItem,
): {
  state: PreparedItem["cue_state"];
  flags: string[];
} {
  const fields = activeFields(item);
  const usable = topicFields.map((field) => topicText(fields[field], field))
    .filter(Boolean);
  const text = [...new Set(usable)].join(" ");
  const letters = (text.match(/[\p{L}\p{N}]/gu) ?? []).length;
  const tokens = new Set(
    text.split(/[^\p{L}\p{M}\p{N}_]+/u).filter(Boolean),
  );
  const flags: string[] = [];
  if (genericTitle(fields.fetched_title)) {
    flags.push("generic_title");
  }
  if (item.metadata_failed) {
    flags.push("metadata_failed");
  }
  if (item.ocr_failed) {
    flags.push("ocr_failed");
  }
  if (item.truncated) {
    flags.push("truncated");
  }
  const state: PreparedItem["cue_state"] = !letters
    ? (item.processing ? "pending" : "missing")
    : (letters >= 40 && tokens.size >= 3 ? "available" : "limited");
  if (state === "limited") {
    flags.push("short_text");
  }
  return { state, flags };
}

function topicText(text: string, field: TopicField): string {
  let normalized = norm(text);
  if (field === "fetched_title" && genericTitle(normalized)) {
    return "";
  }
  if (field !== "user_title" && field !== "note") {
    normalized = normalized.replace(
      /^.{1,150}? on (instagram|threads):\s*/u,
      "",
    );
    normalized = normalized.replace(
      /^\d[\d,]* likes?, \d[\d,]* comments? - [^:]{1,200}:\s*/u,
      "",
    );
  }
  return normalized.replace(/https?:\/\/[^\s]+/gu, " ").replace(
    /(^|\s)@[\w.]+/gu,
    " ",
  ).trim();
}

function genericTitle(title: string): boolean {
  const normalized = norm(title);
  return /^(home|instagram|threads|네이버|naver|login|log in|로그인|page not found|access denied|error)$/u
    .test(normalized) ||
    /^.{1,100}\(@[^)]+\) on (threads|instagram)$/u.test(normalized);
}

function sourceFor(hostname: string): PreparedItem["source"] {
  if (hostname === "instagram.com" || hostname.endsWith(".instagram.com")) {
    return "instagram";
  }
  if (
    hostname === "threads.com" || hostname.endsWith(".threads.com") ||
    hostname === "threads.net" || hostname.endsWith(".threads.net")
  ) {
    return "threads";
  }
  if (metadataHosts.has(hostname)) {
    return "naver_blog";
  }
  return "other";
}

function displayFallback(
  sharedText: string | undefined,
  hostname: string,
): string {
  const firstLine = sharedText?.split(/\r?\n/u).map((line) => line.trim()).find(
    Boolean,
  );
  return firstLine === undefined
    ? hostname
    : [...firstLine].slice(0, 80).join("");
}

async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(bytes)].map((byte) =>
    byte.toString(16).padStart(2, "0")
  )
    .join("");
}
