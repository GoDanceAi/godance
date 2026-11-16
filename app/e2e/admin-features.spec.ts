import { mkdir } from 'node:fs/promises';
import { expect, makeUser, problem, sessionCleared, sessionStarted, test, type MockApi } from './mock-api';
import type { UserRole } from '../src/lib/api';

const FEATURES = {
  inputs: { enabled: false, allow_people: false },
  community: { enabled: false }, byok: { enabled: false },
  routing_policies: { enabled: false, allow_list_restricted: true },
  referrals: { enabled: false }, mcp: { enabled: true },
};

async function staff(api: MockApi, role: UserRole = 'ADMIN') {
  await api.signIn(makeUser({ role, email: 'synthetic-admin@example.test', fullName: 'Synthetic Admin', twoFactorEnabled: true }));
  api.on('GET', '/v1/admin/me', { json: { email: 'synthetic-admin@example.test', role } });
}

test.describe('read-only admin feature status', () => {
  test('ADMIN sees configuration only; keyboard refresh repeats only GET', async ({ page, api }, info) => {
    await staff(api);
    api.on('GET', '/v1/admin/features', { json: FEATURES });
    await page.goto('/admin/features');
    await expect(page.getByRole('heading', { name: 'Feature status', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Feature status', exact: true })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('heading', { name: 'MCP', exact: true }).locator('..')).toContainText('Configured on');
    await expect(page.getByRole('heading', { name: 'Inputs', exact: true }).locator('..')).toContainText('Configured off');
    await expect(page.getByText('Allowlist restriction: configured')).toBeVisible();
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await expect(page.getByRole('switch')).toHaveCount(0);
    const refresh = page.getByRole('button', { name: 'Refresh', exact: true });
    await refresh.focus();
    await expect(refresh).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(() => api.callsTo('GET', '/v1/admin/features').length).toBe(2);
    expect(api.calls.filter((call) => call.path === '/v1/admin/features' && call.method !== 'GET')).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.R15_SCREENSHOTS) {
      await mkdir('../docs/round-15-evidence/05', { recursive: true });
      for (const theme of ['light', 'dark']) {
        await page.evaluate((value) => { document.documentElement.dataset.theme = value; }, theme);
        await page.screenshot({ path: `../docs/round-15-evidence/05/features-${info.project.name}-${theme}.png`, fullPage: true });
      }
    }
  });

  for (const role of ['SUPPORT', 'FINANCE'] as const) {
    test(`${role} has no link and cannot load direct page`, async ({ page, api }) => {
      await staff(api, role);
      await page.goto('/admin/features');
      await expect(page.getByText('Feature status is available to the ADMIN role.')).toBeVisible();
      await expect(page.getByRole('link', { name: 'Feature status' })).toHaveCount(0);
      expect(api.callsTo('GET', '/v1/admin/features')).toEqual([]);
    });
  }

  test('unresolved role does not offer the feature entry', async ({ page, api }) => {
    await staff(api);
    let release!: () => void;
    const waiting = new Promise<void>((resolve) => { release = resolve; });
    api.on('GET', '/v1/admin/me', async () => { await waiting; return { json: { role: 'ADMIN', email: 'synthetic-admin@example.test' } }; });
    api.on('GET', '/v1/admin/features', { json: FEATURES });
    await page.goto('/admin/features');
    await expect(page.getByText('Checking access…')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Feature status' })).toHaveCount(0);
    expect(api.callsTo('GET', '/v1/admin/features')).toEqual([]);
    release();
    await expect(page.getByRole('heading', { name: 'Feature status', exact: true })).toBeVisible();
  });

  test('failed fetch clears statuses and explicit retry can load them', async ({ page, api }) => {
    await staff(api);
    api.on('GET', '/v1/admin/features', problem(503, 'Synthetic unavailable response'));
    await page.goto('/admin/features');
    await expect(page.getByRole('alert')).toContainText('Could not load feature configuration.');
    await expect(page.getByText('Configured on', { exact: true })).toHaveCount(0);
    api.on('GET', '/v1/admin/features', { json: FEATURES });
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText('Configured on', { exact: true })).toBeVisible();
  });

  test('missing schema never becomes an enabled field', async ({ page, api }) => {
    await staff(api);
    api.on('GET', '/v1/admin/features', { json: { ...FEATURES, mcp: {} } });
    await page.goto('/admin/features');
    await expect(page.getByRole('alert')).toContainText('Could not load feature configuration.');
    await expect(page.getByText('Configured on', { exact: true })).toHaveCount(0);
  });

  test('server denial is visible and exposes no configuration', async ({ page, api }) => {
    await staff(api);
    api.on('GET', '/v1/admin/features', problem(403, 'admin access required'));
    await page.goto('/admin/features');
    await expect(page.getByRole('alert')).toContainText('Access denied.');
    await expect(page.getByText('Configured on', { exact: true })).toHaveCount(0);
  });

  test('2FA enrollment guard stays intact', async ({ page, api }) => {
    await staff(api);
    api.on('GET', '/v1/admin/me', problem(403, 'Turn on two-factor sign-in.', 'two_factor_required'));
    await page.goto('/admin/features');
    await expect(page.getByRole('heading', { name: 'Turn on two-factor sign-in' })).toBeVisible();
    expect(api.callsTo('GET', '/v1/admin/features')).toEqual([]);
  });

  test('loading never supplies enabled defaults', async ({ page, api }) => {
    await staff(api);
    let release!: () => void;
    const waiting = new Promise<void>((resolve) => { release = resolve; });
    api.on('GET', '/v1/admin/features', async () => { await waiting; return { json: FEATURES }; });
    await page.goto('/admin/features');
    await expect(page.getByRole('status')).toContainText('Loading feature configuration');
    await expect(page.getByRole('button', { name: 'Refresh' })).toBeDisabled();
    await expect(page.getByText('Configured on', { exact: true })).toHaveCount(0);
    release();
    await expect(page.getByText('Configured on', { exact: true })).toBeVisible();
  });

  test('signed-out deep link returns to feature status after sign-in', async ({ page, api }) => {
    api.mockUser(makeUser({ role: 'ADMIN', email: 'synthetic-admin@example.test' }))
      .on('POST', '/v1/auth/login', sessionStarted('synthetic-admin-session'))
      .on('GET', '/v1/admin/me', { json: { role: 'ADMIN', email: 'synthetic-admin@example.test' } })
      .on('GET', '/v1/admin/features', { json: FEATURES });
    await page.goto('/admin/features');
    await expect(page).toHaveURL('/login');
    await page.getByLabel('Email').fill('synthetic-admin@example.test');
    await page.getByLabel('Password', { exact: true }).fill('synthetic-password-only');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL('/admin/features');
    await expect(page.getByRole('heading', { name: 'Feature status', exact: true })).toBeVisible();
  });

  test('late configuration cannot appear after logout and a different account signs in', async ({ page, api }) => {
    await staff(api);
    let release!: () => void;
    const waiting = new Promise<void>((resolve) => { release = resolve; });
    api.on('GET', '/v1/admin/features', async () => { await waiting; return { json: FEATURES }; })
      .on('POST', '/v1/auth/logout', sessionCleared());
    await page.goto('/admin/features');
    await expect(page.getByRole('status')).toContainText('Loading feature configuration');
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL('/login');
    const support = makeUser({ id: '00000000-0000-4000-8000-000000000099', role: 'SUPPORT', email: 'synthetic-support@example.test' });
    api.mockUser(support).on('POST', '/v1/auth/login', sessionStarted('synthetic-support-session'))
      .on('GET', '/v1/admin/me', { json: { role: 'SUPPORT', email: support.email } });
    await page.getByLabel('Email').fill(support.email!);
    await page.getByLabel('Password', { exact: true }).fill('synthetic-password-only');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    release();
    await page.goto('/admin/features');
    await expect(page.getByText('Feature status is available to the ADMIN role.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Feature status' })).toHaveCount(0);
    await expect(page.getByText('Configured on', { exact: true })).toHaveCount(0);
    expect(api.callsTo('GET', '/v1/admin/features')).toHaveLength(1);
  });

  test('expired feature session returns to sign-in', async ({ page, api }) => {
    await staff(api);
    api.rejectSession('/v1/admin/features');
    await page.goto('/admin/features');
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });
});
