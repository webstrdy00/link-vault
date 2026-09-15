import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { Parser } from "htmlparser2";
import { buildConnector, Client } from "undici";
import { genericTitle, norm } from "./item-preparation.ts";

const ALLOWED_HOSTS = new Set(["blog.naver.com", "m.blog.naver.com"]);
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);
const MAX_REDIRECTS = 3;
const MAX_BODY_BYTES = 2_000_000;
const REQUEST_TIMEOUT_MS = 10_000;
const DEFAULT_RETRY_AFTER_SECONDS = 60;
const MAX_RETRY_AFTER_SECONDS = 86_400;
const ADAPTER_VERSION = "naver-metadata-v1";

export type MetadataState = "ready" | "partial" | "unsupported" | "failed";

export type MetadataErrorCode =
  | "METADATA_TIMEOUT"
  | "NETWORK_ERROR"
  | "RATE_LIMITED"
  | "ACCESS_DENIED"
  | "INVALID_CONTENT"
  | "RESPONSE_TOO_LARGE"
  | "METADATA_BUDGET_EXHAUSTED"
  | "INVALID_RESULT"
  | "INTERNAL_ERROR";

export interface MetadataExtractionMeta {
  adapter_version: string;
  final_url: string | null;
  title_truncated: boolean;
  description_truncated: boolean;
  body_truncated: boolean;
  last_checked_at: string;
  error_code: MetadataErrorCode | null;
}

export interface MetadataResult {
  fetched_title: string | null;
  description: string | null;
  body_text: null;
  metadata_state: MetadataState;
  extraction_meta: MetadataExtractionMeta;
}

export interface ResolvedAddress {
  address: string;
  family: 4 | 6;
}

export interface MetadataDnsResolver {
  resolve(hostname: string, signal: AbortSignal): Promise<ResolvedAddress[]>;
}

export interface MetadataTransportRequest {
  url: URL;
  addresses: readonly ResolvedAddress[];
  signal: AbortSignal;
}

export interface MetadataTransportResponse {
  status: number;
  headers: Readonly<Record<string, string | undefined>>;
  body: AsyncIterable<Uint8Array> | null;
  cancel?: (reason?: unknown) => void;
}

export interface MetadataTransport {
  request(input: MetadataTransportRequest): Promise<MetadataTransportResponse>;
}

export interface MetadataAdapters {
  dns: MetadataDnsResolver;
  transport: MetadataTransport;
  now?: () => Date;
  timeoutMs?: number;
}

export class MetadataFetchFailure extends Error {
  get retryable(): boolean {
    return this.code !== "RETRY_AFTER_EXCEEDED";
  }

  constructor(
    readonly code:
      | "METADATA_TIMEOUT"
      | "NETWORK_ERROR"
      | "RATE_LIMITED"
      | "RETRY_AFTER_EXCEEDED"
      | "INTERNAL_ERROR",
    readonly retryAfterSeconds: number | null = null,
    cause?: unknown,
  ) {
    super(code, cause === undefined ? undefined : { cause });
    this.name = "MetadataFetchFailure";
  }
}

export const productionMetadataDnsResolver: MetadataDnsResolver = {
  async resolve(hostname: string): Promise<ResolvedAddress[]> {
    const addresses = await lookup(hostname, { all: true, verbatim: true });
    return addresses.map(({ address, family }) => ({
      address,
      family: family as 4 | 6,
    }));
  },
};

