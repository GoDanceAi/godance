import fs from 'node:fs';
import type { Page } from '@playwright/test';
import {
  batchId,
  doneRow,
  makeBatch,
  makeRows,
  refuseZip,
  RESULTS_CSV,
  RESULTS_ZIP,
  serveBatch,
  type BatchState,
} from './batches';
import { jobId, mediaUrl, serveMedia } from './generation';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';
import { recipeId } from './user-recipes';

// Round 12 brief 02: one batch at /batches/:id. Its progress is read every
// three seconds while anything in it can change and not after; its rows come
// fifty at a time; a pause is explained and resumed; it can be cancelled; and
// its results download as a CSV or a zip.

const NOW = new Date('2026-11-14T09:10:00Z');
const BATCH = `/v1/recipe-batches/${batchId(1)}`;

/** 120 rows part-way: 41 finished (row 8 failed), two running, the rest waiting. */
function running(): BatchState {
  return { batch: makeBatch(), rows: makeRows(120, 41, 2, [7]) };
}

/** The same batch stopped: nothing running, and why. */
function paused(reason: 'insufficient_credit' | 'spending_cap', detail: string | null = null): BatchState {
  return {
    batch: makeBatch({ status: 'PAUSED', pause_reason: reason, pause_detail: detail, rows_running: 0 }),
    rows: makeRows(120, 41, 0, [7]),
  };
}

/** Opens the batch with the page's clock in the test's hands: no poll falls due until the test says so. */
async function openBatch(page: Page, api: MockApi, state: BatchState, path = `/batches/${state.batch.id}`) {
  await api.signIn();
  serveBatch(api, state);
  await serveMedia(page);
  await page.clock.install({ time: NOW });
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(rows(page).first()).toBeVisible();
  // From here time moves only when the test moves it.
  await page.clock.pauseAt(new Date(NOW.getTime() + 60_000));
  await settle(page);
}

/** Lets what a timer started (a request, and the timer after it) finish. Real time: the page's clock is paused. */
const settle = (page: Page) => page.waitForTimeout(300);

