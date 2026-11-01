import { SESSION_COOKIE, expect, sessionStarted, sessionToken, test } from './mock-api';

test.describe('session expiry', () => {
  test('a 401 on a protected page sends the user to /login, and back after signing in', async ({ page, api }) => {
    await api.signIn();
    api.rejectSession('/v1/keys');

    await page.goto('/keys');

    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    expect(await sessionToken(page)).toBeNull();

    api.on('POST', '/v1/auth/login', sessionStarted('tok-fresh'))
      .on('GET', '/v1/keys', { json: [] });
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill('correct-horse-battery');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();

    await expect(page).toHaveURL('/keys');
    await expect(page.getByText('No keys yet.')).toBeVisible();
    expect(api.callsTo('GET', '/v1/keys').at(-1)!.headers.cookie).toBe(`${SESSION_COOKIE}=tok-fresh`);
  });

  test('a session the API no longer accepts sends the user to /login', async ({ page, api }) => {
    await api.signIn();
    api.rejectSession('/v1/auth/me');

    await page.goto('/billing');

    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
  });

  test('a 401 from the chat stream sends the user to /login', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/models', { json: { object: 'list', data: [] } })
      .rejectSession('/v1/chat/completions', 'POST');

    await page.goto('/chat');
    await page.getByPlaceholder('Ask anything').fill('Hello?');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
    expect(api.callsTo('POST', '/v1/chat/completions')[0].headers.cookie).toBe(`${SESSION_COOKIE}=e2e-session-token`);
  });
});
