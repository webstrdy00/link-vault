import {
  InvalidItemUrlError,
  type ItemPreparationInput,
  type PreparedItem,
  prepareItem,
} from "./item-preparation.ts";

const MAX_BOOTSTRAP_BODY_BYTES = 8 * 1024;
const MAX_ITEM_BODY_BYTES = 64 * 1024;
const MAX_URL_CHARACTERS = 4096;
const MAX_TITLE_CHARACTERS = 300;
const MAX_TEXT_CHARACTERS = 4000;
const MAX_CATEGORY_IDS = 5;
const MAX_LIST_LIMIT = 50;
const MAX_POSTGRES_INTEGER = 2_147_483_647;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HOST_FUNCTIONS_PREFIX = "/functions/v1";
const API_PREFIX = "/library-api/v1";

export type AuthVerification =
  | { status: "verified"; userId: string }
  | { status: "invalid" }
  | { status: "unavailable" };

export type RpcFunctionName =
  | "member_bootstrap"
  | "member_me"
  | "library_list_items"
  | "library_get_item";

export interface RpcCall {
  authorization: string;
  functionName: RpcFunctionName;
  args: Record<string, unknown>;
}

export interface CreateItemCall {
  ownerId: string;
  requestId: string;
  body: ItemPreparationInput;
  prepared: PreparedItem;
}

export interface RpcFailure {
  code?: string;
  message?: string;
}

export interface RpcResult {
  data: unknown;
  error: RpcFailure | null;
}

export interface MemberGateway {
  verifyUser(accessToken: string): Promise<AuthVerification>;
  rpc(call: RpcCall): Promise<RpcResult>;
  createItem(call: CreateItemCall): Promise<RpcResult>;
}

type Route =
  | { kind: "health" | "bootstrap" | "me" }
  | { kind: "items" }
  | { kind: "itemDetail"; itemId: string };

class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
  ) {
    super(message);
  }
}

