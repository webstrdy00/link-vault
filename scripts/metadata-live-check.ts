import { fetchMetadata } from "../supabase/functions/library-api/metadata-engine.ts";
import { classifySnapshot } from "../supabase/functions/library-api/discovery-engine.ts";

// Opt-in network checks against browser-verified public posts, not part of
// deterministic CI. A removed/changed/blocked post must fail, not silently pass.
const cases = [
  {
    service: "Threads / NASA",
    url: "https://www.threads.com/@nasa/post/DdXJKzbkqPg",
    excerpt: "You've got mail: a postcard from Mars!",
    titlePrefix: "You've got mail: a postcard from Mars!",
  },
  {
    service: "Threads / National Geographic",
    url: "https://www.threads.com/@natgeo/post/DezCi1foMVV",
    excerpt: "Say you don't believe in love at first sight?",
    titlePrefix: "Say you don't believe in love at first sight?",
  },
  {
    service: "Instagram",
    url: "https://www.instagram.com/p/DYkt640kyfV/",
    excerpt: "Stopped by Mars, took some photos",
  },
  {
    service: "X",
    url: "https://x.com/NASAWebb/status/1999132374647427401",
    excerpt: "Webb detected the strongest evidence yet",
  },
  {
    service: "Facebook",
    url:
      "https://www.facebook.com/NASA/posts/first-stop-mars-next-stop-psyche-on-may-15-our-psyche-spacecraft-swung-by-mars-o/1530907268404644",
    excerpt: "First stop, Mars. Next stop, Psyche",
  },
  {
    service: "Bluesky",
    url: "https://bsky.app/profile/stsci.edu/post/3mxysey6oqx2l",
    excerpt: "NASA's Nancy Grace Roman Space Telescope",
  },
  {
    service: "Mastodon",
    url: "https://mastodon.social/@badastro/116913268750685901",
    excerpt: "Astronomers turned to public crowdsourcing",
  },
  {
    service: "Instagram / 비짓제주",
    url: "https://www.instagram.com/visitjeju.kr/p/DPDtncZEueq/",
    excerpt: "가을에 제주여행 가고 싶은 사람",
    expectedCategory: "travel",
  },
];

let failures = 0;
for (const testCase of cases) {
  try {
    const result = await fetchMetadata(testCase.url);
    const categories = classifySnapshot({ ...result, text_revision: 1 })
      .categories.map((category) => category.code);
    const problems: string[] = [];
    if (
      testCase.expectedCategory &&
      !categories.includes(testCase.expectedCategory)
    ) {
      problems.push(`missing expected category=${testCase.expectedCategory}`);
    }
    if (!["ready", "partial"].includes(result.metadata_state)) {
      problems.push(`state=${result.metadata_state}`);
    }
    if (!result.fetched_title?.trim()) problems.push("missing title");
    if (!result.description?.includes(testCase.excerpt)) {
      problems.push("description does not contain browser-verified post text");
    }
    if (
      testCase.titlePrefix &&
      !result.fetched_title?.startsWith(testCase.titlePrefix)
    ) {
      problems.push("title does not start with the verified post text");
    }
    if (
      /HomeCreateSearch|MessagesNotifications|ProfileInsights/.test(
        result.fetched_title ?? "",
      )
    ) {
      problems.push("navigation labels contaminated the title");
    }
    if (result.extraction_meta.error_code !== null) {
      problems.push(`error=${result.extraction_meta.error_code}`);
    }
    if (problems.length > 0) failures++;
    console.log(JSON.stringify({
      service: testCase.service,
      url: testCase.url,
      passed: problems.length === 0,
      metadataState: result.metadata_state,
      title: result.fetched_title,
      descriptionExcerpt: result.description?.slice(0, 220) ?? null,
      bodyCaptured: result.body_text !== null,
      categories,
      problems,
    }));
  } catch {
    failures++;
    console.log(JSON.stringify({
      service: testCase.service,
      passed: false,
      problems: ["metadata invocation failed"],
    }));
  }
}
console.log(
  `${
    cases.length - failures
  }/${cases.length} public-post metadata/classifier checks passed; this does not verify database persistence, full-body capture, Android sharing, or production deployment.`,
);
if (failures > 0) Deno.exit(1);
