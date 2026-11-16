import { HANDLE, makeProfile, profileSet, serveProfile } from './community';
import { serveJobList } from './generation';
import { expect, makeUser, test, type MockApi } from './mock-api';
import { makeRecipe, recipeId, serveRecipes } from './user-recipes';

// Round 14 brief 02: the account's public profile in Settings, with its
// rules; and, while the API has the community switched off, no trace of it.

const RULES = [
  'A handle has 3 to 30 characters: lowercase letters, digits and hyphens. It starts and ends with a letter or a digit, with no two hyphens in a row.',
  'Some handles are reserved',
  "Once a moderator approves your profile, your handle can't change.",
  'A moderator approves your display name and link too.',
];

/** The calls the Settings page makes of its own: the webhook secret, and two-factor sign-in's state. */
function settingsPage(api: MockApi): MockApi {
  return api.on('GET', '/v1/webhooks/secret', { json: { secret: 'whsec_test', header: 'X-Godance-Signature' } })
    .on('GET', '/v1/auth/2fa', { json: { enabled: false, recoveryCodesRemaining: 0 } });
}

test.describe('the community switched off', () => {
  test('shows nowhere: no profile, no Following, no publishing, and its pages lead to the dashboard', async ({ page, api }) => {
    // The default mock: GET /v1/profile answers 404 community_disabled.
    await api.signIn();
    serveRecipes(api, [makeRecipe()]);
    serveJobList(api, []);

    settingsPage(api);
    await page.goto('/settings');
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Email address' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Public profile' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Following' })).toHaveCount(0);

    await page.goto(`/recipes/${recipeId(1)}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Mug on a kitchen shelf' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Attempts' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Community' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Publish/ })).toHaveCount(0);

    await page.goto('/following');
    await expect(page).toHaveURL('/dashboard');
    await page.goto('/report?kind=published_recipe&id=k3v9x2m7q1aa');
    await expect(page).toHaveURL('/dashboard');
    expect(api.callsTo('GET', /^\/v1\/recipes\/[^/]+\/publish$/)).toEqual([]);
    expect(api.callsTo('POST', '/v1/reports')).toEqual([]);
  });
});

test.describe('the public profile in Settings', () => {
  test('an account that may publish sets one up, with the rules shown and each one checked as it types', async ({ page, api }) => {
    await api.signIn();
    const served = serveProfile(api, makeProfile());
    settingsPage(api);
    await page.goto('/settings');

    const section = page.getByRole('region', { name: 'Public profile' });
    for (const rule of RULES) await expect(section.getByRole('list', { name: 'Profile rules' })).toContainText(rule);
    const save = section.getByRole('button', { name: 'Set up profile' });
    await expect(save).toBeDisabled();

    // Typed in capitals, it is lowercased; a space is not allowed.
    const handle = section.getByLabel('Handle');
    await handle.fill('Ada Lights');
    await expect(handle).toHaveValue('ada lights');
    await handle.blur();
    await expect(section.getByText('Use lowercase letters, digits and hyphens only.')).toBeVisible();
    await handle.fill('-ada');
    await expect(section.getByText('Start and end with a letter or a digit.')).toBeVisible();
    await handle.fill('ad');
    await expect(section.getByText('A handle has 3 to 30 characters.')).toBeVisible();
    await handle.fill('ada--lights');
    await expect(section.getByText('Use no two hyphens in a row.')).toBeVisible();
    await handle.fill(HANDLE);
    await expect(section.getByText('How you appear in links: godance.ai/u/your-handle.')).toBeVisible();

    await section.getByLabel('Display name').fill('Ada');
    const link = section.getByLabel('Link (optional)');
    await link.fill('http://example.test/ada');
    await expect(section.getByText('A link is an https:// address.')).toBeVisible();
    await expect(save).toBeDisabled();
    await link.fill('https://example.test/ada');
    await save.click();

    await expect(section.getByText('Not public yet. Your profile becomes public when a moderator approves the first recipe you publish.')).toBeVisible();
    expect(api.callsTo('PUT', '/v1/profile').map((call) => call.body)).toEqual([
      { handle: HANDLE, display_name: 'Ada', link: 'https://example.test/ada' },
    ]);
    expect(served.current().handle).toBe(HANDLE);
    await expect(section.getByRole('button', { name: 'Save profile' })).toBeDisabled();
    await expect(section.getByText('Saved.')).toBeVisible();
  });

  test('the API\'s refusals: a reserved handle, and one another account has', async ({ page, api }) => {
    await api.signIn();
    serveProfile(api, makeProfile(), { reserved: ['support-team'], taken: ['mina-teapots'] });
    settingsPage(api);
    await page.goto('/settings');

    const section = page.getByRole('region', { name: 'Public profile' });
    await section.getByLabel('Display name').fill('Ada');
    await section.getByLabel('Handle').fill('support-team');
    await section.getByRole('button', { name: 'Set up profile' }).click();
    await expect(section.getByRole('alert')).toHaveText('“support-team” is reserved. Choose another handle.');

    await section.getByLabel('Handle').fill('mina-teapots');
    await section.getByRole('button', { name: 'Set up profile' }).click();
    await expect(section.getByRole('alert')).toHaveText('“mina-teapots” belongs to another account. Choose another handle.');
    await expect(section.getByText('Not public yet.')).toHaveCount(0);
  });

  test('once approved: the handle is fixed, and a new display name waits for a moderator', async ({ page, api }) => {
    await api.signIn();
    serveProfile(api, profileSet({ approved: true, followers: 41 }));
    settingsPage(api);
    await page.goto('/settings');

    const section = page.getByRole('region', { name: 'Public profile' });
    await expect(section.getByText('Public at godance.ai/u/ada-lights · 41 followers')).toBeVisible();
    await expect(section.getByRole('link', { name: 'godance.ai/u/ada-lights' })).toHaveAttribute('href', /\/u\/ada-lights$/);
    await expect(section.getByLabel('Handle')).toBeDisabled();
    await expect(section.getByText('Fixed: a moderator approved your profile.')).toBeVisible();

    await section.getByLabel('Display name').fill('Ada L.');
    await section.getByRole('button', { name: 'Save profile' }).click();
    await expect(section.getByText('Your new display name or link waits for a moderator.')).toBeVisible();
    expect(api.callsTo('PUT', '/v1/profile').at(-1)?.body).toEqual({ handle: HANDLE, display_name: 'Ada L.', link: 'https://example.test/ada' });
  });

  test('a change a moderator refused says why', async ({ page, api }) => {
    await api.signIn();
    serveProfile(api, profileSet({ approved: true, review_reason: 'The link goes to a page selling followers.' }));
    settingsPage(api);
    await page.goto('/settings');

    await expect(page.getByRole('region', { name: 'Public profile' }))
      .toContainText("A moderator didn't accept your last change: The link goes to a page selling followers.");
  });

  test('an account that may not publish is told why, and gets no form', async ({ page, api }) => {
    await api.signIn(makeUser({ ageStatus: 'CONSENT_GRANTED' }));
    serveProfile(api, makeProfile({ cannot_publish: 'not_adult' }));
    settingsPage(api);
    await page.goto('/settings');
    const section = page.getByRole('region', { name: 'Public profile' });
    await expect(section).toContainText('Public profiles and publishing recipes are for adult accounts only.');
    await expect(section.getByLabel('Handle')).toHaveCount(0);

    serveProfile(api, makeProfile({ cannot_publish: 'email_not_verified' }));
    await page.reload();
    await expect(section).toContainText('Verify your email address to set up a public profile and publish recipes.');
    await expect(section.getByRole('textbox')).toHaveCount(0);
    expect(api.callsTo('PUT', '/v1/profile')).toEqual([]);
  });
});
