import type { Page } from '@playwright/test';
import { MockApi, expect, problem, sessionStarted, test } from './mock-api';
import type { Reply } from './mock-api';

// Mirrors src/lib/referral.tsx, which can't be imported at runtime here.
const STORAGE_KEY = 'godance.referral';
const CODE = 'K7QM2XPD';
const PASSWORD = 'correct-horse-battery';
const DAY_MS = 24 * 60 * 60 * 1000;

const ENABLED = {
  enabled: true,
  code: 'Q4HN7TWE',
  referrer_reward_cents: 100,
  referee_reward_cents: 150,
  min_topup_cents: 10_00,
  invited: 3,
  rewarded: 1,
  earned_cents: 100,
  pending_reward_cents: null,
};

const ACCEPTED: Reply = { json: { referee_reward_cents: 150, min_topup_cents: 10_00 } };

function storedCode(page: Page): Promise<string | null> {
  return page.evaluate((key) => {
    const raw = localStorage.getItem(key);
    return raw === null ? null : (JSON.parse(raw) as { code: string }).code;
  }, STORAGE_KEY);
}

/** A code kept by an earlier visit, `ageDays` ago, put in place before the app's first load only. */
async function seedCode(page: Page, code: string, ageDays = 0): Promise<void> {
  await page.addInitScript(([key, value, expires]) => {
    if (sessionStorage.getItem('e2e.referral-seeded')) return;
    sessionStorage.setItem('e2e.referral-seeded', '1');
    localStorage.setItem(key, JSON.stringify({ code: value, expires }));
  }, [STORAGE_KEY, code, Date.now() + 30 * DAY_MS - ageDays * DAY_MS] as const);
}

async function signUpWithPassword(page: Page): Promise<void> {
  await page.getByLabel('Full name').fill('Ada Lovelace');
  await page.getByLabel('Email').fill('ada@example.com');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByLabel('Birth month').selectOption('December');
  await page.getByLabel('Birth year').selectOption('1990');
  await page.getByRole('button', { name: 'Create account' }).click();
}

async function signInWithPassword(page: Page): Promise<void> {
  await page.getByLabel('Email').fill('ada@example.com');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

test.describe('an invite link', () => {
  test('a password sign-up from it hands the code in once, then forgets it', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201)).mockUser()
      .on('POST', '/v1/referrals/claim', ACCEPTED);

    await page.goto(`/signup?ref=${CODE.toLowerCase()}`);
    await expect.poll(() => storedCode(page)).toBe(CODE);
    await signUpWithPassword(page);

    await expect(page).toHaveURL('/dashboard');
    await expect.poll(() => api.callsTo('POST', '/v1/referrals/claim').length).toBe(1);
    expect(api.callsTo('POST', '/v1/referrals/claim')[0].body).toEqual({ code: CODE });
    await expect.poll(() => storedCode(page)).toBeNull();

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/referrals/claim')).toHaveLength(1);
  });

  test('a social sign-in from it hands the code in after the round trip', async ({ page, api }) => {
    api.on('GET', '/oauth2/authorization/google', { body: 'provider', contentType: 'text/plain' })
      .on('POST', '/v1/referrals/claim', ACCEPTED);

    await page.goto(`/login?ref=${CODE}`);
    await expect.poll(() => storedCode(page)).toBe(CODE);
    await page.getByRole('button', { name: 'Continue with Google' }).click();
    await expect(page).toHaveURL(/\/oauth2\/authorization\/google/);
    const nonce = api.callsTo('GET', '/oauth2/authorization/google')[0].query.get('login_nonce');

    // The provider sends the browser back with the session cookie set.
    await api.signIn();
    await page.goto(`/oauth/callback#token_type=Cookie&login_nonce=${nonce}`);

    await expect(page).toHaveURL('/dashboard');
    await expect.poll(() => api.callsTo('POST', '/v1/referrals/claim').length).toBe(1);
    expect(api.callsTo('POST', '/v1/referrals/claim')[0].body).toEqual({ code: CODE });
    await expect.poll(() => storedCode(page)).toBeNull();
  });

  test('a sign-in in another tab hands the code in from there, once', async ({ page, api }) => {
    api.mockUser().on('POST', '/v1/referrals/claim', ACCEPTED);
    await page.goto(`/signup?ref=${CODE}`);
    await expect.poll(() => storedCode(page)).toBe(CODE);

    const other = await page.context().newPage();
    const otherApi = new MockApi(other);
    await otherApi.install();
    otherApi.on('POST', '/v1/auth/login', sessionStarted('tok-other-tab')).mockUser()
      .on('POST', '/v1/referrals/claim', ACCEPTED);
    await other.goto('/login');
    await signInWithPassword(other);

    await expect(other).toHaveURL('/dashboard');
    await expect.poll(() => otherApi.callsTo('POST', '/v1/referrals/claim').length).toBe(1);
    expect(otherApi.callsTo('POST', '/v1/referrals/claim')[0].body).toEqual({ code: CODE });
    await expect.poll(() => storedCode(page)).toBeNull();

    // The first tab, now signed in too, has nothing left to hand in.
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/referrals/claim')).toHaveLength(0);
    expect(otherApi.unhandled).toEqual([]);
    await other.close();
  });

  test('a stored code never changes where sign-in lands', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', sessionStarted('tok-login')).mockUser()
      .on('POST', '/v1/referrals/claim', ACCEPTED);

    await page.goto(`/login?ref=${CODE}`);
    await expect.poll(() => storedCode(page)).toBe(CODE);
    await page.goto('/billing');                        // signed out: sent to sign in first
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL('/billing');
    await expect.poll(() => api.callsTo('POST', '/v1/referrals/claim').length).toBe(1);
    await expect(page).toHaveURL('/billing');
  });

  for (const [answer, reply] of [
    ['accepted', ACCEPTED],
    ['an unknown code', problem(404, "That invite code isn't valid.", 'referral_code_unknown')],
    ['a code this account cannot use', problem(409, 'This account has already used an invite code.', 'referral_not_eligible')],
    ['a server error', problem(500, 'Internal Server Error')],
  ] as const) {
    test(`the code is forgotten after ${answer}, and nothing is shown`, async ({ page, api }) => {
      await seedCode(page, CODE);
      await api.signIn();
      api.on('POST', '/v1/referrals/claim', reply);

      await page.goto('/dashboard');

      await expect.poll(() => api.callsTo('POST', '/v1/referrals/claim').length).toBe(1);
      await expect.poll(() => storedCode(page)).toBeNull();
      await expect(page).toHaveURL('/dashboard');
      await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    });
  }

  test('a ref that is not a code is not kept', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', sessionStarted('tok-signup', 201)).mockUser();

    await page.goto('/signup?ref=K7QM2XP0');            // a zero is never in a code
    await signUpWithPassword(page);

    await expect(page).toHaveURL('/dashboard');
    expect(await storedCode(page)).toBeNull();
    expect(api.callsTo('POST', '/v1/referrals/claim')).toHaveLength(0);
  });

  test('a code kept for more than 30 days is dropped unused', async ({ page, api }) => {
    await seedCode(page, CODE, 31);
    await api.signIn();

    await page.goto('/dashboard');

    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)).toBeNull();
    expect(api.callsTo('POST', '/v1/referrals/claim')).toHaveLength(0);
  });
});

