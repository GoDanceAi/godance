/**
 * rerun: add a run on another model to recipes that are already published.
 * It is a paid stage like generate: a dry run unless --apply is given, and
 * never without --max-spend-cents. The API is mocked: nothing here touches
 * the network or spends anything.
 */
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkRecipe } from '../../src/lib/gallery-schema.js';
import { main } from './cli.js';
import { createApi } from './lib/api.js';
import { createBudget, openLedger } from './lib/budget.js';
import { priceBook } from './lib/pricing.js';
import { DEFAULT_CONFIG_DIR, readJson, writeJson } from './lib/work.js';
import { chinaHostedFor, heldByReruns, inputsRefusal, mapParams, planRerun, runReruns, selectRecipes } from './stages/rerun.js';
import { checkDecisions } from './stages/review.js';
import { API_BASE, CATALOGUE, FAKE_KEY, allFiles, configDir, fakeWorld, noSleep, tempDir, tinyPng } from './testing.js';

/** Published recipes in the gallery format, invented for the page tests. */
const FIXTURES = fileURLToPath(new URL('../../fixtures/gallery/recipes/', import.meta.url));
const REPO = fileURLToPath(new URL('../../../', import.meta.url));

/**
 * An image model none of those recipes was run on, as the catalogue seeds it
 * (V11, V24): 35000 microcents an image, the 12% image fee, a default size and
 * no list of sizes.
 */
const SEEDREAM = {
  id: 'seedream-5.0',
  owned_by: 'volcengine-ark',
  godance: { modality: 'IMAGE', pricing: { per_image_microcents: 35000 }, markup_percent: 12, capabilities: { default_size: '1024x1024' } },
};

// Other models as the catalogue seeds them (V11, V24, V33, V34).
const image = (id, owner, micro, capabilities, extra = {}) =>
  ({ id, owned_by: owner, godance: { modality: 'IMAGE', pricing: { per_image_microcents: micro, ...extra }, markup_percent: 12, capabilities } });
const video = (id, owner, micro, capabilities) =>
  ({ id, owned_by: owner, godance: { modality: 'VIDEO', pricing: { per_second_microcents: micro }, markup_percent: 15, capabilities } });
const QWEN = image('qwen-image-2.0', 'alibaba-dashscope', 35000, { default_size: '1024x1024' });
const QWEN_PRO = image('qwen-image-2.0-pro', 'alibaba-dashscope', 75000, { default_size: '1024x1024' });
const FLUX = image('flux-2-pro', 'bfl', 30000, { default_size: '1024x1024', max_n: 1, inputs: { images: { max: 8 } } }, { per_input_image_microcents: 60000 });
const GPT_15 = image('gpt-image-1.5', 'openai', 40000, { sizes: ['1024x1024', '1536x1024', '1024x1536', 'auto'], default_size: 'auto', max_n: 10, inputs: { images: { max: 16, mask: true } } });
const NANO = image('nano-banana', 'google-vertex', 134000, { max_n: 1 });
const KLING = video('kling-3.0', 'kling', 84000, { default_seconds: 5, max_n: 1, inputs: { first_frame: true, last_frame: true } });
const HAILUO = video('minimax-hailuo-2.3', 'minimax', 90000, { default_seconds: 6, max_n: 1, inputs: { first_frame: true, last_frame: false } });
const SEEDANCE = video('seedance-2.0', 'volcengine-ark', 140000, { default_size: '1280x720', default_seconds: 5, max_n: 1, inputs: { first_frame: true, last_frame: true, reference_images: { max: 9 } } });
const MODELS = [...CATALOGUE.filter((model) => model.id !== 'seedance-2.0'), SEEDREAM, QWEN, QWEN_PRO, FLUX, GPT_15, NANO, KLING, HAILUO, SEEDANCE];

const prices = priceBook(MODELS);
const recipe = (slug) => JSON.parse(readFileSync(path.join(FIXTURES, `${slug}.json`), 'utf8'));
const committed = JSON.parse(readFileSync(path.join(DEFAULT_CONFIG_DIR, 'china-hosted.json'), 'utf8'));

/** A work folder, a config folder and a gallery folder holding these published recipes, and a way to run the tool in them. */
async function workspace(t, { recipes, models = [...CATALOGUE, SEEDREAM], world = fakeWorld({ models }), key = FAKE_KEY, exceptions = null }) {
  const root = await tempDir(t);
  const dirs = { work: path.join(root, 'work'), content: path.join(root, 'content'), config: await configDir(t) };
  await mkdir(dirs.content, { recursive: true });
  for (const slug of recipes) await copyFile(path.join(FIXTURES, `${slug}.json`), path.join(dirs.content, `${slug}.json`));
  await copyFile(path.join(DEFAULT_CONFIG_DIR, 'china-hosted.json'), path.join(dirs.config, 'china-hosted.json'));
  if (exceptions) await writeFile(path.join(dirs.config, 'rerun-params.json'), JSON.stringify(exceptions));
  const cli = async (...args) => {
    const lines = [];
    const code = await main([...args, '--work-dir', dirs.work, '--config-dir', dirs.config, '--content-dir', dirs.content], {
      env: { GODANCE_API_BASE: API_BASE, ...(key ? { GODANCE_API_KEY: key } : {}) },
      fetch: world.fetch,
      out: (line) => lines.push(line),
      sleep: noSleep,
      now: () => new Date('2026-11-22T09:00:00Z'),
    });
    return { code, text: lines.join('\n') };
  };
  return { dirs, world, cli };
}

