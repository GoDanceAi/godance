import type { Page } from '@playwright/test';
import type { JobView } from '../src/lib/api';
import type { PublishSubmission } from '../src/lib/community';
import { makeProfile, makeSubmission, profileSet, serveProfile, serveSubmission } from './community';
import { jobId, makeJob, mediaUrl, serveJob, serveJobList, serveMedia, serveSaves } from './generation';
import { expect, makeUser, problem, test, type MockApi, type Reply } from './mock-api';
import { makeRecipe, makeVersion, recipeId, serveRecipes, versionId } from './user-recipes';

// Round 14 brief 02: publishing one of the user's recipes from its page:
// the version, one to four of its runs as examples, the licence and tags;
// then each status the API reports, and unpublishing.

/** A run of the first recipe at `version`; finished, its files kept, no input image, unless overridden. */
function run(n: number, version: number, overrides: Partial<JobView> = {}): JobView {
  return makeJob({
    id: jobId(n),
    model: 'gpt-image-1.5',
    createdAt: `2026-11-1${Math.min(n, 9)}T12:00:00Z`,
    outputUrl: mediaUrl(`run-${n}.png`),
    outputs: [{ index: 0, url: mediaUrl(`run-${n}.png`), contentType: 'image/png', sizeBytes: 800_000, revisedPrompt: null }],
    recipe: { id: recipeId(1), version, variables: { color: 'red', surface: 'kitchen shelf' } },
    ...overrides,
  });
}

/**
 * Newest first: five finished runs of version 3, and the runs that cannot be
 * examples: a failed one, a purged one, one made from an input image, and one
 * of version 2.
 */
function runs(): JobView[] {
  return [
    run(9, 3),
    run(8, 3, { status: 'FAILED', outputs: [], outputUrl: null, errorMessage: 'The model refused the prompt.' }),
    run(7, 3),
    run(6, 3, { purged: true, outputs: [], outputUrl: null, prompt: null, mediaExpiresAt: null }),
    run(5, 3, { inputs: [{ role: 'image', file_id: 'file-1', url: mediaUrl('mine.png') }] }),
    run(4, 3),
    run(3, 3),
    run(2, 3),
    run(1, 2),
  ];
}

/** The recipe page of the first recipe, with the community open, this submission, and these runs. */
async function openRecipe(
  page: Page,
  api: MockApi,
  submission: PublishSubmission | null,
  { refuse, jobs = runs() }: { refuse?: Reply; jobs?: JobView[] } = {},
) {
  await api.signIn();
  await serveMedia(page);
  serveProfile(api, profileSet());
  serveRecipes(api, [makeRecipe()], {
    [recipeId(1)]: [makeVersion({ id: versionId(1, 2), number: 2, template: 'A {{color}} mug on a table', variables: [{ name: 'color', default: 'red' }] })],
  });
  serveJobList(api, jobs);
  serveSaves(api, jobs);
  for (const job of jobs) serveJob(api, job);
  const served = serveSubmission(api, recipeId(1), submission, refuse);
  await page.goto(`/recipes/${recipeId(1)}`);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  return served;
}

const panel = (page: Page) => page.getByRole('region', { name: 'Community' });
const dialog = (page: Page) => page.getByRole('dialog', { name: 'Publish “Mug on a kitchen shelf”' });
const exampleBoxes = (page: Page) => dialog(page).getByRole('checkbox');

