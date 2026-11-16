import type { Page } from '@playwright/test';
import type { UserRole } from '../src/lib/api';
import type { QueueItem, ReviewReport } from '../src/lib/community';
import { GALLERY_ORIGIN } from './api-origin';
import { AUTHOR, makeQueueItem, makeReport, makeReviewRecipe, serveModeration } from './community';
import { mediaUrl, serveMedia } from './generation';
import { expect, makeUser, problem, test, type MockApi, type Reply } from './mock-api';

// Round 14 brief 02: the moderation queue under /admin, for the roles brief
// 01 lets moderate (SUPPORT and ADMIN): each item with its template,
// examples and author history; approve, reject with a reason, remove; and
// the open reports.

const PROFILE_REPORT = makeReport({
  id: 'a1b2c3d4-0000-4000-8000-000000000002', kind: 'profile', target: 'cy-ink', reason: 'Pretends to be a museum.',
});

/** A new submission, a reported public recipe, and a profile to review. */
function queue(): QueueItem[] {
  return [
    makeQueueItem(),
    makeQueueItem({
      why: 'reported',
      recipe: makeReviewRecipe({
        public_id: 'pub11c000001', status: 'PUBLIC', title: 'Koi under falling rain', modality: 'video', model: 'kling-3.0',
        params: { size: '1280x720', seconds: 5 }, template: 'Koi circling under {{weather}}',
        variables: [{ name: 'weather', default: 'falling rain' }], tags: [],
        examples: [{ position: 1, url: mediaUrl('koi.mp4'), content_type: 'video/mp4', source_job_id: null, copied: true }],
        published_at: '2026-11-14T10:00:00Z', reviewed_at: '2026-11-14T10:00:00Z',
      }),
      prior_decisions: [
        { public_id: 'p3nd1ng00001', title: 'Teapot still life', status: 'PENDING', reason: null, reviewed_at: null },
        { public_id: 'r3j3ct000001', title: 'Neon cat poster', status: 'REJECTED', reason: 'Shows a brand logo.', reviewed_at: '2026-11-12T10:00:00Z' },
      ],
      reports: [makeReport()],
    }),
    makeQueueItem({
      why: 'profile',
      recipe: null,
      author: {
        user_id: '0f0e0d0c-0000-4000-8000-000000000009', email: 'cy@example.test', handle: 'cy-ink', display_name: 'Cy, the museum',
        link: 'https://example.test/cy', approved_display_name: 'Cy', approved_link: 'https://example.test/cy',
        approved_at: '2026-11-10T00:00:00Z', followers: 7,
      },
      reports: [PROFILE_REPORT],
    }),
  ];
}

async function signInAs(page: Page, api: MockApi, role: UserRole, items: QueueItem[] = queue(),
  reports: ReviewReport[] = [makeReport(), PROFILE_REPORT], refuse: Partial<Record<string, Reply>> = {}) {
  await api.signIn(makeUser({ role }));
  api.on('GET', '/v1/admin/me', { json: { email: 'ada@example.com', role } });
  await serveMedia(page);
  return serveModeration(api, items, reports, refuse);
}

const item = (page: Page, name: string) => page.getByRole('article', { name });
const queueReads = (api: MockApi) => api.callsTo('GET', '/v1/admin/community/queue').length;

