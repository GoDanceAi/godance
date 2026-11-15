import type { Page } from '@playwright/test';
import type { BatchRequest, UserRecipe } from '../src/lib/api';
import { estimatesAsked, mockBatchCreate, mockBatchEstimate, type EstimateOptions } from './batches';
import { MODELS, serveMedia } from './generation';
import { expect, problem, test, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, recipeId, serveRecipes, versionId } from './user-recipes';

// Round 12 brief 02: a recipe run over a pasted table or a CSV file. The
// rows come from the table, each variable from one of its columns, and
// nothing starts before the total has been shown and Start is pressed.

/** The new-batch page of the first recipe: two variables, color and surface, on gpt-image-1.5. */
async function openNewBatch(page: Page, api: MockApi, options: EstimateOptions = {}, recipe: UserRecipe = makeRecipe()) {
  await api.signIn();
  serveRecipes(api, [recipe]);
  api.on('GET', '/v1/models', { json: MODELS });
  // The page asks for the total as soon as every row is whole.
  mockBatchEstimate(api, options);
  await serveMedia(page);
  await page.goto(`/recipes/${recipe.id}/batch`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(recipe.title);
}

const THREE_ROWS = 'color\tsurface\nblue\toak desk\ngreen\tmarble counter\nyellow\twindow sill';

const table = (page: Page) => page.getByLabel('Paste a table');
const preview = (page: Page) => page.getByRole('list', { name: 'Preview of the first rows' }).getByRole('listitem');
const startSection = (page: Page) => page.getByRole('region', { name: 'Before you start' });
const startButton = (page: Page) => page.getByRole('button', { name: /^Start batch/ });
const allRows = (page: Page) => page.getByRole('region', { name: 'All rows', exact: true });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const created = (api: MockApi) => api.callsTo('POST', '/v1/recipe-batches');

/** `n` rows of a tab-separated table under the recipe's two column names. */
function manyRows(n: number): string {
  return ['color\tsurface', ...Array.from({ length: n }, (_, i) => `color ${i + 1}\tsurface ${i + 1}`)].join('\n');
}

test.describe('a new batch: the table', () => {
  test('a pasted 3-row table maps both variables to their columns and previews three prompts', async ({ page, api }) => {
    await openNewBatch(page, api);

    await table(page).fill(THREE_ROWS);

    await expect(page.getByText('3 rows, 2 columns')).toBeVisible();
    // Each variable took the column that has its name.
    await expect(page.getByLabel('Column for color').locator('option:checked')).toHaveText('color');
    await expect(page.getByLabel('Column for surface').locator('option:checked')).toHaveText('surface');

    await expect(preview(page)).toHaveCount(3);
    await expect(preview(page).nth(0)).toContainText('A blue mug on a oak desk, soft morning light');
    await expect(preview(page).nth(1)).toContainText('A green mug on a marble counter, soft morning light');
    await expect(preview(page).nth(2)).toContainText('A yellow mug on a window sill, soft morning light');
  });

  test('a CSV file with a byte-order mark, quoted fields and a line break in a field is read as its rows', async ({ page, api }) => {
    await openNewBatch(page, api);

    // As a spreadsheet saves "CSV UTF-8": a mark first, CRLF line ends, and quotes
    // around a field that holds a comma, a quote or a line break.
    const csv = '﻿color,surface\r\n'
      + '"deep, dark blue","a ""rustic"" oak desk"\r\n'
      + 'green,"a marble counter\r\nby the window"\r\n';
    await page.getByLabel('CSV file').setInputFiles({ name: 'mugs.csv', mimeType: 'text/csv', buffer: Buffer.from(csv, 'utf8') });

    await expect(page.getByText('2 rows, 2 columns')).toBeVisible();
    await expect(page.getByText('separated by commas')).toBeVisible();
    await expect(page.getByText('Read from mugs.csv')).toBeVisible();
    // The mark is not part of the first column's name: the variable found its column.
    await expect(page.getByLabel('Column for color').locator('option:checked')).toHaveText('color');
    await expect(preview(page)).toHaveCount(2);
    await expect(preview(page).nth(0)).toContainText('A deep, dark blue mug on a a "rustic" oak desk, soft morning light');
    // The line break stayed inside the field: still one row, and it is in the prompt.
    await expect(preview(page).nth(1).locator('p').nth(1))
      .toHaveText('A green mug on a a marble counter\nby the window, soft morning light', { useInnerText: true });

    // What is sent is what was read.
    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)?.rows).toEqual([
      { variables: { color: 'deep, dark blue', surface: 'a "rustic" oak desk' } },
      { variables: { color: 'green', surface: 'a marble counter\nby the window' } },
    ]);
  });

  test('a CSV file dropped on the box is read into it, and the page stays where it is', async ({ page, api }) => {
    await openNewBatch(page, api);

    const data = await page.evaluateHandle(() => {
      const transfer = new DataTransfer();
      transfer.items.add(new File(['color,surface\nblue,oak desk\n'], 'dropped.csv', { type: 'text/csv' }));
      return transfer;
    });
    await table(page).dispatchEvent('drop', { dataTransfer: data });

    await expect(page.getByText('1 row, 2 columns')).toBeVisible();
    await expect(page.getByText('Read from dropped.csv')).toBeVisible();
    await expect(table(page)).toHaveValue('color,surface\nblue,oak desk\n');
    await expect(preview(page).first()).toContainText('A blue mug on a oak desk, soft morning light');
    await expect(page).toHaveURL(`/recipes/${recipeId(1)}/batch`);
  });

  test('a workbook is not a CSV: it is refused with what to do, and the table is left as it was', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THREE_ROWS);

    // A spreadsheet's own file is a zip, not text.
    await page.getByLabel('CSV file').setInputFiles({
      name: 'mugs.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0x00]),
    });

    await expect(page.getByRole('region', { name: 'Rows' }).getByRole('alert'))
      .toContainText('mugs.xlsx is not a CSV file. In your spreadsheet, export the sheet as CSV');
    await expect(table(page)).toHaveValue(THREE_ROWS);
    await expect(page.getByText('3 rows, 2 columns')).toBeVisible();
  });

  test('a file that is not UTF-8 is still read, and says how to avoid the guess', async ({ page, api }) => {
    await openNewBatch(page, api);

    // "café" as an older spreadsheet export writes it: one byte for the é.
    const latin1 = Buffer.concat([Buffer.from('color,surface\nred,caf', 'latin1'), Buffer.from([0xe9]), Buffer.from(' table\n', 'latin1')]);
    await page.getByLabel('CSV file').setInputFiles({ name: 'old.csv', mimeType: 'text/csv', buffer: latin1 });

    await expect(page.getByText('1 row, 2 columns')).toBeVisible();
    await expect(preview(page).first()).toContainText('A red mug on a café table');
    await expect(page.getByRole('list', { name: 'About this table' })).toContainText('save it as “CSV UTF-8”');
  });

  test('what was left out of a table is said: empty rows, and fields past the last column', async ({ page, api }) => {
    await openNewBatch(page, api);

    await table(page).fill('color,surface\nblue,desk,extra\n,\ngreen,counter\n');

    await expect(page.getByText('2 rows, 2 columns')).toBeVisible();
    const notes = page.getByRole('list', { name: 'About this table' });
    await expect(notes).toContainText('Row 1 has more fields than the header has columns. The extra fields were left out.');
    await expect(notes).toContainText('One empty row was left out.');
  });

  test('columns are matched by name whatever the case, and can be chosen by hand or left to the default', async ({ page, api }) => {
    await openNewBatch(page, api);

    // "Color" is the variable color; "where" and "sku" are nobody's.
    await table(page).fill('Color,where,sku\nblue,oak desk,A-1\ngreen,marble counter,A-2');

    const color = page.getByLabel('Column for color');
    const surface = page.getByLabel('Column for surface');
    await expect(color.locator('option:checked')).toHaveText('Color');
    // No column is called surface: every row uses the default, and the page says so.
    await expect(surface.locator('option:checked')).toHaveText('No column: use the default');
    await expect(page.getByText('No column: every row uses the default, “kitchen shelf”.')).toBeVisible();
    await expect(preview(page).nth(0)).toContainText('A blue mug on a kitchen shelf, soft morning light');
    await expect(page.getByText('Not used: where, sku.')).toBeVisible();
    // The table of all rows marks the value as the default.
    const first = allRows(page).getByRole('row').nth(1);
    await expect(first.getByRole('cell').nth(1)).toHaveText('kitchen shelfdefault');

    // By hand: surface reads "where".
    await surface.selectOption({ label: 'where' });
    await expect(preview(page).nth(0)).toContainText('A blue mug on a oak desk, soft morning light');
    await expect(preview(page).nth(1)).toContainText('A green mug on a marble counter, soft morning light');
    await expect(page.getByText('Not used: sku.')).toBeVisible();

    // And a variable can be told to ignore the column it was given.
    await color.selectOption({ label: 'No column: use the default' });
    await expect(preview(page).nth(0)).toContainText('A red mug on a oak desk, soft morning light');

    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)?.rows).toEqual([
      { variables: { surface: 'oak desk' } },
      { variables: { surface: 'marble counter' } },
    ]);
  });

  test('an empty cell uses the variable\'s default, and a choice by hand holds only for the table it was made on', async ({ page, api }) => {
    await openNewBatch(page, api);

    await table(page).fill('color,surface\nblue,\n,marble counter');
    await expect(preview(page).nth(0)).toContainText('A blue mug on a kitchen shelf, soft morning light');
    await expect(preview(page).nth(1)).toContainText('A red mug on a marble counter, soft morning light');
    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)?.rows).toEqual([
      { variables: { color: 'blue' } },
      { variables: { surface: 'marble counter' } },
    ]);

    // A choice by hand, then a table with other columns: they are matched afresh.
    await page.getByLabel('Column for surface').selectOption({ label: 'color' });
    await expect(preview(page).nth(0)).toContainText('A blue mug on a blue, soft morning light');
    await table(page).fill('surface,color,note\noak desk,yellow,x');
    await expect(page.getByLabel('Column for surface').locator('option:checked')).toHaveText('surface');
    await expect(preview(page).nth(0)).toContainText('A yellow mug on a oak desk, soft morning light');
  });

  test('a value longer than a variable may be is flagged on its row, and nothing is priced', async ({ page, api }) => {
    await openNewBatch(page, api);

    await table(page).fill(`color\tsurface\nblue\toak desk\n${'x'.repeat(2001)}\tcounter`);

    await expect(allRows(page).getByRole('row').nth(2)).toContainText('The value for color is longer than 2,000 characters.');
    await expect(startSection(page)).toContainText('Row 2 has a problem. Fix it in the table to see the total.');
    await expect(startButton(page)).toBeDisabled();
    await page.waitForTimeout(700);
    expect(estimatesAsked(api)).toHaveLength(0);
  });
});

