import {
  assert,
  assertEquals,
  assertNotEquals,
  assertRejects,
} from "@std/assert";
import { exportJWK, generateKeyPair, type JWK, SignJWT } from "jose";
import {
  AccountDeletionError,
  type AccountDeletionGateway,
  type AccountDeletionNonceDeriver,
  type AccountDeletionRpcResult,
  AccountDeletionService,
  createGoogleIdTokenVerifierForUnitTests,
  createServiceCredentialNonceDeriver,
  GoogleIdTokenVerificationError,
  GoogleIdTokenVerificationUnavailableError,
  type GoogleIdTokenVerifier,
  hashAccountDeletionBody,
  MAX_ACCOUNT_DELETE_BODY_BYTES,
  parseAccountDeletionBody,
  parseDeleteChallengeBody,
} from "./account-deletion.ts";

const NOW = new Date("2026-09-15T12:00:00.000Z");
const NOW_SECONDS = Math.floor(NOW.getTime() / 1000);
const AUDIENCE = "web-client.apps.googleusercontent.com";
const OWNER_ID = "0a6c0d3a-0f92-4608-9a32-dad06348d885";
const REQUEST_ID = "b78364f0-bf6b-4a80-a40b-8b155db932cd";
const SECOND_REQUEST_ID = "5ed33826-0f02-4e38-8c85-70ae595909e1";
const CHALLENGE_ID = "bb65f1d5-7b76-4430-ad52-11d0e17d0ead";
const GOOGLE_SUBJECT = "10769150350006150715113082367";
const RAW_NONCE = "n".repeat(43);
const NONCE_HASH =
  "6920b95e9f8736c294d2541181f2a9b3bd2f174178a0301cd0b6ebc6e0128cea";
const EXPIRES_AT = "2026-09-15T12:05:00.000Z";

interface SigningFixture {
  privateKey: Awaited<ReturnType<typeof generateKeyPair>>["privateKey"];
  jwk: JWK;
  algorithm: "RS256" | "ES256";
  kid: string;
}

const rsaFixture = signingFixture("RS256", "rsa-test-key");
const untrustedRsaFixture = signingFixture("RS256", "rsa-test-key");
const ecFixture = signingFixture("ES256", "ec-test-key");

async function signingFixture(
  algorithm: "RS256" | "ES256",
  kid: string,
): Promise<SigningFixture> {
  const { privateKey, publicKey } = await generateKeyPair(algorithm, {
    extractable: true,
  });
  return {
    privateKey,
    jwk: {
      ...await exportJWK(publicKey),
      alg: algorithm,
      kid,
      use: "sig",
    },
    algorithm,
    kid,
  };
}

async function verifierFor(
  fixture: Promise<SigningFixture> = rsaFixture,
): Promise<GoogleIdTokenVerifier> {
  const resolved = await fixture;
  return createGoogleIdTokenVerifierForUnitTests({
    audience: AUDIENCE,
    jwks: { keys: [resolved.jwk] },
    now: () => NOW,
  });
}

async function signedToken(
  overrides: Record<string, unknown> = {},
  fixture: Promise<SigningFixture> = rsaFixture,
): Promise<string> {
  const resolved = await fixture;
  const payload: Record<string, unknown> = {
    iss: "https://accounts.google.com",
    aud: AUDIENCE,
    sub: GOOGLE_SUBJECT,
    iat: NOW_SECONDS - 10,
    exp: NOW_SECONDS + 300,
    nonce: RAW_NONCE,
    ...overrides,
  };
  for (const [key, value] of Object.entries(payload)) {
    if (value === undefined) {
      delete payload[key];
    }
  }
  return await new SignJWT(payload).setProtectedHeader({
    alg: resolved.algorithm,
    kid: resolved.kid,
  }).sign(resolved.privateKey);
}

class FakeGateway implements AccountDeletionGateway {
  readonly createCalls: Parameters<
    AccountDeletionGateway["createDeleteChallenge"]
  >[0][] = [];
  readonly replayCalls: Parameters<
    AccountDeletionGateway["replayAccountDeletion"]
  >[0][] = [];
  readonly bindingCalls: Parameters<
    AccountDeletionGateway["checkDeleteChallengeBinding"]
  >[0][] = [];
  readonly acceptCalls: Parameters<
    AccountDeletionGateway["acceptAccountDeletion"]
  >[0][] = [];

