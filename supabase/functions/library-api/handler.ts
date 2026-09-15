import {
  InvalidItemUrlError,
  type ItemCategoryReference,
  type ItemPreparationInput,
  type ItemUpdateInput,
  type ItemUpdateSnapshot,
  norm,
  type PreparedItem,
  type PreparedItemUpdate,
  prepareItem,
  prepareItemUpdate,
} from "./item-preparation.ts";
import {
  type ClassificationCategory,
  type ClassificationResult,
  type DiscoverySnapshot,
  explainAliases,
  explainClassification,
  prepareSearch,
  QueryLimitError,
} from "./discovery-engine.ts";
import {
  type AssetGateway,
  AssetServiceUnavailableError,
  completeAssetUpload,
  prepareAssetIndex,
  type PreparedAssetIndex,
  readActiveAssetContent,
} from "./asset-service.ts";

const MAX_BOOTSTRAP_BODY_BYTES = 8 * 1024;
const MAX_ITEM_BODY_BYTES = 64 * 1024;
const MAX_DISCOVERY_BODY_BYTES = 8 * 1024;
const MAX_ASSET_OCR_BODY_BYTES = 128 * 1024;
const MAX_URL_CHARACTERS = 4096;
const MAX_TITLE_CHARACTERS = 300;
const MAX_TEXT_CHARACTERS = 4000;
const MAX_CATEGORY_NAME_CHARACTERS = 30;
const MAX_NORMALIZED_CATEGORY_NAME_CHARACTERS = MAX_CATEGORY_NAME_CHARACTERS *
  18;
const MAX_CATEGORY_IDS = 5;
const MAX_LIST_LIMIT = 50;
const MAX_POSTGRES_INTEGER = 2_147_483_647;
const RULES_VERSION = "rules-v2.0.0";
const SOURCES = new Set(["instagram", "threads", "naver_blog", "other"]);
const CREATE_ITEM_COMMITTED_CONFLICTS = new Set([
  "ITEM_LIMIT_REACHED",
  "URL_HASH_COLLISION",
]);
const CREATE_CATEGORY_COMMITTED_CONFLICTS = new Set([
  "CATEGORY_LIMIT_REACHED",
  "CATEGORY_NAME_EXISTS",
]);
const RENAME_CATEGORY_COMMITTED_CONFLICTS = new Set([
  "CATEGORY_NAME_EXISTS",
]);
const VERSION_COMMITTED_CONFLICTS = new Set(["VERSION_CONFLICT"]);
const RESERVE_ASSET_COMMITTED_CONFLICTS = new Set([
  "VERSION_CONFLICT",
  "ASSET_RESERVATION_EXISTS",
  "STORAGE_LIMIT_REACHED",
]);
const COMPLETE_ASSET_COMMITTED_CONFLICTS = new Set([
  "VERSION_CONFLICT",
  "RESERVATION_EXPIRED",
  "ASSET_NOT_RESERVED",
  "ASSET_OBJECT_MISSING",
  "ASSET_PATH_MISMATCH",
  "ASSET_TOO_LARGE",
  "ASSET_SIZE_INVALID",
  "ASSET_MIME_MISMATCH",
  "ASSET_DECODE_FAILED",
  "ASSET_PIXEL_LIMIT_EXCEEDED",
  "ASSET_DIMENSIONS_INVALID",
]);
const DELETE_ASSET_COMMITTED_CONFLICTS = new Set([
  "VERSION_CONFLICT",
  "ASSET_NOT_ACTIVE",
]);
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
  | "library_get_item"
  | "library_list_categories"
  | "library_search_items";

export type ServiceRpcFunctionName =
  | "library_create_category"
  | "library_rename_category"
  | "library_delete_category"
  | "library_cue_dismiss"
  | "library_reclassify_item"
  | "library_retry_metadata";

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

export interface ServiceRpcCall {
  functionName: ServiceRpcFunctionName;
  ownerId: string;
  requestId: string;
  args: Record<string, unknown>;
}

export interface RpcFailure {
  code?: string;
  message?: string;
}

export interface RpcResult {
  data: unknown;
  error: RpcFailure | null;
}

export interface MemberGateway extends AssetGateway {
  verifyUser(accessToken: string): Promise<AuthVerification>;
  rpc(call: RpcCall): Promise<RpcResult>;
  createItem(call: CreateItemCall): Promise<RpcResult>;
  lookupCategories(call: CategoryLookupCall): Promise<RpcResult>;
  updateItem(call: UpdateItemCall): Promise<RpcResult>;
  serviceRpc(call: ServiceRpcCall): Promise<RpcResult>;
}

