import type { Page, Request } from '@playwright/test';
import { GALLERY_ORIGIN } from './api-origin';
import { jobId, makeJob, MEDIA_ORIGIN, MODELS, mockEstimates, serveJob, serveMedia } from './generation';
import { expect, makeUser, sessionStarted, type MockApi } from './mock-api';
import {
  IMAGE_SLUG,
  imageRecipe,
  indexEntry,
  PROMPT_EN,
  PROMPT_ZH,
  test,
  VIDEO_SLUG,
  videoRecipe,
  type MockGallery,
} from './mock-gallery';
import { fillSignUp, signInWithPassword, socialProvider, submitCode } from './sign-in';

// Round 8 brief 03: a recipe's "Run this" link opens the Studio with the
// recipe loaded, through every way of signing in, and never runs it; an
// empty Studio offers examples from the gallery.

const RECIPE_LINK = `/create/image?example=${IMAGE_SLUG}`;
const SIGN_IN_LINE = 'Sign in and the recipe you picked from the gallery will be ready to run.';
const SIGN_UP_LINE = 'Create your account and the recipe you picked from the gallery will be ready to run.';
const NOT_LOADED = "That recipe couldn't be loaded from the gallery, so the form starts empty.";
const EXAMPLES = 'Start from an example';

/** The Studio's catalog, prices and media. */
async function studioMocks(page: Page, api: MockApi) {
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
}

async function openStudio(page: Page, api: MockApi, path: string) {
  await api.signIn();
  await studioMocks(page, api);
  await page.goto(path);
  await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
}

const prompt = (page: Page) => page.getByLabel('Prompt', { exact: true });

/** The form as imageRecipe() fills it: its model, prompt, size and count, and where it came from. */
async function expectImageRecipe(page: Page) {
  await expect(page).toHaveURL(RECIPE_LINK);
  await expect(prompt(page)).toHaveValue(PROMPT_EN);
  await expect(page.getByLabel('Model', { exact: true })).toHaveValue('gpt-image-1.5');
  await expect(page.getByLabel('Size')).toHaveValue('1536x1024');
  await expect(page.getByLabel('Number of images')).toHaveValue('2');
  await expect(page.getByText('From the gallery:')).toBeVisible();
}

/** Every generation request sent. Loading a recipe sends none. */
function generations(api: MockApi) {
  return [...api.callsTo('POST', '/v1/images/generations'), ...api.callsTo('POST', '/v1/videos/generations')];
}

/** Resolves once the gallery's index has been answered or has failed, so a test can check what the page made of it. */
function indexSettles(page: Page): Promise<unknown> {
  const isIndex = (r: Request) => r.url() === `${GALLERY_ORIGIN}/gallery/index.json`;
  return Promise.race([
    page.waitForEvent('requestfinished', isIndex),
    page.waitForEvent('requestfailed', isIndex),
  ].map((p) => p.catch(() => undefined)));
}

/** Two frames: whatever an answered request makes React render is on the page. */
async function settled(page: Page) {
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
}

