import type { Page } from '@playwright/test';
import type { ProviderCredential } from '../src/lib/provider-keys';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

// Settings → Provider keys (round 14 brief 03, bring your own key): a key of
// the user's own for a provider. Shown only while at least one provider is
// offered, which none is by default. The key is never shown back: the API
// returns its last four characters, and the page never holds more.

/** Not a real key: visibly fake, and only the format matters. */
const FAKE_KEY = 'sk-test-FAKE-settings-0000000000001234';

const NONE: ProviderCredential = {
  provider: 'OPENAI', last4: null, status: 'none', fallback_to_platform: false,
  used_for: ['chat', 'images'], checked_at: null, fee_basis_points: 500,
};
const STORED: ProviderCredential = {
  ...NONE, last4: '1234', status: 'valid', checked_at: '2026-11-16T05:00:00Z',
};

async function openSettings(page: Page, api: MockApi, credentials: ProviderCredential[]) {
  await api.signIn(makeUser());
  api.on('GET', '/v1/webhooks/secret', { json: { secret: 'whsec_test', header: 'X-Godance-Signature' } })
    .on('GET', '/v1/provider-credentials', { json: credentials });
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
}

const section = (page: Page) => page.locator('section', { has: page.getByRole('heading', { name: 'Provider keys' }) });

test('with no provider offered, Settings has no Provider keys section and is as it was', async ({ page, api }) => {
  await openSettings(page, api, []);

  await expect(page.getByRole('heading', { name: 'Webhook signing secret' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 2 })).toHaveText([
    'Email address', 'Two-factor sign-in', 'Webhook signing secret', 'Verification snippet',
  ]);
});

test('an offered provider shows its status, what the key is used for, and the fee', async ({ page, api }) => {
  await openSettings(page, api, [NONE, { ...NONE, provider: 'ANTHROPIC', used_for: ['chat'] }]);

  await expect(section(page)).toContainText('a fee of 5% of the list price');
  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await expect(openAi.getByTestId('provider-key-status')).toHaveText('No key');
  await expect(openAi).toContainText('Used for chat and images.');
  await expect(page.getByRole('listitem', { name: 'Anthropic key' })).toContainText('Used for chat.');
  await expect(openAi.getByRole('button', { name: 'Remove key' })).toHaveCount(0);
});

test('saving a key sends it once with the fallback setting, and shows only its last four', async ({ page, api }) => {
  await openSettings(page, api, [NONE]);
  api.on('PUT', '/v1/provider-credentials/OPENAI', { json: { ...STORED, fallback_to_platform: true } });

  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await openAi.getByLabel('OpenAI API key').fill(FAKE_KEY);
  await openAi.getByRole('checkbox').check();
  await openAi.getByRole('button', { name: 'Save key' }).click();

  await expect(openAi.getByTestId('provider-key-status')).toHaveText('Working');
  await expect(openAi).toContainText('Key ending in 1234');
  await expect(openAi.getByLabel('OpenAI API key')).toHaveValue('');
  expect(api.callsTo('PUT', '/v1/provider-credentials/OPENAI').map((c) => c.body)).toEqual([
    { api_key: FAKE_KEY, fallback_to_platform: true },
  ]);
  // The key is nowhere on the page once saved.
  await expect(page.locator('body')).not.toContainText(FAKE_KEY);
});

test('a key the provider refuses is not stored, and says why', async ({ page, api }) => {
  await openSettings(page, api, [NONE]);
  api.on('PUT', '/v1/provider-credentials/OPENAI',
    problem(400, 'OPENAI did not accept the key; nothing was stored', 'byok_key_rejected'));

  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await openAi.getByLabel('OpenAI API key').fill(FAKE_KEY);
  await openAi.getByRole('button', { name: 'Save key' }).click();

  await expect(openAi.getByRole('alert')).toHaveText('OPENAI did not accept the key; nothing was stored');
  await expect(openAi.getByTestId('provider-key-status')).toHaveText('No key');
});

test('the fallback toggle changes only that setting, without the key', async ({ page, api }) => {
  await openSettings(page, api, [STORED]);
  api.on('PUT', '/v1/provider-credentials/OPENAI', { json: { ...STORED, fallback_to_platform: true } });

  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await openAi.getByRole('checkbox').check();

  await expect.poll(() => api.callsTo('PUT', '/v1/provider-credentials/OPENAI').map((c) => c.body))
    .toEqual([{ fallback_to_platform: true }]);
  await expect(openAi.getByRole('checkbox')).toBeChecked();
});

test('removing a key asks first, then deletes it', async ({ page, api }) => {
  await openSettings(page, api, [STORED]);
  api.on('DELETE', '/v1/provider-credentials/OPENAI', { status: 204 });
  page.once('dialog', (dialog) => void dialog.accept());

  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await openAi.getByRole('button', { name: 'Remove key' }).click();

  await expect(openAi.getByTestId('provider-key-status')).toHaveText('No key');
  expect(api.callsTo('DELETE', '/v1/provider-credentials/OPENAI')).toHaveLength(1);
  await expect(openAi.getByRole('button', { name: 'Remove key' })).toHaveCount(0);
});

test('a refused key is shown as needing replacement', async ({ page, api }) => {
  await openSettings(page, api, [{ ...STORED, status: 'invalid' }]);

  const openAi = page.getByRole('listitem', { name: 'OpenAI key' });
  await expect(openAi.getByTestId('provider-key-status')).toHaveText('Refused by the provider: replace it');
  await expect(openAi.getByRole('button', { name: 'Replace key' })).toBeVisible();
});