export function createHandler(
  gateway: MemberGateway,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    const incomingRequestId = request.headers.get("x-request-id");
    const hasValidIncomingRequestId = incomingRequestId !== null &&
      isUuid(incomingRequestId);
    const requestId = hasValidIncomingRequestId
      ? incomingRequestId
      : crypto.randomUUID();

    try {
      const requestUrl = new URL(request.url);
      const route = matchRoute(requestUrl.pathname);
      if (route === null) {
        throw new ApiError(404, "NOT_FOUND", "Route not found.");
      }

      const method = request.method.toUpperCase();
      const allowedMethods = methodsFor(route);
      if (!allowedMethods.includes(method)) {
        return errorResponse(
          new ApiError(405, "METHOD_NOT_ALLOWED", "Method not allowed."),
          requestId,
          { allow: allowedMethods.join(", ") },
        );
      }

      if (route.kind === "health") {
        requireNoQuery(requestUrl);
        return jsonResponse({ status: "ok" });
      }

      const authorization = request.headers.get("authorization");
      const accessToken = readBearerToken(authorization);
      if (accessToken === null || authorization === null) {
        throw unauthenticatedError();
      }

      let verification: AuthVerification;
      try {
        verification = await gateway.verifyUser(accessToken);
      } catch {
        throw dependencyUnavailableError();
      }

      if (verification.status === "invalid") {
        throw unauthenticatedError();
      }
      if (verification.status !== "verified") {
        throw dependencyUnavailableError();
      }

      if (route.kind === "bootstrap") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        await readStrictEmptyJsonObject(request);
        return await callerRpcResponse(gateway, {
          authorization,
          functionName: "member_bootstrap",
          args: { p_request_id: incomingRequestId },
        }, requestId);
      }

      if (route.kind === "me") {
        requireNoQuery(requestUrl);
        return await callerRpcResponse(gateway, {
          authorization,
          functionName: "member_me",
          args: {},
        }, requestId);
      }

      if (route.kind === "items" && method === "GET") {
        const { limit, offset } = readPagination(requestUrl);
        const rpcResult = await callCallerRpc(gateway, {
          authorization,
          functionName: "library_list_items",
          args: { p_limit: limit, p_offset: offset },
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (!isListResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        return jsonResponse(rpcResult.data, 200, requestId);
      }

      if (route.kind === "items") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const rawBody = await readJson(request, MAX_ITEM_BODY_BYTES);
        const body = validateItemBody(rawBody);
        let prepared: PreparedItem;
        try {
          prepared = await prepareItem(body);
        } catch (error) {
          if (error instanceof InvalidItemUrlError) {
            throw invalidItemBodyError(error.message);
          }
          throw error;
        }

        let rpcResult: RpcResult;
        try {
          rpcResult = await gateway.createItem({
            ownerId: verification.userId,
            requestId: incomingRequestId!,
            body,
            prepared,
          });
        } catch {
          throw dependencyUnavailableError();
        }
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (!isCreateResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        return jsonResponse(
          { duplicate: rpcResult.data.duplicate, item: rpcResult.data.item },
          rpcResult.data.http_status,
          requestId,
        );
      }

      if (route.kind !== "itemDetail") {
        throw new ApiError(
          500,
          "INTERNAL_ERROR",
          "An internal error occurred.",
        );
      }
      requireNoQuery(requestUrl);
      if (!isUuid(route.itemId)) {
        throw new ApiError(
          400,
          "INVALID_BODY",
          "Item id must be a UUID.",
        );
      }
      const rpcResult = await callCallerRpc(gateway, {
        authorization,
        functionName: "library_get_item",
        args: { p_item_id: route.itemId },
      });
      if (rpcResult.error !== null) {
        throw mapRpcFailure(rpcResult.error);
      }
      if (!isObject(rpcResult.data)) {
        throw dependencyUnavailableError();
      }
      return jsonResponse(rpcResult.data, 200, requestId);
    } catch (error) {
      const apiError = error instanceof ApiError
        ? error
        : new ApiError(500, "INTERNAL_ERROR", "An internal error occurred.");
      return errorResponse(apiError, requestId);
    }
  };
}

function matchRoute(pathname: string): Route | null {
  const applicationPath = pathname.startsWith(`${HOST_FUNCTIONS_PREFIX}/`)
    ? pathname.slice(HOST_FUNCTIONS_PREFIX.length)
    : pathname;

  switch (applicationPath) {
    case `${API_PREFIX}/health`:
      return { kind: "health" };
    case `${API_PREFIX}/bootstrap`:
      return { kind: "bootstrap" };
    case `${API_PREFIX}/me`:
      return { kind: "me" };
    case `${API_PREFIX}/items`:
      return { kind: "items" };
  }

  const detailPrefix = `${API_PREFIX}/items/`;
  if (applicationPath.startsWith(detailPrefix)) {
    const itemId = applicationPath.slice(detailPrefix.length);
    if (itemId.length > 0 && !itemId.includes("/")) {
      return { kind: "itemDetail", itemId };
    }
  }
  return null;
}

function methodsFor(route: Route): string[] {
  switch (route.kind) {
    case "health":
    case "me":
    case "itemDetail":
      return ["GET"];
    case "bootstrap":
      return ["POST"];
    case "items":
      return ["GET", "POST"];
  }
}

function readBearerToken(authorization: string | null): string | null {
  if (authorization === null) {
    return null;
  }
  const match = /^Bearer ([^\s]+)$/i.exec(authorization);
  return match?.[1] ?? null;
}

async function readStrictEmptyJsonObject(request: Request): Promise<void> {
  const parsed = await readJson(request, MAX_BOOTSTRAP_BODY_BYTES);
  if (!isObject(parsed) || Object.keys(parsed).length !== 0) {
    throw invalidItemBodyError("Request body must be an empty JSON object.");
  }
}

async function readJson(request: Request, maxBytes: number): Promise<unknown> {
  const contentType = request.headers.get("content-type");
  const mediaType = contentType?.split(";", 1)[0].trim().toLowerCase();
  if (mediaType !== "application/json") {
    throw new ApiError(
      415,
      "UNSUPPORTED_MEDIA_TYPE",
      "Content-Type must be application/json.",
    );
  }

  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength.trim())) {
      throw new ApiError(
        400,
        "INVALID_CONTENT_LENGTH",
        "Content-Length is invalid.",
      );
    }
    if (Number(contentLength) > maxBytes) {
      throw payloadTooLargeError(maxBytes);
    }
  }

  const body = request.body;
  if (body === null) {
    throw invalidJsonError();
  }

  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let byteCount = 0;
  let bodyText = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      byteCount += value.byteLength;
      if (byteCount > maxBytes) {
        try {
          await reader.cancel();
        } catch {
          // The size violation takes precedence over transport cleanup errors.
        }
        throw payloadTooLargeError(maxBytes);
      }
      bodyText += decoder.decode(value, { stream: true });
    }
    bodyText += decoder.decode();
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    throw invalidJsonError();
  } finally {
    reader.releaseLock();
  }

  try {
    return JSON.parse(bodyText);
  } catch {
    throw invalidJsonError();
  }
}

