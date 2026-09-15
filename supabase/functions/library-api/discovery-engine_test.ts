import { assert, assertEquals, assertThrows } from "@std/assert";
import rawFixtures from "./discovery-fixtures.json" with { type: "json" };
import {
  classifyItem,
  classifySnapshot,
  type DiscoverySnapshot,
  explainAliases,
  explainClassification,
  getRulesMetadata,
  prepareSearch,
  QueryLimitError,
  type SearchPlan,
} from "./discovery-engine.ts";
import {
  buildIndex,
  cues,
  hasTerm,
  type IndexItem,
  type SearchField,
  type TopicField,
} from "./item-preparation.ts";

export interface ReferenceItem {
  title?: string;
  user_title?: string;
  fetched_title?: string;
  note?: string;
  ocr?: string;
  shared?: string;
  description?: string;
  body?: string;
  categories?: string;
  url?: string;
  ocr_active?: boolean;
  processing?: boolean;
  metadata_failed?: boolean;
  ocr_failed?: boolean;
  truncated?: boolean;
  manual_override?: boolean;
  manual_categories?: string[];
}

interface ClassificationFixture {
  id: string;
  item: ReferenceItem;
  expected: string[];
}

interface SearchFixture {
  id: string;
  q: string;
  item: ReferenceItem;
  expected: boolean;
  mode?: "literal" | "alias";
}

export interface DiscoveryFixtures {
  thresholds: {
    classification: { exact_set_cases: number };
    search: { cases: number };
  };
  classification: ClassificationFixture[];
  search: SearchFixture[];
}

export const discoveryFixtures = rawFixtures as DiscoveryFixtures;

const searchWeights: Record<SearchField, number> = {
  user_title: 10,
  fetched_title: 10,
  note: 6,
  ocr: 4,
  shared: 3,
  description: 3,
  body: 3,
  categories: 2,
  url: 1,
};
const searchFields = Object.keys(searchWeights) as SearchField[];

export interface ReferenceSearchMatch {
  mode: "literal" | "alias";
  bucket: 0 | 1;
  score: number;
}

export interface ReferenceRankItem extends ReferenceItem {
  id: string;
  saved_at?: string;
}

function adaptExperimentalItem(item: ReferenceItem): DiscoverySnapshot {
  return {
    user_title: item.user_title,
    // The frozen reference suite's experimental `title` is fetched metadata,
    // never a production field or a user-title substitute.
    fetched_title: item.fetched_title ?? item.title,
    note: item.note,
    ocr: item.ocr_active === false ? null : item.ocr,
    shared: item.shared,
    description: item.description,
    body: item.body,
  };
}

function referenceIndex(item: ReferenceItem) {
  return buildIndex({
    ...adaptExperimentalItem(item),
    categories: item.categories,
    url: item.url,
  });
}

export function scoreReferenceSearch(
  item: ReferenceItem,
  plan: SearchPlan,
  options: { aliases?: boolean } = {},
): ReferenceSearchMatch | null {
  const index = referenceIndex(item);
  let literalScore = 0;
  for (const term of plan.terms) {
    const hits = searchFields.filter((field) =>
      index.fields[field].includes(term)
    );
    if (hits.length === 0) {
      literalScore = -1;
      break;
    }
    literalScore += Math.max(...hits.map((field) => searchWeights[field]));
  }
  if (literalScore >= 0) {
    return { mode: "literal", bucket: 0, score: literalScore };
  }
  if (options.aliases === false) {
    return null;
  }

  let aliasScore = 0;
  for (const group of plan.groups) {
    const hits = searchFields.filter((field) => {
      if (group.kind === "literal") {
        return index.fields[field].includes(group.value);
      }
      return field !== "categories" && field !== "url" &&
        index.concepts[field as TopicField].includes(group.value);
    });
    if (hits.length === 0) {
      return null;
    }
    aliasScore += Math.max(...hits.map((field) => searchWeights[field]));
  }
  return { mode: "alias", bucket: 1, score: aliasScore };
}