export const productionMetadataTransport: MetadataTransport = {
  async request(
    input: MetadataTransportRequest,
  ): Promise<MetadataTransportResponse> {
    let validatedUrl: URL;
    try {
      validatedUrl = validateFetchUrl(input.url.href);
    } catch {
      throw new TypeError("Metadata transport requires an allowed URL.");
    }
    const selected = input.addresses.find((entry) => entry.family === 4) ??
      input.addresses[0];
    if (
      selected === undefined ||
      input.addresses.some((entry) =>
        (entry.family !== 4 && entry.family !== 6) ||
        isIP(entry.address) !== entry.family || !isPublicAddress(entry.address)
      )
    ) {
      throw new TypeError("Metadata transport requires validated addresses.");
    }

    const tlsConnector = buildConnector({
      rejectUnauthorized: true,
      timeout: REQUEST_TIMEOUT_MS,
      maxCachedSessions: 0,
    });
    const client = new Client(
      `${validatedUrl.protocol}//${validatedUrl.host}`,
      {
        connect(options, callback) {
          return tlsConnector({
            ...options,
            hostname: selected.address,
            host: selected.address,
            port: "443",
            servername: validatedUrl.hostname,
          }, callback);
        },
        pipelining: 0,
        headersTimeout: REQUEST_TIMEOUT_MS,
        bodyTimeout: REQUEST_TIMEOUT_MS,
        maxHeaderSize: 16 * 1024,
      },
    );

    try {
      const response = await client.request({
        path: `${validatedUrl.pathname}${validatedUrl.search}`,
        method: "GET",
        maxRedirections: 0,
        signal: input.signal,
        headers: {
          accept: "text/html,application/xhtml+xml;q=0.9",
          "accept-encoding": "identity",
          "user-agent": "LinkVaultMetadata/1.0",
        },
      });
      return ownUndiciMetadataResponse(response, client);
    } catch (error) {
      await destroyClient(client, asError(error));
      throw error;
    }
  },
};

export function fetchMetadata(url: string): Promise<MetadataResult> {
  return fetchMetadataWithAdapters(url, {
    dns: productionMetadataDnsResolver,
    transport: productionMetadataTransport,
  });
}

export async function fetchMetadataWithAdapters(
  originalUrl: string,
  adapters: MetadataAdapters,
): Promise<MetadataResult> {
  const now = adapters.now ?? (() => new Date());
  let current: URL;
  try {
    current = validateFetchUrl(originalUrl);
  } catch {
    return emptyResult("unsupported", "INVALID_CONTENT", null, now());
  }

  const controller = new AbortController();
  const timeoutFailure = new MetadataFetchFailure("METADATA_TIMEOUT");
  const timeoutMilliseconds = adapters.timeoutMs ?? REQUEST_TIMEOUT_MS;
  const deadline = performance.now() + timeoutMilliseconds;
  const timeout = setTimeout(
    () => controller.abort(timeoutFailure),
    timeoutMilliseconds,
  );
  let redirects = 0;

  try {
    while (true) {
      const addresses = await resolvePublicAddresses(
        current.hostname,
        adapters.dns,
        controller.signal,
      );
      if (addresses === null) {
        return emptyResult(
          "unsupported",
          "INVALID_CONTENT",
          current.href,
          now(),
        );
      }

      const response = await requestOnce(
        adapters.transport,
        { url: current, addresses, signal: controller.signal },
      );
      if (REDIRECT_STATUSES.has(response.status)) {
        const location = headerValue(response.headers, "location");
        response.cancel?.();
        if (location === null || redirects >= MAX_REDIRECTS) {
          return emptyResult(
            "failed",
            "INVALID_CONTENT",
            current.href,
            now(),
          );
        }
        let next: URL;
        try {
          next = validateFetchUrl(new URL(location, current).href);
        } catch {
          return emptyResult(
            "unsupported",
            "INVALID_CONTENT",
            current.href,
            now(),
          );
        }
        current = next;
        redirects++;
        continue;
      }

      if (response.status === 429) {
        response.cancel?.();
        const retryAfter = parseRetryAfter(
          headerValue(response.headers, "retry-after"),
          now(),
        ) ?? DEFAULT_RETRY_AFTER_SECONDS;
        if (retryAfter > MAX_RETRY_AFTER_SECONDS) {
          throw new MetadataFetchFailure("RETRY_AFTER_EXCEEDED");
        }
        throw new MetadataFetchFailure(
          "RATE_LIMITED",
          retryAfter,
        );
      }
      if (response.status === 408) {
        response.cancel?.();
        throw new MetadataFetchFailure("METADATA_TIMEOUT");
      }
      if (response.status >= 500 && response.status <= 599) {
        response.cancel?.();
        throw new MetadataFetchFailure("NETWORK_ERROR");
      }
      if (response.status === 401 || response.status === 403) {
        response.cancel?.();
        return emptyResult(
          "partial",
          "ACCESS_DENIED",
          current.href,
          now(),
        );
      }
      if (response.status !== 200) {
        response.cancel?.();
        return emptyResult(
          "failed",
          "INVALID_CONTENT",
          current.href,
          now(),
        );
      }

      const contentType = headerValue(response.headers, "content-type");
      const contentEncoding = headerValue(response.headers, "content-encoding");
      const charset = htmlCharset(contentType);
      if (
        charset === null ||
        (contentEncoding !== null && norm(contentEncoding) !== "identity")
      ) {
        response.cancel?.();
        return emptyResult(
          "failed",
          "INVALID_CONTENT",
          current.href,
          now(),
        );
      }
      const declaredLength = contentLength(response.headers);
      if (declaredLength === "invalid") {
        response.cancel?.();
        return emptyResult(
          "failed",
          "INVALID_CONTENT",
          current.href,
          now(),
        );
      }
      if (declaredLength !== null && declaredLength > MAX_BODY_BYTES) {
        response.cancel?.();
        return emptyResult(
          "failed",
          "RESPONSE_TOO_LARGE",
          current.href,
          now(),
        );
      }

      let bytes: Uint8Array;
      try {
        bytes = await readBoundedBody(response, controller.signal);
      } catch (error) {
        if (error instanceof ResponseTooLargeError) {
          return emptyResult(
            "failed",
            "RESPONSE_TOO_LARGE",
            current.href,
            now(),
          );
        }
        if (error instanceof MetadataFetchFailure) {
          throw error;
        }
        throw new MetadataFetchFailure("NETWORK_ERROR", null, error);
      }

      let html: string;
      try {
        html = new TextDecoder(charset).decode(bytes);
      } catch {
        return emptyResult(
          "failed",
          "INVALID_CONTENT",
          current.href,
          now(),
        );
      }
      const result = extractNaverMetadata(html, current.href, now());
      if (performance.now() > deadline) {
        throw timeoutFailure;
      }
      return result;
    }
  } catch (error) {
    if (error instanceof MetadataFetchFailure) {
      throw error;
    }
    throw new MetadataFetchFailure("INTERNAL_ERROR", null, error);
  } finally {
    clearTimeout(timeout);
  }
}

