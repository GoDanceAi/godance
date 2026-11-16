/**
 * "Run at scale" on a gallery recipe's page, through a real `astro build`
 * against the fixtures, read back from the output folder.
 *
 * The block shows what the app's own panel shows: the prompt for an AI
 * assistant, the request as code, and the n8n steps. Their wording has one
 * source, three files under app/src/lib that the page imports. This test
 * imports the same three files and holds the built pages to them, so the
 * site cannot show a prompt or a line of code the app does not.
 *
 * Those files are TypeScript, which Node runs as it is from 22.18 on.
 *
 * The build writes to a folder of its own under .astro/, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  ASSISTANT_PROMPT,
  ASSISTANT_PROMPT_VERSION,
  assistantPrompt,
  promptConditions,
  promptWording,
} from '../../../app/src/lib/assistant-prompt.ts';
import { N8N_CREDENTIAL, n8nInputsNote, n8nRow, n8nSteps, n8nTemplate } from '../../../app/src/lib/n8n.ts';
import { codeFor, createPath, uploadCode } from '../../../app/src/lib/run-code.ts';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = path.join(ROOT, 'fixtures/gallery/recipes');
const astroPackage = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

const API = 'https://api.godance.ai';
const LANGUAGES = ['curl', 'python', 'java'];

const recipes = readdirSync(FIXTURES)
  .filter((name) => name.endsWith('.json'))
  .map((name) => JSON.parse(readFileSync(path.join(FIXTURES, name), 'utf8')));

// --- what each page should hold, from the fixture itself ----------------------

const slotsOf = (recipe) => recipe.inputs ?? [];
const kindOf = (recipe) => (recipe.modality === 'VIDEO' ? 'video' : 'image');
const fileId = (recipe, index) => (slotsOf(recipe).length === 1 ? 'FILE_ID' : `FILE_ID_${index + 1}`);

/** The request that reproduces the first run, as the page's own API call has it. */
function requestBody(recipe) {
  const run = recipe.runs[0];
  const body = { model: run.model, prompt: recipe.prompt[run.prompt_lang] };
  for (const [key, value] of Object.entries(run.params)) if (!(key in body)) body[key] = value;
  const pointers = slotsOf(recipe).map((slot, index) => ({ role: slot.role, pointer: { file_id: fileId(recipe, index) } }));
  const of = (role) => pointers.filter((each) => each.role === role).map((each) => each.pointer);
  if (pointers.length > 0 && recipe.modality === 'IMAGE') body.images = of('image');
  if (recipe.modality === 'VIDEO') {
    if (of('first_frame').length) body.first_frame = of('first_frame')[0];
    if (of('last_frame').length) body.last_frame = of('last_frame')[0];
    if (of('reference_image').length) body.reference_images = of('reference_image');
  }
  body.gallery_recipe = recipe.slug;
  return body;
}

/** The request as the code is written from it: it asks for a job and reads it. */
const codeRequest = (recipe) => ({ apiBase: API, kind: kindOf(recipe), body: requestBody(recipe), mode: 'poll' });

/** The fields POST /v1/videos reads, by the names they have there. It refuses any other. */
const OPENAI_VIDEO_FIELDS = [
  'model', 'prompt', 'seconds', 'size', 'input_reference', 'last_frame', 'reference_images', 'recipe', 'webhook_url',
  'gallery_recipe',
];

/**
 * A video's request as OpenAI's POST /v1/videos takes it: the first frame is
 * `input_reference` there, and seconds a string; a last frame and reference
 * images are fields of that route too. Null when that route would refuse part
 * of it, such as a count of clips: then the request goes to Godance's own
 * route as it is.
 */
function openAiVideo(recipe) {
  if (recipe.modality !== 'VIDEO') return null;
  const shaped = {};
  for (const [key, value] of Object.entries(requestBody(recipe))) {
    if (key === 'n' && value === 1) continue;
    const name = key === 'first_frame' ? 'input_reference' : key;
    if (!OPENAI_VIDEO_FIELDS.includes(name)) return null;
    shaped[name] = key === 'seconds' ? String(value) : value;
  }
  return shaped;
}