test.describe('a new batch: the total, and Start', () => {
  test('the estimate shows rows, total and balance, and nothing starts until Start is pressed', async ({ page, api }) => {
    await openNewBatch(page, api);
    const started = mockBatchCreate(api);

    // Nothing to price yet, and nothing to start.
    await expect(startSection(page)).toContainText('Paste a table or choose a CSV file to see the total.');
    await expect(startButton(page)).toBeDisabled();

    await table(page).fill(THREE_ROWS);

    await expect(startSection(page).getByRole('definition')).toHaveText(['3', '$0.30', '$12.34']);
    await expect(startButton(page)).toBeEnabled();
    await expect(startButton(page)).toHaveText('Start batch · $0.30');
    await expect(page.getByText('Nothing is generated or charged before you press Start.')).toBeVisible();
    // The total was asked for; the batch was not.
    expect(estimatesAsked(api).length).toBeGreaterThan(0);
    expect(created(api)).toHaveLength(0);
    expect(api.callsTo('POST', /generations|edits/)).toHaveLength(0);

    await startButton(page).click();

    // The batch is exactly what was priced: the latest version, its model and settings, each row's values.
    const body: BatchRequest = {
      version_id: versionId(1, 3),
      model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 2 },
      rows: [
        { variables: { color: 'blue', surface: 'oak desk' } },
        { variables: { color: 'green', surface: 'marble counter' } },
        { variables: { color: 'yellow', surface: 'window sill' } },
      ],
      max_concurrency: 2,
    };
    await expect(page).toHaveURL(`/batches/${started.batch.id}`);
    expect(created(api).map((call) => call.body)).toEqual([body]);
    expect(estimatesAsked(api).at(-1)).toEqual(body);
    // And its page opened.
    await expect(page.getByRole('heading', { level: 1, name: 'Mug on a kitchen shelf' })).toBeVisible();
    await expect(page.getByText('0 of 3 rows finished')).toBeVisible();
  });

  test('a change to the table takes the estimate away until the new one is shown', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THREE_ROWS);
    await expect(startButton(page)).toHaveText('Start batch · $0.30');

    // The next estimate is held back, so the moment in between can be seen.
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    api.on('POST', '/v1/recipe-batches/estimate', async (call) => {
      await held;
      const body = call.body as BatchRequest;
      return { json: { rows: body.rows.length, total_cents: body.rows.length * 10, balance_cents: 12_34, enough: true, limits: { max_rows: 500, max_concurrency: 4 } } };
    });
    await table(page).fill(`${THREE_ROWS}\nwhite\tstone shelf`);

    // Four rows on the page, and no total for four rows yet: Start is not offered.
    await expect(page.getByText('4 rows, 2 columns')).toBeVisible();
    await expect(startButton(page)).toBeDisabled();
    await expect(startButton(page)).toHaveText('Start batch');
    await expect(startSection(page)).toContainText('Working out the total…');

    release();
    await expect(startButton(page)).toHaveText('Start batch · $0.40');
    await expect(startButton(page)).toBeEnabled();
  });

  test('with not enough credit the page says so and links to Billing; Start is still possible', async ({ page, api }) => {
    await openNewBatch(page, api, { perRow: 500 });
    const started = mockBatchCreate(api);

    await table(page).fill(THREE_ROWS);

    // $15.00 against a balance of $12.34.
    await expect(startSection(page).getByRole('definition')).toHaveText(['3', '$15.00', '$12.34']);
    const warning = startSection(page).getByRole('alert');
    await expect(warning).toContainText('Your balance of $12.34 does not cover the estimated $15.00.');
    await expect(warning).toContainText('the batch runs until the credit runs out, then pauses');
    await expect(warning.getByRole('link', { name: 'Top up on the Billing page' })).toHaveAttribute('href', '/billing');
    expect(created(api)).toHaveLength(0);

    // The warning has been shown; starting is the user's call. The server pauses it when the credit runs out.
    await expect(startButton(page)).toBeEnabled();
    await startButton(page).click();
    await expect(page).toHaveURL(`/batches/${started.batch.id}`);
    expect(created(api)).toHaveLength(1);
  });

  test('the model defaults to the recipe\'s, another can be chosen, and its settings are fitted', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THREE_ROWS);

    const model = startSection(page).getByLabel('Model');
    await expect(model).toHaveValue('gpt-image-1.5');
    await expect(model.locator('option:checked')).toContainText("GPT Image 1.5 · the recipe's model");
    await expect(startSection(page)).toContainText('Settings for every row: 1536x1024 · Count 2');
    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)).toMatchObject({ model: 'gpt-image-1.5', params: { size: '1536x1024', n: 2 } });

    // FLUX.2 [pro] has no 1536x1024 and makes one image a request: neither setting is sent to it.
    await model.selectOption('flux-2-pro');
    await expect(startSection(page)).toContainText('1536x1024 is not a size FLUX.2 [pro] offers, so its own default is used.');
    await expect(startSection(page)).toContainText('FLUX.2 [pro] makes one per request, so each row asks for one, not 2.');
    await expect(startButton(page)).toBeEnabled();
    const asked = estimatesAsked(api).at(-1)!;
    expect(asked.model).toBe('flux-2-pro');
    // The whole params object is sent, because one that is sent replaces the version's.
    expect(asked.params).toEqual({});
  });

  test('rows at a time runs from 1 to the cap the estimate gives, and is part of the request', async ({ page, api }) => {
    await openNewBatch(page, api, { maxConcurrency: 3 });
    const concurrency = page.getByLabel('Rows at a time');

    // Before the API has said its cap: 1 to 4, starting at 2.
    await expect(concurrency.locator('option')).toHaveText(['1', '2', '3', '4']);
    await expect(concurrency).toHaveValue('2');

    await table(page).fill(THREE_ROWS);
    await expect(startButton(page)).toBeEnabled();
    // The estimate said 3 is the most.
    await expect(concurrency.locator('option')).toHaveText(['1', '2', '3']);

    await concurrency.selectOption('3');
    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)?.max_concurrency).toBe(3);

    const started = mockBatchCreate(api);
    await startButton(page).click();
    await expect(page).toHaveURL(`/batches/${started.batch.id}`);
    expect((created(api)[0].body as BatchRequest).max_concurrency).toBe(3);
  });

  test('a batch the API refuses to start says why, and can be tried again', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THREE_ROWS);
    api.on('POST', '/v1/recipe-batches', problem(409, 'Two batches are already running. Wait for one to finish.', 'batch_limit_reached'));

    await startButton(page).click();

    await expect(startSection(page).getByRole('alert')).toHaveText('Two batches are already running. Wait for one to finish.');
    await expect(page).toHaveURL(`/recipes/${recipeId(1)}/batch`);
    // Still priced, still startable.
    await expect(startButton(page)).toBeEnabled();
  });

  test('a table with more rows than a batch takes is refused before anything is asked', async ({ page, api }) => {
    await openNewBatch(page, api, { maxRows: 5 });

    // The first estimate says how many rows a batch takes.
    await table(page).fill(THREE_ROWS);
    await expect(startButton(page)).toBeEnabled();
    const asked = estimatesAsked(api).length;

    await table(page).fill(manyRows(7));
    await expect(startSection(page)).toContainText('A batch takes up to 5 rows and this table has 7. Remove 2, or run it as more than one batch.');
    await expect(startButton(page)).toBeDisabled();
    await page.waitForTimeout(700);
    expect(estimatesAsked(api)).toHaveLength(asked);
  });

  test('before the API has said its limit, a long table is priced by the API, which says if it is too long', async ({ page, api }) => {
    await openNewBatch(page, api, { maxRows: 5 });

    await table(page).fill(manyRows(7));

    await expect(startSection(page).getByRole('alert')).toHaveText('A batch takes at most 5 rows; this one has 7.');
    await expect(startButton(page)).toBeDisabled();
  });
});