export function rankReferenceSearch(
  items: ReferenceRankItem[],
  query: string,
  options: { aliases?: boolean } = {},
) {
  const plan = prepareSearch(query);
  return items
    .flatMap((item) => {
      const match = scoreReferenceSearch(item, plan, options);
      return match === null ? [] : [{
        id: item.id,
        saved_at: item.saved_at ?? "",
        match,
      }];
    })
    .sort((left, right) =>
      left.match.bucket - right.match.bucket ||
      right.match.score - left.match.score ||
      right.saved_at.localeCompare(left.saved_at) ||
      right.id.localeCompare(left.id)
    );
}

function referenceClassification(item: ReferenceItem): string[] {
  const manual = item.manual_categories ?? [];
  if (manual.length > 5) {
    throw new Error("CATEGORY_LIMIT");
  }
  if (item.manual_override) {
    return [...manual];
  }
  const automatic = classifyItem(adaptExperimentalItem(item)).categories.map(
    (category) => category.code,
  );
  return [
    ...manual,
    ...automatic.slice(0, Math.min(3, 5 - manual.length)),
  ];
}

function referenceCues(item: ReferenceItem) {
  const indexItem: IndexItem = {
    ...adaptExperimentalItem(item),
    processing: item.processing,
    metadata_failed: item.metadata_failed,
    ocr_failed: item.ocr_failed,
    truncated: item.truncated,
  };
  return cues(indexItem);
}

Deno.test("ports all 42 frozen classification fixtures", () => {
  assertEquals(discoveryFixtures.thresholds.classification.exact_set_cases, 42);
  assertEquals(discoveryFixtures.classification.length, 42);
  for (const fixture of discoveryFixtures.classification) {
    assertEquals(
      [...referenceClassification(fixture.item)].sort(),
      [...fixture.expected].sort(),
      fixture.id,
    );
  }
});

Deno.test("ports all 31 frozen search fixtures", () => {
  assertEquals(discoveryFixtures.thresholds.search.cases, 31);
  assertEquals(discoveryFixtures.search.length, 31);
  for (const fixture of discoveryFixtures.search) {
    const match = scoreReferenceSearch(
      fixture.item,
      prepareSearch(fixture.q),
    );
    assertEquals(match !== null, fixture.expected, fixture.id);
    if (fixture.mode !== undefined) {
      assertEquals(match?.mode, fixture.mode, fixture.id);
    }
  }
});

Deno.test("contract 01: direct matches rank before alias-only matches", () => {
  const ranked = rankReferenceSearch([
    { id: "A", note: "카카오톡", saved_at: "2020" },
    { id: "B", title: "카톡", saved_at: "2026" },
  ], "카카오톡");
  assertEquals(ranked[0].id, "A");
});

Deno.test("contract 02: repeated fields never double count a rule", () => {
  const result = classifyItem({
    fetched_title: "맛집",
    description: "맛집",
    ocr: "맛집",
  });
  assertEquals(result.categories[0].score, 3);
});

Deno.test("contract 03: synonymous classification forms are one group", () => {
  assertEquals(
    classifyItem({ fetched_title: "엑셀 excel" }).categories[0].score,
    3,
  );
});

Deno.test("contract 04: weak rules in separate fields do not qualify", () => {
  assertEquals(
    classifyItem({
      fetched_title: "카페",
      description: "메뉴",
      note: "커피",
    }).categories,
    [],
  );
});

Deno.test("contract 05: three weak rules in one field qualify", () => {
  assertEquals(
    classifyItem({ description: "카페 메뉴 커피" }).categories.map((item) =>
      item.code
    ),
    ["food"],
  );
});

Deno.test("contract 06: Latin substrings do not cross boundaries", () => {
  assertEquals(hasTerm("nosql", "sql"), false);
});

Deno.test("contract 07: registered Korean particles are supported", () => {
  assertEquals(hasTerm("레시피를", "레시피"), true);
});

Deno.test("contract 08: Korean compounds are not generally segmented", () => {
  assertEquals(hasTerm("산책", "책"), false);
});

Deno.test("contract 09: search does not infer free synonyms", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "x", title: "반려견 숙소" },
    ], "강아지 호텔"),
    [],
  );
});