type Route =
  | { kind: "health" | "bootstrap" | "me" }
  | { kind: "items" }
  | { kind: "itemDetail"; itemId: string }
  | { kind: "itemAssetReserve"; itemId: string }
  | { kind: "itemAsset"; itemId: string; assetId: string }
  | { kind: "itemAssetComplete"; itemId: string; assetId: string }
  | { kind: "itemAssetOcr"; itemId: string; assetId: string }
  | { kind: "itemAssetContent"; itemId: string; assetId: string }
  | { kind: "itemCueDismiss"; itemId: string }
  | { kind: "itemReclassify"; itemId: string }
  | { kind: "itemRetryMetadata"; itemId: string }
  | { kind: "categories" }
  | { kind: "categoryDetail"; categoryId: string };

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
  scheduleClassification?: () => void,
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
        const { plan, filters, limit, offset } = readSearch(requestUrl);
        const rpcResult = await callCallerRpc(gateway, {
          authorization,
          functionName: "library_search_items",
          args: {
            p_plan: plan,
            p_filters: filters,
            p_limit: limit,
            p_offset: offset,
          },
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (!isListResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        return jsonResponse(rpcResult.data, 200, requestId);
      }

      if (route.kind === "items" && method === "POST") {
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
        if (
          isCommittedConflictResult(
            rpcResult.data,
            CREATE_ITEM_COMMITTED_CONFLICTS,
          )
        ) {
          throw mapRpcFailure({
            code: "P0001",
            message: rpcResult.data.error_code,
          });
        }
        if (!isCreateResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        scheduleAfterCommit(scheduleClassification);
        return jsonResponse(
          { duplicate: rpcResult.data.duplicate, item: rpcResult.data.item },
          rpcResult.data.http_status,
          requestId,
        );
      }

      if (route.kind === "categories" && method === "GET") {
        requireNoQuery(requestUrl);
        const rpcResult = await callCallerRpc(gateway, {
          authorization,
          functionName: "library_list_categories",
          args: {},
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (!isCategoryListResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        return jsonResponse(rpcResult.data, 200, requestId);
      }

      if (route.kind === "categories") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateCategoryBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
        );
        const rpcResult = await callServiceRpc(gateway, {
          functionName: "library_create_category",
          ownerId: verification.userId,
          requestId: incomingRequestId!,
          args: {
            p_body: body,
            p_normalized_name: norm(body.name),
          },
        });
        return categoryMutationResponse(rpcResult, 201, requestId);
      }

      if (route.kind === "categoryDetail") {
        requireNoQuery(requestUrl);
        if (!isUuid(route.categoryId)) {
          throw invalidIdentifierError("Category");
        }
        requireValidRequestId(hasValidIncomingRequestId);

        if (method === "PATCH") {
          const body = validateCategoryBody(
            await readJson(request, MAX_DISCOVERY_BODY_BYTES),
          );
          const rpcResult = await callServiceRpc(gateway, {
            functionName: "library_rename_category",
            ownerId: verification.userId,
            requestId: incomingRequestId!,
            args: {
              p_category_id: route.categoryId,
              p_body: body,
              p_normalized_name: norm(body.name),
            },
          });
          return categoryMutationResponse(rpcResult, 200, requestId);
        }

        await readStrictEmptyJsonObject(request);
        const rpcResult = await callServiceRpc(gateway, {
          functionName: "library_delete_category",
          ownerId: verification.userId,
          requestId: incomingRequestId!,
          args: {
            p_category_id: route.categoryId,
            p_body: {},
          },
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (!isDeleteCategoryResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        return emptyResponse(204, requestId);
      }

      if (
        route.kind === "itemDetail" ||
        route.kind === "itemAssetReserve" ||
        route.kind === "itemAsset" ||
        route.kind === "itemAssetComplete" ||
        route.kind === "itemAssetOcr" ||
        route.kind === "itemAssetContent" ||
        route.kind === "itemCueDismiss" ||
        route.kind === "itemReclassify" ||
        route.kind === "itemRetryMetadata"
      ) {
        if (!isUuid(route.itemId)) {
          throw invalidIdentifierError("Item");
        }
      }
      if (
        route.kind === "itemAsset" ||
        route.kind === "itemAssetComplete" ||
        route.kind === "itemAssetOcr" ||
        route.kind === "itemAssetContent"
      ) {
        if (!isUuid(route.assetId)) {
          throw invalidIdentifierError("Asset");
        }
      }

      if (route.kind === "itemCueDismiss") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateVersionBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
          true,
        );
        const rpcResult = await callServiceRpc(gateway, {
          functionName: "library_cue_dismiss",
          ownerId: verification.userId,
          requestId: incomingRequestId!,
          args: {
            p_item_id: route.itemId,
            p_body: body,
          },
        });
        return itemMutationResponse(rpcResult, 200, requestId);
      }

      if (route.kind === "itemReclassify") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateVersionBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
          false,
        );
        const rpcResult = await callServiceRpc(gateway, {
          functionName: "library_reclassify_item",
          ownerId: verification.userId,
          requestId: incomingRequestId!,
          args: {
            p_item_id: route.itemId,
            p_body: body,
          },
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (isVersionConflictUpdateResult(rpcResult.data)) {
          throw mapRpcFailure({
            code: "P0001",
            message: rpcResult.data.error_code,
          });
        }
        if (!isReclassifyResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        scheduleAfterCommit(scheduleClassification);
        return jsonResponse(
          {
            job_id: rpcResult.data.job_id,
            item_id: rpcResult.data.item_id,
          },
          202,
          requestId,
        );
      }

      if (route.kind === "itemRetryMetadata") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateVersionBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
          false,
        );
        const rpcResult = await callServiceRpc(gateway, {
          functionName: "library_retry_metadata",
          ownerId: verification.userId,
          requestId: incomingRequestId!,
          args: {
            p_item_id: route.itemId,
            p_body: body,
          },
        });
        if (rpcResult.error !== null) {
          throw mapRpcFailure(rpcResult.error);
        }
        if (isVersionConflictUpdateResult(rpcResult.data)) {
          throw mapRpcFailure({
            code: "P0001",
            message: rpcResult.data.error_code,
          });
        }
        if (!isRetryMetadataResult(rpcResult.data)) {
          throw dependencyUnavailableError();
        }
        scheduleAfterCommit(scheduleClassification);
        return jsonResponse(
          { job_id: rpcResult.data.job_id },
          202,
          requestId,
        );
      }

      if (route.kind === "itemAssetReserve") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateAssetReserveBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
        );
        const rpcResult = await callAssetRpc(() =>
          gateway.reserveAsset({
            ownerId: verification.userId,
            itemId: route.itemId,
            requestId: incomingRequestId!,
            body,
          })
        );
        if (rpcResult.error === null) {
          scheduleAfterCommit(scheduleClassification);
        }
        return reserveAssetResponse(
          rpcResult,
          verification.userId,
          route.itemId,
          requestId,
        );
      }

      if (route.kind === "itemAssetComplete") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateAssetOcrBody(
          await readJson(request, MAX_ASSET_OCR_BODY_BYTES),
          true,
        );
        const rpcResult = await completeAssetUpload(gateway, {
          ownerId: verification.userId,
          itemId: route.itemId,
          assetId: route.assetId,
          requestId: incomingRequestId!,
          body,
          loadSnapshot: async () => {
            const snapshotResult = await callCallerRpc(gateway, {
              authorization,
              functionName: "library_get_item",
              args: { p_item_id: route.itemId },
            });
            if (
              snapshotResult.error === null &&
              !isItemUpdateSnapshotFor(snapshotResult.data, route.itemId)
            ) {
              throw dependencyUnavailableError();
            }
            return snapshotResult;
          },
        });
        if (rpcResult.error === null) {
          scheduleAfterCommit(scheduleClassification);
        }
        return assetItemMutationResponse(
          rpcResult,
          COMPLETE_ASSET_COMMITTED_CONFLICTS,
          route.itemId,
          requestId,
        );
      }

      if (route.kind === "itemAssetOcr") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateAssetOcrBody(
          await readJson(request, MAX_ASSET_OCR_BODY_BYTES),
          false,
        );
        const snapshot = await loadItemUpdateSnapshot(
          gateway,
          authorization,
          route.itemId,
        );
        let preparedIndex: PreparedAssetIndex | null = null;
        if (snapshot !== null) {
          try {
            preparedIndex = prepareAssetIndex(snapshot, {
              ocr_state: body.ocr_state,
              ocr_text: body.ocr_state === "ready" ? body.ocr_text : null,
              ocr_truncated: body.ocr_truncated === true,
            });
          } catch {
            throw dependencyUnavailableError();
          }
        }
        const rpcResult = await callAssetRpc(() =>
          gateway.updateAssetOcr({
            ownerId: verification.userId,
            itemId: route.itemId,
            assetId: route.assetId,
            requestId: incomingRequestId!,
            body,
            preparedIndex,
          })
        );
        if (rpcResult.error === null) {
          scheduleAfterCommit(scheduleClassification);
        }
        return assetItemMutationResponse(
          rpcResult,
          DELETE_ASSET_COMMITTED_CONFLICTS,
          route.itemId,
          requestId,
        );
      }

      if (route.kind === "itemAsset") {
        requireNoQuery(requestUrl);
        requireValidRequestId(hasValidIncomingRequestId);
        const body = validateVersionBody(
          await readJson(request, MAX_DISCOVERY_BODY_BYTES),
          false,
        );
        const snapshot = await loadItemUpdateSnapshot(
          gateway,
          authorization,
          route.itemId,
        );
        let preparedIndex: PreparedAssetIndex | null = null;
        if (snapshot !== null) {
          try {
            preparedIndex = prepareAssetIndex(snapshot, {
              ocr_state: "not_requested",
              ocr_text: null,
              ocr_truncated: false,
            });
          } catch {
            throw dependencyUnavailableError();
          }
        }
        const rpcResult = await callAssetRpc(() =>
          gateway.deleteAsset({
            ownerId: verification.userId,
            itemId: route.itemId,
            assetId: route.assetId,
            requestId: incomingRequestId!,
            body,
            preparedIndex,
          })
        );
        if (rpcResult.error === null) {
          scheduleAfterCommit(scheduleClassification);
        }
        return deleteAssetResponse(rpcResult, route.assetId, requestId);
      }

      if (route.kind === "itemAssetContent") {
        requireNoQuery(requestUrl);
        const detailResult = await callCallerRpc(gateway, {
          authorization,
          functionName: "library_get_item",
          args: { p_item_id: route.itemId },
        });
        if (detailResult.error !== null) {
          throw mapRpcFailure(detailResult.error);
        }
        if (
          !isObject(detailResult.data) ||
          typeof detailResult.data.id !== "string" ||
          detailResult.data.id.toLowerCase() !== route.itemId.toLowerCase()
        ) {
          throw dependencyUnavailableError();
        }
        const activeAsset = detailResult.data.active_asset;
        if (
          !isObject(activeAsset) || typeof activeAsset.id !== "string" ||
          activeAsset.id.toLowerCase() !== route.assetId.toLowerCase()
        ) {
          throw assetNotFoundError();
        }
        const content = await readActiveAssetContent(gateway, {
          ownerId: verification.userId,
          itemId: route.itemId,
          assetId: route.assetId,
        });
        if (content === null) {
          throw assetNotFoundError();
        }
        return assetContentResponse(
          content.bytes,
          content.mimeType,
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
      if (method === "GET") {
        const detailPlan = readDetailQuery(requestUrl);
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
        return jsonResponse(
          enhanceItemDetail(rpcResult.data, detailPlan),
          200,
          requestId,
        );
      }

      requireNoQuery(requestUrl);
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
      scheduleAfterCommit(scheduleClassification);
      return jsonResponse(updateResult.data.item, 200, requestId);
    } catch (error) {
      const apiError = error instanceof ApiError
        ? error
        : error instanceof AssetServiceUnavailableError
        ? dependencyUnavailableError()
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
    case `${API_PREFIX}/categories`:
      return { kind: "categories" };
  }

  const cueDismissSuffix = "/cue-dismiss";
  const reclassifySuffix = "/reclassify";
  const retryMetadataSuffix = "/retry-metadata";
  const detailPrefix = `${API_PREFIX}/items/`;
  if (applicationPath.startsWith(detailPrefix)) {
    const detailPath = applicationPath.slice(detailPrefix.length);
    const segments = detailPath.split("/");
    if (
      segments.length === 3 && segments[0].length > 0 &&
      segments[1] === "assets" && segments[2] === "reserve"
    ) {
      return { kind: "itemAssetReserve", itemId: segments[0] };
    }
    if (
      segments.length === 4 && segments[0].length > 0 &&
      segments[1] === "assets" && segments[2].length > 0
    ) {
      switch (segments[3]) {
        case "complete":
          return {
            kind: "itemAssetComplete",
            itemId: segments[0],
            assetId: segments[2],
          };
        case "ocr":
          return {
            kind: "itemAssetOcr",
            itemId: segments[0],
            assetId: segments[2],
          };
        case "content":
          return {
            kind: "itemAssetContent",
            itemId: segments[0],
            assetId: segments[2],
          };
      }
    }
    if (
      segments.length === 3 && segments[0].length > 0 &&
      segments[1] === "assets" && segments[2].length > 0
    ) {
      return {
        kind: "itemAsset",
        itemId: segments[0],
        assetId: segments[2],
      };
    }
    if (
      detailPath.endsWith(cueDismissSuffix) &&
      !detailPath.slice(0, -cueDismissSuffix.length).includes("/")
    ) {
      const itemId = detailPath.slice(0, -cueDismissSuffix.length);
      return itemId.length > 0 ? { kind: "itemCueDismiss", itemId } : null;
    }
    if (
      detailPath.endsWith(reclassifySuffix) &&
      !detailPath.slice(0, -reclassifySuffix.length).includes("/")
    ) {
      const itemId = detailPath.slice(0, -reclassifySuffix.length);
      return itemId.length > 0 ? { kind: "itemReclassify", itemId } : null;
    }
    if (
      detailPath.endsWith(retryMetadataSuffix) &&
      !detailPath.slice(0, -retryMetadataSuffix.length).includes("/")
    ) {
      const itemId = detailPath.slice(0, -retryMetadataSuffix.length);
      return itemId.length > 0 ? { kind: "itemRetryMetadata", itemId } : null;
    }
    const itemId = detailPath;
    if (itemId.length > 0 && !itemId.includes("/")) {
      return { kind: "itemDetail", itemId };
    }
  }

  const categoryPrefix = `${API_PREFIX}/categories/`;
  if (applicationPath.startsWith(categoryPrefix)) {
    const categoryId = applicationPath.slice(categoryPrefix.length);
    if (categoryId.length > 0 && !categoryId.includes("/")) {
      return { kind: "categoryDetail", categoryId };
    }
  }
  return null;
}

