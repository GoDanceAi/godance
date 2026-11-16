/**
 * The community (round 14 brief 02): recipes Godance users published that a
 * moderator approved, and their authors' public profiles. They are read once
 * per build from the API's public routes, which need no sign-in:
 *
 *   GET /v1/public/recipes?limit=&before=   every page, newest first
 *   GET /v1/public/profiles/{handle}        each author's profile
 *
 * Nothing else is read, and of what those routes answer only the fields
 * below are kept. So no page can show anything the API does not already
 * make public.
 *
 * The switch. `communityConfig.enabled` ships false, which keeps the
 * community OFF: the build then reads nothing and writes no community page,
 * link or sitemap entry. COMMUNITY_ENABLED=true turns it on for one build.
 *
 * It fails soft. When the API cannot be read (down, too slow, an error, the
 * community switched off on the API's side, or an answer that is not a
 * list), the build goes on without community pages and a warning says why.
 * The rest of the site never depends on it.
 *
 * Plain TypeScript with no Astro import, so the tests load it with `node`.
 */
import { renderTemplate } from './gallery-template.js';

// ---------------------------------------------------------------------------
// The switch
// ---------------------------------------------------------------------------

export interface CommunityConfig {
  /** Whether the build reads the public API and writes the community pages. */
  enabled: boolean;
}

/** The operator turns the community on here, once the API has it on too. */
export const communityConfig: CommunityConfig = {
  enabled: false,
};

/**
 * The environment variable that overrides the committed value, for a
 * build or a test. A variable that is set wins even when it is empty.
 */
export const COMMUNITY_ENV = { enabled: 'COMMUNITY_ENABLED' } as const;

type Env = Record<string, string | undefined>;

/** Vite's environment (which includes .env files) under the shell's own. */
function buildEnv(): Env {
  const vite = ((import.meta as { env?: Env }).env ?? {}) as Env;
  return { ...vite, ...(globalThis.process?.env ?? {}) };
}

function flag(value: string, name: string): boolean {
  const v = value.trim().toLowerCase();
  if (['1', 'true', 'on', 'yes'].includes(v)) return true;
  if (['', '0', 'false', 'off', 'no'].includes(v)) return false;
  throw new Error(`community: ${name} must be "true" or "false", not "${value}"`);
}

/**
 * The switch as the build sees it. A value that is set but malformed throws,
 * so a typo fails the build instead of quietly leaving the community off.
 */
export function resolveCommunityConfig(env: Env = buildEnv()): CommunityConfig {
  return { enabled: flag(env[COMMUNITY_ENV.enabled] ?? String(communityConfig.enabled), COMMUNITY_ENV.enabled) };
}

/** The API the pages are read from: PUBLIC_API_BASE, as for the model catalog. */
export function apiBaseFrom(env: Env = buildEnv()): string {
  return (env.PUBLIC_API_BASE?.trim() || 'http://localhost:8080').replace(/\/+$/, '');
}

/** The app that "Run this", "Follow" and "Report" open. */
export function appUrlFrom(env: Env = buildEnv()): string {
  return (env.PUBLIC_APP_URL?.trim() || 'https://app.godance.ai').replace(/\/+$/, '');
}

// ---------------------------------------------------------------------------
// What the public routes answer
// ---------------------------------------------------------------------------

export interface PublicVariable {
  name: string;
  default: string;
}

/** An input slot: its name, the role of the image, and the hint. Never a file. */
export interface PublicInputSlot {
  slot: string;
  role: string;
  hint: string | null;
}

/** An example output: an absolute URL to the public copy, and its type. */
export interface PublicExample {
  url: string;
  content_type: string;
}

/** What a recipe was made from: a gallery recipe by slug, or a published recipe by public id. */
export interface MadeFrom {
  kind: 'gallery' | 'published';
  id: string;
}

export interface PublicAuthor {
  handle: string;
  followers: number;
}

