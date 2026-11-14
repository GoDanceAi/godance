import type { Page } from '@playwright/test';
import type { JobView, UserRecipe } from '../src/lib/api';
import { jobId, makeJob, mediaUrl, MODELS, mockEstimates, outputsOf, serveJob, serveJobList, serveMedia, serveSaves } from './generation';
import { expect, makeUser, test, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, MUG_TEMPLATE, recipeId, serveRecipes, versionId } from './user-recipes';

// Round 10 brief 03, outside the Studio: the user's recipes, one recipe with
// its versions and its attempts, and two to four generations side by side.

/** An attempt of the first recipe: version 3 with these values. */
function attempt(n: number, overrides: Partial<JobView> = {}): JobView {
  return makeJob({
    id: jobId(n),
    model: 'gpt-image-1.5',
    provider: 'OPENAI',
    prompt: 'A red mug on a kitchen shelf, soft morning light',
    params: { size: '1536x1024', n: 1 },
    outputUrl: mediaUrl(`out-${n}.png`),
    outputs: [{ index: 0, url: mediaUrl(`out-${n}.png`), contentType: 'image/png', sizeBytes: 800_000, revisedPrompt: null }],
    recipe: { id: recipeId(1), version: 3, variables: { color: 'red', surface: 'kitchen shelf' } },
    ...overrides,
  });
}

/** Three attempts, newest first: blue on version 3, red on version 3, and a purged one from version 2. */
function attempts(): JobView[] {
  return [
    attempt(3, {
      createdAt: '2026-11-13T12:30:00Z',
      prompt: 'A blue mug on a kitchen shelf, soft morning light',
      actualCostCents: 5,
      recipe: { id: recipeId(1), version: 3, variables: { color: 'blue', surface: 'kitchen shelf' } },
    }),
    attempt(2, { createdAt: '2026-11-13T12:10:00Z', actualCostCents: 5 }),
    attempt(1, {
      createdAt: '2026-08-01T09:00:00Z',
      actualCostCents: 3,
      prompt: null,
      outputs: [],
      outputUrl: null,
      purged: true,
      mediaExpiresAt: null,
      recipe: { id: recipeId(1), version: 2, variables: null },
    }),
  ];
}

async function open(page: Page, api: MockApi, path: string) {
  await api.signIn();
  await serveMedia(page);
  await page.goto(path);
}