  createResult: AccountDeletionRpcResult = {
    data: {
      http_status: 201,
      challenge_id: CHALLENGE_ID,
      expires_at: EXPIRES_AT,
    },
    error: null,
  };
  replayResults: AccountDeletionRpcResult[] = [{ data: null, error: null }];
  bindingResult: AccountDeletionRpcResult = {
    data: { http_status: 200, state: "valid" },
    error: null,
  };
  acceptResult: AccountDeletionRpcResult = {
    data: { http_status: 202, state: "deleting" },
    error: null,
  };

  createDeleteChallenge(
    call: Parameters<AccountDeletionGateway["createDeleteChallenge"]>[0],
  ): Promise<AccountDeletionRpcResult> {
    this.createCalls.push(call);
    return Promise.resolve(this.createResult);
  }

  replayAccountDeletion(
    call: Parameters<AccountDeletionGateway["replayAccountDeletion"]>[0],
  ): Promise<AccountDeletionRpcResult> {
    this.replayCalls.push(call);
    const result = this.replayResults.shift();
    assert(result !== undefined, "Unexpected replay call.");
    return Promise.resolve(result);
  }

  checkDeleteChallengeBinding(
    call: Parameters<AccountDeletionGateway["checkDeleteChallengeBinding"]>[0],
  ): Promise<AccountDeletionRpcResult> {
    this.bindingCalls.push(call);
    return Promise.resolve(this.bindingResult);
  }

  acceptAccountDeletion(
    call: Parameters<AccountDeletionGateway["acceptAccountDeletion"]>[0],
  ): Promise<AccountDeletionRpcResult> {
    this.acceptCalls.push(call);
    return Promise.resolve(this.acceptResult);
  }
}

class CountingVerifier implements GoogleIdTokenVerifier {
  readonly tokens: string[] = [];

  constructor(
    private readonly result = {
      subject: GOOGLE_SUBJECT,
      nonce: RAW_NONCE,
    },
  ) {}

  verify(idToken: string): Promise<{ subject: string; nonce: string }> {
    this.tokens.push(idToken);
    return Promise.resolve(this.result);
  }
}

const fixedNonceDeriver: AccountDeletionNonceDeriver = {
  derive: () => Promise.resolve("n".repeat(43)),
};

function deletionBody(
  token = "signed.google.id-token",
  challengeId = CHALLENGE_ID,
): string {
  return JSON.stringify({
    challenge_id: challengeId,
    google_id_token: token,
  });
}

function verifiedUser(subject = GOOGLE_SUBJECT) {
  return {
    id: OWNER_ID,
    identities: [{
      provider: "google",
      provider_id: subject,
      identity_data: { sub: subject, email: "not-an-identifier@example.com" },
    }],
  };
}

async function expectServiceError(
  operation: () => Promise<unknown>,
  code: AccountDeletionError["code"],
): Promise<AccountDeletionError> {
  try {
    await operation();
  } catch (error) {
    assert(error instanceof AccountDeletionError);
    assertEquals(error.code, code);
    return error;
  }
  throw new Error(`Expected ${code}.`);
}

Deno.test("Google verifier accepts either documented issuer with an exact audience", async () => {
  const verifier = await verifierFor();
  for (
    const issuer of ["accounts.google.com", "https://accounts.google.com"]
  ) {
    assertEquals(
      await verifier.verify(await signedToken({ iss: issuer })),
      { subject: GOOGLE_SUBJECT, nonce: RAW_NONCE },
    );
  }
});

