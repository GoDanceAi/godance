/**
 * Comparison pages: two models on the same prompts (round 13).
 *
 * A pair of models gets a page at /gallery/compare/<a>-vs-<b> once at least
 * GALLERY_COMPARE_MIN published recipes (8 by default) have a run on both.
 * Every run of a published recipe was approved: the recipe's review covers
 * the runs it was published with, and a run added later carries its own
 * approved_on. Below the threshold there is no page and no link.
 *
 * A page shows evidence and leaves the judging to the reader: the outputs
 * side by side, what each run cost when it was made, and today's prices.
 * Nothing here ranks the models or says which did better.
 *
 * Plain functions with type-only imports, so the tests run them directly.
 */
import type { Recipe, Run } from './gallery-recipes';

export const COMPARE_PATH = '/gallery/compare';

/** One recipe of a comparison, with its run on each of the two models. */
export interface ComparedRecipe {
  recipe: Recipe;
  a: Run;
  b: Run;
}

export interface Comparison {
  /** The two model ids, in code-point order. */
  a: string;
  b: string;
  /** "<a>-vs-<b>": the page's path segment. */
  slug: string;
  modality: Recipe['modality'];
  /** In the order the gallery lists them: newest first. */
  recipes: ComparedRecipe[];
}

/** Model ids a page can be named by: letters, digits, dots, hyphens and underscores. */
const NAMEABLE = /^[A-Za-z0-9._-]+$/;

/** The page's slug: the two ids in code-point order, so a pair has one page whichever way it is named. */
export function compareSlug(one: string, other: string): string {
  const [a, b] = [one, other].sort();
  return `${a}-vs-${b}`;
}

export const comparePath = (one: string, other: string): string => `${COMPARE_PATH}/${compareSlug(one, other)}`;

/**
 * Every pair of models that at least `min` recipes have a run on both, the
 * pair with the most recipes first. A recipe counts once for a pair, with its
 * first run on each of the two models.
 */
export function comparisons(recipes: Recipe[], min: number): Comparison[] {
  const pairs = new Map<string, Comparison>();
  for (const recipe of recipes) {
    const firstOn = new Map<string, Run>();
    for (const run of recipe.runs) {
      if (!firstOn.has(run.model) && NAMEABLE.test(run.model)) firstOn.set(run.model, run);
    }
    const models = [...firstOn.keys()].sort();
    for (let i = 0; i < models.length; i++) {
      for (let j = i + 1; j < models.length; j++) {
        const slug = compareSlug(models[i], models[j]);
        let pair = pairs.get(slug);
        if (!pair) {
          pair = { a: models[i], b: models[j], slug, modality: recipe.modality, recipes: [] };
          pairs.set(slug, pair);
        }
        pair.recipes.push({ recipe, a: firstOn.get(models[i])!, b: firstOn.get(models[j])! });
      }
    }
  }
  return [...pairs.values()]
    .filter((pair) => pair.recipes.length >= min)
    .sort((x, y) => y.recipes.length - x.recipes.length || x.slug.localeCompare(y.slug));
}

/** What one model's runs in a comparison cost: in all, and per recipe on average, in cents. */
export interface ModelTotals {
  model: string;
  total: number;
  average: number;
}

export function compareTotals(comparison: Comparison): [ModelTotals, ModelTotals] {
  const count = comparison.recipes.length;
  const sum = (side: 'a' | 'b') => comparison.recipes.reduce((total, each) => total + each[side].cost_cents, 0);
  return [
    { model: comparison.a, total: sum('a'), average: sum('a') / count },
    { model: comparison.b, total: sum('b'), average: sum('b') / count },
  ];
}

/**
 * Cents as dollars, to a tenth of a cent so an average is not rounded away:
 * "$0.06" for 6, "$0.059" for 5.9.
 */
export function dollars(cents: number): string {
  const tenths = Math.round(cents * 10) / 10;
  return `$${(tenths / 100).toFixed(Number.isInteger(tenths) ? 2 : 3)}`;
}

/** "8 November 2026" for "2026-11-08". */
function day(isoDate: string): string {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}

/** The days the compared runs were made: the first and the last. */
export function compareDates(comparison: Comparison): { first: string; last: string } {
  const dates = comparison.recipes.flatMap((each) => [each.a.generated_on, each.b.generated_on]).sort();
  return { first: dates[0], last: dates[dates.length - 1] };
}