Deno.test("contract 10: search does not infer visual content", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "x", title: "카톡프사" },
    ], "패션 코디"),
    [],
  );
});

Deno.test("contract 11: alias expansion can be disabled", () => {
  assertEquals(
    rankReferenceSearch(
      [
        { id: "x", title: "카톡" },
      ],
      "카카오톡",
      { aliases: false },
    ),
    [],
  );
});

Deno.test("contract 12: literal score uses only the highest field", () => {
  const match = rankReferenceSearch([
    { id: "x", title: "제주", note: "제주" },
  ], "제주")[0].match;
  assertEquals(match.score, 10);
});

Deno.test("contract 13: alias score uses only the highest field", () => {
  const match = rankReferenceSearch([
    { id: "x", title: "카톡", note: "카톡" },
  ], "카카오톡")[0].match;
  assertEquals(match.score, 10);
});

Deno.test("contract 14: empty search ranks newest then UUID descending", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "A", saved_at: "2020" },
      { id: "B", saved_at: "2026" },
      { id: "C", saved_at: "2026" },
    ], "").map((item) => item.id),
    ["C", "B", "A"],
  );
});

Deno.test("contract 15: query is limited to 200 normalized code points", () => {
  assertThrows(
    () => prepareSearch("가".repeat(201)),
    QueryLimitError,
    "QUERY_LIMIT",
  );
});

Deno.test("contract 16: query is limited to ten input terms", () => {
  assertThrows(
    () => prepareSearch("a b c d e f g h i j k"),
    QueryLimitError,
    "QUERY_LIMIT",
  );
});

Deno.test("contract 17: concept expansion is limited to ten groups", () => {
  assertThrows(
    () => prepareSearch("카톡프사 a b c d e f g h i"),
    QueryLimitError,
    "QUERY_LIMIT",
  );
});

Deno.test("contract 18: longest profile phrase is one concept", () => {
  assertEquals(prepareSearch("카카오톡 프로필 사진").groups.length, 2);
});

Deno.test("contract 19: explicit compound expands to two concepts", () => {
  assertEquals(prepareSearch("카톡프사").groups.length, 2);
});

Deno.test("contract 20: partial alias tokens stay literal", () => {
  assertEquals(prepareSearch("카카오톡방").groups, [
    { kind: "literal", value: "카카오톡방" },
  ]);
});

Deno.test("contract 21: width and case normalize identically", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "A", title: "ＸＬＯＯＫＵＰ" },
    ], "xlookup")[0].match.score,
    10,
  );
});

Deno.test("contract 22: generic metadata is not a useful cue", () => {
  assertEquals(referenceCues({ title: "Home" }).state, "missing");
});

Deno.test("contract 23: absent cues stay pending during processing", () => {
  assertEquals(referenceCues({ processing: true }).state, "pending");
});

Deno.test("contract 24: short useful cues remain limited", () => {
  assertEquals(referenceCues({ title: "카톡프사" }).state, "limited");
});

Deno.test("contract 25: a long note can make cues available", () => {
  assertEquals(
    referenceCues({
      note:
        "주말에 친구와 서울 전시회를 보고 근처 식당에서 저녁을 먹기 위해 저장한 정보이며 장소와 시간을 확인할 예정",
    }).state,
    "available",
  );
});

Deno.test("contract 26: available cue state is not required to classify", () => {
  assertEquals(
    classifyItem({ fetched_title: "전시" }).categories.map((item) => item.code),
    ["culture"],
  );
});

Deno.test("contract 27: inactive OCR contributes neither text nor aliases", () => {
  assertEquals(
    referenceIndex({ ocr: "카톡", ocr_active: false }).concepts.ocr,
    [],
  );
});

Deno.test("contract 28: author-only social heading has no topic", () => {
  assertEquals(
    referenceCues({ title: "Author (@someone) on Threads" }).state,
    "missing",
  );
});

Deno.test("contract 29: URL remains available to literal search", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "A", url: "https://example.com/a_b?x=10%25" },
    ], "a_b")[0].id,
    "A",
  );
});

Deno.test("contract 30: custom category text never creates alias concepts", () => {
  assertEquals(
    rankReferenceSearch([
      { id: "A", categories: "카톡" },
    ], "카카오톡"),
    [],
  );
});