test.describe('the referral page', () => {
  test('with referrals off, there is no Invite entry and /referral leads to the dashboard', async ({ page, api }) => {
    await api.signIn();

    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Hi, Ada.' })).toBeVisible();
    await expect.poll(() => api.callsTo('GET', '/v1/referrals/me').length).toBeGreaterThan(0);
    await expect(page.locator('header nav a[href="/referral"]')).toHaveCount(0);

    await page.goto('/referral');
    await expect(page).toHaveURL('/dashboard');
  });

  test('with referrals on, it shows the link, the offer, the counts and the terms', async ({ page, api }) => {
    await page.addInitScript(() => {
      // Record what Copy puts on the clipboard, without the browser's permission prompt.
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: async (text: string) => { (window as unknown as { copied?: string }).copied = text; } },
      });
    });
    await api.signIn();
    api.on('GET', '/v1/referrals/me', { json: ENABLED });

    await page.goto('/dashboard');
    // Invite comes after Billing, in the header row or, on a phone, the row below it.
    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link')).toHaveText(
      ['Dashboard', 'Chat', 'Create', 'History', 'API keys', 'Billing', 'Invite', 'Settings']);
    // The extra entry doesn't push the page sideways, phone included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await nav.getByRole('link', { name: 'Invite' }).click();
    await expect(page).toHaveURL('/referral');

    await expect(page.getByRole('heading', { name: 'Invite friends' })).toBeVisible();
    const link = `${new URL(page.url()).origin}/signup?ref=${ENABLED.code}`;
    await expect(page.getByLabel('Your invite link')).toHaveValue(link);
    await expect(page.getByText('first top-up of $10.00 or more, you get $1.00 and they get $1.50 in credit')).toBeVisible();
    await expect(page.getByText('Invited', { exact: true }).locator('..')).toContainText('3');
    await expect(page.getByText('Rewarded', { exact: true }).locator('..')).toContainText('1');
    await expect(page.getByText('Earned', { exact: true }).locator('..')).toContainText('$1.00');
    await expect(page.getByRole('heading', { name: 'Terms' })).toBeVisible();
    await expect(page.getByText("We don't tell you who signed up with your link.")).toBeVisible();

    await page.getByRole('button', { name: 'Copy' }).click();
    await expect(page.getByRole('button', { name: 'Copied' })).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { copied?: string }).copied)).toBe(link);

    // Opened directly, it waits for the answer instead of leaving.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Invite friends' })).toBeVisible();
    await expect(page).toHaveURL('/referral');
  });
});

test.describe('the Billing page', () => {
  test('an invited account sees what it gets, and on what top-up', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/referrals/me', { json: { ...ENABLED, pending_reward_cents: 150 } });

    await page.goto('/billing');

    await expect(page.getByText('You were invited: your first card top-up of $10.00 or more adds $1.50 to your balance.'))
      .toBeVisible();
  });

  test('an account that was not invited sees no such line', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/referrals/me', { json: ENABLED });

    await page.goto('/billing');

    await expect(page.getByText('Current balance: $12.34')).toBeVisible();
    await expect.poll(() => api.callsTo('GET', '/v1/referrals/me').length).toBeGreaterThan(0);
    await expect(page.getByText('You were invited')).toHaveCount(0);
  });

  test('with referrals off there is no such line either', async ({ page, api }) => {
    await api.signIn();

    await page.goto('/billing');

    await expect(page.getByText('Current balance: $12.34')).toBeVisible();
    await expect.poll(() => api.callsTo('GET', '/v1/referrals/me').length).toBeGreaterThan(0);
    await expect(page.getByText('You were invited')).toHaveCount(0);
  });
});
