import { authResponse, expect, makeUser, problem, storedToken, test } from './mock-api';

const PASSWORD = 'correct-horse-battery';

test.describe('signup', () => {
  test('creates the account and lands signed in on the dashboard', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', { status: 201, json: authResponse('tok-signup') }).mockUser();

    await page.goto('/signup');
    await page.getByLabel('Full name').fill('Ada Lovelace');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(await storedToken(page)).toBe('tok-signup');
    const [signup] = api.callsTo('POST', '/v1/auth/signup');
    expect(signup.body).toEqual({ email: 'ada@example.com', password: PASSWORD, fullName: 'Ada Lovelace' });
    expect(signup.headers.authorization).toBeUndefined();
    expect(api.callsTo('GET', '/v1/auth/me')[0].headers.authorization).toBe('Bearer tok-signup');
  });

  test('a taken email shows the error and offers a release link', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', problem(409, 'An account with this email already exists.'))
      .on('POST', '/v1/auth/email/release-link', { status: 202 });

    await page.goto('/signup');
    await page.getByLabel('Full name').fill('Ada Lovelace');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByText('An account with this email already exists.')).toBeVisible();
    await expect(page).toHaveURL('/signup');
    expect(await storedToken(page)).toBeNull();

    await page.getByRole('button', { name: 'Email me a release link' }).click();
    await expect(page.getByRole('status')).toContainText("we've emailed it a link");
    expect(api.callsTo('POST', '/v1/auth/email/release-link')[0].body).toEqual({ email: 'ada@example.com' });
  });
});

test.describe('login', () => {
  test('signs in and lands on the dashboard', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: authResponse('tok-login') }).mockUser();

    await page.goto('/login');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(await storedToken(page)).toBe('tok-login');
    expect(api.callsTo('POST', '/v1/auth/login')[0].body).toEqual({ email: 'ada@example.com', password: PASSWORD });
  });

  test('a wrong password shows the error and stays on /login', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', problem(401, 'Invalid email or password.'));

    await page.goto('/login');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill('wrong-password-123');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();

    await expect(page.getByText('Invalid email or password.')).toBeVisible();
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeEnabled();
    expect(await storedToken(page)).toBeNull();
    expect(api.callsTo('GET', '/v1/auth/me')).toHaveLength(0);
  });
});

test.describe('password reset', () => {
  test('requests a reset link from the login page', async ({ page, api }) => {
    api.on('POST', '/v1/auth/password/forgot', { status: 202 });

    await page.goto('/login');
    await page.getByRole('link', { name: 'Forgot password?' }).click();
    await expect(page).toHaveURL('/forgot-password');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByRole('button', { name: 'Email me a reset link' }).click();

    await expect(page.getByText("we've emailed it a reset link")).toBeVisible();
    expect(api.callsTo('POST', '/v1/auth/password/forgot')[0].body).toEqual({ email: 'ada@example.com' });
  });

  test('sets a new password from the emailed link and signs in', async ({ page, api }) => {
    api.on('POST', '/v1/auth/password/reset', { json: authResponse('tok-after-reset') }).mockUser();

    await page.goto('/reset-password?token=rst-valid');
    await page.getByLabel('New password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Reset password' }).click();

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(await storedToken(page)).toBe('tok-after-reset');
    expect(api.callsTo('POST', '/v1/auth/password/reset')[0].body).toEqual({ token: 'rst-valid', newPassword: PASSWORD });
  });

  test('an expired reset link shows the error and keeps the user signed out', async ({ page, api }) => {
    api.on('POST', '/v1/auth/password/reset', problem(400, 'This reset link is invalid or has expired.'));

    await page.goto('/reset-password?token=rst-expired');
    await page.getByLabel('New password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Reset password' }).click();

    await expect(page.getByText('This reset link is invalid or has expired.')).toBeVisible();
    await expect(page).toHaveURL('/reset-password?token=rst-expired');
    expect(await storedToken(page)).toBeNull();
  });

  test('a link without a token asks for a new one', async ({ page }) => {
    await page.goto('/reset-password');

    await expect(page.getByText('Missing reset token.')).toBeVisible();
    await page.getByRole('link', { name: 'Request a new link' }).click();
    await expect(page).toHaveURL('/forgot-password');
  });
});

test.describe('email verification', () => {
  test('verifies the signed-in account once', async ({ page, api }) => {
    await api.signIn(makeUser({ emailVerified: false }));
    api.on('GET', '/v1/auth/verify', { status: 204 });

    await page.goto('/verify-email?token=vfy-valid');

    await expect(page.getByRole('heading', { name: 'Email verified.' })).toBeVisible();
    const verify = api.callsTo('GET', '/v1/auth/verify');
    expect(verify).toHaveLength(1);   // the token is single-use
    expect(verify[0].query.get('token')).toBe('vfy-valid');
    expect(verify[0].headers.authorization).toBe('Bearer e2e-session-token');
  });

  test('an expired link shows why it failed', async ({ page, api }) => {
    await api.signIn(makeUser({ emailVerified: false }));
    api.on('GET', '/v1/auth/verify', problem(400, 'This verification link has expired.'));

    await page.goto('/verify-email?token=vfy-expired');

    await expect(page.getByRole('heading', { name: "Couldn't verify." })).toBeVisible();
    await expect(page.getByText('This verification link has expired.')).toBeVisible();
    // The release offer is only for a 403 (link belongs to another account).
    await expect(page.getByRole('button', { name: 'Release this address' })).toHaveCount(0);
  });

  test('signed out, it asks to sign in and verifies right after', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: authResponse('tok-login') })
      .mockUser(makeUser({ emailVerified: false }))
      .on('GET', '/v1/auth/verify', { status: 204 });

    await page.goto('/verify-email?token=vfy-valid');
    await expect(page.getByRole('heading', { name: 'Sign in to verify your email.' })).toBeVisible();
    expect(api.callsTo('GET', '/v1/auth/verify')).toHaveLength(0);

    await page.getByRole('main').getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL('/login');
    await page.getByLabel('Email').fill('ada@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();

    await expect(page).toHaveURL('/verify-email?token=vfy-valid');
    await expect(page.getByRole('heading', { name: 'Email verified.' })).toBeVisible();
    expect(api.callsTo('GET', '/v1/auth/verify')[0].headers.authorization).toBe('Bearer tok-login');
  });
});