export function parseRetryAfter(
  value: string | null,
  now: Date,
): number | null {
  if (value === null) {
    return null;
  }
  const trimmed = value.trim();
  let seconds: number;
  if (/^\d+$/u.test(trimmed)) {
    if (trimmed.length > 10) return Number.MAX_SAFE_INTEGER;
    seconds = Number(trimmed);
  } else {
    const timestamp = Date.parse(trimmed);
    if (!Number.isFinite(timestamp)) {
      return null;
    }
    seconds = Math.ceil((timestamp - now.getTime()) / 1000);
  }
  if (!Number.isSafeInteger(seconds)) {
    return null;
  }
  return Math.max(DEFAULT_RETRY_AFTER_SECONDS, seconds);
}

function validateFetchUrl(rawUrl: string): URL {
  if (
    rawUrl !== rawUrl.trim() || rawUrl.length === 0 || /\s/u.test(rawUrl) ||
    rawUrl.includes("\\")
  ) {
    throw new Error("Unsupported metadata URL.");
  }
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new Error("Unsupported metadata URL.");
  }
  const literalHostname = parsed.hostname.startsWith("[") &&
      parsed.hostname.endsWith("]")
    ? parsed.hostname.slice(1, -1)
    : parsed.hostname;
  if (
    parsed.protocol !== "https:" || parsed.port !== "" ||
    parsed.username !== "" || parsed.password !== "" ||
    isIP(literalHostname) !== 0 || !ALLOWED_HOSTS.has(parsed.hostname)
  ) {
    throw new Error("Unsupported metadata URL.");
  }
  return parsed;
}

async function resolvePublicAddresses(
  hostname: string,
  resolver: MetadataDnsResolver,
  signal: AbortSignal,
): Promise<ResolvedAddress[] | null> {
  let addresses: ResolvedAddress[];
  try {
    addresses = await abortable(resolver.resolve(hostname, signal), signal);
  } catch (error) {
    if (error instanceof MetadataFetchFailure) {
      throw error;
    }
    reportNetworkFailure("DNS", error);
    throw new MetadataFetchFailure("NETWORK_ERROR", null, error);
  }
  if (addresses.length === 0) {
    throw new MetadataFetchFailure("NETWORK_ERROR");
  }
  if (
    addresses.some((entry) =>
      (entry.family !== 4 && entry.family !== 6) ||
      isIP(entry.address) !== entry.family || !isPublicAddress(entry.address)
    )
  ) {
    return null;
  }
  return addresses;
}

