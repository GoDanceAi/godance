import type { Page } from '@playwright/test';
import type { BatchRequest, MeResponse, UserRecipe } from '../src/lib/api';
import { estimatesAsked, mockBatchCreate, mockBatchEstimate } from './batches';
import { serveMedia } from './generation';
import { INPUT_MODELS, inputsUser, PNG } from './inputs';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, serveRecipes } from './user-recipes';

// Round 12 brief 02: a batch of a recipe with input slots. Many images are
// chosen at once and given to rows, by the file names in a column where the
// table has one, otherwise in name order; they are uploaded through
// POST /v1/files, and a row without an image is said before anything is priced.

const SLOT = 'product photo';

/** The first recipe with one input slot, "product photo", beside its two variables. */
function slotRecipe(): UserRecipe {
  return makeRecipe({
    latest_version: makeVersion({ inputs: [{ slot: SLOT, role: 'image', hint: 'One product on a plain background' }] }),
  });
}

/**
 * POST /v1/files, storing each upload under an id made from its file name,
 * so a test can say which file a row was given. Returns the names stored, in order.
 */
function serveNamedUploads(api: MockApi): string[] {
  const stored: string[] = [];
  api.on('POST', '/v1/files', (call) => {
    const name = /filename="([^"]+)"/.exec(String(call.body))?.[1] ?? `unnamed-${stored.length + 1}`;
    stored.push(name);
    return {
      status: 201,
      json: { id: idOf(name), object: 'file', purpose: 'generation_input', bytes: PNG.length, content_type: 'image/png',
        width: 1, height: 1, contains_person: false, created_at: 1794700000, expires_at: 1794786400 },
    };
  });
  return stored;
}

/** The id the mock stores a file under: readable, so a request shows which file it names. */
const idOf = (name: string) => `file:${name}`;

