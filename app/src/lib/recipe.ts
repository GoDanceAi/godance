/**
 * Recipes from the gallery: static JSON on the marketing site, in the shape
 * docs/claude-code-briefs/round-8/00-coordination.md fixes ("JSON endpoints"):
 *
 *   GET <gallery>/gallery/index.json   { recipes: [{ slug, title, modality, model, tags, thumb, published_on }] }
 *   GET <gallery>/gallery/<slug>.json  { slug, title, modality, prompt, run: { model, prompt_lang, params }, url }
 *
 * and, since round 10, a recipe with variables carries inside `prompt`:
 *
 *   template: { en, zh }, variables: [{ name, default, zh_default? }]
 *
 * where each language's template, filled with its defaults, is that
 * language's prompt (lib/template has the rules).
 *
 * Whatever arrives is untrusted input. These parsers keep only the fields the
 * app uses, with the types and sizes it expects, and drop the rest; a recipe
 * whose shape doesn't match is ignored whole. Nothing in this module touches
 * the network, storage or the page, so the tests run it directly.
 */

import { checkTemplate, isVariableName, MAX_VARIABLES, renderTemplate, type TemplateVariable, VALUE_MAX_LENGTH } from './template';

export type RecipeModality = 'IMAGE' | 'VIDEO';
export type RecipeLang = 'en' | 'zh';

/** The English and Chinese versions of one text. At least one is present. */
export interface RecipeText {
  en: string | null;
  zh: string | null;
}

/**
 * The request fields a run may pass on to the Studio. Anything else in
 * `params` is dropped; the Studio's fitValues then keeps only what the chosen
 * model's capabilities allow.
 */
export interface RecipeParams {
  size?: string;
  n?: number;
  seconds?: number;
}

/** A variable of a gallery recipe: its default in English, and in Chinese where that differs. */
export interface GalleryVariable {
  name: string;
  default: string;
  zhDefault: string | null;
}

/**
 * A gallery recipe's template: its text in every language the prompt has,
 * and its variables. Filled with a language's defaults it is that language's
 * prompt, which parseRecipe checks.
 */
export interface GalleryTemplate {
  text: RecipeText;
  variables: GalleryVariable[];
}

export interface GalleryRecipe {
  slug: string;
  title: RecipeText;
  modality: RecipeModality;
  /** Always has text in the language the run used, `run.promptLang`. */
  prompt: RecipeText;
  /** Null for a recipe without variables, and for one whose template doesn't hold together. */
  template: GalleryTemplate | null;
  run: { model: string; promptLang: RecipeLang; params: RecipeParams };
  /** The recipe's page, and only when it is on the gallery's own origin. */
  url: string | null;
}

export interface GalleryEntry {
  slug: string;
  title: RecipeText;
  modality: RecipeModality;
  model: string;
  /** The thumbnail (a video's poster), as an absolute https URL, or null. */
  thumb: string | null;
}

const SLUG = /^[a-z0-9-]{1,80}$/;
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/;
const SIZE = /^\d{2,5}x\d{2,5}$/;
const TITLE_MAX = 200;
// A sanity bound only, far above any prompt the Studio takes. The Studio cuts
// a loaded prompt to its own limit.
const PROMPT_MAX = 20_000;
const N_MAX = 16;
const SECONDS_MAX = 600;
const INDEX_MAX = 500;
// C0 controls other than tab and newline, DEL, and the bidirectional
// overrides and isolates, which can make text read differently from what it is.
const DISGUISING = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f‪-‮⁦-⁩]/g;

/** Lowercase letters, digits and hyphens, at most 80 characters. Checked before any request is made. */
export function isRecipeSlug(value: unknown): value is string {
  return typeof value === 'string' && SLUG.test(value);
}

/**
 * The recipe an in-app path opens: `/create/image?example=<slug>` or
 * `/create/video?example=<slug>`, with a valid slug. A `?job=` wins over
 * `?example=` in the Studio, and so does a `?recipe=` (one of the user's own
 * recipes), so a path that has either is not a link to a gallery recipe.
 */
export function recipeLinkOf(path: string): { modality: RecipeModality; slug: string } | null {
  const base = 'https://app.invalid';
  let url: URL;
  try {
    url = new URL(path, base);
  } catch {
    return null;
  }
  if (url.origin !== base) return null;
  // As the router matches routes: in any case, with or without a trailing slash.
  const pathname = url.pathname.toLowerCase().replace(/\/$/, '');
  const modality = pathname === '/create/image' ? 'IMAGE' : pathname === '/create/video' ? 'VIDEO' : null;
  const slug = url.searchParams.get('example');
  if (!modality || url.searchParams.has('job') || url.searchParams.has('recipe') || !isRecipeSlug(slug)) return null;
  return { modality, slug };
}

/** One recipe, from `<gallery>/gallery/<slug>.json`, or null when it isn't the recipe asked for in the expected shape. */
export function parseRecipe(json: unknown, expected: { slug: string; galleryOrigin: string }): GalleryRecipe | null {
  if (!isRecord(json) || json.slug !== expected.slug || !isRecipeSlug(json.slug)) return null;
  const title = localized(json.title, TITLE_MAX, true);
  const modality = modalityOf(json.modality);
  const prompt = localized(json.prompt, PROMPT_MAX, false);
  if (!title || !modality || !prompt || !isRecord(json.run)) return null;

  const model = modelOf(json.run.model);
  const promptLang = json.run.prompt_lang === 'en' || json.run.prompt_lang === 'zh' ? json.run.prompt_lang : null;
  if (!model || !promptLang || prompt[promptLang] === null) return null;

  return {
    slug: json.slug,
    title,
    modality,
    prompt,
    template: templateOf(json.prompt, prompt),
    run: { model, promptLang, params: paramsOf(json.run.params) },
    url: pageUrlOf(json.url, expected.galleryOrigin),
  };
}