Deno.test("Google verifier rejects malformed tokens", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    () => verifier.verify("not-a-jwt"),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier rejects an RS256 token signed by an untrusted key", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () =>
      await verifier.verify(await signedToken({}, untrustedRsaFixture)),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier rejects wrong and non-exact audiences", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () =>
      await verifier.verify(await signedToken({ aud: "other-client" })),
    GoogleIdTokenVerificationError,
  );
  await assertRejects(
    async () =>
      await verifier.verify(
        await signedToken({ aud: [AUDIENCE, "other-client"] }),
      ),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier rejects an untrusted issuer", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () =>
      await verifier.verify(
        await signedToken({ iss: "https://attacker.example" }),
      ),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier permits only RS256", async () => {
  const verifier = await verifierFor(ecFixture);
  await assertRejects(
    async () => await verifier.verify(await signedToken({}, ecFixture)),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier requires a valid subject claim", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () => await verifier.verify(await signedToken({ sub: undefined })),
    GoogleIdTokenVerificationError,
  );
  await assertRejects(
    async () =>
      await verifier.verify(await signedToken({ sub: "bad subject" })),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier requires the raw 43-character base64url nonce claim", async () => {
  const verifier = await verifierFor();
  for (
    const nonce of [
      NONCE_HASH,
      "n".repeat(42),
      "n".repeat(44),
      "n".repeat(42) + "+",
    ]
  ) {
    await assertRejects(
      async () => await verifier.verify(await signedToken({ nonce })),
      GoogleIdTokenVerificationError,
    );
  }
});

Deno.test("Google verifier rejects an expired token outside clock skew", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () =>
      await verifier.verify(
        await signedToken({
          iat: NOW_SECONDS - 200,
          exp: NOW_SECONDS - 31,
        }),
      ),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("Google verifier rejects credentials issued beyond future clock skew", async () => {
  const verifier = await verifierFor();
  await assertRejects(
    async () =>
      await verifier.verify(
        await signedToken({
          iat: NOW_SECONDS + 31,
          exp: NOW_SECONDS + 300,
        }),
      ),
    GoogleIdTokenVerificationError,
  );
});

Deno.test("strict body parsers reject malformed, extra, invalid, and oversized input", async () => {
  assertEquals(parseDeleteChallengeBody("{}"), {});
  for (const body of ["null", "[]", '{"extra":true}', "not-json"]) {
    await expectServiceError(
      () => Promise.resolve(parseDeleteChallengeBody(body)),
      "INVALID_REQUEST",
    );
  }

  assertEquals(parseAccountDeletionBody(deletionBody()), {
    challenge_id: CHALLENGE_ID,
    google_id_token: "signed.google.id-token",
  });
  for (
    const body of [
      "{}",
      "[]",
      "not-json",
      JSON.stringify({
        challenge_id: "not-a-uuid",
        google_id_token: "token",
      }),
      JSON.stringify({
        challenge_id: CHALLENGE_ID,
        google_id_token: "token",
        extra: true,
      }),
      " ".repeat(MAX_ACCOUNT_DELETE_BODY_BYTES + 1),
    ]
  ) {
    await expectServiceError(
      () => Promise.resolve(parseAccountDeletionBody(body)),
      "INVALID_REQUEST",
    );
  }
});

Deno.test("challenge nonce is stable for an idempotent request and DB receives only its hash", async () => {
  const gateway = new FakeGateway();
  const deriver = createServiceCredentialNonceDeriver("s".repeat(64));
  const service = new AccountDeletionService(
    gateway,
    new CountingVerifier(),
    deriver,
  );

  const first = await service.createChallenge({
    user: { id: OWNER_ID },
    requestId: REQUEST_ID,
  });
  const second = await service.createChallenge({
    user: { id: OWNER_ID },
    requestId: REQUEST_ID,
  });
  const differentRequestNonce = await deriver.derive(
    OWNER_ID,
    SECOND_REQUEST_ID,
  );

  assertEquals(first, second);
  assertEquals(first.challenge_id, CHALLENGE_ID);
  assertEquals(first.nonce.length, 43);
  assertNotEquals(first.nonce, differentRequestNonce);
  assertEquals(gateway.createCalls.length, 2);
  assertEquals(gateway.createCalls[0], gateway.createCalls[1]);
  assertEquals(gateway.createCalls[0].nonceHash.length, 64);
  assertNotEquals(gateway.createCalls[0].nonceHash, first.nonce);
});

Deno.test("new deletion verifies nonce and linked Google subject before atomic accept", async () => {
  const gateway = new FakeGateway();
  const verifier = new CountingVerifier();
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );
  const result = await service.deleteAccount({
    user: verifiedUser(),
    requestId: REQUEST_ID,
    rawBody: deletionBody(),
  });

  assertEquals(result, { state: "deleting" });
  assertEquals(verifier.tokens, ["signed.google.id-token"]);
  assertEquals(gateway.replayCalls.length, 1);
  assertEquals(gateway.bindingCalls, [{
    ownerId: OWNER_ID,
    challengeId: CHALLENGE_ID,
    nonceHash: NONCE_HASH,
  }]);
  assertEquals(gateway.acceptCalls.length, 1);
  assertEquals(gateway.acceptCalls[0], {
    ...gateway.replayCalls[0],
    challengeId: CHALLENGE_ID,
  });
  assertEquals(
    Object.keys(gateway.acceptCalls[0]).sort(),
    ["challengeId", "ownerId", "requestHash", "requestId"],
  );
});

