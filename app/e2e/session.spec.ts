import { authResponse, expect, problem, storedToken, test } from './mock-api';

const EXPIRED = problem(401, 'Token expired');

test.describe('session expiry', () => {
  test('a 401 on a protected page sends the user to /login, and back after signing in', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', EXPIRED);

    await page.goto('/keys');

    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    expect(await storedToken(page)).toBeNull();

    api.on('POST', '/v1/auth/login', { json: authResponse('tok-fresh') })
      .on('GET', '/v1/keys', { json: [] });
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill('correct-horse-battery');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();

    await expect(page).toHaveURL('/keys');
    await expect(page.getByText('No keys yet.')).toBeVisible();
    expect(api.callsTo('GET', '/v1/keys').at(-1)!.headers.authorization).toBe('Bearer tok-fresh');
  });

  test('a stored token the API no longer accepts sends the user to /login', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/auth/me', EXPIRED);

    await page.goto('/billing');

    await expect(page).toHaveURL('/login');
    expect(await storedToken(page)).toBeNull();
  });

  test('a 401 from the chat stream sends the user to /login', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/models', { json: { object: 'list', data: [] } })
      .on('POST', '/v1/chat/completions', EXPIRED);

    await page.goto('/chat');
    await page.getByPlaceholder('Ask anything').fill('Hello?');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect(page).toHaveURL('/login');
    expect(await storedToken(page)).toBeNull();
  });
});
