import { isRecipeSlug, parseGalleryIndex, parseRecipe, type GalleryEntry, type GalleryRecipe } from './recipe';

/**
 * The marketing site that serves the gallery's JSON (see lib/recipe). Reading
 * it is a public, static read: no cookie or other credential goes with it, no
 * redirect is followed, and only the one file asked for is fetched.
 */
export const GALLERY_ORIGIN: string = originOf(import.meta.env.VITE_GALLERY_URL) ?? 'https://godance.ai';

const TIMEOUT_MS = 10_000;
const INDEX_CACHE_KEY = 'godance.gallery_index';

/**
 * One recipe, or null when it is missing, the gallery is off or out of reach,
 * or its JSON doesn't match. A slug that isn't valid is never requested.
 */
export async function loadRecipe(slug: string): Promise<GalleryRecipe | null> {
  if (!isRecipeSlug(slug)) return null;
  const json = await getJson(`/gallery/${slug}.json`);
  return json === undefined ? null : parseRecipe(json, { slug, galleryOrigin: GALLERY_ORIGIN });
}

let indexLoad: Promise<GalleryEntry[]> | null = null;

/** The gallery's list of recipes, read once per browser session: empty when the gallery is off or out of reach. */
export function loadGalleryIndex(): Promise<GalleryEntry[]> {
  indexLoad ??= readIndex();
  return indexLoad;
}

async function readIndex(): Promise<GalleryEntry[]> {
  const cached = readCachedIndex();
  if (cached !== undefined) return parseGalleryIndex(cached, GALLERY_ORIGIN);
  const json = await getJson('/gallery/index.json');
  if (json === undefined) return [];
  const entries = parseGalleryIndex(json, GALLERY_ORIGIN);
  // The parsed entries have the endpoint's own shape, so a cached copy is checked again on reading.
  writeCachedIndex({ recipes: entries });
  return entries;
}

/** The parsed JSON of a 2xx answer, or undefined for anything else. */
async function getJson(path: string): Promise<unknown> {
  try {
    const res = await fetch(`${GALLERY_ORIGIN}${path}`, {
      credentials: 'omit',
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    return res.ok ? ((await res.json()) as unknown) : undefined;
  } catch {
    return undefined;
  }
}

function readCachedIndex(): unknown {
  try {
    const raw = sessionStorage.getItem(INDEX_CACHE_KEY);
    return raw === null ? undefined : (JSON.parse(raw) as unknown);
  } catch {
    return undefined;
  }
}

function writeCachedIndex(index: { recipes: GalleryEntry[] }): void {
  try {
    sessionStorage.setItem(INDEX_CACHE_KEY, JSON.stringify(index));
  } catch {
    // Storage full or blocked: the next page load reads the list again.
  }
}

function originOf(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.origin : null;
  } catch {
    return null;
  }
}
