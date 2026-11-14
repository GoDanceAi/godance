/**
 * translate, review and publish: a recipe is complete in both languages,
 * only what the operator approved is written, and nothing is written that
 * the schema rejects or whose slug is taken.
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { checkRecipe } from '../../src/lib/gallery-schema.js';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { createApi } from './lib/api.js';
import { createBudget, openLedger } from './lib/budget.js';
import { priceBook } from './lib/pricing.js';
import { readJson } from './lib/work.js';
import { buildRecipe, publish, remove } from './stages/publish.js';
import { checkDecisions, reviewItems, reviewSheet, standardVersionOf } from './stages/review.js';
import { renderTemplate, defaultsIn } from '../../src/lib/gallery-template.js';
import { defaultsMessages, markerFault, planTranslation, projectTranslate, readDefaults, templateMessages, translateEntries, translationMessages } from './stages/translate.js';
import { API_BASE, CATALOGUE, FAKE_KEY, fakeWorld, noSleep, tempDir } from './testing.js';

const imageEntry = (overrides = {}) => ({
  id: 'x-1001', source: 'gpt-image-2', list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0',
  modality: 'IMAGE', model: 'gpt-image-2', title: 'Harbour Poster', title_zh: '港口海报', category: 'poster', lang: 'en',
  prompt: 'A flat poster of a small harbour, {argument name="boat colour" default="red"} boats.',
  variants: { en: 'A flat poster of a small harbour, {argument name="boat colour" default="red"} boats.' },
  author: 'inventedone', author_url: 'https://x.com/inventedone', original_url: 'https://x.com/inventedone/status/1001',
  needs_input: null, problems: [], ...overrides,
});
const videoEntry = (overrides = {}) => ({
  id: 'x-2002', source: 'seedance-2', list: 'invented/video-prompts', list_commit: 'b'.repeat(40), licence: 'CC-BY-4.0',
  modality: 'VIDEO', model: 'seedance-2.0', title: 'Kite Over The Salt Flat', title_zh: '盐滩上的风筝', category: null, lang: 'zh',
  prompt: '正午时分，一只红色风筝飘过白色盐滩。', variants: { en: 'A red kite drifts over a white salt flat at noon.', zh: '正午时分，一只红色风筝飘过白色盐滩。' },
  author: '虚构作者', author_url: 'https://x.com/inventedeight', original_url: 'https://x.com/inventedeight/status/2002',
  needs_input: null, stated_seconds: null, problems: [], ...overrides,
});

const translatedImage = () => ({
  status: 'done', slug: 'harbour-poster', title: { en: 'Harbour Poster', zh: '港口海报' },
  prompt: { en: 'A flat poster of a small harbour, red boats.', zh: '一张小港口的扁平海报，红色的船。', zh_origin: 'machine', variables: [{ name: 'boat colour', default: 'red' }] },
  prompt_lang: 'en', changes: ['placeholders resolved', 'translated to Chinese'],
});
/** The same entry translated with its template kept. */
const templatedImage = () => ({
  status: 'done', slug: 'harbour-poster', title: { en: 'Harbour Poster', zh: '港口海报' },
  prompt: {
    en: 'A flat poster of a small harbour, red boats.', zh: '一张小港口的扁平海报，红色的船。', zh_origin: 'machine',
    template: { en: 'A flat poster of a small harbour, {{boat colour}} boats.', zh: '一张小港口的扁平海报，{{boat colour}}的船。' },
    variables: [{ name: 'boat colour', default: 'red', zh_default: '红色' }],
  },
  prompt_lang: 'en', changes: ['placeholders rewritten as {{name}}', 'translated to Chinese'],
});

/**
 * A stand-in translator. `template` and `defaults` are what it answers when
 * asked for a template and for the defaults; an array is one answer per
 * request, the last repeating. Anything else gets `plain`.
 */
function translator({ template = '一张小港口的扁平海报，{{boat colour}}的船。', defaults = '{"boat colour": "红色"}', plain = '一张小港口的扁平海报，红色的船。' } = {}) {
  const asked = { template: 0, defaults: 0, plain: 0 };
  const next = (answers, n) => (Array.isArray(answers) ? answers[Math.min(n, answers.length - 1)] : answers);
  const reply = (messages) => {
    if (/values that fill the blanks/.test(messages[0].content)) return next(defaults, asked.defaults++);
    if (/placeholders written in double braces/.test(messages[0].content)) return next(template, asked.template++);
    return next(plain, asked.plain++);
  };
  reply.asked = asked;
  return reply;
}

