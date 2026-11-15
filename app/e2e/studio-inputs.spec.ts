import { accepted, jobId, makeJob, MODELS, mockEstimates, outputsOf, serveJob, serveJobList, serveMedia } from './generation';
import { chooseFile, fileId, INPUT_MODELS, inputsUser, openInputsStudio, refuseUploads, serveUploads, uploadsTo } from './inputs';
import { expect, makeUser, test } from './mock-api';

const inputs = (page: import('@playwright/test').Page) => page.getByRole('group', { name: 'Image inputs' });

test.describe('inputs in the Studio, while they are switched off', () => {
  test('nothing about inputs is shown, and a request is what it always was', async ({ page, api }) => {
    // The API as it ships: inputs off, though the catalog says what each model would take.
    await api.signIn(makeUser());
    await page.clock.install();
    api.on('GET', '/v1/models', { json: INPUT_MODELS });
    mockEstimates(api);
    await serveMedia(page);
    const done = makeJob({ id: jobId(1), model: 'gpt-image-1.5', inputs: [] });
    api.on('POST', '/v1/images/generations', accepted(done.id));
    serveJob(api, done);
    await page.goto('/create/image?model=gpt-image-1.5');
    await expect(page.getByLabel('Model')).toHaveValue('gpt-image-1.5');

    await expect(inputs(page)).toHaveCount(0);
    await expect(page.getByText(/Input image|Mask|Choose file|From your images/)).toHaveCount(0);
    await expect(page.getByRole('group', { name: 'Recipe inputs' })).toHaveCount(0);

    await page.getByLabel('Prompt').fill('A lighthouse');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('img', { name: /Generated image/ })).toBeVisible();
    // The request has no input field, and the estimate was asked without an input count.
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual(
      { model: 'gpt-image-1.5', prompt: 'A lighthouse', size: '1024x1024', n: 1, async: true });
    expect(api.callsTo('POST', '/v1/images/edits')).toHaveLength(0);
    for (const call of api.callsTo('POST', '/v1/estimates')) expect(call.body).not.toHaveProperty('input_images');
    // Nor on the result: no way to animate it or describe it.
    await expect(page.getByRole('link', { name: /Animate/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Describe/ })).toHaveCount(0);
  });
});

