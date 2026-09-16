import {
  createLocalJWKSet,
  createRemoteJWKSet,
  errors,
  type JSONWebKeySet,
  jwtVerify,
  type JWTVerifyGetKey,
} from "jose";

const GOOGLE_JWKS_URL = new URL(
  "https://www.googleapis.com/oauth2/v3/certs",
);
const GOOGLE_ISSUERS = [
  "accounts.google.com",
  "https://accounts.google.com",
] as const;
// A bounded tolerance covers small clock differences for standard JWT time checks.
const GOOGLE_CLOCK_SKEW_SECONDS = 30;
const NONCE_PURPOSE = "link-vault/account-deletion/nonce/v1";
const NONCE_KDF_SALT = "link-vault/service-credential-kdf/v1";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const GOOGLE_SUBJECT_PATTERN = /^[\x21-\x7e]{1,255}$/;
const RAW_NONCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const encoder = new TextEncoder();

export const MAX_GOOGLE_ID_TOKEN_BYTES = 16 * 1024;
export const MAX_ACCOUNT_DELETE_BODY_BYTES = MAX_GOOGLE_ID_TOKEN_BYTES + 256;

export type AccountDeletionErrorCode =
  | "INVALID_REQUEST"
  | "DELETE_CHALLENGE_INVALID"
  | "GOOGLE_PROOF_INVALID"
  | "GOOGLE_IDENTITY_REQUIRED"
  | "GOOGLE_IDENTITY_MISMATCH"
  | "ACCOUNT_DELETING"
  | "IDEMPOTENCY_MISMATCH"
  | "DEPENDENCY_UNAVAILABLE";

export class AccountDeletionError extends Error {
  constructor(
    readonly status: number,
    readonly code: AccountDeletionErrorCode,
    message: string,
    readonly retryable = false,
  ) {
    super(message);
    this.name = "AccountDeletionError";
  }
}

export class AccountDeletionConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AccountDeletionConfigurationError";
  }
}

export class GoogleIdTokenVerificationError extends Error {
  constructor() {
    super("The Google identity proof is invalid.");
    this.name = "GoogleIdTokenVerificationError";
  }
}

export class GoogleIdTokenVerificationUnavailableError extends Error {
  constructor() {
    super("Google identity verification is temporarily unavailable.");
    this.name = "GoogleIdTokenVerificationUnavailableError";
  }
}

export interface AccountDeletionRpcFailure {
  code?: string;
  message: string;
}

export interface AccountDeletionRpcResult {
  data: unknown;
  error: AccountDeletionRpcFailure | null;
}

export interface CreateDeleteChallengeCall {
  ownerId: string;
  requestId: string;
  nonceHash: string;
}

export interface ReplayAccountDeletionCall {
  ownerId: string;
  requestId: string;
  requestHash: string;
}

export interface CheckDeleteChallengeBindingCall {
  ownerId: string;
  challengeId: string;
  nonceHash: string;
}

export interface AcceptAccountDeletionCall extends ReplayAccountDeletionCall {
  challengeId: string;
}

export interface AccountDeletionGateway {
  createDeleteChallenge(
    call: CreateDeleteChallengeCall,
  ): Promise<AccountDeletionRpcResult>;
  replayAccountDeletion(
    call: ReplayAccountDeletionCall,
  ): Promise<AccountDeletionRpcResult>;
  checkDeleteChallengeBinding(
    call: CheckDeleteChallengeBindingCall,
  ): Promise<AccountDeletionRpcResult>;
  acceptAccountDeletion(
    call: AcceptAccountDeletionCall,
  ): Promise<AccountDeletionRpcResult>;
}

export interface SupabaseAuthIdentity {
  provider?: unknown;
  provider_id?: unknown;
  identity_data?: unknown;
}

export interface VerifiedAccountDeletionUser {
  id: string;
  identities?: readonly SupabaseAuthIdentity[] | null;
}

export interface GoogleIdentityProof {
  subject: string;
  nonce: string;
}

