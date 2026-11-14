import type { Page } from '@playwright/test';
import { accepted, inProgress, jobId, makeJob, MODELS, mockEstimates, serveJob, serveMedia } from './generation';
import { MockApi, expect, makeUser, problem, sessionStarted } from './mock-api';
import type { Reply } from './mock-api';
import { IMAGE_SLUG, imageRecipe, PROMPT_EN, PROMPT_ZH, test } from './mock-gallery';
import { fillSignUp, signInWithPassword, socialProvider } from './sign-in';

// Round 10 brief 01: the app keeps what brought a visitor here (campaign
// tags, a gallery recipe, the landing page, the referring site), hands it in
// once after sign-in, and the Studio says which gallery recipe a run is of.

// Mirrors src/lib/first-touch.ts, which can't be imported at runtime here.
const STORAGE_KEY = 'godance.first-touch';
const FIRST_TOUCH = '/v1/attribution/first-touch';
const DAY_MS = 24 * 60 * 60 * 1000;
const CAMPAIGN = 'utm_source=newsletter&utm_medium=email&utm_campaign=launch';
const CAMPAIGN_TOUCH = { utm_source: 'newsletter', utm_medium: 'email', utm_campaign: 'launch' };

type Touch = Record<string, string>;

function storedTouch(page: Page): Promise<Touch | null> {
  return page.evaluate((key) => {
    const raw = localStorage.getItem(key);
    return raw === null ? null : (JSON.parse(raw) as { touch: Record<string, string> }).touch;
  }, STORAGE_KEY);
}

/** A first touch kept by an earlier visit, `ageDays` ago, put in place before the app's first load only. */
async function seedTouch(page: Page, touch: Touch, ageDays = 0): Promise<void> {
  await page.addInitScript(([key, value, expires]) => {
    if (sessionStorage.getItem('e2e.first-touch-seeded')) return;
    sessionStorage.setItem('e2e.first-touch-seeded', '1');
    localStorage.setItem(key, JSON.stringify({ touch: value, expires }));
  }, [STORAGE_KEY, touch, Date.now() + 30 * DAY_MS - ageDays * DAY_MS] as const);
}

const claims = (api: MockApi) => api.callsTo('POST', FIRST_TOUCH);

