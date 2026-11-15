import type { Page } from '@playwright/test';
import type { ApiKeySummary } from '../src/lib/api';
import { API_ORIGIN } from './api-origin';
import { batchId, makeBatch, makeRows, serveBatch } from './batches';
import { accepted, inProgress, jobId, makeJob, MODELS, mockEstimates, serveJob, serveMedia } from './generation';
import { chooseFile, fileId, openInputsStudio, serveUploads } from './inputs';
import { expect, FAKE_KEY, makeUser, problem, serveKeys, test, type MockApi } from './mock-api';
import { makeRecipe, makeVersion, MUG_TEMPLATE, recipeId, serveRecipes, versionId } from './user-recipes';

// Round 12 brief 03: "Run at scale". From a recipe, a finished Studio result
// or a batch, four ways to run the same thing outside the app: a prompt for
// an AI assistant, code, an n8n template, and a batch.

const TABS = ['Ask your AI assistant', 'Code', 'No-code (n8n)', 'Batch'];
const N8N = 'https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n';

const panel = (page: Page) => page.getByRole('region', { name: 'Run at scale' });
const tab = (page: Page, name: string) => panel(page).getByRole('tab', { name, exact: true });
const tabPanel = (page: Page, name: string) => panel(page).getByRole('tabpanel', { name, exact: true });
const figure = (page: Page, name: string) => panel(page).getByRole('figure', { name, exact: true });
const choice = (page: Page, group: string, name: string) =>
  panel(page).getByRole('group', { name: group }).getByRole('button', { name, exact: true });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

/** The text of a code block, exactly as it would be copied. */
async function codeOf(page: Page, name: string): Promise<string> {
  return (await figure(page, name).locator('pre').textContent()) ?? '';
}

/** The prompt for an AI assistant, as the first tab shows it. */
async function promptOf(page: Page): Promise<string> {
  await tab(page, 'Ask your AI assistant').click();
  return codeOf(page, 'Prompt for your AI assistant');
}

/** The recipe the prompt carries: the JSON between its heading line and "How to read it". */
function recipeIn(prompt: string): Record<string, unknown> {
  const json = /The recipe, as JSON:\n\n([\s\S]*?)\n\nHow to read it:/.exec(prompt)?.[1];
  expect(json, 'the prompt holds the recipe as JSON').toBeTruthy();
  return JSON.parse(json as string) as Record<string, unknown>;
}

/** No code and no prompt ever holds a key: only the name of the variable it is read from. */
function expectNoKey(text: string) {
  expect(text).not.toMatch(/gd_live_/);
  expect(text).not.toContain(FAKE_KEY);
}

