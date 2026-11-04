import { assert, assertEquals, assertRejects } from "@std/assert";
import { fetchLibraryExport } from "./library-export.ts";

const SUPABASE_URL = "https://database.test/ignored/base";
const ANON_KEY = "public-anon-fixture";
const AUTHORIZATION = "Bearer member-access-fixture";

Deno.test("export transport uses only the fixed raw RPC, anon key, and caller authorization", async () => {
  const upstream = new Response('{"items":[]}', {
    headers: { "content-type": "application/json" },
  });
  const caller = new AbortController();
  let calls = 0;
  const fetcher: typeof fetch = (input, init) => {
    calls++;
    assertEquals(
      String(input),
      "https://database.test/rest/v1/rpc/library_export_items",
    );
    assertEquals(init?.method, "POST");
    assertEquals(init?.body, "{}");
    assertEquals(init?.redirect, "error");
    assertEquals(Object.fromEntries(new Headers(init?.headers)), {
      accept: "application/json",
      "content-type": "application/json",
      apikey: ANON_KEY,
      authorization: AUTHORIZATION,
    });
    assert(init?.signal instanceof AbortSignal);
    assert(init.signal !== caller.signal);
    assertEquals(init.signal.aborted, false);
    return Promise.resolve(upstream);
  };
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: caller.signal,
  }, fetcher);

  assert(response === upstream);
  assertEquals(response.bodyUsed, false);
  assertEquals(calls, 1);
  assertEquals(await response.text(), '{"items":[]}');
});

Deno.test("raw export response preserves lazy chunk delivery and consumer backpressure", async () => {
  const chunks = ['{"items":[', '{"title":"한글 🙂"}', "]}"]
    .map((text) => new TextEncoder().encode(text));
  let pulls = 0;
  const upstream = new Response(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        const chunk = chunks[pulls++];
        if (chunk === undefined) controller.close();
        else controller.enqueue(chunk);
      },
    }, { highWaterMark: 0 }),
  );
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: new AbortController().signal,
  }, () => Promise.resolve(upstream));

  assert(response === upstream);
  assertEquals(pulls, 0);
  assertEquals(upstream.bodyUsed, false);
  const reader = response.body!.getReader();
  for (let index = 0; index < chunks.length; index++) {
    assertEquals(await reader.read(), { done: false, value: chunks[index] });
    await Promise.resolve();
    assertEquals(pulls, index + 1);
  }
  assertEquals(await reader.read(), { done: true, value: undefined });
  reader.releaseLock();
});

Deno.test("raw export transport does not interpret or buffer error statuses and headers", async () => {
  let pulls = 0;
  let cancelled = false;
  const upstream = new Response(
    new ReadableStream<Uint8Array>({
      pull() {
        pulls++;
      },
      cancel() {
        cancelled = true;
      },
    }, { highWaterMark: 0 }),
    {
      status: 503,
      headers: {
        "content-type": "text/html",
        "x-private-fixture": "not-public",
      },
    },
  );
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: new AbortController().signal,
  }, () => Promise.resolve(upstream));

  assert(response === upstream);
  assertEquals(response.status, 503);
  assertEquals(response.headers.get("x-private-fixture"), "not-public");
  assertEquals(pulls, 0);
  await response.body!.cancel();
  assertEquals(cancelled, true);
});

Deno.test("already cancelled exports do not issue a raw request", async () => {
  const caller = new AbortController();
  const failure = new DOMException(
    "cancelled before fetch fixture",
    "AbortError",
  );
  caller.abort(failure);
  let calls = 0;
  const caught = await assertRejects(
    () =>
      fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
        authorization: AUTHORIZATION,
        signal: caller.signal,
      }, () => {
        calls++;
        return Promise.resolve(new Response());
      }),
    DOMException,
    failure.message,
  );
  assert(caught === failure);
  assertEquals(calls, 0);
});

Deno.test("caller cancellation propagates while waiting for export headers without retrying", async () => {
  const caller = new AbortController();
  const failure = new DOMException(
    "cancelled pending fetch fixture",
    "AbortError",
  );
  let calls = 0;
  const fetcher: typeof fetch = (_input, init) => {
    calls++;
    const signal = init?.signal;
    assert(signal instanceof AbortSignal);
    return new Promise((_resolve, reject) => {
      signal.addEventListener("abort", () => reject(signal.reason), {
        once: true,
      });
    });
  };
  const pending = fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: caller.signal,
  }, fetcher);
  caller.abort(failure);
  assert(
    await assertRejects(() => pending, DOMException, failure.message) ===
      failure,
  );
  assertEquals(calls, 1);
});