test.describe('a recipe link, signed in', () => {
  test('fills the form, says where it came from, and runs nothing', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, RECIPE_LINK);

    await expectImageRecipe(page);
    const title = page.getByRole('link', { name: 'Paper lantern street at dusk' });
    await expect(title).toHaveAttribute('href', `${GALLERY_ORIGIN}/gallery/${IMAGE_SLUG}`);
    await expect(title).toHaveAttribute('target', '_blank');
    // Priced and ready: only the visitor's own click sends it.
    await expect(page.getByRole('button', { name: 'Generate $0.10' })).toBeEnabled();
    expect(generations(api)).toEqual([]);
    // One request, for this recipe, without the cookie the browser holds for the gallery's site.
    expect(gallery.requests).toEqual([{ path: `/gallery/${IMAGE_SLUG}.json`, cookie: null }]);
    await expect(page.getByRole('heading', { name: EXAMPLES })).toHaveCount(0);
  });

  test('a video recipe opened on the image page moves to the video page', async ({ page, api, gallery }) => {
    gallery.serveRecipe(videoRecipe());
    await openStudio(page, api, `/create/image?example=${VIDEO_SLUG}`);

    await expect(page).toHaveURL(`/create/video?example=${VIDEO_SLUG}`);
    await expect(prompt(page)).toHaveValue(/^Koi circling under falling rain/);
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('kling-3.0');
    await expect(page.getByLabel('Size')).toHaveValue('1920x1080');
    await expect(page.getByLabel('Duration')).toHaveValue('10');
    // Fetched once, though both pages loaded it.
    expect(gallery.recipeRequests()).toEqual([`/gallery/${VIDEO_SLUG}.json`]);
    expect(generations(api)).toEqual([]);
  });

  test('an image recipe opened on the video page moves to the image page', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, `/create/video?example=${IMAGE_SLUG}`);

    await expectImageRecipe(page);
  });

  test('?job= wins over ?example=, which wins over loose values', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    const job = makeJob({ id: jobId(7), prompt: 'A lighthouse on a sea cliff at dusk' });
    serveJob(api, job);

    await openStudio(page, api, `/create/image?job=${job.id}&example=${IMAGE_SLUG}&prompt=Loose`);
    await expect(prompt(page)).toHaveValue('A lighthouse on a sea cliff at dusk');
    expect(gallery.requests).toEqual([]);

    await page.goto(`/create/image?example=${IMAGE_SLUG}&model=sdxl&prompt=Loose+values`);
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('gpt-image-1.5');
  });

  test('an example that is not a valid slug asks the gallery for no recipe', async ({ page, api, gallery }) => {
    await api.signIn();
    await studioMocks(page, api);

    for (const example of ['Paper-Lantern', '../index', 'a'.repeat(81), 'koi.json', '%2e%2e%2fadmin', '']) {
      await page.goto(`/create/image?example=${encodeURIComponent(example)}`);
      await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
      await expect(prompt(page)).toHaveValue('');
      await expect(page.getByText(NOT_LOADED)).toHaveCount(0);
    }
    expect(gallery.recipeRequests()).toEqual([]);
  });

  test('a missing recipe opens the Studio empty, with a note', async ({ page, api, gallery }) => {
    // Nothing served: the gallery answers 404, as it does for every recipe while it is switched off.
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText(NOT_LOADED)).toBeVisible();
    await expect(prompt(page)).toHaveValue('');
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('flux-2-pro');
    expect(gallery.recipeRequests()).toEqual([`/gallery/${IMAGE_SLUG}.json`]);
  });

  test('so does a gallery out of reach, which offers no examples either', async ({ page, api, gallery }) => {
    gallery.unreachable();
    const index = indexSettles(page);
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText(NOT_LOADED)).toBeVisible();
    await expect(prompt(page)).toHaveValue('');
    await index;
    await settled(page);
    await expect(page.getByRole('heading', { name: EXAMPLES })).toHaveCount(0);
  });

  test('so does a recipe that is not the shape it should be', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe({ modality: 'AUDIO', run: 'gpt-image-1.5' }));
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText(NOT_LOADED)).toBeVisible();
    await expect(prompt(page)).toHaveValue('');
  });

  test('a model the catalog lacks gives way to the default, and the Studio says so', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe({
      run: { model: 'imagen-9', prompt_lang: 'en', params: { size: '1536x1024', n: 2 } },
    }));
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('flux-2-pro');
    await expect(page.getByText("“imagen-9” isn't available, so FLUX.2 [pro] is selected.")).toBeVisible();
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    // FLUX.2 [pro] has no 1536x1024 and makes one image at a time.
    await expect(page.getByLabel('Size')).toHaveValue('1024x1024');
    await expect(page.getByLabel('Number of images')).toHaveCount(0);
  });

  test('values the model does not allow, and anything unexpected, are left out', async ({ page, api, gallery }) => {
    const long = 'A street of paper lanterns. '.repeat(200);
    gallery.serveRecipe(imageRecipe({
      prompt: { en: long, zh: PROMPT_ZH },
      run: {
        model: 'gpt-image-1.5',
        prompt_lang: 'en',
        params: { size: '4096x4096', n: 9, seconds: 10, style: 'vivid', quality: 'hd', webhook_url: 'https://evil.example/hook' },
        cost_cents: 1,
      },
      api_key: 'sk-live-never-sent',
    }));
    await openStudio(page, api, RECIPE_LINK);

    // Cut to the Studio's 4,000 characters.
    await expect(prompt(page)).toHaveValue(long.slice(0, 4000));
    await expect(page.getByLabel('Size')).toHaveValue('1024x1024');
    await expect(page.getByLabel('Number of images')).toHaveValue('1');

    // The request the form would send, as View code shows it: nothing the recipe added.
    await page.getByRole('button', { name: /View code/ }).click();
    const request = page.getByRole('figure', { name: 'Start the job' });
    await expect(request).toContainText('"size": "1024x1024"');
    for (const unexpected of ['4096', 'vivid', 'quality', 'seconds', 'webhook', 'sk-live']) {
      await expect(request).not.toContainText(unexpected);
    }
    expect(generations(api)).toEqual([]);
  });

  test('a title link that leaves the gallery is shown as plain text', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe({ url: `https://evil.example/gallery/${IMAGE_SLUG}` }));
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText('Paper lantern street at dusk')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Paper lantern street at dusk' })).toHaveCount(0);
  });
});

