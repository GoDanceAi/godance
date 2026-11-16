import type { Page } from '@playwright/test';
import type { PublicRecipe } from '../src/lib/community';
import { GALLERY_ORIGIN } from './api-origin';
import {
  AUTHOR,
  makePublicRecipe,
  profileSet,
  serveFollows,
  serveProfile,
  servePublicProfiles,
  servePublicRecipes,
} from './community';
import { MODELS, mockEstimates, serveMedia } from './generation';
import { expect, test, type MockApi } from './mock-api';

// Round 14 brief 02: following authors. A Follow button where a community
// recipe opens in the app, and on the page an author's Follow link opens;
// the /following feed of their new recipes; and the list of who is followed.

const RECIPE = makePublicRecipe();

/** `n` public recipes by AUTHOR, newest first. */
function feedOf(n: number): PublicRecipe[] {
  return Array.from({ length: n }, (_, i) => makePublicRecipe({
    public_id: `feed${String(n - i).padStart(8, '0')}`,
    title: `Recipe ${n - i}`,
    published_at: new Date(Date.UTC(2026, 10, 16) - i * 3_600_000).toISOString(),
  }));
}

async function signedIn(page: Page, api: MockApi, handle = 'ada-lights') {
  await api.signIn();
  serveProfile(api, profileSet({ handle }));
  await serveMedia(page);
}

const followButton = (page: Page, handle = AUTHOR) => page.getByRole('button', { name: `Follow @${handle}` });