/** A recipe's page. The first recipe has two variables, color and surface, on gpt-image-1.5. */
async function openRecipe(page: Page, api: MockApi, recipe = makeRecipe()) {
  await api.signIn();
  serveRecipes(api, [recipe]);
  await serveMedia(page);
  await page.goto(`/recipes/${recipe.id}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(recipe.title);
}

test.describe('Run at scale, on a recipe', () => {
  test('the panel has four tabs, and the assistant prompt holds the recipe and never a key', async ({ page, api }) => {
    await openRecipe(page, api);

    await expect(panel(page).getByRole('tab')).toHaveText(TABS);
    // The first tab is the one shown.
    await expect(tab(page, 'Ask your AI assistant')).toHaveAttribute('aria-selected', 'true');

    const prompt = tabPanel(page, 'Ask your AI assistant').getByRole('figure', { name: 'Prompt for your AI assistant' });
    const text = (await prompt.locator('pre').textContent()) ?? '';

    // The recipe: its template, its variables with their defaults, its model and its settings.
    expect(text).toContain(MUG_TEMPLATE);
    expect(text).toContain('"name": "color"');
    expect(text).toContain('"default": "kitchen shelf"');
    expect(text).toContain('"model": "gpt-image-1.5"');
    expect(text).toContain('"size": "1536x1024"');
    expect(text).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(recipeIn(text)).toEqual({
      title: 'Mug on a kitchen shelf',
      modality: 'IMAGE',
      template: MUG_TEMPLATE,
      variables: [{ name: 'color', default: 'red' }, { name: 'surface', default: 'kitchen shelf' }],
      inputs: [],
      model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 2 },
      version_id: versionId(1, 3),
    });
    // Where the API is, and what to read about it.
    expect(text).toContain(`API base URL: ${API_ORIGIN}/v1`);
    expect(text).toContain(`POST ${API_ORIGIN}/v1/images/generations`);
    expect(text).toContain('https://godance.ai/llms-full.txt');
    expect(text).toContain('https://godance.ai/docs');
    // The rules the script must follow.
    expect(text).toContain('GODANCE_API_KEY');
    expect(text).toContain('base_url');
    expect(text).toContain('/v1/jobs/{id}');
    expect(text).toContain('/v1/estimates');
    expect(text).toContain('402');
    expect(text).toContain('CSV');
    // Every marker was filled in.
    expect(text).not.toMatch(/\[\[[A-Z_]+\]\]/);

    // Never a key, and nothing about the account but the recipe.
    expectNoKey(text);
    const user = makeUser();
    expect(text).not.toContain(user.email);
    expect(text).not.toContain(user.id);
    expect(text).not.toContain(user.fullName);

    await expect(prompt.getByRole('button', { name: 'Copy' })).toBeVisible();
    expect(await noOverflow(page)).toBe(true);
  });

  test('Copy puts the whole prompt on the clipboard', async ({ page, api }) => {
    await page.addInitScript(() => {
      // Record what Copy writes, without the browser's permission prompt.
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: (text: string) => { (window as unknown as { copied: string }).copied = text; return Promise.resolve(); } },
      });
    });
    await openRecipe(page, api);

    const text = await promptOf(page);
    await figure(page, 'Prompt for your AI assistant').getByRole('button', { name: 'Copy' }).click();

    await expect(figure(page, 'Prompt for your AI assistant').getByRole('button', { name: 'Copied' })).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { copied: string }).copied)).toBe(text);
  });

  test('the tabs work from the keyboard', async ({ page, api }) => {
    await openRecipe(page, api);

    await tab(page, 'Ask your AI assistant').focus();
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Code')).toBeFocused();
    await expect(tab(page, 'Code')).toHaveAttribute('aria-selected', 'true');
    await expect(tabPanel(page, 'Code')).toBeVisible();

    await page.keyboard.press('End');
    await expect(tab(page, 'Batch')).toBeFocused();
    await expect(tabPanel(page, 'Batch')).toBeVisible();
    // It wraps round, and Home goes back to the first.
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Ask your AI assistant')).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('ArrowLeft');
    await expect(tab(page, 'Batch')).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('Home');
    await expect(tab(page, 'Ask your AI assistant')).toBeFocused();
    // Only the tab that is open is in the tab order.
    await expect(tab(page, 'Code')).toHaveAttribute('tabindex', '-1');
  });

  test('Code: the recipe by reference, in curl, Python and Java, polling a job or waiting', async ({ page, api }) => {
    await openRecipe(page, api);
    await tab(page, 'Code').click();

    // curl, asking for a job: the request names the version and gives each variable its default.
    await expect(choice(page, 'Language', 'curl')).toHaveAttribute('aria-pressed', 'true');
    await expect(choice(page, 'How it answers', 'Poll a job')).toHaveAttribute('aria-pressed', 'true');
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`curl ${API_ORIGIN}/v1/images/generations`);
    expect(start).toContain('-H "Authorization: Bearer $GODANCE_API_KEY"');
    expect(start).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(start).toContain('"color": "red"');
    expect(start).toContain('"surface": "kitchen shelf"');
    expect(start).toContain('"size": "1536x1024"');
    expect(start).toContain('"async": true');
    expect(start).not.toContain('"prompt"');
    expect(await codeOf(page, 'Check on it')).toContain(`curl ${API_ORIGIN}/v1/jobs/$JOB_ID`);

    // Python: the OpenAI SDK with base_url, the key from the environment, and the job read until it ends.
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    const python = await codeOf(page, 'Python script');
    expect(python).toContain('from openai import OpenAI');
    expect(python).toContain(`base_url="${API_ORIGIN}/v1"`);
    expect(python).toContain('api_key=os.environ["GODANCE_API_KEY"]');
    expect(python).toContain('client.post(\n    "/images/generations",');
    expect(python).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(python).toContain('"async": True');
    expect(python).toContain(`client.get(f"/jobs/{started['job_id']}", cast_to=httpx.Response)`);
    await expect(panel(page).getByText('pip install openai')).toBeVisible();

    // Java: Spring's WebClient, the same body, the same polling.
    await choice(page, 'Language', 'Java (Spring WebClient)').click();
    const java = await codeOf(page, 'Java class');
    expect(java).toContain('import org.springframework.web.reactive.function.client.WebClient;');
    expect(java).toContain(`.baseUrl("${API_ORIGIN}")`);
    expect(java).toContain('System.getenv("GODANCE_API_KEY")');
    expect(java).toContain('godance.post().uri("/v1/images/generations")');
    expect(java).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(java).toContain('godance.get().uri("/v1/jobs/{id}", started.get("job_id"))');

    // Waiting, with a synchronous key: no "async", and the images are read from the answer.
    await choice(page, 'How it answers', 'Wait for the image').click();
    const waitingJava = await codeOf(page, 'Java class');
    expect(waitingJava).not.toContain('"async"');
    expect(waitingJava).toContain('answer.get("data")');
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    const waitingPython = await codeOf(page, 'Python script');
    expect(waitingPython).not.toContain('"async"');
    expect(waitingPython).toContain('answer.get("data")');
    await choice(page, 'Language', 'curl').click();
    const waitingCurl = await codeOf(page, 'Send the request');
    expect(waitingCurl).not.toContain('"async"');
    await expect(figure(page, 'Check on it')).toHaveCount(0);
    // A synchronous key is made on the keys page, which opens ready for this recipe.
    await expect(tabPanel(page, 'Code').getByRole('link', { name: 'API keys' }))
      .toHaveAttribute('href', `/keys?for=${encodeURIComponent('Mug on a kitchen shelf')}`);

    for (const code of [start, python, java, waitingJava, waitingPython, waitingCurl]) expectNoKey(code);
    expect(await noOverflow(page)).toBe(true);
  });

  test('a recipe with an input slot: the upload first, then the request that names it', async ({ page, api }) => {
    await openRecipe(page, api, makeRecipe({
      latest_version: makeVersion({
        template: 'The product on a {{surface}}',
        variables: [{ name: 'surface', default: 'marble counter' }],
        inputs: [{ slot: 'product photo', role: 'image', hint: 'One product on a plain background' }],
      }),
    }));

    // The prompt carries the slot, and no file.
    const recipe = recipeIn(await promptOf(page));
    expect(recipe.inputs).toEqual([{ slot: 'product photo', role: 'image', hint: 'One product on a plain background' }]);

    await tab(page, 'Code').click();
    await expect(tabPanel(page, 'Code')).toContainText('put the id the upload answers with in place of FILE_ID (for “product photo”)');
    const upload = await codeOf(page, 'Upload an image');
    expect(upload).toContain(`curl ${API_ORIGIN}/v1/files`);
    expect(upload).toContain('-F "file=@product-photo.png"');
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain('"product photo": {');
    expect(start).toContain('"file_id": "FILE_ID"');
    // The recipe names its inputs, so the request goes to the generations route.
    expect(start).toContain(`curl ${API_ORIGIN}/v1/images/generations`);

    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    expect(await codeOf(page, 'Upload an image'))
      .toContain('client.files.create(file=open("product-photo.png", "rb"), purpose="generation_input")');
    await choice(page, 'Language', 'Java (Spring WebClient)').click();
    expect(await codeOf(page, 'Upload an image')).toContain('form.part("file", new FileSystemResource("product-photo.png"));');

    // The n8n image template sends a prompt alone: the tab says so rather than pretend.
    await tab(page, 'No-code (n8n)').click();
    await expect(tabPanel(page, 'No-code (n8n)')).toContainText(
      'This recipe works from an input image, and the image template sends a prompt alone.');
  });

  test('a video recipe: always a job, on the video route, with the video template', async ({ page, api }) => {
    await openRecipe(page, api, makeRecipe({
      title: 'Koi pond in the rain',
      modality: 'VIDEO',
      latest_version: makeVersion({
        template: 'Koi circling under {{weather}}, slow overhead shot',
        variables: [{ name: 'weather', default: 'falling rain' }],
        model: 'kling-3.0',
        params: { size: '1920x1080', seconds: 10 },
      }),
    }));

    const prompt = await promptOf(page);
    expect(prompt).toContain(`POST ${API_ORIGIN}/v1/videos/generations`);
    expect(recipeIn(prompt)).toMatchObject({ modality: 'VIDEO', model: 'kling-3.0', params: { size: '1920x1080', seconds: 10 } });

    await tab(page, 'Code').click();
    // No choice of how it answers: a video never waits.
    await expect(panel(page).getByRole('group', { name: 'How it answers' })).toHaveCount(0);
    await expect(tabPanel(page, 'Code')).toContainText('A video is always a job');
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`curl ${API_ORIGIN}/v1/videos/generations`);
    expect(start).toContain('"seconds": 10');
    expect(start).not.toContain('"async"');
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    expect(await codeOf(page, 'Python script')).toContain('client.post(\n    "/videos/generations",');
    await choice(page, 'Language', 'Java (Spring WebClient)').click();
    expect(await codeOf(page, 'Java class')).toContain('godance.post().uri("/v1/videos/generations")');

    await tab(page, 'No-code (n8n)').click();
    await expect(tabPanel(page, 'No-code (n8n)').getByRole('link', { name: 'Download the template' }))
      .toHaveAttribute('href', `${N8N}/godance-sheet-to-videos.json`);
    // A video's row also says how long the clip is.
    const cells = tabPanel(page, 'No-code (n8n)').getByLabel('What a row of the sheet holds');
    await expect(cells.getByRole('term')).toHaveText(['prompt', 'model', 'size', 'seconds']);
    await expect(cells.getByRole('definition').last()).toHaveText('10');
  });

  test('No-code: the template to use, its download, and four steps to import it and set the key', async ({ page, api }) => {
    await openRecipe(page, api);
    await tab(page, 'No-code (n8n)').click();

    const n8n = tabPanel(page, 'No-code (n8n)');
    await expect(n8n).toContainText('godance-sheet-to-images.json');
    const download = n8n.getByRole('link', { name: 'Download the template' });
    await expect(download).toHaveAttribute('href', `${N8N}/godance-sheet-to-images.json`);
    await expect(download).toHaveAttribute('download', 'godance-sheet-to-images.json');

    const steps = n8n.getByRole('list', { name: 'Set the key and import the template' }).getByRole('listitem');
    await expect(steps).toHaveCount(4);
    // The key goes into a credential by name, never into the workflow.
    await expect(steps.nth(0)).toContainText('a credential of the type "Header Auth" and name it "Godance API"');
    await expect(steps.nth(0)).toContainText('its Value is the word Bearer, a space, then your API key');
    await expect(steps.nth(0)).toContainText('never written into the workflow');
    await expect(steps.nth(1)).toContainText('Download godance-sheet-to-images.json');
    await expect(steps.nth(1)).toContainText('Import from File');
    await expect(steps.nth(2)).toContainText('"Get the rows" and "Update the row"');
    await expect(steps.nth(3)).toContainText('Execute workflow');
    await expect(steps.nth(3)).toContainText('try three rows first');

    // What this recipe puts in a row of the sheet: the columns the template reads.
    const cells = n8n.getByLabel('What a row of the sheet holds');
    await expect(cells.getByRole('term')).toHaveText(['prompt', 'model', 'size']);
    await expect(cells.getByRole('definition')).toHaveText(
      ['the template, with its variables filled in for that row', 'gpt-image-1.5', '1536x1024']);
    await expect(n8n.getByRole('link', { name: "templates' notes" }))
      .toHaveAttribute('href', 'https://github.com/GoDanceAi/godance/tree/main/integrations/n8n#readme');
    expectNoKey((await n8n.textContent()) ?? '');
  });

  test('Batch: a link to the new-batch page of this recipe', async ({ page, api }) => {
    await openRecipe(page, api);
    await tab(page, 'Batch').click();

    const link = tabPanel(page, 'Batch').getByRole('link', { name: 'Run as a batch' });
    await expect(link).toHaveAttribute('href', `/recipes/${recipeId(1)}/batch`);
  });

  test('a renamed recipe is handed over under its new name', async ({ page, api }) => {
    await openRecipe(page, api);

    await page.getByRole('button', { name: 'Rename' }).click();
    await page.getByLabel('Recipe title').fill('Mug, morning light');
    await page.getByRole('button', { name: 'Save title' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mug, morning light');

    expect(recipeIn(await promptOf(page)).title).toBe('Mug, morning light');
  });
});

test.describe('Run at scale: a key for this', () => {
  test('one click makes a key named after the recipe with a 5.00 daily cap, shown once', async ({ page, api }) => {
    await openRecipe(page, api);
    const stored: ApiKeySummary[] = [];
    serveKeys(api, stored);

    // Filled in already: the recipe's name, and five dollars a day.
    await expect(panel(page).getByLabel('Key name')).toHaveValue('Mug on a kitchen shelf');
    await expect(panel(page).getByLabel('Daily cap (USD)')).toHaveValue('5.00');
    await expect(panel(page).getByLabel(/^Expires/)).toHaveValue('');

    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();

    const shown = panel(page).getByRole('status', { name: 'New API key' });
    await expect(shown).toContainText('Key created: Mug on a kitchen shelf');
    await expect(shown).toContainText(FAKE_KEY);
    await expect(shown).toContainText('Set it as GODANCE_API_KEY');
    await expect(shown).toContainText('It can spend up to $5.00 a day.');
    expect(api.callsTo('POST', '/v1/keys').map((call) => call.body)).toEqual(
      [{ name: 'Mug on a kitchen shelf', daily_cap_cents: 500 }]);
    expect(stored[0]).toMatchObject({ name: 'Mug on a kitchen shelf', daily_cap_cents: 500, expires_at: null });

    // The code still reads the variable: the key itself is in no prompt and no code.
    expectNoKey(await promptOf(page));
    await tab(page, 'Code').click();
    for (const language of ['curl', 'Python (OpenAI SDK)', 'Java (Spring WebClient)']) {
      await choice(page, 'Language', language).click();
      const blocks = await tabPanel(page, 'Code').locator('pre').allTextContents();
      expect(blocks.length).toBeGreaterThan(0);
      for (const block of blocks) {
        expectNoKey(block);
        expect(block).toContain('GODANCE_API_KEY');
      }
    }

    // Shown once: dismissed, it is gone from the page for good.
    await shown.getByRole('button', { name: "I've saved it — dismiss" }).click();
    await expect(page.getByText(FAKE_KEY)).toHaveCount(0);
    await expect(shown).toHaveCount(0);
    expect(await noOverflow(page)).toBe(true);
  });

  test('the cap can be changed and an expiry added before the click', async ({ page, api }) => {
    await openRecipe(page, api);
    serveKeys(api, []);

    await panel(page).getByLabel('Key name').fill('Mug batch script');
    await panel(page).getByLabel('Daily cap (USD)').fill('12.5');
    await panel(page).getByLabel(/^Expires/).fill('2031-03-31');
    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();

    const shown = panel(page).getByRole('status', { name: 'New API key' });
    await expect(shown).toContainText('Key created: Mug batch script');
    await expect(shown).toContainText('It can spend up to $12.50 a day.');
    await expect(shown).toContainText('It stops working after Mar 31, 2031.');
    const body = api.callsTo('POST', '/v1/keys')[0].body as { name: string; daily_cap_cents: number; expires_at: string };
    expect(body.name).toBe('Mug batch script');
    expect(body.daily_cap_cents).toBe(1250);
    // The end of the day that was picked, where the user is.
    expect(await page.evaluate((iso) => {
      const at = new Date(iso);
      return [at.getFullYear(), at.getMonth() + 1, at.getDate(), at.getHours(), at.getMinutes(), at.getSeconds()];
    }, body.expires_at)).toEqual([2031, 3, 31, 23, 59, 59]);
  });

  test('a cap that is not an amount, or a day already gone, is refused before any request', async ({ page, api }) => {
    await openRecipe(page, api);
    serveKeys(api, []);

    await panel(page).getByLabel('Daily cap (USD)').fill('five');
    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();
    await expect(panel(page).getByRole('alert')).toHaveText('Enter an amount in dollars, such as 5.00, or leave it empty for no cap.');

    // Less than a cent is not a cap either.
    await panel(page).getByLabel('Daily cap (USD)').fill('0.00');
    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();
    await expect(panel(page).getByRole('alert')).toContainText('Enter an amount in dollars');

    await panel(page).getByLabel('Daily cap (USD)').fill('5.00');
    await panel(page).getByLabel(/^Expires/).fill('2020-01-01');
    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();
    await expect(panel(page).getByRole('alert')).toHaveText('Pick a day from today on, or leave it empty for a key that does not expire.');

    expect(api.callsTo('POST', '/v1/keys')).toHaveLength(0);
    await expect(panel(page).getByRole('status', { name: 'New API key' })).toHaveCount(0);
  });

  test('emptied, the cap is left off: the key then has none', async ({ page, api }) => {
    await openRecipe(page, api);
    serveKeys(api, []);

    await panel(page).getByLabel('Daily cap (USD)').fill('');
    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();

    await expect(panel(page).getByRole('status', { name: 'New API key' })).toContainText('It has no daily cap');
    expect(api.callsTo('POST', '/v1/keys')[0].body).toEqual({ name: 'Mug on a kitchen shelf' });
  });

  test('a key the API refuses says why, and shows no key', async ({ page, api }) => {
    await openRecipe(page, api);
    api.on('POST', '/v1/keys', problem(429, 'Key limit reached for this account.'));

    await panel(page).getByRole('button', { name: 'Create a key for this' }).click();

    await expect(panel(page).getByRole('alert')).toHaveText('Key limit reached for this account.');
    await expect(panel(page).getByRole('status', { name: 'New API key' })).toHaveCount(0);
    // The form is as it was, to try again.
    await expect(panel(page).getByLabel('Key name')).toHaveValue('Mug on a kitchen shelf');
    await expect(panel(page).getByRole('button', { name: 'Create a key for this' })).toBeEnabled();
  });
});

// ===== A finished Studio result =====

const PROMPT = 'A lighthouse on a sea cliff at dusk';

async function openStudio(page: Page, api: MockApi, path = '/create/image') {
  await api.signIn();
  await page.clock.install();
  api.on('GET', '/v1/models', { json: MODELS });
  mockEstimates(api);
  await serveMedia(page);
  await page.goto(path);
  await expect(page.getByLabel('Model', { exact: true })).toBeEnabled();
}

/** Opens the panel under a finished result. */
async function openPanel(page: Page) {
  await panel(page).getByRole('button', { name: /^Run at scale/ }).click();
  await expect(panel(page).getByRole('tab')).toHaveText(TABS);
}

test.describe('Run at scale, on a finished Studio result', () => {
  test('an image run: the panel appears when the result is done, with the request that made it', async ({ page, api }) => {
    await openStudio(page, api);
    const id = jobId(1);
    const done = makeJob({ id, model: 'gpt-image-1.5', prompt: PROMPT, params: { size: '1024x1024', n: 2 } });
    api.on('POST', '/v1/images/generations', accepted(id));
    const job = serveJob(api, inProgress(done, 'QUEUED'));

    await page.getByLabel('Model').selectOption('gpt-image-1.5');
    await page.getByLabel('Number of images').selectOption('2');
    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();

    // Not while it runs: there is no result to hand over yet.
    await expect(page.getByRole('status')).toContainText('Queued');
    await expect(panel(page)).toHaveCount(0);

    job.set(done);
    await page.clock.fastForward(2_000);
    await expect(page.getByRole('status')).toContainText('Done');

    // Behind a button, so the result stays the first thing on the page.
    const toggle = panel(page).getByRole('button', { name: /^Run at scale/ });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(panel(page).getByRole('tab')).toHaveCount(0);
    await openPanel(page);
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');

    // The prompt for an assistant: the plain prompt is a template without variables.
    const prompt = await promptOf(page);
    expect(recipeIn(prompt)).toEqual({
      modality: 'IMAGE',
      template: PROMPT,
      variables: [],
      inputs: [],
      model: 'gpt-image-1.5',
      params: { size: '1024x1024', n: 2 },
    });
    expectNoKey(prompt);

    // The code is the request that was sent, to the letter.
    await tab(page, 'Code').click();
    const sent = api.callsTo('POST', '/v1/images/generations')[0].body;
    expect(sent).toEqual({ model: 'gpt-image-1.5', prompt: PROMPT, size: '1024x1024', n: 2, async: true });
    const start = await codeOf(page, 'Start the job');
    expect(JSON.parse(/-d '([\s\S]*)'$/.exec(start)?.[1] ?? '')).toEqual(sent);
    expect(start).toContain(`curl ${API_ORIGIN}/v1/images/generations`);

    // Waiting with a synchronous key is the OpenAI SDK's own call, unchanged.
    await choice(page, 'How it answers', 'Wait for the image').click();
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    const python = await codeOf(page, 'Python script');
    expect(python).toContain('result = client.images.generate(');
    expect(python).toContain('    model="gpt-image-1.5",');
    expect(python).toContain(`    prompt="${PROMPT}",`);
    expect(python).toContain('    size="1024x1024",');
    expect(python).toContain('    n=2,');
    expect(python).not.toContain('async');
    expect(python).toContain('for image in result.data or []:');
    // Polling asks for a job, with the same SDK.
    await choice(page, 'How it answers', 'Poll a job').click();
    expect(await codeOf(page, 'Python script')).toContain('"async": True');

    // Not a saved recipe: a batch needs one.
    await tab(page, 'Batch').click();
    await expect(tabPanel(page, 'Batch')).toContainText('This is not a saved recipe yet');
    await expect(tabPanel(page, 'Batch').getByRole('link')).toHaveCount(0);

    // A key for it is named after the start of the prompt.
    await expect(panel(page).getByLabel('Key name')).toHaveValue(PROMPT);
    await expect(panel(page).getByLabel('Daily cap (USD)')).toHaveValue('5.00');
    expect(await noOverflow(page)).toBe(true);
  });

  test('the code stays the code of the result when the form is changed afterwards', async ({ page, api }) => {
    await openStudio(page, api);
    const done = makeJob({ id: jobId(2), prompt: PROMPT });
    api.on('POST', '/v1/images/generations', accepted(done.id));
    serveJob(api, done);

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('status')).toContainText('Done');

    // The form moves on; the result is still the one that was made.
    await page.getByLabel('Prompt').fill('Something else entirely');
    await openPanel(page);
    await tab(page, 'Code').click();
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`"prompt": "${PROMPT}"`);
    expect(start).not.toContain('Something else entirely');
  });

  test('a failed run offers nothing to hand over', async ({ page, api }) => {
    await openStudio(page, api);
    const failed = makeJob({
      id: jobId(3), status: 'FAILED', outputs: [], outputUrl: null, actualCostCents: 0, errorMessage: 'The provider refused this prompt.',
    });
    api.on('POST', '/v1/images/generations', accepted(failed.id));
    serveJob(api, failed);

    await page.getByLabel('Prompt').fill(PROMPT);
    await page.getByRole('button', { name: /^Generate/ }).click();

    await expect(page.getByRole('status')).toContainText('Failed');
    await expect(panel(page)).toHaveCount(0);
  });

  test('an edit: the code goes to the edits route with the input it used', async ({ page, api }) => {
    await openInputsStudio(page, api);
    serveUploads(api);
    const done = makeJob({ id: jobId(4), inputs: [{ role: 'image', file_id: fileId(1), url: null }] });
    api.on('POST', '/v1/images/edits', accepted(done.id));
    serveJob(api, done);

    await page.getByLabel('Prompt').fill('The mug on a marble counter');
    await chooseFile(page, 'Choose a file for the image 1');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('img', { name: /Generated image/ })).toBeVisible();

    await openPanel(page);
    // The prompt names the slot and its role, never the file.
    const prompt = await promptOf(page);
    expect(prompt).toContain(`POST ${API_ORIGIN}/v1/images/edits`);
    expect(recipeIn(prompt).inputs).toEqual([{ slot: 'image', role: 'image', hint: '' }]);
    expect(prompt).not.toContain(fileId(1));

    await tab(page, 'Code').click();
    const sent = api.callsTo('POST', '/v1/images/edits')[0].body;
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`curl ${API_ORIGIN}/v1/images/edits`);
    expect(JSON.parse(/-d '([\s\S]*)'$/.exec(start)?.[1] ?? '')).toEqual(sent);
    expect(start).toContain(`"file_id": "${fileId(1)}"`);
    // The file is already the user's: nothing to upload first.
    await expect(figure(page, 'Upload an image')).toHaveCount(0);

    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    const python = await codeOf(page, 'Python script');
    expect(python).toContain('client.post(\n    "/images/edits",');
    expect(python).toContain(`"file_id": "${fileId(1)}"`);
    // An edit that waits is still a request the SDK has no call for with a file id: it is posted as it is.
    await choice(page, 'How it answers', 'Wait for the image').click();
    const waiting = await codeOf(page, 'Python script');
    expect(waiting).toContain('client.post(\n    "/images/edits",');
    expect(waiting).not.toContain('"async"');
    await choice(page, 'Language', 'Java (Spring WebClient)').click();
    expect(await codeOf(page, 'Java class')).toContain('godance.post().uri("/v1/images/edits")');
  });

  test('a video run: the code starts the video job and reads it', async ({ page, api }) => {
    await openStudio(page, api, '/create/video');
    const done = makeJob({
      id: jobId(5), modality: 'VIDEO', model: 'kling-3.0', provider: 'KLING', prompt: 'Waves at dawn',
      params: { size: '1280x720', seconds: 5 },
      outputs: [{ index: 0, url: 'http://media.godance.localhost/outputs/out-1.mp4?X-Amz-Signature=e2e', contentType: 'video/mp4', sizeBytes: 1_000_000, revisedPrompt: null }],
    });
    api.on('POST', '/v1/videos/generations', accepted(done.id));
    serveJob(api, done);

    await page.getByLabel('Prompt').fill('Waves at dawn');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('status')).toContainText('Done');

    await openPanel(page);
    const prompt = await promptOf(page);
    expect(prompt).toContain(`POST ${API_ORIGIN}/v1/videos/generations`);
    expect(recipeIn(prompt)).toMatchObject({ modality: 'VIDEO', template: 'Waves at dawn', model: 'kling-3.0' });

    await tab(page, 'Code').click();
    await expect(panel(page).getByRole('group', { name: 'How it answers' })).toHaveCount(0);
    const sent = api.callsTo('POST', '/v1/videos/generations')[0].body;
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`curl ${API_ORIGIN}/v1/videos/generations`);
    expect(JSON.parse(/-d '([\s\S]*)'$/.exec(start)?.[1] ?? '')).toEqual(sent);
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    const python = await codeOf(page, 'Python script');
    expect(python).toContain('client.post(\n    "/videos/generations",');
    expect(python).toContain('"seconds": 5,');
    expect(python).not.toContain('"async"');

    await tab(page, 'No-code (n8n)').click();
    await expect(tabPanel(page, 'No-code (n8n)')).toContainText('godance-sheet-to-videos.json');
  });

  test('a run of one of the user\'s recipes: by reference in the code, and a batch of it one click away', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openStudio(page, api, `/create/image?recipe=${recipeId(1)}`);
    const done = makeJob({
      id: jobId(6), model: 'gpt-image-1.5', prompt: 'A blue mug on a kitchen shelf, soft morning light',
      recipe: { id: recipeId(1), version: 3, variables: { color: 'blue', surface: 'kitchen shelf' } },
    });
    api.on('POST', '/v1/images/generations', accepted(done.id));
    serveJob(api, done);

    await page.getByRole('group', { name: 'Variables' }).getByLabel('color', { exact: true }).fill('blue');
    await page.getByRole('button', { name: /^Generate/ }).click();
    await expect(page.getByRole('status')).toContainText('Done');

    await openPanel(page);
    // The recipe, with the values this run had as what each variable starts from.
    expect(recipeIn(await promptOf(page))).toMatchObject({
      title: 'Mug on a kitchen shelf',
      template: MUG_TEMPLATE,
      variables: [{ name: 'color', default: 'blue' }, { name: 'surface', default: 'kitchen shelf' }],
      version_id: versionId(1, 3),
    });

    await tab(page, 'Code').click();
    const start = await codeOf(page, 'Start the job');
    expect(JSON.parse(/-d '([\s\S]*)'$/.exec(start)?.[1] ?? '')).toEqual(api.callsTo('POST', '/v1/images/generations')[0].body);
    expect(start).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(start).toContain('"color": "blue"');

    await tab(page, 'Batch').click();
    await expect(tabPanel(page, 'Batch').getByRole('link', { name: 'Run as a batch' }))
      .toHaveAttribute('href', `/recipes/${recipeId(1)}/batch`);
    // The key is named after the recipe.
    await expect(panel(page).getByLabel('Key name')).toHaveValue('Mug on a kitchen shelf');
  });

  test('a result opened from its link is handed over from the job itself', async ({ page, api }) => {
    const done = makeJob({ id: jobId(7), model: 'gpt-image-1.5', prompt: PROMPT, params: { size: '1536x1024', n: 1 } });
    serveJob(api, done);
    await openStudio(page, api, `/create/image?job=${done.id}`);
    await expect(page.getByRole('status')).toContainText('Done');

    await openPanel(page);
    expect(recipeIn(await promptOf(page))).toEqual({
      modality: 'IMAGE', template: PROMPT, variables: [], inputs: [], model: 'gpt-image-1.5',
      params: { size: '1536x1024', n: 1 },
    });
    await tab(page, 'Code').click();
    const start = await codeOf(page, 'Start the job');
    expect(JSON.parse(/-d '([\s\S]*)'$/.exec(start)?.[1] ?? '')).toEqual(
      { model: 'gpt-image-1.5', prompt: PROMPT, size: '1536x1024', n: 1, async: true });
  });
});

// ===== A batch =====

/** A batch page: the batch and its rows, and the recipe it ran. */
async function openBatch(page: Page, api: MockApi, batch = makeBatch({ status: 'DONE', rows_total: 3, rows_done: 3, rows_failed: 0, rows_running: 0 })) {
  await api.signIn();
  await serveMedia(page);
  serveBatch(api, { batch, rows: makeRows(3, 3) });
  await page.goto(`/batches/${batch.id}`);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

test.describe('Run at scale, on a batch', () => {
  test('the panel reads the recipe when it is opened, and hands over the version the batch ran', async ({ page, api }) => {
    serveRecipes(api, [makeRecipe()]);
    await openBatch(page, api);

    // The page itself asks for nothing more: the recipe is read when the panel is opened.
    const toggle = panel(page).getByRole('button', { name: /^Run at scale/ });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(api.callsTo('GET', /^\/v1\/recipes\//)).toHaveLength(0);

    await openPanel(page);
    expect(api.callsTo('GET', `/v1/recipes/${recipeId(1)}`)).toHaveLength(1);
    // The latest version is the one it ran: its versions are not asked for.
    expect(api.callsTo('GET', `/v1/recipes/${recipeId(1)}/versions`)).toHaveLength(0);

    const prompt = await promptOf(page);
    expect(recipeIn(prompt)).toMatchObject({
      title: 'Mug on a kitchen shelf', template: MUG_TEMPLATE, model: 'gpt-image-1.5', version_id: versionId(1, 3),
    });
    expectNoKey(prompt);
    // Nothing of the batch itself goes to the assistant: not its id, not its rows' values.
    expect(prompt).not.toContain(batchId(1));
    expect(prompt).not.toContain('shelf 1');

    await tab(page, 'Code').click();
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`"version_id": "${versionId(1, 3)}"`);
    expect(start).toContain(`curl ${API_ORIGIN}/v1/images/generations`);
    await choice(page, 'Language', 'Python (OpenAI SDK)').click();
    expect(await codeOf(page, 'Python script')).toContain(`"version_id": "${versionId(1, 3)}"`);
    await choice(page, 'Language', 'Java (Spring WebClient)').click();
    expect(await codeOf(page, 'Java class')).toContain(`"version_id": "${versionId(1, 3)}"`);

    await tab(page, 'No-code (n8n)').click();
    await expect(tabPanel(page, 'No-code (n8n)').getByRole('list').getByRole('listitem')).toHaveCount(4);

    await tab(page, 'Batch').click();
    await expect(tabPanel(page, 'Batch').getByRole('link', { name: 'Run as a batch' }))
      .toHaveAttribute('href', `/recipes/${recipeId(1)}/batch`);
    await expect(panel(page).getByLabel('Key name')).toHaveValue('Mug on a kitchen shelf');
    expect(await noOverflow(page)).toBe(true);
  });

  test('a batch of an older version, on another model, hands over that version and that model', async ({ page, api }) => {
    const older = makeVersion({
      id: versionId(1, 2), number: 2, template: 'A {{color}} mug on a table', variables: [{ name: 'color', default: 'red' }],
      model: 'flux-2-pro', params: { size: '1024x1024' },
    });
    serveRecipes(api, [makeRecipe()], { [recipeId(1)]: [older] });
    await openBatch(page, api, makeBatch({
      status: 'DONE', rows_total: 3, rows_done: 3, rows_failed: 0, rows_running: 0, version_id: versionId(1, 2), model: 'sdxl',
    }));

    await openPanel(page);

    expect(recipeIn(await promptOf(page))).toMatchObject({
      template: 'A {{color}} mug on a table', model: 'sdxl', params: { size: '1024x1024' }, version_id: versionId(1, 2),
    });
    await tab(page, 'Code').click();
    const start = await codeOf(page, 'Start the job');
    expect(start).toContain(`"version_id": "${versionId(1, 2)}"`);
    expect(start).toContain('"model": "sdxl"');
  });

  test('a recipe that cannot be read says so, and can be tried again', async ({ page, api }) => {
    await openBatch(page, api);
    let fail = true;
    api.on('GET', `/v1/recipes/${recipeId(1)}`, () => (fail ? problem(503, 'Try again shortly.') : { json: makeRecipe() }));

    await panel(page).getByRole('button', { name: /^Run at scale/ }).click();
    await expect(panel(page).getByRole('alert')).toContainText("Couldn't load the recipe this ran.");
    await expect(panel(page).getByRole('tab')).toHaveCount(0);

    fail = false;
    await panel(page).getByRole('button', { name: 'Try again' }).click();
    await expect(panel(page).getByRole('tab')).toHaveText(TABS);
  });

  test('a batch whose recipe was deleted has no panel', async ({ page, api }) => {
    await openBatch(page, api, makeBatch({
      status: 'DONE', rows_total: 3, rows_done: 3, rows_failed: 0, rows_running: 0, recipe_id: null, recipe_title: null,
    }));

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Batch of a deleted recipe');
    await expect(panel(page)).toHaveCount(0);
  });
});
