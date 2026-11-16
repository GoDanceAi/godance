import type { Page } from '@playwright/test';
import { GALLERY_ORIGIN } from './api-origin';
import { AUTHOR, makePublicRecipe, profileSet, serveProfile, servePublicRecipes, serveReports } from './community';
import { expect, makeUser, problem, sessionStarted, test, type MockApi } from './mock-api';
import { signInWithPassword } from './sign-in';

// Round 14 brief 02: the report form a public page's Report link opens,
// /report?kind=…&id=…: signed in only, with a reason, for a moderator.

const RECIPE = makePublicRecipe();
const RECIPE_LINK = `/report?kind=published_recipe&id=${RECIPE.public_id}`;

async function signedIn(api: MockApi) {
  await api.signIn();
  serveProfile(api, profileSet());
  servePublicRecipes(api, [RECIPE]);
}

const form = (page: Page) => page.getByRole('form', { name: 'Report' });
const reason = (page: Page) => page.getByLabel('What is wrong with it?');
const send = (page: Page) => page.getByRole('button', { name: 'Send report' });

test.describe('reporting', () => {
  test('signed out, it asks for a sign-in first, then comes back to the form', async ({ page, api }) => {
    api.mockUser(makeUser()).on('POST', '/v1/auth/login', sessionStarted('tok-report'));
    serveProfile(api, profileSet());
    servePublicRecipes(api, [RECIPE]);

    await page.goto(RECIPE_LINK);
    await expect(page).toHaveURL('/login');
    await signInWithPassword(page);

    await expect(page).toHaveURL(RECIPE_LINK);
    await expect(form(page)).toContainText('You are reporting the recipe “Teapot still life”');
  });

  test('a recipe: it names what is reported, needs a reason, and says thank you', async ({ page, api }) => {
    await signedIn(api);
    serveReports(api);
    await page.goto(RECIPE_LINK);

    await expect(form(page)).toContainText(`You are reporting the recipe “Teapot still life” by @${AUTHOR} to the moderators.`);
    await expect(form(page).getByRole('link', { name: '“Teapot still life”' }))
      .toHaveAttribute('href', `${GALLERY_ORIGIN}/community/${RECIPE.public_id}`);
    await expect(send(page)).toBeDisabled();
    await reason(page).fill('   ');
    await expect(send(page)).toBeDisabled();
    await reason(page).fill('  The examples show a real person without their consent.  ');
    await expect(page.getByText('58 of 1,000 characters')).toBeVisible();
    await send(page).click();

    await expect(page.getByRole('status')).toContainText('Thank you. Your report is with the moderators.');
    await expect(page.getByRole('status')).toContainText('It stays up until one decides.');
    expect(api.callsTo('POST', '/v1/reports').map((call) => call.body)).toEqual([
      { kind: 'published_recipe', id: RECIPE.public_id, reason: 'The examples show a real person without their consent.' },
    ]);
  });

  test('a profile', async ({ page, api }) => {
    await signedIn(api);
    serveReports(api);
    await page.goto(`/report?kind=profile&id=${AUTHOR}`);

    await expect(form(page)).toContainText(`You are reporting the profile @${AUTHOR} to the moderators.`);
    await reason(page).fill('The link goes to a scam.');
    await send(page).click();
    await expect(page.getByRole('status')).toContainText('Thank you.');
    expect(api.callsTo('POST', '/v1/reports')[0].body).toEqual({ kind: 'profile', id: AUTHOR, reason: 'The link goes to a scam.' });
    expect(api.callsTo('GET', /^\/v1\/public\//)).toEqual([]);
  });

  test('too many reports: the API\'s reason, and the form keeps what was typed', async ({ page, api }) => {
    await signedIn(api);
    serveReports(api, problem(429, 'you have sent 10 reports in the last hour; try again later', 'report_limit_reached'));
    await page.goto(RECIPE_LINK);

    await reason(page).fill('Copied from another recipe.');
    await send(page).click();
    await expect(form(page).getByRole('alert')).toHaveText('you have sent 10 reports in the last hour; try again later');
    await expect(reason(page)).toHaveValue('Copied from another recipe.');
  });

  test('one\'s own recipe: the API\'s reason', async ({ page, api }) => {
    await signedIn(api);
    serveReports(api, problem(400, 'you cannot report your own recipe', 'invalid_report'));
    await page.goto(RECIPE_LINK);

    await reason(page).fill('Testing.');
    await send(page).click();
    await expect(form(page).getByRole('alert')).toHaveText('you cannot report your own recipe');
  });

  test('something no longer public: there is nothing to report', async ({ page, api }) => {
    await signedIn(api);
    serveReports(api, problem(404, 'profile not found', 'not_found'));
    await page.goto(`/report?kind=profile&id=${AUTHOR}`);
    await reason(page).fill('Spam.');
    await send(page).click();
    await expect(page.getByText('There is nothing to report.')).toBeVisible();
    await expect(page.getByText(`@${AUTHOR} has no public profile any more.`)).toBeVisible();

    // A recipe that is already gone says so before anything is typed.
    await page.goto('/report?kind=published_recipe&id=g0ne00000001');
    await expect(page.getByText('That recipe is not public any more.')).toBeVisible();
    await expect(reason(page)).toHaveCount(0);
    expect(api.callsTo('POST', '/v1/reports')).toHaveLength(1);
  });

  test('a link that names nothing sends nothing', async ({ page, api }) => {
    await signedIn(api);
    for (const query of ['', '?kind=published_recipe', '?kind=user&id=mina-teapots', '?kind=profile&id=Not%20A%20Handle',
      '?kind=published_recipe&id=..%2F..%2Fadmin']) {
      await page.goto(`/report${query}`);
      await expect(page.getByText('This report link is incomplete.')).toBeVisible();
      await expect(reason(page)).toHaveCount(0);
    }
    expect(api.callsTo('POST', '/v1/reports')).toEqual([]);
    expect(api.callsTo('GET', /^\/v1\/public\//)).toEqual([]);
  });
});
