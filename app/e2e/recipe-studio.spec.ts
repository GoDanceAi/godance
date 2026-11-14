import type { Page } from '@playwright/test';
import type { UserRecipe } from '../src/lib/api';
import { accepted, inProgress, jobId, makeJob, MODELS, mockEstimates, serveJob, serveMedia } from './generation';
import { expect, problem, type ApiCall, type MockApi } from './mock-api';
import {
  FILLED_EN,
  FILLED_ZH,
  IMAGE_SLUG,
  imageRecipe,
  PROMPT_EN,
  templatedRecipe,
  TEMPLATE_EN,
  test,
} from './mock-gallery';
import { makeRecipe, makeVersion, MUG_TEMPLATE, recipeId, serveRecipes, versionId } from './user-recipes';

// Round 10 brief 03, in the Studio: a form that holds a template shows a
// field per variable and fills the prompt in as they change; the user keeps
// a setup as a recipe of their own, saves it again as a new version, and
// turns text into a variable.

const MUG_FILLED = 'A red mug on a kitchen shelf, soft morning light';
const TEMPLATE_OFF = /turns the template off/;

async function openStudio(page: Page, api: MockApi, path: string) {
  await api.signIn();
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
  await page.goto(path);
  await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
}

const prompt = (page: Page) => page.getByLabel('Prompt', { exact: true });
const variables = (page: Page) => page.getByRole('group', { name: 'Variables' });
const variable = (page: Page, name: string) => variables(page).getByLabel(name, { exact: true });
const generate = (page: Page) => page.getByRole('button', { name: /^Generate/ });

/** The body of the one generation request sent. */
function sent(api: MockApi, path = '/v1/images/generations'): Record<string, unknown> {
  const calls = api.callsTo('POST', path);
  expect(calls).toHaveLength(1);
  return calls[0].body as Record<string, unknown>;
}

/** Selects [start, end) of a text box, as dragging over it does. */
async function select(page: Page, label: string, start: number, end: number) {
  await page.getByLabel(label, { exact: true }).evaluate((box, [from, to]) => {
    const field = box as HTMLTextAreaElement;
    field.focus();
    field.setSelectionRange(from, to);
  }, [start, end]);
}

/** Answers the next confirmation, and says what it asked. */
function nextConfirm(page: Page, accept: boolean): Promise<string> {
  return new Promise((resolve) => {
    page.once('dialog', (dialog) => {
      resolve(dialog.message());
      void (accept ? dialog.accept() : dialog.dismiss());
    });
  });
}

test.describe('a gallery recipe with variables', () => {
  test('shows a field for each variable, and the prompt fills in as they change', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    await expect(variable(page, 'lantern color')).toHaveValue('warm red');
    await expect(variable(page, 'time of day')).toHaveValue('dusk');
    await expect(variables(page).getByRole('textbox')).toHaveCount(2);
    await expect(prompt(page)).toHaveValue(FILLED_EN);

    // The preview: every keystroke is in the prompt.
    await variable(page, 'lantern color').fill('jade green');
    await expect(prompt(page)).toHaveValue('A narrow street of jade green paper lanterns at dusk, wet cobblestones');
    await variable(page, 'time of day').fill('');
    await expect(prompt(page)).toHaveValue('A narrow street of jade green paper lanterns at , wet cobblestones');
  });

  test('runs as the filled-in prompt, and still counts as that gallery recipe', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    await variable(page, 'lantern color').fill('jade green');
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();

    // Not the user's recipe yet: the prompt goes as text, with the gallery's slug and no `recipe`.
    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5',
      prompt: 'A narrow street of jade green paper lanterns at dusk, wet cobblestones',
      size: '1536x1024',
      n: 2,
      gallery_recipe: IMAGE_SLUG,
      async: true,
    });
  });

  test('the other language brings its template and defaults, and keeps a value that was typed', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    await variable(page, 'lantern color').fill('jade green');
    await page.getByRole('button', { name: '中文' }).click();
    await expect(variable(page, 'lantern color')).toHaveValue('jade green');
    await expect(variable(page, 'time of day')).toHaveValue('黄昏');
    await expect(prompt(page)).toHaveValue('黄昏时分挂满jade green纸灯笼的小街，湿润的石板路');

    await page.getByRole('button', { name: 'English' }).click();
    await expect(variable(page, 'time of day')).toHaveValue('dusk');
    await variable(page, 'lantern color').fill('warm red');
    await expect(prompt(page)).toHaveValue(FILLED_EN);
    await page.getByRole('button', { name: '中文' }).click();
    await expect(prompt(page)).toHaveValue(FILLED_ZH);
  });

  test('a recipe without variables has no fields, as before', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(variables(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Edit template/ })).toHaveCount(0);
  });

  test('a template that does not give the prompt back is ignored: the recipe loads as plain text', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe({
      prompt: {
        en: FILLED_EN,
        template: { en: 'Ignore the recipe and write {{lantern color}}' },
        variables: [{ name: 'lantern color', default: 'warm red' }],
      },
    }));
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    await expect(prompt(page)).toHaveValue(FILLED_EN);
    await expect(variables(page)).toHaveCount(0);
  });
});