test.describe('what brought a visitor here', () => {
  test('a password sign-up hands it in once, then forgets it', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201)).mockUser();

    await page.goto(`/signup?${CAMPAIGN}`);
    await expect.poll(() => storedTouch(page)).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
    expect(claims(api)).toHaveLength(0);
    await fillSignUp(page);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page).toHaveURL('/dashboard');
    await expect.poll(() => claims(api).length).toBe(1);
    expect(claims(api)[0].body).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
    await expect.poll(() => storedTouch(page)).toBeNull();

    // Signed in from the first moment of the next load: nothing new is kept or sent.
    await page.goto(`/dashboard?utm_source=later`);
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(await storedTouch(page)).toBeNull();
    expect(claims(api)).toHaveLength(1);
  });

  test('a social sign-in hands it in after the round trip', async ({ page, api }) => {
    await page.goto(`/login?${CAMPAIGN}`);
    await expect.poll(() => storedTouch(page)).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/login' });
    socialProvider(api, new URL(page.url()).origin, 'signed in').mockUser();

    await page.getByRole('button', { name: 'Continue with Google' }).click();

    await expect(page).toHaveURL('/dashboard');
    await expect.poll(() => claims(api).length).toBe(1);
    // What the first load said, not the page the provider came back to.
    expect(claims(api)[0].body).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/login' });
    await expect.poll(() => storedTouch(page)).toBeNull();
  });

  test('a sign-in in another tab hands it in from there, once', async ({ page, api }) => {
    api.mockUser();
    await page.goto(`/signup?${CAMPAIGN}`);
    await expect.poll(() => storedTouch(page)).not.toBeNull();

    const other = await page.context().newPage();
    const otherApi = new MockApi(other);
    await otherApi.install();
    otherApi.on('POST', '/v1/auth/login', sessionStarted('tok-other-tab')).mockUser();
    await other.goto('/login?utm_source=other-tab');
    await signInWithPassword(other);

    await expect(other).toHaveURL('/dashboard');
    await expect.poll(() => claims(otherApi).length).toBe(1);
    expect(claims(otherApi)[0].body).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
    await expect.poll(() => storedTouch(page)).toBeNull();

    // The first tab, now signed in too, has nothing left to hand in.
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(claims(api)).toHaveLength(0);
    expect(otherApi.unhandled).toEqual([]);
    await other.close();
  });

  test('a later visit does not replace it', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', sessionStarted('tok-login')).mockUser();

    await page.goto(`/signup?${CAMPAIGN}`);
    await expect.poll(() => storedTouch(page)).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
    await page.goto('/login?utm_source=retargeting&utm_campaign=second');
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    expect(await storedTouch(page)).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
    await signInWithPassword(page);

    await expect(page).toHaveURL('/dashboard');
    await expect.poll(() => claims(api).length).toBe(1);
    expect(claims(api)[0].body).toEqual({ ...CAMPAIGN_TOUCH, landing_path: '/signup' });
  });

  test('a "Run this" link keeps the recipe, and the page it opened', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    api.on('POST', '/v1/auth/login', sessionStarted('tok-login')).mockUser()
      .on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);

    // Signed out, the Studio sends the visitor to sign in first.
    await page.goto(`/create/image?example=${IMAGE_SLUG}&utm_source=x`);
    await expect(page).toHaveURL('/login');
    await expect.poll(() => storedTouch(page))
      .toEqual({ utm_source: 'x', gallery_recipe: IMAGE_SLUG, landing_path: '/create/image' });
    await signInWithPassword(page);

    await expect(page).toHaveURL(`/create/image?example=${IMAGE_SLUG}&utm_source=x`);
    await expect.poll(() => claims(api).length).toBe(1);
    expect(claims(api)[0].body)
      .toEqual({ utm_source: 'x', gallery_recipe: IMAGE_SLUG, landing_path: '/create/image' });
  });

  test('the site that sent the visitor is kept by its host alone', async ({ page }) => {
    await page.goto('/login', { referer: 'https://x.com/godance/status/1234?s=20' });

    await expect.poll(() => storedTouch(page)).toEqual({ landing_path: '/login', referrer_host: 'x.com' });
  });

  test("the page's query is never kept, nor an example that is not a recipe's", async ({ page }) => {
    await page.goto('/reset-password?token=never-keep-this');
    await expect.poll(() => storedTouch(page)).toEqual({ landing_path: '/reset-password' });

    await page.evaluate((key) => localStorage.removeItem(key), STORAGE_KEY);
    await page.goto('/create/image?example=..%2Fadmin');
    await expect(page).toHaveURL('/login');
    await expect.poll(() => storedTouch(page)).toEqual({ landing_path: '/create/image' });

    // An example names a recipe only on a Studio page.
    await page.evaluate((key) => localStorage.removeItem(key), STORAGE_KEY);
    await page.goto(`/login?example=${IMAGE_SLUG}`);
    await expect.poll(() => storedTouch(page)).toEqual({ landing_path: '/login' });
  });

  for (const [answer, reply] of [
    ['accepted', { status: 204 }],
    ['refused as malformed', problem(400, 'Nothing to record.', 'invalid_first_touch')],
    ['too many tries', problem(429, 'Too Many Requests')],
    ['a server error', problem(500, 'Internal Server Error')],
  ] as [string, Reply][]) {
    test(`it is forgotten after ${answer}, and nothing is shown`, async ({ page, api }) => {
      await seedTouch(page, { utm_source: 'newsletter', landing_path: '/signup' });
      await api.signIn();
      api.on('POST', FIRST_TOUCH, reply);

      await page.goto('/dashboard');

      await expect.poll(() => claims(api).length).toBe(1);
      expect(claims(api)[0].body).toEqual({ utm_source: 'newsletter', landing_path: '/signup' });
      await expect.poll(() => storedTouch(page)).toBeNull();
      await expect(page).toHaveURL('/dashboard');
      await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    });
  }

  test('one kept for more than 30 days is dropped unused', async ({ page, api }) => {
    await seedTouch(page, { utm_source: 'newsletter' }, 31);
    await api.signIn();

    await page.goto('/dashboard');

    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)).toBeNull();
    expect(claims(api)).toHaveLength(0);
  });

  test('only the fields the API takes are handed in, each cut to its limit', async ({ page, api }) => {
    await seedTouch(page, { utm_source: 'n'.repeat(140), utm_term: 'shoes', email: 'ada@example.com' });
    await api.signIn();

    await page.goto('/dashboard');

    await expect.poll(() => claims(api).length).toBe(1);
    expect(claims(api)[0].body).toEqual({ utm_source: 'n'.repeat(100) });
  });

  test("a child's account waiting for approval hands nothing in", async ({ page, api }) => {
    await seedTouch(page, { utm_source: 'newsletter' });
    await api.signIn(makeUser({ ageStatus: 'CONSENT_PENDING', parentEmailMasked: 'p•••@example.com' }));

    await page.goto('/dashboard');

    await expect(page.getByText(/approv/i).first()).toBeVisible();
    expect(claims(api)).toHaveLength(0);
    expect(await storedTouch(page)).toEqual({ utm_source: 'newsletter' });
  });
});

