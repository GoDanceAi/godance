/**
 * The gallery as the pages see it: the recipes from the content collection,
 * the switch, and the small view helpers every gallery page shares.
 *
 * Every page, link and JSON endpoint asks `loadGallery()` whether the gallery
 * is on. When it is off they all write nothing.
 */
import { getCollection, type CollectionEntry } from 'astro:content';
import { resolveGalleryConfig, type GalleryConfig } from './gallery-config';

export type Recipe = CollectionEntry<'gallery'>['data'];
export type Run = Recipe['runs'][number];
export type Output = Run['outputs'][number];

export interface Gallery {
  /** True when the media base and the removal contact are set and a recipe exists. */
  on: boolean;
  config: GalleryConfig;
  /** Newest first. Empty when the gallery is off. */
  recipes: Recipe[];
}

/**
 * Slugs the JSON endpoints already use: /gallery/index.json is the list, so a
 * recipe called "index" would overwrite it.
 */
const RESERVED_SLUGS = ['index'];

let cached: Promise<Gallery> | undefined;

async function read(): Promise<Gallery> {
  const config = resolveGalleryConfig();
  // The collection is only read once the operator's values are in, so a
  // build with the switch empty does not warn about an empty collection.
  if (config.mediaBaseUrl === '' || config.removalContact === '') return { on: false, config, recipes: [] };
  const entries = await getCollection('gallery');
  if (entries.length === 0) return { on: false, config, recipes: [] };
  const on = true;

  const recipes = entries
    .map((entry) => entry.data)
    .sort((a, b) => b.published_on.localeCompare(a.published_on) || a.slug.localeCompare(b.slug));

  for (const recipe of recipes) {
    if (RESERVED_SLUGS.includes(recipe.slug)) {
      throw new Error(
        `gallery: the slug "${recipe.slug}" is taken by /gallery/${recipe.slug}.json. Publish this recipe under another slug.`,
      );
    }
  }
  return { on, config, recipes };
}

/** The gallery, read once per build. */
export function loadGallery(): Promise<Gallery> {
  // The dev server re-reads on every request so edits to recipes show up.
  if (import.meta.env.DEV) return read();
  cached ??= read();
  return cached;
}

// ---------------------------------------------------------------------------
// Paths and URLs
// ---------------------------------------------------------------------------

export const GALLERY_PATH = '/gallery';
export const recipePath = (slug: string) => `/gallery/${slug}`;
export const modelPath = (model: string) => `/gallery/model/${encodeURIComponent(model)}`;

/** The public URL of a media file. `base` ends in a slash. */
export function mediaUrl(base: string, file: string): string {
  return base + file.split('/').map(encodeURIComponent).join('/');
}

export const isVideoOutput = (output: Output): output is Extract<Output, { duration_seconds: number }> =>
  'duration_seconds' in output;

/** The still picture for an output: an image's thumbnail, a video's poster, or null. */
export function thumbFile(output: Output): string | null {
  if (isVideoOutput(output)) return output.poster ?? null;
  return output.thumb;
}

// ---------------------------------------------------------------------------
// Views over the recipes
// ---------------------------------------------------------------------------

/** Every model that has a run in some recipe, most-used first. */
export function galleryModels(recipes: Recipe[]): { id: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const recipe of recipes) {
    for (const model of new Set(recipe.runs.map((run) => run.model))) {
      counts.set(model, (counts.get(model) ?? 0) + 1);
    }
  }
  return [...counts]
    .map(([id, count]) => ({ id, count }))
    .sort((a, b) => b.count - a.count || a.id.localeCompare(b.id));
}

/** Every tag in use, most-used first. */
export function galleryTags(recipes: Recipe[]): { tag: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const recipe of recipes) {
    for (const tag of new Set(recipe.tags)) counts.set(tag, (counts.get(tag) ?? 0) + 1);
  }
  return [...counts]
    .map(([tag, count]) => ({ tag, count }))
    .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
}

/** Up to `limit` other recipes that share tags or a model with this one, closest first. */
export function relatedRecipes(recipe: Recipe, recipes: Recipe[], limit = 6): Recipe[] {
  const tags = new Set(recipe.tags);
  const models = new Set(recipe.runs.map((run) => run.model));
  return recipes
    .filter((other) => other.slug !== recipe.slug)
    .map((other, order) => ({
      other,
      order, // `recipes` is newest first, so this breaks ties by date
      score:
        2 * new Set(other.tags.filter((tag) => tags.has(tag))).size +
        (other.runs.some((run) => models.has(run.model)) ? 1 : 0),
    }))
    .filter(({ score }) => score > 0)
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .slice(0, limit)
    .map(({ other }) => other);
}

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

/** "$0.06" for 6. The cost is shown as it was recorded, never recomputed. */
export function formatCost(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

/** "8 November 2026" for "2026-11-08". */
export function formatDate(isoDate: string): string {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

/** The start of a text on one line, cut at a word, for a meta description. */
export function excerpt(text: string, max = 155): string {
  const line = text.replace(/\s+/g, ' ').trim();
  if (line.length <= max) return line;
  const cut = line.slice(0, max - 1);
  const atWord = cut.lastIndexOf(' ');
  return `${(atWord > max * 0.6 ? cut.slice(0, atWord) : cut).replace(/[\s,;:.\-–—]+$/, '')}…`;
}

/** A parameter value as text: strings as they are, everything else as JSON. */
export function formatParam(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/**
 * JSON that is safe inside a <script> element: "<" is written as <, so
 * no text in a recipe can close the element.
 */
export function scriptJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c');
}