const imageRun = () => ({
  status: 'done', job_id: 'job-1', slug: 'harbour-poster', model: 'gpt-image-2', params: { size: '1024x1024', n: 1 }, prompt_lang: 'en', hold_cents: 6,
  cost_cents: 6, generated_on: '2026-11-20',
  outputs: [{ file: 'harbour-poster/gpt-image-2-0.webp', thumb: 'harbour-poster/gpt-image-2-0-480.webp', content_type: 'image/webp', width: 1024, height: 1024 }],
});
const translatedVideo = () => ({
  status: 'done', slug: 'kite-over-the-salt-flat', title: { en: 'Kite Over The Salt Flat', zh: '盐滩上的风筝' },
  prompt: { en: 'A red kite drifts over a white salt flat at noon.', zh: '正午时分，一只红色风筝飘过白色盐滩。', zh_origin: 'list' },
  prompt_lang: 'zh', changes: [],
});
const videoRun = () => ({
  status: 'done', job_id: 'job-2', slug: 'kite-over-the-salt-flat', model: 'seedance-2.0', params: { seconds: 5, size: '1280x720' }, prompt_lang: 'zh', hold_cents: 81,
  cost_cents: 81, generated_on: '2026-11-20',
  outputs: [{ file: 'kite-over-the-salt-flat/seedance-2.0-0.mp4', content_type: 'video/mp4', duration_seconds: 5, width: 1280, height: 720 }],
});
const approved = (slug) => ({ slug, decision: 'approved', reason: '', decided_on: '2026-11-21' });
const rejected = (slug, reason = 'the output shows a logo') => ({ slug, decision: 'rejected', reason, decided_on: '2026-11-21' });

/** A whole batch ready to publish: one image and one video, with the operator's decisions. */
async function batch(t, decisions) {
  const dir = await tempDir(t);
  return {
    contentDir: path.join(dir, 'content'),
    manifestFile: path.join(dir, 'work', 'manifest.json'),
    removalFile: path.join(dir, 'config', 'removal-list.json'),
    entries: [imageEntry(), videoEntry()],
    translated: { 'x-1001': translatedImage(), 'x-2002': translatedVideo() },
    runs: { 'x-1001': imageRun(), 'x-2002': videoRun() },
    decisions: { standard_version: 1, decisions },
    now: () => new Date('2026-11-22T09:00:00Z'),
  };
}

