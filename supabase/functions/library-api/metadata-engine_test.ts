import { assert, assertEquals } from "@std/assert";
import { Readable } from "node:stream";
import {
  fetchMetadataWithAdapters,
  type MetadataAdapters,
  type MetadataDnsResolver,
  MetadataFetchFailure,
  type MetadataTransport,
  type MetadataTransportRequest,
  type MetadataTransportResponse,
  ownUndiciMetadataResponse,
  parseRetryAfter,
  productionMetadataDnsResolver,
  productionMetadataTransport,
  type ResolvedAddress,
} from "./metadata-engine.ts";
import {
  type ItemUpdateSnapshot,
  prepareEnrichedSnapshot,
  prepareItem,
} from "./item-preparation.ts";

const CHECKED_AT = new Date("2026-09-15T12:00:00.000Z");
const PUBLIC_V4: ResolvedAddress = { address: "223.130.200.104", family: 4 };
const encoder = new TextEncoder();

class FakeDns implements MetadataDnsResolver {
  readonly calls: string[] = [];

  constructor(
    private readonly answers: Record<string, ResolvedAddress[] | Error> = {
      "blog.naver.com": [PUBLIC_V4],
      "m.blog.naver.com": [PUBLIC_V4],
    },
  ) {}

  resolve(hostname: string): Promise<ResolvedAddress[]> {
    this.calls.push(hostname);
    const answer = this.answers[hostname];
    return answer instanceof Error
      ? Promise.reject(answer)
      : Promise.resolve(answer ?? []);
  }
}

class FakeTransport implements MetadataTransport {
  readonly calls: MetadataTransportRequest[] = [];

  constructor(
    private readonly steps: Array<
      | MetadataTransportResponse
      | ((
        input: MetadataTransportRequest,
      ) => Promise<MetadataTransportResponse>)
    >,
  ) {}

  request(input: MetadataTransportRequest): Promise<MetadataTransportResponse> {
    this.calls.push(input);
    const step = this.steps.shift();
    if (step === undefined) {
      return Promise.reject(new Error("Unexpected metadata request."));
    }
    return typeof step === "function" ? step(input) : Promise.resolve(step);
  }
}

class FakeResponseOwner {
  readonly destroyErrors: Array<Error | null | undefined> = [];

  destroy(error?: Error | null): Promise<void> {
    this.destroyErrors.push(error);
    return Promise.resolve();
  }
}

function fakeAdapters(
  transport: MetadataTransport,
  dns: MetadataDnsResolver = new FakeDns(),
  timeoutMs = 10_000,
): MetadataAdapters {
  assert(typeof productionMetadataDnsResolver.resolve === "function");
  assert(typeof productionMetadataTransport.request === "function");
  return { dns, transport, now: () => CHECKED_AT, timeoutMs };
}

function response(
  status: number,
  chunks: Uint8Array[] = [],
  headers: Record<string, string> = {
    "content-type": "text/html; charset=utf-8",
  },
): MetadataTransportResponse {
  return { status, headers, body: byteStream(chunks) };
}

function htmlResponse(
  html: string,
  headers: Record<string, string> = {
    "content-type": "text/html; charset=utf-8",
  },
): MetadataTransportResponse {
  return response(200, [encoder.encode(html)], headers);
}

async function* byteStream(chunks: Uint8Array[]): AsyncIterable<Uint8Array> {
  for (const chunk of chunks) {
    yield chunk;
  }
}

async function metadataFailure(
  operation: Promise<unknown>,
): Promise<MetadataFetchFailure> {
  try {
    await operation;
  } catch (error) {
    assert(error instanceof MetadataFetchFailure);
    return error;
  }
  throw new Error("Expected metadata fetch to fail.");
}

Deno.test("production metadata DNS and pinned HTTPS transport adapters exist", () => {
  assert(typeof productionMetadataDnsResolver.resolve === "function");
  assert(typeof productionMetadataTransport.request === "function");
});