test.describe("a recipe's languages", () => {
  test('a recipe in English and Chinese can switch, and its Chinese is marked', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, RECIPE_LINK);
    const english = page.getByRole('button', { name: 'English' });
    const chinese = page.getByRole('button', { name: '中文' });

    await expect(english).toHaveAttribute('aria-pressed', 'true');
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(prompt(page)).not.toHaveAttribute('lang');

    await chinese.click();
    await expect(prompt(page)).toHaveValue(PROMPT_ZH);
    await expect(prompt(page)).toHaveAttribute('lang', 'zh-Hans');
    await expect(chinese).toHaveAttribute('aria-pressed', 'true');
    await expect(chinese).toHaveAttribute('lang', 'zh-Hans');
    await expect(page.getByRole('link', { name: '黄昏的纸灯笼街' })).toHaveAttribute('lang', 'zh-Hans');

    await english.click();
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(page.getByRole('link', { name: 'Paper lantern street at dusk' })).toBeVisible();
    expect(generations(api)).toEqual([]);
  });

  test('a run made in Chinese opens in Chinese', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe({
      run: { model: 'gpt-image-1.5', prompt_lang: 'zh', params: { size: '1536x1024', n: 2 } },
    }));
    await openStudio(page, api, RECIPE_LINK);

    await expect(prompt(page)).toHaveValue(PROMPT_ZH);
    await expect(prompt(page)).toHaveAttribute('lang', 'zh-Hans');
    await expect(page.getByRole('button', { name: '中文' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('a recipe in one language has no switch', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe({ prompt: { en: PROMPT_EN } }));
    await openStudio(page, api, RECIPE_LINK);

    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(page.getByText('From the gallery:')).toBeVisible();
    await expect(page.getByRole('group', { name: 'Prompt language' })).toHaveCount(0);
  });
});

test.describe('signed out, a recipe link waits through every way in', () => {
  /** Opens the recipe link signed out: the sign-in page, saying the recipe will be waiting. */
  async function arrive(page: Page, api: MockApi, gallery: MockGallery) {
    gallery.serveRecipe(imageRecipe());
    await studioMocks(page, api);
    await page.goto(RECIPE_LINK);
    await expect(page).toHaveURL('/login');
    await expect(page.getByText(SIGN_IN_LINE, { exact: true })).toBeVisible();
    // The app's words alone: nothing from the link is shown, and nothing is fetched yet.
    await expect(page.getByText(IMAGE_SLUG)).toHaveCount(0);
    expect(gallery.requests).toEqual([]);
  }

  test('a password sign-in', async ({ page, api, gallery }) => {
    api.on('POST', '/v1/auth/login', sessionStarted('tok-login')).mockUser();
    await arrive(page, api, gallery);
    await signInWithPassword(page);

    await expectImageRecipe(page);
    expect(generations(api)).toEqual([]);
  });

  test('a new account, by way of the link to sign-up', async ({ page, api, gallery }) => {
    api.on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201)).mockUser();
    await arrive(page, api, gallery);
    await page.getByRole('link', { name: 'Create an account' }).click();
    await expect(page.getByText(SIGN_UP_LINE, { exact: true })).toBeVisible();
    await fillSignUp(page);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expectImageRecipe(page);
    expect(generations(api)).toEqual([]);
  });

  test('a social sign-in', async ({ page, api, gallery, baseURL }) => {
    socialProvider(api, baseURL!, 'signed in').mockUser();
    await arrive(page, api, gallery);
    await page.getByRole('button', { name: 'Continue with Google' }).click();

    await expectImageRecipe(page);
    expect(generations(api)).toEqual([]);
  });

  test('a social sign-in that asks for a code', async ({ page, api, gallery, baseURL }) => {
    socialProvider(api, baseURL!, 'two-factor')
      .on('POST', '/v1/auth/2fa/verify', sessionStarted('tok-after-code'))
      .mockUser();
    await arrive(page, api, gallery);
    await page.getByRole('button', { name: 'Continue with Google' }).click();
    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    await submitCode(page, '123456');

    await expectImageRecipe(page);
    expect(generations(api)).toEqual([]);
  });

  test('a video recipe link gets the same line', async ({ page }) => {
    await page.goto(`/create/video?example=${VIDEO_SLUG}`);

    await expect(page).toHaveURL('/login');
    await expect(page.getByText(SIGN_IN_LINE, { exact: true })).toBeVisible();
  });

  test('other links get no line', async ({ page }) => {
    for (const path of ['/keys', '/create/image', '/create/image?example=Paper-Lantern', `${RECIPE_LINK}&job=${jobId(1)}`]) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
      await expect(page.getByText('recipe you picked')).toHaveCount(0);
    }
  });
});

