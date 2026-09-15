import { createClient } from "@supabase/supabase-js";
import {
  type ClassificationGateway,
  createClassificationHandler,
  isInternalClassificationPath,
  runClassificationBatch,
} from "./classification-worker.ts";
import { createHandler, type MemberGateway, type RpcCall } from "./handler.ts";
import {
  createMetadataHandler,
  isInternalMetadataPath,
  type MetadataGateway,
  runMetadataBatch,
} from "./metadata-worker.ts";
import {
  createAssetCleanupHandler,
  isInternalAssetCleanupPath,
  readBoundedAssetResponse,
  runAssetCleanupBatch,
} from "./asset-service.ts";

const supabaseUrl = requiredEnv("SUPABASE_URL");
const supabaseAnonKey = requiredEnv("SUPABASE_ANON_KEY");
const supabaseServiceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
const libraryImageBucket = "library-images" as const;
const serviceClient = createClient(supabaseUrl, supabaseServiceRoleKey, {
  auth: {
    autoRefreshToken: false,
    persistSession: false,
    detectSessionInUrl: false,
  },
});

const gateway: MemberGateway = {
  async verifyUser(accessToken) {
    const client = callerClientFor(`Bearer ${accessToken}`);
    const { data, error } = await client.auth.getUser(accessToken);

    if (error !== null) {
      const status = typeof error.status === "number"
        ? error.status
        : undefined;
      if (
        status === 400 || status === 401 || status === 403 || status === 422
      ) {
        return { status: "invalid" };
      }
      return { status: "unavailable" };
    }

    return data.user?.id
      ? { status: "verified", userId: data.user.id }
      : { status: "invalid" };
  },

  async rpc(call: RpcCall) {
    const client = callerClientFor(call.authorization);
    const { data, error } = await client.rpc(call.functionName, call.args);
    return rpcResult(data, error);
  },

  async createItem(call) {
    const { data, error } = await serviceClient.rpc("library_create_item", {
      p_owner_id: call.ownerId,
      p_request_id: call.requestId,
      p_body: call.body,
      p_prepared: call.prepared,
    });
    return rpcResult(data, error);
  },

  async lookupCategories(call) {
    if (call.categoryIds.length === 0) {
      return { data: [], error: null };
    }
    const { data, error } = await serviceClient
      .from("categories")
      .select("id,name")
      .eq("owner_id", call.ownerId)
      .in("id", call.categoryIds);
    return rpcResult(data, error);
  },

  async updateItem(call) {
    const { data, error } = await serviceClient.rpc("library_update_item", {
      p_owner_id: call.ownerId,
      p_item_id: call.itemId,
      p_request_id: call.requestId,
      p_body: call.body,
      p_prepared: call.prepared,
    });
    return rpcResult(data, error);
  },

  async serviceRpc(call) {
    const { data, error } = await serviceClient.rpc(call.functionName, {
      ...call.args,
      p_owner_id: call.ownerId,
      p_request_id: call.requestId,
    });
    return rpcResult(data, error);
  },

  async replayAssetRequest(call) {
    const { data, error } = await serviceClient.rpc(
      "library_replay_asset_request",
      {
        p_owner_id: call.ownerId,
        p_request_id: call.requestId,
        p_method: call.method,
        p_path: call.path,
        p_body: call.body,
      },
    );
    return rpcResult(data, error);
  },

  async reserveAsset(call) {
    const { data, error } = await serviceClient.rpc("library_reserve_asset", {
      p_owner_id: call.ownerId,
      p_request_id: call.requestId,
      p_item_id: call.itemId,
      p_body: call.body,
    });
    return rpcResult(data, error);
  },

  async lookupAsset(call) {
    const { data, error } = await serviceClient
      .from("assets")
      .select(
        "id,owner_id,item_id,state,object_path,reserved_mime_type,mime_type,actual_bytes",
      )
      .eq("owner_id", call.ownerId)
      .eq("item_id", call.itemId)
      .eq("id", call.assetId)
      .limit(1);
    if (error !== null) {
      return rpcResult(null, error);
    }
    return rpcResult(data?.[0] ?? null, null);
  },

  async downloadAssetObject(call) {
    return await downloadLibraryImage(call.objectPath);
  },

  async completeAsset(call) {
    const { data, error } = await serviceClient.rpc("library_complete_asset", {
      p_owner_id: call.ownerId,
      p_request_id: call.requestId,
      p_item_id: call.itemId,
      p_asset_id: call.assetId,
      p_body: call.body,
      p_verified_object: call.verifiedObject,
      p_prepared_index: call.preparedIndex,
    });
    return rpcResult(data, error);
  },

  async rejectAssetUpload(call) {
    const { data, error } = await serviceClient.rpc(
      "library_reject_asset_upload",
      {
        p_owner_id: call.ownerId,
        p_request_id: call.requestId,
        p_item_id: call.itemId,
        p_asset_id: call.assetId,
        p_body: call.body,
        p_failure_code: call.failureCode,
      },
    );
    return rpcResult(data, error);
  },

  async deleteAsset(call) {
    const { data, error } = await serviceClient.rpc("library_delete_asset", {
      p_owner_id: call.ownerId,
      p_request_id: call.requestId,
      p_item_id: call.itemId,
      p_asset_id: call.assetId,
      p_body: call.body,
      p_prepared_index: call.preparedIndex,
    });
    return rpcResult(data, error);
  },

  async updateAssetOcr(call) {
    const { data, error } = await serviceClient.rpc(
      "library_update_asset_ocr",
      {
        p_owner_id: call.ownerId,
        p_request_id: call.requestId,
        p_item_id: call.itemId,
        p_asset_id: call.assetId,
        p_body: call.body,
        p_prepared_index: call.preparedIndex,
      },
    );
    return rpcResult(data, error);
  },

  async claimAssetCleanupJobs(limit) {
    const { data, error } = await serviceClient.rpc(
      "library_claim_asset_cleanup_jobs",
      { p_limit: limit },
    );
    return rpcResult(data, error);
  },

  async removeAssetObject(call) {
    if (call.bucketId !== libraryImageBucket) {
      return { error: { message: "INVALID_BUCKET" } };
    }
    const { error } = await serviceClient.storage
      .from(libraryImageBucket)
      .remove([call.objectPath]);
    return {
      error: error === null ? null : { message: "ASSET_STORAGE_DELETE_FAILED" },
    };
  },

  async finishAssetCleanup(call) {
    const { data, error } = await serviceClient.rpc(
      "library_finish_asset_cleanup",
      {
        p_asset_id: call.assetId,
        p_lease_token: call.leaseToken,
      },
    );
    return rpcResult(data, error);
  },

  async failAssetCleanup(call) {
    const { data, error } = await serviceClient.rpc(
      "library_fail_asset_cleanup",
      {
        p_asset_id: call.assetId,
        p_lease_token: call.leaseToken,
        p_error_code: call.errorCode,
      },
    );
    return rpcResult(data, error);
  },
};