Deno.test("production pinned transport validates addresses and owns body lifetime", async () => {
  for (
    const addresses of [
      [],
      [{ address: "127.0.0.1", family: 4 as const }],
      [{ address: "fc00::1", family: 6 as const }],
    ]
  ) {
    let rejected = false;
    try {
      await productionMetadataTransport.request({
        url: new URL("https://blog.naver.com/post"),
        addresses,
        signal: new AbortController().signal,
      });
    } catch (error) {
      rejected = error instanceof TypeError;
    }
    assertEquals(rejected, true);
  }

  const cancelledBody = new Readable({ read() {} });
  const cancelledOwner = new FakeResponseOwner();
  const cancelledResponse = ownUndiciMetadataResponse({
    statusCode: 404,
    headers: { "content-type": "text/html" },
    body: cancelledBody,
  }, cancelledOwner);
  const closed = new Promise<void>((resolve) => {
    cancelledBody.once("close", resolve);
  });
  cancelledResponse.cancel?.();
  await closed;
  assertEquals(cancelledOwner.destroyErrors.length, 1);
  assert(cancelledOwner.destroyErrors[0] instanceof Error);

  const readFailure = new Error("Fixture stream failed.");
  let readStarted = false;
  const failingBody = new Readable({
    read() {
      if (readStarted) {
        return;
      }
      readStarted = true;
      this.push(encoder.encode("partial"));
      queueMicrotask(() => this.destroy(readFailure));
    },
  });
  const failingOwner = new FakeResponseOwner();
  const failingResponse = ownUndiciMetadataResponse({
    statusCode: 200,
    headers: { "content-type": "text/html" },
    body: failingBody,
  }, failingOwner);
  let observedFailure: unknown;
  try {
    for await (const _chunk of failingResponse.body!) {
      // Continue so the iterator must observe the stream error.
    }
  } catch (error) {
    observedFailure = error;
  }
  assert(observedFailure === readFailure);
  assertEquals(failingOwner.destroyErrors, [null]);
});

Deno.test("Naver parser decodes entities and prefers real OG metadata", async () => {
  const transport = new FakeTransport([htmlResponse(`<!doctype html>
    <html><head>
      <title>무시할 &amp; fallback</title>
      <meta content="네이버 &amp; 봄 여행" property="og:title">
      <meta name="description" content="낮은 우선순위">
      <meta content="제주 &quot;봄&quot; 숙소 &amp; 카페" property="og:description">
      <meta property="og:image" content="https://example.invalid/image.jpg">
    </head><body><h1>본문 제목</h1><script>login challenge</script></body></html>`)]);

  const result = await fetchMetadataWithAdapters(
    "https://blog.naver.com/PostView.naver?blogId=sample&logNo=1#original",
    fakeAdapters(transport),
  );

  assertEquals(result, {
    fetched_title: "네이버 & 봄 여행",
    description: '제주 "봄" 숙소 & 카페',
    body_text: null,
    metadata_state: "ready",
    extraction_meta: {
      adapter_version: "naver-metadata-v1",
      final_url:
        "https://blog.naver.com/PostView.naver?blogId=sample&logNo=1#original",
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
      last_checked_at: CHECKED_AT.toISOString(),
      error_code: null,
    },
  });
  assertEquals(transport.calls.length, 1);
  assertEquals(transport.calls[0].addresses, [PUBLIC_V4]);
});

Deno.test("semantic heading fallback handles nested entity text without treating login prose as a wall", async () => {
  const html = `<!doctype html><html><head>
    <meta name="description" content="공개 글에서 login API를 안전하게 설명합니다">
    </head><body><h1>네이버 <em>봄 &amp; 여행</em> 기록</h1></body></html>`;
  const result = await fetchMetadataWithAdapters(
    "https://m.blog.naver.com/sample/1",
    fakeAdapters(new FakeTransport([htmlResponse(html)])),
  );

  assertEquals(result.fetched_title, "네이버 봄 & 여행 기록");
  assertEquals(
    result.description,
    "공개 글에서 login API를 안전하게 설명합니다",
  );
  assertEquals(result.metadata_state, "ready");
  assertEquals(result.extraction_meta.error_code, null);
});

Deno.test("missing, generic, login, and doctype challenge pages never become ready", async () => {
  const cases = [
    {
      html: "<!doctype html><html><head></head><body></body></html>",
      code: "INVALID_RESULT",
    },
    {
      html:
        '<!doctype html><title>네이버</title><meta name="description" content="서비스 홈입니다">',
      code: "INVALID_RESULT",
    },
    {
      html:
        '<!doctype html><title>로그인 : 네이버</title><form action="/login"><input type="password"></form>',
      code: "ACCESS_DENIED",
    },
    {
      html:
        '<!doctype html><title>Just a moment...</title><form id="challenge-form"></form>',
      code: "ACCESS_DENIED",
    },
  ];

  for (const testCase of cases) {
    const result = await fetchMetadataWithAdapters(
      "https://blog.naver.com/sample",
      fakeAdapters(new FakeTransport([htmlResponse(testCase.html)])),
    );
    assertEquals(result.metadata_state, "partial");
    assertEquals(result.extraction_meta.error_code, testCase.code);
    assertEquals(result.body_text, null);
  }
});