export interface GoogleIdTokenVerifier {
  verify(idToken: string): Promise<GoogleIdentityProof>;
}

export interface AccountDeletionNonceDeriver {
  derive(ownerId: string, requestId: string): Promise<string>;
}

export interface CreateDeleteChallengeInput {
  user: Pick<VerifiedAccountDeletionUser, "id">;
  requestId: string;
}

export interface DeleteAccountInput {
  user: VerifiedAccountDeletionUser;
  requestId: string;
  rawBody: string;
}

export interface DeleteChallengeResult {
  challenge_id: string;
  nonce: string;
  expires_at: string;
}

export interface AccountDeletionResult {
  state: "deleting";
}

export interface AccountDeletionBody {
  challenge_id: string;
  google_id_token: string;
}

export class AccountDeletionService {
  constructor(
    private readonly gateway: AccountDeletionGateway,
    private readonly googleVerifier: GoogleIdTokenVerifier,
    private readonly nonceDeriver: AccountDeletionNonceDeriver,
  ) {}

  async createChallenge(
    input: CreateDeleteChallengeInput,
  ): Promise<DeleteChallengeResult> {
    const ownerId = requireUuid(input.user.id);
    const requestId = requireUuid(input.requestId);

    let nonce: string;
    try {
      nonce = await this.nonceDeriver.derive(ownerId, requestId);
    } catch {
      throw dependencyUnavailable();
    }
    if (!RAW_NONCE_PATTERN.test(nonce)) {
      throw dependencyUnavailable();
    }

    const nonceHash = await sha256Hex(nonce);
    const data = await callGateway(() =>
      this.gateway.createDeleteChallenge({ ownerId, requestId, nonceHash })
    );
    const row = isObject(data) ? data : null;
    if (
      row === null ||
      !hasExactKeys(row, ["http_status", "challenge_id", "expires_at"]) ||
      row.http_status !== 201 ||
      typeof row.challenge_id !== "string" ||
      !UUID_PATTERN.test(row.challenge_id) ||
      !isTimestamp(row.expires_at)
    ) {
      throw dependencyUnavailable();
    }

    return {
      challenge_id: row.challenge_id,
      nonce,
      expires_at: row.expires_at,
    };
  }

  async deleteAccount(
    input: DeleteAccountInput,
  ): Promise<AccountDeletionResult> {
    const ownerId = requireUuid(input.user.id);
    const requestId = requireUuid(input.requestId);
    const body = parseAccountDeletionBody(input.rawBody);
    const requestHash = await hashAccountDeletionBody(body);
    const replayCall = {
      ownerId,
      requestId,
      requestHash,
    };

    const replayData = await callGateway(() =>
      this.gateway.replayAccountDeletion(replayCall)
    );
    const replayState = parseReplayState(replayData);
    if (replayState !== null) {
      return { state: replayState };
    }

    const linkedSubjects = linkedGoogleSubjects(input.user);
    if (linkedSubjects.size === 0) {
      throw new AccountDeletionError(
        403,
        "GOOGLE_IDENTITY_REQUIRED",
        "A linked Google identity is required.",
      );
    }

    let proof: GoogleIdentityProof;
    try {
      proof = await this.googleVerifier.verify(body.google_id_token);
    } catch (error) {
      if (error instanceof GoogleIdTokenVerificationUnavailableError) {
        throw dependencyUnavailable();
      }
      throw new AccountDeletionError(
        401,
        "GOOGLE_PROOF_INVALID",
        "The Google identity proof is invalid.",
      );
    }

    if (!linkedSubjects.has(proof.subject)) {
      throw new AccountDeletionError(
        403,
        "GOOGLE_IDENTITY_MISMATCH",
        "The Google identity does not match the signed-in account.",
      );
    }

    const proofNonceHash = await sha256Hex(proof.nonce);
    const bindingData = await callGateway(() =>
      this.gateway.checkDeleteChallengeBinding({
        ownerId,
        challengeId: body.challenge_id,
        nonceHash: proofNonceHash,
      })
    );
    requireValidChallengeBinding(bindingData);

    const acceptCall = {
      ...replayCall,
      challengeId: body.challenge_id,
    };
    const acceptData = await callGateway(() =>
      this.gateway.acceptAccountDeletion(acceptCall)
    );
    const acceptedState = parseAcceptedState(acceptData);
    return { state: acceptedState };
  }
}