/** The text of each recipe file, by slug. */
const recipeFiles = async (dir, slugs) =>
  Object.fromEntries(await Promise.all(slugs.map(async (slug) => [slug, await readFile(path.join(dir, `${slug}.json`), 'utf8')])));

/** Lines of a plan that start a job: "  + <slug> on <model>  …". */
const jobs = (text) => text.split('\n').filter((line) => /^ {2}\+ /.test(line)).map((line) => line.trim().split(/\s+/).slice(1, 4).join(' '));

describe('rerun, as a dry run', () => {
  test('plans one job per recipe on the model named, and spends nothing', async (t) => {
    const published = ['tin-robot-tea-party', 'moss-covered-typewriter', 'glass-whale-over-rooftops'];
    const { dirs, world, cli } = await workspace(t, { recipes: published });
    const before = await recipeFiles(dirs.content, published);

    const { code, text } = await cli('rerun', '--models', 'seedream-5.0', '--slugs', 'tin-robot-tea-party,moss-covered-typewriter', '--max-spend-cents', '100');

    assert.equal(code, 0, text);
    assert.match(text, /^gallery rerun: dry run\. Nothing is written and no paid call is made\. Add --apply to do it\.$/m);
    // One job for each recipe asked for, on the model asked for, and none for the recipe that was not asked for.
    for (const slug of ['tin-robot-tea-party', 'moss-covered-typewriter']) {
      assert.equal(text.match(new RegExp(`^ +\\+ ${slug} on seedream-5\\.0\\b`, 'gm'))?.length, 1, `one job for ${slug}\n\n${text}`);
    }
    assert.doesNotMatch(text, /glass-whale-over-rooftops/);
    // seedream-5.0 holds 4 cents an image: 35000 microcents and the 12% fee, rounded up.
    assert.match(text, /^2 new runs would start, holding 8 cents/m);

    // Nothing is spent: the free, public catalogue is the only request, and it carries no key.
    assert.deepEqual(world.requests.map((request) => `${request.method} ${request.url} ${request.authorization}`), [`GET ${API_BASE}/v1/models null`]);
    assert.equal(existsSync(dirs.work), false, 'a dry run writes nothing: no state and no ledger');
    assert.deepEqual(await recipeFiles(dirs.content, published), before, 'the published recipes are as they were');
  });

  test('needs a cap, one way of choosing recipes and a model, before anything is read or asked', async (t) => {
    const { dirs, world, cli } = await workspace(t, { recipes: ['tin-robot-tea-party'] });
    for (const [args, message] of [
      [['--models', 'seedream-5.0', '--slugs', 'tin-robot-tea-party'], /a run that spends money needs --max-spend-cents/],
      [['--models', 'seedream-5.0', '--max-spend-cents', '100'], /rerun takes one of --slugs <slug,…>, --tag <tag> or --all/],
      [['--models', 'seedream-5.0', '--all', '--tag', 'toys', '--max-spend-cents', '100'], /rerun takes one of --slugs/],
      [['--all', '--max-spend-cents', '100'], /rerun needs --models <id,…>, --china-hosted, or both/],
    ]) {
      const { code, text } = await cli('rerun', ...args);
      assert.equal(code, 2, text);
      assert.match(text, message);
    }
    assert.deepEqual(world.requests, []);
    assert.equal(existsSync(dirs.work), false);
  });

  test('a slug that names no published recipe stops it before anything is asked or planned', async (t) => {
    const { world, cli } = await workspace(t, { recipes: ['tin-robot-tea-party'] });
    const { code, text } = await cli('rerun', '--models', 'seedream-5.0', '--slugs', 'tin-robot-tea-party,no-such-recipe', '--max-spend-cents', '100');
    assert.equal(code, 1, text);
    assert.match(text, /there is no published recipe "no-such-recipe" in .*\. Nothing was planned or spent\./);
    assert.deepEqual(jobs(text), []);
    assert.deepEqual(world.requests, []);
  });

  test('says why a recipe gets no run on a model: it has one, the model makes the other kind, or the catalogue lacks it', async (t) => {
    const { cli } = await workspace(t, { recipes: ['glass-whale-over-rooftops', 'harbour-fog-timelapse'], models: MODELS });
    const { code, text } = await cli('rerun', '--models', 'gpt-image-2,seedance-2.0,imagen-9', '--all', '--max-spend-cents', '100');
    assert.equal(code, 0, text);
    for (const expected of [
      '  = glass-whale-over-rooftops on gpt-image-2: it already has a run on gpt-image-2',
      '  - glass-whale-over-rooftops on seedance-2.0: cannot be run: seedance-2.0 does not make images, and this is an image recipe',
      '  - glass-whale-over-rooftops on imagen-9: cannot be run: the catalogue does not list imagen-9',
      '  - harbour-fog-timelapse on gpt-image-2: cannot be run: gpt-image-2 does not make videos, and this is a video recipe',
      '  = harbour-fog-timelapse on seedance-2.0: it already has a run on seedance-2.0',
    ]) {
      assert.ok(text.includes(expected), `the plan does not say: ${expected}\n\n${text}`);
    }
    assert.match(text, /^0 new runs would start, holding 0 cents/m);
  });

  test('--tag and --all choose the recipes, in slug order', async (t) => {
    const { cli } = await workspace(t, { recipes: ['tin-robot-tea-party', 'moss-covered-typewriter', 'ceramic-mug-json-prompt'] });
    const tagged = await cli('rerun', '--models', 'seedream-5.0', '--tag', 'toys', '--max-spend-cents', '100');
    assert.deepEqual(jobs(tagged.text), ['tin-robot-tea-party on seedream-5.0']);
    const all = await cli('rerun', '--models', 'seedream-5.0', '--all', '--max-spend-cents', '100');
    assert.deepEqual(jobs(all.text), ['ceramic-mug-json-prompt on seedream-5.0', 'moss-covered-typewriter on seedream-5.0', 'tin-robot-tea-party on seedream-5.0']);
    const none = await cli('rerun', '--models', 'seedream-5.0', '--tag', 'no-such-tag', '--max-spend-cents', '100');
    assert.equal(none.code, 0, none.text);
    assert.match(none.text, /none has the tag "no-such-tag"/);
  });

  test('--slugs keeps the order named, and reports a slug that names nothing', () => {
    const all = ['tin-robot-tea-party', 'moss-covered-typewriter'].map(recipe);
    const chosen = selectRecipes(all, { slugs: ['moss-covered-typewriter', 'tin-robot-tea-party', 'nope'] });
    assert.deepEqual(chosen.recipes.map((each) => each.slug), ['moss-covered-typewriter', 'tin-robot-tea-party']);
    assert.deepEqual(chosen.unknown, ['nope']);
  });

  test('the plan says what each run leaves out of the first run\'s parameters, and how much of it the cap leaves room for', async (t) => {
    const { cli } = await workspace(t, { recipes: ['neon-noodle-cart-poster', 'tin-robot-tea-party'] });
    const { code, text } = await cli('rerun', '--models', 'seedream-5.0', '--all', '--max-spend-cents', '6');
    assert.equal(code, 0, text);
    assert.ok(text.includes('  + neon-noodle-cart-poster on seedream-5.0  n 1  holds 4 cents'), text);
    assert.ok(text.includes('      size 1536x1024 left out: seedream-5.0 lists no sizes, so its default, 1024x1024, applies'), text);
    assert.ok(text.includes('      quality high left out: the catalogue does not say seedream-5.0 takes quality'), text);
    assert.ok(text.includes('  + tin-robot-tea-party on seedream-5.0  size 1024x1024, n 1  holds 4 cents'), text);
    assert.ok(text.includes('cap: 6 cents, against 0 cents spent in this work folder and 0 cents held by unfinished jobs; 1 of the 2 fit under it, and the run stops there'), text);
  });

  test('a value the exceptions file confirms is kept', async (t) => {
    const { cli } = await workspace(t, { recipes: ['moss-covered-typewriter'], exceptions: { allow: { 'seedream-5.0': { size: ['1024x1536'] } }, map: {} } });
    const { text } = await cli('rerun', '--models', 'seedream-5.0', '--all', '--max-spend-cents', '100');
    assert.ok(text.includes('  + moss-covered-typewriter on seedream-5.0  size 1024x1536, n 1  holds 4 cents'), text);
    assert.doesNotMatch(text, /left out/);
  });
});

