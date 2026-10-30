import type { Page } from '@playwright/test';
import { authResponse, expect, makeUser, problem, storedToken, test, type MockApi } from './mock-api';

const PASSWORD = 'correct-horse-battery';
const CHALLENGE = { twoFactorToken: 'challenge-1', expiresAt: '2030-01-01T00:00:00Z' };
const ENROLMENT = {
  secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
  otpauthUri: 'otpauth://totp/Godance:ada%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP'
    + '&issuer=Godance&algorithm=SHA1&digits=6&period=30',
};
const RECOVERY_CODES = Array.from({ length: 10 }, (_, i) => `AAAA-BBBB-CCCC-DDD${i}`);
const WRONG_CODE = problem(403, "That code isn't right, or has already been used.");

async function submitPassword(page: Page) {
  await page.getByLabel('Email').fill('ada@example.com');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

async function submitCode(page: Page, code: string) {
  await page.getByLabel('Code').fill(code);
  await page.getByRole('button', { name: 'Continue' }).click();
}

test.describe('signing in with two-factor on', () => {
  test('the password alone asks for a code, and the code signs in', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-after-code') })
      .mockUser();

    await page.goto('/login');
    await submitPassword(page);

    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    // No session yet: nothing stored, and /me was never called.
    expect(await storedToken(page)).toBeNull();
    expect(api.callsTo('GET', '/v1/auth/me')).toEqual([]);

    await submitCode(page, '123456');

    await expect(page).toHaveURL('/dashboard');
    expect(await storedToken(page)).toBe('tok-after-code');
    const [verify] = api.callsTo('POST', '/v1/auth/2fa/verify');
    expect(verify.headers.authorization).toBe('Bearer challenge-1');
    expect(verify.body).toEqual({ code: '123456' });
  });

  test('a recovery code goes in the same box', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-recovery') })
      .mockUser();

    await page.goto('/login');
    await submitPassword(page);
    await submitCode(page, 'AAAA-BBBB-CCCC-DDD0');

    await expect(page).toHaveURL('/dashboard');
    expect(api.callsTo('POST', '/v1/auth/2fa/verify')[0].body).toEqual({ code: 'AAAA-BBBB-CCCC-DDD0' });
  });

  test('a wrong code shows why, clears the box and leaves the step open', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', WRONG_CODE);

    await page.goto('/login');
    await submitPassword(page);
    await submitCode(page, '000000');

    await expect(page.getByRole('alert')).toContainText("That code isn't right");
    await expect(page.getByLabel('Code')).toHaveValue('');
    await expect(page).toHaveURL('/login');
    expect(await storedToken(page)).toBeNull();

    api.on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-second-try') }).mockUser();
    await submitCode(page, '123456');
    await expect(page).toHaveURL('/dashboard');
  });

  test('too many wrong codes says how long to wait', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', problem(429, 'too many requests, try again in 15 minutes'));

    await page.goto('/login');
    await submitPassword(page);
    await submitCode(page, '000000');

    await expect(page.getByRole('alert')).toContainText('try again in 15 minutes');
    expect(await storedToken(page)).toBeNull();
  });

  test('an expired challenge goes back to the password', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', { status: 401 });

    await page.goto('/login');
    await submitPassword(page);
    await submitCode(page, '123456');

    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByText('That took too long. Sign in again.')).toBeVisible();
    await expect(page.getByLabel('Password')).toHaveValue('');
  });

  test('without two-factor, signing in stays one step', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: authResponse('tok-plain') }).mockUser();

    await page.goto('/login');
    await submitPassword(page);

    await expect(page).toHaveURL('/dashboard');
    expect(api.callsTo('POST', '/v1/auth/2fa/verify')).toEqual([]);
  });

  test('it returns to the page that asked for sign-in', async ({ page, api }) => {
    api.on('POST', '/v1/auth/login', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-after-code') })
      .on('GET', '/v1/keys', { json: [] })
      .on('GET', '/v1/auth/me', (call) => (call.headers.authorization
        ? { json: makeUser({ twoFactorEnabled: true }) }
        : problem(401, 'Unauthorized')))
      .on('GET', '/v1/admin/me', problem(403, 'Forbidden'));

    await page.goto('/keys');
    await expect(page).toHaveURL('/login');
    await submitPassword(page);
    await submitCode(page, '123456');

    await expect(page).toHaveURL('/keys');
  });
});