test.describe('image inputs in the Studio', () => {
  test('the slots follow what the chosen model takes', async ({ page, api }) => {
    await openInputsStudio(page, api);

    // flux-2-pro, the cheapest: up to two images, no mask.
    await expect(page.getByLabel('Model')).toHaveValue('flux-2-pro');
    await expect(inputs(page).getByText('Input images')).toBeVisible();
    await expect(inputs(page).getByText('0 of up to 2')).toBeVisible();
    await expect(inputs(page).getByText('Mask', { exact: true })).toHaveCount(0);

    // gpt-image-1.5: up to four, and a mask.
    await page.getByLabel('Model').selectOption('gpt-image-1.5');
    await expect(inputs(page).getByText('0 of up to 4')).toBeVisible();
    await expect(inputs(page).getByText('Mask', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Choose a file for the mask')).toBeAttached();

    // sdxl takes none: no slots at all.
    await page.getByLabel('Model').selectOption('sdxl');
    await expect(inputs(page)).toHaveCount(0);
  });

  test('an upload shows as a thumbnail, can be removed, and the controls work from the keyboard', async ({ page, api }) => {
    await openInputsStudio(page, api);
    const stored = serveUploads(api);

    // The file control is a real input with a label: it takes the focus.
    await page.getByLabel('Choose a file for the image 1').focus();
    await expect(page.getByLabel('Choose a file for the image 1')).toBeFocused();
    await chooseFile(page, 'Choose a file for the image 1', 'mug.png');

    await expect(page.getByRole('img', { name: 'Image 1: mug.png' })).toBeVisible();
    await expect(inputs(page).getByText('1 of up to 2')).toBeVisible();
    expect(stored).toEqual([fileId(1)]);
    // It went up as a multipart form with the file and its purpose.
    const upload = api.callsTo('POST', '/v1/files')[0];
    expect(upload.headers['content-type']).toMatch(/^multipart\/form-data/);

    await chooseFile(page, 'Choose a file for the image 2', 'counter.png');
    await expect(page.getByRole('img', { name: 'Image 2: counter.png' })).toBeVisible();
    // Two is all this model takes: no control for a third.
    await expect(page.getByLabel(/Choose a file for the image 3/)).toHaveCount(0);

    await page.getByRole('button', { name: 'Remove Image 1' }).click();
    await expect(page.getByRole('img', { name: /mug\.png/ })).toHaveCount(0);
    await expect(inputs(page).getByText('1 of up to 2')).toBeVisible();
  });

  test('each refusal of an upload says what it means', async ({ page, api }) => {
    await openInputsStudio(page, api);
    const messages: [number, string, RegExp][] = [
      [422, 'input_person_not_allowed', /Images of people are not accepted yet\./],
      [415, 'input_type_unsupported', /not a PNG, JPEG or WebP still image/],
      [413, 'input_too_large', /too large\. An input can be up to 20 MB/],
      [503, 'input_check_unavailable', /could not be checked just now.*Try again/],
      [409, 'input_limit_reached', /as many stored images as an account can keep/],
      [403, 'inputs_not_allowed_for_account', /not available for this account/],
      [403, 'inputs_disabled', /not available right now/],
      [403, 'inputs_terms_required', /Accept the input terms/],
    ];
    for (const [status, code, message] of messages) {
      refuseUploads(api, status, code);
      await chooseFile(page, 'Choose a file for the image 1');
      await expect(inputs(page).getByRole('alert'), code).toHaveText(message);
      // Nothing was added, and the raw answer of the API is not what is shown.
      await expect(inputs(page).getByText('0 of up to 2')).toBeVisible();
      await expect(inputs(page).getByText(`refused: ${code}`)).toHaveCount(0);
    }
    // After a refusal the same control takes the same file again, and an accepted one clears the message.
    serveUploads(api);
    await chooseFile(page, 'Choose a file for the image 1');
    await expect(inputs(page).getByRole('alert')).toHaveCount(0);
    await expect(inputs(page).getByText('1 of up to 2')).toBeVisible();
  });

  test('the first attempt before the terms are accepted opens them, with Accept', async ({ page, api }) => {
    let accepted = false;
    const user = () => inputsUser(accepted ? {} : { accepted: false, accepted_at: null });
    await openInputsStudio(page, api, '/create/image', user());
    api.on('GET', '/v1/auth/me', () => ({ json: user() }));
    api.on('POST', '/v1/inputs/terms', () => { accepted = true; return { status: 204 }; });
    serveUploads(api);

    // No file dialog opens: the terms do.
    await inputs(page).getByText('Choose file').click();
    const dialog = page.getByRole('dialog', { name: 'Before you add an image' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/version 1/)).toBeVisible();
    await expect(dialog.getByRole('list', { name: 'What you confirm' })).toBeVisible();
    expect(uploadsTo(api)).toBe(0);

    // Picking one of your own images is an input too.
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await page.getByRole('button', { name: /Pick one of your images/ }).click();
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: 'Accept' }).click();
    await expect(dialog).toHaveCount(0);
    expect(api.callsTo('POST', '/v1/inputs/terms')[0].body).toEqual({ version: 1 });

    // Accepted: the same control now takes the file.
    await chooseFile(page, 'Choose a file for the image 1');
    await expect(page.getByRole('img', { name: 'Image 1: product.png' })).toBeVisible();
  });

  test('with inputs, an image request is an edit that names them, priced with them', async ({ page, api }) => {
    await openInputsStudio(page, api);
    serveUploads(api);
    const done = makeJob({ id: jobId(1), inputs: [{ role: 'image', file_id: fileId(1), url: null }] });
    api.on('POST', '/v1/images/edits', accepted(done.id));
    serveJob(api, done);

    await page.getByLabel('Prompt').fill('The mug on a marble counter');
    // Without an input: a generation at its own price.
    await expect(page.getByRole('button', { name: /^Generate/ })).toContainText('$0.04');

    await chooseFile(page, 'Choose a file for the image 1');
    await chooseFile(page, 'Choose a file for the image 2');
    // flux-2-pro charges for each input image, and the estimate was asked with their count.
    await expect(page.getByRole('button', { name: /^Generate/ })).toContainText('$0.16');
    expect(api.callsTo('POST', '/v1/estimates').at(-1)?.body).toEqual({ model: 'flux-2-pro', size: '1024x1024', input_images: 2 });

    // The code shown is the request that will be sent: the edits route, with the file ids.
    await page.getByRole('button', { name: /View code/ }).click();
    await expect(page.getByRole('figure', { name: 'Start the job' })).toContainText('/v1/images/edits');
    await expect(page.getByRole('figure', { name: 'Start the job' })).toContainText(fileId(2));

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('img', { name: /Generated image/ })).toBeVisible();
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
    expect(api.callsTo('POST', '/v1/images/edits')[0].body).toEqual({
      model: 'flux-2-pro', prompt: 'The mug on a marble counter', size: '1024x1024', async: true,
      images: [{ file_id: fileId(1) }, { file_id: fileId(2) }],
    });
    // No address was sent as an input anywhere.
    expect(JSON.stringify(api.callsTo('POST', '/v1/images/edits')[0].body)).not.toMatch(/https?:|blob:/);
  });

  test('a mask goes with the images, on a model that takes one', async ({ page, api }) => {
    await openInputsStudio(page, api, '/create/image?model=gpt-image-1.5');
    serveUploads(api);
    api.on('POST', '/v1/images/edits', accepted(jobId(1)));
    serveJob(api, makeJob({ id: jobId(1), model: 'gpt-image-1.5' }));

    await page.getByLabel('Prompt').fill('Replace the sky');
    await chooseFile(page, 'Choose a file for the image 1', 'photo.png');
    await chooseFile(page, 'Choose a file for the mask', 'mask.png');
    await expect(page.getByRole('img', { name: 'Mask: mask.png' })).toBeVisible();
    // A mask is not something to pick from your generations, or to describe.
    await expect(page.getByRole('button', { name: 'Pick one of your images for the mask' })).toHaveCount(0);

    // The mask does not count as an input image in the price.
    await expect.poll(() => api.callsTo('POST', '/v1/estimates').at(-1)?.body).toMatchObject({ input_images: 1 });

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/images/edits').length).toBe(1);
    expect(api.callsTo('POST', '/v1/images/edits')[0].body).toMatchObject({
      model: 'gpt-image-1.5', images: [{ file_id: fileId(1) }], mask: { file_id: fileId(2) },
    });
  });
});

