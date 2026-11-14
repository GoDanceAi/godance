import type { Locator, Page } from '@playwright/test';
import type { JobView } from '../src/lib/api';
import {
  inProgress,
  jobId,
  makeJob,
  MODELS,
  mockEstimates,
  serveJob,
  serveJobList,
  serveMedia,
  serveSaves,
} from './generation';
import { expect, problem, test, type MockApi } from './mock-api';

// Saving a generation keeps it past its deletion date. The control is on
// each History card, on the job page and on a finished Studio result.

// Five minutes after the fixtures' jobs were made.
const NOW = new Date('2026-11-13T12:05:00Z');

async function signedIn(page: Page, api: MockApi) {
  await page.clock.install({ time: NOW });
  await api.signIn();
  await serveMedia(page);
}

/** The job page and the Studio read the model list and price a re-run. */
function withCatalog(api: MockApi) {
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
}

const cards = (page: Page) => page.getByRole('main').getByRole('listitem');
const save = (scope: Page | Locator) => scope.getByRole('button', { name: 'Save', exact: true });
const saved = (scope: Page | Locator) => scope.getByRole('button', { name: 'Saved', exact: true });
const savedOnly = (page: Page) => page.getByRole('button', { name: 'Saved only' });
const saveCalls = (api: MockApi, method: 'PUT' | 'DELETE', job: JobView) =>
  api.callsTo(method, `/v1/jobs/${job.id}/saved`);

/** n finished jobs, newest first, a minute apart; every third one a video. */
function manyJobs(n: number, overrides: Partial<JobView> = {}): JobView[] {
  return Array.from({ length: n }, (_, i) => makeJob({
    id: jobId(200 + i),
    prompt: `Prompt number ${i + 1}`,
    createdAt: new Date(NOW.getTime() - (i + 5) * 60_000).toISOString(),
    ...(i % 3 === 2 ? { modality: 'VIDEO', model: 'kling-3.0' } : {}),
    ...overrides,
  }));
}

/** Saved in July: far past the 90 days an unsaved job is kept. */
function oldSavedJob(n: number): JobView {
  return makeJob({
    id: jobId(n), prompt: 'A keepsake from the summer', createdAt: '2026-07-01T12:00:00Z',
    startedAt: '2026-07-01T12:00:02Z', completedAt: '2026-07-01T12:00:20Z', saved: true, mediaExpiresAt: null,
  });
}

test.describe('saving from a History card', () => {
  test('saves and un-saves, and shows which it is', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(2);
    serveJobList(api, jobs);
    serveSaves(api, jobs);

    await page.goto('/history');
    const card = cards(page).first();
    await expect(save(card)).toHaveAttribute('aria-pressed', 'false');

    await save(card).click();
    await expect(saved(card)).toHaveAttribute('aria-pressed', 'true');
    expect(saveCalls(api, 'PUT', jobs[0])).toHaveLength(1);
    // Only that card changed, and the click did not open the job.
    await expect(save(cards(page).nth(1))).toBeVisible();
    await expect(page).toHaveURL('/history');

    // The API has it: saved after a reload too.
    await page.reload();
    await expect(saved(cards(page).first())).toBeVisible();

    // A recent job un-saves at once, with nothing to confirm.
    await saved(cards(page).first()).click();
    await expect(save(cards(page).first())).toHaveAttribute('aria-pressed', 'false');
    expect(saveCalls(api, 'DELETE', jobs[0])).toHaveLength(1);
    expect(saveCalls(api, 'PUT', jobs[1])).toHaveLength(0);
  });
});