test.describe('a new batch: a row the API refuses', () => {
  /** Thirty rows: the table of all rows shows 25 at a time, so row 28 is on its second page. */
  const THIRTY = manyRows(30);

  test('a refusal that names a row brings that row onto the page, marked, with the reason', async ({ page, api }) => {
    await openNewBatch(page, api);
    // The API counts rows from 0: its row 27 is the page's row 28.
    api.on('POST', '/v1/recipe-batches/estimate', problem(400,
      'row 27: the variable "finish" is not one of this recipe\'s variables.', 'recipe_variable_unknown', { row: 27 }));

    await table(page).fill(THIRTY);

    const reason = 'the variable "finish" is not one of this recipe\'s variables.';
    // The reason, with the row numbered as the page numbers it, and never the API's number beside it.
    await expect(page.locator('[data-row-error-reason]')).toHaveText(`Row 28: ${reason}`);
    await expect(page.getByText('row 27')).toHaveCount(0);
    // The table moved to the page that holds it, and marks it.
    const refused = allRows(page).locator('tr[data-row-refused]');
    await expect(refused).toHaveCount(1);
    await expect(refused.getByRole('rowheader')).toHaveText('28');
    await expect(refused).toContainText(`Refused: ${reason}`);
    await expect(page.getByText('Rows 26–30 of 30')).toBeVisible();
    await expect(startSection(page)).toContainText('Row 28 was refused, so nothing was started. It is marked in the table above.');
    await expect(startButton(page)).toBeDisabled();
    expect(created(api)).toHaveLength(0);

    // Once the table changes, the refusal belongs to another request and is gone.
    mockBatchEstimate(api);
    await table(page).fill(manyRows(29));
    await expect(startButton(page)).toBeEnabled();
    await expect(allRows(page).locator('tr[data-row-refused]')).toHaveCount(0);
    await expect(page.locator('[data-row-error-reason]')).toHaveCount(0);
  });

  test('a row refused at Start is marked the same way, and nothing was started', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THIRTY);
    await expect(startButton(page)).toBeEnabled();
    api.on('POST', '/v1/recipe-batches', problem(400, 'row 3: a value is longer than 2000 characters.', 'invalid_batch', { row: 3 }));

    // The user has paged away from the row that will be refused.
    await page.getByRole('button', { name: 'Next rows' }).click();
    await expect(page.getByText('Rows 26–30 of 30')).toBeVisible();
    await startButton(page).click();

    await expect(page.locator('[data-row-error-reason]')).toHaveText('Row 4: a value is longer than 2000 characters.');
    await expect(allRows(page).locator('tr[data-row-refused]').getByRole('rowheader')).toHaveText('4');
    await expect(page.getByText('Rows 1–25 of 30')).toBeVisible();
    await expect(page).toHaveURL(`/recipes/${recipeId(1)}/batch`);
  });
});