describe('translate', () => {
  const prices = priceBook(CATALOGUE);
  async function run(t, entries, { onChat, cap = 100, taken } = {}) {
    const work = await tempDir(t);
    const world = fakeWorld({ onChat });
    const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: noSleep });
    const state = {};
    const budget = createBudget({ cap });
    const result = await translateEntries(entries, state, { api, prices, budget, ledger: await openLedger(path.join(work, 'ledger.json')), model: 'gpt-4o-mini', save: async () => {}, takenSlugs: taken, concurrency: 1 });
    return { state, world, budget, result };
  }

  test('plans from what the list supplies: which language is missing, the variables and the template', () => {
    const plan = planTranslation(imageEntry());
    assert.deepEqual(plan.prompt, { en: 'A flat poster of a small harbour, red boats.' });
    assert.deepEqual(plan.variables, [{ name: 'boat colour', default: 'red' }]);
    assert.equal(plan.missing, 'zh');
    assert.equal(plan.zhOrigin, 'machine');
    // The blank is kept as a marker; the Chinese side is still to be translated.
    assert.deepEqual(plan.template, { ready: false, en: 'A flat poster of a small harbour, {{boat colour}} boats.', variables: [{ name: 'boat colour', default: 'red' }], changes: [] });
    assert.deepEqual(plan.changes, ['placeholders rewritten as {{name}}']);
    assert.deepEqual({ missing: planTranslation(videoEntry()).missing, zhOrigin: planTranslation(videoEntry()).zhOrigin, template: planTranslation(videoEntry()).template },
      { missing: null, zhOrigin: 'list', template: null });
  });

  test("uses the list's own text in both languages when it has them, and calls no model", async (t) => {
    const { state, world, budget } = await run(t, [videoEntry()]);
    assert.equal(world.chats.length, 0);
    assert.equal(budget.spent, 0);
    assert.deepEqual(state['x-2002'], translatedVideo());
  });

  test('translates the missing language with a text model, and records that it did', async (t) => {
    const plain = imageEntry({ prompt: 'A flat poster of a small harbour, red boats.', variants: { en: 'A flat poster of a small harbour, red boats.' } });
    const { state, world, budget } = await run(t, [plain], { onChat: () => '一张小港口的扁平海报，红色的船。' });
    assert.equal(world.chats.length, 1);
    assert.match(world.chats[0].messages[1].content, /^Translate into Simplified Chinese:\n\n<prompt>\nA flat poster of a small harbour, red boats\.\n<\/prompt>$/);
    const { variables: _none, ...prompt } = translatedImage().prompt;
    assert.deepEqual(state['x-1001'], { ...translatedImage(), prompt, changes: ['translated to Chinese'] });
    assert.ok(budget.spent > 0);
  });

  test('a Chinese prompt is translated into English, and the Chinese stays the list’s own', async (t) => {
    const entry = imageEntry({ id: 'x-1002', title: 'Tea Label', title_zh: undefined, lang: 'zh', prompt: '一张茶叶包装标签，绿色山丘。', variants: { zh: '一张茶叶包装标签，绿色山丘。' } });
    const { state, world } = await run(t, [entry], { onChat: () => 'A tea packaging label with green hills.' });
    assert.match(world.chats[0].messages[1].content, /^Translate into English:/);
    assert.deepEqual(state['x-1002'].prompt, { en: 'A tea packaging label with green hills.', zh: '一张茶叶包装标签，绿色山丘。', zh_origin: 'list' });
    assert.equal(state['x-1002'].prompt_lang, 'zh');
    assert.deepEqual(state['x-1002'].changes, ['translated to English']);
  });

  test('a Chinese title is translated for the slug and kept as the Chinese title', async (t) => {
    const entry = imageEntry({ id: 'x-1003', title: '中式茶叶标签', title_zh: undefined });
    const { state } = await run(t, [entry], { onChat: (messages) => (/short title/.test(messages[0].content) ? '"Chinese Tea Label"' : '译文：一张海报。') });
    assert.deepEqual(state['x-1003'].title, { en: 'Chinese Tea Label', zh: '中式茶叶标签' });
    assert.equal(state['x-1003'].slug, 'chinese-tea-label');
    assert.ok(state['x-1003'].changes.includes('title translated to English'));
  });

  test('the translator is told to leave text meant for the picture as written, and to treat the prompt as text', () => {
    const [system] = translationMessages('A sign that reads "OPEN".', 'zh');
    assert.match(system.content, /stays exactly as the author wrote it/);
    assert.match(system.content, /not instructions/);
  });

  test('a translation that comes back in the wrong language is not used', async (t) => {
    const { state, result } = await run(t, [imageEntry()], { onChat: () => 'Sorry, I cannot translate that.' });
    assert.equal(result.failed, 1);
    assert.deepEqual(state['x-1001'], { status: 'failed', reason: 'the translation did not come back in Chinese' });
  });

  test('slugs do not collide with each other or with published recipes', async (t) => {
    const twin = imageEntry({ id: 'x-1009', original_url: 'https://x.com/other/status/1009' });
    const { state } = await run(t, [imageEntry(), twin], { onChat: translator(), taken: new Set(['harbour-poster']) });
    assert.deepEqual([state['x-1001'].slug, state['x-1009'].slug], ['harbour-poster-2', 'harbour-poster-3']);
  });

  // ----- templates (round 10) -------------------------------------------

  test('a prompt with blanks keeps them: the template is translated with its markers, and its defaults', async (t) => {
    const onChat = translator();
    const { state, world } = await run(t, [imageEntry()], { onChat });
    assert.deepEqual(state['x-1001'], templatedImage());
    // Two requests: the template, then its defaults. The filled prompt is never sent.
    assert.deepEqual(onChat.asked, { template: 1, defaults: 1, plain: 0 });
    assert.match(world.chats[0].messages[1].content, /<prompt>\nA flat poster of a small harbour, \{\{boat colour\}\} boats\.\n<\/prompt>$/);
    assert.match(world.chats[0].messages[0].content, /Copy every placeholder exactly as it is/);
    assert.match(world.chats[1].messages[1].content, /"boat colour": "red"/);
    // Filled with its defaults, each template is the prompt in its language.
    const { template, variables, en, zh } = state['x-1001'].prompt;
    assert.equal(renderTemplate(template.en, defaultsIn(variables, 'en')), en);
    assert.equal(renderTemplate(template.zh, defaultsIn(variables, 'zh')), zh);
  });

  test('a default that is not words gets no zh_default, and fills the Chinese as it is', async (t) => {
    const entry = imageEntry({ variants: { en: 'A poster, {argument name="ratio" default="3:4"} ratio, brand {argument name="brand" default="PURE CRUNCH"}.' } });
    const onChat = translator({ template: '一张海报，比例{{ratio}}，品牌{{brand}}。', defaults: '{"ratio": null, "brand": null}' });
    const { state } = await run(t, [entry], { onChat });
    assert.deepEqual(state['x-1001'].prompt.variables, [{ name: 'ratio', default: '3:4' }, { name: 'brand', default: 'PURE CRUNCH' }]);
    assert.equal(state['x-1001'].prompt.zh, '一张海报，比例3:4，品牌PURE CRUNCH。');
  });

  test('a translation that loses a marker is asked for once more', async (t) => {
    const onChat = translator({ template: ['一张小港口的扁平海报，红色的船。', '一张小港口的扁平海报，{{boat colour}}的船。'] });
    const { state } = await run(t, [imageEntry()], { onChat });
    assert.deepEqual(onChat.asked, { template: 2, defaults: 1, plain: 0 });
    assert.deepEqual(state['x-1001'], templatedImage());
  });

  for (const [what, reply, why] of [
    ['loses a marker', '一张小港口的扁平海报，红色的船。', 'the variable "boat colour" is declared but the template never uses it'],
    ['translates a marker', '一张小港口的扁平海报，{{船的颜色}}的船。', 'the "{{" at position 11 does not begin a variable'],
    ['renames a marker', '一张小港口的扁平海报，{{boat color}}的船。', '{{boat color}} is not a declared variable'],
    ['repeats a marker', '一张{{boat colour}}小港口的扁平海报，{{boat colour}}的船。', 'a marker appears a different number of times than in the original'],
  ]) {
    test(`a translation that ${what} twice falls back to the filled prompt, with a review note`, async (t) => {
      const onChat = translator({ template: reply });
      const { state } = await run(t, [imageEntry()], { onChat });
      // Two tries at the template, then the filled prompt is translated as before.
      assert.deepEqual(onChat.asked, { template: 2, defaults: 0, plain: 1 });
      const record = state['x-1001'];
      assert.deepEqual({ ...record, notes: undefined }, { ...translatedImage(), notes: undefined });
      assert.equal(record.prompt.template, undefined);
      assert.equal(record.notes.length, 1);
      assert.ok(record.notes[0].startsWith('template not kept: the Chinese translation lost or changed a {{name}} marker twice ('), record.notes[0]);
      assert.ok(record.notes[0].includes(why), record.notes[0]);
    });
  }

  test('defaults that come back unusable twice fall back the same way', async (t) => {
    const onChat = translator({ defaults: 'Here are the translations: 红色' });
    const { state } = await run(t, [imageEntry()], { onChat });
    assert.deepEqual(onChat.asked, { template: 1, defaults: 2, plain: 1 });
    assert.equal(state['x-1001'].prompt.template, undefined);
    assert.deepEqual(state['x-1001'].changes, ['placeholders resolved', 'translated to Chinese']);
    assert.deepEqual(state['x-1001'].notes, ['template not kept: the defaults could not be translated into Chinese: the answer was not usable, twice']);
  });

  test('a Chinese template is translated into English: default is the English, zh_default the list’s own', async (t) => {
    const zh = '一张茶叶包装标签，{argument name="hill colour" default="绿色"}山丘，{argument name="ratio" default="3:4"}。';
    const entry = imageEntry({ id: 'x-1002', title: 'Tea Label', title_zh: undefined, lang: 'zh', prompt: zh, variants: { zh } });
    const onChat = translator({ template: 'A tea packaging label with {{hill colour}} hills, {{ratio}}.', defaults: '{"hill colour": "green", "ratio": null}' });
    const { state } = await run(t, [entry], { onChat });
    assert.deepEqual(state['x-1002'].prompt, {
      en: 'A tea packaging label with green hills, 3:4.',
      zh: '一张茶叶包装标签，绿色山丘，3:4。',
      zh_origin: 'list',
      template: { en: 'A tea packaging label with {{hill colour}} hills, {{ratio}}.', zh: '一张茶叶包装标签，{{hill colour}}山丘，{{ratio}}。' },
      variables: [{ name: 'hill colour', default: 'green', zh_default: '绿色' }, { name: 'ratio', default: '3:4' }],
    });
    assert.deepEqual(state['x-1002'].changes, ['placeholders rewritten as {{name}}', 'translated to English']);
    assert.equal(state['x-1002'].prompt_lang, 'zh');
  });

  test('a list that supplies both languages with the same blanks needs no model', async (t) => {
    const entry = videoEntry({
      variants: {
        en: 'A {argument name="kite colour" default="red"} kite drifts over a white salt flat at noon.',
        zh: '正午时分，一只{argument name="kite colour" default="红色"}风筝飘过白色盐滩。',
      },
      prompt: '正午时分，一只{argument name="kite colour" default="红色"}风筝飘过白色盐滩。',
    });
    const { state, world } = await run(t, [entry]);
    assert.equal(world.chats.length, 0);
    assert.deepEqual(state['x-2002'].prompt, {
      en: 'A red kite drifts over a white salt flat at noon.',
      zh: '正午时分，一只红色风筝飘过白色盐滩。',
      zh_origin: 'list',
      template: { en: 'A {{kite colour}} kite drifts over a white salt flat at noon.', zh: '正午时分，一只{{kite colour}}风筝飘过白色盐滩。' },
      variables: [{ name: 'kite colour', default: 'red', zh_default: '红色' }],
    });
    assert.deepEqual(state['x-2002'].changes, ['placeholders rewritten as {{name}}']);
  });

  test('a list whose two languages have different blanks keeps no template, and says so', async (t) => {
    const entry = videoEntry({
      variants: { en: 'A {argument name="kite colour" default="red"} kite over a salt flat.', zh: '正午时分，一只红色风筝飘过{argument name="place" default="盐滩"}。' },
      prompt: '正午时分，一只红色风筝飘过{argument name="place" default="盐滩"}。',
    });
    const { state, world } = await run(t, [entry]);
    assert.equal(world.chats.length, 0);
    assert.equal(state['x-2002'].prompt.template, undefined);
    assert.deepEqual(state['x-2002'].changes, ['placeholders resolved']);
    assert.deepEqual(state['x-2002'].notes, ["template not kept: the list's English and Chinese prompts do not have the same blanks"]);
  });

  test('names the template rules refuse are replaced, and source.changes lists each change', async (t) => {
    const en = 'Two friends: one with {argument name="hair color" default="dark brown"} hair, one with {argument name="hair color" default="black"} hair, holding a {argument name="Main Subject!" default="kite"}.';
    const onChat = translator({
      template: '两位朋友：一位{{hair color}}头发，一位{{hair color 2}}头发，拿着{{Main Subject}}。',
      defaults: '{"hair color": "深棕色", "hair color 2": "黑色", "Main Subject": "风筝"}',
    });
    const { state } = await run(t, [imageEntry({ variants: { en }, prompt: en })], { onChat });
    const record = state['x-1001'];
    assert.equal(record.prompt.template.en, 'Two friends: one with {{hair color}} hair, one with {{hair color 2}} hair, holding a {{Main Subject}}.');
    assert.deepEqual(record.prompt.variables.map((variable) => variable.name), ['hair color', 'hair color 2', 'Main Subject']);
    assert.deepEqual(record.changes, [
      'placeholders rewritten as {{name}}',
      'placeholder "hair color" has more than one default; the one that defaults to "black" is "hair color 2"',
      'placeholder "Main Subject!" renamed "Main Subject"',
      'translated to Chinese',
    ]);
    assert.equal(record.prompt.en, 'Two friends: one with dark brown hair, one with black hair, holding a kite.');
    assert.equal(record.prompt.zh, '两位朋友：一位深棕色头发，一位黑色头发，拿着风筝。');
  });

  test('a prompt that is JSON keeps its template, and is still JSON in both languages', async (t) => {
    // In a JSON prompt the list escapes the quotes of its placeholders.
    const en = '{"scene":{"subject":"a mug on a kitchen shelf in morning light","colour":"{argument name=\\"colour\\" default=\\"teal\\"}"}}';
    const onChat = translator({ template: '{"scene":{"subject":"晨光中厨房架子上的一个马克杯","colour":"{{colour}}"}}', defaults: '{"colour": "青绿色"}' });
    const { state } = await run(t, [imageEntry({ variants: { en }, prompt: en })], { onChat });
    const { prompt } = state['x-1001'];
    assert.equal(prompt.template.en, '{"scene":{"subject":"a mug on a kitchen shelf in morning light","colour":"{{colour}}"}}');
    assert.deepEqual(prompt.variables, [{ name: 'colour', default: 'teal', zh_default: '青绿色' }]);
    // Filled, each ends in "}}" and parses.
    assert.deepEqual(JSON.parse(prompt.en), { scene: { subject: 'a mug on a kitchen shelf in morning light', colour: 'teal' } });
    assert.deepEqual(JSON.parse(prompt.zh), { scene: { subject: '晨光中厨房架子上的一个马克杯', colour: '青绿色' } });
  });

  test('a translated default that would break a JSON prompt gives the template up', async (t) => {
    const en = '{"scene":{"subject":"a mug on a kitchen shelf in morning light","colour":"{argument name=\\"colour\\" default=\\"teal\\"}"}}';
    const onChat = translator({
      template: '{"scene":{"subject":"晨光中厨房架子上的一个马克杯","colour":"{{colour}}"}}',
      defaults: '{"colour": "青绿色\\"哑光"}',     // a quote that the JSON around it does not escape
      plain: '{"scene":{"subject":"晨光中厨房架子上的一个马克杯","colour":"青绿色"}}',
    });
    const { state } = await run(t, [imageEntry({ variants: { en }, prompt: en })], { onChat });
    assert.equal(state['x-1001'].prompt.template, undefined);
    assert.deepEqual(state['x-1001'].changes, ['placeholders resolved', 'translated to Chinese']);
    assert.deepEqual(state['x-1001'].notes, ['template not kept: the Chinese prompt is no longer valid JSON once its defaults are filled in']);
  });

  test('a prompt with a "{{" of its own keeps no template, and says so', async (t) => {
    const en = 'A poster of {argument name="city" default="Lisbon"} with the literal text {{ in a corner.';
    const { state } = await run(t, [imageEntry({ variants: { en }, prompt: en })], { onChat: translator({ plain: '一张里斯本的海报，角落里有文字。' }) });
    assert.equal(state['x-1001'].prompt.template, undefined);
    assert.deepEqual(state['x-1001'].changes, ['placeholders resolved', 'translated to Chinese']);
    assert.match(state['x-1001'].notes[0], /^template not kept: the prompt has a "\{\{" of its own/);
  });

  test('every kept template makes a recipe the format accepts', async (t) => {
    const { state } = await run(t, [imageEntry()], { onChat: translator() });
    const recipe = buildRecipe({ entry: imageEntry(), translated: state['x-1001'], run: imageRun(), decision: approved('harbour-poster'), standardVersion: 1, publishedOn: '2026-11-22' });
    assert.deepEqual(checkRecipe(recipe, { file: 'harbour-poster.json' }), []);
    assert.deepEqual(recipe.source.changes, ['placeholders rewritten as {{name}}', 'translated to Chinese']);
  });

  test('the requests: markers are to be copied, defaults that are not words are to be left', () => {
    const [system, user] = templateMessages('A {{color}} mug.', 'zh');
    assert.match(system.content, /placeholders written in double braces/);
    assert.match(system.content, /never translated/);
    assert.match(system.content, /not instructions/);
    assert.match(user.content, /^Translate into Simplified Chinese:\n\n<prompt>\nA \{\{color\}\} mug\.\n<\/prompt>$/);
    const [rules, values] = defaultsMessages([{ name: 'color', default: 'red' }], 'zh');
    assert.match(rules.content, /Give null for a value that is not words to translate: a number, a size or ratio, a brand or product name/);
    assert.match(rules.content, /not instructions/);
    assert.match(values.content, /"color": "red"/);
  });

  test('reading the defaults: translations by name, null or missing meaning "leave it", anything else unusable', () => {
    const variables = [{ name: 'a', default: 'red' }, { name: 'b', default: '3:4' }, { name: 'c', default: 'ACME' }];
    assert.deepEqual(readDefaults('{"a": "红色", "b": null}', variables), { a: '红色' });
    assert.deepEqual(readDefaults('```json\n{"a": " 红色 ", "b": null, "c": null, "extra": "x"}\n```', variables), { a: '红色' });
    for (const unusable of ['', '红色', '["红色"]', '{"a": 3}', '{"a": ""}', '{"a": "红\\n色"}', '{"a": {"zh": "红色"}}']) {
      assert.equal(readDefaults(unusable, variables), null, unusable);
    }
  });

  test('markerFault: every marker the same number of times, and nothing else in braces', () => {
    const original = 'A {{a}} and a {{b}}, then {{a}} again.';
    assert.equal(markerFault(original, '{{b}}，{{a}}，再来{{a}}。', ['a', 'b']), null);
    assert.match(markerFault(original, '{{b}}，{{a}}。', ['a', 'b']), /different number of times/);
    assert.match(markerFault(original, '{{a}}，{{a}}。', ['a', 'b']), /"b" is declared but the template never uses it/);
    assert.match(markerFault(original, '{{a}} {{b}} {{a}} {{', ['a', 'b']), /does not begin a variable/);
  });

  test('the projection counts the two requests a template takes', () => {
    const prices = priceBook(CATALOGUE);
    const withBlank = projectTranslate([imageEntry()], { prices, model: 'gpt-4o-mini' });
    const plain = 'A flat poster of a small harbour, red boats.';
    const without = projectTranslate([imageEntry({ prompt: plain, variants: { en: plain } })], { prices, model: 'gpt-4o-mini' });
    assert.deepEqual([withBlank.needModel, without.needModel], [1, 1]);
    assert.ok(withBlank.cents > without.cents, `${withBlank.cents} > ${without.cents}`);
  });

  test('stops at the cap, and a later run does only what is left', async (t) => {
    const first = await run(t, [imageEntry()], { cap: 0 });
    assert.equal(first.result.stoppedByBudget, true);
    assert.equal(first.world.chats.length, 0);
    assert.deepEqual(first.state, {});
  });
});