async function requestOnce(
  transport: MetadataTransport,
  input: MetadataTransportRequest,
): Promise<MetadataTransportResponse> {
  try {
    const response = await abortable(transport.request(input), input.signal);
    if (
      !Number.isInteger(response.status) || response.status < 100 ||
      response.status > 599
    ) {
      throw new MetadataFetchFailure("INTERNAL_ERROR");
    }
    return response;
  } catch (error) {
    if (error instanceof MetadataFetchFailure) {
      throw error;
    }
    reportNetworkFailure("HTTPS", error);
    throw new MetadataFetchFailure("NETWORK_ERROR", null, error);
  }
}

function reportNetworkFailure(phase: "DNS" | "HTTPS", error: unknown): void {
  const message = error instanceof Error ? error.message : "";
  const code = typeof error === "object" && error !== null && "code" in error
    ? error.code
    : null;
  const knownCodes = new Set([
    "ENOTFOUND",
    "EAI_AGAIN",
    "ECONNREFUSED",
    "ECONNRESET",
    "ETIMEDOUT",
    "ENETUNREACH",
    "EHOSTUNREACH",
    "ERR_NOT_IMPLEMENTED",
    "CERT_HAS_EXPIRED",
    "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
  ]);
  const reason = typeof code === "string" && knownCodes.has(code)
    ? code
    : /not implemented|not supported|unsupported/i.test(message)
    ? "UNSUPPORTED_RUNTIME"
    : /permission/i.test(message)
    ? "PERMISSION_DENIED"
    : error instanceof TypeError
    ? "TYPE_ERROR"
    : "UNCLASSIFIED";
  const feature = reason === "ERR_NOT_IMPLEMENTED"
    ? [
      "lookup",
      "maxHeaderSize",
      "agent",
      "signal",
      "createConnection",
      "socket",
    ]
      .filter((name) => message.toLowerCase().includes(name.toLowerCase()))
      .map((name) => name.toUpperCase()).join("_")
    : "";
  console.error(`METADATA_${phase}_${reason}${feature ? `_${feature}` : ""}`);
}

async function readBoundedBody(
  response: MetadataTransportResponse,
  signal: AbortSignal,
): Promise<Uint8Array> {
  if (response.body === null) {
    return new Uint8Array();
  }
  const iterator = response.body[Symbol.asyncIterator]();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const part = await abortable(iterator.next(), signal);
      if (part.done) {
        break;
      }
      if (!(part.value instanceof Uint8Array)) {
        throw new Error("Metadata transport returned a non-byte body chunk.");
      }
      total += part.value.byteLength;
      if (total > MAX_BODY_BYTES) {
        throw new ResponseTooLargeError();
      }
      chunks.push(part.value);
    }
  } catch (error) {
    response.cancel?.(error);
    throw error;
  }

  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return joined;
}

function abortable<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) {
    return Promise.reject(signal.reason);
  }
  return new Promise((resolve, reject) => {
    const aborted = () => reject(signal.reason);
    signal.addEventListener("abort", aborted, { once: true });
    operation.then(
      (value) => {
        signal.removeEventListener("abort", aborted);
        resolve(value);
      },
      (error) => {
        signal.removeEventListener("abort", aborted);
        reject(error);
      },
    );
  });
}

function extractNaverMetadata(
  html: string,
  finalUrl: string,
  checkedAt: Date,
): MetadataResult {
  const parsed = parseMetadataHtml(html);
  const rawTitle = firstPresent(
    parsed.meta.get("og:title"),
    parsed.title,
    parsed.headings[0],
  );
  const rawDescription = firstPresent(
    parsed.meta.get("og:description"),
    parsed.meta.get("description"),
  );
  const title = truncateMetadataField(rawTitle, 300);
  const description = truncateMetadataField(rawDescription, 4000);
  const accessDenied = isAccessDeniedDocument(parsed, title.value);
  const meaningfulTitle = isMeaningfulTitle(title.value);
  const meaningfulDescription = isMeaningfulDescription(description.value);
  const metadataState: MetadataState = accessDenied ||
      !meaningfulTitle || !meaningfulDescription
    ? "partial"
    : "ready";
  const errorCode: MetadataErrorCode | null = metadataState === "ready"
    ? null
    : accessDenied
    ? "ACCESS_DENIED"
    : "INVALID_RESULT";

  return {
    fetched_title: accessDenied ? null : title.value,
    description: accessDenied ? null : description.value,
    body_text: null,
    metadata_state: metadataState,
    extraction_meta: {
      adapter_version: ADAPTER_VERSION,
      final_url: finalUrl,
      title_truncated: title.truncated,
      description_truncated: description.truncated,
      body_truncated: false,
      last_checked_at: checkedAt.toISOString(),
      error_code: errorCode,
    },
  };
}

