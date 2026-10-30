import rules from "./rules.json" with { type: "json" };
import {
  activeFields,
  hasTerm,
  norm,
  type TopicField,
  topicFields,
  topicText,
} from "./item-preparation.ts";

interface AliasRule {
  id: string;
  forms: string[];
}

interface CategoryRule {
  id: string;
  name: string;
  strong: string[][];
  weak: string[][];
}

interface RulesConfig {
  version: string;
  search_version: string;
  threshold: number;
  max_auto: number;
  categories: CategoryRule[];
  aliases: AliasRule[];
  compounds: Record<string, string[]>;
  search_compounds: Record<string, string[]>;
}

const config = rules as RulesConfig;
const aliases = config.aliases;
const compounds = config.compounds;

export type DiscoverySnapshot = Partial<
  Record<TopicField, string | null | undefined>
>;

export interface ItemDetailSnapshot {
  text_revision?: number;
  user_title?: string | null;
  fetched_title?: string | null;
  note?: string | null;
  ocr_text?: string | null;
  shared_text?: string | null;
  description?: string | null;
  body_text?: string | null;
}

export interface SearchGroup {
  kind: "concept" | "literal";
  value: string;
}

export interface SearchPlan {
  query: string;
  terms: string[];
  groups: SearchGroup[];
}

export interface ClassificationRuleMatch {
  id: string;
  fields: string[];
}

export interface ClassificationCategory {
  code: string;
  score: number;
  rules: ClassificationRuleMatch[];
}

export interface ClassificationResult {
  rules_version: string;
  target_revision: number;
  categories: ClassificationCategory[];
}

export interface AliasExplanation {
  concept_id: string;
  field: TopicField;
  expression: string;
}

export interface ClassificationExplanation {
  category_code: string;
  rule_id: string;
  field: TopicField;
  expression: string;
}

export interface DiscoveryRulesMetadata {
  rules_version: string;
  search_version: string;
  categories: Array<{
    code: string;
    name: string;
    rules: Array<{ id: string; expressions: string[] }>;
  }>;
  aliases: Array<{ concept_id: string; expressions: string[] }>;
}

export class QueryLimitError extends Error {
  readonly code = "QUERY_LIMIT";

  constructor() {
    super("QUERY_LIMIT");
    this.name = "QueryLimitError";
  }
}

interface RegisteredQueryExpression {
  tokens: string[];
  conceptIds: string[];
}

const registeredQueryExpressions: RegisteredQueryExpression[] = [
  ...aliases.flatMap((alias) =>
    alias.forms.map((form) => ({
      tokens: norm(form).split(" "),
      conceptIds: [alias.id],
    }))
  ),
  ...Object.entries(compounds).map(([form, conceptIds]) => ({
    tokens: norm(form).split(" "),
    conceptIds,
  })),
];