/** A recipe page over `jobs`, each also readable on its own. */
async function openRecipe(page: Page, api: MockApi, recipes: UserRecipe[], jobs: JobView[]) {
  serveRecipes(api, recipes, {
    [recipeId(1)]: [
      makeVersion({
        id: versionId(1, 2), number: 2, template: 'A {{color}} mug on a table', variables: [{ name: 'color', default: 'red' }],
        model: 'flux-2-pro', params: { size: '1024x1024' }, created_at: '2026-11-11T09:00:00Z',
      }),
      makeVersion({
        id: versionId(1, 1), number: 1, template: 'A red mug', variables: [], model: 'flux-2-pro', params: {},
        created_at: '2026-11-10T08:00:00Z',
      }),
    ],
  });
  serveJobList(api, jobs);
  serveSaves(api, jobs);
  for (const job of jobs) serveJob(api, job);
  await open(page, api, `/recipes/${recipeId(1)}`);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

/** GET /v1/referrals/me while referrals are on (the shape referral.spec.ts uses). */
const REFERRALS_ON = {
  enabled: true, code: 'Q4HN7TWE', referrer_reward_cents: 100, referee_reward_cents: 150, min_topup_cents: 10_00,
  invited: 3, rewarded: 1, earned_cents: 100, pending_reward_cents: null,
};

const column = (page: Page, n: number) => page.getByRole('article', { name: `Generation ${n}` });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test.describe('the recipes list', () => {
  test('shows each recipe with its kind, latest model, attempts and what they cost', async ({ page, api }) => {
    serveRecipes(api, [
      makeRecipe(),
      makeRecipe({
        id: recipeId(2), title: 'Koi pond in the rain', modality: 'VIDEO', attempts: 1, spent_cents: 100,
        latest_version: makeVersion({ id: versionId(2, 1), number: 1, model: 'kling-3.0' }),
      }),
      makeRecipe({ id: recipeId(3), title: 'Untried', attempts: 0, spent_cents: 0 }),
    ]);
    await open(page, api, '/recipes');

    const cards = page.getByRole('listitem').filter({ has: page.getByRole('link') });
    await expect(cards).toHaveCount(3);
    const mug = cards.filter({ hasText: 'Mug on a kitchen shelf' });
    await expect(mug.getByRole('link', { name: 'Mug on a kitchen shelf' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    await expect(mug).toContainText('Image');
    await expect(mug).toContainText('gpt-image-1.5');
    await expect(mug).toContainText('12 attempts');
    await expect(mug).toContainText('$0.74 spent');
    const koi = cards.filter({ hasText: 'Koi pond in the rain' });
    await expect(koi).toContainText('Video');
    await expect(koi).toContainText('kling-3.0');
    await expect(koi).toContainText('1 attempt');
    await expect(koi).toContainText('$1.00 spent');
    await expect(cards.filter({ hasText: 'Untried' })).toContainText('No attempts yet');
    expect(await noOverflow(page)).toBe(true);
  });

  test('is paged: Load more asks for the recipes after the last one shown', async ({ page, api }) => {
    const recipes = Array.from({ length: 30 }, (_, i) => makeRecipe({ id: recipeId(i + 1), title: `Recipe ${i + 1}` }));
    serveRecipes(api, recipes);
    await open(page, api, '/recipes');

    const titles = page.getByRole('link', { name: /^Recipe \d+$/ });
    await expect(titles).toHaveCount(24);
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(titles).toHaveCount(30);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);

    const asked = api.callsTo('GET', '/v1/recipes').map((c) => c.query.toString());
    expect(asked).toEqual(['limit=24', `limit=24&before=${recipeId(24)}`]);
  });

  test('with none, says how to make one', async ({ page, api }) => {
    serveRecipes(api, []);
    await open(page, api, '/recipes');

    await expect(page.getByText('No recipes yet.')).toBeVisible();
    await expect(page.getByText('choose Save as recipe to keep it')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Create an image' })).toHaveAttribute('href', '/create/image');
  });
});

test.describe('a recipe page', () => {
  test('shows the latest version: template, variables, model and settings', async ({ page, api }) => {
    await openRecipe(page, api, [makeRecipe({ source_gallery_recipe: 'mug-on-a-shelf' })], attempts());

    await expect(page.getByRole('heading', { level: 1, name: 'Mug on a kitchen shelf' })).toBeVisible();
    await expect(page.getByText('from the gallery recipe mug-on-a-shelf')).toBeVisible();
    const latest = page.getByRole('region', { name: 'Version 3, the latest' });
    await expect(latest).toContainText(MUG_TEMPLATE);
    await expect(latest.locator('mark')).toHaveText(['{{color}}', '{{surface}}']);
    await expect(latest.getByRole('definition')).toHaveText(['red', 'kitchen shelf', 'gpt-image-1.5', '1536x1024 · Count 2']);
    // Whatever order the API returns the settings in, they read the same way.
    await expect(page.getByRole('region', { name: 'Version 3, the latest' })).not.toContainText('Count 2 · 1536x1024');
    await expect(page.getByRole('link', { name: 'Open in Studio' })).toHaveAttribute('href', `/create/image?recipe=${recipeId(1)}`);
  });

  test('lists every version, and an older one opens to show what it was', async ({ page, api }) => {
    await openRecipe(page, api, [makeRecipe()], attempts());

    const versions = page.getByRole('region', { name: 'Versions' });
    await expect(versions.getByRole('listitem')).toHaveCount(3);
    await expect(versions.getByRole('listitem').first()).toContainText('Version 3 · latest');
    const second = versions.getByRole('listitem').nth(1);
    await expect(second).toContainText('Version 2');
    await expect(second).toContainText('flux-2-pro');
    await expect(second.getByText('A {{color}} mug on a table')).toBeHidden();
    await second.getByText('Version 2').click();
    await expect(second).toContainText('A {{color}} mug on a table');
    await expect(second).toContainText('1024x1024');
  });

  test('shows the attempts, newest first, each with its version, values and cost, and the total', async ({ page, api }) => {
    await openRecipe(page, api, [makeRecipe({ attempts: 3, spent_cents: 13 })], attempts());

    const section = page.getByRole('region', { name: 'Attempts' });
    // The total is the API's: every attempt, not only the ones listed.
    await expect(section).toContainText('3 attempts · $0.13 spent in total');
    const rows = section.getByRole('listitem');
    await expect(rows).toHaveCount(3);

    await expect(rows.nth(0)).toContainText('Version 3');
    await expect(rows.nth(0)).toContainText('$0.05');
    await expect(rows.nth(0).getByRole('definition')).toHaveText(['blue', 'kitchen shelf']);
    await expect(rows.nth(1).getByRole('definition')).toHaveText(['red', 'kitchen shelf']);
    // Purged: the cost stays, the values went with the prompt.
    await expect(rows.nth(2)).toContainText('Version 2');
    await expect(rows.nth(2)).toContainText('$0.03');
    await expect(rows.nth(2)).toContainText('Values deleted with the prompt');
    await expect(rows.nth(2)).toContainText('Files deleted');

    expect(api.callsTo('GET', '/v1/jobs').at(-1)?.query.toString()).toBe(`limit=24&recipe=${recipeId(1)}`);
    expect(await noOverflow(page)).toBe(true);
  });

  test('an attempt can be saved from its row', async ({ page, api }) => {
    const jobs = attempts();
    await openRecipe(page, api, [makeRecipe()], jobs);

    const rows = page.getByRole('region', { name: 'Attempts' }).getByRole('listitem');
    await rows.nth(0).getByRole('button', { name: 'Save', exact: true }).click();
    await expect(rows.nth(0).getByRole('button', { name: 'Saved', exact: true })).toHaveAttribute('aria-pressed', 'true');
    expect(jobs[0].saved).toBe(true);
    // A purged attempt has nothing left to save.
    await expect(rows.nth(2).getByRole('button', { name: /^Saved?$/ })).toHaveCount(0);
  });

  test('with no attempts, points at the Studio', async ({ page, api }) => {
    await openRecipe(page, api, [makeRecipe({ attempts: 0, spent_cents: 0 })], []);

    const section = page.getByRole('region', { name: 'Attempts' });
    await expect(section).toContainText('No attempts yet · $0.00 spent in total');
    await expect(section.getByRole('link', { name: 'Open it in the Studio' })).toHaveAttribute('href', `/create/image?recipe=${recipeId(1)}`);
  });

  test('the title is edited in place', async ({ page, api }) => {
    const recipes = [makeRecipe()];
    await openRecipe(page, api, recipes, attempts());

    await page.getByRole('button', { name: 'Rename' }).click();
    const title = page.getByLabel('Recipe title');
    await expect(title).toHaveValue('Mug on a kitchen shelf');
    await title.fill('  Mug, morning light  ');
    await page.getByRole('button', { name: 'Save title' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Mug, morning light' })).toBeVisible();
    expect(api.callsTo('PATCH', `/v1/recipes/${recipeId(1)}`).map((c) => c.body)).toEqual([{ title: 'Mug, morning light' }]);
    expect(recipes[0].title).toBe('Mug, morning light');
    // The counts on the page are as they were read.
    await expect(page.getByRole('region', { name: 'Attempts' })).toContainText('12 attempts');
  });

  test('Delete asks first, saying the generations are kept; declined, nothing is deleted', async ({ page, api }) => {
    const recipes = [makeRecipe({ attempts: 3 })];
    await openRecipe(page, api, recipes, attempts());

    let asked = '';
    page.once('dialog', (dialog) => {
      asked = dialog.message();
      void dialog.dismiss();
    });
    await page.getByRole('button', { name: 'Delete recipe' }).click();

    expect(asked).toContain('Delete “Mug on a kitchen shelf” and every version of it?');
    expect(asked).toContain('The 3 generations made with it are kept in History.');
    expect(api.callsTo('DELETE', `/v1/recipes/${recipeId(1)}`)).toHaveLength(0);
    await expect(page).toHaveURL(`/recipes/${recipeId(1)}`);
    expect(recipes).toHaveLength(1);
  });

  test('once agreed, the recipe is deleted and the list is shown', async ({ page, api }) => {
    const recipes = [makeRecipe()];
    await openRecipe(page, api, recipes, attempts());

    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByRole('button', { name: 'Delete recipe' }).click();

    await expect(page).toHaveURL('/recipes');
    await expect(page.getByText('No recipes yet.')).toBeVisible();
    expect(recipes).toHaveLength(0);
  });

  test("a recipe that isn't the reader's, or isn't there, is not found", async ({ page, api }) => {
    serveRecipes(api, []);
    await open(page, api, `/recipes/${recipeId(9)}`);
    await expect(page.getByText('Recipe not found.')).toBeVisible();

    // Not an id at all: nothing is asked of the API.
    await page.goto('/recipes/not-a-recipe');
    await expect(page.getByText('Recipe not found.')).toBeVisible();
    expect(api.callsTo('GET', /^\/v1\/recipes\/not/)).toHaveLength(0);
  });

  test('the job page of an attempt links back to its recipe and shows the values', async ({ page, api }) => {
    const job = attempts()[0];
    serveJob(api, job);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await open(page, api, `/history/${job.id}`);

    await expect(page.getByRole('link', { name: 'Your recipe, version 3' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    const values = page.getByRole('heading', { name: 'Variables' }).locator('..');
    await expect(values.getByRole('definition')).toHaveText(['blue', 'kitchen shelf']);
  });
});

test.describe('compare', () => {
  test("from a recipe's attempts: two ticked, side by side, with what changed marked", async ({ page, api }) => {
    await openRecipe(page, api, [makeRecipe()], attempts());
    const section = page.getByRole('region', { name: 'Attempts' });
    const boxes = section.getByRole('checkbox');
    await expect(section.getByRole('button', { name: 'Compare' })).toBeDisabled();

    // Red first, then blue: the first ticked is what the others are compared with.
    await boxes.nth(1).check();
    await expect(section.getByRole('button', { name: 'Compare' })).toBeDisabled();
    await boxes.nth(0).check();
    await section.getByRole('link', { name: 'Compare 2' }).click();

    await expect(page).toHaveURL(`/compare?jobs=${jobId(2)},${jobId(3)}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Compare' })).toBeVisible();
    const first = column(page, 1);
    const second = column(page, 2);

    await expect(first.getByRole('img', { name: 'Generation 1' })).toHaveAttribute('src', mediaUrl('out-2.png'));
    await expect(second.getByRole('img', { name: 'Generation 2' })).toHaveAttribute('src', mediaUrl('out-3.png'));
    for (const col of [first, second]) {
      await expect(col).toContainText('gpt-image-1.5');
      await expect(col).toContainText('1536x1024');
      await expect(col).toContainText('$0.05');
      await expect(col).toContainText('18 s');
      await expect(col.getByRole('link', { name: 'Version 3' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    }
    await expect(first).toContainText('red');
    await expect(second).toContainText('blue');
    // The first prompt is the baseline; in the second, only the word that differs is marked.
    await expect(first).toContainText('A red mug on a kitchen shelf, soft morning light');
    await expect(first.locator('mark')).toHaveCount(0);
    await expect(second).toContainText('A blue mug on a kitchen shelf, soft morning light');
    await expect(second.locator('mark')).toHaveText(['blue']);
    await expect(second.getByRole('link', { name: 'Generation 2', exact: true })).toHaveAttribute('href', `/history/${jobId(3)}`);
  });

  test('from History: Select ticks up to four, and Compare opens them', async ({ page, api }) => {
    const jobs = Array.from({ length: 6 }, (_, i) => makeJob({
      id: jobId(i + 1), prompt: `Lighthouse number ${i + 1}`, createdAt: `2026-11-13T12:0${6 - i}:00Z`,
    }));
    serveJobList(api, jobs);
    serveSaves(api, jobs);
    for (const job of jobs) serveJob(api, job);
    await open(page, api, '/history');
    await expect(page.getByRole('link', { name: 'Lighthouse number 1' })).toBeVisible();

    // No tick boxes until Select is on.
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await page.getByRole('button', { name: 'Select', exact: true }).click();
    const bar = page.getByRole('group', { name: 'Compare' });
    await expect(bar).toContainText('Tick two to four generations to see them side by side.');
    await expect(bar.getByRole('button', { name: 'Compare' })).toBeDisabled();
    const boxes = page.getByRole('checkbox');
    await expect(boxes).toHaveCount(6);

    await page.getByRole('checkbox', { name: 'Compare: Lighthouse number 2' }).check();
    await expect(bar.getByRole('button', { name: 'Compare' })).toBeDisabled();
    await page.getByRole('checkbox', { name: 'Compare: Lighthouse number 5' }).check();
    await expect(bar.getByRole('link', { name: 'Compare 2' })).toHaveAttribute('href', `/compare?jobs=${jobId(2)},${jobId(5)}`);

    // Four is the most: the rest can't be ticked until one is given back.
    await page.getByRole('checkbox', { name: 'Compare: Lighthouse number 1' }).check();
    await page.getByRole('checkbox', { name: 'Compare: Lighthouse number 3' }).check();
    await expect(bar).toContainText('4 selected, the most that fit side by side.');
    await expect(page.getByRole('checkbox', { name: 'Compare: Lighthouse number 4' })).toBeDisabled();
    await page.getByRole('checkbox', { name: 'Compare: Lighthouse number 5' }).uncheck();
    await expect(page.getByRole('checkbox', { name: 'Compare: Lighthouse number 4' })).toBeEnabled();

    await bar.getByRole('link', { name: 'Compare 3' }).click();
    await expect(page).toHaveURL(`/compare?jobs=${jobId(2)},${jobId(1)},${jobId(3)}`);
    await expect(column(page, 1)).toContainText('Lighthouse number 2');
    await expect(column(page, 2)).toContainText('Lighthouse number 1');
    await expect(column(page, 2).locator('mark')).toHaveText(['1']);
    await expect(column(page, 3).locator('mark')).toHaveText(['3']);
  });

  test('Cancel leaves select mode and forgets what was ticked', async ({ page, api }) => {
    const jobs = [makeJob({ id: jobId(1), prompt: 'One' }), makeJob({ id: jobId(2), prompt: 'Two' })];
    serveJobList(api, jobs);
    serveSaves(api, jobs);
    serveJob(api, jobs[1]);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await open(page, api, '/history');

    await page.getByRole('button', { name: 'Select', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Compare: One' }).check();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('checkbox')).toHaveCount(0);

    await page.getByRole('button', { name: 'Select', exact: true }).click();
    await expect(page.getByRole('checkbox', { name: 'Compare: One' })).not.toBeChecked();
    // A card still opens its generation while selecting.
    await page.getByRole('link', { name: 'Two', exact: true }).click();
    await expect(page).toHaveURL(`/history/${jobId(2)}`);
  });

  test('a purged generation says its files and prompt were deleted, and still shows its cost', async ({ page, api }) => {
    const [, red, purged] = attempts();
    serveJob(api, red);
    serveJob(api, purged);
    await open(page, api, `/compare?jobs=${red.id},${purged.id}`);

    const gone = column(page, 2);
    await expect(gone).toContainText('The files were deleted at the end of the retention period.');
    await expect(gone).toContainText('The prompt was deleted with the files. The cost and date are kept.');
    await expect(gone).toContainText('Values deleted with the prompt');
    await expect(gone).toContainText('$0.03');
    await expect(gone.getByRole('img')).toHaveCount(0);
    await expect(gone.getByRole('link', { name: 'Version 2' })).toBeVisible();
    // The one that is still there is shown in full beside it.
    await expect(column(page, 1).getByRole('img', { name: 'Generation 1' })).toBeVisible();
    await expect(column(page, 1)).toContainText('A red mug on a kitchen shelf, soft morning light');
  });

  test('four generations, of images and video, fit the page; one that cannot be read says so', async ({ page, api }) => {
    const video = makeJob({
      id: jobId(4), modality: 'VIDEO', model: 'kling-3.0', prompt: 'Koi circling under falling rain',
      params: { size: '1280x720', seconds: 5 }, actualCostCents: 50, outputs: outputsOf(1, 'video'),
      startedAt: '2026-11-13T12:00:00Z', completedAt: '2026-11-13T12:02:05Z',
    });
    const several = makeJob({ id: jobId(5), prompt: 'A red mug', params: { size: '1024x1024', n: 3 }, outputs: outputsOf(3) });
    const failed = makeJob({
      id: jobId(6), status: 'FAILED', prompt: 'A red mug', outputs: [], outputUrl: null, actualCostCents: null,
      errorMessage: 'The provider refused the prompt.',
    });
    for (const job of [video, several, failed]) serveJob(api, job);
    api.on('GET', `/v1/jobs/${jobId(9)}`, { status: 404, json: { title: 'Not Found', status: 404, detail: 'job not found' } });
    await open(page, api, `/compare?jobs=${several.id},${video.id},${jobId(9)},${failed.id}`);

    await expect(column(page, 1).getByRole('img')).toHaveCount(3);
    await expect(column(page, 1)).toContainText('Count');
    await expect(column(page, 2).getByLabel('Generation 2')).toHaveAttribute('src', mediaUrl('out-1.mp4'));
    await expect(column(page, 2)).toContainText('5 seconds');
    await expect(column(page, 2)).toContainText('2 min 5 s');
    await expect(column(page, 2)).toContainText('$0.50');
    await expect(page.getByRole('listitem').filter({ hasText: 'Generation 3' }))
      .toContainText('Not found. It may belong to another account, or the link is incomplete.');
    // A failed run made nothing and was refunded; its prompt is the first one's, word for word.
    await expect(column(page, 4)).toContainText('Failed: nothing was made.');
    await expect(column(page, 4)).toContainText('Refunded');
    await expect(column(page, 4)).toContainText('The same prompt as the first.');
    await expect(column(page, 4).locator('mark')).toHaveCount(0);
    expect(await noOverflow(page)).toBe(true);
  });

  test('with fewer than two generations named, says how to pick them', async ({ page, api }) => {
    await open(page, api, `/compare?jobs=${jobId(1)},nonsense`);

    await expect(page.getByText('Pick at least two generations to compare.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Open History' })).toHaveAttribute('href', '/history');
    // Nothing is read for a comparison that can't be made.
    expect(api.callsTo('GET', /^\/v1\/jobs\//)).toHaveLength(0);
  });
});

test.describe('navigation', () => {
  test('Recipes is in the navigation, between History and API keys, and opens the list', async ({ page, api }) => {
    serveRecipes(api, []);
    await open(page, api, '/dashboard');

    // The header's row or, on a phone, the row below it: whichever is shown.
    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link')).toHaveText(
      ['Dashboard', 'Chat', 'Create', 'History', 'Recipes', 'API keys', 'Billing', 'Settings']);
    // On a phone the links are one row that scrolls sideways: the page itself never does.
    expect(await noOverflow(page)).toBe(true);
    await nav.getByRole('link', { name: 'Recipes' }).click();
    await expect(page).toHaveURL('/recipes');
    await expect(page.getByRole('heading', { level: 1, name: 'Recipes' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Recipes' })).toHaveAttribute('aria-current', 'page');
    expect(await noOverflow(page)).toBe(true);
  });

  test('with every link the navigation can have and a long address, no label wraps and the page does not scroll sideways', async ({ page, api }) => {
    serveRecipes(api, []);
    await api.signIn(makeUser({ email: 'a.rather.long.address.for.a.header@studio.example.com', role: 'ADMIN' }));
    api.on('GET', '/v1/admin/me', { json: { email: 'a.rather.long.address.for.a.header@studio.example.com', role: 'ADMIN' } });
    // Referrals on, as referral.spec.ts serves them: the navigation gains Invite.
    api.on('GET', '/v1/referrals/me', { json: REFERRALS_ON });
    await serveMedia(page);
    await page.goto('/recipes');

    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link')).toHaveText(
      ['Dashboard', 'Chat', 'Create', 'History', 'Recipes', 'API keys', 'Billing', 'Invite', 'Settings', 'Admin']);
    // One line each: every link is as tall as the shortest label's.
    const heights = await nav.getByRole('link').evaluateAll((links) => links.map((link) => (link as HTMLElement).offsetHeight));
    expect(new Set(heights).size).toBe(1);
    const logOut = page.getByRole('button', { name: 'Log out' });
    expect(await logOut.evaluate((b) => (b as HTMLElement).offsetHeight)).toBeLessThan(30);
    expect(await noOverflow(page)).toBe(true);
  });

  test('every page of this round fits the screen, phone included', async ({ page, api }) => {
    const jobs = attempts();
    await openRecipe(page, api, [makeRecipe({ title: 'A recipe with quite a long title that has to wrap on a phone' })], jobs);
    expect(await noOverflow(page)).toBe(true);

    await page.goto(`/compare?jobs=${jobs[0].id},${jobs[1].id},${jobs[2].id}`);
    await expect(column(page, 3)).toBeVisible();
    expect(await noOverflow(page)).toBe(true);

    await page.goto('/history');
    await page.getByRole('button', { name: 'Select', exact: true }).click();
    await page.getByRole('checkbox').first().check();
    expect(await noOverflow(page)).toBe(true);
  });
});