Deno.test("access denial and non-HTML responses use fixed permanent result codes", async () => {
  const denied = await fetchMetadataWithAdapters(
    "https://blog.naver.com/private",
    fakeAdapters(new FakeTransport([response(403)])),
  );
  assertEquals(denied.metadata_state, "partial");
  assertEquals(denied.extraction_meta.error_code, "ACCESS_DENIED");

  const invalid = await fetchMetadataWithAdapters(
    "https://blog.naver.com/json",
    fakeAdapters(
      new FakeTransport([
        response(200, [encoder.encode("{}")], {
          "content-type": "application/json",
        }),
      ]),
    ),
  );
  assertEquals(invalid.metadata_state, "failed");
  assertEquals(invalid.extraction_meta.error_code, "INVALID_CONTENT");
});

Deno.test("content-length and streamed body enforce the decimal two MB boundary", async () => {
  const headerRejected = await fetchMetadataWithAdapters(
    "https://blog.naver.com/declared-large",
    fakeAdapters(
      new FakeTransport([
        response(200, [], {
          "content-type": "text/html",
          "content-length": "2000001",
        }),
      ]),
    ),
  );
  assertEquals(headerRejected.metadata_state, "failed");
  assertEquals(headerRejected.extraction_meta.error_code, "RESPONSE_TOO_LARGE");

  const useful =
    '<meta property="og:title" content="경계 본문"><meta property="og:description" content="정확히 이 메타정보를 읽습니다">';
  const usefulBytes = encoder.encode(useful);
  const exactBytes = new Uint8Array(2_000_000);
  exactBytes.fill(32);
  exactBytes.set(usefulBytes);
  const exact = await fetchMetadataWithAdapters(
    "https://blog.naver.com/exact",
    fakeAdapters(new FakeTransport([response(200, [exactBytes])])),
  );
  assertEquals(exact.metadata_state, "ready");

  const overflow = await fetchMetadataWithAdapters(
    "https://blog.naver.com/stream-large",
    fakeAdapters(
      new FakeTransport([
        response(200, [new Uint8Array(2_000_000), encoder.encode("한")]),
      ]),
    ),
  );
  assertEquals(overflow.metadata_state, "failed");
  assertEquals(overflow.extraction_meta.error_code, "RESPONSE_TOO_LARGE");
});

Deno.test("UTF-8 chunk boundaries decode correctly and output cuts keep Unicode scalar values", async () => {
  const longTitle = "😀".repeat(301);
  const longDescription = "가".repeat(4001);
  const html =
    `<meta property="og:title" content="${longTitle}"><meta property="og:description" content="${longDescription}">`;
  const bytes = encoder.encode(html);
  const emojiStart = bytes.indexOf(0xf0);
  const transport = new FakeTransport([
    response(200, [
      bytes.slice(0, emojiStart + 2),
      bytes.slice(emojiStart + 2),
    ]),
  ]);

  const result = await fetchMetadataWithAdapters(
    "https://blog.naver.com/unicode",
    fakeAdapters(transport),
  );

  assertEquals([...result.fetched_title!].length, 300);
  assertEquals([...result.description!].length, 4000);
  assertEquals(result.fetched_title!.endsWith("😀"), true);
  assertEquals(result.extraction_meta.title_truncated, true);
  assertEquals(result.extraction_meta.description_truncated, true);
  assertEquals(result.extraction_meta.body_truncated, false);
});

Deno.test("unsupported schemes, ports, userinfo, hosts, and literal IPs never reach DNS", async () => {
  const urls = [
    "http://blog.naver.com/post",
    "https://blog.naver.com:444/post",
    "https://user:secret@blog.naver.com/post",
    "https://example.com/post",
    "https://127.0.0.1/post",
    "https://[::1]/post",
    " https://blog.naver.com/post",
  ];

  for (const url of urls) {
    const dns = new FakeDns();
    const transport = new FakeTransport([]);
    const result = await fetchMetadataWithAdapters(
      url,
      fakeAdapters(transport, dns),
    );
    assertEquals(result.metadata_state, "unsupported", url);
    assertEquals(result.extraction_meta.error_code, "INVALID_CONTENT", url);
    assertEquals(dns.calls, [], url);
    assertEquals(transport.calls, [], url);
  }
});

