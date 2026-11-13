import type { Page } from '@playwright/test';
import { sessionStarted, type MockApi } from './mock-api';

// The ways in, as a visitor goes through them: shared by the specs that
// check where each one lands.

export const PASSWORD = 'correct-horse-battery';

export type SocialOutcome = 'signed in' | 'two-factor' | { error: string };

/**
 * The API's /oauth2/authorization/google, standing in for the whole trip to
 * the provider: it sends the browser back to the app's callback with the
 * nonce this tab sent, as OAuthLoginSuccessHandler does, and the session
 * cookie when the sign-in is complete.
 */
export function socialProvider(api: MockApi, appOrigin: string, outcome: SocialOutcome): MockApi {
  return api.on('GET', '/oauth2/authorization/google', (call, route) => {
    const nonce = call.query.get('login_nonce') ?? '';
    const fragment = outcome === 'signed in'
      ? `token_type=Cookie&login_nonce=${nonce}`
      : outcome === 'two-factor'
        ? `two_factor_token=challenge-social&expires_at=2030-01-01T00%3A00%3A00Z&login_nonce=${nonce}`
        : `error=${encodeURIComponent(outcome.error)}&login_nonce=${nonce}`;
    const cookie = outcome === 'signed in' ? sessionStarted('tok-social').setCookie : undefined;
    return route.fulfill({
      status: 302,
      headers: { location: `${appOrigin}/oauth/callback#${fragment}`, ...(cookie ? { 'set-cookie': cookie } : {}) },
    });
  });
}

export async function signInWithPassword(page: Page) {
  await page.getByLabel('Email').fill('ada@example.com');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

export async function fillSignUp(page: Page, born = { month: 'December', year: '1990' }) {
  await page.getByLabel('Full name').fill('Ada Lovelace');
  await page.getByLabel('Email', { exact: true }).fill('ada@example.com');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByLabel('Birth month').selectOption(born.month);
  await page.getByLabel('Birth year').selectOption(born.year);
}

export async function submitCode(page: Page, code: string) {
  await page.getByLabel('Code').fill(code);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
}