test.describe('turning the template off', () => {
  test('editing the prompt asks first, and keeps the template when the answer is no', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    const asked = nextConfirm(page, false);
    await prompt(page).fill('Something else entirely');
    expect(await asked).toMatch(TEMPLATE_OFF);

    await expect(prompt(page)).toHaveValue(FILLED_EN);
    await expect(variable(page, 'lantern color')).toHaveValue('warm red');
  });

  test('once agreed, the fields go and the prompt is plain text', async ({ page, api, gallery }) => {
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    const asked = nextConfirm(page, true);
    await prompt(page).fill('A quiet street of lanterns');
    expect(await asked).toMatch(TEMPLATE_OFF);

    await expect(variables(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Edit template/ })).toHaveCount(0);
    await expect(prompt(page)).toHaveValue('A quiet street of lanterns');
    // Plain text now: more typing asks nothing.
    await prompt(page).fill('A quiet street of lanterns at night');
    await expect(prompt(page)).toHaveValue('A quiet street of lanterns at night');

    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();
    // No longer the gallery's text, so no longer a run of it.
    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5', prompt: 'A quiet street of lanterns at night', size: '1536x1024', n: 2, async: true,
    });
  });
});

test.describe("one of the user's recipes (?recipe=)", () => {
  const LINK = `/create/image?recipe=${recipeId(1)}`;

  test('fills the form from its latest version, with a field for each variable', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, LINK);

    await expect(page.getByText('Your recipe:')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Mug on a kitchen shelf' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    await expect(page.getByText('· version 3')).toBeVisible();
    await expect(variable(page, 'color')).toHaveValue('red');
    await expect(variable(page, 'surface')).toHaveValue('kitchen shelf');
    await expect(prompt(page)).toHaveValue(MUG_FILLED);
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('gpt-image-1.5');
    await expect(page.getByLabel('Size')).toHaveValue('1536x1024');
    await expect(page.getByLabel('Number of images')).toHaveValue('2');
    // Loading never generates.
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('runs by reference: the version and the values, and no prompt', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, LINK);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    await variable(page, 'color').fill('blue');
    await expect(prompt(page)).toHaveValue('A blue mug on a kitchen shelf, soft morning light');
    await page.getByLabel('Size').selectOption('1024x1024');
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();

    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5',
      recipe: { version_id: versionId(1, 3), variables: { color: 'blue', surface: 'kitchen shelf' } },
      size: '1024x1024',
      n: 2,
      async: true,
    });
  });

  test('View code shows the request with the recipe', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, LINK);

    await page.getByRole('button', { name: /View code/ }).click();
    const code = page.getByRole('figure', { name: 'Start the job' });
    await expect(code).toContainText(`"version_id": "${versionId(1, 3)}"`);
    await expect(code).toContainText('"color": "red"');
    await expect(code).not.toContainText('"prompt"');
  });

  test('a video recipe opens the video Studio and runs there', async ({ page, api }) => {
    const video = makeRecipe({
      modality: 'VIDEO',
      latest_version: makeVersion({
        template: 'Koi circling under {{weather}}, slow overhead shot',
        variables: [{ name: 'weather', default: 'falling rain' }],
        model: 'kling-3.0',
        params: { size: '1920x1080', seconds: 10 },
      }),
    });
    serveRecipes(api, [video]);
    await openStudio(page, api, LINK);
    api.on('POST', '/v1/videos/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob({ modality: 'VIDEO', model: 'kling-3.0' }), 'QUEUED'));

    // The image page hands the link to the video page, where the recipe loads.
    await expect(page).toHaveURL(`/create/video?recipe=${recipeId(1)}`);
    await expect(variable(page, 'weather')).toHaveValue('falling rain');
    await expect(page.getByLabel('Duration')).toHaveValue('10');
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();

    expect(sent(api, '/v1/videos/generations')).toEqual({
      model: 'kling-3.0',
      recipe: { version_id: versionId(1, 3), variables: { weather: 'falling rain' } },
      size: '1920x1080',
      seconds: 10,
    });
  });

  test('once its text is edited, a run is a plain prompt until it is saved, and the page says so', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, LINK);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));
    const unsaved = page.getByText("isn't counted with the recipe until you save a new version");
    await expect(unsaved).toHaveCount(0);

    await page.getByRole('button', { name: /Edit template/ }).click();
    await page.getByLabel('Template', { exact: true }).fill('A {{color}} cup on a {{surface}}');
    await expect(prompt(page)).toHaveValue('A red cup on a kitchen shelf');
    await expect(unsaved).toBeVisible();

    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();
    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5', prompt: 'A red cup on a kitchen shelf', size: '1536x1024', n: 2, async: true,
    });
  });

  test('a recipe that cannot be read leaves an empty form and says so', async ({ page, api }) => {
    serveRecipes(api, []);
    await openStudio(page, api, LINK);

    await expect(page.getByText("That recipe of yours couldn't be loaded, so the form starts empty.")).toBeVisible();
    await expect(prompt(page)).toHaveValue('');
    await expect(page.getByText('Your recipe:')).toHaveCount(0);
  });
});