function methodsFor(route: Route): string[] {
  switch (route.kind) {
    case "health":
    case "me":
    case "itemAssetContent":
      return ["GET"];
    case "itemDetail":
      return ["GET", "PATCH"];
    case "itemAssetReserve":
    case "itemAssetComplete":
    case "itemCueDismiss":
    case "itemReclassify":
    case "itemRetryMetadata":
      return ["POST"];
    case "itemAssetOcr":
      return ["PATCH"];
    case "itemAsset":
      return ["DELETE"];
    case "bootstrap":
      return ["POST"];
    case "items":
    case "categories":
      return ["GET", "POST"];
    case "categoryDetail":
      return ["PATCH", "DELETE"];
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

function validateCategoryBody(value: unknown): { name: string } {
  if (
    !isObject(value) || Object.keys(value).length !== 1 ||
    !Object.hasOwn(value, "name") || typeof value.name !== "string"
  ) {
    throw invalidItemBodyError(
      "Request body must contain only a non-empty name.",
    );
  }
  const length = codePointLength(value.name);
  const normalizedName = norm(value.name);
  if (
    length < 1 || length > MAX_CATEGORY_NAME_CHARACTERS ||
    normalizedName.length === 0
  ) {
    throw invalidItemBodyError(
      `name must contain between 1 and ${MAX_CATEGORY_NAME_CHARACTERS} Unicode characters.`,
    );
  }
  if (
    codePointLength(normalizedName) > MAX_NORMALIZED_CATEGORY_NAME_CHARACTERS
  ) {
    throw invalidItemBodyError(
      "Normalized category name exceeds the supported storage bounds.",
    );
  }
  return { name: value.name };
}

function validateVersionBody(
  value: unknown,
  includeTextRevision: boolean,
): { expected_version: number; text_revision: number } | {
  expected_version: number;
} {
  const allowedKeys = includeTextRevision
    ? new Set(["expected_version", "text_revision"])
    : new Set(["expected_version"]);
  if (
    !isObject(value) ||
    Object.keys(value).length !== allowedKeys.size ||
    Object.keys(value).some((key) => !allowedKeys.has(key)) ||
    !isPositivePostgresInteger(value.expected_version) ||
    (includeTextRevision && !isPositivePostgresInteger(value.text_revision))
  ) {
    throw invalidItemBodyError(
      includeTextRevision
        ? "expected_version and text_revision must be positive integers."
        : "expected_version must be a positive integer.",
    );
  }
  return includeTextRevision
    ? {
      expected_version: value.expected_version as number,
      text_revision: value.text_revision as number,
    }
    : { expected_version: value.expected_version as number };
}

function validateAssetReserveBody(
  value: unknown,
): { expected_version: number; mime_type: string } {
  if (
    !isObject(value) || Object.keys(value).length !== 2 ||
    !Object.hasOwn(value, "expected_version") ||
    !Object.hasOwn(value, "mime_type") ||
    !isPositivePostgresInteger(value.expected_version) ||
    typeof value.mime_type !== "string" ||
    !["image/jpeg", "image/png", "image/webp"].includes(value.mime_type)
  ) {
    throw invalidItemBodyError(
      "expected_version and a supported image mime_type are required.",
    );
  }
  return {
    expected_version: value.expected_version,
    mime_type: value.mime_type,
  };
}

function validateAssetOcrBody(
  value: unknown,
  allowNotRequested: boolean,
): {
  expected_version: number;
  ocr_state: "ready" | "failed" | "not_requested";
  ocr_text?: string | null;
  ocr_truncated?: boolean;
} {
  if (!isObject(value)) {
    throw invalidItemBodyError("Request body must be a JSON object.");
  }
  const allowedKeys = new Set([
    "expected_version",
    "ocr_state",
    "ocr_text",
    "ocr_truncated",
  ]);
  if (
    Object.keys(value).some((key) => !allowedKeys.has(key)) ||
    !Object.hasOwn(value, "expected_version") ||
    !Object.hasOwn(value, "ocr_state") ||
    !isPositivePostgresInteger(value.expected_version) ||
    typeof value.ocr_state !== "string" ||
    !["ready", "failed", ...(allowNotRequested ? ["not_requested"] : [])]
      .includes(value.ocr_state)
  ) {
    throw invalidItemBodyError(
      "expected_version and a supported ocr_state are required.",
    );
  }
  if (
    Object.hasOwn(value, "ocr_truncated") &&
    typeof value.ocr_truncated !== "boolean"
  ) {
    throw invalidItemBodyError("ocr_truncated must be a boolean.");
  }
  if (value.ocr_state === "ready") {
    if (
      !Object.hasOwn(value, "ocr_text") || typeof value.ocr_text !== "string"
    ) {
      throw invalidItemBodyError(
        "ocr_text must be a string when ocr_state is ready.",
      );
    }
  } else if (
    (Object.hasOwn(value, "ocr_text") && value.ocr_text !== null) ||
    (Object.hasOwn(value, "ocr_truncated") && value.ocr_truncated !== false)
  ) {
    throw invalidItemBodyError(
      "ocr_text and ocr_truncated are only supported for ready OCR.",
    );
  }
  return value as {
    expected_version: number;
    ocr_state: "ready" | "failed" | "not_requested";
    ocr_text?: string | null;
    ocr_truncated?: boolean;
  };
}

function isPositivePostgresInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 &&
    (value as number) <= MAX_POSTGRES_INTEGER;
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

function readSearch(url: URL): {
  plan: ReturnType<typeof prepareSearch>;
  filters: Record<string, unknown>;
  limit: number;
  offset: number;
} {
  const allowedKeys = new Set([
    "q",
    "category_id",
    "unclassified",
    "source",
    "date_from",
    "date_to",
    "aliases",
    "needs_cues",
    "limit",
    "offset",
  ]);
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

  const query = url.searchParams.get("q") ?? "";
  let plan: ReturnType<typeof prepareSearch>;
  try {
    plan = prepareSearch(query);
  } catch (error) {
    if (error instanceof QueryLimitError) {
      throw new ApiError(
        400,
        "QUERY_LIMIT",
        "The search query exceeds the supported limits.",
      );
    }
    throw error;
  }

  const categoryId = url.searchParams.get("category_id");
  if (categoryId !== null && !isUuid(categoryId)) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      "category_id must be a UUID.",
    );
  }
  const unclassified = readBooleanParameter(url, "unclassified", false);
  if (categoryId !== null && unclassified) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      "category_id and unclassified=true cannot be combined.",
    );
  }

  const source = url.searchParams.get("source");
  if (source !== null && !SOURCES.has(source)) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      "source is not supported.",
    );
  }

  const dateFrom = readDateParameter(url, "date_from");
  const dateTo = readDateParameter(url, "date_to");
  if (
    dateFrom !== undefined && dateTo !== undefined &&
    Date.parse(dateFrom) >= Date.parse(dateTo)
  ) {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      "date_from must be earlier than date_to.",
    );
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

  const filters: Record<string, unknown> = {
    unclassified,
    aliases: readBooleanParameter(url, "aliases", true),
    needs_cues: readBooleanParameter(url, "needs_cues", false),
  };
  if (categoryId !== null) {
    filters.category_id = categoryId;
  }
  if (source !== null) {
    filters.source = source;
  }
  if (dateFrom !== undefined) {
    filters.date_from = dateFrom;
  }
  if (dateTo !== undefined) {
    filters.date_to = dateTo;
  }

  return { plan, filters, limit, offset };
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

