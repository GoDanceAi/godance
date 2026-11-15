import fs from 'node:fs';
import path from 'node:path';
import type { Page, TestInfo } from '@playwright/test';
import { batchId, makeBatch, makeRows, mockBatchEstimate, serveBatch, type BatchState } from './batches';
import { serveMedia } from './generation';
import { INPUT_MODELS, inputsUser, PNG, serveUploads } from './inputs';
import { expect, test, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, serveRecipes } from './user-recipes';

// Round 12 brief 02: the batch pages use the theme's tokens, so they read in
// light and in dark. Each page is opened in both, its colours are the
// theme's, and nothing spills sideways.
//
// With BATCH_EVIDENCE_DIR set, each page is also saved there as a PNG
// (<page>-<theme>.png on desktop, <page>-<theme>-mobile.png on a phone).
// Nothing is written without it.

const EVIDENCE = process.env.BATCH_EVIDENCE_DIR;

type Theme = 'light' | 'dark';

/** The theme's tokens (src/index.css), as the browser reports them. */
const TOKENS: Record<Theme, { page: string; text: string; card: string; tableHead: string }> = {
  light: { page: 'rgb(255, 255, 255)', text: 'rgb(17, 24, 39)', card: 'rgb(255, 255, 255)', tableHead: 'rgb(249, 250, 251)' },
  dark: { page: 'rgb(10, 15, 28)', text: 'rgb(243, 244, 246)', card: 'rgb(19, 27, 46)', tableHead: 'rgb(15, 22, 38)' },
};

async function expectTheme(page: Page, theme: Theme) {
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  const colours = await page.evaluate(() => {
    const colour = (selector: string, property: 'backgroundColor' | 'color') => {
      const element = document.querySelector(selector);
      return element ? getComputedStyle(element)[property] : null;
    };
    return {
      page: colour('body', 'backgroundColor'),
      text: colour('main h1', 'color'),
      card: colour('main .card', 'backgroundColor'),
      tableHead: colour('main table thead', 'backgroundColor'),
    };
  });
  expect(colours).toEqual(TOKENS[theme]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

async function keep(page: Page, info: TestInfo, name: string, theme: Theme) {
  if (!EVIDENCE) return;
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const suffix = info.project.name === 'desktop' ? '' : `-${info.project.name}`;
  await page.screenshot({ path: path.join(EVIDENCE, `${name}-${theme}${suffix}.png`), fullPage: true });
}

/** A recipe with an input slot, a three-row table with a column of file names, and the three images. */
async function openNewBatch(page: Page, api: MockApi, balance: number) {
  const recipe = makeRecipe({
    latest_version: makeVersion({ inputs: [{ slot: 'product photo', role: 'image', hint: 'One product on a plain background' }] }),
  });
  await api.signIn(inputsUser({}, { creditBalanceCents: balance }));
  serveRecipes(api, [recipe]);
  api.on('GET', '/v1/models', { json: INPUT_MODELS });
  mockBatchEstimate(api, { balance });
  serveUploads(api);
  await serveMedia(page);
  await page.goto(`/recipes/${recipe.id}/batch`);
  await page.getByLabel('Paste a table').fill(
    'color\tsurface\timage\nblue\toak desk\tmug-blue.png\ngreen\tmarble counter\tmug-green.png\nyellow\twindow sill\tmug-yellow.png');
  await page.getByLabel('Images for product photo', { exact: true }).setInputFiles(
    ['mug-blue.png', 'mug-green.png', 'mug-yellow.png'].map((name) => ({ name, mimeType: 'image/png', buffer: PNG })));
  // The dot between the words and the price is for the eye only: the button's name is "Start batch $0.30".
  await expect(page.getByRole('button', { name: 'Start batch $0.30' })).toBeEnabled();
}

async function openBatch(page: Page, api: MockApi, state: BatchState) {
  await api.signIn();
  serveBatch(api, state);
  await serveMedia(page);
  await page.goto(`/batches/${batchId(1)}`);
  await expect(page.getByRole('region', { name: 'Rows of the batch' }).locator('tbody tr').first()).toBeVisible();
  // The first thumbnail has been drawn.
  await expect(page.locator('tr[data-row="1"] img')).toHaveJSProperty('complete', true);
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`the batch pages in ${theme}`, () => {
    test.beforeEach(async ({ page }) => {
      // As the theme switch leaves it (lib/theme.ts): read before the first paint.
      await page.addInitScript((chosen) => localStorage.setItem('gd-theme', chosen), theme);
    });

    test('the new-batch page', async ({ page, api }, info) => {
      await openNewBatch(page, api, 12_34);

      await expect(page.getByText('Every row has an image.')).toBeVisible();
      await expectTheme(page, theme);
      await keep(page, info, 'new-batch', theme);
    });

    test('the new-batch page when the balance does not cover the estimate', async ({ page, api }, info) => {
      await openNewBatch(page, api, 12);

      await expect(page.locator('[data-not-enough]')).toContainText('Your balance of $0.12 does not cover the estimated $0.30.');
      await expectTheme(page, theme);
      await keep(page, info, 'new-batch-not-enough-credit', theme);
    });

    test('a running batch', async ({ page, api }, info) => {
      await openBatch(page, api, { batch: makeBatch(), rows: makeRows(120, 41, 2, [7]) });

      await expect(page.getByText('41 of 120 rows finished')).toBeVisible();
      await expectTheme(page, theme);
      await keep(page, info, 'batch-running', theme);
    });

    test('a batch paused for credit', async ({ page, api }, info) => {
      await openBatch(page, api, {
        batch: makeBatch({ status: 'PAUSED', pause_reason: 'insufficient_credit', rows_running: 0 }),
        rows: makeRows(120, 41, 0, [7]),
      });

      await expect(page.getByRole('heading', { name: 'Paused: not enough credit' })).toBeVisible();
      await expectTheme(page, theme);
      await keep(page, info, 'batch-paused', theme);
    });

    test('a batch paused for a spending cap', async ({ page, api }, info) => {
      await openBatch(page, api, {
        batch: makeBatch({
          status: 'PAUSED', pause_reason: 'spending_cap', rows_running: 0,
          pause_detail: 'API-key daily spending cap would be exceeded (480 of 500 cents used or held; this request needs up to 30)',
        }),
        rows: makeRows(120, 41, 0, [7]),
      });

      await expect(page.getByRole('heading', { name: 'Paused: a spending cap was reached' })).toBeVisible();
      await expectTheme(page, theme);
      await keep(page, info, 'batch-paused-for-a-cap', theme);
    });
  });
}
