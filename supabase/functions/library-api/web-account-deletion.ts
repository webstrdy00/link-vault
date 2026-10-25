import { decodeJwt } from "jose";
import {
  type AccountDeletionNonceDeriver,
  type AccountDeletionRpcResult,
  createProductionGoogleIdTokenVerifier,
  GoogleIdTokenVerificationUnavailableError,
  type GoogleIdTokenVerifier,
  MAX_ACCOUNT_DELETE_BODY_BYTES,
  parseAccountDeletionBody,
} from "./account-deletion.ts";

export const WEB_ACCOUNT_ORIGIN = "https://linkfilebox.com";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NONCE = /^[A-Za-z0-9_-]{43}$/;
const encoder = new TextEncoder();
const ALLOWED_HEADERS = ["content-type", "x-request-id", "apikey"];
export type WebAccountPurpose = "delete" | "status";
export type WebAccountRpcName =
  | "library_web_account_challenge"
  | "library_web_account_begin"
  | "library_web_account_finish";
export interface WebAccountGateway {
  rpc(
    name: WebAccountRpcName,
    args: Record<string, unknown>,
  ): Promise<AccountDeletionRpcResult>;
}
interface WebReply {
  http_status: number;
  [key: string]: unknown;
}

class WebAccountError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
  ) {
    super(message);
  }
}

const RPC_ERRORS: Record<string, [number, string, boolean]> = {
  WEB_CHALLENGE_INVALID: [
    409,
    "The verification challenge is invalid, expired, or already used.",
    false,
  ],
  IDEMPOTENCY_MISMATCH: [
    409,
    "The request ID was already used for a different request.",
    false,
  ],
  RATE_LIMITED: [
    429,
    "Too many verification requests. Wait before trying again.",
    true,
  ],
  ACCOUNT_NOT_FOUND: [
    404,
    "No existing account is linked to this Google identity.",
    false,
  ],
  STATUS_NOT_FOUND: [
    404,
    "No retained deletion status is available for this Google identity. This does not confirm deletion.",
    false,
  ],
  ACCOUNT_DELETING: [
    403,
    "Account deletion is already in progress. Verify its status instead.",
    false,
  ],
  ACCOUNT_ALREADY_DELETED: [
    409,
    "The retained deletion job is complete. Verify its status instead.",
    false,
  ],
  GOOGLE_IDENTITY_MISMATCH: [
    403,
    "The linked Google identity is ambiguous or inconsistent.",
    false,
  ],
  DEPENDENCY_UNAVAILABLE: [
    503,
    "The account deletion service is temporarily unavailable.",
    true,
  ],
};

export function isWebAccountDeletionPath(pathname: string): boolean {
  const path = pathname.startsWith("/functions/v1/")
    ? pathname.slice("/functions/v1".length)
    : pathname;
  return path === "/library-api/v1/web-account" ||
    path.startsWith("/library-api/v1/web-account/");
}