describe('the review sheet, for a prompt with a template', () => {
  const fallen = { ...translatedImage(), notes: ['template not kept: the Chinese translation lost or changed a {{name}} marker twice (…)'] };
  const items = reviewItems({
    entries: [imageEntry(), imageEntry({ id: 'x-1010' })],
    screened: { 'x-1001': { status: 'candidate', reasons: [], notes: ['classifier: nothing found against the standard'] }, 'x-1010': { status: 'candidate', reasons: [], notes: [] } },
    translated: { 'x-1001': templatedImage(), 'x-1010': fallen },
    runs: { 'x-1001': imageRun(), 'x-1010': imageRun() },
  });

  test('carries the template cut at its markers, in both languages', () => {
    assert.deepEqual(items[0].template, {
      en: [{ text: 'A flat poster of a small harbour, ' }, { name: 'boat colour' }, { text: ' boats.' }],
      zh: [{ text: '一张小港口的扁平海报，' }, { name: 'boat colour' }, { text: '的船。' }],
    });
    assert.equal(items[1].template, null);
  });

  test('a template that could not be kept says why in the notes, after the screening notes', () => {
    assert.deepEqual(items[0].notes, ['classifier: nothing found against the standard']);
    assert.deepEqual(items[1].notes, ['template not kept: the Chinese translation lost or changed a {{name}} marker twice (…)']);
  });

  test('the sheet marks each variable and lists the defaults in both languages', () => {
    const html = reviewSheet({ items, standardVersion: 1 });
    // The page marks variables and builds the table from data: the data carries what it needs.
    for (const expected of ['"name":"boat colour"', '"zh_default":"红色"', "el('mark'", 'Default, English', 'Default, Chinese', 'Template, Chinese', 'the standard applies to these too', 'template not kept']) {
      assert.ok(html.includes(expected), `the sheet is missing ${expected}`);
    }
    // Third-party text is still never written into the page as markup.
    assert.equal(/<mark>/.test(html), false);
  });
});

