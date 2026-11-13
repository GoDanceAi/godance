import type { Page } from '@playwright/test';
import type { JobView } from '../src/lib/api';
import {
  accepted,
  inProgress,
  jobId,
  makeJob,
  MODELS,
  mockEstimates,
  outputsOf,
  serveJob,
  serveJobList,
  serveMedia,
} from './generation';
import { expect, test, type MockApi } from './mock-api';

// Five minutes after the fixtures' jobs were made.
const NOW = new Date('2026-11-13T12:05:00Z');

/** n jobs, newest first, a minute apart; every third one a video. */
function manyJobs(n: number): JobView[] {
  return Array.from({ length: n }, (_, i) => {
    const video = i % 3 === 2;
    const clip = outputsOf(1, 'video')!;
    return makeJob({
      id: jobId(100 + i),
      prompt: `Prompt number ${i + 1}`,
      createdAt: new Date(NOW.getTime() - (i + 5) * 60_000).toISOString(),
      ...(video ? { modality: 'VIDEO', model: 'kling-3.0', outputs: clip, outputUrl: clip[0].url } : {}),
    });
  });
}

async function signedIn(page: Page, api: MockApi) {
  await page.clock.install({ time: NOW });
  await api.signIn();
  await serveMedia(page);
}

const cards = (page: Page) => page.getByRole('main').getByRole('listitem');