/** The route the page's code sends the request to. */
function routeOf(recipe) {
  if (recipe.modality === 'VIDEO') return openAiVideo(recipe) ? '/v1/videos' : '/v1/videos/generations';
  return slotsOf(recipe).length > 0 ? '/v1/images/edits' : '/v1/images/generations';
}

/** The recipe as the prompt carries it. */
function promptRecipe(recipe) {
  const run = recipe.runs[0];
  const lang = run.prompt_lang;
  const template = recipe.prompt.template;
  return {
    title: recipe.title.en,
    modality: recipe.modality,
    template: template ? template[lang] : recipe.prompt[lang],
    variables: template
      ? (recipe.prompt.variables ?? []).map((variable) => ({
          name: variable.name,
          default: lang === 'zh' ? variable.zh_default ?? variable.default : variable.default,
        }))
      : [],
    inputs: slotsOf(recipe).map((slot) => ({ slot: slot.slot, role: slot.role, hint: slot.hint.en })),
    model: run.model,
    params: run.params,
    gallery_recipe: recipe.slug,
  };
}

// --- building, and reading the built pages ------------------------------------

const outDirs = [];
after(async () => {
  for (const dir of outDirs) await rm(dir, { recursive: true, force: true });
});

async function build() {
  const scratch = path.join(ROOT, '.astro');
  await mkdir(scratch, { recursive: true });
  const outDir = await mkdtemp(path.join(scratch, 'test-scale-'));
  outDirs.push(outDir);
  const base = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
  const result = spawnSync(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
    cwd: ROOT,
    env: {
      ...base,
      GALLERY_CONTENT_DIR: FIXTURES,
      GALLERY_MEDIA_BASE_URL: 'https://media.example.test/gallery',
      GALLERY_REMOVAL_CONTACT: 'removals@example.test',
      SITE_URL: 'https://godance.ai',
      ASTRO_TELEMETRY_DISABLED: '1',
      NO_COLOR: '1',
    },
    encoding: 'utf8',
  });
  return { status: result.status, output: `${result.stdout}\n${result.stderr}`, outDir };
}

const decode = (text) =>
  text
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');

/** An attribute's value from an opening tag, or null. */
function attr(tag, name) {
  const match = new RegExp(`\\s${name}(?:="([^"]*)"|=([^\\s>]+))?(?=[\\s>])`).exec(tag);
  if (!match) return null;
  return decode(match[1] ?? match[2] ?? '');
}

/** Every opening tag with this name, as written. */
const tags = (html, name) => html.match(new RegExp(`<${name}\\b[^>]*>`, 'g')) ?? [];

/** The "Run at scale" section of a page: from its opening tag to the end of it. */
function blockOf(html) {
  const start = html.search(/<section\b[^>]*\sdata-run-at-scale[\s>]/);
  assert.notEqual(start, -1, 'the page has the Run at scale block');
  const end = html.indexOf('</section>', start);
  return html.slice(start, end);
}

/** The prompt in the block: its text, and the version it says it is. */
function promptOf(block) {
  const match = /<pre\b([^>]*\sdata-scale-prompt[^>]*)>([\s\S]*?)<\/pre>/.exec(block);
  assert.ok(match, 'the block holds the prompt');
  return { text: decode(match[2]), version: attr(`<pre${match[1]}>`, 'data-prompt-version'), id: attr(`<pre${match[1]}>`, 'id') };
}

/** The code blocks of one language, in order. */
const codeOf = (block, language) =>
  [...block.matchAll(/<code\b([^>]*)>([\s\S]*?)<\/code>/g)]
    .filter((match) => attr(`<code${match[1]}>`, 'data-scale-code') === language)
    .map((match) => ({ id: attr(`<code${match[1]}>`, 'id'), text: decode(match[2]) }));