test.describe('an empty Studio', () => {
  const IMAGE_EXAMPLES = Array.from({ length: 8 }, (_, i) => indexEntry(`street-scene-${i + 1}`, `Street scene ${i + 1}`));
  const VIDEO_EXAMPLES = [
    indexEntry(VIDEO_SLUG, 'Koi pond in the rain', 'VIDEO'),
    indexEntry('rain-on-a-tin-roof', 'Rain on a tin roof', 'VIDEO'),
  ];
  const examples = (page: Page) => page.getByRole('region', { name: EXAMPLES }).getByRole('listitem');

  test('offers up to six examples of its own kind, read once per session', async ({ page, api, gallery }) => {
    gallery.serveIndex({ recipes: [...IMAGE_EXAMPLES, ...VIDEO_EXAMPLES] });
    await openStudio(page, api, '/create/image');

    await expect(examples(page)).toHaveCount(6);
    await expect(examples(page).first()).toHaveText('Street scene 1');
    await expect(examples(page).first().locator('img'))
      .toHaveAttribute('src', `${MEDIA_ORIGIN}/gallery/street-scene-1-480.webp`);
    await expect(page.getByRole('link', { name: 'Browse the gallery' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/gallery`);

    await page.getByRole('navigation', { name: 'Studio' }).getByRole('link', { name: 'Video' }).click();
    await expect(page).toHaveURL('/create/video');
    await expect(examples(page)).toHaveText(['Koi pond in the rain', 'Rain on a tin roof']);

    await page.reload();
    await expect(examples(page)).toHaveCount(2);
    expect(gallery.requests).toEqual([{ path: '/gallery/index.json', cookie: null }]);
  });

  test('choosing one loads it as its link would, and Back returns to the empty Studio', async ({ page, api, gallery }) => {
    gallery.serveIndex({ recipes: [indexEntry(IMAGE_SLUG, 'Paper lantern street at dusk'), ...IMAGE_EXAMPLES] })
      .serveRecipe(imageRecipe());
    await openStudio(page, api, '/create/image');

    await page.getByRole('button', { name: 'Paper lantern street at dusk' }).click();
    await expectImageRecipe(page);
    await expect(page.getByRole('heading', { name: EXAMPLES })).toHaveCount(0);
    expect(generations(api)).toEqual([]);

    await page.goBack();
    await expect(page).toHaveURL('/create/image');
    await expect(prompt(page)).toHaveValue('');
    await expect(page.getByRole('heading', { name: EXAMPLES })).toBeVisible();
  });

  const NO_ROW: { when: string; index: Record<string, unknown> | 'missing' | 'unreachable' }[] = [
    { when: 'the gallery has none of its kind', index: { recipes: VIDEO_EXAMPLES } },
    { when: 'the gallery is empty', index: { recipes: [] } },
    { when: 'the gallery is switched off', index: 'missing' },
    { when: 'the gallery is out of reach', index: 'unreachable' },
    { when: 'the list is not the shape it should be', index: { items: IMAGE_EXAMPLES } },
  ];
  for (const { when, index } of NO_ROW) {
    test(`shows no examples when ${when}`, async ({ page, api, gallery }) => {
      gallery.serveIndex(index);
      const answered = indexSettles(page);
      await openStudio(page, api, '/create/image');

      await answered;
      await settled(page);
      await expect(page.getByRole('heading', { name: EXAMPLES })).toHaveCount(0);
      await expect(prompt(page)).toBeEnabled();
    });
  }
});

test.describe('the balance', () => {
  async function setVisibility(page: Page, state: 'visible' | 'hidden') {
    await page.evaluate((s) => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => s });
      document.dispatchEvent(new Event('visibilitychange'));
    }, state);
  }

  test("is read again when the Studio's tab comes back into view", async ({ page, api }) => {
    await openStudio(page, api, '/create/image');
    await expect(page.getByText('Balance $12.34')).toBeVisible();

    // Topped up in another tab.
    api.mockUser(makeUser({ creditBalanceCents: 20_34 }));
    const reads = api.callsTo('GET', '/v1/auth/me').length;
    await setVisibility(page, 'hidden');
    await settled(page);
    expect(api.callsTo('GET', '/v1/auth/me')).toHaveLength(reads);

    await setVisibility(page, 'visible');
    await expect(page.getByText('Balance $20.34')).toBeVisible();
  });
});
