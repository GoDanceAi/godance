import type { Page } from '@playwright/test';
import type { InputsStatus, MeResponse } from '../src/lib/api';
import { INPUT_TERMS } from '../src/lib/input-terms';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

// The Inputs section of Settings (round 11 brief 01): the input terms a user
// accepts before uploading an image. It exists only while the API says inputs
// are switched on, which they are not by default.

const ON: InputsStatus = {
  enabled: true,
  terms_version: INPUT_TERMS.version,
  accepted: false,
  accepted_at: null,
  allow_people: false,
};

/** Signs in as `user` and serves what Settings reads. The account can change between calls. */
async function openSettings(page: Page, api: MockApi, account: { user: MeResponse }) {
  await api.signIn(account.user);
  api.on('GET', '/v1/auth/me', () => ({ json: account.user }))
    .on('GET', '/v1/webhooks/secret', { json: { secret: 'whsec_test', header: 'X-Godance-Signature' } });
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
}

const inputsSection = (page: Page) => page.locator('section', { has: page.getByRole('heading', { name: 'Inputs' }) });

test.describe('inputs switched off', () => {
  test('Settings has no Inputs section, and is otherwise as it was', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser() });

    await expect(page.getByRole('heading', { name: 'Webhook signing secret' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2 })).toHaveText([
      'Email address', 'Two-factor sign-in', 'Webhook signing secret', 'Verification snippet',
    ]);
    await expect(page.getByText('input terms')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Accept' })).toHaveCount(0);
    expect(api.callsTo('POST', '/v1/inputs/terms')).toHaveLength(0);
  });

  test('an API that says nothing about inputs is treated as switched off', async ({ page, api }) => {
    const { inputs: _absent, ...older } = makeUser();
    await openSettings(page, api, { user: older });

    await expect(page.getByRole('heading', { name: 'Webhook signing secret' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Inputs' })).toHaveCount(0);
  });

  test('a non-adult account sees nothing either', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser({ ageStatus: 'CONSENT_GRANTED' }) });

    await expect(page.getByRole('heading', { name: 'Webhook signing secret' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Inputs' })).toHaveCount(0);
  });
});

test.describe('inputs switched on', () => {
  test('shows the terms, their version and an Accept button', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser({ inputs: ON }) });

    const section = inputsSection(page);
    await expect(section).toBeVisible();
    await expect(section).toContainText(INPUT_TERMS.title);
    await expect(section).toContainText(`version ${INPUT_TERMS.version}`);
    // Every line of the terms is on the page, as written in the one constant.
    await expect(section.getByRole('list', { name: 'What you confirm' }).getByRole('listitem'))
      .toHaveText(INPUT_TERMS.confirmations);
    await expect(section.getByRole('list', { name: INPUT_TERMS.handlingTitle }).getByRole('listitem'))
      .toHaveText(INPUT_TERMS.handling);
    await expect(section).toContainText('Images of people are not accepted yet.');
    await expect(section.getByRole('button', { name: 'Accept' })).toBeEnabled();
    // Showing the terms accepts nothing.
    expect(api.callsTo('POST', '/v1/inputs/terms')).toHaveLength(0);
  });

  test('says nothing about people once people are allowed', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser({ inputs: { ...ON, allow_people: true } }) });

    await expect(inputsSection(page).getByRole('button', { name: 'Accept' })).toBeVisible();
    await expect(inputsSection(page)).not.toContainText('Images of people are not accepted yet.');
  });

  test('Accept sends the version shown, then says when it was accepted', async ({ page, api }) => {
    const account = { user: makeUser({ inputs: ON }) };
    await openSettings(page, api, account);
    api.on('POST', '/v1/inputs/terms', () => {
      account.user = makeUser({ inputs: { ...ON, accepted: true, accepted_at: '2026-11-14T15:30:00Z' } });
      return { status: 204 };
    });

    const section = inputsSection(page);
    await section.getByRole('button', { name: 'Accept' }).click();

    await expect(section).toContainText(`You accepted the input terms (version ${INPUT_TERMS.version}) on Nov 14, 2026`);
    await expect(section.getByRole('button', { name: 'Accept' })).toHaveCount(0);
    await expect(section.getByRole('list', { name: 'What you confirm' })).toHaveCount(0);
    const sent = api.callsTo('POST', '/v1/inputs/terms');
    expect(sent).toHaveLength(1);
    expect(sent[0].body).toEqual({ version: INPUT_TERMS.version });
  });

  test('an account that already accepted sees one line saying when, and no button', async ({ page, api }) => {
    await openSettings(page, api, {
      user: makeUser({ inputs: { ...ON, accepted: true, accepted_at: '2026-11-01T09:00:00Z' } }),
    });

    const section = inputsSection(page);
    await expect(section).toContainText('You accepted the input terms (version 1) on Nov 1, 2026');
    await expect(section.getByRole('button')).toHaveCount(0);
  });

  test('a refusal from the API is shown, and the terms stay to be accepted', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser({ inputs: ON }) });
    api.on('POST', '/v1/inputs/terms',
      problem(400, 'That is not the current version of the input terms.', 'inputs_terms_version_mismatch'));

    const section = inputsSection(page);
    await section.getByRole('button', { name: 'Accept' }).click();

    await expect(section.getByRole('alert')).toHaveText('That is not the current version of the input terms.');
    await expect(section.getByRole('button', { name: 'Accept' })).toBeEnabled();
  });

  test('terms newer than this page asks for a reload instead of offering Accept', async ({ page, api }) => {
    await openSettings(page, api, {
      user: makeUser({ inputs: { ...ON, terms_version: INPUT_TERMS.version + 1 } }),
    });

    const section = inputsSection(page);
    await expect(section).toContainText('The input terms have changed. Reload this page to read the current version.');
    await expect(section.getByRole('button', { name: 'Accept' })).toHaveCount(0);
    await expect(section.getByRole('list', { name: 'What you confirm' })).toHaveCount(0);
  });

  test('a non-adult account gets one line and no control', async ({ page, api }) => {
    await openSettings(page, api, { user: makeUser({ ageStatus: 'CONSENT_GRANTED', inputs: ON }) });

    const section = inputsSection(page);
    await expect(section).toContainText('Using your own images in a generation is not available for this account.');
    await expect(section.getByRole('button')).toHaveCount(0);
    await expect(section.getByRole('list')).toHaveCount(0);
    // None of the terms: the line itself says "using your own images", so look for their text.
    await expect(section).not.toContainText(INPUT_TERMS.intro);
    await expect(section).not.toContainText(INPUT_TERMS.handlingTitle);
    await expect(section).not.toContainText(`version ${INPUT_TERMS.version}`);
  });
});
