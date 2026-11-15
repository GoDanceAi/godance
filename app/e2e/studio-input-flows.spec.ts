import type { Page } from '@playwright/test';
import { API_ORIGIN } from './api-origin';
import { accepted, jobId, makeJob, mediaUrl, MEDIA_ORIGIN, outputsOf, serveJob, serveJobList } from './generation';
import { chooseFile, fileId, inputsUser, openInputsStudio, PNG, serveUploads, uploadsTo } from './inputs';
import { imageRecipe, IMAGE_SLUG, test } from './mock-gallery';
import { expect, makeUser, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, recipeId, serveRecipes, versionId } from './user-recipes';

const inputs = (page: Page) => page.getByRole('group', { name: 'Image inputs' });
const prompt = (page: Page) => page.getByRole('textbox', { name: 'Prompt', exact: true });

/** A finished image job with two outputs, and the list, the job and its page all served. */
function serveStill(api: MockApi) {
  const still = makeJob({ id: jobId(3), prompt: 'A paper boat on a pond', outputs: outputsOf(2) });
  serveJob(api, still);
  serveJobList(api, [still]);
  return still;
}

/** An image somewhere the app may fetch it from: served with the header a cross-origin fetch needs. */
async function serveFetchable(page: Page, url: string | RegExp, status = 200) {
  await page.route(url, (route) => route.fulfill(status === 200
    ? { status: 200, contentType: 'image/png', headers: { 'access-control-allow-origin': '*' }, body: PNG }
    : { status, contentType: 'text/plain', body: 'gone' }));
}

test.describe('Animate this', () => {
  for (const from of ['the Studio', 'History', 'the job page'] as const) {
    test(`from ${from}, opens the video Studio with that image as the first frame, and generates nothing`, async ({ page, api }) => {
      const still = serveStill(api);
      await openInputsStudio(page, api, from === 'the Studio' ? `/create/image?job=${still.id}` : '/create/image');
      if (from === 'the Studio') {
        await page.getByRole('link', { name: 'Animate image 2 of 2' }).click();
      } else if (from === 'History') {
        await page.goto('/history');
        await page.getByRole('link', { name: 'Animate this: A paper boat on a pond' }).click();
      } else {
        await page.goto(`/history/${still.id}`);
        await page.getByRole('link', { name: 'Animate image 2 of 2' }).click();
      }

      // The video Studio, with the image in the first frame slot, by its job and index: no upload.
      const index = from === 'History' ? 0 : 1;
      await expect(page).toHaveURL(new RegExp(`/create/video\\?animate=${still.id}&output=${index}$`));
      await expect(page.getByRole('img', { name: 'First frame: One of your images' })).toBeVisible();
      // The cheapest model that starts from a frame, and a line on why a still comes first.
      await expect(page.getByLabel('Model')).toHaveValue('kling-3.0');
      await expect(page.getByText('Settling the look in a still first costs cents.')).toBeVisible();
      expect(uploadsTo(api)).toBe(0);
      // Loading never generates.
      expect(api.callsTo('POST', '/v1/videos/generations')).toHaveLength(0);
      expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);

      // An explicit submit does, with the frame named by its job.
      api.on('POST', '/v1/videos/generations', accepted(jobId(9)));
      serveJob(api, makeJob({ id: jobId(9), modality: 'VIDEO', model: 'kling-3.0', outputs: outputsOf(1, 'video') }));
      await prompt(page).fill('The boat drifts away');
      await page.getByRole('button', { name: /^Generate/ }).click();
      await expect.poll(() => api.callsTo('POST', '/v1/videos/generations').length).toBe(1);
      expect(api.callsTo('POST', '/v1/videos/generations')[0].body).toMatchObject({
        model: 'kling-3.0', first_frame: { job_output: { job_id: still.id, index } },
      });
    });
  }

  test('keeps a model the link names when it starts from a frame, and says so when the image is gone', async ({ page, api }) => {
    const still = serveStill(api);
    await openInputsStudio(page, api, `/create/video?animate=${still.id}&output=0&model=seedance-2.0`);
    await expect(page.getByLabel('Model')).toHaveValue('seedance-2.0');
    await expect(page.getByRole('img', { name: 'First frame: One of your images' })).toBeVisible();

    // A model that takes no frame is not kept: the cheapest that does is chosen.
    await page.goto(`/create/video?animate=${still.id}&output=0&model=sora-2`);
    await expect(page.getByLabel('Model')).toHaveValue('kling-3.0');

    // An output that is not there: the form starts empty, and says why.
    await page.goto(`/create/video?animate=${still.id}&output=7`);
    await expect(page.getByText("That image couldn't be loaded as a first frame, so the form starts empty.")).toBeVisible();
    await expect(page.getByRole('img', { name: /First frame/ })).toHaveCount(0);
    // Asked for on the image page, it moves to the video page.
    await page.goto(`/create/image?animate=${still.id}&output=1`);
    await expect(page).toHaveURL(/\/create\/video\?animate=/);
  });
});

