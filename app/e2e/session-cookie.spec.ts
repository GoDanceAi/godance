import { API_ORIGIN, APP_HOST } from './api-origin';
import {
  LEGACY_TOKEN_KEY,
  SESSION_COOKIE,
  expect,
  makeUser,
  problem,
  readableToken,
  sessionCleared,
  sessionStarted,
  sessionToken,
  test,
  type MockApi,
} from './mock-api';
import type { Page } from '@playwright/test';

const NONCE_KEY = 'godance.oauth_nonce';
const NONCE = '0123456789abcdef0123456789abcdef';

async function signInWithPassword(page: Page) {
  await page.getByLabel('Email').fill('ada@example.com');
  await page.getByLabel('Password').fill('correct-horse-battery');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

function logOutButton(page: Page) {
  return page.getByRole('button', { name: 'Log out' });
}

function storedLegacyToken(page: Page) {
  return page.evaluate((key) => localStorage.getItem(key), LEGACY_TOKEN_KEY);
}

test.describe('the session cookie', () => {
  test('sign in, a protected page and sign out', async ({ page, api }) => {
    api.mockUser()
      .on('POST', '/v1/auth/login', sessionStarted('tok-journey'))
      .on('GET', '/v1/keys', { json: [] })
      .on('POST', '/v1/auth/logout', sessionCleared());

    await page.goto('/keys');
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL('/keys');
    await expect(page.getByText('No keys yet.')).toBeVisible();

    // The browser holds it on the API's host alone, HttpOnly, Secure and Strict.
    expect(await page.context().cookies(API_ORIGIN)).toEqual([expect.objectContaining({
      name: SESSION_COOKIE,
      value: 'tok-journey',
      domain: new URL(API_ORIGIN).hostname,
      path: '/',
      httpOnly: true,
      secure: true,
      sameSite: 'Strict',
    })]);
    expect(await page.context().cookies(`http://${APP_HOST}`)).toEqual([]);
    // And page script has nothing to read.
    expect(await readableToken(page)).toEqual({ cookie: '', storage: [] });

    const protectedCall = api.callsTo('GET', '/v1/keys').at(-1)!;
    expect(protectedCall.headers.cookie).toBe(`${SESSION_COOKIE}=tok-journey`);
    expect(protectedCall.headers.authorization).toBeUndefined();

    await page.reload();
    await expect(page.getByText('No keys yet.')).toBeVisible();

    await logOutButton(page).click();

    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
    const logout = api.callsTo('POST', '/v1/auth/logout');
    expect(logout).toHaveLength(1);
    expect(logout[0].headers.cookie).toBe(`${SESSION_COOKIE}=tok-journey`);
    // What the API's CSRF check reads: the browser sets it and page script can't.
    expect(logout[0].headers.origin).toBe(new URL(page.url()).origin);

    await page.goto('/keys');
    await expect(page).toHaveURL('/login');
  });

  test('a failed sign-out keeps the user signed in and says so', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [] })
      .on('POST', '/v1/auth/logout', problem(503, 'Unavailable'));

    await page.goto('/keys');
    await logOutButton(page).click();

    await expect(page.getByRole('alert')).toHaveText("Couldn't log out. Try again.");
    await expect(page).toHaveURL('/keys');
    expect(await sessionToken(page)).toBe('e2e-session-token');

    api.on('POST', '/v1/auth/logout', sessionCleared());
    await logOutButton(page).click();

    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
  });

  test('signing out a session that had already ended goes to /login', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [] }).rejectSession('/v1/auth/logout', 'POST');

    await page.goto('/keys');
    await logOutButton(page).click();

    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
  });

  test('a slow first answer of "signed out" does not undo a sign-in', async ({ page, api }) => {
    let release = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    let firstCall = true;
    api.on('POST', '/v1/auth/login', sessionStarted('tok-fresh'))
      .on('GET', '/v1/auth/me', async (call) => {
        if (firstCall) {
          firstCall = false;
          await held;
          return problem(401, 'Unauthorized');
        }
        return call.headers.cookie ? { json: makeUser() } : problem(401, 'Unauthorized');
      })
      .on('GET', '/v1/admin/me', problem(403, 'Forbidden'));

    await page.goto('/login');
    await signInWithPassword(page);
    await expect(page).toHaveURL('/dashboard');

    const lateAnswer = page.waitForResponse((res) => res.url().endsWith('/v1/auth/me') && res.status() === 401);
    release();
    await lateAnswer;

    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    await expect(page).toHaveURL('/dashboard');
  });
});