test.describe('the other ways in', () => {
  test('a social sign-in with two-factor on asks for the code', async ({ page, api }) => {
    api.on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-social') }).mockUser();
    await page.addInitScript(() => {
      if (!sessionStorage.getItem('e2e.nonce-set')) {
        sessionStorage.setItem('e2e.nonce-set', '1');
        sessionStorage.setItem('godance.oauth_nonce', 'nonce-1');
      }
    });

    await page.goto('/oauth/callback#two_factor_token=challenge-2&expires_at=2030-01-01T00%3A00%3A00Z&login_nonce=nonce-1');

    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    await expect(page).toHaveURL('/login');
    expect(await storedToken(page)).toBeNull();

    await submitCode(page, '123456');

    await expect(page).toHaveURL('/dashboard');
    expect(api.callsTo('POST', '/v1/auth/2fa/verify')[0].headers.authorization).toBe('Bearer challenge-2');
  });

  test('a challenge this tab did not ask for is refused', async ({ page }) => {
    await page.goto('/oauth/callback#two_factor_token=challenge-from-a-link&login_nonce=nonce-1');

    await expect(page).toHaveURL('/login');
    await expect(page.getByText("wasn't started in this tab")).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });

  test('a password reset leaves two-factor on and asks for the code', async ({ page, api }) => {
    api.on('POST', '/v1/auth/password/reset', { json: CHALLENGE })
      .on('POST', '/v1/auth/2fa/verify', { json: authResponse('tok-after-reset') })
      .mockUser();

    await page.goto('/reset-password?token=reset-1');
    await page.getByLabel('New password').fill('a-brand-new-password');
    await page.getByRole('button', { name: 'Reset password' }).click();

    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    expect(await storedToken(page)).toBeNull();

    await submitCode(page, '123456');

    await expect(page).toHaveURL('/dashboard');
    expect(await storedToken(page)).toBe('tok-after-reset');
  });
});

/** Answers /me and the admin probe from `state`, so a change shows up as the real API's would. */
function mockAccount(api: MockApi, state: { twoFactorEnabled: boolean }, overrides = {}) {
  api.on('GET', '/v1/auth/me', () => ({ json: makeUser({ ...overrides, ...state }) }))
    .on('GET', '/v1/webhooks/secret', { json: { secret: 'whsec_test', header: 'X-Godance-Signature' } });
}