/**
 * The page's opening paragraph, made from the data alone: how many recipes
 * were run on both models, when, and what the runs cost. It says nothing the
 * data does not show, and nothing about which model did better.
 */
export function compareIntro(comparison: Comparison): string {
  const [a, b] = compareTotals(comparison);
  const { first, last } = compareDates(comparison);
  const count = comparison.recipes.length;
  const kind = comparison.modality === 'VIDEO' ? 'video' : 'image';
  const when = first === last ? `on ${day(first)}` : `between ${day(first)} and ${day(last)}`;
  return (
    `${count} ${kind} recipes from the Godance gallery were each run on both ${a.model} and ${b.model} through the Godance API, ${when}. ` +
    'Each pair below is the same prompt on the two models, with what each run cost on the day it was made and the parameters it was sent. ' +
    `In all, the ${a.model} runs cost ${dollars(a.total)}, ${dollars(a.average)} a recipe on average, and the ${b.model} runs ${dollars(b.total)}, ${dollars(b.average)} a recipe on average.`
  );
}

/** The page's title. */
export const compareTitle = (comparison: Comparison): string =>
  `${comparison.a} vs ${comparison.b}: ${comparison.recipes.length} prompts, outputs and costs | Godance gallery`;

/** Where a visit from a comparison came from, as the recipe page says it: the recipe's own campaign. */
const campaign = (recipe: Recipe) => `utm_source=godance&utm_medium=gallery&utm_campaign=${encodeURIComponent(recipe.slug)}`;

/** "Run this": the recipe in the Studio, which opens it on the first run whose model the catalogue lists. */
export function runThisUrl(appUrl: string, recipe: Recipe): string {
  return `${appUrl}/create/${recipe.modality === 'VIDEO' ? 'video' : 'image'}?example=${encodeURIComponent(recipe.slug)}&${campaign(recipe)}`;
}

/** "Get an API key": sign-up, while "Run this" is switched off. */
export function getKeyUrl(appUrl: string, recipe: Recipe): string {
  return `${appUrl}/signup?${campaign(recipe)}`;
}

/** The newest day anything on the page was published or approved. */
function lastChanged(comparison: Comparison): string {
  return comparison.recipes
    .flatMap((each) => [each.recipe.published_on, each.a.approved_on, each.b.approved_on])
    .filter((date): date is string => typeof date === 'string')
    .sort()
    .at(-1)!;
}

/** Structured data for a comparison page: the page, and the list of recipes it compares. */
export function compareJsonLd(comparison: Comparison, site: URL | string, description: string) {
  const url = new URL(comparePath(comparison.a, comparison.b), site).toString();
  const godance = { '@type': 'Organization', name: 'Godance', url: new URL('/', site).toString() };
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebPage',
        '@id': url,
        url,
        name: `${comparison.a} vs ${comparison.b}`,
        description,
        inLanguage: 'en',
        dateModified: lastChanged(comparison),
        publisher: godance,
        mainEntity: { '@id': `${url}#recipes` },
      },
      {
        '@type': 'ItemList',
        '@id': `${url}#recipes`,
        name: `Recipes run on both ${comparison.a} and ${comparison.b}`,
        numberOfItems: comparison.recipes.length,
        itemListElement: comparison.recipes.map((each, i) => ({
          '@type': 'ListItem',
          position: i + 1,
          url: new URL(`/gallery/${each.recipe.slug}`, site).toString(),
          name: each.recipe.title.en,
        })),
      },
    ],
  };
}

/** Structured data for /gallery/compare: every comparison page. */
export function compareIndexJsonLd(all: Comparison[], site: URL | string) {
  const url = new URL(COMPARE_PATH, site).toString();
  return {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    '@id': url,
    url,
    name: 'Model comparisons — Godance gallery',
    inLanguage: 'en',
    mainEntity: {
      '@type': 'ItemList',
      numberOfItems: all.length,
      itemListElement: all.map((comparison, i) => ({
        '@type': 'ListItem',
        position: i + 1,
        url: new URL(comparePath(comparison.a, comparison.b), site).toString(),
        name: `${comparison.a} vs ${comparison.b}`,
      })),
    },
  };
}
