import type { AdminFunnel, AdminUsageSummary } from '../src/lib/api';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

// Round 10 brief 01: the admin Overview page shows the funnel from sign-up
// to the API, for a date range, as counts with their share of the sign-ups.

const SUMMARY: AdminUsageSummary = { from: '2026-10-16', to: '2026-11-14', days: [], topModels: [], topUsers: [] };

const FUNNEL: AdminFunnel = {
  cohorts: [
    { week: '2026-W45', signed_up: 80, first_studio_job: 40, first_paid_top_up: 12, first_api_job: 0, median_days_to_api_job: null },
    { week: '2026-W46', signed_up: 120, first_studio_job: 64, first_paid_top_up: 18, first_api_job: 7, median_days_to_api_job: 3.5 },
  ],
  by_source: [
    { source: 'gallery', signed_up: 40, first_studio_job: 31, first_paid_top_up: 9, first_api_job: 4 },
    { source: 'utm:newsletter', signed_up: 25, first_studio_job: 10, first_paid_top_up: 2, first_api_job: 1 },
    { source: 'utm:other', signed_up: 10, first_studio_job: 3, first_paid_top_up: 0, first_api_job: 0 },
    { source: 'referral', signed_up: 8, first_studio_job: 6, first_paid_top_up: 4, first_api_job: 2 },
    { source: 'none', signed_up: 117, first_studio_job: 54, first_paid_top_up: 15, first_api_job: 0 },
  ],
  gallery_recipes: [
    { slug: 'paper-lantern-street-at-dusk', users: 31, jobs: 88, users_with_api_job: 4 },
    { slug: 'koi-pond-in-the-rain', users: 6, jobs: 9, users_with_api_job: 0 },
  ],
};

const EMPTY: AdminFunnel = { cohorts: [], by_source: [], gallery_recipes: [] };

async function signInAsStaff(api: MockApi, role: 'SUPPORT' | 'FINANCE' | 'ADMIN' = 'SUPPORT') {
  await api.signIn(makeUser({ role }));
  api.on('GET', '/v1/admin/me', { json: { email: 'ada@example.com', role } })
    .on('GET', '/v1/admin/usage/summary', { json: SUMMARY });
}

const funnelCalls = (api: MockApi) => api.callsTo('GET', '/v1/admin/funnel');

test.describe('the funnel on the admin Overview page', () => {
  test('shows each week, source and recipe as counts with their share', async ({ page, api }) => {
    await signInAsStaff(api);
    api.on('GET', '/v1/admin/funnel', { json: FUNNEL });

    await page.goto('/admin/overview');

    const section = page.getByRole('region', { name: 'From sign-up to the API' });
    await expect(section.getByText('Groups of fewer than 5 people are left out.')).toBeVisible();

    const weeks = section.getByRole('table').nth(0);
    await expect(weeks.getByRole('columnheader')).toHaveText(
      ['Week', 'Signed up', 'Studio job', 'Paid top-up', 'API job', 'Median days to API job']);
    await expect(weeks.getByRole('row', { name: /2026-W46/ }).getByRole('cell'))
      .toHaveText(['120', '64 (53%)', '18 (15%)', '7 (6%)', '3.5']);
    // Nobody in the week has an API job yet: no median to show.
    await expect(weeks.getByRole('row', { name: /2026-W45/ }).getByRole('cell'))
      .toHaveText(['80', '40 (50%)', '12 (15%)', '0 (0%)', '—']);

    const sources = section.getByRole('table').nth(1);
    await expect(sources.getByRole('rowheader')).toHaveText(
      ['Gallery recipe', 'Campaign: newsletter', 'Other campaigns', 'Invited by a user', 'No source recorded']);
    await expect(sources.getByRole('row', { name: /Gallery recipe/ }).getByRole('cell'))
      .toHaveText(['40', '31 (78%)', '9 (23%)', '4 (10%)']);

    const recipes = section.getByRole('table').nth(2);
    await expect(recipes.getByRole('row', { name: /paper-lantern-street-at-dusk/ }).getByRole('cell'))
      .toHaveText(['31', '88', '4 (13%)']);
    await expect(recipes.getByRole('row', { name: /koi-pond-in-the-rain/ }).getByRole('cell'))
      .toHaveText(['6', '9', '0 (0%)']);

    // The tables scroll inside their cards; the page does not move sideways, phone included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('asks for the last 30 days, then for the range that is picked', async ({ page, api }) => {
    await signInAsStaff(api, 'FINANCE');
    api.on('GET', '/v1/admin/funnel', { json: FUNNEL });

    await page.goto('/admin/overview');
    await expect.poll(() => funnelCalls(api).length).toBe(1);
    const first = funnelCalls(api)[0].query;
    const today = new Date().toISOString().slice(0, 10);
    expect(first.get('to')).toBe(today);
    expect((Date.parse(today) - Date.parse(first.get('from')!)) / 86_400_000).toBe(29);

    api.on('GET', '/v1/admin/funnel', { json: EMPTY });
    const section = page.getByRole('region', { name: 'From sign-up to the API' });
    await section.getByLabel('From').fill('2026-01-01');
    await section.getByLabel('To').fill('2026-01-31');

    await expect(section.getByText('No week in this period has 5 sign-ups or more.')).toBeVisible();
    await expect(section.getByText('No source in this period has 5 sign-ups or more.')).toBeVisible();
    await expect(section.getByText('No recipe was run by 5 people or more in this period.')).toBeVisible();
    const last = funnelCalls(api).at(-1)!.query;
    expect([last.get('from'), last.get('to')]).toEqual(['2026-01-01', '2026-01-31']);
  });

  test('a range the API refuses says why, and the summary above stays', async ({ page, api }) => {
    await signInAsStaff(api, 'ADMIN');
    api.on('GET', '/v1/admin/funnel', problem(400, 'window too large (max ~1 year)'));

    await page.goto('/admin/overview');

    const section = page.getByRole('region', { name: 'From sign-up to the API' });
    await expect(section.getByRole('alert')).toHaveText('window too large (max ~1 year)');
    await expect(page.getByText('Daily revenue vs cost')).toBeVisible();
  });

  test('the funnel still shows when the summary cannot load', async ({ page, api }) => {
    await signInAsStaff(api);
    api.on('GET', '/v1/admin/usage/summary', problem(500, 'Internal Server Error'))
      .on('GET', '/v1/admin/funnel', { json: FUNNEL });

    await page.goto('/admin/overview');

    await expect(page.getByRole('region', { name: 'From sign-up to the API' })
      .getByRole('row', { name: /2026-W46/ })).toBeVisible();
  });
});