export function createProductionAccountDeletionService(
  gateway: AccountDeletionGateway,
  serviceRoleCredential: string,
): AccountDeletionService {
  return new AccountDeletionService(
    gateway,
    createProductionGoogleIdTokenVerifier(),
    createServiceCredentialNonceDeriver(serviceRoleCredential),
  );
}

export function createProductionGoogleIdTokenVerifier(): GoogleIdTokenVerifier {
  const audience = configuredGoogleAudience();
  const remoteJwks = createRemoteJWKSet(GOOGLE_JWKS_URL);
  const jwks: JWTVerifyGetKey = async (protectedHeader, token) => {
    try {
      return await remoteJwks(protectedHeader, token);
    } catch (error) {
      if (
        error instanceof errors.JWKSNoMatchingKey ||
        error instanceof errors.JWKSMultipleMatchingKeys
      ) {
        throw error;
      }
      throw new GoogleIdTokenVerificationUnavailableError();
    }
  };
  return createJoseGoogleIdTokenVerifier(audience, jwks, () => new Date());
}

export function createGoogleIdTokenVerifierForUnitTests(input: {
  audience: string;
  jwks: JSONWebKeySet;
  now: () => Date;
}): GoogleIdTokenVerifier {
  const audience = requireConfigurationValue(
    input.audience,
    "Google test audience",
  );
  return createJoseGoogleIdTokenVerifier(
    audience,
    createLocalJWKSet(input.jwks),
    input.now,
  );
}

export function createServiceCredentialNonceDeriver(
  serviceRoleCredential: string,
): AccountDeletionNonceDeriver {
  const credential = requireConfigurationValue(
    serviceRoleCredential,
    "Service role credential",
  );
  const credentialBytes = encoder.encode(credential);
  if (credentialBytes.byteLength < 32) {
    throw new AccountDeletionConfigurationError(
      "Service role credential is too short for nonce derivation.",
    );
  }
  const key = (async () => {
    const keyMaterial = await crypto.subtle.importKey(
      "raw",
      credentialBytes,
      "HKDF",
      false,
      ["deriveKey"],
    );
    return await crypto.subtle.deriveKey(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: encoder.encode(NONCE_KDF_SALT),
        info: encoder.encode(NONCE_PURPOSE),
      },
      keyMaterial,
      { name: "HMAC", hash: "SHA-256", length: 256 },
      false,
      ["sign"],
    );
  })();

  return {
    async derive(ownerId: string, requestId: string): Promise<string> {
      const normalizedOwnerId = requireUuid(ownerId);
      const normalizedRequestId = requireUuid(requestId);
      const signature = await crypto.subtle.sign(
        "HMAC",
        await key,
        encoder.encode(`${normalizedOwnerId}\0${normalizedRequestId}`),
      );
      return base64Url(new Uint8Array(signature));
    },
  };
}

export function parseDeleteChallengeBody(
  rawBody: string,
): Record<string, never> {
  const value = parseBoundedJson(rawBody);
  if (!isObject(value) || Object.keys(value).length !== 0) {
    throw invalidRequest("The challenge request body must be an empty object.");
  }
  return {};
}

export function parseAccountDeletionBody(
  rawBody: string,
): AccountDeletionBody {
  const value = parseBoundedJson(rawBody);
  if (
    !isObject(value) ||
    !hasExactKeys(value, ["challenge_id", "google_id_token"]) ||
    typeof value.challenge_id !== "string" ||
    !UUID_PATTERN.test(value.challenge_id) ||
    typeof value.google_id_token !== "string" ||
    value.google_id_token.length === 0 ||
    encodedLength(value.google_id_token) > MAX_GOOGLE_ID_TOKEN_BYTES
  ) {
    throw invalidRequest("The account deletion request body is invalid.");
  }
  return {
    challenge_id: value.challenge_id,
    google_id_token: value.google_id_token,
  };
}