describe('the review sheet', () => {
  const items = reviewItems({
    entries: [imageEntry(), videoEntry(), imageEntry({ id: 'x-9', title: 'No Run Yet' })],
    screened: { 'x-1001': { status: 'candidate', reasons: [], notes: ['classifier: nothing found against the standard'] }, 'x-2002': { status: 'candidate', reasons: [], notes: [] } },
    translated: { 'x-1001': translatedImage(), 'x-2002': translatedVideo(), 'x-9': translatedImage() },
    runs: { 'x-1001': imageRun(), 'x-2002': videoRun() },
  });

  test('lists every candidate with a finished run, and only those', () => {
    assert.deepEqual(items.map((item) => item.slug), ['harbour-poster', 'kite-over-the-salt-flat']);
    assert.deepEqual(items[0].notes, ['classifier: nothing found against the standard']);
    assert.equal(items[0].run.cost_cents, 6);
    assert.equal(items[0].source.original_url, 'https://x.com/inventedone/status/1001');
  });

  test('carries the output, both prompts, the source, the notes and the cost', () => {
    const html = reviewSheet({ items, standardVersion: 1 });
    for (const expected of ['harbour-poster/gpt-image-2-0.webp', 'A flat poster of a small harbour, red boats.', '一张小港口的扁平海报', 'inventedone', 'invented/image-prompts', 'CC0-1.0', 'nothing found against the standard', '"cost_cents":6', '"standardVersion":1']) {
      assert.ok(html.includes(expected), `the sheet is missing ${expected}`);
    }
  });

  test('never builds HTML from a prompt: third-party text cannot break out of the page', () => {
    const hostile = imageEntry();
    const record = { ...translatedImage(), prompt: { ...translatedImage().prompt, en: '</script><script>alert(1)</script><img src=x onerror=alert(2)>' } };
    const html = reviewSheet({ items: reviewItems({ entries: [hostile], screened: {}, translated: { 'x-1001': record }, runs: { 'x-1001': imageRun() } }), standardVersion: 1 });
    assert.equal(html.includes('</script><script>alert(1)'), false);
    assert.equal(html.includes('<img src=x'), false);
    assert.equal((html.match(/<\/script>/g) ?? []).length, 2, 'only the two script elements of the page itself close');
    const data = JSON.parse(/<script type="application\/json" id="data">([\s\S]*?)<\/script>/.exec(html)[1]);
    assert.equal(data.items[0].prompt.en, record.prompt.en, 'the text itself is intact, as data');
  });

  test('loads nothing from the network', () => {
    const html = reviewSheet({ items, standardVersion: 1 });
    assert.match(html, /Content-Security-Policy" content="default-src 'none'/);
    assert.equal(/<(script|link|img|iframe)[^>]+(src|href)=["']https?:/i.test(html), false);
  });

  test('reads the version the standard gives itself', () => {
    assert.equal(standardVersionOf('# Standard\n\n**Version:** 3\n\ntext'), 3);
    assert.equal(standardVersionOf('# Standard, version two'), null);
    assert.equal(standardVersionOf(null), null);
  });

  test('a decisions file must say which standard was applied, and why anything was rejected', () => {
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { a: approved('a'), b: rejected('b') } }), []);
    assert.deepEqual(checkDecisions({ decisions: { a: approved('a') } }), ['it does not say which version of the content standard was applied']);
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { b: rejected('b', '  ') } }), ['b: rejected without a reason']);
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { c: { decision: 'maybe', decided_on: '2026-11-21' } } }), ['c: the decision is neither approved nor rejected']);
    assert.deepEqual(checkDecisions([]), ['it is not a decisions file']);
  });
});

