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
import { isMetadataUrlEligible } from "./metadata-url.ts";

const CHECKED_AT = new Date("2026-09-15T12:00:00.000Z");
const PUBLIC_V4: ResolvedAddress = { address: "223.130.200.104", family: 4 };
const encoder = new TextEncoder();

class FakeDns implements MetadataDnsResolver {
  readonly calls: string[] = [];

  constructor(
    private readonly answers: Record<string, ResolvedAddress[] | Error> = {
      "blog.naver.com": [PUBLIC_V4],
      "m.blog.naver.com": [PUBLIC_V4],
      "example.com": [PUBLIC_V4],
      "www.example.com": [PUBLIC_V4],
      "threads.net": [PUBLIC_V4],
      "www.threads.com": [PUBLIC_V4],
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

Deno.test("production metadata DNS and pinned HTTP/HTTPS transport adapters exist", () => {
  assert(typeof productionMetadataDnsResolver.resolve === "function");
  assert(typeof productionMetadataTransport.request === "function");
});

Deno.test("production pinned transport validates addresses and owns body lifetime", async () => {
  for (const protocol of ["http:", "https:"]) {
    for (
      const addresses of [
        [],
        [{ address: "127.0.0.1", family: 4 as const }],
        [{ address: "fc00::1", family: 6 as const }],
        [PUBLIC_V4, { address: "10.0.0.1", family: 4 as const }],
      ]
    ) {
      let rejected = false;
      try {
        await productionMetadataTransport.request({
          url: new URL(`${protocol}//example.com/post`),
          addresses,
          signal: new AbortController().signal,
        });
      } catch (error) {
        rejected = error instanceof TypeError;
      }
      assertEquals(rejected, true);
    }
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

Deno.test("a complete keep-alive response releases its owned client after parsing", async () => {
  const owner = new FakeResponseOwner();
  const body = Readable.from([
    encoder.encode("<html><head><title>Complete article</title>"),
    encoder.encode(
      '<meta name="description" content="The entire response arrived"></head></html>',
    ),
  ]);
  const transport = new FakeTransport([
    ownUndiciMetadataResponse({
      statusCode: 200,
      headers: { "content-type": "text/html", connection: "keep-alive" },
      body,
    }, owner),
  ]);
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(transport),
  );
  assertEquals(result.metadata_state, "ready");
  assertEquals(result.fetched_title, "Complete article");
  assertEquals(result.description, "The entire response arrived");
  assertEquals(owner.destroyErrors, [null]);
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
      adapter_version: "public-page-metadata-v1",
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

Deno.test("Threads document title excludes navigation SVG titles and later body titles", async () => {
  const result = await fetchMetadataWithAdapters(
    "https://www.threads.com/@nasa/post/sample",
    fakeAdapters(
      new FakeTransport([htmlResponse(`<!doctype html><html><head>
      <title>A postcard from Mars!</title>
      <meta property="og:title" content="NASA (@nasa) on Threads">
      <meta property="og:description" content="A postcard from Mars! A rover panorama.">
    </head><body>
      <svg><g><title>Home</title><path /></g></svg>
      <svg><title>Search</title></svg>
      <math><title>Notifications</title></math>
      <title>Profile</title>
    </body></html>`)]),
    ),
  );
  assertEquals(result.fetched_title, "A postcard from Mars!");
  assertEquals(result.description, "A postcard from Mars! A rover panorama.");
  assertEquals(result.metadata_state, "ready");
});

Deno.test("icon-only titles never override descriptions or contaminate headings", async () => {
  for (
    const heading of [
      "",
      "<h1>A useful heading<svg><title>Share</title></svg></h1>",
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/article",
      fakeAdapters(
        new FakeTransport([htmlResponse(`<html><head>
        <meta name="description" content="A useful description">
      </head><body><svg><title>Home</title></svg>${heading}</body></html>`)]),
      ),
    );
    assertEquals(
      result.fetched_title,
      heading ? "A useful heading" : "A useful description",
    );
    assertEquals(result.metadata_state, "ready");
  }
});

Deno.test("only the first document title is used", async () => {
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(
      new FakeTransport([htmlResponse(
        '<title>Article title</title><title>Another title</title><meta name="description" content="Article description">',
      )]),
    ),
  );
  assertEquals(result.fetched_title, "Article title");
  assertEquals(result.description, "Article description");
});

Deno.test("generic public HTTP and HTTPS pages prefer OG over Twitter and HTML metadata", async () => {
  for (
    const url of [
      "http://example.com:80/article?ref=shared#section",
      "https://www.example.com:443/article?ref=shared#section",
    ]
  ) {
    const transport = new FakeTransport([htmlResponse(`<!doctype html>
      <title>HTML article title</title>
      <meta name="twitter:title" content="Twitter article title">
      <meta property="og:title" content="Public research &amp; notes">
      <meta name="description" content="HTML description">
      <meta name="twitter:description" content="Twitter description">
      <meta property="og:description" content="Useful public article description">
      <article><p>Body text must not be extracted.</p></article>`)]);
    const dns = new FakeDns();
    const result = await fetchMetadataWithAdapters(
      url,
      fakeAdapters(transport, dns),
    );

    assertEquals(result.fetched_title, "Public research & notes");
    assertEquals(result.description, "Useful public article description");
    assertEquals(result.body_text, null);
    assertEquals(result.metadata_state, "ready");
    assertEquals(result.extraction_meta.final_url, new URL(url).href);
    assertEquals(dns.calls, [new URL(url).hostname]);
    assertEquals(transport.calls[0].url.protocol, new URL(url).protocol);
    assertEquals(transport.calls[0].addresses, [PUBLIC_V4]);
  }
});

Deno.test("meaningful Twitter and HTML metadata replace generic higher-priority fields", async () => {
  const cases = [
    {
      html: `<title>HTML fallback title</title>
        <meta property="og:title" content="Home">
        <meta name="twitter:title" content="Twitter research title">
        <meta property="og:description" content="Login">
        <meta name="twitter:description" content="Twitter research description">`,
      title: "Twitter research title",
      description: "Twitter research description",
    },
    {
      html: `<title>HTML research title &amp; notes</title>
        <meta property="og:site_name" content="Publication">
        <meta property="og:title" content="Publication">
        <meta name="description" content="HTML research description">`,
      title: "HTML research title & notes",
      description: "HTML research description",
    },
  ];
  for (const testCase of cases) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/research",
      fakeAdapters(new FakeTransport([htmlResponse(testCase.html)])),
    );
    assertEquals(result.fetched_title, testCase.title);
    assertEquals(result.description, testCase.description);
    assertEquals(result.metadata_state, "ready");
    assertEquals(result.body_text, null);
  }
});

for (
  const testCase of [
    {
      name: "X account-only title",
      url: "https://x.com/NASAWebb/status/123",
      title: "NASAWebb",
      description: "Webb detected a distant world. More observations follow.",
      expected: "Webb detected a distant world.",
    },
    {
      name: "Facebook account title",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA",
      description: "First stop, Mars. Next stop, Psyche.",
      expected: "First stop, Mars.",
    },
    {
      name: "Bluesky profile title",
      url: "https://bsky.app/profile/stsci.edu/post/123",
      title: "Space Telescope Science Institute (@stsci.edu)",
      description: "A new telescope is ready!\n\nRead the launch notes.",
      expected: "A new telescope is ready!",
    },
    {
      name: "federated Mastodon profile title",
      url: "https://science.example.com/@astronomer/123",
      title: "Astronomer (@astronomer@science.example.com)",
      description:
        "Astronomers asked the public for help.\nThe results are here.",
      expected: "Astronomers asked the public for help.",
    },
    {
      name: "Instagram caption wrapper",
      url: "https://www.instagram.com/p/123/",
      title: "NASA on Instagram: &quot;Stopped by Mars, took some photos&quot;",
      description:
        "1,234 likes, 56 comments - nasa on May 15: &quot;Stopped by Mars, took some photos&quot;",
      expected: "Stopped by Mars, took some photos",
      expectedDescription: "Stopped by Mars, took some photos",
    },
    {
      name: "Instagram author-only wrapper",
      url: "https://www.instagram.com/p/123/",
      title: "NASA on Instagram",
      description: "Mars looks beautiful today. See the panorama.",
      expected: "Mars looks beautiful today.",
    },
    {
      name: "genuine article title",
      url: "https://example.com/article",
      title: "NASA",
      description: "A history of the space agency. Read more here.",
      expected: "NASA",
    },
    {
      name: "useful short social title",
      url: "https://x.com/NASA/status/123",
      title: "Liftoff!",
      description: "The rocket launched today. Follow the mission.",
      expected: "Liftoff!",
    },
    {
      name: "article discussing a social service is not a caption wrapper",
      url: "https://example.com/article",
      title: "Research on Instagram: New findings",
      description: "An independent analysis of social networks.",
      expected: "Research on Instagram: New findings",
    },
    {
      name: "Unicode description fallback",
      url: "https://bsky.app/profile/space.example/post/123",
      title: "Bluesky",
      description: "제주에서 본 별빛😀。 다음 관측은 내일입니다.",
      expected: "제주에서 본 별빛😀。",
    },
    {
      name: "missing description does not invent an account post title",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA",
      description: null,
      expected: null,
    },
    {
      name: "Unicode title without description",
      url: "https://example.com/article",
      title: "별빛😀",
      description: null,
      expected: "별빛😀",
    },
    {
      name: "live X quoted title with service suffix",
      url: "https://x.com/NASAWebb/status/123",
      title: "&quot;Webb detected the strongest evidence yet!&quot; / X",
      description: "Webb detected the strongest evidence yet!",
      expected: "Webb detected the strongest evidence yet!",
    },
    {
      name: "live Facebook expanded account corroborated by post metadata",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - National Aeronautics and Space Administration",
      extraMeta:
        `<meta property="og:image:alt" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:url" content="https://www.facebook.com/NASA/posts/123/">`,
      description: "First stop, Mars. Next stop, Psyche.",
      expected: "First stop, Mars.",
    },
    {
      name: "Facebook article without account corroboration stays intact",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - A new mission launches",
      description: "First stop, Mars. Next stop, Psyche.",
      expected: "NASA - A new mission launches",
    },
    {
      name:
        "Facebook clipped HTML fallback after OG and Twitter account labels",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - National Aeronautics and Space Administration",
      documentTitle:
        "Firs... - NASA - National Aeronautics and Space Administration",
      extraMeta:
        `<meta name="twitter:title" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:image:alt" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:url" content="https://www.facebook.com/NASA/posts/123/">`,
      description: "First stop, Mars. Next stop, Psyche.",
      expected: "First stop, Mars.",
    },
    {
      name: "Facebook Korean account post wrapper uses description",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - National Aeronautics and Space Administration",
      documentTitle:
        "NASA - National Aeronautics and Space Administration님의 게시물",
      extraMeta:
        `<meta property="og:image:alt" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:url" content="https://www.facebook.com/NASA/posts/123/">`,
      description: "First stop, Mars. Next stop, Psyche.",
      expected: "First stop, Mars.",
    },
    {
      name:
        "Facebook Korean headline containing account post words is preserved",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - National Aeronautics and Space Administration",
      documentTitle:
        "NASA - National Aeronautics and Space Administration님의 게시물 분석",
      extraMeta:
        `<meta property="og:image:alt" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:url" content="https://www.facebook.com/NASA/posts/123/">`,
      description: "우주 탐사 소식을 분석합니다.",
      expected:
        "NASA - National Aeronautics and Space Administration님의 게시물 분석",
    },
    {
      name: "Facebook complete HTML headline with account suffix is preserved",
      url: "https://www.facebook.com/NASA/posts/123",
      title: "NASA - National Aeronautics and Space Administration",
      documentTitle:
        "First stop, Mars - NASA - National Aeronautics and Space Administration",
      extraMeta:
        `<meta property="og:image:alt" content="NASA - National Aeronautics and Space Administration">
        <meta property="og:url" content="https://www.facebook.com/NASA/posts/123/">`,
      description: "First stop, Mars. Next stop, Psyche.",
      expected:
        "First stop, Mars - NASA - National Aeronautics and Space Administration",
    },
    {
      name: "live Mastodon clipped quoted title uses complete description",
      url: "https://mastodon.social/@badastro/123",
      title:
        "Phil Plait: &quot;Astronomers turned to public crowdsourcing to hel…&quot; - Mastodon.social",
      description:
        "Astronomers turned to public crowdsourcing to help them find brown dwarfs in space. It, um, worked.",
      expected:
        "Astronomers turned to public crowdsourcing to help them find brown dwarfs in space.",
    },
    {
      name: "explicit author metadata identifies a display name",
      url: "https://x.com/NASAWebb/status/123",
      title: "NASA Webb Telescope",
      author: "NASA Webb Telescope",
      description: "A new view of the stars.\nMore details.",
      expected: "A new view of the stars.",
    },
  ]
) {
  Deno.test(`representative post titles: ${testCase.name}`, async () => {
    const hostname = new URL(testCase.url).hostname;
    const dns = new FakeDns({ [hostname]: [PUBLIC_V4] });
    const result = await fetchMetadataWithAdapters(
      testCase.url,
      fakeAdapters(
        new FakeTransport([htmlResponse(`
        <title>${testCase.documentTitle ?? testCase.title}</title>
        <meta property="og:title" content="${testCase.title}">
        ${testCase.extraMeta ?? ""}
        ${
          testCase.author
            ? `<meta name="author" content="${testCase.author}">`
            : ""
        }
        ${
          testCase.description === null
            ? ""
            : `<meta property="og:description" content="${testCase.description}">`
        }`)]),
        dns,
      ),
    );
    assertEquals(dns.calls, [hostname]);
    assertEquals(result.fetched_title, testCase.expected);
    assertEquals(
      result.description,
      testCase.expectedDescription ?? testCase.description,
    );
    assertEquals(result.body_text, null);
    assertEquals(
      result.metadata_state,
      testCase.description === null ? "partial" : "ready",
    );
  });
}

Deno.test("Threads generic site and account titles use a bounded meaningful description first line", async () => {
  const firstLine = "여행😀".repeat(41);
  for (
    const title of [
      "Threads",
      "Threads • Say more",
      "Threads.net",
      "Writer (@writer) on Threads",
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://www.threads.com/@writer/post/123",
      fakeAdapters(
        new FakeTransport([htmlResponse(`
        <title>${title}</title>
        <meta property="og:description" content="${firstLine}&#10;Second line must not enter the display title.">`)]),
      ),
    );
    assertEquals(result.fetched_title, [...firstLine].slice(0, 120).join(""));
    assertEquals([...result.fetched_title!].length, 120);
    assertEquals(
      result.description,
      `${firstLine}\nSecond line must not enter the display title.`,
    );
    assertEquals(result.metadata_state, "ready");
    assertEquals(result.extraction_meta.title_truncated, true);
    assertEquals(result.body_text, null);
  }
});

Deno.test("Threads service descriptions and generic first lines do not manufacture titles", async () => {
  for (
    const description of [
      "Join Threads to share ideas and connect with others.",
      "Say more with Threads — a place to share your thoughts.",
      "Threads",
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://threads.net/@writer/post/123",
      fakeAdapters(
        new FakeTransport([htmlResponse(`
        <title>Threads</title><meta name="description" content="${description}">`)]),
      ),
    );
    assertEquals(result.fetched_title, null);
    assertEquals(result.description, null);
    assertEquals(result.metadata_state, "partial");
    assertEquals(result.extraction_meta.error_code, "INVALID_RESULT");
  }

  const result = await fetchMetadataWithAdapters(
    "https://threads.net/@writer/post/123",
    fakeAdapters(
      new FakeTransport([htmlResponse(`
      <title>Threads</title><meta name="description" content="Threads&#10;Actual public information follows.">`)]),
    ),
  );
  assertEquals(result.fetched_title, null);
  assertEquals(
    result.description,
    "Threads\nActual public information follows.",
  );
  assertEquals(result.metadata_state, "partial");
});

Deno.test("partial title-only metadata and missing metadata never invent descriptions or body text", async () => {
  for (
    const testCase of [
      {
        html: "<title>A useful public article</title>",
        title: "A useful public article",
      },
      {
        html: "<article><p>Visible prose is not metadata.</p></article>",
        title: null,
      },
      {
        html: '<title>Home</title><meta name="description" content="Home">',
        title: null,
      },
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/article",
      fakeAdapters(new FakeTransport([htmlResponse(testCase.html)])),
    );
    assertEquals(result.fetched_title, testCase.title);
    assertEquals(result.description, null);
    assertEquals(result.body_text, null);
    assertEquals(result.metadata_state, "partial");
    assertEquals(result.extraction_meta.error_code, "INVALID_RESULT");
  }
});

Deno.test("description-only HTML metadata supplies an untruncated first-line display title", async () => {
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(
      new FakeTransport([htmlResponse(`
      <meta name="description" content="  Public &amp; useful first line&#13;&#10;A second line.  ">`)]),
    ),
  );
  assertEquals(result.fetched_title, "Public & useful first line");
  assertEquals(
    result.description,
    "Public & useful first line\nA second line.",
  );
  assertEquals(result.extraction_meta.title_truncated, false);
  assertEquals(result.metadata_state, "ready");
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

Deno.test("login, challenge, and soft-error documents cannot expose otherwise useful OG metadata", async () => {
  const usefulMetadata =
    `<meta property="og:title" content="An attractive public article">
    <meta property="og:description" content="A useful article description">`;
  for (
    const testCase of [
      {
        html: "<title>Sign in | Publication</title>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<title>Publication • Log in</title>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<h1>Log in to Threads</h1>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: '<form action="/signin"><input type="password"></form>',
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<title>Just a moment...</title>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: '<form id="challenge-form"></form>',
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: '<div class="cf-chl-widget">Verification required</div>',
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<h1>Verify you are human</h1>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<title>CAPTCHA</title>",
        code: "ACCESS_DENIED",
        state: "partial",
      },
      {
        html: "<title>404 Not Found</title>",
        code: "INVALID_CONTENT",
        state: "failed",
      },
      {
        html: "<h1>Page not found • Publication</h1>",
        code: "INVALID_CONTENT",
        state: "failed",
      },
      {
        html: "<h1>Sorry, this page isn't available.</h1>",
        code: "INVALID_CONTENT",
        state: "failed",
      },
      {
        html: "<title>Service unavailable</title>",
        code: "INVALID_CONTENT",
        state: "failed",
      },
      {
        html: "<title>Something went wrong</title>",
        code: "INVALID_CONTENT",
        state: "failed",
      },
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/article",
      fakeAdapters(
        new FakeTransport([htmlResponse(`${usefulMetadata}${testCase.html}`)]),
      ),
    );
    assertEquals(result.metadata_state, testCase.state, testCase.html);
    assertEquals(
      result.extraction_meta.error_code,
      testCase.code,
      testCase.html,
    );
    assertEquals(result.fetched_title, null, testCase.html);
    assertEquals(result.description, null, testCase.html);
    assertEquals(result.body_text, null, testCase.html);
  }
});

Deno.test("login-related article titles, prose, and ordinary captcha widgets are not access walls", async () => {
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(
      new FakeTransport([htmlResponse(`
      <title>Login API implementation guide</title>
      <meta name="description" content="How to implement sign in and handle errors safely.">
      <h2>Login API example</h2>
      <div class="g-recaptcha"></div>
      <script>challenge-form access-denied</script>`)]),
    ),
  );
  assertEquals(result.fetched_title, "Login API implementation guide");
  assertEquals(result.metadata_state, "ready");
});

Deno.test("article error, login, and challenge subsections do not override document metadata", async () => {
  for (const primaryHeading of ["", "<h1>HTTP authentication reference</h1>"]) {
    for (
      const heading of [
        "404",
        "Error",
        "오류",
        "Page not found",
        "Login",
        "로그인",
        "Sign in",
        "Access denied",
        "Verify you are human",
      ]
    ) {
      const result = await fetchMetadataWithAdapters(
        "https://example.com/article",
        fakeAdapters(
          new FakeTransport([htmlResponse(`
          <title>HTTP authentication reference</title>
          <meta property="og:title" content="Public authentication guide">
          <meta property="og:description" content="A public guide to authentication and HTTP failures.">
          <article>${primaryHeading}<h2>${heading}</h2><p>An ordinary article subsection.</p></article>`)]),
        ),
      );
      assertEquals(
        result.fetched_title,
        "Public authentication guide",
        heading,
      );
      assertEquals(
        result.description,
        "A public guide to authentication and HTTP failures.",
        heading,
      );
      assertEquals(result.metadata_state, "ready", heading);
      assertEquals(result.extraction_meta.error_code, null, heading);
    }
  }
});

Deno.test("title-less error and access documents still recognize their primary heading", async () => {
  for (const tag of ["h1", "h2"]) {
    for (
      const testCase of [
        { heading: "404", code: "INVALID_CONTENT" },
        { heading: "Error", code: "INVALID_CONTENT" },
        { heading: "오류", code: "INVALID_CONTENT" },
        { heading: "Login", code: "ACCESS_DENIED" },
        { heading: "Verify you are human", code: "ACCESS_DENIED" },
      ]
    ) {
      const result = await fetchMetadataWithAdapters(
        "https://example.com/article",
        fakeAdapters(
          new FakeTransport([
            htmlResponse(`<${tag}>${testCase.heading}</${tag}>`),
          ]),
        ),
      );
      assertEquals(result.extraction_meta.error_code, testCase.code);
      assertEquals(result.fetched_title, null);
      assertEquals(result.description, null);
    }
  }
});

Deno.test("login and JavaScript challenge description prompts are not public article metadata", async () => {
  for (
    const description of [
      "Log in to view this post.",
      "Please sign in to continue.",
      "You must log in to see the content.",
      "Enable JavaScript and cookies to continue.",
    ]
  ) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/article",
      fakeAdapters(
        new FakeTransport([htmlResponse(`
        <meta property="og:title" content="An attractive article title">
        <meta property="og:description" content="${description}">`)]),
      ),
    );
    assertEquals(result.fetched_title, null);
    assertEquals(result.description, null);
    assertEquals(result.extraction_meta.error_code, "ACCESS_DENIED");
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

Deno.test("encoded bodies, unknown charsets, malformed lengths, and empty bodies do not manufacture metadata", async () => {
  const invalidHeaders: Array<Record<string, string>> = [
    { "content-type": "text/html", "content-encoding": "gzip" },
    { "content-type": "text/html; charset=not-a-charset" },
    { "content-type": "text/html", "content-length": "1.5" },
    { "content-type": "text/html", "content-length": "-1" },
    { "content-type": "text/html", "content-length": "99999999999999999999" },
  ];
  for (const headers of invalidHeaders) {
    const result = await fetchMetadataWithAdapters(
      "https://example.com/article",
      fakeAdapters(
        new FakeTransport([
          htmlResponse("<title>Useful title</title>", headers),
        ]),
      ),
    );
    assertEquals(result.metadata_state, "failed");
    assertEquals(result.extraction_meta.error_code, "INVALID_CONTENT");
    assertEquals(result.fetched_title, null);
    assertEquals(result.description, null);
  }
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(
      new FakeTransport([{
        status: 200,
        headers: { "content-type": "text/html" },
        body: null,
      }]),
    ),
  );
  assertEquals(result.fetched_title, null);
  assertEquals(result.description, null);
  assertEquals(result.metadata_state, "partial");
});

Deno.test("general HTML encodings can supply metadata within the same resource limits", async () => {
  const prefix = encoder.encode("<title>Caf");
  const suffix = encoder.encode(
    ' guide</title><meta name="description" content="Public coffee guide">',
  );
  const bytes = new Uint8Array([...prefix, 0xe9, ...suffix]);
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(
      new FakeTransport([response(200, [bytes], {
        "content-type": "text/html; charset=windows-1252",
      })]),
    ),
  );
  assertEquals(result.fetched_title, "Café guide");
  assertEquals(result.description, "Public coffee guide");
  assertEquals(result.metadata_state, "ready");
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
  const longTitle = "봄" + "😀".repeat(300);
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

Deno.test("public URL eligibility accepts only matching lexical standard ports and DNS authorities", () => {
  for (
    const url of [
      "http://example.com/article",
      "http://example.com:80/article",
      "HTTPS://EXAMPLE.COM:443/article",
      "https://blog.naver.com/owner/post",
      "https://www.threads.com/@writer/post/123",
      "https://xn--9n2bp8q.com/article",
      "https://public.local.example.com/article",
      "https://example.com/article?next=http%3A%2F%2F127.0.0.1#section",
      `https://example.com/${"a".repeat(4096 - "https://example.com/".length)}`,
    ]
  ) {
    assertEquals(isMetadataUrlEligible(url), true, url);
  }
});

Deno.test("Unicode URL scalar limits do not become encoded transport or provenance limits", async () => {
  const prefix = "https://example.com/";
  for (const character of ["가", "😀"]) {
    const rawUrl = prefix + character.repeat(4096 - prefix.length);
    assertEquals([...rawUrl].length, 4096);
    assertEquals(isMetadataUrlEligible(rawUrl), true);
    assertEquals(isMetadataUrlEligible(rawUrl + character), false);
    const parsed = new URL(rawUrl);
    assert(parsed.href.length > 4096);

    let transportFailure: unknown;
    try {
      await productionMetadataTransport.request({
        url: parsed,
        addresses: [],
        signal: new AbortController().signal,
      });
    } catch (error) {
      transportFailure = error;
    }
    assert(transportFailure instanceof TypeError);
    assertEquals(
      transportFailure.message,
      "Metadata transport requires validated addresses.",
    );

    const transport = new FakeTransport([htmlResponse(
      '<title>A Unicode URL article</title><meta name="description" content="Public Unicode article description">',
    )]);
    const result = await fetchMetadataWithAdapters(
      rawUrl,
      fakeAdapters(transport),
    );
    assertEquals(result.metadata_state, "ready");
    assertEquals(result.fetched_title, "A Unicode URL article");
    assertEquals(result.extraction_meta.final_url, null);
    assertEquals(transport.calls[0].url.href, parsed.href);

    const denied = await fetchMetadataWithAdapters(
      rawUrl,
      fakeAdapters(new FakeTransport([response(403)])),
    );
    assertEquals(denied.extraction_meta.error_code, "ACCESS_DENIED");
    assertEquals(denied.extraction_meta.final_url, null);
  }
});

Deno.test("unsupported schemes, ports, userinfo, local hosts, and literal IPs never reach DNS", async () => {
  const urls = [
    "ftp://blog.naver.com/post",
    "https://blog.naver.com:444/post",
    "https://user:secret@blog.naver.com/post",
    "https://@example.com/post",
    "http://example.com:443/post",
    "https://example.com:80/post",
    "https://example.com:0443/post",
    "http://example.com:080/post",
    "https://example.com:/post",
    "https://example.com./post",
    "https://localhost/post",
    "https://metadata.internal/post",
    "https://metadata.local/post",
    "https://metadata.localdomain/post",
    "https://metadata.localhost/post",
    "https://metadata.home/post",
    "https://home.arpa/post",
    "https://metadata.home.arpa/post",
    "https://metadata.lan/post",
    "https://metadata.test/post",
    "https://metadata.invalid/post",
    "https://metadata.example/post",
    "https://metadata.onion/post",
    "https://single-label/post",
    "https://-invalid.example.com/post",
    "https://example..com/post",
    "https://example.0x/post",
    "https://example.123/post",
    "https://%65xample.com/post",
    "https://예시.com/post",
    "https://K.example.com/post",
    "https://127.0.0.1/post",
    "https://8.8.8.8/post",
    "https://2130706433/post",
    "https://0x7f000001/post",
    "https://127.1/post",
    "https://[::1]/post",
    "https://[2606:4700:4700::1111]/post",
    " https://blog.naver.com/post",
    "https://example.com/a b",
    "https://example.com/\u0000",
    "https://example.com/\u007f",
    "https://example.com/\\private",
    "https:example.com/post",
    `https://example.com/${"a".repeat(4096)}`,
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
    assertEquals(isMetadataUrlEligible(url), false, url);
  }
});

Deno.test("private DNS answers are rejected before the pinned transport", async () => {
  for (
    const privateAddress of [
      { address: "0.0.0.0", family: 4 as const },
      { address: "10.0.0.7", family: 4 as const },
      { address: "100.64.0.1", family: 4 as const },
      { address: "127.0.0.1", family: 4 as const },
      { address: "169.254.169.254", family: 4 as const },
      { address: "172.16.0.1", family: 4 as const },
      { address: "192.168.0.1", family: 4 as const },
      { address: "192.0.0.1", family: 4 as const },
      { address: "192.0.2.1", family: 4 as const },
      { address: "198.18.0.1", family: 4 as const },
      { address: "198.51.100.1", family: 4 as const },
      { address: "203.0.113.1", family: 4 as const },
      { address: "224.0.0.1", family: 4 as const },
      { address: "255.255.255.255", family: 4 as const },
      { address: "::1", family: 6 as const },
      { address: "::ffff:127.0.0.1", family: 6 as const },
      { address: "fe80::1", family: 6 as const },
      { address: "fc00::1", family: 6 as const },
      { address: "2001::1", family: 6 as const },
      { address: "2001:20::1", family: 6 as const },
      { address: "2001:db8::1", family: 6 as const },
      { address: "2002:7f00:1::1", family: 6 as const },
      { address: "3fff::1", family: 6 as const },
      { address: "ff02::1", family: 6 as const },
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

Deno.test("mixed public/private and malformed DNS answers fail closed for generic hosts", async () => {
  for (
    const answers of [
      [PUBLIC_V4, { address: "10.0.0.1", family: 4 as const }],
      [PUBLIC_V4, { address: "::1", family: 6 as const }],
      [{ address: "223.130.200.104", family: 6 as const }],
      [{ address: "not-an-address", family: 4 as const }],
    ]
  ) {
    const transport = new FakeTransport([]);
    const result = await fetchMetadataWithAdapters(
      "http://example.com/article",
      fakeAdapters(transport, new FakeDns({ "example.com": answers })),
    );
    assertEquals(result.metadata_state, "unsupported");
    assertEquals(result.fetched_title, null);
    assertEquals(result.description, null);
    assertEquals(transport.calls, []);
  }
});

Deno.test("public IPv6 DNS answers are handed to the pinned transport unchanged", async () => {
  const address: ResolvedAddress = {
    address: "2606:4700:4700::1111",
    family: 6,
  };
  const transport = new FakeTransport([htmlResponse(
    '<title>Public IPv6 article</title><meta name="description" content="A useful IPv6 article description">',
  )]);
  const result = await fetchMetadataWithAdapters(
    "https://example.com/article",
    fakeAdapters(transport, new FakeDns({ "example.com": [address] })),
  );
  assertEquals(result.metadata_state, "ready");
  assertEquals(transport.calls[0].addresses, [address]);
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

Deno.test("raw URLs and redirects reject every C0 control, DEL, whitespace and backslash", async () => {
  const forbidden = [
    ...Array.from({ length: 32 }, (_, code) => String.fromCharCode(code)),
    String.fromCharCode(127),
    " ",
    "\u00a0",
    "\u2028",
    "\ufeff",
    "\\",
  ];
  for (const character of forbidden) {
    const label = `codepoint ${character.codePointAt(0)}`;
    const path = `/before${character}after`;
    const dns = new FakeDns();
    const initialTransport = new FakeTransport([]);
    const initial = await fetchMetadataWithAdapters(
      `https://example.com${path}`,
      fakeAdapters(initialTransport, dns),
    );
    assertEquals(initial.metadata_state, "unsupported", label);
    assertEquals(dns.calls, [], label);
    assertEquals(initialTransport.calls.length, 0, label);

    const redirectTransport = new FakeTransport([
      response(302, [], { location: path }),
    ]);
    const redirected = await fetchMetadataWithAdapters(
      "https://example.com/start",
      fakeAdapters(redirectTransport),
    );
    assertEquals(redirected.metadata_state, "unsupported", label);
    assertEquals(redirectTransport.calls.length, 1, label);
  }
  assertEquals(isMetadataUrlEligible("https://example.com/한글😀?q=%20"), true);
});

Deno.test("three validated redirects may cross public hosts and HTTP/HTTPS boundaries", async () => {
  const transport = new FakeTransport([
    response(301, [], { location: "https://www.example.com:443/one" }),
    response(302, [], { location: "//example.com/two" }),
    response(307, [], { location: "http://example.com:80/article" }),
    htmlResponse(
      '<title>Redirected public article</title><meta name="description" content="A useful public description">',
    ),
  ]);
  const dns = new FakeDns();
  const result = await fetchMetadataWithAdapters(
    "http://example.com/start",
    fakeAdapters(transport, dns),
  );
  assertEquals(result.metadata_state, "ready");
  assertEquals(result.extraction_meta.final_url, "http://example.com/article");
  assertEquals(dns.calls, [
    "example.com",
    "www.example.com",
    "example.com",
    "example.com",
  ]);
  assertEquals(transport.calls.map((call) => call.url.protocol), [
    "http:",
    "https:",
    "https:",
    "http:",
  ]);
  assertEquals(transport.calls.length, 4);
});

Deno.test("redirect length budgets use raw Location scalars rather than expanded or prefixed URLs", async () => {
  for (const prefix of ["https://example.com/", "//example.com/", "/"]) {
    const location = prefix + "😀".repeat(4096 - prefix.length);
    const transport = new FakeTransport([
      response(302, [], { location }),
      htmlResponse(
        '<title>A redirected Unicode article</title><meta name="description" content="Public article description">',
      ),
    ]);
    const result = await fetchMetadataWithAdapters(
      "https://example.com/start",
      fakeAdapters(transport),
    );
    assertEquals(result.metadata_state, "ready", prefix);
    assertEquals(result.extraction_meta.final_url, null, prefix);
    assertEquals(transport.calls.length, 2, prefix);
    assert(transport.calls[1].url.href.length > 4096);

    const tooLongTransport = new FakeTransport([
      response(302, [], { location: location + "😀" }),
    ]);
    const rejected = await fetchMetadataWithAdapters(
      "https://example.com/start",
      fakeAdapters(tooLongTransport),
    );
    assertEquals(rejected.metadata_state, "unsupported", prefix);
    assertEquals(tooLongTransport.calls.length, 1, prefix);
  }
});

Deno.test("redirects cannot normalize unsafe authorities or leave the public URL policy", async () => {
  for (
    const location of [
      "http://127.0.0.1/private",
      "https://8.8.8.8/private",
      "http://metadata.home.arpa/private",
      "https://metadata.internal/private",
      "http://example.com:443/private",
      "https://example.com:0443/private",
      "https://example.com:/private",
      "https://example.com./private",
      "https://user:secret@example.com/private",
      "https://@example.com/private",
      "https://%65xample.com/private",
      "//example.com:0443/private",
      "//metadata.local/private",
      "https:example.com/private",
      "ftp://example.com/private",
      " https://example.com/private",
      "https://example.com/\\private",
    ]
  ) {
    let cancellations = 0;
    const redirect = response(302, [], { location });
    redirect.cancel = () => cancellations++;
    const transport = new FakeTransport([redirect]);
    const dns = new FakeDns();
    const result = await fetchMetadataWithAdapters(
      "https://example.com/start",
      fakeAdapters(transport, dns),
    );
    assertEquals(result.metadata_state, "unsupported", location);
    assertEquals(
      result.extraction_meta.error_code,
      "INVALID_CONTENT",
      location,
    );
    assertEquals(result.fetched_title, null, location);
    assertEquals(result.description, null, location);
    assertEquals(dns.calls, ["example.com"], location);
    assertEquals(transport.calls.length, 1, location);
    assertEquals(cancellations, 1, location);
  }
});

Deno.test("same-host redirects repeat DNS validation instead of reusing the first public answer", async () => {
  const calls: string[] = [];
  const dns: MetadataDnsResolver = {
    resolve(hostname) {
      calls.push(hostname);
      return Promise.resolve(
        calls.length === 1
          ? [PUBLIC_V4]
          : [{ address: "169.254.169.254", family: 4 }],
      );
    },
  };
  const transport = new FakeTransport([
    response(302, [], { location: "/private" }),
  ]);
  const result = await fetchMetadataWithAdapters(
    "http://example.com/start",
    fakeAdapters(transport, dns),
  );
  assertEquals(result.metadata_state, "unsupported");
  assertEquals(calls, ["example.com", "example.com"]);
  assertEquals(transport.calls.length, 1);
});

Deno.test("redirect responses without a location fail without fetching another document", async () => {
  const transport = new FakeTransport([response(302)]);
  const result = await fetchMetadataWithAdapters(
    "https://example.com/start",
    fakeAdapters(transport),
  );
  assertEquals(result.metadata_state, "failed");
  assertEquals(result.extraction_meta.error_code, "INVALID_CONTENT");
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

Deno.test("generic transport and interrupted-body failures do not return partial unverified metadata", async () => {
  let cancellations = 0;
  async function* failingBody(failure: Error): AsyncIterable<Uint8Array> {
    yield encoder.encode(
      '<title>Incomplete article</title><meta name="description" content="Unverified description">',
    );
    throw failure;
  }
  const transports = [
    new FakeTransport([() => Promise.reject(new Error("Connection reset."))]),
    new FakeTransport([{
      status: 200,
      headers: { "content-type": "text/html" },
      body: failingBody(new Error("Interrupted response.")),
      cancel: () => cancellations++,
    }]),
    new FakeTransport([{
      status: 200,
      headers: { "content-type": "text/html" },
      body: failingBody(
        new Deno.errors.UnexpectedEof(
          "peer closed connection without sending TLS close_notify",
        ),
      ),
      cancel: () => cancellations++,
    }]),
  ];
  for (const transport of transports) {
    const error = await metadataFailure(fetchMetadataWithAdapters(
      "http://example.com/article",
      fakeAdapters(transport),
    ));
    assertEquals(error.code, "NETWORK_ERROR");
    assertEquals(error.retryable, true);
    assertEquals(transport.calls.length, 1);
  }
  assertEquals(cancellations, 2);
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
      adapter_version: "public-page-metadata-v1",
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

Deno.test("automatic metadata eligibility supports public HTTP/HTTPS without restricting ordinary URL saves", async () => {
  const naver = await prepareItem({ url: "https://blog.naver.com/owner/post" });
  const mobile = await prepareItem({
    url: "https://m.blog.naver.com/owner/post",
  });
  const instagram = await prepareItem({ url: "https://www.instagram.com/p/1" });
  const ordinary = await prepareItem({ url: "http://example.com/article" });
  const privateUrl = await prepareItem({ url: "http://127.0.0.1/article" });
  const nonstandardPort = await prepareItem({
    url: "https://example.com:8443/article",
  });

  assertEquals(naver.metadata_allowed, true);
  assertEquals(mobile.metadata_allowed, true);
  assertEquals(instagram.metadata_allowed, true);
  assertEquals(ordinary.metadata_allowed, true);
  assertEquals(privateUrl.metadata_allowed, false);
  assertEquals(nonstandardPort.metadata_allowed, false);
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