test.describe('History', () => {
  test('pages 24 at a time, newest first, with Load more', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(30);
    serveJobList(api, jobs);

    await page.goto('/history');
    await expect(cards(page)).toHaveCount(24);
    await expect(cards(page).first()).toContainText('Prompt number 1');
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs')[0].query)).toEqual({ limit: '24' });

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards(page)).toHaveCount(30);
    await expect(cards(page).last()).toContainText('Prompt number 30');
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs')[1].query)).toEqual({ limit: '24', before: jobs[23].id });
    // A short page was the last one.
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
  });

  test('filters by modality, and keeps the filter in the URL', async ({ page, api }) => {
    await signedIn(page, api);
    serveJobList(api, manyJobs(9));

    await page.goto('/history');
    await expect(cards(page)).toHaveCount(9);
    await expect(page.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'Videos', exact: true }).click();
    await expect(cards(page)).toHaveCount(3);
    await expect(page).toHaveURL('/history?type=video');
    await expect(page.getByRole('button', { name: 'Videos', exact: true })).toHaveAttribute('aria-pressed', 'true');
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs').at(-1)!.query)).toEqual({ limit: '24', modality: 'VIDEO' });

    await page.reload();
    await expect(cards(page)).toHaveCount(3);

    await page.getByRole('button', { name: 'Images', exact: true }).click();
    await expect(cards(page)).toHaveCount(6);
    expect(api.callsTo('GET', '/v1/jobs').at(-1)!.query.get('modality')).toBe('IMAGE');

    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(cards(page)).toHaveCount(9);
    await expect(page).toHaveURL('/history');
  });

  test('a card shows the first output, prompt, model, cost, age and status', async ({ page, api }) => {
    await signedIn(page, api);
    const clip = outputsOf(1, 'video')!;
    const [image, video] = [
      makeJob({ id: jobId(1), outputs: outputsOf(2), actualCostCents: 9, model: 'gpt-image-1.5' }),
      makeJob({
        id: jobId(2), modality: 'VIDEO', model: 'kling-3.0', prompt: 'Waves at night', outputs: clip, outputUrl: clip[0].url,
        createdAt: '2026-11-12T12:00:00Z',
      }),
    ];
    serveJobList(api, [image, video]);

    await page.goto('/history');
    const first = cards(page).nth(0);
    await expect(first.getByRole('link', { name: image.prompt! })).toHaveAttribute('href', `/history/${image.id}`);
    await expect(first.locator('img')).toHaveAttribute('src', outputsOf(2)![0].url);
    await expect(first.locator('img')).toHaveAttribute('loading', 'lazy');
    await expect(first).toContainText('gpt-image-1.5');
    await expect(first).toContainText('$0.09');
    await expect(first).toContainText('5 minutes ago');
    await expect(first).toContainText('Done');

    const second = cards(page).nth(1);
    // A frame from the video's metadata stands in for a thumbnail.
    await expect(second.locator('video')).toHaveAttribute('preload', 'metadata');
    await expect(second.locator('video')).toHaveAttribute('src', `${clip[0].url}#t=0.1`);
    await expect(second).toContainText('yesterday');
  });

  test('a running job keeps polling until it is done', async ({ page, api }) => {
    await signedIn(page, api);
    const done = makeJob({ id: jobId(3), prompt: 'Still cooking' });
    serveJobList(api, [inProgress(done)]);
    const job = serveJob(api, inProgress(done));

    await page.goto('/history');
    const card = cards(page).first();
    await expect(card).toContainText('Running');
    await expect(card).toContainText('In progress…');
    await expect(card).toContainText('up to $0.04');

    job.set(done);
    await page.clock.fastForward(2_000);
    await expect(card).toContainText('Done');
    await expect(card.locator('img')).toHaveAttribute('src', done.outputs![0].url);
    await expect(card).toContainText('$0.04');
    await expect(card).not.toContainText('up to');
  });

  test('the empty state links to the Studio', async ({ page, api }) => {
    await signedIn(page, api);
    serveJobList(api, []);

    await page.goto('/history');
    await expect(page.getByText('No generations yet.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Create an image' })).toHaveAttribute('href', '/create/image');
    await expect(page.getByRole('link', { name: 'Create a video' })).toHaveAttribute('href', '/create/video');
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Videos', exact: true }).click();
    await expect(page.getByText('No videos yet.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Create a video' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Create an image' })).toHaveCount(0);
  });

  test('a purged job shows its files were deleted, with the cost and date kept', async ({ page, api }) => {
    await signedIn(page, api);
    const purged = makeJob({
      id: jobId(4), prompt: null, params: {}, outputs: [], outputUrl: null, purged: true, mediaExpiresAt: null,
      createdAt: '2026-08-01T12:00:00Z',
    });
    serveJobList(api, [purged]);
    serveJob(api, purged);

    await page.goto('/history');
    const card = cards(page).first();
    await expect(card).toContainText('Files deleted');
    await expect(card).toContainText('$0.04');
    await expect(card).toContainText('Aug 1, 2026');

    await card.getByRole('link', { name: 'Prompt deleted with the files' }).click();
    await expect(page).toHaveURL(`/history/${purged.id}`);
    await expect(page.getByText('The files for this generation were deleted at the end of the retention period.')).toBeVisible();
    await expect(page.getByText('The prompt was deleted with the files. The cost and date are kept.')).toBeVisible();
    await expect(page.getByRole('definition').filter({ hasText: '$0.04' })).toBeVisible();
    await expect(page.getByText('Aug 1, 2026').first()).toBeVisible();
    for (const name of ['Copy prompt', 'Run again', 'Open in Studio', 'Download']) {
      await expect(page.getByRole('main').getByText(name, { exact: true })).toHaveCount(0);
    }
  });

  test('without outputs (an API from before them), the outputUrl is shown', async ({ page, api }) => {
    await signedIn(page, api);
    const legacy = makeJob({ id: jobId(5), outputUrl: outputsOf(1)![0].url });
    delete legacy.outputs;
    delete legacy.prompt;
    delete legacy.params;
    delete legacy.purged;
    delete legacy.mediaExpiresAt;
    serveJobList(api, [legacy]);
    serveJob(api, legacy);

    await page.goto('/history');
    await expect(cards(page).first().locator('img')).toHaveAttribute('src', legacy.outputUrl!);

    await page.goto(`/history/${legacy.id}`);
    await expect(page.getByRole('img', { name: 'Generated image' })).toHaveAttribute('src', legacy.outputUrl!);
    await expect(page.getByRole('link', { name: 'Download image' })).toHaveAttribute('href', legacy.outputUrl!);
    await expect(page.getByText(/will be deleted on/)).toHaveCount(0);
  });
});