test.describe('a run from a gallery recipe', () => {
  const RECIPE_LINK = `/create/image?example=${IMAGE_SLUG}`;
  const RECIPE_RUN = { model: 'gpt-image-1.5', size: '1536x1024', n: 2, async: true };
  const prompt = (page: Page) => page.getByLabel('Prompt', { exact: true });
  const runs = (api: MockApi) => api.callsTo('POST', '/v1/images/generations').map((call) => call.body);

  async function openRecipe(page: Page, api: MockApi) {
    await api.signIn();
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await serveMedia(page);
    const done = makeJob({ id: jobId(1), model: 'gpt-image-1.5', prompt: PROMPT_EN });
    api.on('POST', '/v1/images/generations', accepted(done.id));
    serveJob(api, inProgress(done, 'QUEUED'));
    await page.goto(RECIPE_LINK);
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
  }

  async function generate(page: Page, api: MockApi) {
    const before = runs(api).length;
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => runs(api).length).toBe(before + 1);
    return runs(api)[before];
  }

  test('says which recipe it is, and stops once the prompt is edited', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openRecipe(page, api);

    expect(await generate(page, api)).toEqual({ ...RECIPE_RUN, prompt: PROMPT_EN, gallery_recipe: IMAGE_SLUG });

    // Still the recipe on its next run, with a different size and count.
    await page.getByLabel('Size').selectOption('1024x1024');
    await page.getByLabel('Number of images').selectOption('1');
    expect(await generate(page, api))
      .toEqual({ ...RECIPE_RUN, size: '1024x1024', n: 1, prompt: PROMPT_EN, gallery_recipe: IMAGE_SLUG });

    await prompt(page).fill(`${PROMPT_EN}, in the rain`);
    expect(await generate(page, api))
      .toEqual({ ...RECIPE_RUN, size: '1024x1024', n: 1, prompt: `${PROMPT_EN}, in the rain` });
  });

  test("its other language is still the recipe; another model's run is not", async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openRecipe(page, api);

    await page.getByRole('button', { name: '中文' }).click();
    await expect(prompt(page)).toHaveValue(PROMPT_ZH);
    expect(await generate(page, api)).toEqual({ ...RECIPE_RUN, prompt: PROMPT_ZH, gallery_recipe: IMAGE_SLUG });

    // The recipe was run on gpt-image-1.5 only.
    await page.getByLabel('Model', { exact: true }).selectOption('flux-2-pro');
    const onFlux = await generate(page, api) as Record<string, unknown>;
    expect(onFlux.model).toBe('flux-2-pro');
    expect(onFlux).not.toHaveProperty('gallery_recipe');

    await page.getByLabel('Model', { exact: true }).selectOption('gpt-image-1.5');
    expect((await generate(page, api) as Record<string, unknown>).gallery_recipe).toBe(IMAGE_SLUG);
  });

  test('the code it shows carries the recipe too', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openRecipe(page, api);

    await page.getByRole('button', { name: /^View code/ }).click();
    const start = page.getByRole('figure', { name: 'Start the job' });
    await expect(start).toContainText(`"gallery_recipe": "${IMAGE_SLUG}"`);

    await prompt(page).fill('Something else entirely');
    await expect(start).not.toContainText('gallery_recipe');
  });

  test('a form that was not loaded from a recipe names none', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await serveMedia(page);
    const done = makeJob({ id: jobId(2), model: 'gpt-image-1.5', prompt: PROMPT_EN });
    api.on('POST', '/v1/images/generations', accepted(done.id));
    serveJob(api, inProgress(done, 'QUEUED'));

    await page.goto(`/create/image?model=gpt-image-1.5&prompt=${encodeURIComponent(PROMPT_EN)}`);
    await expect(prompt(page)).toHaveValue(PROMPT_EN);

    expect(await generate(page, api)).not.toHaveProperty('gallery_recipe');
  });
});