test.describe('a new batch: 500 rows', () => {
  test('500 rows paste, preview and price without freezing the page', async ({ page, api }) => {
    await openNewBatch(page, api);
    const rows = manyRows(500);

    // From the paste to the summary, the preview and the first page of the table.
    const pasted = Date.now();
    await table(page).fill(rows);
    await expect(page.getByText('500 rows, 2 columns')).toBeVisible();
    await expect(preview(page)).toHaveCount(3);
    await expect(allRows(page).getByRole('row')).toHaveCount(26);
    expect(Date.now() - pasted, 'paste to preview, in ms').toBeLessThan(3_000);

    // The table never holds more than a page of rows, whatever the table's size.
    await expect(page.getByText('Rows 1–25 of 500')).toBeVisible();
    await expect(startButton(page)).toHaveText('Start batch · $50.00');
    expect(estimatesAsked(api).at(-1)?.rows).toHaveLength(500);

    // One more keystroke's worth of work, measured in the page: the table is read again and
    // the page drawn again within a tenth of the time a person would notice as a freeze.
    const redraw = await page.evaluate(async (text) => {
      const box = document.getElementById('batch-table') as HTMLTextAreaElement;
      const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
      const started = performance.now();
      set.call(box, text);
      box.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return performance.now() - started;
    }, `${rows}\nlast\tone`);
    await expect(page.getByText('501 rows, 2 columns')).toBeVisible();
    expect(redraw, 'reading and drawing 501 rows, in ms').toBeLessThan(1_000);

    // Paging through it is immediate too.
    const paged = Date.now();
    await page.getByRole('button', { name: 'Next rows' }).click();
    await expect(page.getByText('Rows 26–50 of 501')).toBeVisible();
    expect(Date.now() - paged, 'next page, in ms').toBeLessThan(1_000);
    expect(await noOverflow(page)).toBe(true);
  });
});

