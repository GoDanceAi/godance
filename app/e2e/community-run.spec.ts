import type { Page } from '@playwright/test';
import { GALLERY_ORIGIN } from './api-origin';
import { AUTHOR, makePublicRecipe, profileSet, serveFollows, serveProfile, servePublicRecipes } from './community';
import { accepted, inProgress, jobId, makeJob, MODELS, mockEstimates, serveJob, serveMedia } from './generation';
import { expect, problem, type MockApi } from './mock-api';
import { IMAGE_SLUG, imageRecipe, test } from './mock-gallery';
import { makeRecipe, recipeId, serveRecipes } from './user-recipes';

// Round 14 brief 02: "Run this" on a community recipe's public page opens the
// Studio with ?published=<public id>. The form is filled as a gallery recipe's
// is; Generate sends `recipe.published_id`, with the model and params as the
// form has them. In the pre-fill order it comes after ?recipe=.

const RECIPE = makePublicRecipe();
const LINK = `/create/image?published=${RECIPE.public_id}`;
const FILLED = 'A teapot on a wooden table, soft window light';
const NOT_LOADED = "That community recipe couldn't be loaded, so the form starts empty.";

async function openStudio(page: Page, api: MockApi, path: string) {
  await api.signIn();
  serveProfile(api, profileSet());
  serveFollows(api, []);
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
  await page.goto(path);
  await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
}

const prompt = (page: Page) => page.getByLabel('Prompt', { exact: true });
const variable = (page: Page, name: string) => page.getByRole('group', { name: 'Variables' }).getByLabel(name, { exact: true });
const generate = (page: Page) => page.getByRole('button', { name: /^Generate/ });
const publicReads = (api: MockApi, id = RECIPE.public_id) => api.callsTo('GET', `/v1/public/recipes/${id}`);
const generations = (api: MockApi) => [
  ...api.callsTo('POST', '/v1/images/generations'),
  ...api.callsTo('POST', '/v1/videos/generations'),
];

test.describe('a community recipe in the Studio', () => {
  test('fills the form, says whose it is, and runs nothing until Generate', async ({ page, api }) => {
    servePublicRecipes(api, [RECIPE]);
    await openStudio(page, api, LINK);

    await expect(prompt(page)).toHaveValue(FILLED);
    await expect(variable(page, 'thing')).toHaveValue('teapot');
    await expect(variable(page, 'light')).toHaveValue('soft window');
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('gpt-image-1.5');
    await expect(page.getByLabel('Size')).toHaveValue('1536x1024');
    await expect(page.getByLabel('Number of images')).toHaveValue('2');

    const source = page.locator('[data-published-source]');
    await expect(source).toContainText(`From the community: Teapot still life by @${AUTHOR} · CC-BY-4.0`);
    const title = source.getByRole('link', { name: 'Teapot still life' });
    await expect(title).toHaveAttribute('href', `${GALLERY_ORIGIN}/community/${RECIPE.public_id}`);
    await expect(title).toHaveAttribute('target', '_blank');
    await expect(source.getByRole('link', { name: `@${AUTHOR}` })).toHaveAttribute('href', `${GALLERY_ORIGIN}/u/${AUTHOR}`);
    await expect(source.getByRole('button', { name: `Follow @${AUTHOR}` })).toHaveAttribute('aria-pressed', 'false');

    await expect(generate(page)).toBeEnabled();
    expect(generations(api)).toEqual([]);
    // A public read: no session goes with it.
    expect(publicReads(api)).toHaveLength(1);
    expect(publicReads(api)[0].headers.cookie).toBeUndefined();
  });

  test('Generate sends recipe.published_id and the values, with the model and params, and no prompt', async ({ page, api }) => {
    servePublicRecipes(api, [RECIPE]);
    await openStudio(page, api, LINK);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob({ publishedRecipe: RECIPE.public_id }), 'QUEUED'));

    await variable(page, 'thing').fill('copper kettle');
    await expect(prompt(page)).toHaveValue('A copper kettle on a wooden table, soft window light');
    await page.getByLabel('Size').selectOption('1024x1024');
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();

    const calls = api.callsTo('POST', '/v1/images/generations');
    expect(calls).toHaveLength(1);
    expect(calls[0].body).toEqual({
      model: 'gpt-image-1.5',
      recipe: { published_id: RECIPE.public_id, variables: { thing: 'copper kettle', light: 'soft window' } },
      size: '1024x1024',
      n: 2,
      async: true,
    });
  });

  test('a prompt changed away from the published template runs as a plain prompt', async ({ page, api }) => {
    servePublicRecipes(api, [RECIPE]);
    await openStudio(page, api, LINK);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    page.once('dialog', (dialog) => void dialog.accept());
    await prompt(page).fill('A teapot on a marble counter');
    await expect(page.getByRole('group', { name: 'Variables' })).toHaveCount(0);
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();

    const body = api.callsTo('POST', '/v1/images/generations')[0].body as Record<string, unknown>;
    expect(body.prompt).toBe('A teapot on a marble counter');
    expect(body.recipe).toBeUndefined();
    expect(body.model).toBe('gpt-image-1.5');
  });

  test('a video recipe opened on the image page moves to the video page, and is read once', async ({ page, api }) => {
    const video = makePublicRecipe({
      public_id: 'v1d3o0000001', title: 'Koi under falling rain', modality: 'video', model: 'kling-3.0',
      params: { size: '1920x1080', seconds: 10 }, template: 'Koi circling under {{weather}}',
      variables: [{ name: 'weather', default: 'falling rain' }],
      examples: [{ url: 'http://media.godance.localhost/outputs/koi.mp4', content_type: 'video/mp4' }],
    });
    servePublicRecipes(api, [video]);
    await openStudio(page, api, `/create/image?published=${video.public_id}`);

    await expect(page).toHaveURL(`/create/video?published=${video.public_id}`);
    await expect(prompt(page)).toHaveValue('Koi circling under falling rain');
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('kling-3.0');
    await expect(page.getByLabel('Size')).toHaveValue('1920x1080');
    await expect(page.getByLabel('Duration')).toHaveValue('10');
    expect(publicReads(api, video.public_id)).toHaveLength(1);
    expect(generations(api)).toEqual([]);
  });

  test('saved as a recipe of one\'s own, it records the community recipe it came from', async ({ page, api }) => {
    servePublicRecipes(api, [RECIPE]);
    const recipes = [makeRecipe()];
    serveRecipes(api, recipes);
    await openStudio(page, api, LINK);

    await page.getByRole('button', { name: 'Save as recipe' }).click();
    await expect(page.getByLabel('Recipe title')).toHaveValue('Teapot still life');
    await page.getByRole('button', { name: 'Create recipe' }).click();
    await expect(page.getByText('Saved to your recipes.')).toBeVisible();

    const body = api.callsTo('POST', '/v1/recipes')[0].body as Record<string, unknown>;
    expect(body).toMatchObject({
      title: 'Teapot still life',
      modality: 'IMAGE',
      template: RECIPE.template,
      variables: RECIPE.variables,
      model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 2 },
      source_published_recipe: RECIPE.public_id,
    });
    expect(body.source_gallery_recipe).toBeUndefined();
  });
});