export async function hashAccountDeletionBody(
  body: AccountDeletionBody,
): Promise<string> {
  return await sha256Hex(JSON.stringify({
    challenge_id: body.challenge_id,
    google_id_token: body.google_id_token,
  }));
}

function createJoseGoogleIdTokenVerifier(
  audience: string,
  jwks: JWTVerifyGetKey,
  now: () => Date,
): GoogleIdTokenVerifier {
  return {
    async verify(idToken: string): Promise<GoogleIdentityProof> {
      try {
        if (
          typeof idToken !== "string" || idToken.length === 0 ||
          encodedLength(idToken) > MAX_GOOGLE_ID_TOKEN_BYTES
        ) {
          throw new Error("Invalid token size.");
        }
        const currentDate = now();
        if (!Number.isFinite(currentDate.getTime())) {
          throw new Error("Invalid verification clock.");
        }
        const { payload, protectedHeader } = await jwtVerify(idToken, jwks, {
          algorithms: ["RS256"],
          audience,
          issuer: [...GOOGLE_ISSUERS],
          requiredClaims: ["iss", "aud", "sub", "exp", "iat", "nonce"],
          clockTolerance: GOOGLE_CLOCK_SKEW_SECONDS,
          currentDate,
        });
        const nowSeconds = Math.floor(currentDate.getTime() / 1000);
        if (
          protectedHeader.alg !== "RS256" ||
          typeof payload.aud !== "string" || payload.aud !== audience ||
          typeof payload.iat !== "number" || !Number.isInteger(payload.iat) ||
          typeof payload.exp !== "number" || !Number.isInteger(payload.exp) ||
          payload.exp <= payload.iat ||
          payload.iat > nowSeconds + GOOGLE_CLOCK_SKEW_SECONDS ||
          typeof payload.sub !== "string" ||
          !GOOGLE_SUBJECT_PATTERN.test(payload.sub) ||
          typeof payload.nonce !== "string" ||
          !RAW_NONCE_PATTERN.test(payload.nonce)
        ) {
          throw new Error("Invalid Google claims.");
        }
        return { subject: payload.sub, nonce: payload.nonce };
      } catch (error) {
        if (error instanceof GoogleIdTokenVerificationUnavailableError) {
          throw error;
        }
        throw new GoogleIdTokenVerificationError();
      }
    },
  };
}

function configuredGoogleAudience(): string {
  let value: string | undefined;
  try {
    value = Deno.env.get("GOOGLE_WEB_CLIENT_ID");
  } catch {
    throw new AccountDeletionConfigurationError(
      "GOOGLE_WEB_CLIENT_ID is unavailable.",
    );
  }
  return requireConfigurationValue(value, "GOOGLE_WEB_CLIENT_ID");
}

function requireConfigurationValue(
  value: string | undefined,
  name: string,
): string {
  if (value === undefined || value.length === 0 || value !== value.trim()) {
    throw new AccountDeletionConfigurationError(`${name} is not configured.`);
  }
  return value;
}

function parseBoundedJson(rawBody: string): unknown {
  if (
    typeof rawBody !== "string" ||
    encodedLength(rawBody) > MAX_ACCOUNT_DELETE_BODY_BYTES
  ) {
    throw invalidRequest("The request body is too large.");
  }
  try {
    return JSON.parse(rawBody);
  } catch {
    throw invalidRequest("The request body must be valid JSON.");
  }
}

function linkedGoogleSubjects(user: VerifiedAccountDeletionUser): Set<string> {
  const subjects = new Set<string>();
  if (!Array.isArray(user.identities)) {
    return subjects;
  }
  for (const identity of user.identities) {
    if (!isObject(identity) || identity.provider !== "google") {
      continue;
    }
    const providerId = validGoogleSubject(identity.provider_id);
    const identityData = isObject(identity.identity_data)
      ? identity.identity_data
      : null;
    const dataSubject = validGoogleSubject(identityData?.sub);
    if (
      providerId !== null && dataSubject !== null &&
      providerId !== dataSubject
    ) {
      continue;
    }
    const subject = providerId ?? dataSubject;
    if (subject !== null) {
      subjects.add(subject);
    }
  }
  return subjects;
}