/** The gallery's list, from `<gallery>/gallery/index.json`. Entries that don't match are dropped; the rest are kept in order. */
export function parseGalleryIndex(json: unknown, galleryOrigin: string): GalleryEntry[] {
  if (!isRecord(json) || !Array.isArray(json.recipes)) return [];
  const seen = new Set<string>();
  const entries: GalleryEntry[] = [];
  for (const item of json.recipes.slice(0, INDEX_MAX)) {
    if (!isRecord(item) || !isRecipeSlug(item.slug) || seen.has(item.slug)) continue;
    const title = localized(item.title, TITLE_MAX, true);
    const modality = modalityOf(item.modality);
    const model = modelOf(item.model);
    if (!title || !modality || !model) continue;
    seen.add(item.slug);
    entries.push({ slug: item.slug, title, modality, model, thumb: imageUrlOf(item.thumb, galleryOrigin) });
  }
  return entries;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function textOf(value: unknown, max: number, oneLine: boolean): string | null {
  if (typeof value !== 'string' || value.length > max * 2) return null;
  let text = value.replace(DISGUISING, '');
  if (oneLine) text = text.replace(/\s+/g, ' ');
  text = text.trim();
  return text.length > 0 && text.length <= max ? text : null;
}

function localized(value: unknown, max: number, oneLine: boolean): RecipeText | null {
  if (!isRecord(value)) return null;
  const en = textOf(value.en, max, oneLine);
  const zh = textOf(value.zh, max, oneLine);
  return en !== null || zh !== null ? { en, zh } : null;
}

/** A gallery recipe's variables with the default each has in one language. */
export function defaultsIn(variables: readonly GalleryVariable[], lang: RecipeLang): TemplateVariable[] {
  return variables.map((v) => ({ name: v.name, default: lang === 'zh' && v.zhDefault !== null ? v.zhDefault : v.default }));
}

/** A variable's default: any text up to the API's limit on a value, and it may be empty. */
function defaultOf(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > VALUE_MAX_LENGTH) return null;
  return value.replace(DISGUISING, '');
}

/**
 * The template inside `prompt`, or null. It is kept only when it holds
 * together: valid, uniquely named variables, a template for every language
 * the prompt has, and each one giving exactly that prompt when filled with
 * its defaults. Anything less and the recipe is still a recipe, with a
 * plain prompt.
 */
function templateOf(value: unknown, prompt: RecipeText): GalleryTemplate | null {
  if (!isRecord(value) || !isRecord(value.template) || !Array.isArray(value.variables)) return null;
  if (value.variables.length === 0 || value.variables.length > MAX_VARIABLES) return null;

  const variables: GalleryVariable[] = [];
  for (const item of value.variables) {
    if (!isRecord(item) || !isVariableName(item.name) || variables.some((v) => v.name === item.name)) return null;
    const fallback = defaultOf(item.default);
    const zhDefault = item.zh_default === undefined ? null : defaultOf(item.zh_default);
    if (fallback === null || (item.zh_default !== undefined && zhDefault === null)) return null;
    variables.push({ name: item.name, default: fallback, zhDefault });
  }

  const text: RecipeText = { en: null, zh: null };
  const names = variables.map((v) => v.name);
  for (const lang of ['en', 'zh'] as const) {
    if (prompt[lang] === null) continue;
    const template = textOf(value.template[lang], PROMPT_MAX, false);
    if (template === null || checkTemplate(template, names).length > 0) return null;
    if (renderTemplate(template, defaultsIn(variables, lang)) !== prompt[lang]) return null;
    text[lang] = template;
  }
  return { text, variables };
}

function modalityOf(value: unknown): RecipeModality | null {
  return value === 'IMAGE' || value === 'VIDEO' ? value : null;
}

function modelOf(value: unknown): string | null {
  return typeof value === 'string' && MODEL_ID.test(value) ? value : null;
}

function paramsOf(value: unknown): RecipeParams {
  if (!isRecord(value)) return {};
  const params: RecipeParams = {};
  if (typeof value.size === 'string' && SIZE.test(value.size)) params.size = value.size;
  if (isWholeNumber(value.n, 1, N_MAX)) params.n = value.n;
  if (isWholeNumber(value.seconds, 1, SECONDS_MAX)) params.seconds = value.seconds;
  return params;
}

function isWholeNumber(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

function pageUrlOf(value: unknown, galleryOrigin: string): string | null {
  const url = absoluteUrl(value);
  return url && url.origin === galleryOrigin ? url.href : null;
}

function imageUrlOf(value: unknown, galleryOrigin: string): string | null {
  const url = absoluteUrl(value);
  if (!url) return null;
  // https, except while the gallery itself is on http: local development and the tests.
  const secure = url.protocol === 'https:' || (url.protocol === 'http:' && galleryOrigin.startsWith('http:'));
  return secure ? url.href : null;
}

function absoluteUrl(value: unknown): URL | null {
  if (typeof value !== 'string' || value.length > 2000) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
    return url.username === '' && url.password === '' ? url : null;
  } catch {
    return null;
  }
}