describe('parameters', () => {
  const caps = (model) => prices.capabilities(model);

  test('a size is kept when the model lists it or it is the model\'s default; otherwise left out, with the reason', () => {
    assert.deepEqual(mapParams({ size: '1536x1024', n: 1 }, { model: 'gpt-image-1.5', modality: 'IMAGE', capabilities: caps('gpt-image-1.5') }),
      { params: { size: '1536x1024', n: 1 }, left: [] });
    assert.deepEqual(mapParams({ size: '1344x768' }, { model: 'gpt-image-1.5', modality: 'IMAGE', capabilities: caps('gpt-image-1.5') }),
      { params: {}, left: [{ param: 'size', value: '1344x768', why: 'gpt-image-1.5 does not list 1344x768 among its sizes' }] });
    assert.deepEqual(mapParams({ size: '1024x1024' }, { model: 'seedream-5.0', modality: 'IMAGE', capabilities: caps('seedream-5.0') }),
      { params: { size: '1024x1024' }, left: [] });
    assert.deepEqual(mapParams({ size: '1024x1536' }, { model: 'nano-banana', modality: 'IMAGE', capabilities: caps('nano-banana') }),
      { params: {}, left: [{ param: 'size', value: '1024x1536', why: 'nano-banana lists no sizes' }] });
  });

  test('a count is kept up to the model\'s max_n, which is 1 when the catalogue states none', () => {
    assert.deepEqual(mapParams({ n: 2 }, { model: 'gpt-image-1.5', modality: 'IMAGE', capabilities: caps('gpt-image-1.5') }).params, { n: 2 });
    assert.deepEqual(mapParams({ n: 2 }, { model: 'flux-2-pro', modality: 'IMAGE', capabilities: caps('flux-2-pro') }).left,
      [{ param: 'n', value: 2, why: 'flux-2-pro makes at most 1 per request' }]);
    assert.deepEqual(mapParams({ n: 2 }, { model: 'qwen-image-2.0', modality: 'IMAGE', capabilities: caps('qwen-image-2.0') }).left,
      [{ param: 'n', value: 2, why: 'qwen-image-2.0 makes at most 1 per request' }]);
    assert.deepEqual(mapParams({ n: 1 }, { model: 'qwen-image-2.0', modality: 'IMAGE', capabilities: caps('qwen-image-2.0') }).params, { n: 1 });
  });

  test('a parameter the target model cannot take is left out, and the plan says why', () => {
    const mapped = mapParams({ size: '1536x1024', n: 1, quality: 'high' }, { model: 'seedream-5.0', modality: 'IMAGE', capabilities: caps('seedream-5.0') });
    assert.deepEqual(mapped.params, { n: 1 });
    assert.deepEqual(mapped.left, [
      { param: 'size', value: '1536x1024', why: 'seedream-5.0 lists no sizes, so its default, 1024x1024, applies' },
      { param: 'quality', value: 'high', why: 'the catalogue does not say seedream-5.0 takes quality' },
    ]);
  });

  test('a video always states its seconds: kept when they are the model\'s default, the default otherwise, and never guessed', () => {
    assert.deepEqual(mapParams({ seconds: 5, size: '1280x720' }, { model: 'kling-3.0', modality: 'VIDEO', capabilities: caps('kling-3.0') }),
      { params: { seconds: 5 }, left: [{ param: 'size', value: '1280x720', why: 'kling-3.0 lists no sizes' }] });
    assert.deepEqual(mapParams({ seconds: 8 }, { model: 'minimax-hailuo-2.3', modality: 'VIDEO', capabilities: caps('minimax-hailuo-2.3') }),
      { params: { seconds: 6 }, left: [{ param: 'seconds', value: 8, why: 'minimax-hailuo-2.3 lists no clip lengths, so its default, 6 s, is sent' }] });
    assert.deepEqual(mapParams({ seconds: 10 }, { model: 'kling-3.0', modality: 'VIDEO', capabilities: { durations: [5, 10] } }).params, { seconds: 10 });
    assert.deepEqual(mapParams({ seconds: 5 }, { model: 'x-video', modality: 'VIDEO', capabilities: {} }),
      { error: 'x-video lists no clip lengths and no default length, so the run could not state its seconds' });
  });

  test('the exceptions file allows a confirmed value, and names a value to send instead of another', () => {
    const exceptions = { allow: { 'seedream-5.0': { size: ['1024x1536'] } }, map: { 'qwen-image-2.0': { size: { '1024x1536': '928x1664' } } } };
    assert.deepEqual(mapParams({ size: '1024x1536' }, { model: 'seedream-5.0', modality: 'IMAGE', capabilities: caps('seedream-5.0'), exceptions }), { params: { size: '1024x1536' }, left: [] });
    assert.deepEqual(mapParams({ size: '1024x1536' }, { model: 'qwen-image-2.0', modality: 'IMAGE', capabilities: caps('qwen-image-2.0'), exceptions }), { params: { size: '928x1664' }, left: [] });
    // An exception for one model says nothing about another.
    assert.equal(mapParams({ size: '1024x1536' }, { model: 'qwen-image-2.0-pro', modality: 'IMAGE', capabilities: caps('qwen-image-2.0-pro'), exceptions }).left.length, 1);
  });

  test('the committed exceptions file has the shape the tool reads', () => {
    const file = JSON.parse(readFileSync(path.join(DEFAULT_CONFIG_DIR, 'rerun-params.json'), 'utf8'));
    for (const part of ['allow', 'map']) {
      assert.equal(typeof file[part], 'object');
      assert.ok(file[part] !== null && !Array.isArray(file[part]));
    }
    for (const values of Object.values(file.allow).flatMap((byParam) => Object.values(byParam))) assert.ok(Array.isArray(values));
  });
});