function validGoogleSubject(value: unknown): string | null {
  return typeof value === "string" && GOOGLE_SUBJECT_PATTERN.test(value)
    ? value
    : null;
}

async function callGateway(
  operation: () => Promise<AccountDeletionRpcResult>,
): Promise<unknown> {
  let result: AccountDeletionRpcResult;
  try {
    result = await operation();
  } catch {
    throw dependencyUnavailable();
  }
  if (
    !isObject(result) || !("data" in result) || !("error" in result) ||
    (result.error !== null && !isRpcFailure(result.error))
  ) {
    throw dependencyUnavailable();
  }
  if (result.error !== null) {
    throw mapRpcFailure(result.error);
  }
  return result.data;
}

function parseReplayState(data: unknown): "deleting" | null {
  if (data === null) {
    return null;
  }
  if (
    !isObject(data) || !hasExactKeys(data, ["http_status", "state"]) ||
    data.http_status !== 202 || data.state !== "deleting"
  ) {
    throw dependencyUnavailable();
  }
  return data.state;
}

function requireValidChallengeBinding(data: unknown): void {
  if (
    !isObject(data) ||
    !hasExactKeys(data, ["http_status", "state"]) ||
    data.http_status !== 200 ||
    data.state !== "valid"
  ) {
    throw dependencyUnavailable();
  }
}

function parseAcceptedState(data: unknown): "deleting" {
  if (
    !isObject(data) || !hasExactKeys(data, ["http_status", "state"]) ||
    data.http_status !== 202 || data.state !== "deleting"
  ) {
    throw dependencyUnavailable();
  }
  return data.state;
}

function mapRpcFailure(error: AccountDeletionRpcFailure): AccountDeletionError {
  if (error.code === "P0001" && error.message === "ACCOUNT_DELETING") {
    return new AccountDeletionError(
      403,
      "ACCOUNT_DELETING",
      "Account deletion is already in progress.",
    );
  }
  if (error.code === "P0001" && error.message === "IDEMPOTENCY_MISMATCH") {
    return new AccountDeletionError(
      409,
      "IDEMPOTENCY_MISMATCH",
      "The request ID was already used for a different request.",
    );
  }
  if (
    error.code === "P0001" &&
    (
      error.message === "DELETE_CHALLENGE_INVALID" ||
      error.message === "DELETE_CHALLENGE_USED" ||
      error.message === "DELETE_CHALLENGE_EXPIRED"
    )
  ) {
    return new AccountDeletionError(
      409,
      "DELETE_CHALLENGE_INVALID",
      "The account deletion challenge is invalid or expired.",
    );
  }
  return dependencyUnavailable();
}

function isRpcFailure(value: unknown): value is AccountDeletionRpcFailure {
  return isObject(value) && typeof value.message === "string" &&
    (value.code === undefined || typeof value.code === "string");
}

function invalidRequest(message: string): AccountDeletionError {
  return new AccountDeletionError(400, "INVALID_REQUEST", message);
}

function dependencyUnavailable(): AccountDeletionError {
  return new AccountDeletionError(
    503,
    "DEPENDENCY_UNAVAILABLE",
    "The account deletion service is temporarily unavailable.",
    true,
  );
}

function requireUuid(value: string): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw invalidRequest("A valid request identity is required.");
  }
  return value.toLowerCase();
}

function hasExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length &&
    actual.every((key, index) => key === sortedExpected[index]);
}

function isTimestamp(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 &&
    Number.isFinite(Date.parse(value));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function encodedLength(value: string): number {
  return encoder.encode(value).byteLength;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(
    new Uint8Array(digest),
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}
