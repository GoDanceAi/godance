import type { Page } from '@playwright/test';
import type { Batch } from '../src/lib/api';
import { batchId, makeBatch, makeRows, serveBatch, serveBatchList } from './batches';
import { jobId, makeJob, MODELS, mockEstimates, serveJob, serveJobList, serveMedia } from './generation';
import { expect, test, type MockApi } from './mock-api';
import { makeRecipe, recipeId, serveRecipes } from './user-recipes';

// Round 12 brief 02, outside the two batch pages: the list of batches under
// Recipes, a recipe's own batches and its "Run as batch", the way back from a
// job to its batch and row, and the mark on batch jobs in History.

const NOW = new Date('2026-11-14T12:00:00Z');

async function open(page: Page, api: MockApi, path: string) {
  await api.signIn();
  await serveMedia(page);
  await page.clock.install({ time: NOW });
  await page.goto(path);
}

/** Three batches, newest first: one running, one paused, one finished whose recipe has since been deleted. */
function batches(): Batch[] {
  return [
    makeBatch({ id: batchId(3), created_at: '2026-11-14T11:00:00Z' }),
    makeBatch({
      id: batchId(2), recipe_id: recipeId(2), recipe_title: 'Koi pond in the rain', model: 'kling-3.0',
      status: 'PAUSED', pause_reason: 'insufficient_credit', rows_total: 12, rows_done: 5, rows_failed: 0, rows_running: 0,
      estimated_total_cents: 600, spent_cents: 250, created_at: '2026-11-13T18:00:00Z',
    }),
    makeBatch({
      id: batchId(1), recipe_id: null, recipe_title: null, status: 'DONE', rows_total: 1, rows_done: 1, rows_failed: 0,
      rows_running: 0, estimated_total_cents: 6, spent_cents: 6, created_at: '2026-11-01T08:00:00Z',
      completed_at: '2026-11-01T08:01:00Z',
    }),
  ];
}

const list = (page: Page) => page.getByRole('list', { name: 'Batches' }).getByRole('listitem');
const strip = (page: Page) => page.getByRole('navigation', { name: 'Recipes and batches' });
const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Main' });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test.describe('the batches list', () => {
  test('lists batches newest first, each with its status, recipe, progress and what it has spent', async ({ page, api }) => {
    serveBatchList(api, batches());
    await open(page, api, '/batches');

    await expect(page.getByRole('heading', { level: 1, name: 'Batches' })).toBeVisible();
    await expect(list(page)).toHaveCount(3);

    const first = list(page).nth(0);
    await expect(first.getByRole('link')).toHaveAttribute('href', `/batches/${batchId(3)}`);
    await expect(first.getByRole('link')).toContainText('Mug on a kitchen shelf');
    await expect(first.locator('.pill')).toHaveText('Running');
    await expect(first).toContainText('41 of 120 rows');
    await expect(first).toContainText('1 failed');
    await expect(first).toContainText('$2.38');
    await expect(first).toContainText('of est. $7.20');
    await expect(first).toContainText('1 hour ago');
    await expect(first).toContainText('gpt-image-1.5');

    const second = list(page).nth(1);
    await expect(second.getByRole('link')).toContainText('Koi pond in the rain');
    await expect(second.locator('.pill')).toHaveText('Paused');
    await expect(second).toContainText('5 of 12 rows');
    await expect(second).toContainText('$2.50');

    // Its recipe is gone; the batch is still there to open.
    const third = list(page).nth(2);
    await expect(third.getByRole('link')).toContainText('A deleted recipe');
    await expect(third.locator('.pill')).toHaveText('Done');
    await expect(third).toContainText('1 of 1 row');

    expect(api.callsTo('GET', '/v1/recipe-batches')[0].query.toString()).toBe('limit=24');
    expect(await noOverflow(page)).toBe(true);
  });

  test('two batches of one recipe are told apart for a screen reader by when they started', async ({ page, api }) => {
    serveBatchList(api, [
      makeBatch({ id: batchId(2), created_at: '2026-11-14T11:00:00Z' }),
      makeBatch({ id: batchId(1), created_at: '2026-11-13T09:30:00Z' }),
    ]);
    await open(page, api, '/batches');

    // The part after the title is for a screen reader only: it is in the link's name, not on the screen.
    await expect(list(page).getByRole('link')).toHaveText([
      /^Mug on a kitchen shelf, batch started Nov 14, 2026/,
      /^Mug on a kitchen shelf, batch started Nov 13, 2026/,
    ]);
  });

  test('is paged: Load more asks for the batches after the last one shown', async ({ page, api }) => {
    const many = Array.from({ length: 30 }, (_, i) => makeBatch({ id: batchId(100 - i), recipe_title: `Recipe ${i + 1}` }));
    serveBatchList(api, many);
    await open(page, api, '/batches');

    await expect(list(page)).toHaveCount(24);
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(list(page)).toHaveCount(30);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
    expect(api.callsTo('GET', '/v1/recipe-batches').map((call) => call.query.toString()))
      .toEqual(['limit=24', `limit=24&before=${batchId(77)}`]);
  });

  test('with none, says where a batch is started', async ({ page, api }) => {
    await open(page, api, '/batches');

    await expect(page.getByText('No batches yet.')).toBeVisible();
    await expect(page.getByText('Open one of your recipes and choose Run as batch')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Open your recipes' })).toHaveAttribute('href', '/recipes');
  });

  test('a batch in the list opens its page', async ({ page, api }) => {
    const all = batches();
    serveBatchList(api, all);
    serveBatch(api, { batch: all[0], rows: makeRows(120, 41, 2, [7]) });
    await open(page, api, '/batches');

    await list(page).nth(0).getByRole('link').click();

    await expect(page).toHaveURL(`/batches/${batchId(3)}`);
    await expect(page.getByText('41 of 120 rows finished')).toBeVisible();
  });
});