test.describe('what the link loads when it names several things', () => {
  test('?job= wins over ?recipe=', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    serveRecipes(api, [makeRecipe()]);
    serveJob(api, makeJob({ id: jobId(7), prompt: 'A lighthouse on a sea cliff at dusk' }));
    await openStudio(page, api, `/create/image?job=${jobId(7)}&recipe=${recipeId(1)}&example=${IMAGE_SLUG}`);

    await expect(prompt(page)).toHaveValue('A lighthouse on a sea cliff at dusk');
    await expect(page.getByText('Your recipe:')).toHaveCount(0);
    expect(api.callsTo('GET', /^\/v1\/recipes/)).toHaveLength(0);
    expect(gallery.recipeRequests()).toEqual([]);
  });

  test('?recipe= wins over ?example= and over loose values', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}&prompt=loose&model=flux-2-pro&recipe=${recipeId(1)}`);

    await expect(prompt(page)).toHaveValue(MUG_FILLED);
    await expect(page.getByLabel('Model', { exact: true })).toHaveValue('gpt-image-1.5');
    await expect(page.getByText('Your recipe:')).toBeVisible();
    await expect(page.getByText('From the gallery:')).toHaveCount(0);
    expect(gallery.recipeRequests()).toEqual([]);
  });

  test('a ?recipe= that is not a recipe id is ignored, and nothing is asked of the API for it', async ({ page, api, gallery }) => {
    gallery.serveRecipe(imageRecipe());
    await openStudio(page, api, `/create/image?recipe=..%2F..%2Fv1%2Fauth%2Fme&example=${IMAGE_SLUG}`);

    await expect(prompt(page)).toHaveValue(PROMPT_EN);
    await expect(page.getByText('From the gallery:')).toBeVisible();
    expect(api.callsTo('GET', /^\/v1\/recipes/)).toHaveLength(0);
  });

  test('a job that ran a recipe brings the recipe back, with the values it ran with', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()], {
      [recipeId(1)]: [makeVersion({
        id: versionId(1, 2), number: 2, template: 'A {{color}} mug on a table', variables: [{ name: 'color', default: 'red' }],
      })],
    });
    serveJob(api, makeJob({
      id: jobId(7),
      model: 'gpt-image-1.5',
      prompt: 'A teal mug on a table',
      params: { size: '1024x1024', n: 1 },
      recipe: { id: recipeId(1), version: 2, variables: { color: 'teal' } },
    }));
    await openStudio(page, api, `/create/image?job=${jobId(7)}`);
    api.on('POST', '/v1/images/generations', accepted(jobId(8)));
    serveJob(api, inProgress(makeJob({ id: jobId(8) }), 'QUEUED'));

    // The version it ran, not the latest: another run is another attempt of that version.
    await expect(page.getByText('· version 2')).toBeVisible();
    await expect(variable(page, 'color')).toHaveValue('teal');
    await expect(prompt(page)).toHaveValue('A teal mug on a table');
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();
    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5',
      recipe: { version_id: versionId(1, 2), variables: { color: 'teal' } },
      size: '1024x1024',
      n: 1,
      async: true,
    });
  });

  test('a job whose recipe was deleted loads as its prompt', async ({ page, api }) => {
    serveRecipes(api, []);
    serveJob(api, makeJob({
      id: jobId(7), prompt: 'A teal mug on a table', recipe: { id: recipeId(1), version: 2, variables: { color: 'teal' } },
    }));
    await openStudio(page, api, `/create/image?job=${jobId(7)}`);

    await expect(prompt(page)).toHaveValue('A teal mug on a table');
    await expect(page.getByText('Your recipe:')).toHaveCount(0);
    await expect(variables(page)).toHaveCount(0);
  });
});

test.describe('turning text into a variable', () => {
  test('the selection becomes a variable with that text as its value', async ({ page, api }) => {
    await openStudio(page, api, '/create/image');
    await prompt(page).fill('A red mug on a shelf');

    await select(page, 'Prompt', 2, 5);
    await page.getByRole('button', { name: 'Make the selection a variable' }).click();
    const naming = page.getByRole('group', { name: 'New variable' });
    await expect(naming).toContainText('“red”');
    await naming.getByLabel('Variable name').fill('color');
    await naming.getByRole('button', { name: 'Add variable' }).click();

    await expect(variable(page, 'color')).toHaveValue('red');
    await expect(page.getByLabel('Template', { exact: true })).toHaveValue('A {{color}} mug on a shelf');
    await expect(prompt(page)).toHaveValue('A red mug on a shelf');
    await variable(page, 'color').fill('blue');
    await expect(prompt(page)).toHaveValue('A blue mug on a shelf');

    // A second one, from the template this time.
    await select(page, 'Template', 21, 26);
    await page.getByRole('button', { name: 'Make the selection a variable' }).click();
    await page.getByLabel('Variable name').fill('surface');
    await page.getByLabel('Variable name').press('Enter');
    await expect(page.getByLabel('Template', { exact: true })).toHaveValue('A {{color}} mug on a {{surface}}');
    await expect(variable(page, 'surface')).toHaveValue('shelf');
    await expect(prompt(page)).toHaveValue('A blue mug on a shelf');
    // Enter in the name box added the variable; it did not generate.
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('says what is wrong: nothing selected, a name that is not one, a name already used', async ({ page, api }) => {
    await openStudio(page, api, '/create/image');
    await prompt(page).fill('A red mug on a shelf');

    await select(page, 'Prompt', 3, 3);
    await page.getByRole('button', { name: 'Make the selection a variable' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Select some of the text above first' })).toBeVisible();

    await select(page, 'Prompt', 2, 5);
    await page.getByRole('button', { name: 'Make the selection a variable' }).click();
    await page.getByLabel('Variable name').fill('my.color');
    await page.getByRole('button', { name: 'Add variable' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'A name is 1–40 letters, digits, spaces, hyphens or underscores' })).toBeVisible();
    await expect(variables(page)).toHaveCount(0);

    await page.getByLabel('Variable name').fill('color');
    await page.getByRole('button', { name: 'Add variable' }).click();
    await expect(variable(page, 'color')).toHaveValue('red');

    await select(page, 'Template', 12, 15);
    await page.getByRole('button', { name: 'Make the selection a variable' }).click();
    await page.getByLabel('Variable name').fill('color');
    await page.getByRole('button', { name: 'Add variable' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'There is already a variable called “color”.' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByLabel('Template', { exact: true })).toHaveValue('A {{color}} mug on a shelf');
  });

  test('a marker typed into the template is a variable; one left open stops the run and says where', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}`);

    await page.getByRole('button', { name: /Edit template/ }).click();
    const template = page.getByLabel('Template', { exact: true });
    await template.fill(`${MUG_TEMPLATE}, {{style}}`);
    await expect(variable(page, 'style')).toHaveValue('');
    await variable(page, 'style').fill('35mm film');
    await expect(prompt(page)).toHaveValue(`${MUG_FILLED}, 35mm film`);

    await template.fill(`${MUG_TEMPLATE}, {{sty`);
    await expect(page.getByRole('alert').filter({ hasText: 'doesn\'t begin a variable' })).toBeVisible();
    await expect(variable(page, 'style')).toHaveCount(0);
    await expect(generate(page)).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save as new version' })).toBeDisabled();
  });
});