const reads = (api: MockApi) => api.callsTo('GET', BATCH).length;
const rowReads = (api: MockApi) => api.callsTo('GET', `${BATCH}/rows`).map((call) => call.query.toString());
const rowsRegion = (page: Page) => page.getByRole('region', { name: 'Rows of the batch' });
/** The table's body rows. */
const rows = (page: Page) => rowsRegion(page).locator('tbody tr');
const row = (page: Page, n: number) => rowsRegion(page).locator(`tr[data-row="${n}"]`);
const progress = (page: Page) => page.getByRole('progressbar', { name: 'Rows finished' });
const counts = (page: Page) => page.getByRole('region', { name: 'Progress' }).getByRole('definition');
const status = (page: Page) => page.getByRole('article').locator('header .pill');
const pausedBox = (page: Page) => page.getByRole('region', { name: /^Paused/ });
const results = (page: Page) => page.getByRole('region', { name: 'Results' });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test.describe('a batch: status and progress', () => {
  test('shows the status, the counts, what it has spent against the estimate, and a progress bar', async ({ page, api }) => {
    await openBatch(page, api, running());

    await expect(page.getByRole('heading', { level: 1, name: 'Mug on a kitchen shelf' })).toBeVisible();
    await expect(status(page)).toHaveText('Running');
    await expect(page.getByText('41 of 120 rows finished')).toBeVisible();
    await expect(page.locator('[data-batch-spent]')).toHaveText('Spent $2.38 of an estimated $7.20');
    // Done, failed, running, waiting, total.
    await expect(counts(page)).toHaveText(['40', '1', '2', '77', '120']);
    // The bar is a progress bar to assistive technology, with its numbers.
    await expect(progress(page)).toHaveAttribute('aria-valuenow', '41');
    await expect(progress(page)).toHaveAttribute('aria-valuemax', '120');
    await expect(progress(page)).toHaveAttribute('aria-valuetext', '41 of 120 rows finished');
    // And the progress is announced, in steps.
    await expect(page.getByRole('region', { name: 'Progress' }).getByRole('status'))
      .toHaveText('Running. About 30% of the rows have finished, 1 failed.');

    await expect(page.getByText('gpt-image-1.5')).toBeVisible();
    await expect(page.getByText('2 rows at a time')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Open the recipe' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    await expect(page.getByRole('link', { name: '← Batches' })).toHaveAttribute('href', '/batches');
    await expect(page.getByText('This page updates by itself while the batch runs.')).toBeVisible();
  });

  test('is read every 3 seconds while it runs, and not once more after it ends', async ({ page, api }) => {
    const state = running();
    await openBatch(page, api, state);
    const first = reads(api);

    // Not before three seconds have passed.
    await page.clock.runFor(2_900);
    await settle(page);
    expect(reads(api)).toBe(first);

    // At three seconds: one reading, which shows what has changed.
    state.batch = { ...state.batch, rows_done: 43, rows_running: 2, spent_cents: 250 };
    state.rows = makeRows(120, 44, 2, [7]);
    await page.clock.runFor(200);
    await expect(page.getByText('44 of 120 rows finished')).toBeVisible();
    await expect(page.locator('[data-batch-spent]')).toHaveText('Spent $2.50 of an estimated $7.20');
    await settle(page);
    expect(reads(api)).toBe(first + 1);
    // The rows that could still change were read again: row 42 was running and is done.
    await expect(row(page, 42).locator('.pill')).toHaveText('Done');

    // And again three seconds on: it has finished.
    state.batch = {
      ...state.batch, status: 'DONE', rows_done: 119, rows_running: 0, spent_cents: 714, completed_at: '2026-11-14T09:30:00Z',
    };
    state.rows = makeRows(120, 120, 0, [7]);
    await page.clock.runFor(3_000);
    await expect(status(page)).toHaveText('Done');
    await expect(page.getByText('120 of 120 rows finished')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Progress' }).getByRole('status'))
      .toHaveText('Done. 119 of 120 rows done, 1 failed.');
    await settle(page);
    expect(reads(api)).toBe(first + 2);
    await expect(page.getByText('This page updates by itself')).toHaveCount(0);

    // Nothing more is asked, however long the page stays open.
    await page.clock.runFor(60_000);
    await settle(page);
    expect(reads(api)).toBe(first + 2);
  });

  test('a finished batch is never read again, and cannot be cancelled', async ({ page, api }) => {
    const state: BatchState = {
      batch: makeBatch({ status: 'DONE', rows_done: 119, rows_running: 0, spent_cents: 714, completed_at: '2026-11-14T09:30:00Z' }),
      rows: makeRows(120, 120, 0, [7]),
    };
    await openBatch(page, api, state);

    expect(reads(api)).toBe(1);
    await page.clock.runFor(60_000);
    await settle(page);
    expect(reads(api)).toBe(1);
    await expect(page.getByRole('button', { name: 'Cancel batch' })).toHaveCount(0);
  });

  test('the balance in the header follows what the rows cost', async ({ page, api }) => {
    const state = running();
    await openBatch(page, api, state);
    const header = page.getByRole('banner');
    // The header shows the balance where there is room for it.
    const wide = (page.viewportSize()?.width ?? 0) >= 768;
    if (wide) await expect(header.getByText('$12.34')).toBeVisible();

    // Two more rows were charged; /me now says less.
    api.on('GET', '/v1/auth/me', { json: makeUser({ creditBalanceCents: 12_22 }) });
    state.batch = { ...state.batch, rows_done: 42, spent_cents: 250 };
    const before = api.callsTo('GET', '/v1/auth/me').length;
    await page.clock.runFor(3_000);

    await expect(page.locator('[data-batch-spent]')).toContainText('$2.50');
    await expect.poll(() => api.callsTo('GET', '/v1/auth/me').length).toBe(before + 1);
    if (wide) await expect(header.getByText('$12.22')).toBeVisible();
  });

  test('a batch that can no longer be read says so and stops asking', async ({ page, api }) => {
    await openBatch(page, api, running());
    api.on('GET', BATCH, problem(404, 'Batch not found.', 'batch_not_found'));

    await page.clock.runFor(3_000);
    await expect(page.getByRole('alert')).toContainText('This batch can no longer be read');
    await settle(page);
    const asked = reads(api);
    await page.clock.runFor(30_000);
    await settle(page);
    expect(reads(api)).toBe(asked);
  });
});

test.describe('a batch: its rows', () => {
  test('each row shows its number, values, status, output, cost and error, and opens its job', async ({ page, api }) => {
    const state = running();
    // Row 3's job has been purged: its values went with it.
    state.rows[2] = doneRow(2, { variables: null, outputs: [] });
    // Row 4 used only the recipe's defaults.
    state.rows[3] = doneRow(3, { variables: {} });
    await openBatch(page, api, state);

    await expect(rowsRegion(page).getByRole('columnheader')).toHaveText(['Row', 'Values', 'Status', 'Output', 'Cost', 'Error']);
    await expect(rowsRegion(page).getByRole('table')).toHaveAccessibleName('Rows 1 to 50 of 120');

    // A finished row: its values, what it made, what it cost, and the way to its job.
    const first = row(page, 1);
    await expect(first.getByRole('cell').nth(0)).toHaveText('color: blue · surface: shelf 1');
    await expect(first.locator('.pill')).toHaveText('Done');
    await expect(first.getByRole('cell').nth(3)).toHaveText('$0.06');
    await expect(first.getByRole('link', { name: 'Row 1: open its generation' })).toHaveAttribute('href', `/history/${jobId(1000)}`);
    const thumb = first.getByRole('link', { name: 'Row 1: open its output' });
    await expect(thumb).toHaveAttribute('href', `/history/${jobId(1000)}`);
    await expect(thumb.locator('img')).toHaveAttribute('src', mediaUrl('row-1.png'));

    await expect(row(page, 3).getByRole('cell').nth(0)).toHaveText('Values deleted');
    await expect(row(page, 4).getByRole('cell').nth(0)).toHaveText("The recipe's defaults");

    // A failed row: why, and nothing charged.
    const failed = row(page, 8);
    await expect(failed.locator('.pill')).toHaveText('Failed');
    await expect(failed.getByRole('cell').nth(4)).toHaveText('The provider refused this prompt.');
    await expect(failed.getByRole('cell').nth(3)).toHaveText('$0.00');
    await expect(failed.locator('img')).toHaveCount(0);

    // A running row has a job to open and no cost yet; a waiting one has neither.
    await expect(row(page, 42).locator('.pill')).toHaveText('Running');
    await expect(row(page, 42).getByRole('link', { name: 'Row 42: open its generation' })).toHaveAttribute('href', `/history/${jobId(1041)}`);
    await expect(row(page, 44).locator('.pill')).toHaveText('Waiting');
    await expect(row(page, 44).getByRole('link')).toHaveCount(0);
    await expect(row(page, 44).getByRole('cell').nth(3)).toHaveText('—');
  });

  test('rows come fifty at a time, each page asked for with the last row already held', async ({ page, api }) => {
    await openBatch(page, api, running());

    await expect(rows(page)).toHaveCount(50);
    await expect(page.getByText('Rows 1–50 of 120')).toBeVisible();
    expect(rowReads(api)).toEqual(['limit=50']);

    await page.getByRole('button', { name: 'Load more rows' }).click();
    await expect(rows(page)).toHaveCount(100);
    await page.getByRole('button', { name: 'Load more rows' }).click();
    await expect(rows(page)).toHaveCount(120);
    await expect(page.getByText('Rows 1–120 of 120')).toBeVisible();
    await expect(rows(page).last().getByRole('rowheader')).toHaveText('120');
    // The last page was the last: nothing more to load.
    await expect(page.getByRole('button', { name: 'Load more rows' })).toHaveCount(0);
    expect(rowReads(api)).toEqual(['limit=50', 'limit=50&after=49', 'limit=50&after=99']);
  });

  test('a link from a job opens at its row, marked, with the earlier rows a click away', async ({ page, api }) => {
    // The API counts rows from 0: ?row=72 is the page's row 73.
    await openBatch(page, api, running(), `/batches/${batchId(1)}?row=72`);

    await expect(rows(page)).toHaveCount(50);
    await expect(page.getByText('Rows 51–100 of 120')).toBeVisible();
    expect(rowReads(api)).toEqual(['limit=50&after=49']);
    const marked = rowsRegion(page).locator('tr[data-linked-row]');
    await expect(marked).toHaveCount(1);
    await expect(marked.getByRole('rowheader')).toContainText('73');
    await expect(marked.getByRole('rowheader')).toContainText('the row you came from');
    await expect(marked).toBeInViewport();

    await page.getByRole('button', { name: 'Show earlier rows' }).click();
    await expect(rows(page)).toHaveCount(100);
    await expect(page.getByText('Rows 1–100 of 120')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Show earlier rows' })).toHaveCount(0);
    expect(rowReads(api)).toEqual(['limit=50&after=49', 'limit=50']);
  });

  test('the grid shows the outputs of the rows, each opening its job', async ({ page, api }) => {
    const state = running();
    // Row 2 made two images.
    state.rows[1] = doneRow(1, {
      outputs: [
        { index: 0, url: mediaUrl('row-2a.png'), content_type: 'image/png' },
        { index: 1, url: mediaUrl('row-2b.png'), content_type: 'image/png' },
      ],
    });
    await openBatch(page, api, state);

    const view = page.getByRole('group', { name: 'Show the rows as' });
    await expect(view.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'true');
    await view.getByRole('button', { name: 'Grid' }).click();
    await expect(view.getByRole('button', { name: 'Grid' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rowsRegion(page)).toHaveCount(0);

    // 40 finished rows with an output among the first fifty, one of them with two.
    const outputs = page.getByRole('list', { name: 'Outputs of the rows' }).getByRole('listitem');
    await expect(outputs).toHaveCount(41);
    await expect(outputs.nth(0).getByRole('link', { name: 'Row 1: open its generation' })).toHaveAttribute('href', `/history/${jobId(1000)}`);
    await expect(outputs.nth(0).getByRole('img', { name: 'Row 1' })).toHaveAttribute('src', mediaUrl('row-1.png'));
    await expect(outputs.nth(1).getByRole('img', { name: 'Row 2, output 1' })).toHaveAttribute('src', mediaUrl('row-2a.png'));
    await expect(outputs.nth(2).getByRole('img', { name: 'Row 2, output 2' })).toHaveAttribute('src', mediaUrl('row-2b.png'));
    await expect(outputs.nth(0)).toContainText('color: blue · surface: shelf 1');
    expect(await noOverflow(page)).toBe(true);

    // The table is one press away, where it was.
    await view.getByRole('button', { name: 'Table' }).click();
    await expect(rows(page)).toHaveCount(50);
  });

  test('a video output shows a frame, in the table and in the grid', async ({ page, api }) => {
    const state: BatchState = {
      batch: makeBatch({ model: 'kling-3.0', rows_total: 2, rows_done: 1, rows_failed: 0, rows_running: 1 }),
      rows: [
        doneRow(0, { outputs: [{ index: 0, url: mediaUrl('row-1.mp4'), content_type: 'video/mp4' }] }),
        ...makeRows(2, 0, 2).slice(1),
      ],
    };
    await openBatch(page, api, state);

    await expect(row(page, 1).locator('video')).toHaveAttribute('src', `${mediaUrl('row-1.mp4')}#t=0.1`);
    await page.getByRole('button', { name: 'Grid' }).click();
    await expect(page.getByRole('list', { name: 'Outputs of the rows' }).locator('video')).toHaveAttribute('aria-label', 'Row 1');
  });

  test('the page fits the screen, phone included, and the table scrolls in its own box from the keyboard', async ({ page, api }) => {
    await openBatch(page, api, running());

    expect(await noOverflow(page)).toBe(true);
    await rowsRegion(page).focus();
    await expect(rowsRegion(page)).toBeFocused();
  });

  test("a batch that isn't the reader's, or isn't there, is not found; a link that is not an id asks nothing", async ({ page, api }) => {
    await api.signIn();
    serveBatch(api, running());

    await page.goto(`/batches/${batchId(9)}`);
    await expect(page.getByText('Batch not found.')).toBeVisible();

    await page.goto('/batches/nonsense');
    await expect(page.getByText('Batch not found.')).toBeVisible();
    expect(api.callsTo('GET', '/v1/recipe-batches/nonsense')).toHaveLength(0);
  });
});

test.describe('a batch: paused', () => {
  test('paused for credit: it says so, links to Billing, and Resume carries it on', async ({ page, api }) => {
    const state = paused('insufficient_credit');
    await openBatch(page, api, state);

    await expect(status(page)).toHaveText('Paused');
    const box = pausedBox(page);
    await expect(box.getByRole('heading')).toHaveText('Paused: not enough credit');
    await expect(box).toContainText('The balance did not cover the next row, so no more rows are started.');
    await expect(box.getByRole('link', { name: 'Top up on the Billing page' })).toHaveAttribute('href', '/billing');
    await expect(box).toContainText('79 rows have not started.');

    // Nothing in a paused batch changes by itself: it is not read again while it waits.
    const waiting = reads(api);
    await page.clock.runFor(30_000);
    await settle(page);
    expect(reads(api)).toBe(waiting);

    await box.getByRole('button', { name: 'Resume' }).click();

    await expect(status(page)).toHaveText('Running');
    await expect(pausedBox(page)).toHaveCount(0);
    expect(api.callsTo('POST', `${BATCH}/resume`)).toHaveLength(1);
    // Running again, so it is read again.
    await settle(page);
    const resumed = reads(api);
    await page.clock.runFor(3_000);
    await settle(page);
    expect(reads(api)).toBe(resumed + 1);
  });

  test('paused for a cap on an API key: it says which cap, in the API\'s words, and links to API keys', async ({ page, api }) => {
    const detail = 'API-key daily spending cap would be exceeded (480 of 500 cents used or held; this request needs up to 30)';
    await openBatch(page, api, paused('spending_cap', detail));

    const box = pausedBox(page);
    await expect(box.getByRole('heading')).toHaveText('Paused: a spending cap was reached');
    await expect(box.locator('[data-pause-detail]')).toHaveText(detail);
    await expect(box).toContainText('Topping up does not lift a cap.');
    await expect(box.getByRole('link', { name: 'API keys' })).toHaveAttribute('href', '/keys');
    await expect(box.getByRole('link', { name: 'Settings' })).toHaveCount(0);
    await expect(box.getByRole('link', { name: /Billing/ })).toHaveCount(0);

    await box.getByRole('button', { name: 'Resume' }).click();
    await expect(status(page)).toHaveText('Running');
    expect(api.callsTo('POST', `${BATCH}/resume`)).toHaveLength(1);
  });

  test('paused for a cap on the account: it names the cap, shows the account\'s caps, and links to Settings', async ({ page, api }) => {
    const detail = 'daily spending cap would be exceeded (480 of 500 cents used or held; this request needs up to 30)';
    const state = paused('spending_cap', detail);
    await api.signIn(makeUser({ dailyCreditCapCents: 500, monthlyCreditCapCents: 5_000 }));
    serveBatch(api, state);
    await serveMedia(page);
    await page.goto(`/batches/${batchId(1)}`);

    const box = pausedBox(page);
    await expect(box.locator('[data-pause-detail]')).toHaveText(detail);
    await expect(box).toContainText('The cap is on your account ($5.00 a day, $50.00 a month)');
    await expect(box.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings');
    await expect(box.getByRole('link', { name: 'API keys' })).toHaveCount(0);

    await box.getByRole('button', { name: 'Resume' }).click();
    await expect(status(page)).toHaveText('Running');
  });

  test('a paused batch with rows still finishing is read until they have', async ({ page, api }) => {
    const state = paused('insufficient_credit');
    state.batch = { ...state.batch, rows_running: 1 };
    state.rows = makeRows(120, 41, 1, [7]);
    await openBatch(page, api, state);

    await expect(pausedBox(page)).toContainText('78 rows have not started. 1 that had started is still finishing.');
    const first = reads(api);

    // The row that was running finishes: one more reading shows it, and that is the last.
    state.batch = { ...state.batch, rows_done: 41, rows_running: 0, spent_cents: 244 };
    state.rows = makeRows(120, 42, 0, [7]);
    await page.clock.runFor(3_000);
    await expect(page.getByText('42 of 120 rows finished')).toBeVisible();
    await expect(row(page, 42).locator('.pill')).toHaveText('Done');
    await settle(page);
    expect(reads(api)).toBe(first + 1);
    await page.clock.runFor(30_000);
    await settle(page);
    expect(reads(api)).toBe(first + 1);
  });

  test('a Resume the API refuses says why, and the batch is shown as it now stands', async ({ page, api }) => {
    const state = paused('insufficient_credit');
    await openBatch(page, api, state);
    // Cancelled from somewhere else since the page was read.
    state.batch = { ...state.batch, status: 'CANCELLED', pause_reason: null, rows_skipped: 79 };

    await pausedBox(page).getByRole('button', { name: 'Resume' }).click();

    await expect(status(page)).toHaveText('Cancelled');
    await expect(pausedBox(page)).toHaveCount(0);
    await expect(page.getByText('Cancelled: the rows that had not started were skipped.')).toBeVisible();
  });
});

test.describe('a batch: cancel', () => {
  test('Cancel asks first, saying that running rows will finish; declined, nothing is cancelled', async ({ page, api }) => {
    await openBatch(page, api, running());

    let asked = '';
    page.once('dialog', (dialog) => {
      asked = dialog.message();
      void dialog.dismiss();
    });
    await page.getByRole('button', { name: 'Cancel batch' }).click();

    expect(asked).toContain('Cancel this batch?');
    expect(asked).toContain('Rows that are running now will finish and be charged.');
    expect(asked).toContain('Rows that have not started are skipped.');
    expect(api.callsTo('POST', `${BATCH}/cancel`)).toHaveLength(0);
    await expect(status(page)).toHaveText('Running');
  });

  test('once agreed, the batch is cancelled: the rows that had not started are skipped, and the running ones finish', async ({ page, api }) => {
    const state = running();
    await openBatch(page, api, state);

    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByRole('button', { name: 'Cancel batch' }).click();

    await expect(status(page)).toHaveText('Cancelled');
    expect(api.callsTo('POST', `${BATCH}/cancel`)).toHaveLength(1);
    await expect(page.getByText('Cancelled: the rows that had not started were skipped. 2 that had started are still finishing.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel batch' })).toHaveCount(0);
    // Done, failed, running, skipped, total.
    await expect(counts(page)).toHaveText(['40', '1', '2', '77', '120']);
    await expect(page.getByRole('region', { name: 'Progress' }).getByText('Skipped')).toBeVisible();
    // The rows on the page were read again: a waiting one is now skipped, a running one still runs.
    await expect(row(page, 44).locator('.pill')).toHaveText('Skipped');
    await expect(row(page, 42).locator('.pill')).toHaveText('Running');

    // It is still read while its running rows finish, then no more.
    await settle(page);
    const first = reads(api);
    state.batch = { ...state.batch, rows_done: 42, rows_running: 0, completed_at: '2026-11-14T09:12:00Z' };
    state.rows = state.rows.map((each) => (each.status === 'RUNNING' ? doneRow(each.index) : each));
    await page.clock.runFor(3_000);
    await expect(row(page, 42).locator('.pill')).toHaveText('Done');
    await settle(page);
    expect(reads(api)).toBe(first + 1);
    await page.clock.runFor(30_000);
    await settle(page);
    expect(reads(api)).toBe(first + 1);
  });

  test('a paused batch can be cancelled too; a cancel that fails says so', async ({ page, api }) => {
    await openBatch(page, api, paused('insufficient_credit'));
    api.on('POST', `${BATCH}/cancel`, problem(500, 'Internal Server Error'));

    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByRole('button', { name: 'Cancel batch' }).click();

    await expect(page.getByRole('alert')).toHaveText('Internal Server Error');
    await expect(status(page)).toHaveText('Paused');
  });
});

test.describe('a batch: results', () => {
  test('the CSV downloads as a file, asked for with the session and without asking for JSON', async ({ page, api }) => {
    await openBatch(page, api, running());

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      results(page).getByRole('button', { name: 'Download CSV' }).click(),
    ]);

    expect(download.suggestedFilename()).toBe('batch-ba7c0000-results.csv');
    expect(fs.readFileSync(await download.path(), 'utf8')).toBe(RESULTS_CSV);
    const call = api.callsTo('GET', `${BATCH}/results.csv`)[0];
    // A file, not JSON: the request does not say it accepts only JSON, and it carries the session cookie.
    expect(call.headers.accept).not.toContain('application/json');
    expect(call.headers.cookie).toContain('__Host-godance_session=');
    await expect(results(page).getByRole('button', { name: 'Download CSV' })).toBeEnabled();
  });

  test('the zip downloads as a file, byte for byte', async ({ page, api }) => {
    await openBatch(page, api, running());

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      results(page).getByRole('button', { name: 'Download zip' }).click(),
    ]);

    expect(download.suggestedFilename()).toBe('batch-ba7c0000-results.zip');
    expect(fs.readFileSync(await download.path()).equals(RESULTS_ZIP)).toBe(true);
    expect(api.callsTo('GET', `${BATCH}/results.zip`)[0].headers.accept).not.toContain('application/json');
  });

  test('a zip that is too large says so and offers the CSV, which downloads', async ({ page, api }) => {
    await openBatch(page, api, running());
    refuseZip(api);

    await results(page).getByRole('button', { name: 'Download zip' }).click();

    const notice = results(page).getByRole('alert');
    await expect(notice).toContainText('The outputs are too large to pack into one zip.');
    await expect(notice).toContainText('The outputs of this batch come to 612 MB, more than the 500 MB one zip may hold.');
    await expect(notice).toContainText('The CSV links to every output');

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      notice.getByRole('button', { name: 'Download the CSV instead' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('batch-ba7c0000-results.csv');
    expect(fs.readFileSync(await download.path(), 'utf8')).toBe(RESULTS_CSV);
    await expect(results(page).getByRole('alert')).toHaveCount(0);
  });

  test('a download that fails says so, and can be tried again', async ({ page, api }) => {
    await openBatch(page, api, running());
    api.on('GET', `${BATCH}/results.csv`, problem(503, 'Service Unavailable'));

    await results(page).getByRole('button', { name: 'Download CSV' }).click();
    await expect(results(page).getByRole('alert')).toHaveText('Service Unavailable');
    await expect(results(page).getByRole('button', { name: 'Download CSV' })).toBeEnabled();
  });

  test('a batch with nothing finished has no results to download yet', async ({ page, api }) => {
    await openBatch(page, api, {
      batch: makeBatch({ status: 'QUEUED', rows_done: 0, rows_failed: 0, rows_running: 0, spent_cents: 0 }),
      rows: makeRows(120, 0),
    });

    await expect(status(page)).toHaveText('Queued');
    await expect(results(page)).toHaveCount(0);
    await expect(page.getByText('0 of 120 rows finished')).toBeVisible();
  });
});