async function openSlotBatch(page: Page, api: MockApi, user: MeResponse = inputsUser(), recipe: UserRecipe = slotRecipe()) {
  await api.signIn(user);
  serveRecipes(api, [recipe]);
  api.on('GET', '/v1/models', { json: INPUT_MODELS });
  mockBatchEstimate(api);
  await serveMedia(page);
  await page.goto(`/recipes/${recipe.id}/batch`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(recipe.title);
}

const png = (name: string) => ({ name, mimeType: 'image/png', buffer: PNG });
const table = (page: Page) => page.getByLabel('Paste a table');
// Exact: "Match images for product photo" is another control's label.
const images = (page: Page) => page.getByLabel(`Images for ${SLOT}`, { exact: true });
const plan = (page: Page) => page.getByLabel(`Match images for ${SLOT}`);
const slotBox = (page: Page) => page.locator(`[data-batch-slot="${SLOT}"]`);
const allRows = (page: Page) => page.getByRole('region', { name: 'All rows of the batch' });
const rowCells = (page: Page, n: number) => allRows(page).getByRole('row').nth(n).getByRole('cell');
const preview = (page: Page) => page.getByRole('list', { name: 'Preview of the first rows' }).getByRole('listitem');
const startSection = (page: Page) => page.getByRole('region', { name: 'Before you start' });
const startButton = (page: Page) => page.getByRole('button', { name: /^Start batch/ });
const uploaded = (page: Page) => page.getByRole('progressbar', { name: 'Images uploaded' });
/** The line that says every image is stored. Exact: the same words are also said to a screen reader, with a full stop. */
const allUploaded = (page: Page, n: number) =>
  page.getByText(`All ${n} ${n === 1 ? 'image' : 'images'} uploaded`, { exact: true });

/** The file each row was given in the last request priced. */
function filesAsked(api: MockApi): (string | undefined)[] {
  return (estimatesAsked(api).at(-1)?.rows ?? []).map((row) => {
    const ref = row.inputs?.[SLOT];
    return ref && 'file_id' in ref ? ref.file_id : undefined;
  });
}

test.describe('a batch with images: by a column of file names', () => {
  test('a column of file names matches each image to its row, whatever order the files were chosen in', async ({ page, api }) => {
    await openSlotBatch(page, api);
    const stored = serveNamedUploads(api);

    await table(page).fill('color,surface,image\nblue,oak desk,mug-blue.png\ngreen,marble counter,photos/MUG-GREEN.PNG\nyellow,window sill,mug-yellow');
    // The table has a column of file names, so that is how images are matched, without asking.
    await expect(plan(page).locator('option:checked')).toHaveText('By the file names in “image”');
    await expect(slotBox(page)).toContainText('3 rows need an image for “product photo”.');
    await expect(startButton(page)).toBeDisabled();

    // Chosen all at once, in no particular order.
    await images(page).setInputFiles([png('mug-yellow.png'), png('mug-blue.png'), png('mug-green.png')]);

    await expect(slotBox(page)).toContainText('3 images chosen');
    await expect(slotBox(page)).toContainText('Every row has an image.');
    // Each row has the file its cell names: folders, case and a missing extension do not matter.
    await expect(rowCells(page, 1).nth(2)).toContainText('mug-blue.png');
    await expect(rowCells(page, 2).nth(2)).toContainText('mug-green.png');
    await expect(rowCells(page, 3).nth(2)).toContainText('mug-yellow.png');

    // They are uploaded through /v1/files, and the progress says so.
    await expect(allUploaded(page, 3)).toBeVisible();
    await expect(uploaded(page)).toHaveAttribute('aria-valuenow', '3');
    await expect(uploaded(page)).toHaveAttribute('aria-valuemax', '3');
    await expect(rowCells(page, 1).nth(2)).toContainText('uploaded');
    expect([...stored].sort()).toEqual(['mug-blue.png', 'mug-green.png', 'mug-yellow.png']);
    expect(api.callsTo('POST', '/v1/files')[0].headers['content-type']).toMatch(/^multipart\/form-data/);

    // The preview shows each of the first rows with its image.
    await expect(preview(page).nth(0).getByRole('img', { name: `${SLOT}: mug-blue.png` })).toBeVisible();
    await expect(preview(page).nth(2).getByRole('img', { name: `${SLOT}: mug-yellow.png` })).toBeVisible();

    // The total is asked for once every row is whole, with each row's own file.
    await expect(startButton(page)).toBeEnabled();
    expect(filesAsked(api)).toEqual([idOf('mug-blue.png'), idOf('mug-green.png'), idOf('mug-yellow.png')]);
    // The file-name column is an image's, not a variable's: it is not sent as a value.
    expect(estimatesAsked(api).at(-1)?.rows[0].variables).toEqual({ color: 'blue', surface: 'oak desk' });
  });

  test('rows whose image is missing are flagged, files that match no row are counted, and nothing is priced', async ({ page, api }) => {
    await openSlotBatch(page, api);
    const stored = serveNamedUploads(api);

    await table(page).fill('color,image\nblue,mug-1.png\ngreen,mug-2.png\nyellow,\nwhite,mug-4.png');
    await images(page).setInputFiles([png('mug-1.png'), png('mug-4.png'), png('extra.png')]);

    // Row 2 names a file that was not chosen; row 3 names none.
    await expect(slotBox(page).locator('[data-missing-images]'))
      .toHaveText('2 rows have no image for “product photo”: rows 2 and 3.');
    await expect(slotBox(page).locator('[data-unused-images]'))
      .toHaveText('1 image matches no row and is not uploaded: extra.png.');
    await expect(rowCells(page, 2).nth(2)).toHaveText('No image');
    await expect(rowCells(page, 2).last()).toHaveText('No image for “product photo”.');
    await expect(rowCells(page, 1).last()).toHaveText('Ready');
    await expect(page.getByText('2 of 4 rows have a problem')).toBeVisible();
    await expect(startSection(page)).toContainText('2 rows have a problem. Fix it in the table to see the total.');
    await expect(startButton(page)).toBeDisabled();

    // Only the files a row uses are uploaded, and no total is asked for while a row is not whole.
    await expect(allUploaded(page, 2)).toBeVisible();
    expect([...stored].sort()).toEqual(['mug-1.png', 'mug-4.png']);
    await page.waitForTimeout(700);
    expect(estimatesAsked(api)).toHaveLength(0);

    // The missing ones arrive: one more file, and a file name in the empty cell.
    await images(page).setInputFiles([png('mug-2.png')]);
    await table(page).fill('color,image\nblue,mug-1.png\ngreen,mug-2.png\nyellow,extra.png\nwhite,mug-4.png');

    await expect(slotBox(page)).toContainText('Every row has an image.');
    await expect(allUploaded(page, 4)).toBeVisible();
    await expect(startButton(page)).toBeEnabled();
    expect(filesAsked(api)).toEqual([idOf('mug-1.png'), idOf('mug-2.png'), idOf('extra.png'), idOf('mug-4.png')]);
    // A file already stored is not sent again when the matching changes.
    expect([...stored].sort()).toEqual(['extra.png', 'mug-1.png', 'mug-2.png', 'mug-4.png']);
  });

  test('one image named by many rows is uploaded once', async ({ page, api }) => {
    await openSlotBatch(page, api);
    const stored = serveNamedUploads(api);

    await table(page).fill('color,image\nblue,mug.png\ngreen,mug.png\nyellow,mug.png');
    await images(page).setInputFiles([png('mug.png')]);

    await expect(allUploaded(page, 1)).toBeVisible();
    await expect(startButton(page)).toBeEnabled();
    expect(stored).toEqual(['mug.png']);
    expect(filesAsked(api)).toEqual([idOf('mug.png'), idOf('mug.png'), idOf('mug.png')]);
  });
});

test.describe('a batch with images: by order', () => {
  test('with no column of file names, images go to rows in name order, numbers as numbers', async ({ page, api }) => {
    await openSlotBatch(page, api);
    serveNamedUploads(api);

    await table(page).fill('color,surface\nblue,oak desk\ngreen,marble counter\nyellow,window sill');
    await expect(plan(page).locator('option:checked')).toHaveText('In name order: the first image to row 1');
    await expect(slotBox(page)).toContainText('Images are taken in name order, so “photo 2” comes before “photo 10”.');

    await images(page).setInputFiles([png('photo 10.png'), png('photo 2.png'), png('photo 1.png')]);

    await expect(rowCells(page, 1).nth(2)).toContainText('photo 1.png');
    await expect(rowCells(page, 2).nth(2)).toContainText('photo 2.png');
    await expect(rowCells(page, 3).nth(2)).toContainText('photo 10.png');
    await expect(startButton(page)).toBeEnabled();
    expect(filesAsked(api)).toEqual([idOf('photo 1.png'), idOf('photo 2.png'), idOf('photo 10.png')]);
  });

  test('fewer images than rows flags the rows left over; the matching can be switched by hand', async ({ page, api }) => {
    await openSlotBatch(page, api);
    serveNamedUploads(api);

    await table(page).fill('color,file\nblue,b.png\ngreen,a.png\nyellow,c.png');
    await images(page).setInputFiles([png('a.png'), png('b.png')]);

    // By the file names in "file": rows 1 and 2 have theirs, row 3's was not chosen.
    await expect(rowCells(page, 1).nth(2)).toContainText('b.png');
    await expect(slotBox(page).locator('[data-missing-images]')).toHaveText('1 row has no image for “product photo”: row 3.');

    // By order instead: a.png to row 1, b.png to row 2, and row 3 is still without.
    await plan(page).selectOption('order');
    await expect(rowCells(page, 1).nth(2)).toContainText('a.png');
    await expect(rowCells(page, 2).nth(2)).toContainText('b.png');
    await expect(slotBox(page).locator('[data-missing-images]')).toHaveText('1 row has no image for “product photo”: row 3.');
    await expect(startButton(page)).toBeDisabled();

    await images(page).setInputFiles([png('c.png')]);
    await expect(startButton(page)).toBeEnabled();
    expect(filesAsked(api)).toEqual([idOf('a.png'), idOf('b.png'), idOf('c.png')]);
  });

  test('with no table at all, each image is a row and every variable uses its default', async ({ page, api }) => {
    await openSlotBatch(page, api);
    serveNamedUploads(api);
    const started = mockBatchCreate(api);

    await expect(page.getByText('With no table, each image is one row.')).toBeVisible();
    await images(page).setInputFiles([png('b.png'), png('a.png'), png('c.png')]);

    await expect(preview(page)).toHaveCount(3);
    await expect(preview(page).nth(0)).toContainText('A red mug on a kitchen shelf, soft morning light');
    await expect(preview(page).nth(0).getByRole('img', { name: `${SLOT}: a.png` })).toBeVisible();
    await expect(startSection(page).getByRole('definition')).toHaveText(['3', '$0.30', '$12.34']);

    await startButton(page).click();
    await expect(page).toHaveURL(`/batches/${started.batch.id}`);
    expect((api.callsTo('POST', '/v1/recipe-batches')[0].body as BatchRequest).rows).toEqual([
      { variables: {}, inputs: { [SLOT]: { file_id: idOf('a.png') } } },
      { variables: {}, inputs: { [SLOT]: { file_id: idOf('b.png') } } },
      { variables: {}, inputs: { [SLOT]: { file_id: idOf('c.png') } } },
    ]);
  });

  test('images can be dropped onto the slot, many at once', async ({ page, api }) => {
    await openSlotBatch(page, api);
    serveNamedUploads(api);
    await table(page).fill('color\nblue\ngreen');

    // Two files dropped together, as from a folder.
    const data = await page.evaluateHandle((bytes) => {
      const transfer = new DataTransfer();
      for (const name of ['two.png', 'one.png']) {
        transfer.items.add(new File([new Uint8Array(bytes)], name, { type: 'image/png' }));
      }
      return transfer;
    }, [...PNG]);
    await slotBox(page).dispatchEvent('drop', { dataTransfer: data });

    await expect(slotBox(page)).toContainText('2 images chosen');
    // The cells of a row: color, surface, the slot's image, and the check.
    await expect(rowCells(page, 1).nth(2)).toContainText('one.png');
    await expect(rowCells(page, 2).nth(2)).toContainText('two.png');
    await expect(startButton(page)).toBeEnabled();
  });
});

test.describe('a batch with images: uploads', () => {
  test('uploads the API refuses say why, the files still waiting are not sent, and it can be tried again', async ({ page, api }) => {
    await openSlotBatch(page, api);
    await table(page).fill('color\nblue\ngreen\nyellow\nwhite\nblack');
    // The account's hourly upload limit.
    api.on('POST', '/v1/files', problem(429, 'Too many uploads.', 'rate_limited'));

    await images(page).setInputFiles(['a', 'b', 'c', 'd', 'e'].map((name) => png(`${name}.png`)));

    const alert = page.getByRole('region', { name: 'Images' }).getByRole('alert');
    await expect(alert).toContainText('5 images could not be uploaded.');
    await expect(alert.getByRole('listitem')).toHaveText(
      ['a.png, b.png, c.png, d.png, e.png: Too many uploads in a short time. Wait a little and try again.']);
    // Three go up at a time. The two still waiting were not sent: the same refusal would have met them.
    expect(api.callsTo('POST', '/v1/files')).toHaveLength(3);
    await expect(rowCells(page, 1).last()).toHaveText('a.png could not be uploaded.');
    await expect(rowCells(page, 5).last()).toHaveText('e.png could not be uploaded.');
    await expect(startSection(page)).toContainText('Some images could not be uploaded. Try the upload again to see the total.');
    await expect(startButton(page)).toBeDisabled();

    const stored = serveNamedUploads(api);
    await alert.getByRole('button', { name: 'Try the upload again' }).click();

    await expect(allUploaded(page, 5)).toBeVisible();
    expect([...stored].sort()).toEqual(['a.png', 'b.png', 'c.png', 'd.png', 'e.png']);
    await expect(startButton(page)).toBeEnabled();
  });

  test('a file that is not an image is left out, and said', async ({ page, api }) => {
    await openSlotBatch(page, api);
    const stored = serveNamedUploads(api);
    await table(page).fill('color\nblue');

    await images(page).setInputFiles([png('a.png'), { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('x') }]);

    await expect(slotBox(page).getByRole('alert')).toHaveText('1 file left out: an input is a PNG, JPEG or WebP image.');
    await expect(slotBox(page)).toContainText('1 image chosen');
    await expect(allUploaded(page, 1)).toBeVisible();
    expect(stored).toEqual(['a.png']);
  });

  test('the first image before the input terms are accepted opens them, and nothing is uploaded until Accept', async ({ page, api }) => {
    let accepted = false;
    await openSlotBatch(page, api, inputsUser({ accepted: false, accepted_at: null }));
    // Once accepted, /me says so.
    api.on('GET', '/v1/auth/me', () => ({ json: inputsUser(accepted ? {} : { accepted: false, accepted_at: null }) }));
    api.on('POST', '/v1/inputs/terms', () => { accepted = true; return { status: 204 }; });
    const stored = serveNamedUploads(api);
    await table(page).fill('color\nblue');

    // No file dialog opens: the terms do.
    await slotBox(page).getByText('Choose images').click();
    const dialog = page.getByRole('dialog', { name: 'Before you add an image' });
    await expect(dialog).toBeVisible();
    expect(stored).toHaveLength(0);

    await dialog.getByRole('button', { name: 'Accept' }).click();
    await expect(dialog).toHaveCount(0);
    expect(api.callsTo('POST', '/v1/inputs/terms')[0].body).toEqual({ version: 1 });

    await images(page).setInputFiles([png('a.png')]);
    await expect(allUploaded(page, 1)).toBeVisible();
    expect(stored).toEqual(['a.png']);
  });

  test('while image inputs are off for the account, a recipe with slots says it cannot be batched here', async ({ page, api }) => {
    // The API as it ships: inputs switched off.
    await openSlotBatch(page, api, makeUser());

    await expect(page.getByText("This recipe can't be run as a batch from this account right now.")).toBeVisible();
    await expect(page.getByLabel('Paste a table')).toHaveCount(0);
    await expect(startButton(page)).toHaveCount(0);
  });

  test('500 images are matched to 500 rows and uploaded without freezing the page', async ({ page, api }) => {
    test.setTimeout(90_000);
    await openSlotBatch(page, api);
    const stored = serveNamedUploads(api);
    const rows = ['color,image', ...Array.from({ length: 500 }, (_, i) => `color ${i + 1},product-${i + 1}.png`)].join('\n');
    await table(page).fill(rows);
    await expect(page.getByText('500 rows, 2 columns')).toBeVisible();

    const files = Array.from({ length: 500 }, (_, i) => png(`product-${500 - i}.png`));
    const chosen = Date.now();
    await images(page).setInputFiles(files);

    // Matched at once: every row has its image before the first upload has finished.
    await expect(slotBox(page)).toContainText('Every row has an image.');
    expect(Date.now() - chosen, 'choosing to matched, in ms').toBeLessThan(3_000);

    // While they upload, the page still answers: the table pages on a click.
    const paged = Date.now();
    await page.getByRole('button', { name: 'Next rows' }).click();
    await expect(page.getByText('Rows 26–50 of 500')).toBeVisible();
    expect(Date.now() - paged, 'next page while uploading, in ms').toBeLessThan(2_000);
    await expect(rowCells(page, 1).nth(2)).toContainText('product-26.png');

    await expect(allUploaded(page, 500)).toBeVisible({ timeout: 30_000 });
    expect(Date.now() - chosen, 'choosing to all uploaded, in ms').toBeLessThan(30_000);
    expect(stored).toHaveLength(500);
    await expect(startButton(page)).toHaveText('Start batch · $50.00');
    expect(filesAsked(api).slice(0, 3)).toEqual([idOf('product-1.png'), idOf('product-2.png'), idOf('product-3.png')]);
    expect(filesAsked(api)).toHaveLength(500);
  });
});
