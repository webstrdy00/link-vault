import {
  InvalidItemUrlError,
  type ItemCategoryReference,
  type ItemPreparationInput,
  type ItemUpdateInput,
  type ItemUpdateSnapshot,
  type PreparedItem,
  type PreparedItemUpdate,
  prepareItem,
  prepareItemUpdate,
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

export interface CategoryLookupCall {
  ownerId: string;
  categoryIds: string[];
}

export interface UpdateItemCall {
  ownerId: string;
  itemId: string;
  requestId: string;
  body: ItemUpdateInput;
  prepared: PreparedItemUpdate | null;
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
  lookupCategories(call: CategoryLookupCall): Promise<RpcResult>;
  updateItem(call: UpdateItemCall): Promise<RpcResult>;
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
      if (method === "GET") {
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
      }

      requireValidRequestId(hasValidIncomingRequestId);
      const rawBody = await readJson(request, MAX_ITEM_BODY_BYTES);
      const body = validateItemUpdateBody(rawBody);
      const snapshotResult = await callCallerRpc(gateway, {
        authorization,
        functionName: "library_get_item",
        args: { p_item_id: route.itemId },
      });
      let prepared: PreparedItemUpdate | null = null;
      if (snapshotResult.error !== null) {
        if (!isRpcGuard(snapshotResult.error, "ITEM_NOT_FOUND")) {
          throw mapRpcFailure(snapshotResult.error);
        }
      } else {
        if (!isItemUpdateSnapshot(snapshotResult.data)) {
          throw dependencyUnavailableError();
        }
        const selectedCategories = await categoriesForUpdate(
          gateway,
          verification.userId,
          snapshotResult.data,
          body,
        );
        try {
          prepared = await prepareItemUpdate(
            snapshotResult.data,
            body,
            selectedCategories,
          );
        } catch {
          throw dependencyUnavailableError();
        }
      }

      let updateResult: RpcResult;
      try {
        updateResult = await gateway.updateItem({
          ownerId: verification.userId,
          itemId: route.itemId,
          requestId: incomingRequestId!,
          body,
          prepared,
        });
      } catch {
        throw dependencyUnavailableError();
      }
      if (updateResult.error !== null) {
        throw mapRpcFailure(updateResult.error);
      }
      if (isVersionConflictUpdateResult(updateResult.data)) {
        throw mapRpcFailure({
          code: "P0001",
          message: updateResult.data.error_code,
        });
      }
      if (!isUpdateResult(updateResult.data)) {
        throw dependencyUnavailableError();
      }
      return jsonResponse(updateResult.data.item, 200, requestId);
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
      return ["GET"];
    case "itemDetail":
      return ["GET", "PATCH"];
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

  validateCategoryIds(value);

  return value as unknown as ItemPreparationInput;
}

function validateItemUpdateBody(value: unknown): ItemUpdateInput {
  if (!isObject(value)) {
    throw invalidItemBodyError("Request body must be a JSON object.");
  }
  const allowedKeys = new Set([
    "expected_version",
    "title",
    "note",
    "category_ids",
  ]);
  if (Object.keys(value).some((key) => !allowedKeys.has(key))) {
    throw invalidItemBodyError("Request body contains an unknown field.");
  }
  if (
    !Number.isInteger(value.expected_version) ||
    (value.expected_version as number) < 1 ||
    (value.expected_version as number) > MAX_POSTGRES_INTEGER
  ) {
    throw invalidItemBodyError("expected_version must be a positive integer.");
  }
  if (
    !Object.hasOwn(value, "title") &&
    !Object.hasOwn(value, "note") &&
    !Object.hasOwn(value, "category_ids")
  ) {
    throw invalidItemBodyError(
      "At least one of title, note, or category_ids is required.",
    );
  }

  validateOptionalNullableString(value, "title", MAX_TITLE_CHARACTERS);
  validateOptionalNullableString(value, "note", MAX_TEXT_CHARACTERS);
  validateCategoryIds(value);
  return value as unknown as ItemUpdateInput;
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

function validateOptionalNullableString(
  value: Record<string, unknown>,
  field: "title" | "note",
  maxCharacters: number,
): void {
  if (!Object.hasOwn(value, field) || value[field] === null) {
    return;
  }
  const fieldValue = value[field];
  if (
    typeof fieldValue !== "string" ||
    codePointLength(fieldValue) > maxCharacters
  ) {
    throw invalidItemBodyError(
      `${field} must be null or a string of at most ${maxCharacters} characters.`,
    );
  }
}

function validateCategoryIds(value: Record<string, unknown>): void {
  if (!Object.hasOwn(value, "category_ids")) {
    return;
  }
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

async function categoriesForUpdate(
  gateway: MemberGateway,
  ownerId: string,
  snapshot: ItemUpdateSnapshot,
  body: ItemUpdateInput,
): Promise<ItemCategoryReference[]> {
  if (!Object.hasOwn(body, "category_ids")) {
    return snapshot.category_refs;
  }
  if (body.category_ids!.length === 0) {
    return [];
  }

  let result: RpcResult;
  try {
    result = await gateway.lookupCategories({
      ownerId,
      categoryIds: body.category_ids!,
    });
  } catch {
    throw dependencyUnavailableError();
  }
  if (result.error !== null || !Array.isArray(result.data)) {
    throw dependencyUnavailableError();
  }

  const byId = new Map<string, ItemCategoryReference>();
  for (const value of result.data) {
    if (
      !isObject(value) || typeof value.id !== "string" ||
      !isUuid(value.id) || typeof value.name !== "string"
    ) {
      throw dependencyUnavailableError();
    }
    const id = value.id.toLowerCase();
    if (byId.has(id)) {
      throw dependencyUnavailableError();
    }
    byId.set(id, { id: value.id, name: value.name });
  }

  const categories: ItemCategoryReference[] = [];
  for (const categoryId of body.category_ids!) {
    const category = byId.get(categoryId.toLowerCase());
    if (category === undefined) {
      throw new ApiError(
        400,
        "INVALID_CATEGORY_IDS",
        "category_ids contains an invalid category.",
      );
    }
    categories.push(category);
  }
  if (categories.length !== byId.size) {
    throw dependencyUnavailableError();
  }
  return categories;
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
      case "VERSION_CONFLICT":
        return new ApiError(
          409,
          "VERSION_CONFLICT",
          "The item changed before this update was applied.",
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

function isRpcGuard(error: RpcFailure, message: string): boolean {
  return error.code === "P0001" && error.message === message;
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

function isUpdateResult(
  value: unknown,
): value is { http_status: 200; item: object } {
  return isObject(value) && value.http_status === 200 && isObject(value.item);
}

function isVersionConflictUpdateResult(
  value: unknown,
): value is { http_status: 409; error_code: "VERSION_CONFLICT" } {
  return isObject(value) &&
    Object.keys(value).length === 2 &&
    Object.hasOwn(value, "http_status") &&
    value.http_status === 409 &&
    Object.hasOwn(value, "error_code") &&
    value.error_code === "VERSION_CONFLICT";
}

function isItemUpdateSnapshot(value: unknown): value is ItemUpdateSnapshot {
  if (
    !isObject(value) || !Number.isInteger(value.version) ||
    (value.version as number) < 1 ||
    (value.version as number) > MAX_POSTGRES_INTEGER ||
    typeof value.url !== "string" ||
    !isNullableString(value.user_title) ||
    !isNullableString(value.fetched_title) ||
    !isNullableString(value.shared_text) ||
    !isNullableString(value.description) ||
    !isNullableString(value.body_text) ||
    !isNullableString(value.note) ||
    !Array.isArray(value.category_refs) ||
    ![
      "queued",
      "running",
      "ready",
      "partial",
      "unsupported",
      "failed",
    ].includes(value.metadata_state as string) ||
    !["not_requested", "queued", "running", "ready", "failed"].includes(
      value.ocr_state as string,
    ) ||
    !isObject(value.extraction_meta) ||
    !isActiveAsset(value.active_asset)
  ) {
    return false;
  }
  return value.category_refs.every((category) =>
    isObject(category) && typeof category.id === "string" &&
    isUuid(category.id) && typeof category.name === "string"
  );
}

function isActiveAsset(
  value: unknown,
): value is ItemUpdateSnapshot["active_asset"] {
  if (value === null) {
    return true;
  }
  if (!isObject(value)) {
    return false;
  }
  return (
    !Object.hasOwn(value, "ocr_text") || isNullableString(value.ocr_text)
  ) &&
    (
      !Object.hasOwn(value, "ocr_truncated") ||
      typeof value.ocr_truncated === "boolean"
    );
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
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