function readBooleanParameter(
  url: URL,
  name: "unclassified" | "aliases" | "needs_cues",
  defaultValue: boolean,
): boolean {
  const raw = url.searchParams.get(name);
  if (raw === null) {
    return defaultValue;
  }
  if (raw !== "true" && raw !== "false") {
    throw new ApiError(
      400,
      "INVALID_QUERY",
      `${name} must be true or false.`,
    );
  }
  return raw === "true";
}

function readDateParameter(
  url: URL,
  name: "date_from" | "date_to",
): string | undefined {
  const raw = url.searchParams.get(name);
  if (raw === null) {
    return undefined;
  }
  const match =
    /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(?:[Zz]|([+-])(\d{2}):(\d{2}))$/
      .exec(raw);
  if (match === null) {
    throw invalidDateQueryError(name);
  }
  const [
    ,
    yearText,
    monthText,
    dayText,
    hourText,
    minuteText,
    secondText,
    ,
    ,
    offsetHourText,
    offsetMinuteText,
  ] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const daysInMonth = [
    31,
    isLeapYear(year) ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ][month - 1] ?? 0;
  if (
    month < 1 || month > 12 || day < 1 || day > daysInMonth ||
    hour > 23 || minute > 59 || second > 59 ||
    (offsetHourText !== undefined && Number(offsetHourText) > 23) ||
    (offsetMinuteText !== undefined && Number(offsetMinuteText) > 59)
  ) {
    throw invalidDateQueryError(name);
  }
  const timestamp = Date.parse(raw);
  if (!Number.isFinite(timestamp)) {
    throw invalidDateQueryError(name);
  }
  return new Date(timestamp).toISOString();
}