function validateItemBody(value: unknown): ItemPreparationInput {
  if (!isObject(value)) {
    throw invalidItemBodyError("Request body must be a JSON object.");
  }
  const allowedKeys = new Set([
    "url",
    "shared_text",
    "title",
    "note",
    "category_ids",
  ]);
  if (Object.keys(value).some((key) => !allowedKeys.has(key))) {
    throw invalidItemBodyError("Request body contains an unknown field.");
  }

  if (
    typeof value.url !== "string" || codePointLength(value.url) === 0 ||
    codePointLength(value.url) > MAX_URL_CHARACTERS
  ) {
    throw invalidItemBodyError(
      `url must be a string of at most ${MAX_URL_CHARACTERS} characters.`,
    );
  }
  validateOptionalString(value, "title", MAX_TITLE_CHARACTERS);
  validateOptionalString(value, "shared_text", MAX_TEXT_CHARACTERS);
  validateOptionalString(value, "note", MAX_TEXT_CHARACTERS);

  if (Object.hasOwn(value, "category_ids")) {
    if (!Array.isArray(value.category_ids)) {
      throw invalidItemBodyError("category_ids must be an array.");
    }
    if (value.category_ids.length > MAX_CATEGORY_IDS) {
      throw invalidItemBodyError(
        `category_ids must contain at most ${MAX_CATEGORY_IDS} values.`,
      );
    }
    const seen = new Set<string>();
    for (const categoryId of value.category_ids) {
      if (typeof categoryId !== "string" || !isUuid(categoryId)) {
        throw invalidItemBodyError("category_ids must contain only UUIDs.");
      }
      const comparableId = categoryId.toLowerCase();
      if (seen.has(comparableId)) {
        throw invalidItemBodyError("category_ids must not contain duplicates.");
      }
      seen.add(comparableId);
    }
  }

  return value as unknown as ItemPreparationInput;
}

function validateOptionalString(
  value: Record<string, unknown>,
  field: "title" | "shared_text" | "note",
  maxCharacters: number,
): void {
  if (!Object.hasOwn(value, field)) {
    return;
  }
  const fieldValue = value[field];
  if (
    typeof fieldValue !== "string" ||
    codePointLength(fieldValue) > maxCharacters
  ) {
    throw invalidItemBodyError(
      `${field} must be a string of at most ${maxCharacters} characters.`,
    );
  }
}

function readPagination(url: URL): { limit: number; offset: number } {
  const allowedKeys = new Set(["limit", "offset"]);
  for (const key of url.searchParams.keys()) {
    if (!allowedKeys.has(key)) {
      throw new ApiError(
        400,
        "INVALID_QUERY",
        `Unknown query parameter: ${key}.`,
      );
    }
    if (url.searchParams.getAll(key).length !== 1) {
      throw new ApiError(
        400,
        "INVALID_QUERY",
        `Query parameter ${key} must be supplied once.`,
      );
    }
  }

  const limit = readIntegerParameter(url, "limit", 20);
  const offset = readIntegerParameter(url, "offset", 0);
  if (limit < 1 || limit > MAX_LIST_LIMIT || offset < 0) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      `limit must be between 1 and ${MAX_LIST_LIMIT}; offset must be non-negative.`,
    );
  }
  return { limit, offset };
}

function readIntegerParameter(
  url: URL,
  name: "limit" | "offset",
  defaultValue: number,
): number {
  const raw = url.searchParams.get(name);
  if (raw === null) {
    return defaultValue;
  }
  if (!/^(0|[1-9]\d*)$/.test(raw)) {
    throw new ApiError(400, "INVALID_QUERY", `${name} must be an integer.`);
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value > MAX_POSTGRES_INTEGER) {
    throw new ApiError(400, "INVALID_QUERY", `${name} is out of range.`);
  }
  return value;
}

function requireNoQuery(url: URL): void {
  const firstKey = url.searchParams.keys().next();
  if (!firstKey.done) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      `Unknown query parameter: ${firstKey.value}.`,
    );
  }
}

function requireValidRequestId(valid: boolean): void {
  if (!valid) {
    throw new ApiError(
      400,
      "INVALID_REQUEST_ID",
      "X-Request-Id must be a UUID.",
    );
  }
}

async function callerRpcResponse(
  gateway: MemberGateway,
  call: RpcCall,
  requestId: string,
): Promise<Response> {
  const rpcResult = await callCallerRpc(gateway, call);
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  if (!isObject(rpcResult.data)) {
    throw dependencyUnavailableError();
  }
  return jsonResponse(rpcResult.data, 200, requestId);
}

