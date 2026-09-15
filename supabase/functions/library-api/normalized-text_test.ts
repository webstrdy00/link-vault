import { assert, assertEquals } from "@std/assert";
import { buildIndex, norm, prepareItem } from "./item-preparation.ts";

const maximumNormalizationExpansion = 18;
const arabicLigature = "ﷺ";
const expandedArabicLigature = "صلى الله عليه وسلم";

Deno.test("norm proves the Unicode scalar expansion ceiling used by storage", () => {
  let observedMaximum = 0;
  const maximumWitnesses: number[] = [];

  for (let codePoint = 0; codePoint <= 0x10ffff; codePoint++) {
    if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
      continue;
    }

    const normalizedLength = [...norm(String.fromCodePoint(codePoint))].length;
    if (normalizedLength > observedMaximum) {
      observedMaximum = normalizedLength;
      maximumWitnesses.length = 0;
      maximumWitnesses.push(codePoint);
    } else if (normalizedLength === observedMaximum) {
      maximumWitnesses.push(codePoint);
    }
  }

  assertEquals(observedMaximum, maximumNormalizationExpansion);
  assert(maximumWitnesses.includes(0xfdfa));
  assertEquals(
    norm(arabicLigature.repeat(30)),
    expandedArabicLigature.repeat(30),
  );
  assertEquals([...norm(arabicLigature.repeat(8))].length, 144);
  assertEquals(
    [...norm(arabicLigature.repeat(30))].length,
    30 * maximumNormalizationExpansion,
  );
  assertEquals(norm("İ".repeat(4000)), "i̇".repeat(4000));
});

Deno.test("buildIndex retains raw-maximum ligature and dotted-I fields", () => {
  const rawTitle = arabicLigature.repeat(300);
  const rawNote = "İ".repeat(4000);
  const rawCategories = ["a", "b", "c", "d", "e"].map((suffix) =>
    arabicLigature.repeat(29) + suffix
  ).join(" ");
  const expectedCategories = ["a", "b", "c", "d", "e"].map((suffix) =>
    expandedArabicLigature.repeat(29) + suffix
  ).join(" ");

  const index = buildIndex({
    user_title: rawTitle,
    note: rawNote,
    categories: rawCategories,
  });

  assertEquals(
    index.fields.user_title,
    expandedArabicLigature.repeat(300),
  );
  assertEquals([...index.fields.user_title].length, 5400);
  assertEquals(index.fields.note, "i̇".repeat(4000));
  assertEquals([...index.fields.note].length, 8000);
  assertEquals(index.fields.categories, expectedCategories);
  assertEquals([...index.fields.categories].length, 2619);
});

Deno.test("prepareItem retains the maximum expanded title and note", async () => {
  const prepared = await prepareItem({
    url: "https://example.com/normalized-text-bounds",
    title: arabicLigature.repeat(300),
    note: arabicLigature.repeat(4000),
  });

  assertEquals(
    prepared.normalized_fields.user_title,
    expandedArabicLigature.repeat(300),
  );
  assertEquals([...prepared.normalized_fields.user_title].length, 5400);
  assertEquals(
    prepared.normalized_fields.note,
    expandedArabicLigature.repeat(4000),
  );
  assertEquals([...prepared.normalized_fields.note].length, 72000);
  assertEquals(
    prepared.normalized_fields.url,
    "https://example.com/normalized-text-bounds",
  );
});
