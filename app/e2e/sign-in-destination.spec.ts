import type { Page } from '@playwright/test';
import { expect, makeUser, sessionStarted, sessionToken, test, type MockApi } from './mock-api';
import { fillSignUp, signInWithPassword, socialProvider, submitCode } from './sign-in';

// Recipe links, and the line the sign-in pages show for them, are in
// gallery-hand-off.spec.ts.

// Mirror src/lib/oauth.ts and src/lib/return-to.ts, which can't be imported at
// runtime here: they read the page's sessionStorage and location.
const NONCE_KEY = 'godance.oauth_nonce';
const KEPT_KEY = 'godance.return_to';
const NONCE = '0123456789abcdef0123456789abcdef';
const MINUTE = 60_000;

// A page that needs sign-in, with a query, so the whole destination is checked.
const DESTINATION = '/keys?view=all';

const KID_YEAR = new Date().getUTCFullYear() - 12;
const WAITING_KID = makeUser({
  fullName: 'Kim Lee',
  email: 'kim@example.com',
  emailVerified: false,
  creditBalanceCents: 0,
  ageStatus: 'CONSENT_PENDING',
  parentEmailMasked: 'p***@family.test',
});

/** Answers the destination page's own request, and /me once the session cookie is there. */
function destinationPage(api: MockApi, user = makeUser()): MockApi {
  return api.on('GET', '/v1/keys', { json: [] }).mockUser(user);
}

/** What the login page keeps for a social sign-in, with the age given. */
function kept(path: string, ageMinutes = 0): string {
  return JSON.stringify({ path, savedAt: Date.now() - ageMinutes * MINUTE });
}

/** Puts this tab's sign-in nonce, and optionally a kept destination, where the login page leaves them. Needs an app page open. */
async function seedSocialSignIn(page: Page, keptValue?: string) {
  await page.evaluate(([nonceKey, nonce, keptKey, value]) => {
    sessionStorage.setItem(nonceKey, nonce);
    if (value !== '') sessionStorage.setItem(keptKey, value);
  }, [NONCE_KEY, NONCE, KEPT_KEY, keptValue ?? '']);
}

function keptDestination(page: Page) {
  return page.evaluate((key) => sessionStorage.getItem(key), KEPT_KEY);
}

/** Every name a crafted link might try for a destination. */
function redirectParams(path: string): string {
  return new URLSearchParams({ from: path, next: path, redirect: path, returnTo: path, return_to: path }).toString();
}

test.describe('the page that asked for sign-in', () => {
  test('a password sign-in returns to it', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/login', sessionStarted('tok-login'));

    await page.goto(DESTINATION);
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL(DESTINATION);
  });

  test('the link to sign-up keeps it, and the new account lands there', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201));

    await page.goto(DESTINATION);
    await page.getByRole('link', { name: 'Create an account' }).click();
    await expect(page).toHaveURL('/signup');
    await fillSignUp(page);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page).toHaveURL(DESTINATION);
    expect(await sessionToken(page)).toBe('tok-signup');
  });

  test("so does the header's sign-up button", async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201));

    await page.goto(DESTINATION);
    await page.getByRole('link', { name: 'Start free' }).click();
    await expect(page).toHaveURL('/signup');
    await fillSignUp(page);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page).toHaveURL(DESTINATION);
  });

  test('going back from sign-up to sign-in keeps it', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/login', sessionStarted('tok-login'));

    await page.goto(DESTINATION);
    await page.getByRole('link', { name: 'Create an account' }).click();
    await page.getByText('Already have one?').getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL(DESTINATION);
  });

  test('a social sign-in comes back to it, and uses it up', async ({ page, api, baseURL }) => {
    socialProvider(destinationPage(api), baseURL!, 'signed in');

    await page.goto(DESTINATION);
    await page.getByRole('button', { name: 'Continue with Google' }).click();

    await expect(page).toHaveURL(DESTINATION);
    expect(await sessionToken(page)).toBe('tok-social');
    expect(await keptDestination(page)).toBeNull();
  });

  test('a social sign-in that asks for a code comes back to it after the code', async ({ page, api, baseURL }) => {
    socialProvider(destinationPage(api), baseURL!, 'two-factor')
      .on('POST', '/v1/auth/2fa/verify', sessionStarted('tok-after-code'));

    await page.goto(DESTINATION);
    await page.getByRole('button', { name: 'Continue with Google' }).click();
    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    await expect(page).toHaveURL('/login');
    expect(await sessionToken(page)).toBeNull();
    await submitCode(page, '123456');

    await expect(page).toHaveURL(DESTINATION);
    expect(api.callsTo('POST', '/v1/auth/2fa/verify')[0].headers.authorization).toBe('Bearer challenge-social');
  });

  test('a refused social sign-in keeps it for the next try', async ({ page, api, baseURL }) => {
    socialProvider(destinationPage(api), baseURL!, { error: 'Google sign-in was cancelled.' })
      .on('POST', '/v1/auth/login', sessionStarted('tok-login'));

    await page.goto(DESTINATION);
    await page.getByRole('button', { name: 'Continue with Google' }).click();
    await expect(page.getByText('Google sign-in was cancelled.')).toBeVisible();
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL(DESTINATION);
  });

  test("an account waiting for a parent's approval gets the waiting screen there", async ({ page, api }) => {
    destinationPage(api, WAITING_KID).on('POST', '/v1/auth/signup', sessionStarted('tok-kid', 201));

    await page.goto(DESTINATION);
    await page.getByRole('link', { name: 'Create an account' }).click();
    await fillSignUp(page, { month: 'June', year: String(KID_YEAR) });
    await page.getByLabel("Parent or guardian's email").fill('parent@family.test');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByRole('heading', { name: "Waiting for your parent's approval" })).toBeVisible();
    await expect(page).toHaveURL(DESTINATION);
    expect(api.callsTo('GET', '/v1/keys')).toEqual([]);
  });
});

