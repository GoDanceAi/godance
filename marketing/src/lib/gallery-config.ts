/**
 * The gallery switch: the operator's inputs for the gallery pages.
 *
 * These are public values, not secrets. All four ship empty, which keeps the
 * gallery OFF: the build then writes no gallery page, link, JSON file or
 * sitemap entry.
 *
 * The gallery is ON only when `mediaBaseUrl` and `removalContact` are both
 * set and at least one recipe exists (see `loadGallery` in
 * gallery-recipes.ts).
 */
export interface GalleryConfig {
  /**
   * Public base URL of the uploaded media, e.g. "https://media.example.com/gallery".
   * A recipe's media paths are appended to it.
   */
  mediaBaseUrl: string;
  /** Where to ask for a recipe to be removed: an email address or an https URL. */
  removalContact: string;
  /** The licence statement for the generated outputs, shown on every recipe page. */
  outputLicence: string;
  /**
   * Whether recipe pages show "Run this", which opens the recipe in the app.
   * Turn it on only once the app side is live.
   */
  runThis: boolean;
  /**
   * How many published recipes two models must both have a run on before the
   * gallery builds a page comparing them (round 13). Below it there is no
   * page and no link.
   */
  compareMin: number;
}

/** The fewest recipes a comparison page is built from, unless GALLERY_COMPARE_MIN says otherwise. */
export const DEFAULT_COMPARE_MIN = 8;

/**
 * The operator edits the first four values to switch the gallery on.
 * compareMin has a default and needs no edit.
 */
export const galleryConfig: GalleryConfig = {
  mediaBaseUrl: '',
  removalContact: '',
  outputLicence: '',
  runThis: false,
  compareMin: DEFAULT_COMPARE_MIN,
};

/**
 * Environment variables that override the committed values, for local work
 * and tests. A variable that is set wins even when it is empty, so a test can
 * force the gallery off.
 */
export const GALLERY_ENV = {
  mediaBaseUrl: 'GALLERY_MEDIA_BASE_URL',
  removalContact: 'GALLERY_REMOVAL_CONTACT',
  outputLicence: 'GALLERY_OUTPUT_LICENCE',
  runThis: 'GALLERY_RUN_THIS',
  compareMin: 'GALLERY_COMPARE_MIN',
} as const;

type Env = Record<string, string | undefined>;

function flag(value: string, name: string): boolean {
  const v = value.trim().toLowerCase();
  if (['1', 'true', 'on', 'yes'].includes(v)) return true;
  if (['', '0', 'false', 'off', 'no'].includes(v)) return false;
  throw new Error(`gallery: ${name} must be "true" or "false", not "${value}"`);
}

/** The media base as an absolute URL ending in a slash, or '' when unset. */
function mediaBase(value: string): string {
  const trimmed = value.trim();
  if (trimmed === '') return '';
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    throw new Error(`gallery: the media base URL must be an absolute URL, not "${value}"`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new Error(`gallery: the media base URL must be http or https, not "${value}"`);
  }
  if (url.search !== '' || url.hash !== '') {
    throw new Error(`gallery: the media base URL must not carry a query or a fragment: "${value}"`);
  }
  return url.href.endsWith('/') ? url.href : `${url.href}/`;
}

/** A number of recipes: a whole number, at least 1. Empty means the default. */
function recipeCount(value: string, name: string): number {
  const v = value.trim();
  if (v === '') return DEFAULT_COMPARE_MIN;
  if (/^\d+$/.test(v) && Number(v) >= 1) return Number(v);
  throw new Error(`gallery: ${name} must be a whole number of recipes, at least 1, not "${value}"`);
}

/** The removal contact as an email address or an http(s) URL, or '' when unset. */
function contact(value: string): string {
  const trimmed = value.trim();
  if (trimmed === '') return '';
  if (/^https?:\/\/\S+$/i.test(trimmed) || /^[^\s@:/]+@[^\s@:/]+\.[^\s@:/]+$/.test(trimmed)) return trimmed;
  throw new Error(`gallery: the removal contact must be an email address or an https URL, not "${value}"`);
}

/**
 * The switch as the build sees it: the committed values with any environment
 * overrides applied, checked and tidied. A value that is set but malformed
 * throws, so a typo fails the build instead of quietly leaving the gallery off.
 */
export function resolveGalleryConfig(env: Env = globalThis.process?.env ?? {}): GalleryConfig {
  const pick = (key: keyof typeof GALLERY_ENV, committed: string) => env[GALLERY_ENV[key]] ?? committed;
  return {
    mediaBaseUrl: mediaBase(pick('mediaBaseUrl', galleryConfig.mediaBaseUrl)),
    removalContact: contact(pick('removalContact', galleryConfig.removalContact)),
    outputLicence: pick('outputLicence', galleryConfig.outputLicence).trim(),
    runThis: flag(pick('runThis', String(galleryConfig.runThis)), GALLERY_ENV.runThis),
    compareMin: recipeCount(pick('compareMin', String(galleryConfig.compareMin)), GALLERY_ENV.compareMin),
  };
}