Deno.test("private DNS answers are rejected before the pinned transport", async () => {
  for (
    const privateAddress of [
      { address: "10.0.0.7", family: 4 as const },
      { address: "169.254.169.254", family: 4 as const },
      { address: "fc00::1", family: 6 as const },
      { address: "2001:db8::1", family: 6 as const },
    ]
  ) {
    const dns = new FakeDns({ "blog.naver.com": [privateAddress] });
    const transport = new FakeTransport([]);
    const result = await fetchMetadataWithAdapters(
      "https://blog.naver.com/post",
      fakeAdapters(transport, dns),
    );
    assertEquals(result.metadata_state, "unsupported", privateAddress.address);
    assertEquals(transport.calls, [], privateAddress.address);
  }
});

Deno.test("every redirect hop is revalidated against DNS before transport", async () => {
  const dns = new FakeDns({
    "blog.naver.com": [PUBLIC_V4],
    "m.blog.naver.com": [{ address: "192.168.1.2", family: 4 }],
  });
  const transport = new FakeTransport([
    response(302, [], { location: "https://m.blog.naver.com/private-target" }),
  ]);

  const result = await fetchMetadataWithAdapters(
    "https://blog.naver.com/start",
    fakeAdapters(transport, dns),
  );

  assertEquals(result.metadata_state, "unsupported");
  assertEquals(dns.calls, ["blog.naver.com", "m.blog.naver.com"]);
  assertEquals(transport.calls.length, 1);
});

Deno.test("a fourth redirect fails without a fifth request", async () => {
  const transport = new FakeTransport([
    response(302, [], { location: "/one" }),
    response(302, [], { location: "/two" }),
    response(302, [], { location: "/three" }),
    response(302, [], { location: "/four" }),
  ]);
  const dns = new FakeDns();

  const result = await fetchMetadataWithAdapters(
    "https://blog.naver.com/start",
    fakeAdapters(transport, dns),
  );

  assertEquals(result.metadata_state, "failed");
  assertEquals(result.extraction_meta.error_code, "INVALID_CONTENT");
  assertEquals(transport.calls.length, 4);
  assertEquals(dns.calls.length, 4);
});

Deno.test("the total deadline throws one typed retryable timeout without refetch", async () => {
  const transport = new FakeTransport([
    () => new Promise<MetadataTransportResponse>(() => {}),
  ]);
  const error = await metadataFailure(fetchMetadataWithAdapters(
    "https://blog.naver.com/slow",
    fakeAdapters(transport, new FakeDns(), 1),
  ));

  assertEquals(error.code, "METADATA_TIMEOUT");
  assertEquals(error.retryable, true);
  assertEquals(error.retryAfterSeconds, null);
  assertEquals(transport.calls.length, 1);
});

Deno.test("DNS and upstream failures use the typed retryable network code", async () => {
  const dnsFailure = await metadataFailure(fetchMetadataWithAdapters(
    "https://blog.naver.com/dns-failure",
    fakeAdapters(
      new FakeTransport([]),
      new FakeDns({ "blog.naver.com": new Error("resolver unavailable") }),
    ),
  ));
  assertEquals(dnsFailure.code, "NETWORK_ERROR");
  assertEquals(dnsFailure.retryable, true);

  const transport = new FakeTransport([response(503)]);
  const upstreamFailure = await metadataFailure(fetchMetadataWithAdapters(
    "https://blog.naver.com/upstream-failure",
    fakeAdapters(transport),
  ));
  assertEquals(upstreamFailure.code, "NETWORK_ERROR");
  assertEquals(transport.calls.length, 1);
});

Deno.test("429 excessive Retry-After stops rather than retrying earlier than requested", async () => {
  const transport = new FakeTransport([
    response(429, [], { "retry-after": "9999999999" }),
  ]);
  const error = await metadataFailure(fetchMetadataWithAdapters(
    "https://blog.naver.com/rate-limited",
    fakeAdapters(transport),
  ));

  assertEquals(error.code, "RETRY_AFTER_EXCEEDED");
  assertEquals(error.retryable, false);
  assertEquals(error.retryAfterSeconds, null);
  assertEquals(transport.calls.length, 1);
  assertEquals(
    parseRetryAfter("Tue, 15 Sep 2026 12:00:30 GMT", CHECKED_AT),
    60,
  );
  assertEquals(parseRetryAfter("not-a-date", CHECKED_AT), null);
  assertEquals(parseRetryAfter("86401", CHECKED_AT), 86401);
  assertEquals((parseRetryAfter("99999999999", CHECKED_AT) ?? 0) > 86400, true);
});