describe('inputs', () => {
  test('a model takes a recipe\'s slots only when the catalogue says it does', () => {
    const teapot = recipe('enamel-teapot-on-marble'); // one image slot
    assert.equal(inputsRefusal(teapot, { model: 'flux-2-pro', capabilities: prices.capabilities('flux-2-pro') }), null);
    assert.equal(inputsRefusal(teapot, { model: 'nano-banana', capabilities: prices.capabilities('nano-banana') }), 'nano-banana takes no input images');
    assert.equal(inputsRefusal({ ...teapot, inputs: [...teapot.inputs, ...teapot.inputs, ...teapot.inputs] }, { model: 'x', capabilities: { inputs: { images: { max: 2 } } } }),
      'x takes at most 2 input images, and this recipe has 3');

    const boat = recipe('paper-boat-sets-off'); // a first frame and a reference image
    assert.equal(inputsRefusal(boat, { model: 'seedance-2.0', capabilities: prices.capabilities('seedance-2.0') }), null);
    assert.equal(inputsRefusal(boat, { model: 'kling-3.0', capabilities: prices.capabilities('kling-3.0') }), 'kling-3.0 takes no reference images');
    assert.equal(inputsRefusal(boat, { model: 'x', capabilities: {} }), 'x takes no first frame');
    assert.equal(inputsRefusal(recipe('tin-robot-tea-party'), { model: 'x', capabilities: {} }), null, 'a recipe without inputs fits any model');
  });

  test('a recipe with inputs is planned as an edit, holding the model\'s price for each input image', () => {
    const plan = planRerun(recipe('enamel-teapot-on-marble'), 'flux-2-pro', { prices });
    // (30000 + 60000 for the input) microcents and the 12% fee: 10.08 cents, rounded up.
    assert.equal(plan.hold, 11);
    assert.deepEqual(plan.inputs.map((slot) => slot.sample.file), ['enamel-teapot-on-marble/input-0.webp']);
    assert.equal(planRerun(recipe('enamel-teapot-on-marble'), 'nano-banana', { prices }).why, 'nano-banana takes no input images');
  });
});