test.describe('saving from the job page', () => {
  test('saves and un-saves, and says the files are kept in place of the date', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const job = makeJob({ id: jobId(3) });
    serveJob(api, job);
    serveSaves(api, [job]);

    await page.goto(`/history/${job.id}`);
    await expect(page.getByText('These files will be deleted on Feb 11, 2027.')).toBeVisible();

    await save(page).click();
    await expect(saved(page)).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('These files are kept until you un-save this generation or close your account.'))
      .toBeVisible();
    await expect(page.getByText(/will be deleted on/)).toHaveCount(0);
    expect(saveCalls(api, 'PUT', job)).toHaveLength(1);

    // Read back from the API as saved: no date, and the same sentence.
    await page.reload();
    await expect(saved(page)).toBeVisible();
    await expect(page.getByText(/These files are kept until you un-save/)).toBeVisible();

    await saved(page).click();
    await expect(save(page)).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByText('These files will be deleted on Feb 11, 2027.')).toBeVisible();
    await expect(page.getByText(/These files are kept until/)).toHaveCount(0);
    expect(saveCalls(api, 'DELETE', job)).toHaveLength(1);
  });
});

test.describe('saving from the Studio', () => {
  test('a finished result saves and un-saves', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const job = makeJob({ id: jobId(4) });
    serveJob(api, job);
    serveSaves(api, [job]);

    await page.goto(`/create/image?job=${job.id}`);
    const result = page.getByRole('region', { name: 'Result' });
    await expect(result.getByRole('img', { name: /^Generated image/ })).toBeVisible();

    await save(result).click();
    await expect(saved(result)).toHaveAttribute('aria-pressed', 'true');
    expect(saveCalls(api, 'PUT', job)).toHaveLength(1);

    await saved(result).click();
    await expect(save(result)).toHaveAttribute('aria-pressed', 'false');
    expect(saveCalls(api, 'DELETE', job)).toHaveLength(1);
  });

  test('a run gets the control once it finishes, not before', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const done = makeJob({ id: jobId(5) });
    const job = serveJob(api, inProgress(done));
    serveSaves(api, [done]);

    await page.goto(`/create/image?job=${done.id}`);
    const result = page.getByRole('region', { name: 'Result' });
    await expect(result).toContainText('Running');
    await expect(save(result)).toHaveCount(0);

    job.set(done);
    await page.clock.fastForward(2_000);
    await expect(save(result)).toBeVisible();
    await save(result).click();
    await expect(saved(result)).toBeVisible();
  });
});