interface ParsedMetadataHtml {
  meta: Map<string, string>;
  title: string | null;
  headings: string[];
  hasPasswordInput: boolean;
  hasLoginForm: boolean;
  hasChallengeMarker: boolean;
}

function parseMetadataHtml(html: string): ParsedMetadataHtml {
  const meta = new Map<string, string>();
  const titleParts: string[] = [];
  const headings: string[] = [];
  let currentHeading: string[] | null = null;
  let titleDepth = 0;
  let headingDepth = 0;
  let ignoredDepth = 0;
  let hasPasswordInput = false;
  let hasLoginForm = false;
  let hasChallengeMarker = false;
  const ignoredTags = new Set(["script", "style", "noscript", "template"]);

  const parser = new Parser({
    onopentag(name, attributes) {
      if (ignoredDepth > 0) {
        ignoredDepth++;
        return;
      }
      if (ignoredTags.has(name)) {
        ignoredDepth = 1;
        return;
      }
      if (name === "meta") {
        const key = norm(attributes.property ?? attributes.name);
        const content = cleanText(attributes.content);
        if (
          content !== null &&
          (key === "og:title" || key === "og:description" ||
            key === "description") &&
          !meta.has(key)
        ) {
          meta.set(key, content);
        }
      }
      if (name === "title") {
        titleDepth++;
      }
      if (name === "h1" || name === "h2") {
        if (headingDepth === 0) {
          currentHeading = [];
        }
        headingDepth++;
      }
      if (name === "input" && norm(attributes.type) === "password") {
        hasPasswordInput = true;
      }
      if (name === "form") {
        const action = norm(attributes.action);
        if (
          /\b(login|signin|auth)\b/u.test(action) || action.includes("로그인")
        ) {
          hasLoginForm = true;
        }
      }
      const marker = norm(
        `${attributes.id ?? ""} ${attributes.class ?? ""} ${
          attributes.action ?? ""
        }`,
      );
      if (
        /\b(challenge-form|captcha|cf-chl|access-denied)\b/u.test(marker)
      ) {
        hasChallengeMarker = true;
      }
    },
    ontext(text) {
      if (ignoredDepth > 0) {
        return;
      }
      if (titleDepth > 0) {
        titleParts.push(text);
      }
      if (headingDepth > 0 && currentHeading !== null) {
        currentHeading.push(text);
      }
    },
    onclosetag(name) {
      if (ignoredDepth > 0) {
        ignoredDepth--;
        return;
      }
      if (name === "title" && titleDepth > 0) {
        titleDepth--;
      }
      if ((name === "h1" || name === "h2") && headingDepth > 0) {
        headingDepth--;
        if (headingDepth === 0 && currentHeading !== null) {
          const heading = cleanText(currentHeading.join(""));
          if (heading !== null && headings.length < 8) {
            headings.push(heading);
          }
          currentHeading = null;
        }
      }
    },
  }, { decodeEntities: true });
  parser.end(html);

  return {
    meta,
    title: cleanText(titleParts.join("")),
    headings,
    hasPasswordInput,
    hasLoginForm,
    hasChallengeMarker,
  };
}

function isAccessDeniedDocument(
  parsed: ParsedMetadataHtml,
  title: string | null,
): boolean {
  const normalizedTitle = norm(title);
  const normalizedHeadings = parsed.headings.map(norm);
  const accessHeading = normalizedHeadings.some((heading) =>
    /^(로그인|log in|login|access denied|접근이 제한되었습니다|서비스 이용이 제한되었습니다|just a moment)$/u
      .test(heading)
  );
  const challengeTitle =
    /^(just a moment|attention required|access denied|접근이 제한되었습니다)(?:[.…]*|\s*[:|-].*)$/u
      .test(normalizedTitle);
  const loginTitle =
    /^(?:(?:네이버|naver)\s*)?(로그인|log in|login)(\s*[:|-].*)?$/u.test(
      normalizedTitle,
    );
  return loginTitle || challengeTitle ||
    (parsed.hasPasswordInput && (parsed.hasLoginForm || accessHeading)) ||
    (parsed.hasChallengeMarker && accessHeading);
}