test.describe('Describe as prompt', () => {
  test('says what it costs first, asks only when told to, and replaces the prompt only on confirmation', async ({ page, api }) => {
    await openInputsStudio(page, api);
    serveUploads(api);
    api.on('POST', '/v1/chat/completions', {
      json: { choices: [{ message: { role: 'assistant', content: 'A red ceramic mug on a wooden shelf, soft light.' } }] },
    });
    await prompt(page).fill('My own prompt');
    await chooseFile(page, 'Choose a file for the image 1', 'mug.png');

    await page.getByRole('button', { name: 'Describe as prompt for Image 1' }).click();
    const dialog = page.getByRole('dialog', { name: 'Describe as prompt' });
    // The model and the cost, before anything is sent.
    await expect(dialog.getByText(/gpt-4o-mini looks at the image/)).toBeVisible();
    await expect(dialog.getByText(/normal chat call, charged as one: about/)).toBeVisible();
    await expect(dialog.locator('[data-describe-cost]')).toHaveText('$0.01');
    expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(0);

    await dialog.getByRole('button', { name: 'Describe the image' }).click();
    await expect(dialog.locator('[data-describe-answer]')).toHaveText('A red ceramic mug on a wooden shelf, soft light.');
    // The image went as the user's own file, by its content address on the API: never another address.
    const sent = api.callsTo('POST', '/v1/chat/completions')[0].body as { model: string; messages: { content: { type: string; text?: string; image_url?: { url: string } }[] }[] };
    expect(sent.model).toBe('gpt-4o-mini');
    expect(sent.messages[0].content[1]).toEqual({ type: 'image_url', image_url: { url: `${API_ORIGIN}/v1/files/${fileId(1)}/content` } });
    expect(sent.messages[0].content[0].text).toMatch(/^Describe this image as a prompt/);
    expect(sent.messages[0].content[0].text).not.toContain('My own prompt');

    // Nothing changed yet. Keeping the prompt leaves it.
    await expect(prompt(page)).toHaveValue('My own prompt');
    await dialog.getByRole('button', { name: 'Keep my prompt' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(prompt(page)).toHaveValue('My own prompt');

    // Asked again, and confirmed this time.
    await page.getByRole('button', { name: 'Describe as prompt for Image 1' }).click();
    await dialog.getByRole('button', { name: 'Describe the image' }).click();
    await dialog.getByRole('button', { name: 'Replace the prompt with this' }).click();
    await expect(prompt(page)).toHaveValue('A red ceramic mug on a wooden shelf, soft light.');
  });

  test('on a result, stores the image as a file first; with no model that sees, says so', async ({ page, api }) => {
    const still = makeJob({ id: jobId(3), prompt: 'A paper boat on a pond' });
    await openInputsStudio(page, api);
    serveJob(api, still);
    const stored = serveUploads(api);
    await serveFetchable(page, `${MEDIA_ORIGIN}/outputs/out-1.png**`);
    api.on('POST', '/v1/chat/completions', { json: { choices: [{ message: { content: 'A paper boat on a still pond.' } }] } });
    await page.goto(`/create/image?job=${still.id}`);

    await page.getByRole('button', { name: 'Describe as prompt for image' }).click();
    const dialog = page.getByRole('dialog', { name: 'Describe as prompt' });
    await dialog.getByRole('button', { name: 'Describe the image' }).click();
    await expect(dialog.locator('[data-describe-answer]')).toHaveText('A paper boat on a still pond.');
    expect(stored).toEqual([fileId(1)]);
    const sent = api.callsTo('POST', '/v1/chat/completions')[0].body as { messages: { content: { image_url?: { url: string } }[] }[] };
    expect(sent.messages[0].content[1].image_url?.url).toBe(`${API_ORIGIN}/v1/files/${fileId(1)}/content`);
    await dialog.getByRole('button', { name: 'Replace the prompt with this' }).click();
    await expect(prompt(page)).toHaveValue('A paper boat on a still pond.');

    // A refusal says what happened, in its own words, and changes nothing.
    api.on('POST', '/v1/chat/completions', { status: 402, contentType: 'application/problem+json',
      json: { status: 402, detail: 'insufficient credit', code: 'insufficient_credit' } });
    await page.getByRole('button', { name: 'Describe as prompt for image' }).click();
    await dialog.getByRole('button', { name: 'Describe the image' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('There is not enough credit for this. Add credit and try again.');
  });
});

test.describe('recipes with input slots', () => {
  test('saving the form as a recipe keeps each input by name, role and hint, and never the image', async ({ page, api }) => {
    await openInputsStudio(page, api);
    serveUploads(api);
    const recipes: ReturnType<typeof makeRecipe>[] = [];
    serveRecipes(api, recipes);
    await prompt(page).fill('The product on a marble counter');
    await chooseFile(page, 'Choose a file for the image 1', 'mug.png');

    // Each input has a name and a hint to edit before the recipe is saved.
    const editor = page.getByRole('group', { name: 'Recipe inputs' });
    await expect(editor.getByLabel('Name of the image')).toHaveValue('image');
    await editor.getByLabel('Name of the image').fill('product photo');
    await editor.getByLabel('Hint: what to put here').fill('One product on a plain background');

    await page.getByRole('button', { name: 'Save as recipe' }).click();
    await page.getByRole('button', { name: 'Create recipe' }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/recipes').length).toBe(1);
    const saved = api.callsTo('POST', '/v1/recipes')[0].body as Record<string, unknown>;
    expect(saved.inputs).toEqual([{ slot: 'product photo', role: 'image', hint: 'One product on a plain background' }]);
    // The file itself is not part of the recipe.
    expect(JSON.stringify(saved)).not.toContain(fileId(1));
  });

  test('a recipe with slots opens with them empty and their hints, and runs with an input for each', async ({ page, api }) => {
    const version = makeVersion({
      template: 'The product on a marble counter', variables: [], model: 'gpt-image-1.5', params: { size: '1024x1024' },
      inputs: [{ slot: 'product photo', role: 'image', hint: 'One product on a plain background' }],
    });
    const recipe = makeRecipe({ latest_version: version });
    serveRecipes(api, [recipe]);
    await openInputsStudio(page, api, `/create/image?recipe=${recipe.id}`);
    serveUploads(api);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, makeJob({ id: jobId(1), model: 'gpt-image-1.5' }));

    // The slot is there by its name, with its hint, and no image: a recipe never keeps one.
    const slot = inputs(page).locator('[data-input-slot="product photo"]');
    await expect(slot).toContainText('product photo');
    await expect(slot).toContainText('One product on a plain background');
    await expect(slot.getByRole('img')).toHaveCount(0);
    // It cannot run until the slot is filled, and says which.
    await expect(page.getByText('This recipe needs an image for “product photo” before it can run.')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Generate/ })).toBeDisabled();

    await chooseFile(page, 'Choose a file for the product photo', 'mug.png');
    await expect(slot.getByRole('img', { name: 'product photo: mug.png' })).toBeVisible();
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/images/generations').length).toBe(1);
    // The recipe runs by reference, with the input under its slot's name: the API places it.
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({
      model: 'gpt-image-1.5', size: '1024x1024', n: 1, async: true,
      recipe: { version_id: version.id, variables: {}, inputs: { 'product photo': { file_id: fileId(1) } } },
    });
    expect(api.callsTo('POST', '/v1/images/edits')).toHaveLength(0);

    // The recipe's page lists the slot.
    await page.goto(`/recipes/${recipe.id}`);
    const listed = page.getByRole('list', { name: 'Inputs of this version' });
    await expect(listed).toContainText('product photo');
    await expect(listed).toContainText('Image');
    await expect(listed).toContainText('One product on a plain background');
  });
});

test.describe('a gallery recipe with inputs', () => {
  const SAMPLE = 'https://media.godance.test/gallery/paper/input-0.webp';
  const withSlot = () => imageRecipe({
    run: { model: 'gpt-image-1.5', prompt_lang: 'en', params: { size: '1024x1024', n: 1 } },
    inputs: [{ slot: 'product photo', role: 'image', hint: 'One product on a plain background', sample_url: SAMPLE }],
  });

  test('shows its sample in the slot, and uploads it as the user\'s own input on submit', async ({ page, api, gallery }) => {
    gallery.serveRecipe(withSlot());
    await serveFetchable(page, SAMPLE);
    await openInputsStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);
    const stored = serveUploads(api);
    api.on('POST', '/v1/images/edits', accepted(jobId(1)));
    serveJob(api, makeJob({ id: jobId(1), model: 'gpt-image-1.5' }));

    const slot = inputs(page).locator('[data-input-slot="product photo"]');
    await expect(slot.getByText('Sample', { exact: true })).toBeVisible();
    await expect(slot).toContainText('One product on a plain background');
    await expect(slot.getByRole('button', { name: 'Remove product photo' })).toHaveText('Replace');
    // Loading the recipe uploads nothing and generates nothing.
    expect(stored).toEqual([]);
    expect(api.callsTo('POST', '/v1/images/edits')).toHaveLength(0);

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/images/edits').length).toBe(1);
    // The sample became one of the user's files, and that file is what the request names.
    expect(stored).toEqual([fileId(1)]);
    expect(api.callsTo('POST', '/v1/images/edits')[0].body).toMatchObject({
      model: 'gpt-image-1.5', images: [{ file_id: fileId(1) }], gallery_recipe: IMAGE_SLUG,
    });
    expect(JSON.stringify(api.callsTo('POST', '/v1/images/edits')[0].body)).not.toContain('media.godance.test');
  });

  test('the sample can be replaced by the user\'s own image, which is then what is sent', async ({ page, api, gallery }) => {
    gallery.serveRecipe(withSlot());
    await serveFetchable(page, SAMPLE);
    await openInputsStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);
    serveUploads(api);
    api.on('POST', '/v1/images/edits', accepted(jobId(1)));
    serveJob(api, makeJob({ id: jobId(1), model: 'gpt-image-1.5' }));

    const slot = inputs(page).locator('[data-input-slot="product photo"]');
    await slot.getByRole('button', { name: 'Remove product photo' }).click();
    // The slot stays, empty, with its hint.
    await expect(slot.getByRole('img')).toHaveCount(0);
    await expect(slot).toContainText('One product on a plain background');
    await chooseFile(page, 'Choose a file for the product photo', 'my-mug.png');
    await expect(slot.getByText('Sample', { exact: true })).toHaveCount(0);

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/images/edits').length).toBe(1);
    expect(uploadsTo(api)).toBe(1);
  });

  test('a sample that cannot be fetched leaves the slot empty, with the hint', async ({ page, api, gallery }) => {
    gallery.serveRecipe(withSlot());
    await serveFetchable(page, SAMPLE, 404);
    await openInputsStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    const slot = inputs(page).locator('[data-input-slot="product photo"]');
    await expect(slot).toContainText('One product on a plain background');
    await expect(slot.getByRole('img')).toHaveCount(0);
    await expect(slot.getByText('Sample', { exact: true })).toHaveCount(0);
    await expect(page.getByLabel('Choose a file for the product photo')).toBeAttached();
  });

  test('before the terms are accepted, a submit that would upload the sample opens them instead', async ({ page, api, gallery }) => {
    gallery.serveRecipe(withSlot());
    await serveFetchable(page, SAMPLE);
    await openInputsStudio(page, api, `/create/image?example=${IMAGE_SLUG}`, inputsUser({ accepted: false, accepted_at: null }));

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('dialog', { name: 'Before you use an image' })).toBeVisible();
    expect(uploadsTo(api)).toBe(0);
    expect(api.callsTo('POST', '/v1/images/edits')).toHaveLength(0);
  });
});