describe('--china-hosted', () => {
  test('chooses the cheapest China-hosted model of the recipe\'s kind from the committed list, the first listed when two cost the same', () => {
    // seedream-5.0 and qwen-image-2.0 cost the same today: the file lists seedream-5.0 first.
    assert.equal(prices.unitCents('seedream-5.0'), prices.unitCents('qwen-image-2.0'));
    assert.deepEqual(chinaHostedFor(recipe('tin-robot-tea-party'), { list: committed, prices }), { model: 'seedream-5.0' });
    // When one is cheaper, it is chosen, wherever it stands in the file.
    const cheaperQwen = priceBook([...MODELS.filter((model) => model.id !== 'qwen-image-2.0'), image('qwen-image-2.0', 'alibaba-dashscope', 30000, {})]);
    assert.deepEqual(chinaHostedFor(recipe('tin-robot-tea-party'), { list: committed, prices: cheaperQwen }), { model: 'qwen-image-2.0' });
    // A video recipe gets the cheapest video model: kling-3.0, at 84000 microcents a second.
    const clip = { ...recipe('origami-crane-unfolding'), runs: [{ ...recipe('origami-crane-unfolding').runs[0], model: 'veo-3.1' }] };
    assert.deepEqual(chinaHostedFor(clip, { list: committed, prices }), { model: 'kling-3.0' });
  });

  test('a recipe with a run on a China-hosted model gets none, and nor does one whose kind the catalogue lists none of', () => {
    assert.deepEqual(chinaHostedFor(recipe('harbour-fog-timelapse'), { list: committed, prices }),
      { model: null, why: 'it already has a run on seedance-2.0, which is China-hosted' });
    assert.deepEqual(chinaHostedFor(recipe('tin-robot-tea-party'), { list: committed, prices: priceBook(CATALOGUE) }),
      { model: null, why: 'the catalogue lists none of the China-hosted images models in config/china-hosted.json' });
  });

  test('the committed list is every model of the catalogue whose provider the Alicloud allowlist names, in the order the catalogue was seeded', () => {
    const allowlist = readFileSync(path.join(REPO, 'backend/src/main/java/ai/godance/gateway/AlicloudProviderAllowlist.java'), 'utf8');
    const block = /EnumSet\.of\(([\s\S]*?)\);/.exec(allowlist)[1];
    const allowed = new Set([...block.matchAll(/Provider\.([A-Z_]+)/g)].map((match) => match[1]));
    assert.ok(allowed.size >= 8, 'the allowlist was read');

    const migrations = path.join(REPO, 'backend/src/main/resources/db/migration');
    const rows = readdirSync(migrations)
      .filter((name) => /^V\d+__.*\.sql$/.test(name))
      .sort((a, b) => Number(/^V(\d+)/.exec(a)[1]) - Number(/^V(\d+)/.exec(b)[1]))
      .flatMap((name) => [...readFileSync(path.join(migrations, name), 'utf8').matchAll(/\(\s*'([^']+)',\s*'([A-Z_]+)',\s*'(IMAGE|VIDEO)'/g)])
      .map(([, model, provider, modality]) => ({ model, provider, modality }));
    assert.ok(rows.length >= 19, 'the catalogue rows were read');

    for (const modality of ['IMAGE', 'VIDEO']) {
      const expected = rows.filter((row) => row.modality === modality && allowed.has(row.provider)).map(({ model, provider }) => ({ model, provider }));
      assert.deepEqual(committed[modality], expected, `config/china-hosted.json ${modality}`);
    }
  });

  test('adds a run on that model to each selected recipe that lacks one, and says why for the others', async (t) => {
    const { code, text } = await (await workspace(t, { recipes: ['tin-robot-tea-party', 'harbour-fog-timelapse'], models: MODELS })).cli('rerun', '--china-hosted', '--all', '--max-spend-cents', '100');
    assert.equal(code, 0, text);
    assert.deepEqual(jobs(text), ['tin-robot-tea-party on seedream-5.0']);
    assert.ok(text.includes('  = harbour-fog-timelapse: no China-hosted run added: it already has a run on seedance-2.0, which is China-hosted'), text);
  });
});

/** Everything runReruns needs, over a fresh work folder and a fresh fake API. */
async function setup(t, { cap = 1000, world = fakeWorld({ models: MODELS, imageCost: 4 }), state = {} } = {}) {
  const work = await tempDir(t);
  const file = path.join(work, 'reruns.json');
  const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: noSleep });
  const ledger = await openLedger(path.join(work, 'ledger.json'));
  const budget = createBudget({ cap, spent: ledger.spent, held: heldByReruns(state) });
  const ctx = { api, budget, ledger, mediaDir: path.join(work, 'media'), save: () => writeJson(file, state), sleep: noSleep, pollIntervalMs: 1, pollTimeoutMs: 1000 };
  return { work, world, state, file, api, ledger, budget, ctx };
}
const planned = (slugs, model = 'seedream-5.0') => slugs.map((slug) => planRerun(recipe(slug), model, { prices }));
const SLUGS = ['tin-robot-tea-party', 'moss-covered-typewriter', 'neon-noodle-cart-poster', 'ceramic-mug-json-prompt', 'glass-whale-over-rooftops'];