test.describe('batches sit under Recipes', () => {
  test('a "Recipes | Batches" strip joins the two lists, and the main navigation keeps its entries', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    serveBatchList(api, batches());
    await open(page, api, '/recipes');

    await expect(strip(page).getByRole('link')).toHaveText(['Recipes', 'Batches']);
    await expect(strip(page).getByRole('link', { name: 'Recipes' })).toHaveAttribute('aria-current', 'page');
    await expect(strip(page).getByRole('link', { name: 'Batches' })).not.toHaveAttribute('aria-current', 'page');

    await strip(page).getByRole('link', { name: 'Batches' }).click();
    await expect(page).toHaveURL('/batches');
    await expect(page.getByRole('heading', { level: 1, name: 'Batches' })).toBeVisible();
    await expect(strip(page).getByRole('link', { name: 'Batches' })).toHaveAttribute('aria-current', 'page');
    await expect(strip(page).getByRole('link', { name: 'Recipes' })).not.toHaveAttribute('aria-current', 'page');

    // Batches have no entry of their own up there: the same eight, with Recipes as the current one.
    await expect(mainNav(page).getByRole('link')).toHaveText(
      ['Dashboard', 'Chat', 'Create', 'History', 'Recipes', 'API keys', 'Billing', 'Settings']);
    await expect(mainNav(page).getByRole('link', { name: 'Recipes' })).toHaveAttribute('aria-current', 'page');
    await expect(mainNav(page).locator('[aria-current="page"]')).toHaveCount(1);
    // One line each, and the page does not scroll sideways, phone included.
    const heights = await mainNav(page).getByRole('link').evaluateAll((links) => links.map((link) => (link as HTMLElement).offsetHeight));
    expect(new Set(heights).size).toBe(1);
    expect(await noOverflow(page)).toBe(true);

    await strip(page).getByRole('link', { name: 'Recipes' }).click();
    await expect(page).toHaveURL('/recipes');
    await expect(page.getByRole('heading', { level: 1, name: 'Recipes' })).toBeVisible();
    expect(await noOverflow(page)).toBe(true);
  });

  test('on a batch page and on a new-batch page, Recipes is the current link in the main navigation', async ({ page, api }) => {
    const batch = makeBatch();
    serveBatch(api, { batch, rows: makeRows(120, 41, 2, [7]) });
    serveRecipes(api, [makeRecipe()]);
    api.on('GET', '/v1/models', { json: MODELS });
    await open(page, api, `/batches/${batch.id}`);

    await expect(page.getByText('41 of 120 rows finished')).toBeVisible();
    await expect(mainNav(page).getByRole('link', { name: 'Recipes' })).toHaveAttribute('aria-current', 'page');
    await expect(mainNav(page).getByRole('link', { name: 'History' })).not.toHaveAttribute('aria-current', 'page');

    await page.goto(`/recipes/${recipeId(1)}/batch`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Mug on a kitchen shelf');
    await expect(mainNav(page).getByRole('link', { name: 'Recipes' })).toHaveAttribute('aria-current', 'page');
  });
});