test.describe('a new batch: the page', () => {
  test('says which version runs, shows the template, and fits the screen with a table on it', async ({ page, api }) => {
    await openNewBatch(page, api);

    await expect(page.getByText('version 3, the latest')).toBeVisible();
    await expect(page.locator('[data-batch-template] mark')).toHaveText(['{{color}}', '{{surface}}']);
    await expect(page.getByRole('link', { name: '← Back to the recipe' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);

    await table(page).fill(THREE_ROWS);
    await expect(startButton(page)).toBeEnabled();
    // Wide tables scroll inside their own box: the page does not move sideways, phone included.
    expect(await noOverflow(page)).toBe(true);
  });

  test('the table of all rows is a real table: named, with row and column headers, reachable from the keyboard', async ({ page, api }) => {
    await openNewBatch(page, api);
    await table(page).fill(THREE_ROWS);

    const region = page.getByRole('region', { name: 'All rows of the batch' });
    await expect(region.getByRole('table')).toHaveAccessibleName(/Rows 1 to 3 of 3/);
    await expect(region.getByRole('columnheader')).toHaveText(['Row', 'color', 'surface', 'Check']);
    await expect(region.getByRole('rowheader')).toHaveText(['1', '2', '3']);
    await expect(region.getByRole('row').nth(1).getByRole('cell')).toHaveText(['blue', 'oak desk', 'Ready']);
    // The box that scrolls takes the focus, so the keyboard can scroll it.
    await region.focus();
    await expect(region).toBeFocused();
  });

  test('a recipe with no variables runs the same prompt on every row', async ({ page, api }) => {
    const plain = makeRecipe({
      title: 'A red mug', latest_version: makeVersion({ template: 'A red mug', variables: [], params: {} }),
    });
    await openNewBatch(page, api, {}, plain);

    await table(page).fill('sku\nA-1\nA-2');

    await expect(page.getByText('This recipe has no variables, so every row runs the same prompt.')).toBeVisible();
    await expect(preview(page)).toHaveText([/A red mug/, /A red mug/]);
    await expect(startButton(page)).toBeEnabled();
    expect(estimatesAsked(api).at(-1)?.rows).toEqual([{ variables: {} }, { variables: {} }]);
  });

  test("a recipe that isn't there is not found, and nothing is asked for a link that is not an id", async ({ page, api }) => {
    await api.signIn();
    serveRecipes(api, []);
    api.on('GET', '/v1/models', { json: MODELS });

    await page.goto(`/recipes/${recipeId(9)}/batch`);
    await expect(page.getByText('Recipe not found.')).toBeVisible();

    await page.goto('/recipes/nonsense/batch');
    await expect(page.getByText('Recipe not found.')).toBeVisible();
    expect(api.callsTo('GET', '/v1/recipes/nonsense')).toHaveLength(0);
  });
});