test.describe('publishing a recipe', () => {
  test('choose the version, one to four of its runs, the licence and tags, and see what will be public', async ({ page, api }) => {
    const served = await openRecipe(page, api, null);
    await expect(panel(page)).toContainText('Share this recipe in the community');
    await panel(page).getByRole('button', { name: 'Publish…' }).click();

    const box = dialog(page);
    await expect(box.getByLabel('Version')).toHaveValue('3');
    await expect(box).toContainText('Only finished runs of this version that used no image of yours can be examples');
    // Of version 3's eight runs, the failed, purged and input-image ones are left out.
    await expect(exampleBoxes(page)).toHaveCount(5);
    const submit = box.getByRole('button', { name: 'Submit for review' });
    await expect(submit).toBeDisabled();

    // Four is the most: the rest wait until one is unticked.
    for (let i = 0; i < 3; i++) await exampleBoxes(page).nth(i).check();
    await expect(box.getByText('3 of 4 chosen.')).toBeVisible();
    await exampleBoxes(page).nth(3).check();
    await expect(box.getByText('4 of 4 chosen, the most a recipe shows.')).toBeVisible();
    await expect(exampleBoxes(page).nth(4)).toBeDisabled();

    // Another version starts the choice again, with its own runs.
    await box.getByLabel('Version').selectOption('2');
    await expect(exampleBoxes(page)).toHaveCount(1);
    await expect(box.getByText('0 of 4 chosen.')).toBeVisible();
    await box.getByLabel('Version').selectOption('3');
    await exampleBoxes(page).nth(0).check();
    await exampleBoxes(page).nth(2).check();

    await expect(box.getByRole('radio', { name: /CC-BY-4\.0/ })).toBeChecked();
    await box.getByRole('radio', { name: /CC0-1\.0/ }).check();
    await expect(box.getByText('Anyone may reuse the prompt and the examples, with no conditions.')).toBeVisible();

    await box.getByLabel('Tags (optional)').fill('Mugs, kitchen ware');
    await expect(box.getByText('“ware” can')).toHaveCount(0);
    await box.getByLabel('Tags (optional)').fill('mugs, kitchen_ware');
    await expect(box.getByText("“kitchen_ware” can't be a tag")).toBeVisible();
    await expect(submit).toBeDisabled();
    await box.getByLabel('Tags (optional)').fill('Mugs, kitchen');

    await expect(box.getByRole('list', { name: 'Public once approved' })).toContainText("version 3's template, variables and their defaults");
    await expect(box.getByRole('list', { name: 'Public once approved' })).toContainText('Copies of the examples you choose');
    await expect(box.getByRole('list', { name: 'Stays private' })).toContainText('What your runs cost, and your balance');
    await expect(box.getByRole('list', { name: 'Stays private' })).toContainText('Your email address and account');

    await submit.click();
    await expect(dialog(page)).toHaveCount(0);
    expect(api.callsTo('POST', `/v1/recipes/${recipeId(1)}/publish`).map((call) => call.body)).toEqual([
      { version: 3, licence: 'CC0-1.0', job_ids: [jobId(9), jobId(4)], tags: ['mugs', 'kitchen'] },
    ]);
    expect(served.current()?.status).toBe('PENDING');
    await expect(panel(page)).toContainText('Pending review');
    await expect(panel(page)).toContainText('Nothing is public until a moderator approves it.');
    await expect(panel(page)).toContainText('Version 3 · CC0-1.0 · mugs, kitchen');
    await expect(panel(page).getByRole('button', { name: 'Unpublish' })).toBeVisible();
  });

  test('a refusal from the API stays in the dialog with its reason', async ({ page, api }) => {
    await openRecipe(page, api, null, {
      refuse: problem(400, `the job ${jobId(9)} has had its files deleted`, 'invalid_example', { job_id: jobId(9) }),
    });
    await panel(page).getByRole('button', { name: 'Publish…' }).click();
    await exampleBoxes(page).first().check();
    await dialog(page).getByRole('button', { name: 'Submit for review' }).click();

    await expect(dialog(page).getByRole('alert')).toHaveText(`the job ${jobId(9)} has had its files deleted`);
    await expect(panel(page)).toContainText('Share this recipe in the community');
  });

  test('with no run that can be an example, it says how to make one', async ({ page, api }) => {
    // Only runs that cannot be examples: a failed one, and one made from an input image.
    await openRecipe(page, api, null, { jobs: runs().filter((job) => job.id === jobId(8) || job.id === jobId(5)) });
    await panel(page).getByRole('button', { name: 'Publish…' }).click();
    await expect(dialog(page)).toContainText('No run of version 3 can be an example yet.');
    await expect(dialog(page).getByRole('link', { name: 'Run it in the Studio' })).toHaveAttribute('href', `/create/image?recipe=${recipeId(1)}`);
    await expect(dialog(page).getByRole('button', { name: 'Submit for review' })).toBeDisabled();
  });
});