Deno.test("contract 31: a manually emptied selection stays empty", () => {
  assertEquals(
    referenceClassification({
      title: "여행",
      manual_override: true,
      manual_categories: [],
    }),
    [],
  );
});

Deno.test("contract 32: sealed category, alias, and rule IDs are unique", () => {
  const metadata = getRulesMetadata();
  assertEquals(metadata.categories.map((category) => category.code), [
    "travel",
    "food",
    "work",
    "shopping",
    "tools",
    "life",
    "culture",
  ]);
  assertEquals(metadata.aliases.map((alias) => alias.concept_id), [
    "kakaotalk",
    "profile_photo",
    "instagram",
    "threads",
    "excel",
    "android",
    "youtube",
    "reels",
  ]);
  assertEquals(
    new Set(metadata.categories.map((category) => category.code)).size,
    metadata.categories.length,
  );
  assertEquals(
    new Set(
      metadata.categories.flatMap((category) =>
        category.rules.map((rule) => rule.id)
      ),
    ).size,
    metadata.categories.flatMap((category) => category.rules).length,
  );
  const aliasExpressions = metadata.aliases.flatMap((alias) =>
    alias.expressions
  );
  assertEquals(new Set(aliasExpressions).size, aliasExpressions.length);
});

Deno.test("contract 33: database column is not an opinion column", () => {
  assertEquals(
    classifyItem({ fetched_title: "SQL column guide" }).categories.map((item) =>
      item.code
    ),
    ["work"],
  );
});

Deno.test("contract 34: opinion column is an explicit culture cue", () => {
  assertEquals(
    classifyItem({ fetched_title: "Opinion column" }).categories.map((item) =>
      item.code
    ),
    ["culture"],
  );
});

Deno.test("contract 35: higher score precedes the fixed category order", () => {
  assertEquals(
    classifyItem({
      fetched_title: "제주 여행용 카메라",
      note: "제품 비교 자료",
    }).categories.map((item) => item.code),
    ["shopping", "travel"],
  );
});

Deno.test("contract 36: publisher name in fetched title is not evidence", () => {
  assertEquals(
    classifyItem({
      fetched_title: "Travel Magazine on Instagram: 오늘의 한마디",
    }).categories,
    [],
  );
});

Deno.test("contract 37: caption after a publisher remains evidence", () => {
  assertEquals(
    classifyItem({
      fetched_title: "Author on Instagram: 스트레칭",
    }).categories.map((item) => item.code),
    ["life"],
  );
});

Deno.test("contract 38: social counts and publisher prefix are excluded", () => {
  assertEquals(
    classifyItem({
      description:
        "100 likes, 10 comments - travel on April 15, 2022: 오늘의 한마디",
    }).categories,
    [],
  );
});

Deno.test("contract 39: test adapter keeps custom categories and caps at five", () => {
  assertEquals(
    referenceClassification({
      title: "여행 맛집 엑셀",
      manual_categories: ["c1", "c2", "c3"],
    }),
    ["c1", "c2", "c3", "travel", "food"],
  );
});

Deno.test("contract 40: test adapter rejects six manual categories", () => {
  assertThrows(
    () =>
      referenceClassification({
        manual_categories: ["1", "2", "3", "4", "5", "6"],
      }),
    Error,
    "CATEGORY_LIMIT",
  );
});

Deno.test("user and fetched titles do not double a classification score", () => {
  const result = classifyItem({
    user_title: "맛집",
    fetched_title: "맛집",
  }, 8);
  assertEquals(result, {
    rules_version: "rules-v2.0.0",
    target_revision: 8,
    categories: [{
      code: "food",
      score: 3,
      rules: [{
        id: "food:strong:0",
        fields: ["user_title", "fetched_title"],
      }],
    }],
  });
});

Deno.test("equal scores use the sealed order and stop at three", () => {
  assertEquals(
    classifyItem({
      fetched_title: "#여행 #맛집 #엑셀 #스트레칭",
    }).categories.map((category) => category.code),
    ["travel", "food", "work"],
  );
});