test.describe('turning two-factor on', () => {
  test('shows a QR code and the key, confirms a code and gives recovery codes once', async ({ page, api }) => {
    const account = { twoFactorEnabled: false };
    await api.signIn(makeUser(), 'tok-before');
    mockAccount(api, account);
    api.on('POST', '/v1/auth/2fa/enrol', { json: ENROLMENT })
      .on('POST', '/v1/auth/2fa/enable', (call) => {
        if ((call.body as { code: string }).code === '000000') return WRONG_CODE;
        account.twoFactorEnabled = true;
        return { json: { recoveryCodes: RECOVERY_CODES, session: authResponse('tok-after') } };
      })
      .on('GET', '/v1/auth/2fa', { json: { enabled: true, recoveryCodesRemaining: 10 } });

    await page.goto('/settings');
    const section = page.locator('section', { hasText: 'Two-factor sign-in' });
    await section.getByRole('button', { name: 'Turn on' }).click();
    await section.getByLabel('Current password').fill(PASSWORD);
    await section.getByRole('button', { name: 'Continue' }).click();

    await expect(section.getByRole('img', { name: 'QR code for your authenticator app' })).toBeVisible();
    await expect(section.getByText(ENROLMENT.secret)).toBeVisible();
    expect(api.callsTo('POST', '/v1/auth/2fa/enrol')[0].body).toEqual({ currentPassword: PASSWORD });

    await section.getByLabel('6-digit code').fill('000000');
    await section.getByRole('button', { name: 'Turn on' }).click();
    await expect(section.getByRole('alert')).toContainText("That code isn't right");

    await section.getByLabel('6-digit code').fill('123456');
    await section.getByRole('button', { name: 'Turn on' }).click();

    const codes = section.getByRole('list', { name: 'Recovery codes' });
    await expect(codes.getByRole('listitem')).toHaveCount(10);
    await expect(codes).toContainText(RECOVERY_CODES[0]);
    // Turning it on ended the old session; the replacement is what's stored.
    expect(await storedToken(page)).toBe('tok-after');

    await section.getByRole('button', { name: "I've saved them" }).click();

    await expect(codes).toHaveCount(0);
    await expect(section).toContainText('On.');
    await expect(section).toContainText('10 recovery codes left.');
    expect(api.callsTo('GET', '/v1/auth/2fa').at(-1)!.headers.authorization).toBe('Bearer tok-after');
  });

  test('a wrong password stops before any secret is shown', async ({ page, api }) => {
    await api.signIn();
    mockAccount(api, { twoFactorEnabled: false });
    api.on('POST', '/v1/auth/2fa/enrol', problem(403, 'Your current password is incorrect.'));

    await page.goto('/settings');
    const section = page.locator('section', { hasText: 'Two-factor sign-in' });
    await section.getByRole('button', { name: 'Turn on' }).click();
    await section.getByLabel('Current password').fill('not-the-password');
    await section.getByRole('button', { name: 'Continue' }).click();

    await expect(section.getByRole('alert')).toContainText('Your current password is incorrect.');
    await expect(section.getByRole('img')).toHaveCount(0);
  });

  test('an account without a password is not asked for one', async ({ page, api }) => {
    const social = makeUser({ hasPassword: false });
    await api.signIn(social);
    mockAccount(api, { twoFactorEnabled: false }, { hasPassword: false });
    api.on('POST', '/v1/auth/2fa/enrol', { json: ENROLMENT });

    await page.goto('/settings');
    const section = page.locator('section', { hasText: 'Two-factor sign-in' });
    await section.getByRole('button', { name: 'Turn on' }).click();
    await expect(section.getByLabel('Current password')).toHaveCount(0);
    await expect(section).toContainText('within 10 minutes of signing in');
    await section.getByRole('button', { name: 'Continue' }).click();

    await expect(section.getByRole('img', { name: 'QR code for your authenticator app' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/auth/2fa/enrol')[0].body).toEqual({});
  });
});

test.describe('turning two-factor off', () => {
  test('needs the password and a current code', async ({ page, api }) => {
    const account = { twoFactorEnabled: true };
    await api.signIn(makeUser(account));
    mockAccount(api, account);
    api.on('GET', '/v1/auth/2fa', { json: { enabled: true, recoveryCodesRemaining: 1 } })
      .on('POST', '/v1/auth/2fa/disable', (call) => {
        if ((call.body as { code: string }).code === '000000') return WRONG_CODE;
        account.twoFactorEnabled = false;
        return { status: 204 };
      });

    await page.goto('/settings');
    const section = page.locator('section', { hasText: 'Two-factor sign-in' });
    await expect(section).toContainText('1 recovery code left.');

    await section.getByRole('button', { name: 'Turn off' }).click();
    await section.getByLabel('Current password').fill(PASSWORD);
    await section.getByLabel('Code').fill('000000');
    await section.getByRole('button', { name: 'Turn off' }).click();
    await expect(section.getByRole('alert')).toContainText("That code isn't right");
    await expect(section.getByLabel('Code')).toBeVisible();

    await section.getByLabel('Code').fill('123456');
    await section.getByRole('button', { name: 'Turn off' }).click();

    await expect(section.getByRole('button', { name: 'Turn on' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/auth/2fa/disable').at(-1)!.body)
      .toEqual({ currentPassword: PASSWORD, code: '123456' });
  });
});

test.describe('the admin console', () => {
  const NEEDS_2FA = {
    status: 403,
    contentType: 'application/problem+json',
    json: {
      type: 'about:blank', title: 'Forbidden', status: 403,
      detail: 'Turn on two-factor sign-in to use the admin console.', code: 'two_factor_required',
    },
  };

  test('an admin without two-factor is sent to set it up, and the console opens after', async ({ page, api }) => {
    const account = { twoFactorEnabled: false };
    await api.signIn();
    mockAccount(api, account);
    api.on('GET', '/v1/admin/me', () => (account.twoFactorEnabled
      ? { json: { email: 'ada@example.com' } }
      : NEEDS_2FA))
      .on('GET', '/v1/admin/pricing/proposals', { json: [] })
      .on('GET', '/v1/auth/2fa', { json: { enabled: true, recoveryCodesRemaining: 10 } })
      .on('POST', '/v1/auth/2fa/enrol', { json: ENROLMENT })
      .on('POST', '/v1/auth/2fa/enable', () => {
        account.twoFactorEnabled = true;
        return { json: { recoveryCodes: RECOVERY_CODES, session: authResponse('tok-after') } };
      });

    await page.goto('/admin/users');

    // Told what to do, not bounced to the dashboard as if not an admin.
    await expect(page.getByRole('heading', { name: 'Turn on two-factor sign-in' })).toBeVisible();
    await expect(page).toHaveURL('/admin/users');
    await page.getByRole('link', { name: 'Go to settings' }).click();
    await expect(page).toHaveURL('/settings');

    const section = page.locator('section', { hasText: 'Two-factor sign-in' });
    await section.getByRole('button', { name: 'Turn on' }).click();
    await section.getByLabel('Current password').fill(PASSWORD);
    await section.getByRole('button', { name: 'Continue' }).click();
    await section.getByLabel('6-digit code').fill('123456');
    await section.getByRole('button', { name: 'Turn on' }).click();
    await section.getByRole('button', { name: "I've saved them" }).click();

    await expect.poll(() => api.callsTo('GET', '/v1/admin/me').length).toBeGreaterThan(1);
    await page.goto('/admin/proposals');
    await expect(page.getByText('operator console')).toBeVisible();
  });

  test('a plain user is still bounced to the dashboard', async ({ page, api }) => {
    await api.signIn();

    await page.goto('/admin/users');

    await expect(page).toHaveURL('/dashboard');
  });
});

test.describe('operator reset', () => {
  const OWNER = {
    id: '00000000-0000-4000-8000-000000000002',
    email: 'owner@example.com',
    fullName: 'Owner',
    role: 'USER',
    active: true,
    creditBalanceCents: 0,
    apiKeyCount: 0,
    dailyCreditCapCents: null,
    monthlyCreditCapCents: null,
    createdAt: '2026-10-01T00:00:00Z',
  };

  async function openOwner(page: Page, api: MockApi) {
    await api.signIn(makeUser({ twoFactorEnabled: true }));
    api.on('GET', '/v1/admin/me', { json: { email: 'ada@example.com' } })
      .on('GET', '/v1/admin/users', { json: { users: [OWNER] } })
      .on('GET', `/v1/admin/users/${OWNER.id}/ledger`, { json: { entries: [], page: 0, totalPages: 0, totalEntries: 0 } });
    await page.goto('/admin/users');
    await page.getByPlaceholder('email or user UUID…').fill('owner@example.com');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByText('owner@example.com').first().click();
  }

  test('asks first, then says it is done', async ({ page, api }) => {
    api.on('POST', `/v1/admin/users/${OWNER.id}/2fa/reset`, { status: 204 });
    await openOwner(page, api);
    const dialogs: string[] = [];
    page.on('dialog', (dialog) => {
      dialogs.push(dialog.message());
      void dialog.accept();
    });

    await page.getByRole('button', { name: 'Reset two-factor sign-in' }).click();

    await expect(page.getByRole('status')).toContainText('The owner has been emailed.');
    expect(dialogs[0]).toContain('owner@example.com');
    expect(api.callsTo('POST', `/v1/admin/users/${OWNER.id}/2fa/reset`)).toHaveLength(1);
  });

  test('declining the prompt sends nothing', async ({ page, api }) => {
    await openOwner(page, api);
    page.on('dialog', (dialog) => void dialog.dismiss());

    await page.getByRole('button', { name: 'Reset two-factor sign-in' }).click();

    await expect(page.getByRole('button', { name: 'Reset two-factor sign-in' })).toBeEnabled();
    expect(api.callsTo('POST', `/v1/admin/users/${OWNER.id}/2fa/reset`)).toEqual([]);
  });

  test('an account without two-factor shows the reason', async ({ page, api }) => {
    api.on('POST', `/v1/admin/users/${OWNER.id}/2fa/reset`,
      problem(409, 'Two-factor sign-in is not on for this account.'));
    await openOwner(page, api);
    page.on('dialog', (dialog) => void dialog.accept());

    await page.getByRole('button', { name: 'Reset two-factor sign-in' }).click();

    await expect(page.getByRole('status')).toContainText('not on for this account');
  });
});