test.describe('following an author', () => {
  test('Following is in the navigation while the community is open', async ({ page, api }) => {
    await signedIn(page, api);
    serveFollows(api, [AUTHOR], [RECIPE]);
    await page.goto('/dashboard');

    await page.getByRole('link', { name: 'Following' }).click();
    await expect(page).toHaveURL('/following');
    await expect(page.getByRole('heading', { level: 1, name: 'Following' })).toBeVisible();
    await expect(page.getByRole('article', { name: RECIPE.title })).toBeVisible();
  });

  test('from the Studio\'s line about a community recipe, and stopping again', async ({ page, api }) => {
    await signedIn(page, api);
    const following: string[] = [];
    serveFollows(api, following);
    servePublicRecipes(api, [RECIPE]);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await page.goto(`/create/image?published=${RECIPE.public_id}`);

    const button = followButton(page);
    await expect(button).toHaveAttribute('aria-pressed', 'false');
    await expect(button).toHaveText('Follow');
    await button.click();
    await expect(button).toHaveAttribute('aria-pressed', 'true');
    await expect(button).toHaveText('Following');
    expect(following).toEqual([AUTHOR]);

    await button.click();
    await expect(button).toHaveAttribute('aria-pressed', 'false');
    expect(following).toEqual([]);
    expect(api.callsTo('POST', `/v1/follows/${AUTHOR}`)).toHaveLength(1);
    expect(api.callsTo('DELETE', `/v1/follows/${AUTHOR}`)).toHaveLength(1);
  });

  test('an author is never offered a Follow button for their own recipe', async ({ page, api }) => {
    await signedIn(page, api, AUTHOR);
    serveFollows(api, []);
    servePublicRecipes(api, [RECIPE]);
    api.on('GET', '/v1/models', { json: MODELS });
    mockEstimates(api);
    await page.goto(`/create/image?published=${RECIPE.public_id}`);

    await expect(page.locator('[data-published-source]')).toContainText(`by @${AUTHOR}`);
    await expect(followButton(page)).toHaveCount(0);
  });

  test('the Follow link of an author\'s page shows them, and follows only when asked', async ({ page, api }) => {
    await signedIn(page, api);
    const following: string[] = [];
    serveFollows(api, following, [RECIPE]);
    servePublicProfiles(api, [{ handle: AUTHOR, display_name: 'Mina', link: null, followers: 3, recipes: [RECIPE] }]);
    await page.goto(`/following?follow=${AUTHOR}&utm_source=godance&utm_medium=community&utm_campaign=${AUTHOR}`);

    const card = page.getByRole('region', { name: `Follow @${AUTHOR}` });
    await expect(card).toContainText('Mina');
    await expect(card).toContainText(`@${AUTHOR} · 3 followers · 1 recipe`);
    await expect(card.getByRole('link', { name: `@${AUTHOR}` })).toHaveAttribute('href', `${GALLERY_ORIGIN}/u/${AUTHOR}`);
    await expect(page.getByText('Nothing yet.')).toBeVisible();
    expect(api.callsTo('POST', `/v1/follows/${AUTHOR}`)).toEqual([]);

    await card.getByRole('button', { name: `Follow @${AUTHOR}` }).click();
    await expect(card.getByRole('button', { name: `Follow @${AUTHOR}` })).toHaveAttribute('aria-pressed', 'true');
    expect(following).toEqual([AUTHOR]);
    // The feed is read again, and now has the author's recipe.
    await expect(page.getByRole('article', { name: RECIPE.title })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Authors you follow' })).toContainText(`@${AUTHOR}`);
  });

  test('a Follow link for a handle with no public profile says so, and offers no button', async ({ page, api }) => {
    await signedIn(page, api);
    serveFollows(api, []);
    servePublicProfiles(api, []);
    await page.goto('/following?follow=nobody-here');

    const card = page.getByRole('region', { name: 'Follow @nobody-here' });
    await expect(card).toHaveText('@nobody-here has no public profile.');
    await expect(card.getByRole('button')).toHaveCount(0);
  });
});

test.describe('the /following feed', () => {
  test('newest first, each with Run this, its page and Report; Load more asks for those after the last', async ({ page, api }) => {
    await signedIn(page, api);
    const feed = feedOf(30);
    serveFollows(api, [AUTHOR], feed);
    await page.goto('/following');

    const cards = page.getByRole('region', { name: 'New recipes' }).getByRole('article');
    await expect(cards).toHaveCount(24);
    await expect(cards.first()).toHaveAccessibleName('Recipe 30');
    const first = cards.first();
    await expect(first).toContainText(`by @${AUTHOR}`);
    await expect(first).toContainText('Run 12 times by 5 people');
    await expect(first.getByRole('link', { name: 'Run this' })).toHaveAttribute('href', `/create/image?published=${feed[0].public_id}`);
    await expect(first.getByRole('link', { name: 'Its page' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/community/${feed[0].public_id}`);
    await expect(first.getByRole('link', { name: 'Report' })).toHaveAttribute('href', `/report?kind=published_recipe&id=${feed[0].public_id}`);

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards).toHaveCount(30);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);
    expect(api.callsTo('GET', '/v1/follows/feed').map((call) => call.query.toString()))
      .toEqual(['limit=24', `limit=24&before=${feed[23].public_id}`]);
  });

  test('the authors followed, each with a way to stop', async ({ page, api }) => {
    await signedIn(page, api);
    const following = [AUTHOR, 'bo-paper'];
    serveFollows(api, following, [RECIPE]);
    await page.goto('/following');

    const authors = page.getByRole('region', { name: 'Authors you follow' });
    await expect(authors.getByRole('listitem')).toHaveCount(2);
    await expect(authors.getByRole('link', { name: '@bo-paper' })).toHaveAttribute('href', `${GALLERY_ORIGIN}/u/bo-paper`);
    await authors.getByRole('button', { name: 'Follow @bo-paper' }).click();
    await expect(authors.getByRole('listitem')).toHaveCount(1);
    expect(following).toEqual([AUTHOR]);
    expect(api.callsTo('DELETE', '/v1/follows/bo-paper')).toHaveLength(1);
    // Read again once the list changed: once at first, once after.
    await expect.poll(() => api.callsTo('GET', '/v1/follows/feed').length).toBe(2);
  });

  test('following no one, it says how to start', async ({ page, api }) => {
    await signedIn(page, api);
    serveFollows(api, []);
    await page.goto('/following');

    await expect(page.getByText("You don't follow anyone yet.")).toBeVisible();
    await expect(page.getByText('Nothing yet. When an author you follow publishes a recipe, it shows here.')).toBeVisible();
  });
});