test.describe('the saved view', () => {
  test('shows only saved generations, with the count against the limit', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(6);
    for (const i of [0, 2, 3]) Object.assign(jobs[i], { saved: true, mediaExpiresAt: null });
    serveJobList(api, jobs);
    serveSaves(api, jobs);

    await page.goto('/history');
    await expect(cards(page)).toHaveCount(6);
    await expect(savedOnly(page)).toHaveAttribute('aria-pressed', 'false');

    await savedOnly(page).click();
    await expect(page).toHaveURL('/history?saved=1');
    await expect(savedOnly(page)).toHaveAttribute('aria-pressed', 'true');
    await expect(cards(page)).toHaveCount(3);
    await expect(cards(page)).toContainText(['Prompt number 1', 'Prompt number 3', 'Prompt number 4']);
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs').at(-1)!.query)).toEqual({ limit: '24', saved: 'true' });
    await expect(page.getByText('3 of 500 saved.')).toBeVisible();

    // With the kind filter, in the request and in the URL.
    await page.getByRole('button', { name: 'Videos', exact: true }).click();
    await expect(page).toHaveURL('/history?type=video&saved=1');
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText('Prompt number 3');
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs').at(-1)!.query))
      .toEqual({ limit: '24', modality: 'VIDEO', saved: 'true' });

    await page.reload();
    await expect(cards(page)).toHaveCount(1);
    await expect(savedOnly(page)).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('3 of 500 saved.')).toBeVisible();

    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(page).toHaveURL('/history?saved=1');
    await expect(cards(page)).toHaveCount(3);

    // Un-saving here takes the card out of the view and one off the count.
    await saved(cards(page).first()).click();
    await expect(cards(page)).toHaveCount(2);
    await expect(cards(page)).toContainText(['Prompt number 3', 'Prompt number 4']);
    await expect(page.getByText('2 of 500 saved.')).toBeVisible();

    // Back to everything: the un-saved one is there, and can be saved again.
    await savedOnly(page).click();
    await expect(page).toHaveURL('/history');
    await expect(cards(page)).toHaveCount(6);
    await expect(save(cards(page).first())).toBeVisible();
    expect(api.callsTo('GET', '/v1/jobs').at(-1)!.query.has('saved')).toBe(false);
  });

  test('pages through saved generations with Load more', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(30, { saved: true, mediaExpiresAt: null });
    serveJobList(api, jobs);
    serveSaves(api, jobs);

    await page.goto('/history?saved=1');
    await expect(cards(page)).toHaveCount(24);
    await expect(page.getByText('30 of 500 saved.')).toBeVisible();

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards(page)).toHaveCount(30);
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs').at(-1)!.query))
      .toEqual({ limit: '24', before: jobs[23].id, saved: 'true' });
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
  });

  test('un-saving a whole page still reaches the saved generations after it', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(26, { saved: true, mediaExpiresAt: null });
    serveJobList(api, jobs);
    serveSaves(api, jobs);

    await page.goto('/history?saved=1');
    await expect(cards(page)).toHaveCount(24);
    for (let left = 24; left > 0; left--) {
      await saved(cards(page).first()).click();
      await expect(cards(page)).toHaveCount(left - 1);
    }
    // Two are still saved, on what was the next page: not an empty view.
    await expect(page.getByText('2 of 500 saved.')).toBeVisible();
    await expect(page.getByText('No saved generations yet.')).toHaveCount(0);

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards(page)).toHaveCount(2);
    await expect(cards(page)).toContainText(['Prompt number 25', 'Prompt number 26']);
    expect(Object.fromEntries(api.callsTo('GET', '/v1/jobs').at(-1)!.query)).toEqual({ limit: '24', saved: 'true' });
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
  });

  test('says when nothing is saved, and leads back to everything', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(2);
    serveJobList(api, jobs);
    serveSaves(api, jobs);

    await page.goto('/history?saved=1');
    await expect(page.getByText('No saved generations yet.')).toBeVisible();
    await expect(page.getByText('0 of 500 saved.')).toBeVisible();
    await expect(cards(page)).toHaveCount(0);

    await page.getByRole('link', { name: 'Show everything' }).click();
    await expect(page).toHaveURL('/history');
    await expect(cards(page)).toHaveCount(2);
  });

  test('fits a phone screen', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(3, { saved: true, mediaExpiresAt: null });
    serveJobList(api, jobs);
    serveSaves(api, jobs, 3);

    await page.goto('/history?saved=1');
    await expect(cards(page)).toHaveCount(3);
    await expect(page.getByText('3 of 3 saved.')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});

test.describe('at the limit', () => {
  test('a card says so and points to the saved view, and un-saves nothing', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(3);
    for (const i of [1, 2]) Object.assign(jobs[i], { saved: true, mediaExpiresAt: null });
    serveJobList(api, jobs);
    serveSaves(api, jobs, 2);

    await page.goto('/history');
    const card = cards(page).first();
    await save(card).click();

    const notice = card.getByRole('alert');
    await expect(notice).toContainText('You have saved 2 generations, the most an account can keep.');
    // Still not saved, and nothing was un-saved to make room.
    await expect(save(card)).toHaveAttribute('aria-pressed', 'false');
    expect(api.callsTo('DELETE', /\/saved$/)).toHaveLength(0);
    await expect(saved(cards(page).nth(1))).toBeVisible();
    await expect(saved(cards(page).nth(2))).toBeVisible();

    await notice.getByRole('link', { name: 'See your saved generations' }).click();
    await expect(page).toHaveURL('/history?saved=1');
    await expect(cards(page)).toHaveCount(2);
    await expect(page.getByText('2 of 2 saved. That is the most an account can keep: un-save one to save another.'))
      .toBeVisible();
    expect(api.callsTo('DELETE', /\/saved$/)).toHaveLength(0);
  });

  test('the job page and the Studio say so too', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const job = makeJob({ id: jobId(6) });
    serveJob(api, job);
    api.on('PUT', `/v1/jobs/${job.id}/saved`, problem(409,
      'You have saved 500 generations, the most an account can keep. Un-save one to save another.',
      'saved_limit_reached'));

    await page.goto(`/history/${job.id}`);
    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('You have saved 500 generations');
    await expect(page.getByRole('alert').getByRole('link', { name: 'See your saved generations' }))
      .toHaveAttribute('href', '/history?saved=1');
    await expect(save(page)).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByText('These files will be deleted on Feb 11, 2027.')).toBeVisible();

    await page.goto(`/create/image?job=${job.id}`);
    const result = page.getByRole('region', { name: 'Result' });
    await save(result).click();
    await expect(result.getByRole('alert')).toContainText('You have saved 500 generations');
    await expect(result.getByRole('link', { name: 'See your saved generations' }))
      .toHaveAttribute('href', '/history?saved=1');
  });
});