/** `PublicRecipe` in the API's OpenAPI file: exactly these fields. */
export interface PublicRecipe {
  public_id: string;
  title: string;
  modality: 'image' | 'video';
  template: string;
  variables: PublicVariable[];
  inputs: PublicInputSlot[];
  model: string;
  params: Record<string, unknown>;
  examples: PublicExample[];
  made_from: MadeFrom | null;
  /** Finished runs by other accounts; the author's own are not counted. */
  runs: number;
  /** The accounts that ran it, its author excluded. */
  runners: number;
  licence: string;
  author: PublicAuthor;
  tags: string[];
  published_at: string;
}

/** `PublicProfile`: the profile as a moderator approved it, and how many follow it, never who. */
export interface PublicProfile {
  handle: string;
  display_name: string | null;
  link: string | null;
  followers: number;
}

// ---------------------------------------------------------------------------
// Checking what came back
// ---------------------------------------------------------------------------

/** A public id: what becomes /community/<public_id>. No dot or slash can get into a path. */
export const PUBLIC_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
/** A handle, as the API's profile rules have it: 3 to 30 characters, no two hyphens in a row. */
const HANDLE = /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/;
/** A tag, as the API's publish rules have it. */
const TAG = /^[a-z0-9][a-z0-9-]{0,23}$/;
/** A gallery recipe's slug. */
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export const isPublicId = (value: unknown): value is string => typeof value === 'string' && PUBLIC_ID.test(value);
export const isHandle = (value: unknown): value is string =>
  typeof value === 'string' && HANDLE.test(value) && !value.includes('--');
const isTag = (value: unknown): value is string => typeof value === 'string' && TAG.test(value) && !value.includes('--');

type Json = Record<string, unknown>;
const isObject = (value: unknown): value is Json => typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const count = (value: unknown): number => (Number.isSafeInteger(value) && (value as number) >= 0 ? (value as number) : 0);

/** An absolute http(s) URL with no credentials in it, or null. */
function webUrl(value: unknown, protocols = ['https:', 'http:']): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return protocols.includes(url.protocol) && url.username === '' && url.password === '' ? url.href : null;
  } catch {
    return null;
  }
}

/** Plain JSON values only, so params can be shown and sent as they are. */
function params(value: unknown): Record<string, unknown> {
  if (!isObject(value)) return {};
  const out: Record<string, unknown> = {};
  for (const [key, each] of Object.entries(value)) {
    if (['string', 'number', 'boolean'].includes(typeof each)) out[key] = each;
  }
  return out;
}

/**
 * A recipe as the pages use it, built field by field from what the API
 * answered, or null when it lacks what a page needs. A field the API might
 * add one day is never copied.
 */