const classificationGateway: ClassificationGateway = {
  async claimClassificationJobs(limit) {
    const { data, error } = await serviceClient.rpc(
      "library_claim_classification_jobs",
      { p_limit: limit },
    );
    return rpcResult(data, error);
  },

  async completeClassificationJob(call) {
    const { data, error } = await serviceClient.rpc(
      "library_complete_classification_job",
      {
        p_job_id: call.jobId,
        p_lease_token: call.leaseToken,
        p_expected_version: call.expectedVersion,
        p_result: call.result,
      },
    );
    return rpcResult(data, error);
  },

  async failClassificationJob(call) {
    const { data, error } = await serviceClient.rpc(
      "library_fail_classification_job",
      {
        p_job_id: call.jobId,
        p_lease_token: call.leaseToken,
        p_retryable: call.retryable,
        p_error_code: call.errorCode,
      },
    );
    return rpcResult(data, error);
  },
};

const metadataGateway: MetadataGateway = {
  async claimMetadataJobs(limit) {
    const { data, error } = await serviceClient.rpc(
      "library_claim_metadata_jobs",
      { p_limit: limit },
    );
    return rpcResult(data, error);
  },

  async completeMetadataJob(call) {
    const { data, error } = await serviceClient.rpc(
      "library_complete_metadata_job",
      {
        p_job_id: call.jobId,
        p_lease_token: call.leaseToken,
        p_expected_version: call.expectedVersion,
        p_result: call.result,
        p_prepared: call.prepared,
      },
    );
    return rpcResult(data, error);
  },

  async failMetadataJob(call) {
    const { data, error } = await serviceClient.rpc(
      "library_fail_metadata_job",
      {
        p_job_id: call.jobId,
        p_lease_token: call.leaseToken,
        p_error_code: call.errorCode,
        p_retry_after_seconds: call.retryAfterSeconds,
      },
    );
    return rpcResult(data, error);
  },
};