test.describe('each status the API reports', () => {
  const statuses: [string, Partial<PublishSubmission>, RegExp[], string][] = [
    ['pending', { status: 'PENDING' }, [/Pending review/, /Submitted .*\. Nothing is public until a moderator approves it\./], 'Unpublish'],
    ['public, with a link to its page', { status: 'PUBLIC', published_at: '2026-11-15T12:00:00Z', reviewed_at: '2026-11-15T12:00:00Z' },
      [/Public/, /Published .*: its page on godance\.ai\. A new page appears there within a day\./], 'Unpublish'],
    ['rejected, with the reason', { status: 'REJECTED', reason: 'The examples show a brand logo.', reviewed_at: '2026-11-15T12:00:00Z' },
      [/Rejected/, /A moderator rejected it: The examples show a brand logo\. You can change it and publish it again\./], 'Publish again…'],
    ['removed by a moderator, with the reason', { status: 'REMOVED', reason: 'Reported as a copy of another recipe.' },
      [/Removed/, /A moderator removed it: Reported as a copy of another recipe\./], 'Publish again…'],
    ['unpublished by its author', { status: 'REMOVED', reason: null },
      [/Unpublished/, /You unpublished it\. It is no longer in the community\./], 'Publish again…'],
  ];
  for (const [what, submission, lines, action] of statuses) {
    test(what, async ({ page, api }) => {
      await openRecipe(page, api, makeSubmission(submission));
      for (const line of lines) await expect(panel(page)).toContainText(line);
      await expect(panel(page).getByRole('button', { name: action })).toBeVisible();
      if (submission.status === 'PUBLIC') {
        await expect(panel(page).getByRole('link', { name: 'its page on godance.ai' }))
          .toHaveAttribute('href', /\/community\/n3wpub000001$/);
      }
    });
  }
});

test.describe('unpublishing', () => {
  test('asks first; once agreed, the recipe leaves the community at once', async ({ page, api }) => {
    const served = await openRecipe(page, api, makeSubmission({ status: 'PUBLIC', published_at: '2026-11-15T12:00:00Z' }));
    const unpublish = panel(page).getByRole('button', { name: 'Unpublish' });

    page.once('dialog', (d) => void d.dismiss());
    await unpublish.click();
    expect(api.callsTo('DELETE', `/v1/recipes/${recipeId(1)}/publish`)).toEqual([]);

    page.once('dialog', (d) => {
      expect(d.message()).toBe('Unpublish “Mug on a kitchen shelf”? It leaves the community at once, and its example copies are deleted.');
      void d.accept();
    });
    await unpublish.click();
    await expect(panel(page)).toContainText('You unpublished it.');
    expect(api.callsTo('DELETE', `/v1/recipes/${recipeId(1)}/publish`)).toHaveLength(1);
    expect(served.current()?.status).toBe('REMOVED');
    await expect(panel(page).getByRole('button', { name: 'Publish again…' })).toBeVisible();
  });

  test('a pending one is taken out of review the same way', async ({ page, api }) => {
    await openRecipe(page, api, makeSubmission({ status: 'PENDING' }));
    page.once('dialog', (d) => {
      expect(d.message()).toContain('It is taken out of review at once.');
      void d.accept();
    });
    await panel(page).getByRole('button', { name: 'Unpublish' }).click();
    await expect(panel(page)).toContainText('You unpublished it.');
  });
});

test.describe('who may publish', () => {
  test('an account without a profile is sent to set one up first', async ({ page, api }) => {
    await api.signIn();
    serveProfile(api, makeProfile());
    serveRecipes(api, [makeRecipe()]);
    serveJobList(api, []);
    serveSubmission(api, recipeId(1), null);
    await page.goto(`/recipes/${recipeId(1)}`);

    const panel = page.getByRole('region', { name: 'Community' });
    await expect(panel).toContainText('To publish this recipe, first set up your public profile');
    await expect(panel.getByRole('link', { name: 'set up your public profile' })).toHaveAttribute('href', '/settings');
    await expect(panel.getByRole('button', { name: /^Publish/ })).toHaveCount(0);
  });

  test('an account that may not publish is told why', async ({ page, api }) => {
    await api.signIn(makeUser({ emailVerified: false }));
    serveProfile(api, makeProfile({ cannot_publish: 'email_not_verified' }));
    serveRecipes(api, [makeRecipe()]);
    serveJobList(api, []);
    await page.goto(`/recipes/${recipeId(1)}`);

    const panel = page.getByRole('region', { name: 'Community' });
    await expect(panel).toContainText('Verify your email address to publish recipes.');
    await expect(panel.getByRole('button')).toHaveCount(0);
    expect(api.callsTo('GET', `/v1/recipes/${recipeId(1)}/publish`)).toEqual([]);
  });
});