test.describe('the job page', () => {
  test('shows every output, the prompt and the details, and runs it again', async ({ page, api, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await signedIn(page, api);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    const job = makeJob({
      id: jobId(6), model: 'gpt-image-1.5', prompt: 'Two foxes in the snow\nwatercolor', actualCostCents: 9,
      params: { size: '1536x1024', n: 2, quality: 'high' }, outputs: outputsOf(2),
    });
    serveJob(api, job);
    const again = jobId(7);
    api.on('POST', '/v1/images/generations', accepted(again));
    serveJob(api, inProgress({ ...job, id: again }, 'QUEUED'));

    await page.goto(`/history/${job.id}`);
    await expect(page.getByRole('img', { name: /^Generated image \d of 2/ })).toHaveCount(2);
    await expect(page.getByRole('link', { name: 'Download image 1 of 2' })).toHaveAttribute('href', outputsOf(2)![0].url);
    await expect(page.getByRole('link', { name: 'Download image 2 of 2' })).toHaveAttribute('href', outputsOf(2)![1].url);
    await expect(page.getByText('Two foxes in the snow watercolor')).toBeVisible();
    for (const [term, value] of [
      ['Model', 'gpt-image-1.5'], ['Size', '1536x1024'], ['Count', '2'], ['Quality', 'high'],
      ['Cost', '$0.09'], ['Created', 'Nov 13, 2026'], ['Finished', 'Nov 13, 2026'],
    ]) {
      await expect(page.getByRole('term').filter({ hasText: term })).toBeVisible();
      await expect(page.getByRole('definition').filter({ hasText: value }).first()).toBeVisible();
    }
    await expect(page.getByText('These files will be deleted on Feb 11, 2027.')).toBeVisible();

    await page.getByRole('button', { name: 'Copy prompt' }).click();
    await expect(page.getByRole('button', { name: 'Copied' })).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('Two foxes in the snow\nwatercolor');

    await expect(page.getByRole('link', { name: 'Open in Studio' })).toHaveAttribute('href', `/create/image?job=${job.id}`);

    // Run again: the same request, priced first, then watched in the Studio.
    const runAgain = page.getByRole('button', { name: 'Run again $0.10' });
    await expect(runAgain).toBeVisible();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'gpt-image-1.5', n: 2, size: '1536x1024' });
    await runAgain.click();
    await expect(page).toHaveURL(`/create/image?job=${again}`);
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({
      model: 'gpt-image-1.5', prompt: 'Two foxes in the snow\nwatercolor', size: '1536x1024', n: 2, quality: 'high', async: true,
    });
    await expect(page.getByRole('status')).toHaveText('Queued. The model starts on it shortly.');
  });

  test('Run again asks for no more than the model makes per request', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    // Made before the API checked counts: two images from a model that makes one.
    const flux = makeJob({ id: jobId(10), params: { size: '1024x1024', n: 2 }, outputs: outputsOf(2), actualCostCents: 8 });
    // Six from a model that makes up to four.
    const gpt = makeJob({
      id: jobId(11), model: 'gpt-image-1.5', params: { size: '1024x1024', n: 6 }, outputs: outputsOf(6), actualCostCents: 30,
    });
    serveJob(api, flux);
    serveJob(api, gpt);
    const again = jobId(12);
    api.on('POST', '/v1/images/generations', accepted(again));
    serveJob(api, inProgress({ ...flux, id: again, params: { size: '1024x1024' } }, 'QUEUED'));

    await page.goto(`/history/${gpt.id}`);
    await expect(page.getByRole('button', { name: 'Run again $0.20' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'gpt-image-1.5', n: 4, size: '1024x1024' });
    await expect(page.getByText('GPT Image 1.5 makes up to 4 images per request, so Run again asks for 4.')).toBeVisible();

    await page.goto(`/history/${flux.id}`);
    await expect(page.getByText('FLUX.2 [pro] makes one image per request, so Run again asks for one.')).toBeVisible();
    const runAgain = page.getByRole('button', { name: 'Run again $0.04' });
    await expect(runAgain).toBeVisible();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'flux-2-pro', size: '1024x1024' });
    await runAgain.click();
    await expect(page).toHaveURL(`/create/image?job=${again}`);
    expect(api.callsTo('POST', '/v1/images/generations')[0].body).toEqual({
      model: 'flux-2-pro', prompt: flux.prompt, size: '1024x1024', async: true,
    });
  });

  test('a model that is no longer offered has no Run again, and opens in the Studio', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', '/v1/models', { json: MODELS });
    const retired = makeJob({ id: jobId(13), model: 'flux-1-dev', params: { size: '1024x1024', n: 2 } });
    serveJob(api, retired);

    await page.goto(`/history/${retired.id}`);
    await expect(page.getByText("flux-1-dev isn't available any more. Open it in the Studio to run the prompt on another model.")).toBeVisible();
    await expect(page.getByRole('button', { name: /^Run again/ })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Open in Studio' })).toHaveAttribute('href', `/create/image?job=${retired.id}`);
    expect(api.callsTo('POST', '/v1/estimates')).toHaveLength(0);
  });

  test('when the model list can\'t be read, Run again sends the job as it was', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', '/v1/models', { status: 503 });
    mockEstimates(api);
    const job = makeJob({ id: jobId(14), params: { size: '1024x1024', n: 2 } });
    serveJob(api, job);

    await page.goto(`/history/${job.id}`);
    await expect(page.getByRole('button', { name: 'Run again $0.08' })).toBeEnabled();
    expect(api.callsTo('POST', '/v1/estimates').at(-1)!.body).toEqual({ model: 'flux-2-pro', n: 2, size: '1024x1024' });
    await expect(page.getByText(/so Run again asks for/)).toHaveCount(0);
  });

  test('a failed job shows the error and the refund; no date when none is set', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', '/v1/models', { json: MODELS });
    api.on('POST', '/v1/estimates', { status: 503 });
    const failed = makeJob({
      id: jobId(8), status: 'FAILED', outputs: [], outputUrl: null, actualCostCents: null, mediaExpiresAt: null,
      errorMessage: 'Content policy violation.',
    });
    serveJob(api, failed);

    await page.goto(`/history/${failed.id}`);
    await expect(page.getByText('Content policy violation.')).toBeVisible();
    await expect(page.getByText('The charge was returned to your balance.')).toBeVisible();
    await expect(page.getByRole('definition').filter({ hasText: 'Refunded' })).toBeVisible();
    await expect(page.getByText(/will be deleted on/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Run again', exact: true })).toBeVisible();
  });

  test('another account\'s job, or a made-up id, is not found', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', `/v1/jobs/${jobId(9)}`, { status: 404, json: { status: 404, detail: 'job not found' } });

    await page.goto(`/history/${jobId(9)}`);
    await expect(page.getByText('Generation not found.')).toBeVisible();

    await page.goto('/history/not-a-job');
    await expect(page.getByText('Generation not found.')).toBeVisible();
    expect(api.callsTo('GET', /^\/v1\/jobs\/not-a-job/)).toHaveLength(0);
  });
});