test.describe('un-saving a generation older than the retention period', () => {
  test('asks first on the job page, and then shows it as due for deletion', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const job = oldSavedJob(7);
    serveJob(api, job);
    serveSaves(api, [job]);

    await page.goto(`/history/${job.id}`);
    await expect(page.getByText(/These files are kept until you un-save/)).toBeVisible();

    // Declined: nothing is sent and it stays saved.
    let asked = '';
    page.once('dialog', (dialog) => {
      asked = dialog.message();
      void dialog.dismiss();
    });
    await saved(page).click();
    expect(asked).toContain('more than 90 days old');
    expect(asked).toContain('its files and prompt will be deleted at the next nightly clean-up');
    await expect(saved(page)).toHaveAttribute('aria-pressed', 'true');
    expect(saveCalls(api, 'DELETE', job)).toHaveLength(0);

    // Confirmed: un-saved, and its deletion is now overdue.
    page.once('dialog', (dialog) => void dialog.accept());
    await saved(page).click();
    await expect(save(page)).toHaveAttribute('aria-pressed', 'false');
    expect(saveCalls(api, 'DELETE', job)).toHaveLength(1);
    await expect(page.getByText(/These files are due to be deleted at the next nightly clean-up/)).toBeVisible();
    await expect(page.getByText(/will be deleted on/)).toHaveCount(0);

    // The API says the same: a date in the past reads as due, not as a date.
    await page.reload();
    await expect(page.getByText(/These files are due to be deleted at the next nightly clean-up/)).toBeVisible();
    await expect(save(page)).toBeVisible();
  });

  test('asks first on a History card and in the Studio', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const job = oldSavedJob(8);
    serveJob(api, job);
    serveJobList(api, [job]);
    serveSaves(api, [job]);
    const dialogs: string[] = [];
    page.on('dialog', (dialog) => {
      dialogs.push(dialog.message());
      void dialog.dismiss();
    });

    await page.goto('/history?saved=1');
    await saved(cards(page).first()).click();
    await expect.poll(() => dialogs.length).toBe(1);
    await expect(cards(page)).toHaveCount(1);
    await expect(saved(cards(page).first())).toBeVisible();

    await page.goto(`/create/image?job=${job.id}`);
    const result = page.getByRole('region', { name: 'Result' });
    await saved(result).click();
    await expect.poll(() => dialogs.length).toBe(2);
    await expect(saved(result)).toBeVisible();
    expect(saveCalls(api, 'DELETE', job)).toHaveLength(0);
  });
});