describe('publish', () => {
  test('builds a recipe the schema accepts, for an image and for a video', () => {
    const image = buildRecipe({ entry: imageEntry(), translated: translatedImage(), run: imageRun(), decision: approved('harbour-poster'), standardVersion: 1, publishedOn: '2026-11-22' });
    assert.deepEqual(checkRecipe(image, { file: 'harbour-poster.json' }), []);
    assert.deepEqual(Object.keys(image), ['slug', 'title', 'modality', 'tags', 'prompt', 'runs', 'source', 'review', 'published_on']);
    assert.deepEqual(image.source, {
      list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0', author: 'inventedone',
      author_url: 'https://x.com/inventedone', original_url: 'https://x.com/inventedone/status/1001', changes: ['placeholders resolved', 'translated to Chinese'],
    });
    assert.deepEqual(image.review, { approved_on: '2026-11-21', standard_version: 1 });
    assert.deepEqual(image.tags, ['poster']);
    assert.deepEqual(image.runs[0], { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024', n: 1 }, cost_cents: 6, generated_on: '2026-11-20', outputs: imageRun().outputs });

    const video = buildRecipe({ entry: videoEntry(), translated: translatedVideo(), run: videoRun(), decision: approved('kite-over-the-salt-flat'), standardVersion: 1, publishedOn: '2026-11-22' });
    assert.deepEqual(checkRecipe(video, { file: 'kite-over-the-salt-flat.json' }), []);
    assert.deepEqual(video.tags, []);
    assert.equal(video.runs[0].params.seconds, 5);
  });

  test('writes only what the operator approved', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': rejected('kite-over-the-salt-flat') });
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.deepEqual(result.refused, []);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), true);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
    const folder = await readRecipeFolder(b.contentDir);
    assert.deepEqual(folder.problems, []);
    assert.deepEqual(folder.recipes.map((recipe) => recipe.slug), ['harbour-poster']);
    assert.equal(folder.recipes[0].data.published_on, '2026-11-22');
  });

  test('an entry with no decision is not published', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
  });

  test('without --apply it writes nothing', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    const result = await publish({ ...b, apply: false });
    assert.deepEqual(result.written, ['harbour-poster', 'kite-over-the-salt-flat']);
    assert.equal(existsSync(b.contentDir), false);
    assert.equal(existsSync(b.manifestFile), false);
  });

  test('refuses to write a recipe the schema rejects, naming the rule', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    b.entries[0].author_url = 'http://x.com/inventedone'; // not https
    b.runs['x-2002'].params = { size: '1280x720' }; // a video run that does not state its seconds
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, []);
    assert.deepEqual(result.refused, [
      { slug: 'harbour-poster', why: ['[https-url] source.author_url: must be an https URL'] },
      { slug: 'kite-over-the-salt-flat', why: ['[video-seconds] runs[0].params.seconds: a video run states seconds, a whole number of at least 1'] },
    ]);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), false);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
  });

  test('refuses a recipe whose slug is already taken, and leaves the existing file alone', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const before = await readFile(path.join(b.contentDir, 'harbour-poster.json'), 'utf8');

    // Another post arrives under the same slug.
    const other = imageEntry({ id: 'x-7777', original_url: 'https://x.com/someoneelse/status/7777', author: 'someoneelse', author_url: 'https://x.com/someoneelse' });
    const result = await publish({ ...b, entries: [other], translated: { 'x-7777': translatedImage() }, runs: { 'x-7777': imageRun() }, decisions: { standard_version: 1, decisions: { 'x-7777': approved('harbour-poster') } }, apply: true });
    assert.deepEqual(result.written, []);
    assert.deepEqual(result.refused, [{ slug: 'harbour-poster', why: ['the slug is already taken by harbour-poster.json'] }]);
    assert.equal(await readFile(path.join(b.contentDir, 'harbour-poster.json'), 'utf8'), before);
  });

  test('refuses two recipes in one batch that share a slug', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('harbour-poster') });
    b.translated['x-2002'].slug = 'harbour-poster';
    b.runs['x-2002'].outputs[0].file = 'harbour-poster/seedance-2.0-0.mp4';
    const dry = await publish({ ...b, apply: false });
    assert.deepEqual(dry.written, ['harbour-poster']);
    assert.deepEqual(dry.refused.map((item) => item.why), [['the slug is already taken by another recipe in this batch']]);

    // For real, the first is on disk by the time the second is checked.
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.deepEqual(result.refused.map((item) => item.why), [['the slug is already taken by harbour-poster.json']]);
  });

  test('publishing again does not rewrite or refuse what is already published', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const again = await publish({ ...b, apply: true, now: () => new Date('2026-12-01T00:00:00Z') });
    assert.deepEqual({ written: again.written, already: again.already, refused: again.refused }, { written: [], already: ['harbour-poster'], refused: [] });
    assert.equal((await readJson(path.join(b.contentDir, 'harbour-poster.json'))).published_on, '2026-11-22');
  });

  test('an approved entry with no finished run is refused, not skipped quietly', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    b.runs['x-1001'].status = 'downloading';
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.refused, [{ slug: 'harbour-poster', why: ['it is approved, but it has no finished run to publish'] }]);
  });

  test('lists the media to upload, and commits none of it', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    await publish({ ...b, apply: true });
    assert.deepEqual(await readJson(b.manifestFile), {
      upload: ['harbour-poster/gpt-image-2-0-480.webp', 'harbour-poster/gpt-image-2-0.webp', 'kite-over-the-salt-flat/seedance-2.0-0.mp4'],
      delete: [],
    });
    const { readdir } = await import('node:fs/promises');
    assert.deepEqual((await readdir(b.contentDir)).sort(), ['harbour-poster.json', 'kite-over-the-salt-flat.json']);
  });
});

