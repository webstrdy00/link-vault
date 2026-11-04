export interface ExportItemsCall {
  authorization: string;
  signal: AbortSignal;
}

export async function fetchLibraryExport(
  supabaseUrl: string,
  anonKey: string,
  call: ExportItemsCall,
  fetcher: typeof fetch = fetch,
): Promise<Response> {
  call.signal.throwIfAborted();
  return await fetcher(
    new URL("/rest/v1/rpc/library_export_items", supabaseUrl),
    {
      method: "POST",
      headers: {
        accept: "application/json",
        "content-type": "application/json",
        apikey: anonKey,
        authorization: call.authorization,
      },
      body: "{}",
      redirect: "error",
      signal: AbortSignal.any([
        call.signal,
        AbortSignal.timeout(300_000),
      ]),
    },
  );
}