const publicHandler = createHandler(gateway, scheduleBackgroundWork);
const classificationHandler = createClassificationHandler(
  classificationGateway,
  supabaseServiceRoleKey,
);
const metadataHandler = createMetadataHandler(
  metadataGateway,
  supabaseServiceRoleKey,
);
const assetCleanupHandler = createAssetCleanupHandler(
  gateway,
  supabaseServiceRoleKey,
);

Deno.serve((request) => {
  const pathname = new URL(request.url).pathname;
  if (isInternalClassificationPath(pathname)) {
    return classificationHandler(request);
  }
  if (isInternalMetadataPath(pathname)) {
    return metadataHandler(request);
  }
  if (isInternalAssetCleanupPath(pathname)) {
    return assetCleanupHandler(request);
  }
  return publicHandler(request);
});

function scheduleBackgroundWork(): void {
  const edgeRuntime = (
    globalThis as typeof globalThis & {
      EdgeRuntime?: { waitUntil(promise: Promise<unknown>): void };
    }
  ).EdgeRuntime;
  edgeRuntime?.waitUntil(
    Promise.all([
      runClassificationBatch(classificationGateway, 5).catch(() => undefined),
      runMetadataBatch(metadataGateway, 5).catch(() => undefined),
      runAssetCleanupBatch(gateway, 5).catch(() => undefined),
    ]),
  );
}

async function downloadLibraryImage(objectPath: string) {
  if (!isLibraryImagePath(objectPath)) {
    throw new Error("INVALID_ASSET_PATH");
  }
  const encodedPath = objectPath.split("/").map(encodeURIComponent).join("/");
  const objectUrl = new URL(
    `/storage/v1/object/authenticated/${libraryImageBucket}/${encodedPath}`,
    supabaseUrl,
  );
  const response = await fetch(objectUrl, {
    method: "GET",
    headers: {
      "accept-encoding": "identity",
      apikey: supabaseServiceRoleKey,
      authorization: `Bearer ${supabaseServiceRoleKey}`,
    },
    redirect: "error",
  });
  return await readBoundedAssetResponse(response);
}

function isLibraryImagePath(value: string): boolean {
  const uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  return new RegExp(`^${uuid}/${uuid}/${uuid}$`, "i").test(value);
}

function callerClientFor(authorization: string) {
  return createClient(supabaseUrl, supabaseAnonKey, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
      detectSessionInUrl: false,
    },
    global: {
      headers: { Authorization: authorization },
    },
  });
}

function rpcResult(
  data: unknown,
  error: { code?: string; message?: string } | null,
) {
  return {
    data,
    error: error === null ? null : { code: error.code, message: error.message },
  };
}

function requiredEnv(
  name: "SUPABASE_URL" | "SUPABASE_ANON_KEY" | "SUPABASE_SERVICE_ROLE_KEY",
): string {
  const value = Deno.env.get(name);
  if (value === undefined || value.length === 0) {
    throw new Error(`${name} is required`);
  }
  return value;
}