test.describe('the dashboard and the nav', () => {
  test('the dashboard shows the four newest generations, and the Create cards', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(4);
    serveJobList(api, jobs);

    await page.goto('/dashboard');
    const recent = page.getByRole('region', { name: 'Recent generations' });
    await expect(recent.getByRole('listitem')).toHaveCount(4);
    await expect(recent.getByRole('listitem').first().getByRole('link')).toHaveAttribute('href', `/history/${jobs[0].id}`);
    await expect(recent.getByRole('link', { name: 'View all' })).toHaveAttribute('href', '/history');
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs')[0].query)).toEqual({ limit: '4' });

    await expect(page.getByRole('link', { name: /Create an image/ })).toHaveAttribute('href', '/create/image');
    await expect(page.getByRole('link', { name: /Create a video/ })).toHaveAttribute('href', '/create/video');
    await expect(page.getByText(/7 text models/)).toHaveCount(0);
  });

  test('the dashboard has no strip before the first generation', async ({ page, api }) => {
    await signedIn(page, api);

    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Get going' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recent generations' })).toHaveCount(0);
  });

  test('the nav reaches Create and History, and fits the screen', async ({ page, api }) => {
    await signedIn(page, api);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);

    await page.goto('/dashboard');
    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link')).toHaveText(
      ['Dashboard', 'Chat', 'Create', 'History', 'API keys', 'Billing', 'Settings']);
    // Nothing overflows the page sideways, phone included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

    await nav.getByRole('link', { name: 'Create' }).click();
    await expect(page).toHaveURL('/create/image');
    await expect(nav.getByRole('link', { name: 'Create' })).toHaveAttribute('aria-current', 'page');
    await page.getByRole('link', { name: 'Video', exact: true }).click();
    await expect(page).toHaveURL('/create/video');
    await expect(nav.getByRole('link', { name: 'Create' })).toHaveAttribute('aria-current', 'page');

    await nav.getByRole('link', { name: 'History' }).click();
    await expect(page).toHaveURL('/history');
    await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  });
});
