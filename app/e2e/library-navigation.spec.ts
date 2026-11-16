import { expect, makeUser, problem, sessionStarted } from './mock-api';
import { test, IMAGE_SLUG, imageRecipe, indexEntry } from './mock-gallery';
import { serveRecipes } from './user-recipes';
import { MODELS, mockEstimates } from './generation';

test('gallery bookmarks have a persistent home distinct from versioned recipes and generated outputs', async ({ page, api, gallery }) => {
  await api.signIn(makeUser({ email: 'ada@example.test' }));
  serveRecipes(api, []);
  let slugs = [IMAGE_SLUG, 'removed-example'];
  api.on('GET', '/v1/saved-recipes', () => ({ json: slugs.map((slug) => ({ slug, savedAt: '2026-11-16T12:00:00Z' })) }));
  api.on('DELETE', /^\/v1\/saved-recipes\/[^/]+$/, (call) => {
    slugs = slugs.filter((slug) => !call.path.endsWith(slug));
    return { status: 204 };
  });
  gallery.serveIndex({ recipes: [indexEntry(IMAGE_SLUG, 'Paper lantern street at dusk')] }).serveRecipe(imageRecipe());
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await page.goto('/recipes');
  const bookmarks = page.getByRole('region', { name: 'Gallery bookmarks' });
  await expect(bookmarks.getByRole('listitem')).toHaveCount(2);
  await expect(bookmarks).toContainText('separate from your own versioned recipes');
  await expect(bookmarks.getByRole('link', { name: 'History', exact: true })).toHaveAttribute('href', '/history');
  await expect(bookmarks).toContainText('Not in the current gallery.');
  await page.reload();
  await expect(bookmarks.getByRole('listitem')).toHaveCount(2);
  await bookmarks.getByRole('link', { name: 'Paper lantern street at dusk' }).click();
  await expect(page.getByLabel('Prompt', { exact: true })).toHaveValue(/^A narrow street/);
  await page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link', { name: 'Recipes', exact: true }).click();
  await bookmarks.getByRole('link', { name: 'removed-example', exact: true }).click();
  await expect(page.getByText("That recipe couldn't be loaded from the gallery, so the form starts empty.")).toBeVisible();
  await page.getByRole('navigation', { name: 'Main', exact: true }).getByRole('link', { name: 'Recipes', exact: true }).click();
  await bookmarks.getByRole('button', { name: 'Remove removed-example from gallery bookmarks' }).click();
  await expect(bookmarks.getByRole('listitem')).toHaveCount(1);
  expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
});

test('gallery bookmark empty, read failure, retry and failed removal are truthful', async ({ page, api }) => {
  await api.signIn();
  serveRecipes(api, []);
  api.on('GET', '/v1/saved-recipes', problem(503, 'Unavailable'));
  await page.goto('/recipes');
  const bookmarks = page.getByRole('region', { name: 'Gallery bookmarks' });
  await expect(bookmarks.getByRole('alert')).toHaveText('Could not load your gallery bookmarks.');
  api.on('GET', '/v1/saved-recipes', { json: [] });
  await bookmarks.getByRole('button', { name: 'Retry bookmarks' }).click();
  await expect(bookmarks).toContainText('No gallery bookmarks yet.');
  api.on('GET', '/v1/saved-recipes', { json: [{ slug: 'missing-example', savedAt: '2026-11-16T12:00:00Z' }] });
  api.on('DELETE', '/v1/saved-recipes/missing-example', problem(503, 'Unavailable'));
  await page.reload();
  await bookmarks.getByRole('button', { name: 'Remove missing-example from gallery bookmarks' }).click();
  await expect(bookmarks.getByRole('alert')).toHaveText('Could not remove the bookmark. Try again.');
  await expect(bookmarks.getByRole('listitem')).toHaveCount(1);
});

test('a signed-out batch deep link keeps its destination through sign-in', async ({ page, api }) => {
  await page.goto('/batches');
  await expect(page).toHaveURL('/login');
  api.mockUser(makeUser({ email: 'ada@example.test' }));
  api.on('POST', '/v1/auth/login', sessionStarted('unusable-e2e-session'));
  await page.getByLabel('Email').fill('ada@example.test');
  await page.getByLabel('Password').fill('unusable-e2e-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/batches');
  await expect(page.getByRole('heading', { level: 1, name: 'Batches' })).toBeVisible();
});