function isMeaningfulTitle(value: string | null): boolean {
  if (value === null || genericTitle(value)) {
    return false;
  }
  const normalized = norm(value);
  if (
    /^(naver blog|네이버 블로그|blog|블로그|just a moment|attention required|untitled document)$/u
      .test(normalized)
  ) {
    return false;
  }
  return isMeaningfulText(value);
}

function isMeaningfulText(value: string | null): boolean {
  return value !== null && /[\p{L}\p{N}]/u.test(value);
}

function isMeaningfulDescription(value: string | null): boolean {
  if (!isMeaningfulText(value)) {
    return false;
  }
  return !/^(naver blog|네이버 블로그|서비스 홈(입니다)?|블로그(입니다)?|login|로그인)$/u
    .test(norm(value));
}

function truncateMetadataField(
  value: string | null,
  maximum: number,
): { value: string | null; truncated: boolean } {
  if (value === null) {
    return { value: null, truncated: false };
  }
  const characters = [...value];
  return characters.length > maximum
    ? { value: characters.slice(0, maximum).join(""), truncated: true }
    : { value, truncated: false };
}

function firstPresent(
  ...values: Array<string | null | undefined>
): string | null {
  return values.find((value): value is string =>
    value !== null && value !== undefined
  ) ??
    null;
}

function cleanText(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const cleaned = value.replace(/\s+/gu, " ").trim();
  return cleaned.length === 0 ? null : cleaned;
}

function htmlCharset(contentType: string | null): string | null {
  if (contentType === null) {
    return null;
  }
  const [rawMediaType] = contentType.split(";", 1);
  const mediaType = norm(rawMediaType);
  if (mediaType !== "text/html" && mediaType !== "application/xhtml+xml") {
    return null;
  }
  const charsetMatch = /(?:^|;)\s*charset\s*=\s*["']?([^;\s"']+)/iu.exec(
    contentType,
  );
  if (charsetMatch === null) {
    return "utf-8";
  }
  const charset = norm(charsetMatch[1]);
  if (charset === "utf-8" || charset === "utf8") {
    return "utf-8";
  }
  if (
    charset === "euc-kr" || charset === "cp949" ||
    charset === "windows-949" || charset === "ks_c_5601-1987"
  ) {
    return "euc-kr";
  }
  return null;
}

function contentLength(
  headers: Readonly<Record<string, string | undefined>>,
): number | null | "invalid" {
  const value = headerValue(headers, "content-length");
  if (value === null) {
    return null;
  }
  if (!/^\d+$/u.test(value.trim())) {
    return "invalid";
  }
  const length = Number(value);
  return Number.isSafeInteger(length) ? length : "invalid";
}

function headerValue(
  headers: Readonly<Record<string, string | undefined>>,
  name: string,
): string | null {
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === name && value !== undefined) {
      return value;
    }
  }
  return null;
}

interface UndiciResponseOwner {
  destroy(error: Error | null): Promise<void>;
}

interface UndiciMetadataBody extends AsyncIterable<Uint8Array> {
  destroy(error?: Error): unknown;
  on(event: "error", listener: (error: Error) => void): unknown;
}

export function ownUndiciMetadataResponse(
  response: {
    statusCode: number;
    headers: Record<string, string | string[] | undefined>;
    body: UndiciMetadataBody;
  },
  owner: UndiciResponseOwner,
): MetadataTransportResponse {
  // Undici BodyReadable.destroy() manufactures RequestAbortedError when it is
  // called before iteration without an error. Own the EventEmitter error from
  // the moment headers are exposed; async iteration still observes real errors.
  let intentionallyCancelled = false;
  response.body.on("error", (error) => {
    if (!intentionallyCancelled) reportNetworkFailure("HTTPS", error);
  });
  return {
    status: response.statusCode,
    headers: normalizeResponseHeaders(response.headers),
    body: closeClientAfterBody(response.body, owner),
    cancel: (reason?: unknown) => {
      const error = asError(reason) ??
        new Error("Metadata response intentionally cancelled.");
      intentionallyCancelled = true;
      response.body.destroy(error);
      observeClientDestroy(owner, error);
    },
  };
}