function isLeapYear(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function invalidDateQueryError(name: "date_from" | "date_to"): ApiError {
  return new ApiError(
    400,
    "INVALID_QUERY",
    `${name} must be a valid RFC 3339 timestamp.`,
  );
}

function readDetailQuery(url: URL): ReturnType<typeof prepareSearch> | null {
  for (const key of url.searchParams.keys()) {
    if (key !== "q") {
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
        "Query parameter q must be supplied once.",
      );
    }
  }
  if (!url.searchParams.has("q")) {
    return null;
  }
  try {
    return prepareSearch(url.searchParams.get("q")!);
  } catch (error) {
    if (error instanceof QueryLimitError) {
      throw new ApiError(
        400,
        "QUERY_LIMIT",
        "The search query exceeds the supported limits.",
      );
    }
    throw error;
  }
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

async function callServiceRpc(
  gateway: MemberGateway,
  call: ServiceRpcCall,
): Promise<RpcResult> {
  try {
    return await gateway.serviceRpc(call);
  } catch {
    throw dependencyUnavailableError();
  }
}

async function callAssetRpc(
  operation: () => Promise<RpcResult>,
): Promise<RpcResult> {
  try {
    return await operation();
  } catch {
    throw dependencyUnavailableError();
  }
}

async function loadItemUpdateSnapshot(
  gateway: MemberGateway,
  authorization: string,
  itemId: string,
): Promise<ItemUpdateSnapshot | null> {
  const result = await callCallerRpc(gateway, {
    authorization,
    functionName: "library_get_item",
    args: { p_item_id: itemId },
  });
  if (result.error !== null) {
    if (isRpcGuard(result.error, "ITEM_NOT_FOUND")) {
      return null;
    }
    throw mapRpcFailure(result.error);
  }
  if (!isItemUpdateSnapshotFor(result.data, itemId)) {
    throw dependencyUnavailableError();
  }
  return result.data;
}

function reserveAssetResponse(
  rpcResult: RpcResult,
  ownerId: string,
  itemId: string,
  requestId: string,
): Response {
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  if (
    isCommittedConflictResult(
      rpcResult.data,
      RESERVE_ASSET_COMMITTED_CONFLICTS,
    )
  ) {
    throw mapRpcFailure({
      code: "P0001",
      message: rpcResult.data.error_code,
    });
  }
  if (
    !isReserveAssetResult(rpcResult.data) ||
    rpcResult.data.object_path !==
      `${ownerId.toLowerCase()}/${itemId.toLowerCase()}/${rpcResult.data.asset_id.toLowerCase()}`
  ) {
    throw dependencyUnavailableError();
  }
  const {
    asset_id,
    object_path,
    expires_at,
    max_bytes,
  } = rpcResult.data;
  return jsonResponse(
    { asset_id, object_path, expires_at, max_bytes },
    201,
    requestId,
  );
}

function assetItemMutationResponse(
  rpcResult: RpcResult,
  allowedConflicts: ReadonlySet<string>,
  itemId: string,
  requestId: string,
): Response {
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  if (isCommittedConflictResult(rpcResult.data, allowedConflicts)) {
    throw mapRpcFailure({
      code: "P0001",
      message: rpcResult.data.error_code,
    });
  }
  if (
    !isAssetItemResult(rpcResult.data) ||
    rpcResult.data.item.id.toLowerCase() !== itemId.toLowerCase()
  ) {
    throw dependencyUnavailableError();
  }
  return jsonResponse(rpcResult.data.item, 200, requestId);
}

function deleteAssetResponse(
  rpcResult: RpcResult,
  assetId: string,
  requestId: string,
): Response {
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  if (
    isCommittedConflictResult(
      rpcResult.data,
      DELETE_ASSET_COMMITTED_CONFLICTS,
    )
  ) {
    throw mapRpcFailure({
      code: "P0001",
      message: rpcResult.data.error_code,
    });
  }
  if (
    !isDeleteAssetResult(rpcResult.data) ||
    rpcResult.data.asset_id.toLowerCase() !== assetId.toLowerCase()
  ) {
    throw dependencyUnavailableError();
  }
  return jsonResponse(
    { asset_id: rpcResult.data.asset_id },
    202,
    requestId,
  );
}

function categoryMutationResponse(
  rpcResult: RpcResult,
  expectedStatus: 200 | 201,
  requestId: string,
): Response {
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  const allowedConflicts = expectedStatus === 201
    ? CREATE_CATEGORY_COMMITTED_CONFLICTS
    : RENAME_CATEGORY_COMMITTED_CONFLICTS;
  if (isCommittedConflictResult(rpcResult.data, allowedConflicts)) {
    throw mapRpcFailure({
      code: "P0001",
      message: rpcResult.data.error_code,
    });
  }
  if (!isCategoryMutationResult(rpcResult.data, expectedStatus)) {
    throw dependencyUnavailableError();
  }
  return jsonResponse(rpcResult.data.category, expectedStatus, requestId);
}

function itemMutationResponse(
  rpcResult: RpcResult,
  expectedStatus: 200,
  requestId: string,
): Response {
  if (rpcResult.error !== null) {
    throw mapRpcFailure(rpcResult.error);
  }
  if (isVersionConflictUpdateResult(rpcResult.data)) {
    throw mapRpcFailure({
      code: "P0001",
      message: rpcResult.data.error_code,
    });
  }
  if (!isUpdateResult(rpcResult.data)) {
    throw dependencyUnavailableError();
  }
  return jsonResponse(rpcResult.data.item, expectedStatus, requestId);
}

function enhanceItemDetail(
  detail: Record<string, unknown>,
  queryPlan: ReturnType<typeof prepareSearch> | null,
): Record<string, unknown> {
  const snapshot = detailDiscoverySnapshot(detail);
  const enhanced: Record<string, unknown> = {
    ...detail,
    classification_explanations: [],
  };

  if (
    detail.rules_version === RULES_VERSION &&
    isPositivePostgresInteger(detail.text_revision) &&
    isClassificationCategories(detail.classification_reasons)
  ) {
    const result: ClassificationResult = {
      rules_version: RULES_VERSION,
      target_revision: detail.text_revision,
      categories: detail.classification_reasons,
    };
    enhanced.classification_explanations = explainClassification(
      snapshot,
      result,
    );
  }

  if (queryPlan !== null) {
    enhanced.alias_explanations = explainAliases(snapshot, queryPlan);
  }
  return enhanced;
}

function detailDiscoverySnapshot(
  detail: Record<string, unknown>,
): DiscoverySnapshot {
  const activeAsset = isObject(detail.active_asset)
    ? detail.active_asset
    : null;
  return {
    user_title: nullableDetailString(detail.user_title),
    fetched_title: nullableDetailString(detail.fetched_title),
    note: nullableDetailString(detail.note),
    ocr: nullableDetailString(activeAsset?.ocr_text),
    shared: nullableDetailString(detail.shared_text),
    description: nullableDetailString(detail.description),
    body: nullableDetailString(detail.body_text),
  };
}

function nullableDetailString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function isClassificationCategories(
  value: unknown,
): value is ClassificationCategory[] {
  return Array.isArray(value) &&
    value.every((category) =>
      isObject(category) && typeof category.code === "string" &&
      typeof category.score === "number" && Number.isFinite(category.score) &&
      Array.isArray(category.rules) &&
      category.rules.every((rule) =>
        isObject(rule) && typeof rule.id === "string" &&
        Array.isArray(rule.fields) &&
        rule.fields.every((field) => typeof field === "string")
      )
    );
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
      case "ASSET_NOT_FOUND":
        return assetNotFoundError();
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
          409,
          "CATEGORY_LIMIT_REACHED",
          "The category limit has been reached.",
        );
      case "CATEGORY_NAME_EXISTS":
        return new ApiError(
          409,
          "CATEGORY_NAME_EXISTS",
          "A category with this name already exists.",
        );
      case "CATEGORY_NORMALIZATION_REQUIRED":
        return new ApiError(
          503,
          "CATEGORY_NORMALIZATION_REQUIRED",
          "Category normalization must finish before this operation.",
          true,
        );
      case "SYSTEM_CATEGORY_READONLY":
        return new ApiError(
          403,
          "SYSTEM_CATEGORY_READONLY",
          "System categories cannot be changed.",
        );
      case "CATEGORY_NOT_FOUND":
        return new ApiError(
          404,
          "CATEGORY_NOT_FOUND",
          "Category was not found.",
        );
      case "QUERY_LIMIT":
        return new ApiError(
          400,
          "QUERY_LIMIT",
          "The search query exceeds the supported limits.",
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
      case "ASSET_RESERVATION_EXISTS":
        return new ApiError(
          409,
          "ASSET_RESERVATION_EXISTS",
          "An asset upload is already reserved for this item.",
        );
      case "STORAGE_LIMIT_REACHED":
        return new ApiError(
          409,
          "STORAGE_LIMIT_REACHED",
          "The image storage limit has been reached.",
        );
      case "RESERVATION_EXPIRED":
        return new ApiError(
          409,
          "RESERVATION_EXPIRED",
          "The asset upload reservation expired.",
        );
      case "ASSET_NOT_RESERVED":
        return new ApiError(
          409,
          "ASSET_NOT_RESERVED",
          "The asset is not reserved for upload.",
        );
      case "ASSET_NOT_ACTIVE":
        return new ApiError(
          409,
          "ASSET_NOT_ACTIVE",
          "The asset is not active.",
        );
      case "ASSET_OBJECT_MISSING":
        return assetConflictError(
          "ASSET_OBJECT_MISSING",
          "The uploaded asset object was not found.",
        );
      case "ASSET_PATH_MISMATCH":
        return assetConflictError(
          "ASSET_PATH_MISMATCH",
          "The uploaded asset path is invalid.",
        );
      case "ASSET_TOO_LARGE":
        return assetConflictError(
          "ASSET_TOO_LARGE",
          "The uploaded asset exceeds the size limit.",
        );
      case "ASSET_SIZE_INVALID":
        return assetConflictError(
          "ASSET_SIZE_INVALID",
          "The uploaded asset size is invalid.",
        );
      case "ASSET_MIME_MISMATCH":
        return assetConflictError(
          "ASSET_MIME_MISMATCH",
          "The uploaded asset type does not match its reservation.",
        );
      case "ASSET_DECODE_FAILED":
        return assetConflictError(
          "ASSET_DECODE_FAILED",
          "The uploaded asset is not a valid image.",
        );
      case "ASSET_PIXEL_LIMIT_EXCEEDED":
        return assetConflictError(
          "ASSET_PIXEL_LIMIT_EXCEEDED",
          "The uploaded image exceeds the pixel limit.",
        );
      case "ASSET_DIMENSIONS_INVALID":
        return assetConflictError(
          "ASSET_DIMENSIONS_INVALID",
          "The uploaded image dimensions are invalid.",
        );
      case "METADATA_UNSUPPORTED":
        return new ApiError(
          409,
          "METADATA_UNSUPPORTED",
          "Metadata extraction is not supported for this item.",
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

function isCategoryListResult(
  value: unknown,
): value is {
  categories: unknown[];
  count: number;
  unclassified_count: number;
} {
  return isObject(value) && Array.isArray(value.categories) &&
    Number.isInteger(value.count) && (value.count as number) >= 0 &&
    Number.isInteger(value.unclassified_count) &&
    (value.unclassified_count as number) >= 0;
}

function isCategoryMutationResult(
  value: unknown,
  expectedStatus: 200 | 201,
): value is { http_status: 200 | 201; category: object } {
  return isObject(value) && value.http_status === expectedStatus &&
    isObject(value.category);
}

function isDeleteCategoryResult(
  value: unknown,
): value is { http_status: 204 } {
  return isObject(value) && Object.keys(value).length === 1 &&
    value.http_status === 204;
}

function isReclassifyResult(
  value: unknown,
): value is { http_status: 202; job_id: string; item_id: string } {
  return isObject(value) && value.http_status === 202 &&
    typeof value.job_id === "string" && isUuid(value.job_id) &&
    typeof value.item_id === "string" && isUuid(value.item_id);
}

function isRetryMetadataResult(
  value: unknown,
): value is { http_status: 202; job_id: string } {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 202 && typeof value.job_id === "string" &&
    isUuid(value.job_id);
}

function isReserveAssetResult(
  value: unknown,
): value is {
  http_status: 201;
  asset_id: string;
  object_path: string;
  expires_at: string;
  max_bytes: 2_000_000;
} {
  return isObject(value) && Object.keys(value).length === 5 &&
    value.http_status === 201 && typeof value.asset_id === "string" &&
    isUuid(value.asset_id) && typeof value.object_path === "string" &&
    typeof value.expires_at === "string" &&
    !Number.isNaN(Date.parse(value.expires_at)) &&
    value.max_bytes === 2_000_000;
}

function isDeleteAssetResult(
  value: unknown,
): value is {
  http_status: 202;
  asset_id: string;
  state: "deleting";
} {
  return isObject(value) && Object.keys(value).length === 3 &&
    value.http_status === 202 && typeof value.asset_id === "string" &&
    isUuid(value.asset_id) && value.state === "deleting";
}

function isAssetItemResult(
  value: unknown,
): value is {
  http_status: 200;
  item: Record<string, unknown> & { id: string };
} {
  return isObject(value) && Object.keys(value).length === 2 &&
    value.http_status === 200 && isObject(value.item) &&
    typeof value.item.id === "string" && isUuid(value.item.id);
}

function isUpdateResult(
  value: unknown,
): value is { http_status: 200; item: object } {
  return isObject(value) && value.http_status === 200 && isObject(value.item);
}

function isVersionConflictUpdateResult(
  value: unknown,
): value is { http_status: 409; error_code: "VERSION_CONFLICT" } {
  return isCommittedConflictResult(value, VERSION_COMMITTED_CONFLICTS) &&
    value.error_code === "VERSION_CONFLICT";
}

function isCommittedConflictResult(
  value: unknown,
  allowedErrorCodes: ReadonlySet<string>,
): value is { http_status: 409; error_code: string } {
  return isObject(value) &&
    Object.keys(value).length === 2 &&
    Object.hasOwn(value, "http_status") &&
    value.http_status === 409 &&
    Object.hasOwn(value, "error_code") &&
    typeof value.error_code === "string" &&
    allowedErrorCodes.has(value.error_code);
}

function isItemUpdateSnapshotFor(
  value: unknown,
  itemId: string,
): value is ItemUpdateSnapshot & { id: string } {
  return isObject(value) && typeof value.id === "string" &&
    isUuid(value.id) && value.id.toLowerCase() === itemId.toLowerCase() &&
    isItemUpdateSnapshot(value);
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

function invalidIdentifierError(kind: "Item" | "Category" | "Asset"): ApiError {
  return new ApiError(
    400,
    "INVALID_BODY",
    `${kind} id must be a UUID.`,
  );
}

function assetNotFoundError(): ApiError {
  return new ApiError(404, "ASSET_NOT_FOUND", "Asset was not found.");
}

function assetConflictError(code: string, message: string): ApiError {
  return new ApiError(409, code, message);
}

function scheduleAfterCommit(schedule: (() => void) | undefined): void {
  try {
    schedule?.();
  } catch {
    // Opportunistic execution must never change an already committed result.
  }
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

function assetContentResponse(
  bytes: Uint8Array,
  mimeType: string,
  requestId: string,
): Response {
  return new Response(bytes.slice().buffer, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "content-length": String(bytes.byteLength),
      "content-type": mimeType,
      "x-content-type-options": "nosniff",
      "x-request-id": requestId,
    },
  });
}

function emptyResponse(status: 204, requestId: string): Response {
  return new Response(null, {
    status,
    headers: {
      "cache-control": "no-store",
      "x-request-id": requestId,
    },
  });
}