Deno.test("rule IDs and fields follow sealed group and canonical field order", () => {
  const category = classifyItem({
    user_title: "카페 추천 메뉴",
    fetched_title: "coffee",
  }).categories[0];
  assertEquals(category.code, "food");
  assertEquals(category.rules, [
    { id: "food:strong:3", fields: ["user_title"] },
    { id: "food:weak:0", fields: ["user_title"] },
    { id: "food:weak:2", fields: ["user_title"] },
    { id: "food:weak:3", fields: ["fetched_title"] },
  ]);
});

Deno.test("classification ignores URL and category payload fields", () => {
  const result = classifySnapshot(
    {
      text_revision: 12,
      fetched_title: "Home",
      // Deliberate runtime excess properties prove that the explicit adapter
      // does not turn server presentation data into topic evidence.
      url: "https://example.com/맛집",
      categories: "여행",
    } as Parameters<typeof classifySnapshot>[0],
  );
  assertEquals(result.target_revision, 12);
  assertEquals(result.categories, []);
});

Deno.test("clearing an M2 note immediately removes its classification cue", () => {
  assertEquals(
    classifySnapshot({ note: "맛집", text_revision: 3 }).categories[0].code,
    "food",
  );
  const cleared = classifySnapshot({ note: null, text_revision: 4 });
  assertEquals(cleared.target_revision, 4);
  assertEquals(cleared.categories, []);
});

Deno.test("metadata cleanup is not applied to user-authored titles or notes", () => {
  assertEquals(
    classifyItem({
      user_title: "Travel Magazine on Instagram: 오늘의 한마디",
    }).categories.map((item) => item.code),
    ["travel"],
  );
  assertEquals(
    classifyItem({
      note: "Travel Magazine on Instagram: 오늘의 한마디",
    }).categories.map((item) => item.code),
    ["travel"],
  );
});

Deno.test("prepareSearch deduplicates terms and groups without stripping particles", () => {
  assertEquals(prepareSearch("  카톡 카톡 프로필 사진을  "), {
    query: "카톡 카톡 프로필 사진을",
    terms: ["카톡", "프로필", "사진을"],
    groups: [
      { kind: "concept", value: "kakaotalk" },
      { kind: "literal", value: "프로필" },
      { kind: "literal", value: "사진을" },
    ],
  });
});

Deno.test("literal direct score does not add user and fetched title weights", () => {
  const match = scoreReferenceSearch({
    user_title: "제주",
    fetched_title: "제주",
  }, prepareSearch("제주"));
  assertEquals(match?.score, 10);
});

Deno.test("alias explanations contain only registered forms found in the document", () => {
  assertEquals(
    explainAliases(
      { fetched_title: "카톡프사를 바꿨다" },
      prepareSearch("카카오톡 프로필 사진"),
    ),
    [
      {
        concept_id: "kakaotalk",
        field: "fetched_title",
        expression: "카톡프사",
      },
      {
        concept_id: "profile_photo",
        field: "fetched_title",
        expression: "카톡프사",
      },
    ],
  );
  assertEquals(
    explainAliases(
      { fetched_title: "등록되지 않은 표현" },
      prepareSearch("unknown"),
    ),
    [],
  );
});

Deno.test("classification explanations contain registered expressions, not copied text", () => {
  const snapshot = { fetched_title: "엑셀 excel 실전 안내" };
  const result = classifyItem(snapshot, 9);
  assertEquals(explainClassification(snapshot, result), [
    {
      category_code: "work",
      rule_id: "work:strong:0",
      field: "fetched_title",
      expression: "엑셀",
    },
    {
      category_code: "work",
      rule_id: "work:strong:0",
      field: "fetched_title",
      expression: "excel",
    },
  ]);
});

Deno.test("rules metadata returns isolated arrays for read-only responses", () => {
  const first = getRulesMetadata();
  const original = first.categories[0].rules[0].expressions[0];
  first.categories[0].rules[0].expressions[0] = "changed";
  assertEquals(
    getRulesMetadata().categories[0].rules[0].expressions[0],
    original,
  );
  assert(first.aliases.length > 0);
});