Deno.test("nonce mismatch rejects proof without consuming the challenge", async () => {
  const gateway = new FakeGateway();
  const verifier = new CountingVerifier({
    subject: GOOGLE_SUBJECT,
    nonce: "b".repeat(43),
  });
  gateway.bindingResult = {
    data: null,
    error: { code: "P0001", message: "DELETE_CHALLENGE_INVALID" },
  };
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );

  await expectServiceError(
    () =>
      service.deleteAccount({
        user: verifiedUser(),
        requestId: REQUEST_ID,
        rawBody: deletionBody(),
      }),
    "DELETE_CHALLENGE_INVALID",
  );
  assertEquals(gateway.acceptCalls.length, 0);
  assertEquals(gateway.bindingCalls, [{
    ownerId: OWNER_ID,
    challengeId: CHALLENGE_ID,
    nonceHash:
      "f01b57226261ae3756b93936e1c4e6b849f9a5b0933fa3b91843bf4195b9d964",
  }]);
});

Deno.test("Google JWKS unavailability is retryable and does not consume the challenge", async () => {
  const gateway = new FakeGateway();
  const service = new AccountDeletionService(
    gateway,
    {
      verify: () =>
        Promise.reject(new GoogleIdTokenVerificationUnavailableError()),
    },
    fixedNonceDeriver,
  );

  const error = await expectServiceError(
    () =>
      service.deleteAccount({
        user: verifiedUser(),
        requestId: REQUEST_ID,
        rawBody: deletionBody(),
      }),
    "DEPENDENCY_UNAVAILABLE",
  );
  assertEquals(error.status, 503);
  assertEquals(error.retryable, true);
  assertEquals(gateway.bindingCalls.length, 0);
  assertEquals(gateway.acceptCalls.length, 0);
});

Deno.test("Google subject must match provider_id or identity_data.sub, never email", async () => {
  const gateway = new FakeGateway();
  const service = new AccountDeletionService(
    gateway,
    new CountingVerifier(),
    fixedNonceDeriver,
  );

  await expectServiceError(
    () =>
      service.deleteAccount({
        user: verifiedUser("different-google-subject"),
        requestId: REQUEST_ID,
        rawBody: deletionBody(),
      }),
    "GOOGLE_IDENTITY_MISMATCH",
  );
  assertEquals(gateway.acceptCalls.length, 0);
});

Deno.test("linked Google identity_data.sub is accepted when provider_id is absent", async () => {
  const gateway = new FakeGateway();
  const service = new AccountDeletionService(
    gateway,
    new CountingVerifier(),
    fixedNonceDeriver,
  );

  assertEquals(
    await service.deleteAccount({
      user: {
        id: OWNER_ID,
        identities: [{
          provider: "google",
          identity_data: { sub: GOOGLE_SUBJECT },
        }],
      },
      requestId: REQUEST_ID,
      rawBody: deletionBody(),
    }),
    { state: "deleting" },
  );
  assertEquals(gateway.acceptCalls.length, 1);
});