test.describe('the pre-fill order', () => {
  test('?recipe= wins over ?published=, which wins over ?example=', async ({ page, api, gallery }) => {
    servePublicRecipes(api, [RECIPE]);
    serveRecipes(api, [makeRecipe()]);
    gallery.serveRecipe(imageRecipe());

    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}&published=${RECIPE.public_id}&example=${IMAGE_SLUG}`);
    await expect(prompt(page)).toHaveValue('A red mug on a kitchen shelf, soft morning light');
    expect(publicReads(api)).toEqual([]);

    await page.goto(`/create/image?published=${RECIPE.public_id}&example=${IMAGE_SLUG}&prompt=Loose`);
    await expect(prompt(page)).toHaveValue(FILLED);
    expect(gallery.recipeRequests()).toEqual([]);
  });
});

test.describe('a link that cannot be loaded', () => {
  test('a recipe that is not public opens the Studio empty, with a note', async ({ page, api }) => {
    servePublicRecipes(api, []);
    await openStudio(page, api, '/create/image?published=zzzz00000000');

    await expect(page.getByText(NOT_LOADED)).toBeVisible();
    await expect(prompt(page)).toHaveValue('');
    await expect(page.locator('[data-published-source]')).toHaveCount(0);
  });

  test('a value that is not a public id is never asked for', async ({ page, api }) => {
    servePublicRecipes(api, [RECIPE]);
    await openStudio(page, api, '/create/image');
    for (const value of ['K3V9X2M7Q1AA', '../index', 'a b', 'x'.repeat(65), '-leading', '']) {
      await page.goto(`/create/image?published=${encodeURIComponent(value)}`);
      await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
      await expect(prompt(page)).toHaveValue('');
      await expect(page.getByText(NOT_LOADED)).toHaveCount(0);
    }
    expect(api.callsTo('GET', /^\/v1\/public\//)).toEqual([]);
  });

  test('with the community switched off, the same link opens the Studio empty, with the note', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/models', { json: MODELS })
      .on('GET', /^\/v1\/public\/recipes\/[^/]+$/, problem(404, 'the community is not open', 'community_disabled'));
    mockEstimates(api);
    await page.goto(LINK);

    await expect(page.getByText(NOT_LOADED)).toBeVisible();
    await expect(page.getByRole('button', { name: /^Follow/ })).toHaveCount(0);
  });
});