export function prepareSearch(query: string): SearchPlan {
  const normalizedQuery = norm(query);
  const originalTerms = normalizedQuery.split(" ").filter(Boolean);
  if ([...normalizedQuery].length > 200 || originalTerms.length > 10) {
    throw new QueryLimitError();
  }
  const remainder = normalizedQuery.replace(
    /^(?:지난번|전에|아까) 저장한 /u,
    "",
  );
  // Only interpret an explicit retrieval prefix when a registered topic remains.
  // Quoted text and code-like punctuation retain literal query semantics.
  const retrievalQuery = remainder !== normalizedQuery &&
      /^[\p{L}\p{M}\p{N}# ]+$/u.test(remainder) &&
      config.categories.some((category) =>
        [...category.strong, ...category.weak].flat().some((expression) =>
          hasTerm(remainder, expression)
        )
      )
    ? remainder
    : normalizedQuery;
  const inputTerms = retrievalQuery.split(" ").filter(Boolean).map((term) => {
    const bare = term.startsWith("#") ? term.slice(1) : term;
    return term.startsWith("#") &&
        (registeredQueryExpressions.some((expression) =>
          expression.tokens.length === 1 && expression.tokens[0] === bare
        ) ||
          Object.hasOwn(config.search_compounds, bare) ||
          config.categories.some((category) =>
            [...category.strong, ...category.weak].flat().includes(bare)
          ))
      ? bare
      : term;
  });

  const groups: SearchGroup[] = [];
  for (let index = 0; index < inputTerms.length;) {
    let longest: RegisteredQueryExpression | undefined;
    for (const expression of registeredQueryExpressions) {
      if (
        expression.tokens.length > (longest?.tokens.length ?? 0) &&
        expression.tokens.every(
          (token, offset) => inputTerms[index + offset] === token,
        )
      ) {
        longest = expression;
      }
    }

    if (longest === undefined) {
      const term = inputTerms[index];
      const literalTerms = Object.hasOwn(config.search_compounds, term)
        ? config.search_compounds[term]
        : [term];
      groups.push(
        ...literalTerms.map((value) => ({
          kind: "literal" as const,
          value,
        })),
      );
      index++;
      continue;
    }

    groups.push(...longest.conceptIds.map((value) => ({
      kind: "concept" as const,
      value,
    })));
    index += longest.tokens.length;
  }

  const uniqueGroups = [...new Map(
    groups.map((group) => [`${group.kind}:${group.value}`, group]),
  ).values()];
  if (uniqueGroups.length > 10) {
    throw new QueryLimitError();
  }

  return {
    query: normalizedQuery,
    terms: [...new Set(inputTerms)],
    groups: uniqueGroups,
  };
}

export function classifyItem(
  snapshot: DiscoverySnapshot,
  targetRevision = 0,
): ClassificationResult {
  const fields = classificationFields(snapshot);

  const candidates = config.categories.flatMap((category, categoryIndex) => {
    const matchedRules: Array<
      ClassificationRuleMatch & { strength: "strong" | "weak" }
    > = [];
    let score = 0;

    for (const strength of ["strong", "weak"] as const) {
      category[strength].forEach((expressions, ruleIndex) => {
        const matchedFields = topicFields.filter((field) =>
          expressions.some((expression) => hasTerm(fields[field], expression))
        );
        if (matchedFields.length === 0) {
          return;
        }
        score += strength === "strong" ? 3 : 1;
        matchedRules.push({
          id: `${category.id}:${strength}:${ruleIndex}`,
          fields: matchedFields,
          strength,
        });
      });
    }

    const supported = matchedRules.some((rule) => rule.strength === "strong") ||
      topicFields.some((field) =>
        matchedRules.filter((rule) =>
          rule.strength === "weak" && rule.fields.includes(field)
        ).length >= 3
      );
    if (score < config.threshold || !supported) {
      return [];
    }

    return [{
      categoryIndex,
      code: category.id,
      score,
      rules: matchedRules.map(({ id, fields }) => ({ id, fields })),
    }];
  });

  const categories = candidates
    .sort((left, right) =>
      right.score - left.score || left.categoryIndex - right.categoryIndex
    )
    .slice(0, config.max_auto)
    .map(({ code, score, rules }) => ({ code, score, rules }));

  return {
    rules_version: config.version,
    target_revision: targetRevision,
    categories,
  };
}

export function classifySnapshot(
  snapshot: ItemDetailSnapshot,
  targetRevision = snapshot.text_revision ?? 0,
): ClassificationResult {
  return classifyItem({
    user_title: snapshot.user_title,
    fetched_title: snapshot.fetched_title,
    note: snapshot.note,
    ocr: snapshot.ocr_text,
    shared: snapshot.shared_text,
    description: snapshot.description,
    body: snapshot.body_text,
  }, targetRevision);
}

export function explainAliases(
  snapshot: DiscoverySnapshot,
  queryPlan: SearchPlan,
): AliasExplanation[] {
  const requestedConcepts = new Set(
    queryPlan.groups.filter((group) => group.kind === "concept").map((group) =>
      group.value
    ),
  );
  const fields = activeFields(snapshot);
  const explanations: AliasExplanation[] = [];

  for (const conceptId of requestedConcepts) {
    const expressions = [
      ...(aliases.find((alias) => alias.id === conceptId)?.forms ?? []),
      ...Object.entries(compounds)
        .filter(([, ids]) => ids.includes(conceptId))
        .map(([expression]) => expression),
    ];
    for (const field of topicFields) {
      for (const expression of expressions) {
        if (hasTerm(fields[field], expression)) {
          explanations.push({
            concept_id: conceptId,
            field,
            expression,
          });
        }
      }
    }
  }

  return explanations;
}

export function explainClassification(
  snapshot: DiscoverySnapshot,
  result: ClassificationResult,
): ClassificationExplanation[] {
  const fields = classificationFields(snapshot);
  const explanations: ClassificationExplanation[] = [];

  for (const resultCategory of result.categories) {
    const category = config.categories.find((candidate) =>
      candidate.id === resultCategory.code
    );
    if (category === undefined) {
      continue;
    }
    const registeredRules = new Map<string, readonly string[]>(
      (["strong", "weak"] as const).flatMap((strength) =>
        category[strength].map((expressions, index) =>
          [
            `${category.id}:${strength}:${index}`,
            expressions,
          ] as const
        )
      ),
    );
    for (const resultRule of resultCategory.rules) {
      const expressions = registeredRules.get(resultRule.id);
      if (expressions === undefined) {
        continue;
      }
      for (const field of topicFields) {
        if (!resultRule.fields.includes(field)) {
          continue;
        }
        for (const expression of expressions) {
          if (hasTerm(fields[field], expression)) {
            explanations.push({
              category_code: category.id,
              rule_id: resultRule.id,
              field,
              expression,
            });
          }
        }
      }
    }
  }

  return explanations;
}

function classificationFields(
  snapshot: DiscoverySnapshot,
): Record<TopicField, string> {
  return Object.fromEntries(
    topicFields.map((field) => [
      field,
      classificationText(topicText(norm(snapshot[field]), field)),
    ]),
  ) as Record<TopicField, string>;
}

const categoryExpressions = [
  ...new Set(
    config.categories.flatMap((category) =>
      [...category.strong, ...category.weak].flat()
    ),
  ),
].sort((left, right) => right.length - left.length);
const negatedCategoryExpression = new RegExp(
  `(^|[^\\p{L}\\p{M}\\p{N}_])(?:${
    categoryExpressions.map((expression) =>
      expression.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&")
    ).join("|")
  })(?:은|는|이|가)? (?:없고|없어요|없습니다|없다|아니고|아니에요|아닙니다|아니다)(?=$|[^\\p{L}\\p{M}\\p{N}_])`,
  "gu",
);

function classificationText(text: string): string {
  if (text === "") {
    return "";
  }
  // This is deliberately not general negation detection: only an adjacent,
  // explicit absent/not-a cue is removed, leaving other occurrences intact.
  const result = text.replace(negatedCategoryExpression, "$1 ");
  return result.replace(
    /(^|[^\p{L}\p{M}\p{N}_])(?:python regius|ball python|python is a (?:nonvenomous )?snake)(?=$|[^\p{L}\p{M}\p{N}_])/gu,
    "$1 ",
  );
}

export function getRulesMetadata(): DiscoveryRulesMetadata {
  return {
    rules_version: config.version,
    search_version: config.search_version,
    categories: config.categories.map((category) => ({
      code: category.id,
      name: category.name,
      rules: (["strong", "weak"] as const).flatMap((strength) =>
        category[strength].map((expressions, index) => ({
          id: `${category.id}:${strength}:${index}`,
          expressions: [...expressions],
        }))
      ),
    })),
    aliases: aliases.map((alias) => ({
      concept_id: alias.id,
      expressions: [...alias.forms],
    })),
  };
}
