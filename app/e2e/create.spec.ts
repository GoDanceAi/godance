import type { Page } from '@playwright/test';
import { API_ORIGIN } from './api-origin';
import {
  accepted,
  inProgress,
  jobId,
  makeJob,
  MODELS,
  mockEstimates,
  outputsOf,
  serveJob,
  serveMedia,
} from './generation';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

const PROMPT = 'A lighthouse on a sea cliff at dusk';

/** The Studio's catalog and prices, with the clock under the test's control. */
async function studioMocks(page: Page, api: MockApi) {
  await page.clock.install();
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
}

async function openStudio(page: Page, api: MockApi, path = '/create/image') {
  await api.signIn();
  await studioMocks(page, api);
  await page.goto(path);
  await expect(page.getByLabel('Model')).toBeEnabled();
}

/** The next poll falls due: the Studio reads a job every 2 s at first. */
async function nextPoll(page: Page) {
  await page.clock.fastForward(2_000);
}

const status = (page: Page) => page.getByRole('status');

test.describe('the Studio', () => {
  test('an image run: priced, submitted, queued, running, done', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(1);
    const done = makeJob({ id, prompt: PROMPT });
    api.on('POST', '/v1/images/generations', accepted(id));
    const job = serveJob(api, inProgress(done, 'QUEUED'));

    // The cheapest model, priced with the fee, under its provider.
    const picker = page.getByLabel('Model');
    await expect(picker).toHaveValue('flux-2-pro');
    await expect(picker.locator('optgroup[label="Black Forest Labs"] option'))
      .toHaveText(['FLUX.2 [pro] · $0.0336 per image']);
    await expect(picker.locator('optgroup[label="OpenAI"] option')).toHaveText(['GPT Image 1.5 · $0.0448 per image']);
    await expect(picker.locator('option', { hasText: 'gpt-4o-mini' })).toHaveCount(0);
    await expect(picker.locator('option', { hasText: 'godance-auto' })).toHaveCount(0);
    await expect(page.getByText('Photoreal detail and accurate text')).toBeVisible();

    // The exact price, from the estimate endpoint, on the button.
    const generate = page.getByRole('button', { name: 'Generate $0.04' });
    await expect(generate).toBeDisabled();
    expect(api.callsTo('POST', '/v1/estimates')[0].body).toEqual({ model: 'flux-2-pro', size: '1024x1024' });

    await page.getByLabel('Prompt').fill(PROMPT);
    await generate.click();

    await expect(status(page)).toHaveText('Queued. The model starts on it shortly.');
    await expect(page).toHaveURL(`/create/image?job=${id}`);
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({
      model: 'flux-2-pro', prompt: PROMPT, size: '1024x1024', async: true,
    });
    // The first reading waits for the first poll.
    expect(api.callsTo('GET', `/v1/jobs/${id}`)).toHaveLength(0);

    job.set(inProgress(done));
    await nextPoll(page);
    await expect(status(page)).toHaveText('Running.');
    job.set(done);
    await nextPoll(page);
    await expect(status(page)).toHaveText('Done: 1 image.');

    const image = page.getByRole('img', { name: `Generated image: ${PROMPT}` });
    await expect(image).toHaveAttribute('src', done.outputs![0].url);
    await expect(page.getByRole('link', { name: 'Download image' })).toHaveAttribute('href', done.outputs![0].url);
    await expect(page.getByRole('link', { name: 'Open in History' })).toHaveAttribute('href', `/history/${id}`);

    // Polling stops with the job.
    const reads = api.callsTo('GET', `/v1/jobs/${id}`).length;
    await page.clock.fastForward(10_000);
    await page.waitForTimeout(250);
    expect(api.callsTo('GET', `/v1/jobs/${id}`)).toHaveLength(reads);
  });

  test('two outputs for n: 2, and no count control when a model makes one', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(2);
    api.on('POST', '/v1/images/generations', accepted(id));
    serveJob(api, makeJob({ id, model: 'gpt-image-1.5', params: { size: '1536x1024', n: 2 }, outputs: outputsOf(2) }));

    // flux-2-pro publishes max_n 1.
    await expect(page.getByLabel('Number of images')).toHaveCount(0);

    await page.getByLabel('Model').selectOption('gpt-image-1.5');
    await expect(page.getByLabel('Number of images')).toHaveValue('1');
    await expect(page.getByLabel('Number of images').locator('option')).toHaveText(['1', '2', '3', '4']);
    await page.getByLabel('Size').selectOption('1536x1024');
    await page.getByLabel('Number of images').selectOption('2');

    const generate = page.getByRole('button', { name: 'Generate $0.10' });
    await expect(generate).toBeVisible();
    await page.getByLabel('Prompt').fill(PROMPT);
    await generate.click();
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({
      model: 'gpt-image-1.5', prompt: PROMPT, size: '1536x1024', n: 2, async: true,
    });

    await nextPoll(page);
    await expect(status(page)).toHaveText('Done: 2 images.');
    await expect(page.getByRole('img', { name: /^Generated image \d of 2/ })).toHaveCount(2);
    await expect(page.getByRole('link', { name: 'Download image 2 of 2' })).toHaveAttribute('href', outputsOf(2)![1].url);

    // Back to a one-at-a-time model: the control goes, n with it, and a size it lacks.
    await page.getByLabel('Model').selectOption('flux-2-pro');
    await expect(page.getByLabel('Number of images')).toHaveCount(0);
    await expect(page.getByLabel('Size')).toHaveValue('1024x1024');
    await expect.poll(() => api.callsTo('POST', '/v1/estimates').at(-1)?.body)
      .toEqual({ model: 'flux-2-pro', size: '1024x1024' });
  });

  test('a model with no published capabilities sends only the model and prompt', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(3);
    api.on('POST', '/v1/images/generations', accepted(id));
    serveJob(api, inProgress(makeJob({ id, model: 'sdxl' })));

    await page.getByLabel('Model').selectOption('sdxl');
    await expect(page.getByLabel('Size')).toHaveCount(0);
    await expect(page.getByLabel('Number of images')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Generate $0.06' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'sdxl' });

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: 'Generate $0.06' }).click();
    await expect(status(page)).toContainText('Queued');
    // No size, count or duration. async is how the Studio asks for a job.
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({ model: 'sdxl', prompt: PROMPT, async: true });
  });

  test('a video run with a duration control', async ({ page, api }) => {
    await openStudio(page, api, '/create/video');
    const id = jobId(4);
    const clip = outputsOf(1, 'video')!;
    const done = makeJob({
      id, modality: 'VIDEO', provider: 'KLING', model: 'kling-3.0', prompt: PROMPT,
      params: { size: '1280x720', seconds: 10 }, outputs: clip, outputUrl: clip[0].url,
    });
    api.on('POST', '/v1/videos/generations', accepted(id));
    const job = serveJob(api, inProgress(done, 'QUEUED'));

    await expect(page.getByLabel('Model')).toHaveValue('kling-3.0');
    await expect(page.getByLabel('Model').locator('option', { hasText: 'Kling 3.0' }))
      .toHaveText('Kling 3.0 · $0.0966 per second');
    await expect(page.getByLabel('Duration')).toHaveValue('5');
    await expect(page.getByLabel('Number of videos')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Generate $0.50' })).toBeVisible();

    await page.getByLabel('Duration').selectOption('10');
    await expect(page.getByRole('button', { name: 'Generate $1.00' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'kling-3.0', size: '1280x720', seconds: 10 });

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: 'Generate $1.00' }).click();
    expect(api.callsTo('POST', '/v1/videos/generations')[0].body).toEqual({
      model: 'kling-3.0', prompt: PROMPT, size: '1280x720', seconds: 10,
    });
    await expect(status(page)).toHaveText('Queued. The model starts on it shortly.');
    await expect(page.getByText('Videos usually take 1–5 minutes.')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Result' }).getByRole('link', { name: 'History', exact: true }))
      .toHaveAttribute('href', '/history');

    job.set(inProgress(done));
    await nextPoll(page);
    await expect(status(page)).toHaveText('Running.');
    job.set(done);
    await nextPoll(page);
    await expect(status(page)).toHaveText('Done: 1 video.');
    await expect(page.getByLabel(`Generated video: ${PROMPT}`)).toHaveAttribute('src', clip[0].url);
    await expect(page.getByText('Videos usually take 1–5 minutes.')).toHaveCount(0);
  });

  test('a failed job says the charge was returned, and the balance is read again', async ({ page, api }) => {
    const id = jobId(5);
    const failed = makeJob({
      id, status: 'FAILED', outputs: [], outputUrl: null, actualCostCents: null,
      errorMessage: 'The provider rejected the prompt.',
    });
    // The balance as the API reports it: the price is held while the job
    // runs, and returned when it fails.
    let held = false;
    await api.signIn();
    api.on('GET', '/v1/auth/me', () => ({ json: makeUser({ creditBalanceCents: held ? 11_34 : 12_34 }) }));
    await studioMocks(page, api);
    api.on('POST', '/v1/images/generations', () => { held = true; return accepted(id); });
    const job = serveJob(api, inProgress(failed, 'QUEUED'));

    await page.goto('/create/image');
    const balance = page.getByText(/^Balance \$/);
    await expect(balance).toHaveText('Balance $12.34');
    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(balance).toHaveText('Balance $11.34');

    held = false;
    job.set(failed);
    await nextPoll(page);
    await expect(status(page)).toContainText('Failed.');
    await expect(status(page)).toContainText('The provider rejected the prompt.');
    await expect(status(page)).toContainText('The charge was returned to your balance.');
    await expect(balance).toHaveText('Balance $12.34');
    await expect(page.getByRole('button', { name: 'Copy prompt' })).toBeVisible();
  });

  test('a 402 for too little credit points to the Billing page', async ({ page, api }) => {
    await openStudio(page, api);
    api.on('POST', '/v1/images/generations',
      problem(402, 'insufficient credit; top up via /v1/billing/topup', 'insufficient_credit'));

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();

    await expect(page.getByText('Not enough credit for this generation.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Top up on the Billing page' })).toHaveAttribute('href', '/billing');
    await expect(page.getByText('/v1/billing/topup')).toHaveCount(0);
    await expect(page).toHaveURL('/create/image');
    await expect(page.getByLabel('Prompt')).toHaveValue(PROMPT);
    await expect(status(page)).toHaveText('');
  });

  test('a spending-cap 402 shows the reason the API gives, with no Billing link', async ({ page, api }) => {
    await openStudio(page, api);
    api.on('POST', '/v1/images/generations',
      problem(402, 'daily spending cap reached (1000 of 1000 cents used) — resets at the start of the next period'));

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();

    await expect(page.getByText(/daily spending cap reached/)).toBeVisible();
    await expect(page.getByRole('link', { name: 'Top up on the Billing page' })).toHaveCount(0);
  });

  test('without an estimate the button shows no price and still works', async ({ page, api }) => {
    await openStudio(page, api);
    api.on('POST', '/v1/estimates', problem(503, 'Estimates are unavailable.'));
    api.on('POST', '/v1/images/generations', accepted(jobId(6)));
    serveJob(api, inProgress(makeJob({ id: jobId(6) }), 'QUEUED'));

    await page.getByLabel('Model').selectOption('gpt-image-1.5');
    await page.getByLabel('Prompt').fill(PROMPT);
    await expect.poll(() => api.callsTo('POST', '/v1/estimates').map((c) => (c.body as { model: string }).model))
      .toContain('gpt-image-1.5');
    await expect(page.getByRole('button', { name: /^Generate \$/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Generate', exact: true }).click();
    await expect(status(page)).toContainText('Queued');
  });
});

test.describe('the Studio from a link', () => {
  test('?job= fills the form from that job, and a reload keeps polling it', async ({ page, api }) => {
    const id = jobId(7);
    const done = makeJob({
      id, model: 'gpt-image-1.5', prompt: 'Two foxes in the snow', params: { size: '1536x1024', n: 2 }, outputs: outputsOf(2),
    });
    const job = serveJob(api, inProgress(done));
    await openStudio(page, api, `/create/image?job=${id}`);

    await expect(page.getByLabel('Prompt')).toHaveValue('Two foxes in the snow');
    await expect(page.getByLabel('Model')).toHaveValue('gpt-image-1.5');
    await expect(page.getByLabel('Size')).toHaveValue('1536x1024');
    await expect(page.getByLabel('Number of images')).toHaveValue('2');
    await expect(status(page)).toHaveText('Running.');

    await page.reload();
    await expect(status(page)).toHaveText('Running.');
    await expect(page.getByLabel('Prompt')).toHaveValue('Two foxes in the snow');
    job.set(done);
    await nextPoll(page);
    await expect(status(page)).toHaveText('Done: 2 images.');
    // Loading a job never runs one.
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('a run started here survives a reload: its job is in the URL', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(8);
    const done = makeJob({ id, prompt: PROMPT });
    api.on('POST', '/v1/images/generations', accepted(id));
    const job = serveJob(api, inProgress(done, 'QUEUED'));

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page).toHaveURL(`/create/image?job=${id}`);

    await page.reload();
    // Read once on load, then on the schedule.
    await expect(status(page)).toHaveText('Queued. The model starts on it shortly.');
    await expect(page.getByLabel('Prompt')).toHaveValue(PROMPT);
    job.set(inProgress(done));
    await nextPoll(page);
    await expect(status(page)).toHaveText('Running.');
    job.set(done);
    await nextPoll(page);
    await expect(status(page)).toHaveText('Done: 1 image.');
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(1);
  });

  test('a video job opened on the image page moves to the video page', async ({ page, api }) => {
    const id = jobId(9);
    const clip = outputsOf(1, 'video')!;
    serveJob(api, makeJob({
      id, modality: 'VIDEO', model: 'sora-2', params: { size: '1280x720', seconds: 8 }, outputs: clip, outputUrl: clip[0].url,
    }));
    await openStudio(page, api, `/create/image?job=${id}`);

    await expect(page).toHaveURL(`/create/video?job=${id}`);
    await expect(page.getByLabel('Model')).toHaveValue('sora-2');
    await expect(page.getByLabel('Duration')).toHaveValue('8');
    await expect(status(page)).toHaveText('Done: 1 video.');
  });

  test('a job that cannot be read leaves the form empty, with a note', async ({ page, api }) => {
    api.on('GET', `/v1/jobs/${jobId(10)}`, problem(404, 'job not found'));
    await openStudio(page, api, `/create/image?job=${jobId(10)}`);

    await expect(page.getByText("That generation couldn't be loaded, so the form starts empty.")).toBeVisible();
    await expect(page.getByLabel('Prompt')).toHaveValue('');
    await expect(page.getByLabel('Model')).toHaveValue('flux-2-pro');
  });

  test('?model=&prompt=&size=&n= fill the form, within what the model allows', async ({ page, api }) => {
    await openStudio(page, api, '/create?model=gpt-image-1.5&prompt=A%20red%20kite&size=1024x1536&n=3');

    await expect(page).toHaveURL(/\/create\/image\?/);
    await expect(page.getByLabel('Prompt')).toHaveValue('A red kite');
    await expect(page.getByLabel('Model')).toHaveValue('gpt-image-1.5');
    await expect(page.getByLabel('Size')).toHaveValue('1024x1536');
    await expect(page.getByLabel('Number of images')).toHaveValue('3');

    // Values the model doesn't offer give way to its defaults.
    await page.goto('/create/image?model=gpt-image-1.5&size=9999x9999&n=40');
    await expect(page.getByLabel('Model')).toBeEnabled();
    await expect(page.getByLabel('Size')).toHaveValue('1024x1024');
    await expect(page.getByLabel('Number of images')).toHaveValue('1');
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('an unknown model falls back to the cheapest and says so', async ({ page, api }) => {
    await openStudio(page, api, '/create/video?model=no-such-model&prompt=Waves&seconds=10');

    await expect(page.getByLabel('Model')).toHaveValue('kling-3.0');
    await expect(page.getByText('“no-such-model” isn\'t available, so Kling 3.0 is selected.')).toBeVisible();
    await expect(page.getByLabel('Prompt')).toHaveValue('Waves');
    await expect(page.getByLabel('Duration')).toHaveValue('10');
  });
});

test.describe('View code', () => {
  test('shows the curl for this request and for polling it, with a placeholder key', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(11);
    api.on('POST', '/v1/images/generations', accepted(id));
    serveJob(api, inProgress(makeJob({ id }), 'QUEUED'));

    await page.getByLabel('Model').selectOption('gpt-image-1.5');
    await page.getByLabel('Number of images').selectOption('2');
    await page.getByLabel('Prompt').fill("It's a lighthouse");
    await page.getByRole('button', { name: /View code/ }).click();

    const start = page.getByRole('figure', { name: 'Start the job' });
    await expect(start).toContainText(`curl ${API_ORIGIN}/v1/images/generations`);
    await expect(start).toContainText('-H "Authorization: Bearer $GODANCE_API_KEY"');
    // The apostrophe is quoted for the shell.
    await expect(start).toContainText(`"prompt": "It'\\''s a lighthouse"`);
    await expect(start).toContainText('"n": 2');
    await expect(start).toContainText('"async": true');
    const poll = page.getByRole('figure', { name: 'Check on it' });
    await expect(poll).toContainText(`curl ${API_ORIGIN}/v1/jobs/$JOB_ID`);
    await expect(page.locator('#studio-code').getByRole('link', { name: 'API keys' })).toHaveAttribute('href', '/keys');

    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(poll).toContainText(`curl ${API_ORIGIN}/v1/jobs/${id}`);
  });
});
