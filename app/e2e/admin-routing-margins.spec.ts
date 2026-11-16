import type { AdminFunnel, AdminRoutingMargins, AdminUsageSummary } from '../src/lib/api';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

// Round 14 brief 04 (round 4's Phase 2): the admin Overview page shows
// finance and admins what routed requests earned, per routing policy and the
// rule that chose the model. Support never sees it, and nothing is asked.

const SUMMARY: AdminUsageSummary = { from: '2026-10-16', to: '2026-11-14', days: [], topModels: [], topUsers: [] };
const NO_FUNNEL: AdminFunnel = { cohorts: [], by_source: [], gallery_recipes: [] };
const OWNER = '6f1c2e9a-3b4d-4c5e-8f70-1a2b3c4d5e6f';

const MARGINS: AdminRoutingMargins = {
  from: '2026-10-16',
  to: '2026-11-14',
  rows: [
    { policy: 'godance-auto', rule: 'hard-task', requests: 1200, failed_attempts: 14,
      paid_cents: 48_210, cost_cents: 45_900, spread_cents: 2_310 },
    { policy: 'godance-fast', rule: 'all-cheap', requests: 9800, failed_attempts: 0,
      paid_cents: 3_150, cost_cents: 2_990, spread_cents: 160 },
    { policy: `usr_${OWNER}:my-coder`, rule: 'code', requests: 40, failed_attempts: 1,
      paid_cents: 1_000, cost_cents: 1_050, spread_cents: -50 },
  ],
  total: { requests: 11_040, failed_attempts: 15, paid_cents: 52_360, cost_cents: 49_940, spread_cents: 2_420 },
};

async function signInAs(api: MockApi, role: 'SUPPORT' | 'FINANCE' | 'ADMIN') {
  await api.signIn(makeUser({ role }));
  api.on('GET', '/v1/admin/me', { json: { email: 'ada@example.com', role } })
    .on('GET', '/v1/admin/usage/summary', { json: SUMMARY })
    .on('GET', '/v1/admin/funnel', { json: NO_FUNNEL });
}

const marginCalls = (api: MockApi) => api.callsTo('GET', '/v1/admin/routing/margins');

test.describe('routing margins on the admin Overview page', () => {
  test('shows finance each policy and rule: what callers paid, what the models cost, and the spread', async ({ page, api }) => {
    await signInAs(api, 'FINANCE');
    api.on('GET', '/v1/admin/routing/margins', { json: MARGINS });

    await page.goto('/admin/overview');

    const table = page.getByRole('region', { name: 'Routing margins' }).getByRole('table');
    await expect(table.getByRole('columnheader')).toHaveText(['Policy', 'Rule', 'Requests', 'Paid', 'Cost', 'Spread']);
    await expect(table.getByRole('row', { name: /godance-auto/ }).getByRole('cell'))
      .toHaveText(['hard-task', '1,200', '$482.10', '$459.00', '$23.10']);
    await expect(table.getByRole('row', { name: /godance-fast/ }).getByRole('cell'))
      .toHaveText(['all-cheap', '9,800', '$31.50', '$29.90', '$1.60']);
    // A user's own policy shows by the name its owner gave it; this one cost more than it earned.
    const own = table.getByRole('row', { name: /my-coder/ });
    await expect(own.getByRole('rowheader')).toHaveText("my-codera user's policy");
    await expect(own.getByRole('cell')).toHaveText(['code', '40', '$10.00', '$10.50', '-$0.50']);
    await expect(table.getByRole('row', { name: /Total/ }).getByRole('cell'))
      .toHaveText(['11,040', '$523.60', '$499.40', '$24.20']);
    // The last 30 days, as the summary above.
    const asked = marginCalls(api)[0].query;
    expect(asked.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(asked.get('to')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  test('asks again for the period an admin picks', async ({ page, api }) => {
    await signInAs(api, 'ADMIN');
    api.on('GET', '/v1/admin/routing/margins', { json: MARGINS });

    await page.goto('/admin/overview');
    const section = page.getByRole('region', { name: 'Routing margins' });
    await expect(section.getByRole('table')).toBeVisible();
    await section.getByLabel('From').fill('2026-11-01');

    await expect.poll(() => marginCalls(api).at(-1)?.query.get('from')).toBe('2026-11-01');
  });

  test('says so when no request went through a routing policy', async ({ page, api }) => {
    await signInAs(api, 'FINANCE');
    api.on('GET', '/v1/admin/routing/margins', {
      json: { ...MARGINS, rows: [], total: { requests: 0, failed_attempts: 0, paid_cents: 0, cost_cents: 0, spread_cents: 0 } },
    });

    await page.goto('/admin/overview');

    await expect(page.getByRole('region', { name: 'Routing margins' }))
      .toContainText('No request in this period went through a routing policy.');
  });

  test('shows what the API said when it refuses', async ({ page, api }) => {
    await signInAs(api, 'FINANCE');
    api.on('GET', '/v1/admin/routing/margins', problem(400, 'window too large (max ~1 year)'));

    await page.goto('/admin/overview');

    await expect(page.getByRole('region', { name: 'Routing margins' }).getByRole('alert'))
      .toHaveText('window too large (max ~1 year)');
  });

  test('is not shown to support, and nothing is asked', async ({ page, api }) => {
    await signInAs(api, 'SUPPORT');

    await page.goto('/admin/overview');

    await expect(page.getByRole('region', { name: 'From sign-up to the API' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Routing margins' })).toHaveCount(0);
    expect(marginCalls(api)).toHaveLength(0);
  });
});