export function parseRecipe(raw: unknown): PublicRecipe | null {
  if (!isObject(raw) || !isObject(raw.author)) return null;
  const title = text(raw.title)?.trim();
  const template = text(raw.template);
  const model = text(raw.model)?.trim();
  const licence = text(raw.licence)?.trim();
  const modality = raw.modality === 'image' || raw.modality === 'video' ? raw.modality : null;
  const publishedAt = text(raw.published_at);
  if (!isPublicId(raw.public_id) || !title || template === null || template.trim() === '' || !model || !licence
      || !modality || !isHandle(raw.author.handle) || publishedAt === null || Number.isNaN(Date.parse(publishedAt))) {
    return null;
  }
  const variables = (Array.isArray(raw.variables) ? raw.variables : [])
    .filter(isObject)
    .map((v) => ({ name: text(v.name), default: text(v.default) ?? '' }))
    .filter((v): v is PublicVariable => v.name !== null && v.name !== '');
  const inputs = (Array.isArray(raw.inputs) ? raw.inputs : [])
    .filter(isObject)
    .map((s) => ({ slot: text(s.slot), role: text(s.role) ?? '', hint: text(s.hint) }))
    .filter((s): s is PublicInputSlot => s.slot !== null && s.slot !== '');
  const examples = (Array.isArray(raw.examples) ? raw.examples : [])
    .filter(isObject)
    .map((e) => ({ url: webUrl(e.url), content_type: text(e.content_type) ?? '' }))
    .filter((e): e is PublicExample => e.url !== null && /^(image|video)\//.test(e.content_type));
  const from = isObject(raw.made_from) ? raw.made_from : null;
  const madeFrom: MadeFrom | null = from && from.kind === 'gallery' && typeof from.id === 'string' && SLUG.test(from.id)
    ? { kind: 'gallery', id: from.id }
    : from && from.kind === 'published' && isPublicId(from.id)
      ? { kind: 'published', id: from.id }
      : null;
  return {
    public_id: raw.public_id,
    title,
    modality,
    template,
    variables,
    inputs,
    model,
    params: params(raw.params),
    examples,
    made_from: madeFrom,
    runs: count(raw.runs),
    runners: count(raw.runners),
    licence,
    author: { handle: raw.author.handle, followers: count(raw.author.followers) },
    tags: [...new Set((Array.isArray(raw.tags) ? raw.tags : []).filter(isTag))],
    published_at: publishedAt,
  };
}

/** A profile as the pages use it, or null. Its link is shown only when it is an https address. */
export function parseProfile(raw: unknown): PublicProfile | null {
  if (!isObject(raw) || !isHandle(raw.handle)) return null;
  const name = text(raw.display_name)?.trim();
  return {
    handle: raw.handle,
    display_name: name ? name : null,
    link: webUrl(raw.link, ['https:']),
    followers: count(raw.followers),
  };
}

// ---------------------------------------------------------------------------
// Reading the API
// ---------------------------------------------------------------------------

/** The most a page holds, as the API allows. */
const PAGE_SIZE = 100;
/** Pages read before stopping: ten thousand recipes. */
const MAX_PAGES = 100;
/** Profiles read at once. */
const PROFILE_CONCURRENCY = 4;
const TIMEOUT_MS = 10_000;
/** Waits before the second and third try of a request that failed for want of an answer. */
const RETRY_DELAYS_MS = [500, 1500];

/** The API could not be read: why, in words for the warning. */
class Unreadable extends Error { }

interface Answer {
  status: number;
  body: unknown;
}

/** Why an answer is not what was asked for, for a warning. */
function describe({ status, body }: Answer): string {
  const code = isObject(body) && typeof body.code === 'string' ? body.code : null;
  if (status === 404 && code === 'community_disabled') return 'the API has the community switched off (community_disabled)';
  return `HTTP ${status}${code ? ` ${code}` : ''}`;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * One GET, with no credential of any kind. No answer, a 429 or a 5xx is
 * tried again twice; any other answer is returned as it is.
 */
async function get(url: string): Promise<Answer> {
  let why = '';
  for (let attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
    if (attempt > 0) await sleep(RETRY_DELAYS_MS[attempt - 1]);
    try {
      const res = await fetch(url, {
        headers: { Accept: 'application/json' },
        redirect: 'error',
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (res.status !== 429 && res.status < 500) {
        let body: unknown;
        try {
          body = await res.json();
        } catch {
          body = undefined;
        }
        return { status: res.status, body };
      }
      why = `HTTP ${res.status}`;
    } catch (err) {
      const cause = err instanceof Error && err.cause instanceof Error ? `: ${err.cause.message}` : '';
      why = err instanceof Error ? `${err.message}${cause}` : String(err);
    }
  }
  throw new Unreadable(why);
}

/** Every public recipe, newest first, page by page. Throws Unreadable when a page cannot be read. */
async function readRecipes(base: string, warn: (message: string) => void): Promise<PublicRecipe[]> {
  const recipes: PublicRecipe[] = [];
  const seen = new Set<string>();
  let skipped = 0;
  let before: string | null = null;
  let complete = false;
  for (let page = 0; page < MAX_PAGES && !complete; page++) {
    const query = new URLSearchParams({ limit: String(PAGE_SIZE) });
    if (before !== null) query.set('before', before);
    const answer = await get(`${base}/v1/public/recipes?${query}`);
    if (answer.status !== 200) throw new Unreadable(describe(answer));
    if (!Array.isArray(answer.body)) throw new Unreadable('the answer is not a list of recipes');
    const list: unknown[] = answer.body;
    for (const raw of list) {
      const recipe = parseRecipe(raw);
      if (recipe === null) skipped += 1;
      else if (!seen.has(recipe.public_id)) {
        seen.add(recipe.public_id);
        recipes.push(recipe);
      }
    }
    complete = list.length < PAGE_SIZE;
    if (!complete) {
      // The next page starts after the last recipe of this one.
      const last = list[list.length - 1];
      const cursor = isObject(last) && typeof last.public_id === 'string' ? last.public_id : null;
      if (cursor === null || cursor === before) throw new Unreadable('the pages of recipes do not move on');
      before = cursor;
    }
  }
  if (!complete) warn(`read the first ${MAX_PAGES * PAGE_SIZE} public recipes and stopped there`);
  if (skipped > 0) {
    warn(`${skipped} public recipe${skipped === 1 ? '' : 's'} could not be read and ${skipped === 1 ? 'has' : 'have'} no page`);
  }
  return recipes;
}

/**
 * The profile of each handle, where it can be read. A profile that cannot
 * be read only loses its own page; once the API stops answering, the rest
 * are not asked for.
 */
async function readProfiles(base: string, handles: string[], warn: (message: string) => void): Promise<PublicProfile[]> {
  const found = new Map<string, PublicProfile>();
  // Set once a profile gets no answer at all, after its retries.
  const stopped: { why: string | null } = { why: null };
  for (let at = 0; at < handles.length && stopped.why === null; at += PROFILE_CONCURRENCY) {
    await Promise.all(handles.slice(at, at + PROFILE_CONCURRENCY).map(async (handle) => {
      try {
        const answer = await get(`${base}/v1/public/profiles/${encodeURIComponent(handle)}`);
        const profile = answer.status === 200 ? parseProfile(answer.body) : null;
        if (profile && profile.handle === handle) found.set(handle, profile);
        else warn(`the profile of @${handle} could not be read (${answer.status === 200 ? 'not a profile' : describe(answer)}); it has no page`);
      } catch (err) {
        stopped.why ??= err instanceof Error ? err.message : String(err);
      }
    }));
  }
  if (stopped.why !== null) {
    warn(`the API stopped answering while profiles were read (${stopped.why}); the authors left have no page`);
  }
  return handles.flatMap((handle) => {
    const profile = found.get(handle);
    return profile ? [profile] : [];
  });
}

export interface Community {
  /** True when the switch is on and the API listed at least one recipe. */
  on: boolean;
  /** Newest first. Empty when the community is off. */
  recipes: PublicRecipe[];
  /** One for each author whose profile could be read, in the order of their newest recipe. */
  profiles: PublicProfile[];
  /** The app that "Run this", "Follow" and "Report" open. */
  appUrl: string;
}

/**
 * The community as the build sees it. Never throws on what the API does:
 * a failure becomes a warning and a community that is off. Only a
 * malformed switch value throws.
 */
export async function readCommunity(
  env: Env = buildEnv(),
  warn: (message: string) => void = (message) => console.warn(`community: ${message}`),
): Promise<Community> {
  const config = resolveCommunityConfig(env);
  const appUrl = appUrlFrom(env);
  const off: Community = { on: false, recipes: [], profiles: [], appUrl };
  if (!config.enabled) return off;
  const base = apiBaseFrom(env);
  try {
    const recipes = await readRecipes(base, warn);
    if (recipes.length === 0) {
      console.info('community: the API lists no public recipe yet, so there are no community pages');
      return off;
    }
    const handles = [...new Set(recipes.map((recipe) => recipe.author.handle))];
    const profiles = await readProfiles(base, handles, warn);
    return { on: true, recipes, profiles, appUrl };
  } catch (err) {
    const why = err instanceof Unreadable ? err.message : err instanceof Error ? err.message : String(err);
    warn(`the public API at ${base} could not be read (${why}). The build goes on without community pages.`);
    return off;
  }
}

let cached: Promise<Community> | undefined;

/** The community, read once per build; the dev server reads it again on every request. */
export function loadCommunity(): Promise<Community> {
  if ((import.meta as { env?: { DEV?: boolean } }).env?.DEV === true) return readCommunity();
  cached ??= readCommunity();
  return cached;
}

// ---------------------------------------------------------------------------
// Paths and links
// ---------------------------------------------------------------------------

export const COMMUNITY_PATH = '/community';
export const recipePath = (publicId: string) => `${COMMUNITY_PATH}/${publicId}`;
export const tagPath = (tag: string) => `${COMMUNITY_PATH}/tag/${tag}`;
export const profilePath = (handle: string) => `/u/${handle}`;

/** Where a visit from these pages came from, as the app's first-touch record reads it. */
const campaign = (what: string) => `utm_source=godance&utm_medium=community&utm_campaign=${encodeURIComponent(what)}`;

/** "Run this": the Studio with the recipe loaded, ready to run and never run by the link. */
export const runThisUrl = (appUrl: string, recipe: PublicRecipe) =>
  `${appUrl}/create/${recipe.modality}?published=${encodeURIComponent(recipe.public_id)}&${campaign(recipe.public_id)}`;

/** The app's report form, which needs a sign-in. */
export const reportUrl = (appUrl: string, kind: 'published_recipe' | 'profile', id: string) =>
  `${appUrl}/report?kind=${kind}&id=${encodeURIComponent(id)}`;

/** Following someone happens in the app, with a sign-in: this link only opens the button. */
export const followUrl = (appUrl: string, handle: string) =>
  `${appUrl}/following?follow=${encodeURIComponent(handle)}&${campaign(handle)}`;

/** The canonical page for each licence the API offers today, and two more an operator may add. */
export const LICENCE_URLS: Record<string, string> = {
  'CC0-1.0': 'https://creativecommons.org/publicdomain/zero/1.0/',
  'CC-BY-4.0': 'https://creativecommons.org/licenses/by/4.0/',
  'CC-BY-SA-4.0': 'https://creativecommons.org/licenses/by-sa/4.0/',
  'CC-BY-NC-4.0': 'https://creativecommons.org/licenses/by-nc/4.0/',
};

/** What each licence lets others do, in a line. */
export const LICENCE_TERMS: Record<string, string> = {
  'CC0-1.0': 'Anyone may reuse the prompt and the examples, for any purpose, without asking.',
  'CC-BY-4.0': 'Anyone may reuse the prompt and the examples, for any purpose, crediting the author.',
  'CC-BY-SA-4.0': 'Anyone may reuse the prompt and the examples, crediting the author and sharing alike.',
  'CC-BY-NC-4.0': 'Anyone may reuse the prompt and the examples, crediting the author, but not commercially.',
};

// ---------------------------------------------------------------------------
// Views
// ---------------------------------------------------------------------------

/** Every tag in use, most-used first. */
export function communityTags(recipes: PublicRecipe[]): { tag: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const recipe of recipes) for (const tag of recipe.tags) counts.set(tag, (counts.get(tag) ?? 0) + 1);
  return [...counts]
    .map(([tag, n]) => ({ tag, count: n }))
    .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
}

/** The prompt the template makes with every default; the template as written when it cannot be rendered. */
export function promptWithDefaults(recipe: PublicRecipe): string {
  try {
    return renderTemplate(recipe.template, recipe.variables);
  } catch {
    return recipe.template;
  }
}

/** The name an author goes by: their display name, or their handle. */
export const authorName = (handle: string, profile: PublicProfile | undefined) => profile?.display_name ?? `@${handle}`;

/** "16 November 2026". */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}

/** The day part of an ISO time, for <time datetime>. */
export const isoDay = (iso: string) => new Date(iso).toISOString().slice(0, 10);

/** "Run 12 times by 5 people". Its author's own runs are not counted. */
export function runsText(recipe: Pick<PublicRecipe, 'runs' | 'runners'>): string {
  if (recipe.runs === 0) return 'Not run by anyone else yet';
  return `Run ${recipe.runs.toLocaleString('en-US')} ${recipe.runs === 1 ? 'time' : 'times'} by `
    + `${recipe.runners.toLocaleString('en-US')} ${recipe.runners === 1 ? 'person' : 'people'}`;
}

export const followersText = (n: number) => `${n.toLocaleString('en-US')} ${n === 1 ? 'follower' : 'followers'}`;

/** The start of a text on one line, cut at a word, for a meta description. */
export function excerpt(value: string, max = 155): string {
  const line = value.replace(/\s+/g, ' ').trim();
  if (line.length <= max) return line;
  const cut = line.slice(0, max - 1);
  const atWord = cut.lastIndexOf(' ');
  return `${(atWord > max * 0.6 ? cut.slice(0, atWord) : cut).replace(/[\s,;:.\-–—]+$/, '')}…`;
}

/** A parameter value as text: strings as they are, everything else as JSON. */
export const formatParam = (value: unknown) => (typeof value === 'string' ? value : JSON.stringify(value));

/** The first example that is a picture, for Open Graph and the cards. */
export const firstImage = (recipe: PublicRecipe) => recipe.examples.find((e) => e.content_type.startsWith('image/')) ?? null;

/**
 * JSON that is safe inside a <script> element: "<" is written as <, so
 * no text in a recipe can close the element.
 */
export function scriptJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c');
}

// ---------------------------------------------------------------------------
// The API call
// ---------------------------------------------------------------------------

/** The API host, as the docs page writes it. */
export const API_ORIGIN = 'https://api.godance.ai';

/**
 * The request that runs the recipe with its defaults: the model and params
 * it was published with, and `recipe.published_id`. The API renders the
 * version its author published.
 */
export function requestBody(recipe: PublicRecipe): Record<string, unknown> {
  // An input image for each slot, by name: FILE_ID, or FILE_ID_1 … with several.
  const fileId = (i: number) => (recipe.inputs.length === 1 ? 'FILE_ID' : `FILE_ID_${i + 1}`);
  const body: Record<string, unknown> = {
    model: recipe.model,
    recipe: {
      published_id: recipe.public_id,
      variables: Object.fromEntries(recipe.variables.map((variable) => [variable.name, variable.default])),
      ...(recipe.inputs.length > 0
        ? { inputs: Object.fromEntries(recipe.inputs.map((slot, i) => [slot.slot, { file_id: fileId(i) }])) }
        : {}),
    },
  };
  for (const [key, value] of Object.entries(recipe.params)) {
    if (!(key in body)) body[key] = value;
  }
  return body;
}

export const generationsEndpoint = (recipe: PublicRecipe) =>
  `${API_ORIGIN}/v1/${recipe.modality === 'video' ? 'videos' : 'images'}/generations`;

/**
 * The curl command that runs it. The body goes in through a quoted
 * here-document, so the shell reads it exactly as written: pretty-printed
 * JSON, in which no line can be the bare closing word.
 */
export function createSnippet(recipe: PublicRecipe): string {
  return [
    `curl ${generationsEndpoint(recipe)} \\`,
    '  -H "Authorization: Bearer $GODANCE_API_KEY" \\',
    '  -H "Content-Type: application/json" \\',
    "  --data-binary @- <<'JSON'",
    JSON.stringify(requestBody(recipe), null, 2),
    'JSON',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Structured data
// ---------------------------------------------------------------------------

const godance = (site: URL | string) => ({ '@type': 'Organization', name: 'Godance', url: new URL('/', site).toString() });

/** The author as a Person: their name, their handle, and their page when it is built. */
function person(handle: string, profile: PublicProfile | undefined, site: URL | string) {
  return {
    '@type': 'Person',
    name: authorName(handle, profile),
    alternateName: `@${handle}`,
    ...(profile ? { url: new URL(profilePath(handle), site).toString() } : {}),
  };
}

/**
 * A recipe page as one graph: the page, published by Godance; the recipe,
 * created by its author and published by Godance, under its licence; and
 * each example, created by the author from the recipe.
 */
export function recipeJsonLd(
  recipe: PublicRecipe,
  profile: PublicProfile | undefined,
  site: URL | string,
  madeFromUrl: string | null,
) {
  const url = new URL(recipePath(recipe.public_id), site).toString();
  const author = person(recipe.author.handle, profile, site);
  const media = recipe.examples.map((example, i) => ({
    '@type': example.content_type.startsWith('video/') ? 'VideoObject' : 'ImageObject',
    '@id': `${url}#example-${i + 1}`,
    name: `${recipe.title}, example ${i + 1}`,
    contentUrl: example.url,
    encodingFormat: example.content_type,
    uploadDate: recipe.published_at,
    creator: author,
    isBasedOn: { '@id': `${url}#recipe` },
  }));
  const work = {
    '@type': 'CreativeWork',
    '@id': `${url}#recipe`,
    name: recipe.title,
    text: recipe.template,
    url,
    creator: author,
    author,
    publisher: godance(site),
    license: LICENCE_URLS[recipe.licence] ?? recipe.licence,
    datePublished: recipe.published_at,
    ...(recipe.tags.length > 0 ? { keywords: recipe.tags.join(', ') } : {}),
    ...(madeFromUrl ? { isBasedOn: madeFromUrl } : {}),
    interactionStatistic: {
      '@type': 'InteractionCounter',
      interactionType: 'https://schema.org/UseAction',
      userInteractionCount: recipe.runs,
    },
    ...(media.length > 0 ? { associatedMedia: media.map((m) => ({ '@id': m['@id'] })) } : {}),
  };
  const page = {
    '@type': 'WebPage',
    '@id': url,
    url,
    name: recipe.title,
    description: excerpt(promptWithDefaults(recipe)),
    inLanguage: 'en',
    datePublished: recipe.published_at,
    publisher: godance(site),
    mainEntity: { '@id': `${url}#recipe` },
  };
  return { '@context': 'https://schema.org', '@graph': [page, work, ...media] };
}

/** A profile page: the Person, how many follow them, and their recipes. */
export function profileJsonLd(profile: PublicProfile, recipes: PublicRecipe[], site: URL | string) {
  const url = new URL(profilePath(profile.handle), site).toString();
  return {
    '@context': 'https://schema.org',
    '@type': 'ProfilePage',
    '@id': url,
    url,
    name: `${authorName(profile.handle, profile)} (@${profile.handle})`,
    publisher: godance(site),
    mainEntity: {
      ...person(profile.handle, profile, site),
      ...(profile.link ? { sameAs: [profile.link] } : {}),
      interactionStatistic: {
        '@type': 'InteractionCounter',
        interactionType: 'https://schema.org/FollowAction',
        userInteractionCount: profile.followers,
      },
    },
    hasPart: recipes.map((recipe) => ({
      '@type': 'CreativeWork',
      name: recipe.title,
      url: new URL(recipePath(recipe.public_id), site).toString(),
    })),
  };
}

/** A list page of recipes, newest first. */
export function collectionJsonLd(name: string, description: string, path: string, recipes: PublicRecipe[], site: URL | string) {
  return {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name,
    description,
    url: new URL(path, site).toString(),
    publisher: godance(site),
    mainEntity: {
      '@type': 'ItemList',
      numberOfItems: recipes.length,
      itemListElement: recipes.map((recipe, i) => ({
        '@type': 'ListItem',
        position: i + 1,
        name: recipe.title,
        url: new URL(recipePath(recipe.public_id), site).toString(),
      })),
    },
  };
}
