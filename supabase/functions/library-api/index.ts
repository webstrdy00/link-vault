import { createClient } from "@supabase/supabase-js";
import { createHandler, type MemberGateway, type RpcCall } from "./handler.ts";

const supabaseUrl = requiredEnv("SUPABASE_URL");
const supabaseAnonKey = requiredEnv("SUPABASE_ANON_KEY");
const supabaseServiceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
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
};

Deno.serve(createHandler(gateway));

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