export class WebAccountDeletionService {
  constructor(
    private readonly gateway: WebAccountGateway,
    private readonly verifier: GoogleIdTokenVerifier,
    private readonly nonces: Record<
      WebAccountPurpose,
      AccountDeletionNonceDeriver
    >,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async challenge(
    purpose: WebAccountPurpose,
    requestId: string,
  ): Promise<WebReply> {
    const nonce = await this.nonces[purpose].derive(requestId, requestId);
    if (!NONCE.test(nonce)) throw unavailable();
    const result = await this.call("library_web_account_challenge", {
      p_request_id: requestId,
      p_purpose: purpose,
      p_nonce_hash: await sha256(nonce),
    });
    if (
      result.http_status !== 201 ||
      !exactKeys(result, ["http_status", "challenge_id", "expires_at"]) ||
      typeof result.challenge_id !== "string" ||
      !UUID.test(result.challenge_id) || !timestamp(result.expires_at)
    ) {
      throw unavailable();
    }
    return { ...result, purpose, nonce };
  }

  async prove(
    purpose: WebAccountPurpose,
    requestId: string,
    rawBody: string,
  ): Promise<WebReply> {
    let body: ReturnType<typeof parseAccountDeletionBody>;
    try {
      body = parseAccountDeletionBody(rawBody);
    } catch {
      throw invalid(
        "The proof request body must contain only challenge_id and google_id_token.",
      );
    }
    // Digest the token, never persist it. The purpose prevents cross-endpoint replay.
    const requestHash = await sha256(JSON.stringify({
      purpose,
      challenge_id: body.challenge_id.toLowerCase(),
      google_id_token: body.google_id_token,
    }));
    const args = {
      p_request_id: requestId,
      p_challenge_id: body.challenge_id.toLowerCase(),
      p_purpose: purpose,
      p_request_hash: requestHash,
    };
    const begin = await this.call("library_web_account_begin", args);
    if (begin.state !== "verify") return validateProofReply(begin, purpose);
    if (
      !exactKeys(begin, ["http_status", "state", "created_at"]) ||
      begin.http_status !== 200 || !timestamp(begin.created_at)
    ) throw unavailable();
    let proof;
    try {
      proof = await this.verifier.verify(body.google_id_token);
      // The reused verifier authenticates these claims. Browser proofs must
      // additionally be freshly issued for this challenge, not merely unexpired.
      const issuedAt = decodeJwt(body.google_id_token).iat;
      const nowSeconds = Math.floor(this.now().getTime() / 1000);
      if (
        typeof issuedAt !== "number" || !Number.isInteger(issuedAt) ||
        !Number.isFinite(nowSeconds) ||
        issuedAt < Math.floor(Date.parse(begin.created_at) / 1000) - 30 ||
        issuedAt < nowSeconds - 330 || issuedAt > nowSeconds + 30
      ) throw new Error("Stale proof.");
    } catch (error) {
      if (error instanceof GoogleIdTokenVerificationUnavailableError) {
        throw unavailable();
      }
      throw new WebAccountError(
        401,
        "GOOGLE_PROOF_INVALID",
        "The Google identity proof is invalid. Obtain a fresh proof.",
      );
    }
    if (
      !NONCE.test(proof.nonce) || !/^[\x21-\x7e]{1,255}$/.test(proof.subject)
    ) {
      throw new WebAccountError(
        401,
        "GOOGLE_PROOF_INVALID",
        "The Google identity proof is invalid. Obtain a fresh proof.",
      );
    }
    return validateProofReply(
      await this.call("library_web_account_finish", {
        ...args,
        p_google_subject: proof.subject,
        p_nonce_hash: await sha256(proof.nonce),
      }),
      purpose,
    );
  }

  private async call(
    name: WebAccountRpcName,
    args: Record<string, unknown>,
  ): Promise<WebReply> {
    let result;
    try {
      result = await this.gateway.rpc(name, args);
    } catch {
      throw unavailable();
    }
    if (
      !object(result) || result.error !== null || !object(result.data) ||
      typeof result.data.http_status !== "number"
    ) throw unavailable();
    const data = result.data as WebReply;
    if (typeof data.error_code === "string") {
      const error = RPC_ERRORS[data.error_code];
      if (error === undefined || data.http_status !== error[0]) {
        throw unavailable();
      }
      throw new WebAccountError(error[0], data.error_code, error[1], error[2]);
    }
    return data;
  }
}

export function createProductionWebAccountDeletionService(
  gateway: WebAccountGateway,
  credential: string,
): WebAccountDeletionService {
  return new WebAccountDeletionService(
    gateway,
    createProductionGoogleIdTokenVerifier(),
    createWebAccountNonceDerivers(credential),
  );
}

export function createWebAccountNonceDerivers(
  credential: string,
): Record<WebAccountPurpose, AccountDeletionNonceDeriver> {
  if (
    encoder.encode(credential).byteLength < 32 ||
    credential !== credential.trim()
  ) throw new Error("Invalid service credential.");
  const material = crypto.subtle.importKey(
    "raw",
    encoder.encode(credential),
    "HKDF",
    false,
    ["deriveKey"],
  );
  function deriver(purpose: WebAccountPurpose): AccountDeletionNonceDeriver {
    const key = material.then((value) =>
      crypto.subtle.deriveKey(
        {
          name: "HKDF",
          hash: "SHA-256",
          salt: encoder.encode("link-vault/service-credential-kdf/v1"),
          info: encoder.encode(`link-vault/web-account/${purpose}/nonce/v1`),
        },
        value,
        { name: "HMAC", hash: "SHA-256", length: 256 },
        false,
        ["sign"],
      )
    );
    return {
      async derive(_ownerId, requestId) {
        const signature = new Uint8Array(
          await crypto.subtle.sign(
            "HMAC",
            await key,
            encoder.encode(requestId),
          ),
        );
        return btoa(String.fromCharCode(...signature)).replaceAll("+", "-")
          .replaceAll("/", "_").replace(/=+$/, "");
      },
    };
  }
  return {
    delete: deriver("delete"),
    status: deriver("status"),
  };
}

export function createWebAccountDeletionHandler(
  service: WebAccountDeletionService | undefined,
  scheduleDeletion?: () => void,
) {
  return async (request: Request): Promise<Response> => {
    const approved = request.headers.get("origin") === WEB_ACCOUNT_ORIGIN;
    const givenId = request.headers.get("x-request-id");
    const requestId = givenId !== null && UUID.test(givenId)
      ? givenId.toLowerCase()
      : crypto.randomUUID();
    const headers = new Headers({
      "cache-control": "no-store",
      "pragma": "no-cache",
      "x-request-id": requestId,
      "vary":
        "Origin, Access-Control-Request-Method, Access-Control-Request-Headers",
      "x-content-type-options": "nosniff",
    });
    if (approved) {
      headers.set("access-control-allow-origin", WEB_ACCOUNT_ORIGIN);
      headers.set("access-control-expose-headers", "X-Request-Id, Retry-After");
    }
    function response(value: unknown, status: number): Response {
      headers.set("content-type", "application/json; charset=utf-8");
      return new Response(JSON.stringify(value), { status, headers });
    }
    try {
      if (!approved) {
        throw new WebAccountError(
          403,
          "ORIGIN_NOT_ALLOWED",
          "This browser origin is not allowed.",
        );
      }
      const url = new URL(request.url);
      if (url.search !== "") throw invalid("Query parameters are not allowed.");
      const path = url.pathname.startsWith("/functions/v1/")
        ? url.pathname.slice("/functions/v1".length)
        : url.pathname;
      const action = path.slice("/library-api/v1/web-account/".length);
      if (
        !isWebAccountDeletionPath(url.pathname) ||
        !["challenge", "delete", "status"].includes(action)
      ) {
        throw new WebAccountError(404, "NOT_FOUND", "Route not found.");
      }
      if (request.method === "OPTIONS") {
        const requested =
          (request.headers.get("access-control-request-headers") ?? "").split(
            ",",
          ).map((v) => v.trim().toLowerCase()).filter(Boolean);
        if (
          request.headers.get("access-control-request-method") !== "POST" ||
          requested.some((v) => !ALLOWED_HEADERS.includes(v))
        ) {
          throw new WebAccountError(
            403,
            "PREFLIGHT_NOT_ALLOWED",
            "This browser preflight is not allowed.",
          );
        }
        headers.set("access-control-allow-methods", "POST");
        headers.set("access-control-allow-headers", ALLOWED_HEADERS.join(", "));
        return new Response(null, { status: 204, headers });
      }
      if (request.method !== "POST") {
        throw new WebAccountError(
          405,
          "METHOD_NOT_ALLOWED",
          "Only POST is allowed.",
        );
      }
      if (givenId === null || !UUID.test(givenId)) {
        throw invalid("X-Request-Id must be a UUID.");
      }
      // Browser credentials never participate in authorization, including accidental cookies.
      if (request.headers.has("cookie")) {
        throw invalid("Cookies are not accepted by this endpoint.");
      }
      const mediaType = request.headers.get("content-type")?.split(";")[0]
        .trim().toLowerCase();
      if (mediaType !== "application/json") {
        throw new WebAccountError(
          415,
          "UNSUPPORTED_MEDIA_TYPE",
          "Content-Type must be application/json.",
        );
      }
      const rawBody = await boundedBody(request);
      if (service === undefined) throw unavailable();
      let reply;
      if (action === "challenge") {
        let value;
        try {
          value = JSON.parse(rawBody);
        } catch {
          throw invalid("The request body must be valid JSON.");
        }
        if (
          !object(value) || !exactKeys(value, ["purpose"]) ||
          (value.purpose !== "delete" && value.purpose !== "status")
        ) {
          throw invalid("The challenge purpose must be delete or status.");
        }
        reply = await service.challenge(value.purpose, requestId);
      } else {
        reply = await service.prove(
          action as WebAccountPurpose,
          requestId,
          rawBody,
        );
      }
      const { http_status, ...body } = reply;
      if (action === "delete" && http_status === 202) {
        try {
          scheduleDeletion?.();
        } catch {
          /* Acceptance already committed; maintenance also dispatches independently. */
        }
      }
      return response(body, http_status);
    } catch (error) {
      const failure = error instanceof WebAccountError ? error : unavailable();
      if (failure.status === 429) headers.set("retry-after", "60");
      return response({
        error: {
          code: failure.code,
          message: failure.message,
          retryable: failure.retryable,
        },
        request_id: requestId,
      }, failure.status);
    }
  };
}

function validateProofReply(
  reply: WebReply,
  purpose: WebAccountPurpose,
): WebReply {
  if (purpose === "delete") {
    if (
      reply.http_status !== 202 || reply.state !== "deleting" ||
      !exactKeys(reply, ["http_status", "state"])
    ) throw unavailable();
  } else {
    if (
      reply.http_status !== 200 ||
      !exactKeys(reply, [
        "http_status",
        "state",
        "requested_at",
        "completed_at",
        "status_available_until",
      ])
    ) throw unavailable();
    if (reply.state === "not_requested") {
      if (
        reply.requested_at !== null || reply.completed_at !== null ||
        reply.status_available_until !== null
      ) throw unavailable();
    } else if (reply.state === "deleting") {
      if (
        !timestamp(reply.requested_at) || reply.completed_at !== null ||
        reply.status_available_until !== null
      ) throw unavailable();
    } else if (reply.state === "deleted") {
      if (
        !timestamp(reply.requested_at) || !timestamp(reply.completed_at) ||
        !timestamp(reply.status_available_until) ||
        Date.parse(reply.completed_at) < Date.parse(reply.requested_at) ||
        Date.parse(reply.status_available_until) <=
          Date.parse(reply.completed_at)
      ) throw unavailable();
    } else throw unavailable();
  }
  return reply;
}

async function boundedBody(request: Request): Promise<string> {
  const contentLength = request.headers.get("content-length");
  if (
    contentLength !== null &&
    (!/^\d+$/.test(contentLength) ||
      Number(contentLength) > MAX_ACCOUNT_DELETE_BODY_BYTES)
  ) {
    throw new WebAccountError(
      413,
      "PAYLOAD_TOO_LARGE",
      "The request body is too large.",
    );
  }
  if (request.body === null) throw invalid("A JSON request body is required.");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  let reads = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(invalid("The request body could not be read."));
      void reader.cancel().catch(() => undefined);
    }, 5000);
  });
  try {
    while (true) {
      const chunk = await Promise.race([reader.read(), timeout]);
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (
        size > MAX_ACCOUNT_DELETE_BODY_BYTES ||
        ++reads > MAX_ACCOUNT_DELETE_BODY_BYTES + 1
      ) {
        void reader.cancel().catch(() => undefined);
        throw new WebAccountError(
          413,
          "PAYLOAD_TOO_LARGE",
          "The request body is too large.",
        );
      }
      if (chunk.value.byteLength > 0) chunks.push(chunk.value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (error) {
    if (error instanceof WebAccountError) throw error;
    throw invalid("The request body could not be read.");
  } finally {
    clearTimeout(timer);
    reader.releaseLock();
  }
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function exactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key));
}
function timestamp(value: unknown): value is string {
  return typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/
      .test(value) &&
    Number.isFinite(Date.parse(value));
}
function invalid(message: string): WebAccountError {
  return new WebAccountError(400, "INVALID_REQUEST", message);
}
function unavailable(): WebAccountError {
  return new WebAccountError(
    503,
    "DEPENDENCY_UNAVAILABLE",
    RPC_ERRORS.DEPENDENCY_UNAVAILABLE[1],
    true,
  );
}
async function sha256(value: string): Promise<string> {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", encoder.encode(value)),
    ),
    (v) => v.toString(16).padStart(2, "0"),
  ).join("");
}
