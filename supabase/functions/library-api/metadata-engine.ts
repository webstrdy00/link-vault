import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { Parser } from "htmlparser2";
import { genericTitle, norm } from "./item-preparation.ts";
import {
  hasInvalidMetadataUrlCharacters,
  isMetadataParsedUrlEligible,
  isMetadataUrlEligible,
  parseMetadataUrl,
} from "./metadata-url.ts";

const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);
const MAX_REDIRECTS = 3;
const MAX_BODY_BYTES = 2_000_000;
const REQUEST_TIMEOUT_MS = 10_000;
const DEFAULT_RETRY_AFTER_SECONDS = 60;
const MAX_RETRY_AFTER_SECONDS = 86_400;
const ADAPTER_VERSION = "public-page-metadata-v1";

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
    if (!isMetadataParsedUrlEligible(input.url)) {
      throw new TypeError("Metadata transport requires an allowed URL.");
    }
    const validatedUrl = input.url;
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

    // Ordinary API requests must not initialize the metadata-only HTTP client.
    const { buildConnector, Client } = await import("undici");
    const connector = buildConnector({
      rejectUnauthorized: true,
      timeout: REQUEST_TIMEOUT_MS,
      maxCachedSessions: 0,
    });
    const client = new Client(
      `${validatedUrl.protocol}//${validatedUrl.host}`,
      {
        connect(options, callback) {
          return connector({
            ...options,
            hostname: selected.address,
            host: selected.address,
            protocol: validatedUrl.protocol,
            port: validatedUrl.protocol === "https:" ? "443" : "80",
            servername: validatedUrl.protocol === "https:"
              ? validatedUrl.hostname
              : undefined,
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
          if (
            [...location].length > 4096 ||
            hasInvalidMetadataUrlCharacters(location)
          ) {
            throw new Error("Unsupported metadata redirect.");
          }
          const parsed = /^[a-z][a-z0-9+.-]*:/iu.test(location)
            ? parseMetadataUrl(location)
            : location.startsWith("//")
            ? parseMetadataUrl(`${current.protocol}${location}`)
            : new URL(location, current);
          if (parsed === null || !isMetadataParsedUrlEligible(parsed)) {
            throw new Error("Unsupported metadata redirect.");
          }
          next = parsed;
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
      const result = extractPageMetadata(html, current.href, now());
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
  if (!isMetadataUrlEligible(rawUrl)) {
    throw new Error("Unsupported metadata URL.");
  }
  return new URL(rawUrl);
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
    reportNetworkFailure("HTTP", error);
    throw new MetadataFetchFailure("NETWORK_ERROR", null, error);
  }
}

function reportNetworkFailure(phase: "DNS" | "HTTP", error: unknown): void {
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
    "UND_ERR_CONNECT_TIMEOUT",
    "UND_ERR_HEADERS_TIMEOUT",
    "UND_ERR_BODY_TIMEOUT",
    "UND_ERR_SOCKET",
    "ERR_TLS_CERT_ALTNAME_INVALID",
    "ERR_TLS_HANDSHAKE_TIMEOUT",
    "ERR_STREAM_PREMATURE_CLOSE",
    "ERR_HTTP2_ERROR",
    "EPROTO",
  ]);
  const reason = typeof code === "string" && knownCodes.has(code)
    ? code
    : /module|package|specifier|resolve.*import/i.test(message)
    ? "MODULE_RESOLUTION"
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

function extractPageMetadata(
  html: string,
  finalUrl: string,
  checkedAt: Date,
): MetadataResult {
  const parsed = parseMetadataHtml(html);
  if (isAccessDeniedDocument(parsed)) {
    return emptyResult("partial", "ACCESS_DENIED", finalUrl, checkedAt);
  }
  if (isErrorDocument(parsed)) {
    return emptyResult("failed", "INVALID_CONTENT", finalUrl, checkedAt);
  }
  const rawDescription = [
    parsed.meta.get("og:description"),
    parsed.meta.get("twitter:description"),
    parsed.meta.get("description"),
  ].find(isMeaningfulDescription) ?? null;
  const rawTitle = [
    parsed.meta.get("og:title"),
    parsed.meta.get("twitter:title"),
    parsed.title,
    ...parsed.headings,
  ].find((value) =>
    isMeaningfulTitle(value) &&
    norm(value) !== norm(parsed.meta.get("og:site_name"))
  ) ?? null;
  const descriptionTitle = rawDescription?.split("\n")[0];
  const title = rawTitle !== null
    ? truncateMetadataField(cleanText(rawTitle), 300)
    : truncateMetadataField(
      isMeaningfulTitle(descriptionTitle) ? cleanText(descriptionTitle) : null,
      120,
    );
  const description = truncateMetadataField(cleanText(rawDescription), 4000);
  const metadataState: MetadataState = title.value === null ||
      description.value === null
    ? "partial"
    : "ready";
  const errorCode: MetadataErrorCode | null = metadataState === "ready"
    ? null
    : "INVALID_RESULT";

  return {
    fetched_title: title.value,
    description: description.value,
    body_text: null,
    metadata_state: metadataState,
    extraction_meta: {
      adapter_version: ADAPTER_VERSION,
      final_url: boundedFinalUrl(finalUrl),
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
  primaryHeading: string | null;
  hasPasswordInput: boolean;
  hasLoginForm: boolean;
  hasChallengeMarker: boolean;
}

function parseMetadataHtml(html: string): ParsedMetadataHtml {
  const meta = new Map<string, string>();
  const titleParts: string[] = [];
  const headings: string[] = [];
  let primaryHeading: string | null = null;
  let currentHeading: string[] | null = null;
  let titleDepth = 0;
  let documentTitleSeen = false;
  let bodyStarted = false;
  let headingDepth = 0;
  let ignoredDepth = 0;
  let hasPasswordInput = false;
  let hasLoginForm = false;
  let hasChallengeMarker = false;
  const ignoredTags = new Set([
    "script",
    "style",
    "noscript",
    "template",
    "svg",
    "math",
  ]);

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
        const content = cleanMetadataText(attributes.content);
        if (
          content !== null &&
          (key === "og:title" || key === "og:description" ||
            key === "twitter:title" || key === "twitter:description" ||
            key === "description" || key === "og:site_name") &&
          !meta.has(key)
        ) {
          meta.set(key, content);
        }
      }
      if (name === "body") {
        bodyStarted = true;
      }
      if (name === "title" && !documentTitleSeen && !bodyStarted) {
        documentTitleSeen = true;
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
        /\b(challenge-form|cf-chl|access-denied)\b/u.test(marker)
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
          if (heading !== null && name === "h1" && primaryHeading === null) {
            primaryHeading = heading;
          }
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
    primaryHeading,
    hasPasswordInput,
    hasLoginForm,
    hasChallengeMarker,
  };
}

function documentLabels(parsed: ParsedMetadataHtml): string[] {
  const titles = [
    parsed.title,
    parsed.meta.get("og:title"),
    parsed.meta.get("twitter:title"),
  ];
  // Subsection labels cannot override a useful document title. A title-less
  // document may still expose its login/error message through a lone h2.
  const heading = parsed.primaryHeading ??
    (titles.some(isMeaningfulTitle) ? null : parsed.headings[0]);
  return [
    ...titles,
    heading,
  ].filter((value): value is string => value !== null && value !== undefined)
    .map(norm);
}

function isAccessDeniedDocument(parsed: ParsedMetadataHtml): boolean {
  const accessDescription = [
    parsed.meta.get("og:description"),
    parsed.meta.get("twitter:description"),
    parsed.meta.get("description"),
  ].some((description) =>
    /^(?:(?:please|you must|you need to)\s+)?(?:log\s?in|sign\s?in)\s+to\s+(?:continue|view|see|access)\b/u
      .test(norm(description)) ||
    /^(?:please\s+)?(?:enable|turn on)\s+javascript(?:\s+and\s+cookies)?\s+to\s+continue\b/u
      .test(norm(description))
  );
  return accessDescription || parsed.hasChallengeMarker ||
    (parsed.hasPasswordInput && parsed.hasLoginForm) ||
    documentLabels(parsed).some((label) =>
      /^(?:(?:네이버|naver)\s*)?(로그인|log\s?in|sign\s?in)(?:[.…!]*|\s*[:|•·–—-].*|\s+(?:to|into|on)\s+.*)$/u
        .test(label) ||
      /^(?:.+\s*[:|•·–—-]\s*)(로그인|log\s?in|sign\s?in)$/u.test(label) ||
      /^(just a moment|attention required|access denied|접근이 제한되었습니다|서비스 이용이 제한되었습니다|checking your browser|verify (?:that )?you are human|security verification|human verification|captcha|robot or human|are you a robot|complete the security check)(?:[.…!?]*|\s*[:|•·–—-].*)$/u
        .test(label)
    );
}

function isErrorDocument(parsed: ParsedMetadataHtml): boolean {
  return documentLabels(parsed).some((label) =>
    /^(?:sorry[,!.]?\s*)?(?:(?:error\s*)?(?:404|410|500|502|503)\s*[:|•·–—-]?\s*)?(page not found|not found|this page isn['’]t available|this content isn['’]t available|page unavailable|internal server error|server error|service unavailable|something went wrong|페이지를 찾을 수 없습니다|요청하신 페이지를 찾을 수 없습니다)(?:[.…!]*|\s*[:|•·–—-].*)$/u
      .test(label) || /^(error|오류|404|410|500|502|503)$/u.test(label)
  );
}

function isMeaningfulTitle(value: string | null | undefined): boolean {
  if (value === null || value === undefined || genericTitle(value)) {
    return false;
  }
  const normalized = norm(value);
  if (
    /^(naver blog|네이버 블로그|blog|블로그|just a moment|attention required|untitled document)$/u
      .test(normalized) ||
    /^threads(?:\.(?:net|com))?(?:\s*[,|•·:–—-].*)?$/u
      .test(normalized)
  ) {
    return false;
  }
  return isMeaningfulText(value);
}

function isMeaningfulText(value: string | null | undefined): boolean {
  return value !== null && value !== undefined && /[\p{L}\p{N}]/u.test(value);
}

function isMeaningfulDescription(value: string | null | undefined): boolean {
  if (!isMeaningfulText(value) || genericTitle(value ?? "")) {
    return false;
  }
  return !/^(naver blog|네이버 블로그|서비스 홈(입니다)?|블로그(입니다)?|login|로그인|threads|instagram)$/u
    .test(norm(value)) &&
    !/^(join (?:threads|instagram)\b|say more with threads\b|create an account or log in to (?:threads|instagram)\b|threads에서 (?:가입|로그인))/u
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

function cleanMetadataText(value: string | undefined): string | null {
  if (value === undefined) {
    return null;
  }
  const cleaned = value.replace(/\r\n?/gu, "\n").split("\n")
    .map((line) => line.replace(/\s+/gu, " ").trim()).filter(Boolean).join(
      "\n",
    );
  return cleaned.length === 0 ? null : cleaned;
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
  return charset;
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
    if (!intentionallyCancelled) reportNetworkFailure("HTTP", error);
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
    reportNetworkFailure("HTTP", destroyError);
  }
}

function observeClientDestroy(
  owner: UndiciResponseOwner,
  error: Error,
): void {
  try {
    owner.destroy(error).catch((destroyError) => {
      reportNetworkFailure("HTTP", destroyError);
    });
  } catch (destroyError) {
    reportNetworkFailure("HTTP", destroyError);
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
      final_url: boundedFinalUrl(finalUrl),
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
      last_checked_at: checkedAt.toISOString(),
      error_code: errorCode,
    },
  };
}

function boundedFinalUrl(finalUrl: string | null): string | null {
  return finalUrl !== null && [...finalUrl].length <= 4096 ? finalUrl : null;
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
    !(groups[0] === 0x2001 && groups[1] < 0x0200) &&
    !(groups[0] === 0x2001 && groups[1] === 0x0db8) &&
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