test.describe('the moderation queue', () => {
  test('support sees each item\'s template, examples, author and history, and the open reports', async ({ page, api }) => {
    await signInAs(page, api, 'SUPPORT');
    await page.goto('/admin/community');

    await expect(page.getByRole('link', { name: 'Community' })).toHaveClass(/active/);
    await expect(page.getByRole('heading', { name: 'Queue · 3' })).toBeVisible();

    const pending = item(page, 'Teapot still life');
    await expect(pending).toContainText('New submission');
    await expect(pending.locator('[data-review-template]')).toHaveText('A {{thing}} on a wooden table, soft window light.');
    await expect(pending.locator('[data-review-template] mark')).toHaveText(['{{thing}}']);
    await expect(pending.getByRole('list', { name: 'Examples' }).getByRole('img')).toHaveCount(2);
    await expect(pending.getByRole('img', { name: 'Example 1' })).toHaveAttribute('src', mediaUrl('review-1.png'));
    for (const fact of ['teapot', 'gpt-image-2', '1024x1024', 'CC-BY-4.0', 'still-life']) await expect(pending).toContainText(fact);
    const author = pending.getByRole('region', { name: 'Author', exact: true });
    for (const fact of [`@${AUTHOR}`, 'Mina', 'https://example.test/mina', 'mina@example.test', 'not approved yet']) {
      await expect(author).toContainText(fact);
    }
    await expect(pending.getByRole('region', { name: 'Author history' })).toContainText('None: this is their first submission.');

    const reported = item(page, 'Koi under falling rain');
    await expect(reported).toContainText('Reported');
    await expect(reported.getByLabel('Example 1')).toHaveAttribute('src', mediaUrl('koi.mp4'));
    const history = reported.getByRole('region', { name: 'Author history' });
    await expect(history.getByRole('listitem')).toHaveCount(2);
    await expect(history).toContainText('rejected Neon cat poster');
    await expect(history).toContainText('Shows a brand logo.');
    await expect(reported.getByRole('region', { name: 'Reports' })).toContainText('The examples are not what the prompt makes.');

    const profile = item(page, "@cy-ink's profile");
    await expect(profile.getByRole('region', { name: 'Author', exact: true })).toContainText('Cy, the museum');
    await expect(profile.getByRole('region', { name: 'Author', exact: true })).toContainText('approved: Cy');
    await expect(profile.getByRole('region', { name: 'Reports' })).toContainText('Pretends to be a museum.');

    const reports = page.getByRole('region', { name: 'Open reports · 2' });
    await expect(reports.getByRole('row')).toHaveCount(3);
    await expect(reports.getByRole('link', { name: 'Recipe pub11c000001' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/community/pub11c000001`);
    await expect(reports.getByRole('link', { name: 'Profile @cy-ink' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/u/cy-ink`);
  });

  test('approving asks first; then the submission leaves the queue', async ({ page, api }) => {
    const { decisions } = await signInAs(page, api, 'SUPPORT');
    await page.goto('/admin/community');
    const approve = item(page, 'Teapot still life').getByRole('button', { name: 'Approve', exact: true });

    page.once('dialog', (dialog) => void dialog.dismiss());
    await approve.click();
    expect(decisions).toEqual([]);

    page.once('dialog', (dialog) => {
      expect(dialog.message()).toBe('Approve “Teapot still life”? It becomes public at once, with its examples.');
      void dialog.accept();
    });
    await approve.click();
    await expect(page.getByText('Approved “Teapot still life”.')).toBeVisible();
    await expect(item(page, 'Teapot still life')).toHaveCount(0);
    expect(decisions).toEqual([{ action: 'approve', target: 'p3nd1ng00001' }]);
    expect(queueReads(api)).toBe(2);
  });

  test('rejecting needs a reason the author will see, and sends it', async ({ page, api }) => {
    const { decisions } = await signInAs(page, api, 'SUPPORT');
    await page.goto('/admin/community');
    const pending = item(page, 'Teapot still life');

    await pending.getByRole('button', { name: 'Reject…' }).click();
    await pending.getByRole('button', { name: 'Cancel' }).click();
    await pending.getByRole('button', { name: 'Reject…' }).click();
    const reject = pending.getByRole('button', { name: 'Reject with this reason' });
    await expect(reject).toBeDisabled();
    await pending.getByLabel('Why? The author sees this.').fill('The examples show a brand logo.');
    await reject.click();

    await expect(page.getByText('Rejected “Teapot still life”.')).toBeVisible();
    expect(decisions).toEqual([{ action: 'reject', target: 'p3nd1ng00001', reason: 'The examples show a brand logo.' }]);
  });

  test('a reported recipe: keep it public, or remove it with a reason', async ({ page, api }) => {
    const { decisions } = await signInAs(page, api, 'SUPPORT', [queue()[1], { ...queue()[1], recipe: makeReviewRecipe({ public_id: 'pub11c000002', status: 'PUBLIC', title: 'Glass whale' }) }]);
    await page.goto('/admin/community');

    page.once('dialog', (dialog) => {
      expect(dialog.message()).toBe('Keep “Koi under falling rain” public and close its reports?');
      void dialog.accept();
    });
    await item(page, 'Koi under falling rain').getByRole('button', { name: 'Keep it public' }).click();
    await expect(page.getByText('Kept “Koi under falling rain” public.')).toBeVisible();

    const whale = item(page, 'Glass whale');
    await whale.getByRole('button', { name: 'Remove…' }).click();
    await whale.getByLabel('Why? The author sees this.').fill('A copy of another artist\'s work.');
    await whale.getByRole('button', { name: 'Remove with this reason' }).click();
    await expect(page.getByText('Removed “Glass whale”.')).toBeVisible();
    expect(decisions).toEqual([
      { action: 'approve', target: 'pub11c000001' },
      { action: 'remove', target: 'pub11c000002', reason: 'A copy of another artist\'s work.' },
    ]);
  });

  test('a profile: approve it as it is, or remove it with a reason', async ({ page, api }) => {
    const profile = queue()[2];
    const { decisions } = await signInAs(page, api, 'SUPPORT', [profile, { ...profile, author: { ...profile.author, handle: 'bo-paper' } }]);
    await page.goto('/admin/community');

    page.once('dialog', (dialog) => void dialog.accept());
    await item(page, "@cy-ink's profile").getByRole('button', { name: 'Approve profile' }).click();
    await expect(page.getByText("Approved @cy-ink's profile.")).toBeVisible();

    const bo = item(page, "@bo-paper's profile");
    await bo.getByRole('button', { name: 'Remove profile…' }).click();
    await bo.getByLabel('Why? The author sees this.').fill('The display name pretends to be a museum.');
    await bo.getByRole('button', { name: 'Remove profile with this reason' }).click();
    await expect(page.getByText("Removed @bo-paper's profile.")).toBeVisible();
    expect(decisions).toEqual([
      { action: 'profile-approve', target: 'cy-ink' },
      { action: 'profile-remove', target: 'bo-paper', reason: 'The display name pretends to be a museum.' },
    ]);
  });

  test('a decision the API refuses shows its reason, and the item stays', async ({ page, api }) => {
    await signInAs(page, api, 'SUPPORT', queue(), [], {
      approve: problem(409, 'this recipe was rejected in the meantime', 'moderation_conflict'),
    });
    await page.goto('/admin/community');

    page.once('dialog', (dialog) => void dialog.accept());
    const pending = item(page, 'Teapot still life');
    await pending.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(pending.getByRole('alert')).toHaveText('this recipe was rejected in the meantime');
    await expect(pending).toBeVisible();
  });

  test('with nothing waiting, it says so', async ({ page, api }) => {
    await signInAs(page, api, 'ADMIN', [], []);
    await page.goto('/admin/community');
    await expect(page.getByText('Nothing waits for review.')).toBeVisible();
    await expect(page.getByText('No open reports.')).toBeVisible();
  });
});

test.describe('who may moderate', () => {
  test('an admin has the Community tab and the queue', async ({ page, api }) => {
    await signInAs(page, api, 'ADMIN');
    api.on('GET', '/v1/admin/pricing/proposals', { json: { proposals: [] } });
    await page.goto('/admin/proposals');

    await page.getByRole('link', { name: 'Community' }).click();
    await expect(page).toHaveURL('/admin/community');
    await expect(item(page, 'Teapot still life')).toBeVisible();
  });

  test('finance has no Community tab, and the page refuses it without asking the API', async ({ page, api }) => {
    await signInAs(page, api, 'FINANCE');
    api.on('GET', '/v1/admin/pricing/proposals', { json: { proposals: [] } });
    await page.goto('/admin/proposals');
    await expect(page.getByRole('link', { name: 'Proposals' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Community' })).toHaveCount(0);

    await page.goto('/admin/community');
    await expect(page.getByText('Moderating the community is for the support and admin roles.')).toBeVisible();
    expect(queueReads(api)).toBe(0);
  });
});