describe('remove', () => {
  test('deletes the recipe, puts its post on the removal list, and lists its media for deletion', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    await publish({ ...b, apply: true });
    const result = await remove('harbour-poster', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, reason: 'the author asked', apply: true, now: b.now });

    assert.equal(result.original_url, 'https://x.com/inventedone/status/1001');
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), false);
    assert.deepEqual(await readJson(b.removalFile), { removed: [{ original_url: 'https://x.com/inventedone/status/1001', slug: 'harbour-poster', removed_on: '2026-11-22', reason: 'the author asked' }] });
    assert.deepEqual(await readJson(b.manifestFile), {
      upload: ['kite-over-the-salt-flat/seedance-2.0-0.mp4'],
      delete: ['harbour-poster/gpt-image-2-0-480.webp', 'harbour-poster/gpt-image-2-0.webp'],
    });
    assert.deepEqual((await readRecipeFolder(b.contentDir)).problems, []);
  });

  test('without --apply it changes nothing', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const result = await remove('harbour-poster', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile });
    assert.equal(result.media.length, 2);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), true);
    assert.equal(existsSync(b.removalFile), false);
  });

  test('refuses anything that is not a slug, and a recipe that is not there', async (t) => {
    const b = await batch(t, {});
    const options = { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, apply: true };
    await assert.rejects(remove('../../package', options), /is not a slug/);
    await assert.rejects(remove('no-such-recipe', options), /there is no recipe no-such-recipe\.json/);
  });

  test('a recipe that is not valid JSON is left alone', async (t) => {
    const b = await batch(t, {});
    const { mkdir } = await import('node:fs/promises');
    await mkdir(b.contentDir, { recursive: true });
    await writeFile(path.join(b.contentDir, 'broken.json'), '{ not json');
    await assert.rejects(remove('broken', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, apply: true }));
    assert.equal(existsSync(path.join(b.contentDir, 'broken.json')), true);
  });
});