test.describe('a browser signed in before the cookie', () => {
  function adoptsSession(api: MockApi) {
    return api.on('POST', '/v1/auth/session', { ...sessionStarted('tok-legacy'), status: 204, json: undefined });
  }

  test('moves its token into the cookie once and keeps no copy', async ({ page, api }) => {
    await api.seedLegacyToken('tok-legacy');
    adoptsSession(api).mockUser();

    await page.goto('/dashboard');

    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    const adopted = api.callsTo('POST', '/v1/auth/session');
    expect(adopted).toHaveLength(1);
    expect(adopted[0].headers.authorization).toBe('Bearer tok-legacy');
    expect(await sessionToken(page)).toBe('tok-legacy');
    expect(await readableToken(page)).toEqual({ cookie: '', storage: [] });

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/auth/session')).toHaveLength(1);
  });

  test('drops a token the API refuses and asks to sign in', async ({ page, api }) => {
    await api.seedLegacyToken('tok-expired');
    api.on('POST', '/v1/auth/session', problem(401, 'Token expired')).mockUser();

    await page.goto('/dashboard');

    await expect(page).toHaveURL('/login');
    expect(await storedLegacyToken(page)).toBeNull();
  });

  test('keeps the token for the next load when the API is down', async ({ page, api }) => {
    await api.seedLegacyToken('tok-legacy');
    api.on('POST', '/v1/auth/session', problem(503, 'Unavailable')).mockUser();

    await page.goto('/dashboard');

    await expect(page).toHaveURL('/login');
    expect(await storedLegacyToken(page)).toBe('tok-legacy');

    adoptsSession(api);
    await page.goto('/dashboard');

    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(await storedLegacyToken(page)).toBeNull();
  });
});

test.describe('OAuth sign-in', () => {
  async function seedNonce(page: Page) {
    await page.addInitScript(([key, value]) => {
      if (sessionStorage.getItem('e2e.nonce')) return;
      sessionStorage.setItem('e2e.nonce', '1');
      sessionStorage.setItem(key, value);
    }, [NONCE_KEY, NONCE]);
  }

  test('asks the API for the cookie alone', async ({ page, api }) => {
    api.on('GET', '/oauth2/authorization/google', { body: 'provider', contentType: 'text/plain' });

    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue with Google' }).click();

    await expect(page).toHaveURL(/\/oauth2\/authorization\/google/);
    const [start] = api.callsTo('GET', '/oauth2/authorization/google');
    expect(start.query.get('session')).toBe('cookie');
    expect(start.query.get('login_nonce')).toMatch(/^[0-9a-f]{32}$/);
  });

  test('a callback this tab started lands on the dashboard', async ({ page, api }) => {
    await seedNonce(page);
    await api.signIn();

    await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${NONCE}`);

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
  });

  test('a callback from an older app bundle uses the cookie and stores nothing', async ({ page, api }) => {
    await seedNonce(page);
    await api.signIn();

    await page.goto(`/oauth/callback#access_token=tok-old&token_type=Bearer&login_nonce=${NONCE}`);

    await expect(page).toHaveURL('/dashboard');
    expect(page.url()).not.toContain('tok-old');
    expect(await readableToken(page)).toEqual({ cookie: '', storage: [] });
    expect(await sessionToken(page)).toBe('e2e-session-token');
  });

  test('a crafted link is refused', async ({ page, api }) => {
    await seedNonce(page);
    api.mockUser();

    await page.goto('/oauth/callback#token_type=Cookie&login_nonce=someone-elses');

    await expect(page).toHaveURL('/login');
    await expect(page.getByText("wasn't started in this tab")).toBeVisible();
  });

  test('a refused sign-in shows the reason', async ({ page, api }) => {
    await seedNonce(page);
    api.mockUser();

    await page.goto(`/oauth/callback#error=Account+disabled.&login_nonce=${NONCE}`);

    await expect(page).toHaveURL('/login');
    await expect(page.getByText('Account disabled.')).toBeVisible();
  });
});