Deno.test("account without a linked Google provider is denied with no email bypass", async () => {
  const gateway = new FakeGateway();
  const verifier = new CountingVerifier();
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );

  await expectServiceError(
    () =>
      service.deleteAccount({
        user: {
          id: OWNER_ID,
          identities: [{
            provider: "email",
            provider_id: GOOGLE_SUBJECT,
            identity_data: {
              sub: GOOGLE_SUBJECT,
              email: "same-address@example.com",
            },
          }, {
            provider: "google",
            identity_data: { email: "same-address@example.com" },
          }],
        },
        requestId: REQUEST_ID,
        rawBody: deletionBody(),
      }),
    "GOOGLE_IDENTITY_REQUIRED",
  );
  assertEquals(verifier.tokens.length, 0);
  assertEquals(gateway.acceptCalls.length, 0);
});

Deno.test("exact accepted replay returns before verifier even after proof expiry", async () => {
  const gateway = new FakeGateway();
  gateway.replayResults = [
    { data: null, error: null },
    { data: { http_status: 202, state: "deleting" }, error: null },
  ];
  const verifier = new CountingVerifier();
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );
  const input = {
    user: verifiedUser(),
    requestId: REQUEST_ID,
    rawBody: deletionBody("token-that-may-now-be-expired"),
  };

  assertEquals(await service.deleteAccount(input), {
    state: "deleting",
  });
  assertEquals(await service.deleteAccount(input), {
    state: "deleting",
  });
  assertEquals(verifier.tokens.length, 1);
  assertEquals(gateway.bindingCalls.length, 1);
  assertEquals(gateway.acceptCalls.length, 1);
});

Deno.test("same request ID with a changed token is an idempotency mismatch before verification", async () => {
  const gateway = new FakeGateway();
  gateway.replayResults = [
    { data: null, error: null },
    {
      data: null,
      error: { code: "P0001", message: "IDEMPOTENCY_MISMATCH" },
    },
  ];
  const verifier = new CountingVerifier();
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );

  await service.deleteAccount({
    user: verifiedUser(),
    requestId: REQUEST_ID,
    rawBody: deletionBody("first-token"),
  });
  const error = await expectServiceError(
    () =>
      service.deleteAccount({
        user: verifiedUser(),
        requestId: REQUEST_ID,
        rawBody: deletionBody("changed-token"),
      }),
    "IDEMPOTENCY_MISMATCH",
  );

  assertEquals(error.status, 409);
  assertEquals(verifier.tokens, ["first-token"]);
  assertNotEquals(
    gateway.replayCalls[0].requestHash,
    gateway.replayCalls[1].requestHash,
  );
  assertEquals(gateway.bindingCalls.length, 1);
  assertEquals(gateway.acceptCalls.length, 1);
});

Deno.test("one-use challenge enforcement is delegated to atomic SQL accept", async () => {
  const gateway = new FakeGateway();
  gateway.acceptResult = {
    data: null,
    error: { code: "P0001", message: "DELETE_CHALLENGE_INVALID" },
  };
  const verifier = new CountingVerifier();
  const service = new AccountDeletionService(
    gateway,
    verifier,
    fixedNonceDeriver,
  );

  const error = await expectServiceError(
    () =>
      service.deleteAccount({
        user: verifiedUser(),
        requestId: REQUEST_ID,
        rawBody: deletionBody(),
      }),
    "DELETE_CHALLENGE_INVALID",
  );
  assertEquals(error.status, 409);
  assertEquals(verifier.tokens.length, 1);
  assertEquals(gateway.acceptCalls.length, 1);
});

Deno.test("canonical deletion hash binds both challenge and raw token value", async () => {
  const first = await hashAccountDeletionBody(
    parseAccountDeletionBody(deletionBody("first-token")),
  );
  const sameWithDifferentWhitespace = await hashAccountDeletionBody(
    parseAccountDeletionBody(
      `{ "google_id_token": "first-token", "challenge_id": "${CHALLENGE_ID}" }`,
    ),
  );
  const changedToken = await hashAccountDeletionBody(
    parseAccountDeletionBody(deletionBody("changed-token")),
  );

  assertEquals(first, sameWithDifferentWhitespace);
  assertEquals(first.length, 64);
  assertNotEquals(first, changedToken);
});