test.describe('a generation that cannot be saved', () => {
  test('has no control on its History card', async ({ page, api }) => {
    await signedIn(page, api);
    const finished = makeJob({ id: jobId(10), prompt: 'Finished' });
    const failed = makeJob({
      id: jobId(11), prompt: 'Failed', status: 'FAILED', outputs: [], outputUrl: null, actualCostCents: null,
      errorMessage: 'The provider refused the prompt.', mediaExpiresAt: null,
    });
    const running = inProgress(makeJob({ id: jobId(12), prompt: 'Running' }));
    const purged = makeJob({
      id: jobId(13), prompt: null, params: {}, outputs: [], outputUrl: null, purged: true, mediaExpiresAt: null,
    });
    // From an API that does not know about saving: no `saved` at all.
    const legacy = makeJob({ id: jobId(14), prompt: 'Legacy' });
    delete legacy.saved;
    serveJobList(api, [finished, failed, running, purged, legacy]);
    serveJob(api, running);

    await page.goto('/history');
    await expect(cards(page)).toHaveCount(5);
    await expect(save(cards(page).nth(0))).toBeVisible();
    await expect(page.getByRole('main').getByRole('button', { name: /^Saved?$/ })).toHaveCount(1);
  });

  test('from an API without saving, the saved view shows nothing as saved', async ({ page, api }) => {
    await signedIn(page, api);
    // That API ignores ?saved=true, sends no `saved` field and has no count route.
    const jobs = manyJobs(30);
    for (const job of jobs) delete job.saved;
    api.on('GET', '/v1/jobs', (call) => ({ json: jobs.slice(0, Number(call.query.get('limit') ?? jobs.length)) }));
    api.on('GET', '/v1/jobs/saved', problem(400, 'Bad Request'));

    await page.goto('/history?saved=1');
    await expect(page.getByText('No saved generations yet.')).toBeVisible();
    await expect(cards(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
    await expect(page.getByText(/ of \d+ saved/)).toHaveCount(0);
  });

  test('has no control on its job page or in the Studio', async ({ page, api }) => {
    await signedIn(page, api);
    withCatalog(api);
    const failed = makeJob({
      id: jobId(15), status: 'FAILED', outputs: [], outputUrl: null, actualCostCents: null,
      errorMessage: 'The provider refused the prompt.', mediaExpiresAt: null,
    });
    const purged = makeJob({
      id: jobId(16), prompt: null, params: {}, outputs: [], outputUrl: null, purged: true, mediaExpiresAt: null,
    });
    serveJob(api, failed);
    serveJob(api, purged);

    await page.goto(`/history/${failed.id}`);
    await expect(page.getByText('The provider refused the prompt.')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Saved?$/ })).toHaveCount(0);

    await page.goto(`/history/${purged.id}`);
    await expect(page.getByText('The prompt was deleted with the files.')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Saved?$/ })).toHaveCount(0);

    await page.goto(`/create/image?job=${failed.id}`);
    const result = page.getByRole('region', { name: 'Result' });
    await expect(result).toContainText('Failed.');
    await expect(result.getByRole('button', { name: /^Saved?$/ })).toHaveCount(0);
  });
});

test.describe('a save that does not go through', () => {
  test('says so and leaves the generation as it was', async ({ page, api }) => {
    await signedIn(page, api);
    const jobs = manyJobs(2);
    serveJobList(api, jobs);
    api.on('PUT', `/v1/jobs/${jobs[0].id}/saved`, problem(500, 'Internal Server Error'));
    // Its files were deleted since the list was read.
    api.on('PUT', `/v1/jobs/${jobs[1].id}/saved`,
      problem(409, "This generation's files have been deleted, so it can't be saved.", 'job_not_saveable'));

    await page.goto('/history');
    await save(cards(page).nth(0)).click();
    await expect(cards(page).nth(0).getByRole('alert')).toHaveText("Couldn't save this. Try again.");
    await expect(save(cards(page).nth(0))).toHaveAttribute('aria-pressed', 'false');

    await save(cards(page).nth(1)).click();
    await expect(cards(page).nth(1).getByRole('alert'))
      .toHaveText("This generation can't be saved: its files are no longer kept.");
    await expect(save(cards(page).nth(1))).toHaveAttribute('aria-pressed', 'false');
  });
});
