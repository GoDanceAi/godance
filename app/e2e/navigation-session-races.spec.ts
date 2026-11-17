import type { Request } from '@playwright/test';
import { expect, makeUser, problem, sessionCleared, sessionStarted, test } from './mock-api';
import { makeProfile } from './community';

const PROBES = ['/v1/profile', '/v1/referrals/me', '/v1/admin/me', '/v1/follows',
  '/v1/saved-recipes', '/v1/jobs', '/v1/recipes', '/v1/follows/feed'] as const;
const pageFor = (probe: string) => probe.startsWith('/v1/follows') ? '/following'
  : ['/v1/saved-recipes', '/v1/recipes'].includes(probe) ? '/recipes' : '/dashboard';

for (const probe of PROBES) {
  for (const sameAccount of [false, true]) {
    test(`a late ${probe} 401 cannot sign out a ${sameAccount ? 'new session for the same' : 'different'} account`, async ({ page, api }) => {
      const first = makeUser({ email: 'first@example.test', fullName: 'First Account' });
      await api.signIn(first);
      api.on('POST', '/v1/auth/logout', sessionCleared());
      api.on('GET', '/v1/profile', { json: makeProfile() });
      api.on('GET', '/v1/follows/feed', { json: [] });
      api.on('GET', '/v1/follows', { json: [] });
      api.on('GET', '/v1/recipes', { json: [] });
      let release!: () => void;
      const waiting = new Promise<void>((resolve) => { release = resolve; });
      api.on('GET', probe, async () => { await waiting; return problem(401, 'Synthetic expired old session'); });
      let original: Request | null = null;
      let finish!: () => void;
      const settled = new Promise<void>((resolve) => { finish = resolve; });
      page.on('request', (request) => {
        if (!original && new URL(request.url()).pathname === probe) original = request;
      });
      const onSettled = (request: Request) => { if (request === original) finish(); };
      page.on('requestfinished', onSettled);
      page.on('requestfailed', onSettled);
      await page.goto(pageFor(probe));
      await expect.poll(() => api.callsTo('GET', probe).length).toBe(1);
      await page.getByRole('button', { name: 'Log out', exact: true }).click();
      await expect(page).toHaveURL('/login');
      api.mockUser(makeUser({
        id: sameAccount ? first.id : '00000000-0000-4000-8000-000000000002',
        email: 'next@example.test', fullName: 'Next Account',
      }));
      api.on('POST', '/v1/auth/login', sessionStarted('synthetic-next-session'));
      api.on('GET', '/v1/profile', problem(404, 'community off', 'community_disabled'));
      api.on('GET', '/v1/referrals/me', { json: { enabled: false } });
      api.on('GET', '/v1/admin/me', problem(403, 'Forbidden'));
      api.on('GET', '/v1/follows', { json: [] });
      api.on('GET', '/v1/saved-recipes', { json: [] });
      api.on('GET', '/v1/jobs', { json: [] });
      api.on('GET', '/v1/recipes', { json: [] });
      api.on('GET', '/v1/follows/feed', { json: [] });
      await page.getByLabel('Email').fill('next@example.test');
      await page.getByLabel('Password').fill('unusable-test-password');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Log out', exact: true })).toBeVisible();
      // Sign-in may restore the old private-library destination. Move to
      // B's Dashboard before delivering A's delayed response.
      await page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link', { name: 'Dashboard', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Hi, Next.' })).toBeVisible();
      release();
      await settled;
      // Let the old response's fetch continuation and React render finish.
      await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
      await expect(page).toHaveURL('/dashboard');
      await expect(page.getByRole('heading', { name: 'Hi, Next.' })).toBeVisible();
      const main = page.getByRole('navigation', { name: 'Main', exact: true });
      await expect(main.getByRole('link', { name: 'Following', exact: true })).toHaveCount(0);
      await expect(main.getByRole('link', { name: 'Invite', exact: true })).toHaveCount(0);
    });
  }

  test(`a current ${probe} 401 still ends the current session`, async ({ page, api }) => {
    await api.signIn(makeUser({ email: 'current@example.test' }));
    api.on('GET', '/v1/profile', { json: makeProfile() });
    api.on('GET', '/v1/follows/feed', { json: [] });
    api.on('GET', '/v1/follows', { json: [] });
    api.on('GET', '/v1/recipes', { json: [] });
    api.on('GET', probe, problem(401, 'Synthetic current session expired'));
    await page.goto(pageFor(probe));
    await expect(page).toHaveURL('/login');
    expect(api.callsTo('GET', probe).length).toBeGreaterThan(0);
  });
}