async function callCallerRpc(
  gateway: MemberGateway,
  call: RpcCall,
): Promise<RpcResult> {
  try {
    return await gateway.rpc(call);
  } catch {
    throw dependencyUnavailableError();
  }
}

function mapRpcFailure(error: RpcFailure): ApiError {
  if (error.code === "P0001") {
    switch (error.message) {
      case "UNAUTHENTICATED":
        return unauthenticatedError();
      case "BETA_ACCESS_REQUIRED":
        return new ApiError(
          403,
          "BETA_ACCESS_REQUIRED",
          "Beta access is required.",
        );
      case "ACCOUNT_DELETING":
        return new ApiError(
          403,
          "ACCOUNT_DELETING",
          "Account deletion is in progress.",
        );
      case "ITEM_NOT_FOUND":
        return new ApiError(404, "ITEM_NOT_FOUND", "Item was not found.");
      case "INVALID_REQUEST_ID":
        return new ApiError(
          400,
          "INVALID_REQUEST_ID",
          "X-Request-Id must be a UUID.",
        );
      case "INVALID_BODY":
        return invalidItemBodyError("Request body is invalid.");
      case "INVALID_PREPARED":
        return new ApiError(
          400,
          "INVALID_PREPARED",
          "Prepared item data is invalid.",
        );
      case "INVALID_CATEGORY_IDS":
        return new ApiError(
          400,
          "INVALID_CATEGORY_IDS",
          "category_ids contains an invalid category.",
        );
      case "CATEGORY_LIMIT_REACHED":
        return new ApiError(
          400,
          "CATEGORY_LIMIT_REACHED",
          "Too many categories were selected.",
        );
      case "ITEM_LIMIT_REACHED":
        return new ApiError(
          409,
          "ITEM_LIMIT_REACHED",
          "The item limit has been reached.",
        );
      case "IDEMPOTENCY_MISMATCH":
        return new ApiError(
          409,
          "IDEMPOTENCY_MISMATCH",
          "The request id was already used for different content.",
        );
      case "URL_HASH_COLLISION":
        return new ApiError(
          409,
          "URL_HASH_COLLISION",
          "The URL could not be matched safely.",
        );
      case "ITEM_DELETED":
        return new ApiError(410, "ITEM_DELETED", "The item was deleted.");
      case "RATE_LIMITED":
        return new ApiError(
          429,
          "RATE_LIMITED",
          "Too many requests.",
          true,
        );
    }
  }
  return dependencyUnavailableError();
}

function isCreateResult(
  value: unknown,
): value is { http_status: 200 | 201; duplicate: boolean; item: object } {
  return isObject(value) &&
    (value.http_status === 200 || value.http_status === 201) &&
    typeof value.duplicate === "boolean" && isObject(value.item);
}

function isListResult(
  value: unknown,
): value is { items: unknown[]; has_more: boolean } {
  return isObject(value) && Array.isArray(value.items) &&
    typeof value.has_more === "boolean";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function codePointLength(value: string): number {
  return [...value].length;
}

function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value);
}

function unauthenticatedError(): ApiError {
  return new ApiError(
    401,
    "UNAUTHENTICATED",
    "Authentication is required.",
  );
}

function dependencyUnavailableError(): ApiError {
  return new ApiError(
    503,
    "DEPENDENCY_UNAVAILABLE",
    "A required service is temporarily unavailable.",
    true,
  );
}

function invalidJsonError(): ApiError {
  return new ApiError(400, "INVALID_JSON", "Request body must be valid JSON.");
}

function invalidItemBodyError(message: string): ApiError {
  return new ApiError(400, "INVALID_BODY", message);
}

function payloadTooLargeError(maxBytes: number): ApiError {
  return new ApiError(
    413,
    "PAYLOAD_TOO_LARGE",
    `Request body must not exceed ${maxBytes} bytes.`,
  );
}

function errorResponse(
  error: ApiError,
  requestId: string,
  extraHeaders: HeadersInit = {},
): Response {
  const headers = new Headers(extraHeaders);
  if (error.status === 429) {
    headers.set("retry-after", "60");
  }
  return jsonResponse(
    {
      error: {
        code: error.code,
        message: error.message,
        retryable: error.retryable,
      },
      request_id: requestId,
    },
    error.status,
    requestId,
    headers,
  );
}

function jsonResponse(
  body: unknown,
  status = 200,
  requestId?: string,
  extraHeaders: HeadersInit = {},
): Response {
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("cache-control", "no-store");
  if (requestId !== undefined) {
    headers.set("x-request-id", requestId);
  }
  return new Response(JSON.stringify(body), { status, headers });
}
