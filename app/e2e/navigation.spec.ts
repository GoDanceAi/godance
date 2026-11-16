import { expect, makeUser, problem, sessionCleared, sessionStarted, test } from './mock-api';
import { makeProfile } from './community';
import { GALLERY_ORIGIN } from './api-origin';

const CORE = ['Dashboard', 'Image Studio', 'Video Studio', 'Chat', 'History', 'Recipes', 'Batches', 'API keys', 'MCP documentation', 'Billing', 'Settings'];
const REFERRALS = { enabled: true, code: 'ABCDEFGH', referrer_reward_cents: 100, referee_reward_cents: 100,
  min_topup_cents: 1000, invited: 0, rewarded: 0, earned_cents: 0, pending_reward_cents: null };

test('grouped navigation exposes every core destination without sideways scrolling or writes', async ({ page, api }) => {
  await api.signIn(makeUser({ email: 'ada@example.test' }));
  await page.goto('/dashboard');
  const nav = page.getByRole('navigation', { name: 'Main', exact: true });
  await expect(nav.getByRole('link')).toHaveText(CORE);
  for (const group of ['Create', 'Library', 'Developer tools', 'Account']) {
    await expect(nav.getByText(group, { exact: true })).toBeVisible();
  }
  await expect(nav.getByRole('link', { name: 'Batches', exact: true })).toHaveAttribute('href', '/batches');
  await expect(nav.getByRole('link', { name: 'MCP documentation' })).toHaveAttribute('href', 'https://godance.ai/docs/#mcp');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(api.calls.filter((call) => call.method !== 'GET')).toEqual([]);
});

test('optional navigation stays hidden while loading and on denied discovery, then shows permitted links', async ({ page, api }) => {
  await api.signIn();
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => { release = resolve; });
  api.on('GET', '/v1/profile', async () => { await waiting; return { json: makeProfile() }; });
  api.on('GET', '/v1/referrals/me', async () => { await waiting; return { json: REFERRALS }; });
  await page.goto('/dashboard');
  const nav = page.getByRole('navigation', { name: 'Main', exact: true });
  await expect(nav.getByRole('link', { name: 'Batches', exact: true })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Following' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Invite' })).toHaveCount(0);
  release();
  await expect(nav.getByRole('link', { name: 'Following' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Invite' })).toBeVisible();
  api.on('GET', '/v1/profile', problem(403, 'Forbidden'));
  api.on('GET', '/v1/referrals/me', problem(503, 'Unavailable'));
  await page.reload();
  await expect(nav.getByRole('link', { name: 'Following' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Invite' })).toHaveCount(0);
});

for (const nextAccount of ['different', 'same'] as const) {
test(`late account discovery cannot enable links for a ${nextAccount} account session`, async ({ page, api }) => {
  const first = makeUser({ email: 'first@example.test' });
  await api.signIn(first);
  api.on('POST', '/v1/auth/logout', sessionCleared());
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => { release = resolve; });
  api.on('GET', '/v1/profile', async () => { await waiting; return { json: makeProfile() }; });
  api.on('GET', '/v1/referrals/me', async () => { await waiting; return { json: REFERRALS }; });
  await page.goto('/dashboard');
  await expect.poll(() => api.callsTo('GET', '/v1/profile').length).toBe(1);
  await page.getByRole('button', { name: 'Log out' }).click();
  api.mockUser(nextAccount === 'same' ? first : makeUser({ id: '00000000-0000-4000-8000-000000000002', email: 'next@example.test' }));
  api.on('POST', '/v1/auth/login', sessionStarted('unusable-test-session'));
  api.on('GET', '/v1/profile', problem(403, 'Forbidden'));
  api.on('GET', '/v1/referrals/me', { json: { enabled: false } });
  await page.getByLabel('Email').fill('next@example.test');
  await page.getByLabel('Password').fill('unusable-test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/dashboard');
  release();
  const nav = page.getByRole('navigation', { name: 'Main', exact: true });
  await expect(nav.getByRole('link', { name: 'Following' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Invite' })).toHaveCount(0);
});
}

test('the menu is keyboard usable, keeps focus and current-page indication, and fits narrow screens', async ({ page, api }, testInfo) => {
  await api.signIn(makeUser({ email: 'ada@example.test' }));
  await page.goto('/dashboard');
  const menu = page.locator('header summary');
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link')).toHaveCount(0);
  await expect(menu).toBeFocused();
  await page.keyboard.press('Space');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link', { name: 'Dashboard' })).toBeFocused();
  await expect(page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
  if (process.env.R15_CAPTURE_EVIDENCE === '1') {
    await page.screenshot({ path: testInfo.outputPath(`dashboard-${testInfo.project.name}-light.png`), fullPage: true });
    await page.getByRole('button', { name: 'Switch to dark mode' }).click();
    await page.screenshot({ path: testInfo.outputPath(`dashboard-${testInfo.project.name}-dark.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 320, height: 740 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('Following offers public discovery only after readable published content is verified', async ({ page, api }) => {
  await api.signIn();
  api.on('GET', '/v1/profile', { json: makeProfile() });
  api.on('GET', '/v1/follows', { json: [] });
  api.on('GET', '/v1/follows/feed', { json: [] });
  await page.route(`${GALLERY_ORIGIN}/community`, (route) => route.fulfill({ status: 200,
    contentType: 'text/html', headers: { 'access-control-allow-origin': '*' },
    body: '<main><h1>Community</h1><a href="/community/example-recipe">Example recipe</a></main>' }));
  await page.goto('/following');
  await expect(page.getByRole('link', { name: 'Explore community recipes' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/community`);
  await page.route(`${GALLERY_ORIGIN}/community`, (route) => route.fulfill({ status: 404 }));
  await page.reload();
  await expect(page.getByText("You don't follow anyone yet.")).toBeVisible();
  await expect(page.getByRole('link', { name: 'Explore community recipes' })).toHaveCount(0);
  await page.route(`${GALLERY_ORIGIN}/community`, (route) => route.fulfill({ status: 200,
    contentType: 'text/html', headers: { 'access-control-allow-origin': '*' }, body: '<main><h1>Community</h1></main>' }));
  await page.reload();
  await expect(page.getByText("You don't follow anyone yet.")).toBeVisible();
  await expect(page.getByRole('link', { name: 'Explore community recipes' })).toHaveCount(0);
  await page.route(`${GALLERY_ORIGIN}/community`, (route) => route.abort());
  await page.reload();
  await expect(page.getByText("You don't follow anyone yet.")).toBeVisible();
  await expect(page.getByRole('link', { name: 'Explore community recipes' })).toHaveCount(0);
});
