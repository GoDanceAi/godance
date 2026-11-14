import type { Page } from '@playwright/test';
import { MODELS, mockEstimates, serveMedia } from './generation';
import { expect, problem, type MockApi } from './mock-api';
import { IMAGE_SLUG, imageRecipe, indexEntry, PROMPT_EN, test, VIDEO_SLUG, videoRecipe } from './mock-gallery';

// Saved recipes: a gallery recipe loaded into the Studio can be saved to
// come back to, and an empty Studio lists the saved ones.

const RECIPE_LINK = `/create/image?example=${IMAGE_SLUG}`;
const SAVED = 'Your saved recipes';
const SAVED_ROUTE = /^\/v1\/saved-recipes\/([a-z0-9-]+)$/;

/**
 * GET /v1/saved-recipes, and PUT and DELETE /v1/saved-recipes/{slug}, over a
 * list that starts as `slugs`, newest first.
 */
function serveSavedRecipes(api: MockApi, slugs: string[] = []): { slugs: () => string[] } {
  let saved = [...slugs];
  const slugOf = (path: string) => SAVED_ROUTE.exec(path)![1];
  api.on('GET', '/v1/saved-recipes', () => ({
    json: saved.map((slug, i) => ({ slug, savedAt: new Date(Date.UTC(2026, 10, 13, 12, 0, saved.length - i)).toISOString() })),
  }))
    .on('PUT', SAVED_ROUTE, (call) => {
      const slug = slugOf(call.path);
      if (!saved.includes(slug)) saved = [slug, ...saved];
      return { status: 204 };
    })
    .on('DELETE', SAVED_ROUTE, (call) => {
      saved = saved.filter((s) => s !== slugOf(call.path));
      return { status: 204 };
    });
  return { slugs: () => saved };
}

async function openStudio(page: Page, api: MockApi, path: string) {
  await api.signIn();
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
  await page.goto(path);
  await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
}

const prompt = (page: Page) => page.getByLabel('Prompt', { exact: true });
const saveRecipe = (page: Page) => page.getByRole('button', { name: 'Save recipe' });
const recipeSaved = (page: Page) => page.getByRole('button', { name: 'Recipe saved' });
const savedList = (page: Page) => page.getByRole('region', { name: SAVED }).getByRole('listitem');

test.describe('the gallery line in the Studio', () => {
  test('saves the recipe, and takes it off the list again', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    const saved = serveSavedRecipes(api);
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText('From the gallery:')).toBeVisible();
    await expect(saveRecipe(page)).toHaveAttribute('aria-pressed', 'false');

    await saveRecipe(page).click();
    await expect(recipeSaved(page)).toHaveAttribute('aria-pressed', 'true');
    expect(api.callsTo('PUT', `/v1/saved-recipes/${IMAGE_SLUG}`)).toHaveLength(1);
    expect(saved.slugs()).toEqual([IMAGE_SLUG]);
    // Saving keeps the form as it was and runs nothing.
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);

    // The API has it: saved after a reload too.
    await page.reload();
    await expect(recipeSaved(page)).toBeVisible();

    await recipeSaved(page).click();
    await expect(saveRecipe(page)).toHaveAttribute('aria-pressed', 'false');
    expect(api.callsTo('DELETE', `/v1/saved-recipes/${IMAGE_SLUG}`)).toHaveLength(1);
    expect(saved.slugs()).toEqual([]);
  });

  test('says so when the list is full, and when the save fails', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, RECIPE_LINK);
    const line = page.getByText('From the gallery:').locator('..');

    api.on('PUT', SAVED_ROUTE, problem(409,
      'You have saved 500 recipes, the most an account can keep. Remove one to save another.',
      'saved_recipes_limit_reached'));
    await saveRecipe(page).click();
    await expect(line.getByRole('alert')).toHaveText(
      'You have saved 500 recipes, the most an account can keep. Remove one to save another.');
    await expect(saveRecipe(page)).toHaveAttribute('aria-pressed', 'false');

    api.on('PUT', SAVED_ROUTE, problem(500, 'Internal Server Error'));
    await saveRecipe(page).click();
    await expect(line.getByRole('alert')).toHaveText("Couldn't save this recipe. Try again.");
    await expect(saveRecipe(page)).toHaveAttribute('aria-pressed', 'false');
  });

  test('has no control when the API has no saved recipes', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    api.on('GET', '/v1/saved-recipes', problem(404, 'Not Found'));
    await openStudio(page, api, RECIPE_LINK);

    await expect(page.getByText('From the gallery:')).toBeVisible();
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(page.getByRole('button', { name: /recipe/i })).toHaveCount(0);
  });
});