test.describe('nothing else sets where sign-in goes', () => {
  test('no URL parameter on the sign-in page', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/login', sessionStarted('tok-login'));

    await page.goto(`/login?${redirectParams('/keys')}`);
    await signInWithPassword(page);

    await expect(page).toHaveURL('/dashboard');
  });

  test('no URL parameter on the sign-up page', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201));

    await page.goto(`/signup?${redirectParams('/keys')}`);
    await fillSignUp(page);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page).toHaveURL('/dashboard');
  });

  test('no URL parameter on the way back from a social sign-in', async ({ page, api }) => {
    destinationPage(api);
    await api.signIn();

    await page.goto('/login');
    await seedSocialSignIn(page);
    await page.goto(`/oauth/callback?${redirectParams('/keys')}#token_type=Cookie&login_nonce=${NONCE}&${redirectParams('/keys')}`);

    await expect(page).toHaveURL('/dashboard');
  });
});

test.describe('a destination kept for a social sign-in', () => {
  test('is followed while it is under 30 minutes old', async ({ page, api }) => {
    destinationPage(api);
    await api.signIn();

    await page.goto('/login');
    await seedSocialSignIn(page, kept(DESTINATION, 29));
    await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${NONCE}`);

    await expect(page).toHaveURL(DESTINATION);
  });

  const IGNORED: { when: string; value: () => string }[] = [
    { when: 'it points off the app', value: () => kept('https://evil.example/keys') },
    { when: 'it starts with //', value: () => kept('//evil.example/keys') },
    { when: 'it starts with /\\', value: () => kept('/\\evil.example/keys') },
    { when: 'it is more than 30 minutes old', value: () => kept(DESTINATION, 31) },
    { when: 'it is dated in the future', value: () => kept(DESTINATION, -10) },
    { when: 'it is not what the app keeps', value: () => DESTINATION },
  ];
  for (const { when, value } of IGNORED) {
    test(`is ignored when ${when}`, async ({ page, api }) => {
      destinationPage(api);
      await api.signIn();

      await page.goto('/login');
      await seedSocialSignIn(page, value());
      await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${NONCE}`);

      await expect(page).toHaveURL('/dashboard');
      expect(await keptDestination(page)).toBeNull();
    });
  }

  test('is used once', async ({ page, api }) => {
    destinationPage(api);
    await api.signIn();

    await page.goto('/login');
    await seedSocialSignIn(page, kept(DESTINATION));
    await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${NONCE}`);
    await expect(page).toHaveURL(DESTINATION);

    // The same way back again, with a nonce but nothing kept any more.
    await seedSocialSignIn(page);
    await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${NONCE}`);
    await expect(page).toHaveURL('/dashboard');
  });

  test('is not followed by a callback this tab did not start, which uses it up', async ({ page, api }) => {
    destinationPage(api).on('POST', '/v1/auth/login', sessionStarted('tok-login'));

    await page.goto('/login');
    await seedSocialSignIn(page, kept(DESTINATION));
    await page.goto('/oauth/callback#token_type=Cookie&login_nonce=someone-elses');
    await expect(page.getByText("wasn't started in this tab")).toBeVisible();
    expect(await keptDestination(page)).toBeNull();
    await signInWithPassword(page);

    await expect(page).toHaveURL('/dashboard');
  });

  test('is forgotten when a social sign-in starts without a destination', async ({ page, api, baseURL }) => {
    socialProvider(destinationPage(api), baseURL!, 'signed in');

    await page.goto('/login');
    // Left behind by an earlier attempt that never came back.
    await page.evaluate(([key, value]) => sessionStorage.setItem(key, value), [KEPT_KEY, kept(DESTINATION)]);
    await page.getByRole('button', { name: 'Continue with Google' }).click();

    await expect(page).toHaveURL('/dashboard');
  });
});