/** The JSON a curl command sends: its -d word, unquoted as a shell would. */
const curlBody = (code) => JSON.parse(/-d '([\s\S]*)'$/.exec(code)[1].replaceAll(`'\\''`, "'"));

describe('Run at scale on the gallery recipe pages', { timeout: 300_000 }, () => {
  let dir;
  const pageOf = (slug) => readFileSync(path.join(dir, `gallery/${slug}/index.html`), 'utf8');
  before(async () => {
    const result = await build();
    assert.equal(result.status, 0, result.output);
    dir = result.outDir;
  });

  test('the fixtures cover an image, a video, a template, and inputs of both kinds', () => {
    assert.ok(recipes.some((r) => r.modality === 'IMAGE' && !r.inputs && !r.prompt.template), 'a plain image recipe');
    assert.ok(recipes.some((r) => r.modality === 'VIDEO' && !r.inputs), 'a video recipe');
    assert.ok(recipes.some((r) => r.prompt.template), 'a recipe with a template');
    assert.ok(recipes.some((r) => r.modality === 'IMAGE' && r.inputs), 'an image recipe with an input');
    assert.ok(recipes.some((r) => r.modality === 'VIDEO' && r.inputs), 'a video recipe with an input');
    // A video goes to OpenAI's route, from text or from images: a first frame and a reference
    // image (paper-boat-sets-off) as much as a prompt alone. Only a count of clips would not.
    const roles = (r) => slotsOf(r).map((slot) => slot.role);
    assert.ok(recipes.some((r) => routeOf(r) === '/v1/videos' && !r.inputs), 'a video from text on OpenAI\'s route');
    assert.ok(
      recipes.some((r) => routeOf(r) === '/v1/videos' && roles(r).includes('first_frame') && roles(r).includes('reference_image')),
      'a video from a first frame and a reference image on OpenAI\'s route',
    );
  });

  test('every recipe page has the block, once, with its three parts in order', () => {
    for (const recipe of recipes) {
      const html = pageOf(recipe.slug);
      assert.equal(tags(html, 'section').filter((tag) => attr(tag, 'data-run-at-scale') !== null).length, 1, recipe.slug);
      const block = blockOf(html);
      assert.match(block, /<h2\b[^>]*>Run at scale<\/h2>/, recipe.slug);
      const parts = tags(block, 'details').map((tag) => attr(tag, 'data-scale-part')).filter((part) => part !== null);
      assert.deepEqual(parts, ['assistant', 'code', 'n8n'], recipe.slug);
      // The first is open when the page loads; the others open on a click, with no script.
      const open = tags(block, 'details').filter((tag) => attr(tag, 'data-scale-part') !== null).map((tag) => attr(tag, 'open') !== null);
      assert.deepEqual(open, [true, false, false], recipe.slug);
    }
  });

  test("the prompt is the app's one constant, filled in with this recipe", () => {
    for (const recipe of recipes) {
      const request = codeRequest(recipe);
      const expected = assistantPrompt(promptRecipe(recipe), { base: API, createPath: createPath(request.kind, request.body) });
      const prompt = promptOf(blockOf(pageOf(recipe.slug)));

      assert.equal(prompt.text, expected, recipe.slug);
      assert.equal(prompt.version, ASSISTANT_PROMPT_VERSION, recipe.slug);
      // Every line of the wording that is for every recipe is on the page as the constant has it.
      let inBlock = false;
      for (const line of ASSISTANT_PROMPT.split('\n')) {
        if (line.startsWith('[[IF ')) inBlock = true;
        else if (line === '[[END]]') inBlock = false;
        else if (!inBlock && line.trim() !== '' && !line.includes('[[')) {
          assert.ok(prompt.text.includes(line), `${recipe.slug}: ${line.slice(0, 60)}`);
        }
      }
      assert.ok(!prompt.text.includes('[['), recipe.slug);
    }
  });

  test('the prompt says only what is true of the recipe: uploads only where there are inputs', () => {
    for (const recipe of recipes) {
      const { text } = promptOf(blockOf(pageOf(recipe.slug)));
      const route = routeOf(recipe);
      const openAi = route === '/v1/videos';
      const wording = promptWording(promptConditions(promptRecipe(recipe), { createPath: route }));
      assert.equal(wording.includes('/v1/files'), slotsOf(recipe).length > 0, recipe.slug);
      assert.equal(text.includes('/v1/files'), slotsOf(recipe).length > 0, recipe.slug);
      // A gallery recipe is run by filling the template in, and says where it came from.
      assert.ok(text.includes('Fill the template in yourself'), recipe.slug);
      assert.ok(text.includes('Send "gallery_recipe" unchanged'), recipe.slug);
      assert.ok(!text.includes('"version_id" names this recipe'), recipe.slug);
      // Rule 3 is about the route the request goes to.
      assert.ok(text.includes(`POST ${API}${route}\n`), recipe.slug);
      assert.equal(text.includes('A video request always answers 202'), recipe.modality === 'VIDEO' && !openAi, recipe.slug);
      assert.equal(text.includes('A video request answers 200 with the video'), openAi, recipe.slug);
      assert.equal(text.includes('client.videos.create_and_poll'), openAi, recipe.slug);
      assert.equal(text.includes('An image request answers in one of two ways'), recipe.modality === 'IMAGE', recipe.slug);
      // Inputs go in the field their role names; on OpenAI's route that is in extra_body, where
      // a first frame is input_reference.
      const inputs = slotsOf(recipe).length > 0;
      assert.equal(text.includes('Each input goes in the field its "role" names'), inputs && !openAi, recipe.slug);
      assert.equal(text.includes('Each input goes in extra_body, in the field its "role" names'), inputs && openAi, recipe.slug);
      assert.equal(text.includes('"input_reference"'), inputs && openAi, recipe.slug);
    }
  });

  test('the prompt holds the recipe: its template or prompt, its variables, its slots, the model and the params', () => {
    for (const recipe of recipes) {
      const { text } = promptOf(blockOf(pageOf(recipe.slug)));
      const json = /The recipe, as JSON:\n\n([\s\S]*?)\n\nHow to read it:/.exec(text);
      assert.ok(json, recipe.slug);
      const shown = JSON.parse(json[1]);
      const run = recipe.runs[0];

      assert.equal(shown.title, recipe.title.en, recipe.slug);
      assert.equal(shown.modality, recipe.modality, recipe.slug);
      assert.equal(shown.model, run.model, recipe.slug);
      assert.deepEqual(shown.params, run.params, recipe.slug);
      assert.equal(shown.gallery_recipe, recipe.slug, recipe.slug);
      // A gallery recipe is nobody's saved recipe: there is no version to name.
      assert.equal('version_id' in shown, false, recipe.slug);
      if (recipe.prompt.template) {
        assert.equal(shown.template, recipe.prompt.template[run.prompt_lang], recipe.slug);
        assert.deepEqual(shown.variables.map((v) => v.name), recipe.prompt.variables.map((v) => v.name), recipe.slug);
      } else {
        assert.equal(shown.template, recipe.prompt[run.prompt_lang], recipe.slug);
        assert.deepEqual(shown.variables, [], recipe.slug);
      }
      assert.deepEqual(shown.inputs.map((slot) => [slot.slot, slot.role]), slotsOf(recipe).map((slot) => [slot.slot, slot.role]), recipe.slug);
      // A slot is named, never filled: no sample's address goes to the assistant.
      for (const slot of slotsOf(recipe)) assert.ok(!text.includes(slot.sample.file), recipe.slug);
      assert.ok(text.includes(`POST ${API}${createPath(kindOf(recipe), requestBody(recipe))}`), recipe.slug);
    }
  });

  test("the code is the app's, in three languages, for the request that made the run", () => {
    for (const recipe of recipes) {
      const block = blockOf(pageOf(recipe.slug));
      const request = codeRequest(recipe);
      const shown = tags(block, 'details').map((tag) => attr(tag, 'data-scale-language')).filter((language) => language !== null);
      assert.deepEqual(shown, LANGUAGES, recipe.slug);

      for (const language of LANGUAGES) {
        const expected = [
          ...(slotsOf(recipe).length > 0 ? [uploadCode(language, API, 'input.png')] : []),
          ...codeFor(language, request),
        ].map((snippet) => snippet.code);
        assert.deepEqual(codeOf(block, language).map((code) => code.text), expected, `${recipe.slug} ${language}`);
      }

      // The curl sends the run's own request. An image asks for a job; a video is always one, in
      // OpenAI's shape where that route takes all of it, else as it is on Godance's own route.
      const curl = codeOf(block, 'curl').map((code) => code.text);
      const start = curl[slotsOf(recipe).length > 0 ? 1 : 0];
      const openAi = openAiVideo(recipe);
      const sent = openAi ?? (recipe.modality === 'VIDEO' ? requestBody(recipe) : { ...requestBody(recipe), async: true });
      assert.deepEqual(curlBody(start), sent, recipe.slug);
      assert.equal(createPath(kindOf(recipe), requestBody(recipe)), routeOf(recipe), recipe.slug);
      assert.ok(start.startsWith(`curl ${API}${routeOf(recipe)} \\`), recipe.slug);
      if (openAi) {
        // The video is read until it ends, then downloaded.
        assert.ok(curl.at(-2).startsWith(`curl ${API}/v1/videos/$VIDEO_ID \\`), recipe.slug);
        assert.ok(curl.at(-1).startsWith(`curl -o "$VIDEO_ID.mp4" ${API}/v1/videos/$VIDEO_ID/content \\`), recipe.slug);
        const python = codeOf(block, 'python').at(-1).text;
        assert.ok(python.includes('video = client.videos.create_and_poll('), recipe.slug);
        assert.ok(python.includes(`    seconds="${openAi.seconds}",`), recipe.slug);
        assert.ok(!python.includes('/jobs/'), recipe.slug);
        // The SDK sends a form, in which a list of images goes as JSON text.
        const list = 'reference_images' in openAi;
        assert.equal(python.includes('        "reference_images": json.dumps(['), list, recipe.slug);
        assert.equal(python.startsWith('import json\n'), list, recipe.slug);
      } else {
        assert.ok(curl.at(-1).startsWith(`curl ${API}/v1/jobs/$JOB_ID`), recipe.slug);
      }
    }
  });

  test('the key is a variable in every block, and no key is anywhere', () => {
    const names = { curl: '$GODANCE_API_KEY', python: 'os.environ["GODANCE_API_KEY"]', java: 'System.getenv("GODANCE_API_KEY")' };
    for (const recipe of recipes) {
      const block = blockOf(pageOf(recipe.slug));
      for (const language of LANGUAGES) {
        const blocks = codeOf(block, language);
        assert.ok(blocks.length > 0, `${recipe.slug} ${language}`);
        for (const code of blocks) assert.ok(code.text.includes(names[language]), `${recipe.slug} ${language}`);
      }
      assert.doesNotMatch(decode(block), /gd_live_/, recipe.slug);
      // Nobody is signed in: there is nothing to make a key with, only where to get one.
      assert.equal(tags(block, 'form').length + tags(block, 'input').length, 0, recipe.slug);
    }
  });

  test('a recipe with inputs says to upload first, and names what stands for each id', () => {
    for (const recipe of recipes) {
      const block = blockOf(pageOf(recipe.slug));
      const note = /<p\b[^>]*\sdata-scale-upload-note[^>]*>([\s\S]*?)<\/p>/.exec(block);
      if (slotsOf(recipe).length === 0) {
        assert.equal(note, null, recipe.slug);
        continue;
      }
      assert.ok(note, recipe.slug);
      const text = decode(note[1].replace(/<[^>]+>/g, ''));
      slotsOf(recipe).forEach((_, index) => assert.ok(text.includes(fileId(recipe, index)), recipe.slug));
      assert.ok(codeOf(block, 'curl')[0].text.includes('/v1/files'), recipe.slug);
    }
  });

  test('the sign-up link carries the same tags as the page\'s own', () => {
    for (const recipe of recipes) {
      const html = pageOf(recipe.slug);
      const link = tags(blockOf(html), 'a').find((tag) => attr(tag, 'data-scale-get-key') !== null);
      assert.ok(link, recipe.slug);
      const href = attr(link, 'href');
      assert.equal(href, `https://app.godance.ai/signup?utm_source=godance&utm_medium=gallery&utm_campaign=${recipe.slug}`, recipe.slug);
      // "Run this" is off in this build, so the page's own call to action is the same link.
      const cta = tags(html, 'a').find((tag) => attr(tag, 'data-get-key') !== null);
      assert.equal(href, attr(cta, 'href'), recipe.slug);
    }
  });

  test('n8n: the template for the recipe\'s kind, its download, four steps, and the row to fill in', () => {
    for (const recipe of recipes) {
      const block = blockOf(pageOf(recipe.slug));
      const template = n8nTemplate(recipe.modality);
      assert.equal(template.file, recipe.modality === 'VIDEO' ? 'godance-sheet-to-videos.json' : 'godance-sheet-to-images.json');

      const download = tags(block, 'a').find((tag) => attr(tag, 'data-scale-n8n-template') !== null);
      assert.equal(attr(download, 'href'),
        `https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n/${template.file}`, recipe.slug);

      const list = /<ol\b[^>]*\sdata-scale-n8n-steps[^>]*>([\s\S]*?)<\/ol>/.exec(block);
      assert.ok(list, recipe.slug);
      const steps = [...list[1].matchAll(/<li\b[^>]*>([\s\S]*?)<\/li>/g)].map((match) => decode(match[1]));
      assert.deepEqual(steps, n8nSteps(template), recipe.slug);
      assert.equal(steps.length, 4, recipe.slug);
      assert.ok(steps[0].includes(`"Header Auth" and name it "${N8N_CREDENTIAL}"`), recipe.slug);
      assert.equal(N8N_CREDENTIAL, 'Godance API');

      const row = /<dl\b[^>]*\sdata-scale-n8n-row[^>]*>([\s\S]*?)<\/dl>/.exec(block);
      assert.ok(row, recipe.slug);
      const cells = [...row[1].matchAll(/<dt\b[^>]*>([\s\S]*?)<\/dt>\s*<dd\b[^>]*>([\s\S]*?)<\/dd>/g)]
        .map((match) => ({ column: decode(match[1]), value: decode(match[2]) }));
      assert.deepEqual(cells, n8nRow(promptRecipe(recipe)), recipe.slug);

      const note = /<p\b[^>]*\sdata-scale-n8n-inputs[^>]*>([\s\S]*?)<\/p>/.exec(block);
      assert.equal(note ? decode(note[1]) : null, n8nInputsNote(recipe.modality, promptRecipe(recipe).inputs), recipe.slug);
    }
  });

  test('every Copy button in the block names an element of the page, and no id is used twice', () => {
    for (const recipe of recipes) {
      const html = pageOf(recipe.slug);
      const block = blockOf(html);
      const targets = tags(block, 'button').map((tag) => attr(tag, 'data-copy')).filter((id) => id !== null);
      // The prompt, and each block of code.
      const blocks = LANGUAGES.reduce((count, language) => count + codeOf(block, language).length, 0);
      assert.equal(targets.length, 1 + blocks, recipe.slug);
      assert.equal(new Set(targets).size, targets.length, recipe.slug);
      const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]);
      for (const target of targets) {
        assert.equal(ids.filter((id) => id === target).length, 1, `${recipe.slug}: #${target}`);
      }
      assert.equal(promptOf(block).id, 'scale-prompt', recipe.slug);
    }
  });
});