test.describe('an empty Studio', () => {
  test('lists the saved recipes by title, and opens one as its link would', async ({ page, api, gallery }) => {
    gallery.serveIndex({ recipes: [
      indexEntry(IMAGE_SLUG, 'Paper lantern street at dusk'),
      indexEntry(VIDEO_SLUG, 'Koi pond in the rain', 'VIDEO'),
    ] }).serveRecipe(imageRecipe()).serveRecipe(videoRecipe());
    // The last one has left the gallery: it is listed by its slug.
    serveSavedRecipes(api, [VIDEO_SLUG, IMAGE_SLUG, 'gone-from-the-gallery']);
    await openStudio(page, api, '/create/image');

    await expect(page.getByRole('heading', { name: SAVED })).toBeVisible();
    await expect(savedList(page)).toHaveCount(3);
    await expect(savedList(page).nth(0)).toContainText('Koi pond in the rain');
    await expect(savedList(page).nth(1)).toContainText('Paper lantern street at dusk');
    await expect(savedList(page).nth(2)).toContainText('gone-from-the-gallery');

    // An image recipe opens here, filled in and saved; nothing is run.
    await savedList(page).nth(1).getByRole('button', { name: 'Paper lantern street at dusk', exact: true }).click();
    await expect(page).toHaveURL(RECIPE_LINK);
    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(recipeSaved(page)).toBeVisible();
    await expect(page.getByRole('heading', { name: SAVED })).toHaveCount(0);
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);

    // Back to the empty Studio; a video recipe moves to the video page.
    await page.goBack();
    await expect(page).toHaveURL('/create/image');
    await savedList(page).nth(0).getByRole('button', { name: 'Koi pond in the rain', exact: true }).click();
    await expect(page).toHaveURL(`/create/video?example=${VIDEO_SLUG}`);
    await expect(prompt(page)).toHaveValue(/^Koi circling under falling rain/);
    await expect(recipeSaved(page)).toBeVisible();
  });

  test('removes a recipe from the list, including one the gallery no longer has', async ({ page, api, gallery }) => {
    gallery.serveIndex({ recipes: [indexEntry(IMAGE_SLUG, 'Paper lantern street at dusk')] });
    const saved = serveSavedRecipes(api, [IMAGE_SLUG, 'gone-from-the-gallery']);
    await openStudio(page, api, '/create/image');
    await expect(savedList(page)).toHaveCount(2);

    // Opening it finds nothing, and the list is still there to remove it from.
    await page.getByRole('button', { name: 'gone-from-the-gallery', exact: true }).click();
    await expect(page.getByText("That recipe couldn't be loaded from the gallery, so the form starts empty."))
      .toBeVisible();
    await page.getByRole('button', { name: 'Remove gone-from-the-gallery from your saved recipes' }).click();
    await expect(savedList(page)).toHaveCount(1);
    expect(api.callsTo('DELETE', '/v1/saved-recipes/gone-from-the-gallery')).toHaveLength(1);

    await page.getByRole('button', { name: 'Remove Paper lantern street at dusk from your saved recipes' }).click();
    await expect(page.getByRole('heading', { name: SAVED })).toHaveCount(0);
    expect(saved.slugs()).toEqual([]);
  });

  test('has no list when nothing is saved', async ({ page, api, gallery }) => {
    gallery.serveIndex({ recipes: [indexEntry(IMAGE_SLUG, 'Paper lantern street at dusk')] });
    await openStudio(page, api, '/create/image');

    await expect(page.getByRole('heading', { name: 'Start from an example' })).toBeVisible();
    await expect(page.getByRole('heading', { name: SAVED })).toHaveCount(0);
  });

  test('shows twelve of a long list, then all of it, and fits a phone', async ({ page, api }) => {
    serveSavedRecipes(api, Array.from({ length: 15 }, (_, i) => `a-rather-long-recipe-slug-number-${i + 1}`));
    await openStudio(page, api, '/create/image');

    await expect(savedList(page)).toHaveCount(12);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

    await page.getByRole('button', { name: 'Show all 15' }).click();
    await expect(savedList(page)).toHaveCount(15);
    await page.getByRole('button', { name: 'Show fewer' }).click();
    await expect(savedList(page)).toHaveCount(12);
  });
});