Deno.test("caller cancellation remains connected to the response stream after headers", async () => {
  const caller = new AbortController();
  const failure = new DOMException(
    "cancelled during transfer fixture",
    "AbortError",
  );
  let calls = 0;
  const fetcher: typeof fetch = (_input, init) => {
    calls++;
    const signal = init?.signal;
    assert(signal instanceof AbortSignal);
    return Promise.resolve(
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            signal.addEventListener(
              "abort",
              () => controller.error(signal.reason),
              { once: true },
            );
          },
        }, { highWaterMark: 0 }),
      ),
    );
  };
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: caller.signal,
  }, fetcher);
  const reader = response.body!.getReader();
  const pending = reader.read();
  caller.abort(failure);
  assert(
    await assertRejects(() => pending, DOMException, failure.message) ===
      failure,
  );
  reader.releaseLock();
  assertEquals(calls, 1);
});

Deno.test("the finite 300-second export budget covers both pending headers and the response stream", async () => {
  const originalTimeout = AbortSignal.timeout;
  try {
    for (const phase of ["headers", "body"]) {
      const deadline = new AbortController();
      const failure = new DOMException(
        "export deadline fixture",
        "TimeoutError",
      );
      const budgets: number[] = [];
      AbortSignal.timeout = (milliseconds) => {
        budgets.push(milliseconds);
        return deadline.signal;
      };
      let calls = 0;
      const fetcher: typeof fetch = (_input, init) => {
        calls++;
        const signal = init?.signal;
        assert(signal instanceof AbortSignal);
        if (phase === "headers") {
          return new Promise((_resolve, reject) => {
            signal.addEventListener("abort", () => reject(signal.reason), {
              once: true,
            });
          });
        }
        return Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(controller) {
                signal.addEventListener(
                  "abort",
                  () => controller.error(signal.reason),
                  { once: true },
                );
              },
            }, { highWaterMark: 0 }),
          ),
        );
      };
      const pending = fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
        authorization: AUTHORIZATION,
        signal: new AbortController().signal,
      }, fetcher);
      if (phase === "headers") {
        deadline.abort(failure);
        assert(
          await assertRejects(() => pending, DOMException, failure.message) ===
            failure,
        );
      } else {
        const response = await pending;
        const reader = response.body!.getReader();
        const reading = reader.read();
        deadline.abort(failure);
        assert(
          await assertRejects(() => reading, DOMException, failure.message) ===
            failure,
        );
        reader.releaseLock();
      }
      assertEquals(budgets, [300_000]);
      assertEquals(calls, 1);
    }
  } finally {
    AbortSignal.timeout = originalTimeout;
  }
});

Deno.test("raw export fetch rejection and body errors propagate without fallback or retry", async () => {
  const failure = new TypeError("raw fetch failed fixture");
  let calls = 0;
  assert(
    await assertRejects(
      () =>
        fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
          authorization: AUTHORIZATION,
          signal: new AbortController().signal,
        }, () => {
          calls++;
          return Promise.reject(failure);
        }),
      TypeError,
      failure.message,
    ) === failure,
  );
  assertEquals(calls, 1);

  const bodyFailure = new Error("raw stream failed fixture");
  const upstream = new Response(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.error(bodyFailure);
      },
    }, { highWaterMark: 0 }),
  );
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: new AbortController().signal,
  }, () => {
    calls++;
    return Promise.resolve(upstream);
  });
  assert(response === upstream);
  const reader = response.body!.getReader();
  assert(
    await assertRejects(() => reader.read(), Error, bodyFailure.message) ===
      bodyFailure,
  );
  reader.releaseLock();
  assertEquals(calls, 2);
});

Deno.test("raw export consumer cancellation reaches upstream without copying or teeing", async () => {
  let cancelled: unknown;
  const upstream = new Response(
    new ReadableStream<Uint8Array>({
      cancel(reason) {
        cancelled = reason;
      },
    }, { highWaterMark: 0 }),
  );
  const response = await fetchLibraryExport(SUPABASE_URL, ANON_KEY, {
    authorization: AUTHORIZATION,
    signal: new AbortController().signal,
  }, () => Promise.resolve(upstream));
  assert(response === upstream);
  await response.body!.cancel("consumer cancelled fixture");
  assertEquals(cancelled, "consumer cancelled fixture");
});