test.describe('keeping a recipe', () => {
  const created = (api: MockApi): ApiCall[] => api.callsTo('POST', '/v1/recipes');

  test('Save as recipe keeps a plain prompt as a template with no variables, with the model and settings', async ({ page, api }) => {
    const recipes: UserRecipe[] = [];
    serveRecipes(api, recipes);
    await openStudio(page, api, '/create/image');
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    // Nothing to keep yet.
    await expect(page.getByRole('button', { name: 'Save as recipe' })).toBeDisabled();
    await prompt(page).fill('A lighthouse on a sea cliff at dusk');
    await page.getByLabel('Model', { exact: true }).selectOption('gpt-image-1.5');
    await page.getByLabel('Number of images').selectOption('3');

    await page.getByRole('button', { name: 'Save as recipe' }).click();
    const title = page.getByLabel('Recipe title');
    await expect(title).toHaveValue('A lighthouse on a sea cliff at dusk');
    await title.fill('Lighthouse at dusk');
    await page.getByRole('button', { name: 'Create recipe' }).click();

    await expect(page.getByText('Runs from here count as its attempts.')).toBeVisible();
    expect(created(api)).toHaveLength(1);
    expect(created(api)[0].body).toEqual({
      title: 'Lighthouse at dusk',
      modality: 'IMAGE',
      template: 'A lighthouse on a sea cliff at dusk',
      variables: [],
      model: 'gpt-image-1.5',
      params: { size: '1024x1024', n: 3 },
    });
    const saved = recipes[0];
    await expect(page.getByRole('link', { name: 'your recipes' })).toHaveAttribute('href', `/recipes/${saved.id}`);
    await expect(page.getByText('· version 1')).toBeVisible();

    // The form is that recipe now: a run goes by reference.
    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();
    expect(sent(api)).toEqual({
      model: 'gpt-image-1.5', recipe: { version_id: saved.latest_version.id, variables: {} }, size: '1024x1024', n: 3, async: true,
    });
  });

  test('from a gallery recipe it keeps the template, the values as defaults, and the slug', async ({ page, api, gallery }) => {
    const recipes: UserRecipe[] = [];
    serveRecipes(api, recipes);
    gallery.serveRecipe(templatedRecipe());
    await openStudio(page, api, `/create/image?example=${IMAGE_SLUG}`);

    await variable(page, 'lantern color').fill('jade green');
    await page.getByRole('button', { name: 'Save as recipe' }).click();
    // The gallery's title is offered; Enter saves, and does not generate.
    await expect(page.getByLabel('Recipe title')).toHaveValue('Paper lantern street at dusk');
    await page.getByLabel('Recipe title').press('Enter');

    await expect(page.getByText('Your recipe:')).toBeVisible();
    expect(created(api)[0].body).toEqual({
      title: 'Paper lantern street at dusk',
      modality: 'IMAGE',
      template: TEMPLATE_EN,
      variables: [{ name: 'lantern color', default: 'jade green' }, { name: 'time of day', default: 'dusk' }],
      model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 2 },
      source_gallery_recipe: IMAGE_SLUG,
    });
    expect(api.callsTo('POST', '/v1/images/generations')).toHaveLength(0);
  });

  test('a refused save says why: the account has as many recipes as it can keep', async ({ page, api }) => {
    await openStudio(page, api, '/create/image');
    api.on('POST', '/v1/recipes', problem(409,
      'You have 500 recipes, the most an account can keep. Delete one to save another.', 'recipe_limit_reached'));
    await prompt(page).fill('A lighthouse');

    await page.getByRole('button', { name: 'Save as recipe' }).click();
    await page.getByRole('button', { name: 'Create recipe' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'You have 500 recipes, the most an account can keep.' })).toBeVisible();
    await expect(page.getByText('Your recipe:')).toHaveCount(0);
  });

  test('Save as new version does nothing when nothing changed, and says so', async ({ page, api }) => {
    const recipes = [makeRecipe()];
    serveRecipes(api, recipes);
    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}`);

    await page.getByRole('button', { name: 'Save as new version' }).click();

    await expect(page.getByText('Nothing has changed since version 3, so no new version was made.')).toBeVisible();
    expect(api.callsTo('POST', `/v1/recipes/${recipeId(1)}/versions`)[0].body).toEqual({
      template: MUG_TEMPLATE,
      variables: [{ name: 'color', default: 'red' }, { name: 'surface', default: 'kitchen shelf' }],
      model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 2 },
    });
    expect(recipes[0].latest_version.number).toBe(3);
    await expect(page.getByText('· version 3')).toBeVisible();
  });

  test('Save as new version keeps what changed, and the next run is an attempt of the new version', async ({ page, api }) => {
    const recipes = [makeRecipe()];
    serveRecipes(api, recipes);
    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}`);
    api.on('POST', '/v1/images/generations', accepted(jobId(1)));
    serveJob(api, inProgress(makeJob(), 'QUEUED'));

    await variable(page, 'color').fill('blue');
    await page.getByLabel('Model', { exact: true }).selectOption('flux-2-pro');
    await page.getByRole('button', { name: 'Save as new version' }).click();

    await expect(page.getByRole('link', { name: 'version 4' })).toHaveAttribute('href', `/recipes/${recipeId(1)}`);
    await expect(page.getByText('· version 4')).toBeVisible();
    expect(recipes[0].latest_version).toMatchObject({
      number: 4,
      template: MUG_TEMPLATE,
      variables: [{ name: 'color', default: 'blue' }, { name: 'surface', default: 'kitchen shelf' }],
      model: 'flux-2-pro',
      // flux-2-pro makes one image at a time and has no 1536x1024, so its own defaults are kept.
      params: { size: '1024x1024' },
    });

    await generate(page).click();
    await expect(page.getByText('Queued. The model starts on it shortly.')).toBeVisible();
    expect(sent(api)).toEqual({
      model: 'flux-2-pro',
      recipe: { version_id: versionId(1, 4), variables: { color: 'blue', surface: 'kitchen shelf' } },
      size: '1024x1024',
      async: true,
    });

    // Saved again with nothing new: no fifth version.
    await page.getByRole('button', { name: 'Save as new version' }).click();
    await expect(page.getByText('Nothing has changed since version 4, so no new version was made.')).toBeVisible();
    expect(recipes[0].latest_version.number).toBe(4);
  });

  test('an edited template saved as a new version counts runs again', async ({ page, api }) => {
    const recipes = [makeRecipe()];
    serveRecipes(api, recipes);
    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}`);
    const unsaved = page.getByText("isn't counted with the recipe until you save a new version");

    await page.getByRole('button', { name: /Edit template/ }).click();
    await page.getByLabel('Template', { exact: true }).fill('A {{color}} cup');
    await expect(unsaved).toBeVisible();
    await page.getByRole('button', { name: 'Save as new version' }).click();

    await expect(page.getByText('· version 4')).toBeVisible();
    await expect(unsaved).toHaveCount(0);
    expect(recipes[0].latest_version).toMatchObject({
      number: 4, template: 'A {{color}} cup', variables: [{ name: 'color', default: 'red' }],
    });
  });
});