test.describe('a job\'s inputs', () => {
  const withInputs = () => makeJob({
    id: jobId(5), prompt: 'The mug on a marble counter',
    inputs: [
      { role: 'image', file_id: fileId(1), url: mediaUrl('input-1.png') },
      { role: 'mask', file_id: fileId(2), url: null },
    ],
  });

  test('are shown beside its outputs on the job page, and one whose file is gone says so', async ({ page, api }) => {
    const job = withInputs();
    await openInputsStudio(page, api);
    serveJob(api, job);
    await page.goto(`/history/${job.id}`);

    const list = page.getByRole('list', { name: 'Inputs' });
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await expect(list.getByRole('img', { name: 'Input image' })).toBeVisible();
    await expect(list.getByRole('link', { name: 'Open the input image in full' })).toHaveAttribute('href', mediaUrl('input-1.png'));
    await expect(list.getByText('Deleted')).toBeVisible();
    await expect(list.getByText('Mask')).toBeVisible();
  });

  test('go with it when it is run again, to the edits route, priced with them', async ({ page, api }) => {
    const job = withInputs();
    await openInputsStudio(page, api);
    serveJob(api, job);
    api.on('POST', '/v1/images/edits', accepted(jobId(8)));
    serveJob(api, makeJob({ id: jobId(8) }));
    await page.goto(`/history/${job.id}`);

    // flux-2-pro charges for its one input image; the mask is not counted.
    await expect(page.getByRole('button', { name: /^Run again/ })).toContainText('$0.10');
    await page.getByRole('button', { name: /^Run again/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/images/edits').length).toBe(1);
    expect(api.callsTo('POST', '/v1/images/edits')[0].body).toEqual({
      model: 'flux-2-pro', prompt: 'The mug on a marble counter', size: '1024x1024', async: true,
      images: [{ file_id: fileId(1) }], mask: { file_id: fileId(2) },
    });
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('are shown in History, in compare and in the Studio\'s result', async ({ page, api }) => {
    const job = withInputs();
    const other = makeJob({ id: jobId(6), prompt: 'A lighthouse' });
    await openInputsStudio(page, api);
    serveJob(api, job);
    serveJob(api, other);
    serveJobList(api, [job, other]);

    await page.goto('/history');
    await expect(page.getByRole('list', { name: 'Inputs' })).toHaveCount(1);
    await expect(page.getByRole('list', { name: 'Inputs' }).getByRole('listitem')).toHaveCount(2);

    await page.goto(`/compare?jobs=${job.id},${other.id}`);
    await expect(page.getByRole('article', { name: 'Generation 1' }).getByRole('list', { name: 'Inputs' })).toBeVisible();
    await expect(page.getByRole('article', { name: 'Generation 2' }).getByRole('list', { name: 'Inputs' })).toHaveCount(0);

    await page.goto(`/create/image?job=${job.id}`);
    await expect(page.getByRole('region', { name: 'Result' }).getByRole('list', { name: 'Inputs' })).toBeVisible();
  });

  test('with inputs switched off, a job that has them still shows them, and nothing offers to animate', async ({ page, api }) => {
    const job = withInputs();
    await openInputsStudio(page, api, '/create/image', makeUser());
    serveJob(api, job);
    serveJobList(api, [job]);

    await page.goto(`/history/${job.id}`);
    await expect(page.getByRole('list', { name: 'Inputs' })).toBeVisible();
    await expect(page.getByRole('link', { name: /Animate/ })).toHaveCount(0);
    await page.goto('/history');
    await expect(page.getByRole('link', { name: /Animate/ })).toHaveCount(0);
  });
});

void recipeId;
void versionId;