test.describe('a recipe page and its batches', () => {
  test('"Run as batch" opens the new batch, and the recipe\'s own batches are listed', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    // The second batch is another recipe's.
    serveBatchList(api, batches());
    // The new-batch page, opened at the end, reads the models.
    api.on('GET', '/v1/models', { json: MODELS });
    await open(page, api, `/recipes/${recipeId(1)}`);

    await expect(page.getByRole('link', { name: 'Run as batch' })).toHaveAttribute('href', `/recipes/${recipeId(1)}/batch`);

    const section = page.getByRole('region', { name: 'Batches' });
    await expect(section.getByRole('listitem')).toHaveCount(1);
    const only = section.getByRole('listitem').first();
    // On its recipe's page a batch is named by its size, not by the recipe again.
    await expect(only.getByRole('link')).toContainText('Batch of 120 rows');
    await expect(only.getByRole('link')).toHaveAttribute('href', `/batches/${batchId(3)}`);
    await expect(only.locator('.pill')).toHaveText('Running');
    await expect(only).toContainText('41 of 120 rows');
    await expect(only).toContainText('$2.38');
    // Asked for by recipe.
    expect(api.callsTo('GET', '/v1/recipe-batches')[0].query.toString()).toBe(`limit=5&recipe_id=${recipeId(1)}`);
    expect(await noOverflow(page)).toBe(true);

    await page.getByRole('link', { name: 'Run as batch' }).click();
    await expect(page).toHaveURL(`/recipes/${recipeId(1)}/batch`);
    await expect(page.getByLabel('Paste a table')).toBeVisible();
  });

  test('a recipe that was never run as a batch says so, with the way to run one', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await open(page, api, `/recipes/${recipeId(1)}`);

    const section = page.getByRole('region', { name: 'Batches' });
    await expect(section).toContainText('This recipe has not been run as a batch yet.');
    await expect(section.getByRole('link', { name: 'Run it over a table' })).toHaveAttribute('href', `/recipes/${recipeId(1)}/batch`);
  });
});

test.describe('a job of a batch', () => {
  test('its page links back to the batch and the row, and the link opens the batch at that row', async ({ page, api }) => {
    const batch = makeBatch();
    // The API counts rows from 0: row 17 is the eighteenth.
    const job = makeJob({
      id: jobId(1017), model: 'gpt-image-1.5', channel: 'BATCH', batch: { id: batch.id, row: 17 },
      recipe: { id: recipeId(1), version: 3, variables: { color: 'yellow', surface: 'shelf 18' } },
    });
    serveJob(api, job);
    serveBatch(api, { batch, rows: makeRows(120, 41, 2, [7]) });
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await open(page, api, `/history/${job.id}`);

    const link = page.getByRole('link', { name: 'Row 18 of a batch' });
    await expect(link).toHaveAttribute('href', `/batches/${batch.id}?row=17`);
    await link.click();

    await expect(page).toHaveURL(`/batches/${batch.id}?row=17`);
    const marked = page.locator('tr[data-linked-row]');
    await expect(marked.getByRole('rowheader')).toContainText('18');
    await expect(marked.getByRole('link', { name: 'Row 18: open its generation' })).toHaveAttribute('href', `/history/${job.id}`);
  });

  test('a job that is not a row of a batch has no such link', async ({ page, api }) => {
    const job = makeJob({ id: jobId(5), channel: 'STUDIO', batch: null });
    serveJob(api, job);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await open(page, api, `/history/${job.id}`);

    await expect(page.getByRole('heading', { level: 2, name: 'Details' })).toBeVisible();
    await expect(page.getByRole('link', { name: /of a batch/ })).toHaveCount(0);
    await expect(page.getByRole('term').filter({ hasText: 'Batch' })).toHaveCount(0);
  });

  test('History marks the jobs a batch made, and only those', async ({ page, api }) => {
    serveJobList(api, [
      makeJob({ id: jobId(3), prompt: 'A blue mug on shelf 1', channel: 'BATCH', batch: { id: batchId(1), row: 0 } }),
      makeJob({ id: jobId(2), prompt: 'Made in the Studio', channel: 'STUDIO' }),
      makeJob({ id: jobId(1), prompt: 'Made with a key', channel: 'API' }),
    ]);
    await open(page, api, '/history');

    const cards = page.getByRole('main').getByRole('listitem');
    await expect(cards).toHaveCount(3);
    await expect(cards.filter({ hasText: 'A blue mug on shelf 1' }).locator('[data-batch-badge]')).toHaveText('Batch');
    await expect(page.locator('[data-batch-badge]')).toHaveCount(1);
    expect(await noOverflow(page)).toBe(true);
  });
});