async function* closeClientAfterBody(
  body: UndiciMetadataBody,
  owner: UndiciResponseOwner,
): AsyncIterable<Uint8Array> {
  try {
    for await (const chunk of body) {
      if (!(chunk instanceof Uint8Array)) {
        throw new TypeError(
          "Metadata transport returned a non-byte body chunk.",
        );
      }
      yield chunk;
    }
  } finally {
    await destroyClient(owner);
  }
}

async function destroyClient(
  owner: UndiciResponseOwner,
  error?: Error,
): Promise<void> {
  try {
    await owner.destroy(error ?? null);
  } catch (destroyError) {
    reportNetworkFailure("HTTPS", destroyError);
  }
}

function observeClientDestroy(
  owner: UndiciResponseOwner,
  error: Error,
): void {
  try {
    owner.destroy(error).catch((destroyError) => {
      reportNetworkFailure("HTTPS", destroyError);
    });
  } catch (destroyError) {
    reportNetworkFailure("HTTPS", destroyError);
  }
}

function normalizeResponseHeaders(
  headers: Record<string, string | string[] | undefined>,
): Record<string, string | undefined> {
  const allowed = [
    "location",
    "content-type",
    "content-length",
    "content-encoding",
    "retry-after",
  ];
  return Object.fromEntries(allowed.map((name) => {
    const value = headers[name];
    return [name, Array.isArray(value) ? value.join(", ") : value];
  }));
}

function emptyResult(
  state: MetadataState,
  errorCode: MetadataErrorCode,
  finalUrl: string | null,
  checkedAt: Date,
): MetadataResult {
  return {
    fetched_title: null,
    description: null,
    body_text: null,
    metadata_state: state,
    extraction_meta: {
      adapter_version: ADAPTER_VERSION,
      final_url: finalUrl,
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
      last_checked_at: checkedAt.toISOString(),
      error_code: errorCode,
    },
  };
}

function isPublicAddress(address: string): boolean {
  const family = isIP(address);
  if (family === 4) {
    const [a, b, c] = address.split(".").map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 0 && c === 0) ||
      (a === 192 && b === 0 && c === 2) ||
      (a === 192 && b === 88 && c === 99) ||
      (a === 192 && b === 168) ||
      (a === 198 && (b === 18 || b === 19)) ||
      (a === 198 && b === 51 && c === 100) ||
      (a === 203 && b === 0 && c === 113));
  }
  if (family !== 6 || address.includes("%")) {
    return false;
  }
  const groups = expandIpv6(address);
  if (groups === null) {
    return false;
  }
  const first = groups[0];
  return first >= 0x2000 && first <= 0x3fff &&
    !(groups[0] === 0x2001 && groups[1] === 0x0db8) &&
    !(groups[0] === 0x2001 && groups[1] === 0x0002) &&
    !(groups[0] === 0x2001 && groups[1] >= 0x0010 && groups[1] <= 0x001f) &&
    groups[0] !== 0x2002 && groups[0] !== 0x3fff;
}

function expandIpv6(address: string): number[] | null {
  const normalized = address.toLowerCase();
  const halves = normalized.split("::");
  if (halves.length > 2 || normalized.includes(".")) {
    return null;
  }
  const left = halves[0] === "" ? [] : halves[0].split(":");
  const right = halves.length === 1 || halves[1] === ""
    ? []
    : halves[1].split(":");
  if (halves.length === 1 && left.length !== 8) {
    return null;
  }
  const missing = 8 - left.length - right.length;
  if (missing < (halves.length === 2 ? 1 : 0)) {
    return null;
  }
  const groups = [
    ...left,
    ...Array(missing).fill("0"),
    ...right,
  ].map((group) => Number.parseInt(group, 16));
  return groups.length === 8 &&
      groups.every((group) =>
        Number.isInteger(group) && group >= 0 && group <= 0xffff
      )
    ? groups
    : null;
}

function asError(reason: unknown): Error | undefined {
  if (reason === undefined) {
    return undefined;
  }
  return reason instanceof Error
    ? reason
    : new Error("Metadata request cancelled.");
}

class ResponseTooLargeError extends Error {}