test.describe('video inputs in the Studio', () => {
  test('the slots follow the model, and a slot takes one of your own images without an upload', async ({ page, api }) => {
    const still = makeJob({ id: jobId(7), prompt: 'A paper boat on a pond', outputs: outputsOf(2) });
    await openInputsStudio(page, api, '/create/video');
    serveJobList(api, [still, makeJob({ id: jobId(8), status: 'FAILED', outputs: [] })]);
    api.on('POST', '/v1/videos/generations', accepted(jobId(9)));
    serveJob(api, makeJob({ id: jobId(9), modality: 'VIDEO', model: 'kling-3.0', outputs: outputsOf(1, 'video') }));

    // kling-3.0, the cheapest: a first frame and a last frame, no reference images.
    await expect(page.getByLabel('Model')).toHaveValue('kling-3.0');
    await expect(inputs(page).getByText('First frame', { exact: true })).toBeVisible();
    await expect(inputs(page).getByText('Last frame', { exact: true })).toBeVisible();
    await expect(inputs(page).getByText('Reference images')).toHaveCount(0);
    // seedance-2.0 also follows reference images; sora-2 takes nothing.
    await page.getByLabel('Model').selectOption('seedance-2.0');
    await expect(inputs(page).getByText('0 of up to 3')).toBeVisible();
    await page.getByLabel('Model').selectOption('sora-2');
    await expect(inputs(page)).toHaveCount(0);
    await page.getByLabel('Model').selectOption('kling-3.0');

    // Pick the second image of a finished generation as the first frame.
    await page.getByRole('button', { name: 'Pick one of your images for the first frame' }).click();
    const picker = page.getByRole('dialog', { name: 'Use one of your images as the first frame' });
    await expect(picker.getByRole('list', { name: 'Your recent images' }).getByRole('button')).toHaveCount(2);
    expect(api.callsTo('GET', '/v1/jobs').at(-1)?.query.get('modality')).toBe('IMAGE');
    await picker.getByRole('button', { name: /Use A paper boat on a pond/ }).nth(1).click();
    await expect(picker).toHaveCount(0);
    await expect(page.getByRole('img', { name: 'First frame: One of your images' })).toBeVisible();
    expect(uploadsTo(api)).toBe(0);

    await page.getByLabel('Prompt').fill('The boat drifts away');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/videos/generations').length).toBe(1);
    expect(api.callsTo('POST', '/v1/videos/generations')[0].body).toEqual({
      model: 'kling-3.0', prompt: 'The boat drifts away', size: '1280x720', seconds: 5,
      first_frame: { job_output: { job_id: jobId(7), index: 1 } },
    });
  });

  test('a first frame, a last frame and reference images each go in their own field', async ({ page, api }) => {
    await openInputsStudio(page, api, '/create/video?model=seedance-2.0');
    serveUploads(api);
    api.on('POST', '/v1/videos/generations', accepted(jobId(9)));
    serveJob(api, makeJob({ id: jobId(9), modality: 'VIDEO', model: 'seedance-2.0', outputs: outputsOf(1, 'video') }));
    await page.getByLabel('Prompt').fill('Image 1 in the style of Image 2');

    await chooseFile(page, 'Choose a file for the reference image 1', 'subject.png');
    await chooseFile(page, 'Choose a file for the reference image 2', 'style.png');
    // A video's inputs cost nothing: the price is the clip's, and the estimate is asked without a count.
    await expect(page.getByRole('button', { name: /^Generate/ })).toContainText('$0.81');
    for (const call of api.callsTo('POST', '/v1/estimates')) expect(call.body).not.toHaveProperty('input_images');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/videos/generations').length).toBe(1);
    expect(api.callsTo('POST', '/v1/videos/generations')[0].body).toMatchObject({
      model: 'seedance-2.0', reference_images: [{ file_id: fileId(1) }, { file_id: fileId(2) }],
    });

    await page.getByRole('button', { name: 'Remove Reference image 1' }).click();
    await page.getByRole('button', { name: 'Remove Reference image 1' }).click();
    await chooseFile(page, 'Choose a file for the first frame', 'start.png');
    await chooseFile(page, 'Choose a file for the last frame', 'end.png');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect.poll(() => api.callsTo('POST', '/v1/videos/generations').length).toBe(2);
    const body = api.callsTo('POST', '/v1/videos/generations')[1].body as Record<string, unknown>;
    expect(body.first_frame).toEqual({ file_id: fileId(3) });
    expect(body.last_frame).toEqual({ file_id: fileId(4) });
    expect(body).not.toHaveProperty('reference_images');
  });
});

// MODELS is the catalog without inputs; it is used here only to say what a model costs.
void MODELS;
