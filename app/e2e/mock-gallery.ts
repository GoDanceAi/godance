import type { Page, Route } from '@playwright/test';
import { GALLERY_ORIGIN } from './api-origin';
import { MEDIA_ORIGIN } from './generation';
import { test as base } from './mock-api';

// The marketing site's gallery JSON, answered in the browser: the endpoints
// the round 8 coordination doc fixes. They are static files readable from
// any origin, so every answer says `access-control-allow-origin: *`, which a
// browser refuses to a request that carries credentials.

export const IMAGE_SLUG = 'paper-lantern-street-at-dusk';
export const VIDEO_SLUG = 'koi-pond-in-the-rain';
export const PROMPT_EN = 'A narrow street of paper lanterns at dusk, warm red light, wet cobblestones';
export const PROMPT_ZH = '黄昏时分挂满纸灯笼的小街，暖红色的灯光，湿润的石板路';

type Json = Record<string, unknown>;

/** An image recipe as /gallery/<slug>.json serves it: gpt-image-1.5 of the e2e catalog, at a size and count it allows. */
export function imageRecipe(overrides: Json = {}): Json {
  return {
    slug: IMAGE_SLUG,
    title: { en: 'Paper lantern street at dusk', zh: '黄昏的纸灯笼街' },
    modality: 'IMAGE',
    prompt: { en: PROMPT_EN, zh: PROMPT_ZH },
    run: { model: 'gpt-image-1.5', prompt_lang: 'en', params: { size: '1536x1024', n: 2 } },
    url: `${GALLERY_ORIGIN}/gallery/${IMAGE_SLUG}`,
    ...overrides,
  };
}

/** A video recipe: kling-3.0 of the e2e catalog, at a size and duration it allows. */
export function videoRecipe(overrides: Json = {}): Json {
  return {
    slug: VIDEO_SLUG,
    title: { en: 'Koi pond in the rain', zh: '雨中的锦鲤池' },
    modality: 'VIDEO',
    prompt: { en: 'Koi circling under falling rain, ripples spreading, slow overhead shot', zh: '雨中锦鲤绕圈游动，涟漪散开，缓慢的俯拍镜头' },
    run: { model: 'kling-3.0', prompt_lang: 'en', params: { size: '1920x1080', seconds: 10 } },
    url: `${GALLERY_ORIGIN}/gallery/${VIDEO_SLUG}`,
    ...overrides,
  };
}

/** An entry of /gallery/index.json, its thumbnail on the storage host serveMedia answers. */
export function indexEntry(slug: string, title: string, modality: 'IMAGE' | 'VIDEO' = 'IMAGE', overrides: Json = {}): Json {
  return {
    slug,
    title: { en: title },
    modality,
    model: modality === 'IMAGE' ? 'gpt-image-1.5' : 'kling-3.0',
    tags: ['e2e'],
    thumb: `${MEDIA_ORIGIN}/gallery/${slug}-480.webp`,
    published_on: '2026-11-21',
    ...overrides,
  };
}

export interface GalleryRequest {
  path: string;
  cookie: string | null;
}

/**
 * The gallery's origin, answered in the browser. Unless a test serves them,
 * the index is empty and every recipe is missing (404), as on a gallery that
 * is switched off. Every request is recorded with the cookies it carried.
 */
export class MockGallery {
  readonly requests: GalleryRequest[] = [];
  private index: Json | 'missing' | 'unreachable' = { recipes: [] };
  private readonly recipes = new Map<string, Json>();
  private down = false;

  constructor(private readonly page: Page) {}

  async install(): Promise<void> {
    // A cookie this browser holds for the gallery's site: a request sent with
    // credentials would carry it, so a test sees one that did.
    await this.page.context().addCookies([{ name: 'marketing_session', value: 'e2e', url: GALLERY_ORIGIN }]);
    await this.page.route(`${GALLERY_ORIGIN}/**`, (route) => this.handle(route));
  }

  /**
   * What /gallery/index.json answers: 'missing' is a 404, as from a gallery
   * that is switched off, and 'unreachable' fails on the network.
   */
  serveIndex(index: Json | 'missing' | 'unreachable'): this {
    this.index = index;
    return this;
  }

  /** Serves a recipe at /gallery/<its slug>.json. */
  serveRecipe(recipe: Json): this {
    this.recipes.set(String(recipe.slug), recipe);
    return this;
  }

  /** The whole gallery out of reach: every request fails on the network. */
  unreachable(): this {
    this.down = true;
    return this;
  }

  /** Paths of the requests for a recipe, as opposed to the index. */
  recipeRequests(): string[] {
    return this.requests.map((r) => r.path).filter((p) => p !== '/gallery/index.json');
  }

  private async handle(route: Route): Promise<void> {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const headers = await request.allHeaders();
    this.requests.push({ path, cookie: headers.cookie ?? null });

    if (this.down || (path === '/gallery/index.json' && this.index === 'unreachable')) {
      await route.abort('internetdisconnected');
      return;
    }
    const recipe = /^\/gallery\/([^/]+)\.json$/.exec(path)?.[1];
    const json = path === '/gallery/index.json' ? this.index : recipe ? this.recipes.get(recipe) : undefined;
    if (json === undefined || typeof json === 'string') {
      // The static host's own not-found page.
      await route.fulfill({ status: 404, contentType: 'text/html', body: '<!doctype html><title>Not found</title>' });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify(json),
    });
  }
}

/** The mock-api test with a `gallery` fixture. Tests that don't ask for it get the gallery aborted, as MockApi does. */
export const test = base.extend<{ gallery: MockGallery }>({
  gallery: async ({ page, api }, use) => {
    // Set up after MockApi's catch-all, so this route is the one that answers the gallery.
    void api;
    const gallery = new MockGallery(page);
    await gallery.install();
    await use(gallery);
  },
});