Deno.test("429 preserves a supported delay without another request", async () => {
  const transport = new FakeTransport([
    response(429, [], { "retry-after": "3600" }),
  ]);
  const error = await metadataFailure(fetchMetadataWithAdapters(
    "https://blog.naver.com/rate-limited",
    fakeAdapters(transport),
  ));
  assertEquals(error.code, "RATE_LIMITED");
  assertEquals(error.retryable, true);
  assertEquals(error.retryAfterSeconds, 3600);
  assertEquals(transport.calls.length, 1);
});

Deno.test("enriched preparation preserves user fields, active OCR, and owner categories", () => {
  const snapshot = itemSnapshot();
  const prepared = prepareEnrichedSnapshot(snapshot, {
    fetched_title: "새 여행 제목",
    description: "제주 숙소와 카페를 정리한 공개 글",
    body_text: null,
    metadata_state: "ready",
    extraction_meta: {
      adapter_version: "naver-metadata-v1",
      final_url: "https://m.blog.naver.com/owner/post",
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
      last_checked_at: CHECKED_AT.toISOString(),
      error_code: null,
    },
  });

  assertEquals(prepared.raw_fetched_title, "새 여행 제목");
  assertEquals(prepared.raw_body_text, null);
  assertEquals(prepared.normalized_fields.user_title, "사용자 카톡 제목");
  assertEquals(prepared.normalized_fields.note, "기존 안드로이드 메모");
  assertEquals(
    prepared.normalized_fields.ocr,
    "활성 이미지 속 excel xlookup 정보",
  );
  assertEquals(prepared.normalized_fields.categories, "업무 자료 여행");
  assertEquals(prepared.normalized_fields.body, "검증된 기존 본문");
  assertEquals(prepared.concept_index.note, ["android"]);
  assertEquals(prepared.concept_index.ocr, ["excel"]);
  assertEquals(prepared.cue_state, "available");
});

Deno.test("enriched preparation truncates only raw metadata and OCR patch fields", () => {
  const prepared = prepareEnrichedSnapshot(itemSnapshot(), {
    fetched_title: "ﬃ".repeat(301),
    description: "가".repeat(4001),
    body_text: "나".repeat(20001),
    metadata_state: "ready",
    extraction_meta: {},
    ocr_text: "다".repeat(20001),
    ocr_state: "ready",
  });

  assertEquals([...prepared.raw_fetched_title!].length, 300);
  assertEquals([...prepared.raw_description!].length, 4000);
  assertEquals([...prepared.raw_body_text!].length, 20000);
  assertEquals([...prepared.raw_ocr_text!].length, 20000);
  assertEquals(prepared.raw_fetched_title!.endsWith("ﬃ"), true);
  assertEquals(prepared.extraction_meta.title_truncated, true);
  assertEquals(prepared.extraction_meta.description_truncated, true);
  assertEquals(prepared.extraction_meta.body_truncated, true);
  assertEquals(prepared.ocr_truncated, true);
  assertEquals([...prepared.normalized_fields.fetched_title].length, 900);
});

Deno.test("automatic metadata eligibility is Naver-only without restricting ordinary URL saves", async () => {
  const naver = await prepareItem({ url: "https://blog.naver.com/owner/post" });
  const mobile = await prepareItem({
    url: "https://m.blog.naver.com/owner/post",
  });
  const instagram = await prepareItem({ url: "https://www.instagram.com/p/1" });
  const ordinary = await prepareItem({ url: "http://example.com/article" });

  assertEquals(naver.metadata_allowed, true);
  assertEquals(mobile.metadata_allowed, true);
  assertEquals(instagram.metadata_allowed, false);
  assertEquals(ordinary.metadata_allowed, false);
  assertEquals(instagram.source, "instagram");
  assertEquals(ordinary.source, "other");
});

function itemSnapshot(): ItemUpdateSnapshot {
  return {
    version: 7,
    url: "https://blog.naver.com/owner/post",
    user_title: "사용자 카톡 제목",
    fetched_title: "이전 확보 제목",
    shared_text: "공유된 원문 단서",
    description: "이전 설명",
    body_text: "검증된 기존 본문",
    note: "기존 안드로이드 메모",
    category_refs: [
      { id: "11111111-1111-4111-8111-111111111111", name: "업무 자료" },
      { id: "22222222-2222-4222-8222-222222222222", name: "여행" },
    ],
    metadata_state: "running",
    ocr_state: "ready",
    extraction_meta: {
      title_truncated: false,
      description_truncated: false,
      body_truncated: false,
    },
    active_asset: {
      ocr_text: "활성 이미지 속 excel xlookup 정보",
      ocr_truncated: false,
    },
  };
}