describe('running reruns', () => {
  test('stops before the cap would be passed, and submits nothing more', async (t) => {
    const { world, state, ctx, budget } = await setup(t, { cap: 10 }); // room for two 4-cent images, not three
    const result = await runReruns(planned(SLUGS), state, ctx);
    assert.equal(world.submissions.length, 2);
    assert.deepEqual({ done: result.done, stoppedByBudget: result.stoppedByBudget }, { done: 2, stoppedByBudget: true });
    assert.equal(budget.spent, 8);
    assert.equal(Object.keys(state).length, 2);
  });

  test('a cap of zero submits nothing', async (t) => {
    const { world, state, ctx } = await setup(t, { cap: 0 });
    const result = await runReruns(planned(SLUGS.slice(0, 1)), state, ctx);
    assert.equal(world.submissions.length, 0);
    assert.equal(result.stoppedByBudget, true);
    assert.deepEqual(state, {});
  });

  test('the job id is on disk as soon as the API accepts the job', async (t) => {
    let seenOnDisk = null;
    const { state, file, ctx } = await setup(t);
    const api = ctx.api;
    ctx.api = { ...api, job: async (id) => { seenOnDisk ??= (await readJson(file))['tin-robot-tea-party@seedream-5.0']; return api.job(id); } };
    await runReruns(planned(['tin-robot-tea-party']), state, ctx);
    assert.deepEqual({ status: seenOnDisk.status, job_id: seenOnDisk.job_id, hold_cents: seenOnDisk.hold_cents }, { status: 'accepted', job_id: 'job-1', hold_cents: 4 });
  });

  test('a resumed rerun does not resubmit an accepted job, and is paid for once', async (t) => {
    const world = fakeWorld({ models: MODELS, imageCost: 4, pollsUntilDone: 3 });
    const first = await setup(t, { world });
    const interrupted = await runReruns(planned(['tin-robot-tea-party']), first.state, { ...first.ctx, pollTimeoutMs: 0 });
    assert.equal(interrupted.pending, 1);

    const state = await readJson(first.file);
    const ledger = await openLedger(path.join(first.work, 'ledger.json'));
    const budget = createBudget({ cap: 1000, spent: ledger.spent, held: heldByReruns(state) });
    assert.equal(budget.held, 4, 'the unfinished job still holds its money');
    const resumed = await runReruns([{ key: 'tin-robot-tea-party@seedream-5.0', slug: 'tin-robot-tea-party', model: 'seedream-5.0', modality: 'IMAGE' }], state,
      { ...first.ctx, ledger, budget, save: () => writeJson(first.file, state) });

    assert.equal(world.submissions.length, 1, 'the job was not submitted again');
    assert.equal(resumed.done, 1);
    assert.deepEqual({ status: state['tin-robot-tea-party@seedream-5.0'].status, cost_cents: state['tin-robot-tea-party@seedream-5.0'].cost_cents }, { status: 'done', cost_cents: 4 });
    assert.deepEqual({ spent: budget.spent, held: budget.held, events: ledger.events.length }, { spent: 4, held: 0, events: 1 });
  });

  test('a request that got no clear answer is not sent again by itself; --retry-uncertain sends it once more, holding once', async (t) => {
    let fail = true;
    const world = fakeWorld({ models: MODELS, imageCost: 4, onSubmit: () => (fail ? 'network-error' : undefined) });
    const { state, ctx, budget } = await setup(t, { world, cap: 4 });
    const first = await runReruns(planned(['tin-robot-tea-party']), state, ctx);
    assert.equal(first.uncertain, 1);
    assert.equal(state['tin-robot-tea-party@seedream-5.0'].status, 'submitting');
    assert.equal(budget.held, 4, 'its money stays held: the job may exist');

    const posts = () => world.requests.filter((request) => request.method === 'POST').length;
    const before = posts();
    assert.equal((await runReruns(planned(['tin-robot-tea-party']), state, ctx)).uncertain, 1);
    assert.equal(posts(), before, 'no request was sent for it');

    fail = false;
    const retried = await runReruns(planned(['tin-robot-tea-party']), state, { ...ctx, retryUncertain: true });
    assert.equal(retried.done, 1);
    assert.deepEqual({ spent: budget.spent, held: budget.held }, { spent: 4, held: 0 });
  });

  test('at most two jobs are in flight', async (t) => {
    const { world, state, ctx } = await setup(t);
    await runReruns(planned(SLUGS), state, ctx);
    assert.equal(world.submissions.length, 5);
    assert.ok(world.mostInFlight <= 2, `${world.mostInFlight} in flight`);
  });

  test('sends the recipe\'s prompt with the mapped parameters, saves each output under the recipe by its new model, and records what it cost', async (t) => {
    const { world, state, ctx, ledger, work } = await setup(t);
    await runReruns(planned(['neon-noodle-cart-poster']), state, ctx);
    const neon = recipe('neon-noodle-cart-poster');
    assert.deepEqual(world.submissions[0].body, { model: 'seedream-5.0', prompt: neon.prompt[neon.runs[0].prompt_lang], n: 1 });
    const record = state['neon-noodle-cart-poster@seedream-5.0'];
    assert.equal(record.status, 'done');
    assert.deepEqual(record.outputs.map((output) => output.file), ['neon-noodle-cart-poster/seedream-5.0-0.webp']);
    assert.ok(existsSync(path.join(work, 'media', 'neon-noodle-cart-poster', 'seedream-5.0-0-480.webp')));
    assert.deepEqual(ledger.events.map(({ kind, ref, model, cents }) => ({ kind, ref, model, cents })),
      [{ kind: 'rerun', ref: 'neon-noodle-cart-poster@seedream-5.0', model: 'seedream-5.0', cents: 4 }]);
  });

  test('a recipe with inputs uploads its samples and sends an edit that names them; a sample not in the work folder fails it before anything is held', async (t) => {
    const { world, state, ctx, budget } = await setup(t);
    const teapot = planRerun(recipe('enamel-teapot-on-marble'), 'flux-2-pro', { prices });
    const missing = await runReruns([teapot], state, ctx);
    assert.equal(missing.failed, 1);
    assert.match(state[teapot.key].error, /its sample input is not in the work folder's media folder \(input-0\.webp\)/);
    assert.deepEqual({ submissions: world.submissions.length, held: budget.held, spent: budget.spent }, { submissions: 0, held: 0, spent: 0 });

    delete state[teapot.key];
    await mkdir(path.join(ctx.mediaDir, 'enamel-teapot-on-marble'), { recursive: true });
    await writeFile(path.join(ctx.mediaDir, 'enamel-teapot-on-marble', 'input-0.webp'), await tinyPng());
    const result = await runReruns([teapot], state, ctx);
    assert.equal(result.done, 1);
    assert.equal(world.uploads.length, 1);
    assert.deepEqual({ route: world.submissions[0].route, images: world.submissions[0].body.images }, { route: 'edits', images: [{ file_id: 'file-1' }] });
    assert.deepEqual(state[teapot.key].inputs, { [recipe('enamel-teapot-on-marble').inputs[0].slot]: 'enamel-teapot-on-marble/input-0.webp' });
  });
});

describe('review and publish', () => {
  const decide = async (dirs, runs) => {
    await mkdir(path.join(dirs.work, 'review'), { recursive: true });
    await writeFile(path.join(dirs.work, 'review', 'decisions.json'), JSON.stringify({ standard_version: 2, saved_at: '2026-11-23T10:00:00Z', decisions: {}, runs }));
  };
  const decision = (slug, model, made, reason = '') => ({ slug, model, decision: made, reason, decided_on: '2026-11-23' });

  async function rerunDone(t) {
    const space = await workspace(t, { recipes: ['tin-robot-tea-party', 'moss-covered-typewriter'], models: MODELS, world: fakeWorld({ models: MODELS, imageCost: 4 }) });
    const { code, text } = await space.cli('rerun', '--models', 'seedream-5.0', '--all', '--max-spend-cents', '100', '--apply');
    assert.equal(code, 0, text);
    assert.match(text, /done 2 · failed 0 · still running 0 · uncertain 0/);
    return space;
  }

  test('the review sheet shows each new run beside its recipe\'s first run, in a work folder that imported nothing', async (t) => {
    const { cli, dirs } = await rerunDone(t);
    const { code, text } = await cli('review', '--apply');
    assert.equal(code, 0, text);
    assert.match(text, /0 candidates with a finished run; content standard version 2/);
    assert.match(text, /2 new runs on published recipes, each beside its recipe's first run/);

    const html = await readFile(path.join(dirs.work, 'review', 'index.html'), 'utf8');
    const data = JSON.parse(/<script type="application\/json" id="data">([\s\S]*?)<\/script>/.exec(html)[1]);
    const item = data.reruns.find((each) => each.key === 'moss-covered-typewriter@seedream-5.0');
    assert.deepEqual({ first: item.first.model, run: item.run.model, cost: item.run.cost_cents, published: item.published },
      { first: 'gpt-image-2', run: 'seedream-5.0', cost: 4, published: true });
    assert.deepEqual(item.first.outputs, recipe('moss-covered-typewriter').runs[0].outputs);
    assert.deepEqual(item.run.left, [{ param: 'size', value: '1024x1536', why: 'seedream-5.0 lists no sizes, so its default, 1024x1024, applies' }]);
    for (const expected of ['New runs on published recipes', "'First run, published: '", "'New run: '", "Left out of the first run\\'s parameters"]) {
      assert.ok(html.includes(expected), `the sheet does not have: ${expected}`);
    }
    // The key is never written anywhere.
    for (const [file, contents] of await allFiles(dirs.work)) assert.ok(!contents.includes(FAKE_KEY), `${file} holds the key`);
  });

  test('publish adds only the approved run, after the recipe\'s runs, with the day it was approved, and changes nothing else', async (t) => {
    const { cli, dirs } = await rerunDone(t);
    const before = await recipeFiles(dirs.content, ['tin-robot-tea-party', 'moss-covered-typewriter']);
    await decide(dirs, {
      'tin-robot-tea-party@seedream-5.0': decision('tin-robot-tea-party', 'seedream-5.0', 'approved'),
      'moss-covered-typewriter@seedream-5.0': decision('moss-covered-typewriter', 'seedream-5.0', 'rejected', 'the output is square'),
    });

    const dry = await cli('publish');
    assert.equal(dry.code, 0, dry.text);
    assert.match(dry.text, /would add 1 run to published recipes; 0 already there; 0 refused/);
    assert.deepEqual(await recipeFiles(dirs.content, ['tin-robot-tea-party']), { 'tin-robot-tea-party': before['tin-robot-tea-party'] }, 'a dry run writes nothing');

    const { code, text } = await cli('publish', '--apply');
    assert.equal(code, 0, text);
    assert.match(text, /decisions on new runs: 1 approved, 1 rejected/);
    assert.match(text, /added 1 run to published recipes; 0 already there; 0 refused\n {2}\+ tin-robot-tea-party@seedream-5\.0/);

    const was = JSON.parse(before['tin-robot-tea-party']);
    const now = JSON.parse(await readFile(path.join(dirs.content, 'tin-robot-tea-party.json'), 'utf8'));
    assert.deepEqual(checkRecipe(now, { file: 'tin-robot-tea-party.json' }), []);
    assert.equal(now.runs.length, 2);
    assert.deepEqual(now.runs[0], was.runs[0], 'the first run is as it was');
    assert.deepEqual({ ...now, runs: was.runs }, was, 'nothing else changed: not the review, not the prompt');
    assert.deepEqual(Object.keys(now.runs[1]), ['model', 'prompt_lang', 'params', 'cost_cents', 'generated_on', 'approved_on', 'outputs']);
    assert.deepEqual({ ...now.runs[1], outputs: undefined }, {
      model: 'seedream-5.0', prompt_lang: 'en', params: { size: '1024x1024', n: 1 }, cost_cents: 4,
      generated_on: '2026-11-22', approved_on: '2026-11-23', outputs: undefined,
    });
    assert.equal(now.review.approved_on, was.review.approved_on, 'the recipe\'s own review is not touched');
    assert.equal(await readFile(path.join(dirs.content, 'moss-covered-typewriter.json'), 'utf8'), before['moss-covered-typewriter'], 'a rejected run is not added');

    const manifest = await readJson(path.join(dirs.work, 'manifest.json'));
    assert.deepEqual(manifest.upload, ['tin-robot-tea-party/seedream-5.0-0-480.webp', 'tin-robot-tea-party/seedream-5.0-0.webp']);

    const again = await cli('publish', '--apply');
    assert.equal(again.code, 0, again.text);
    assert.match(again.text, /added 0 runs to published recipes; 1 already there; 0 refused/);
  });

  test('a run whose recipe\'s prompt is no longer the one it was made with is refused, and nothing is written', async (t) => {
    const { cli, dirs } = await rerunDone(t);
    const file = path.join(dirs.content, 'tin-robot-tea-party.json');
    const changed = JSON.parse(await readFile(file, 'utf8'));
    changed.prompt.en = `${changed.prompt.en} Edited.`;
    await writeFile(file, `${JSON.stringify(changed, null, 2)}\n`);
    await decide(dirs, { 'tin-robot-tea-party@seedream-5.0': decision('tin-robot-tea-party', 'seedream-5.0', 'approved') });

    const { code, text } = await cli('publish', '--apply');
    assert.equal(code, 1, text);
    assert.match(text, /refused tin-robot-tea-party@seedream-5\.0:\n\s+the recipe's English prompt is no longer the one this run was made with/);
    assert.equal(JSON.parse(await readFile(file, 'utf8')).runs.length, 1);
  });

  test('nothing is published that the operator did not approve', async (t) => {
    const { cli, dirs } = await rerunDone(t);
    const before = await recipeFiles(dirs.content, ['tin-robot-tea-party', 'moss-covered-typewriter']);
    assert.equal((await cli('publish', '--apply')).code, 1, 'there are no decisions yet');
    await decide(dirs, {});
    await cli('publish', '--apply');
    assert.deepEqual(await recipeFiles(dirs.content, ['tin-robot-tea-party', 'moss-covered-typewriter']), before);
  });

  test('the cap covers what earlier reruns spent, and the status says what each rerun did', async (t) => {
    const { cli } = await workspace(t, { recipes: ['tin-robot-tea-party', 'moss-covered-typewriter'], models: MODELS, world: fakeWorld({ models: MODELS, imageCost: 4 }) });
    const first = await cli('rerun', '--models', 'seedream-5.0', '--slugs', 'tin-robot-tea-party', '--max-spend-cents', '4', '--apply');
    assert.equal(first.code, 0, first.text);
    const second = await cli('rerun', '--models', 'seedream-5.0', '--slugs', 'moss-covered-typewriter', '--max-spend-cents', '4', '--apply');
    assert.equal(second.code, 1, second.text);
    assert.match(second.text, /cap: 4 cents, against 4 cents spent in this work folder and 0 cents held by unfinished jobs; 0 of the 1 fit under it/);
    assert.match(second.text, /stopped at the cap/);
    const status = await cli('status');
    assert.match(status.text, /rerun {6}1 done · 0 unfinished · 0 uncertain · 0 failed/);
    assert.match(status.text, /spent: 4 cents in total \(rerun 4 cents\)/);
  });

  test('a decisions file is checked for its runs as for its recipes', () => {
    const made = (decision, extra = {}) => ({ decision, reason: '', decided_on: '2026-11-23', ...extra });
    assert.deepEqual(checkDecisions({ standard_version: 2, decisions: {}, runs: { 'a@m': made('approved'), 'b@m': made('rejected', { reason: 'square' }) } }), []);
    assert.deepEqual(checkDecisions({ standard_version: 2, decisions: {}, runs: { 'b@m': made('rejected') } }), ['b@m: rejected without a reason']);
    assert.deepEqual(checkDecisions({ standard_version: 2, decisions: {}, runs: { 'c@m': { decision: 'approved' } } }), ['c@m: no date']);
    assert.deepEqual(checkDecisions({ standard_version: 2, decisions: {}, runs: [] }), ['its runs are not decisions by run']);
  });
});

describe('a run\'s approval date, in the recipe format', () => {
  test('is optional, and when a run has one it is an ISO date', () => {
    const whale = recipe('glass-whale-over-rooftops');
    assert.deepEqual(checkRecipe(whale, { file: 'glass-whale-over-rooftops.json' }), [], 'a run without one stays valid');
    whale.runs[1].approved_on = '2026-11-12';
    assert.deepEqual(checkRecipe(whale, { file: 'glass-whale-over-rooftops.json' }), []);
    whale.runs[1].approved_on = '12 November 2026';
    assert.deepEqual(checkRecipe(whale).map(({ rule, path: where }) => [rule, where]), [['iso-date', 'runs[1].approved_on']]);
  });

  test('a run\'s approval leaves the recipe\'s own review as it is', () => {
    const whale = recipe('glass-whale-over-rooftops');
    const approved = { ...whale, runs: [whale.runs[0], { ...whale.runs[1], approved_on: '2026-11-12' }] };
    assert.deepEqual(approved.review, whale.review);
    assert.deepEqual(checkRecipe(approved), []);
  });
});
