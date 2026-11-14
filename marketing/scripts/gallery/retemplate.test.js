/**
 * retemplate: a published recipe gains its template only when the template,
 * filled with its defaults, is the published prompt exactly; otherwise it is
 * left as it is, with the reason. No paid call is made.
 */
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { checkRecipe } from '../../src/lib/gallery-schema.js';
import { defaultsIn, renderTemplate } from '../../src/lib/gallery-template.js';
import { imageRecipe } from '../gallery-schema/example-recipes.js';
import { main } from './cli.js';
import { templateFor, templateFromPublished } from './stages/retemplate.js';
import { API_BASE, CC0_TEXT, IMAGE_COMMIT, configDir, fakeWorld, noSleep, tempDir } from './testing.js';

const post = (n) => `https://x.com/invented/status/${n}`;
const listCase = (n, title, prompt) =>
  `### Case ${n}: [${title}](${post(n)}) (by [@invented](https://x.com/invented))\n\n**Prompt:**\n\n\`\`\`\n${prompt}\n\`\`\`\n`;

/** An image list in the shape of the real one, with the kinds of blank a recipe can have come from. */
const LIST = {
  LICENSE: CC0_TEXT,
  'cases/poster.md': [
    '# Poster Cases\n',
    listCase(1, 'Harbour Card', 'A card of a small harbour, {argument name="ratio" default="3:4"} ratio, for the brand {argument name="brand" default="PURE CRUNCH"}.'),
    listCase(2, 'Harbour Poster', 'A flat poster of a small harbour, {argument name="boat colour" default="red"} boats.'),
    listCase(3, 'Lighthouse Print', 'A linocut print of a lighthouse on a cliff, two colours.'),
    listCase(4, 'Three Boats', 'A print of {argument name="count" default="3"} boats in a {argument name="ratio" default="3:4"} frame.'),
    listCase(5, 'Friends', 'One with {argument name="hair" default="dark brown"} hair, one with {argument name="hair" default="black"} hair, size {argument name="Size!" default="A3"}.'),
    listCase(6, 'Mug', '{"subject":"a mug","colour":"{argument name=\\"colour\\" default=\\"#00AA88\\"}"}'),
  ].join('\n'),
};

/** A recipe as round 8's import published it: the blanks filled, and listed as they were. */
function published(slug, n, { en, zh, variables, changes = ['placeholders resolved', 'translated to Chinese'] }) {
  const recipe = imageRecipe();
  recipe.slug = slug;
  recipe.title = { en: slug };
  recipe.prompt = { en, zh, zh_origin: 'machine', ...(variables ? { variables } : {}) };
  recipe.runs[0].outputs[0].file = `${slug}/gpt-image-2-0.webp`;
  recipe.runs[0].outputs[0].thumb = `${slug}/gpt-image-2-0-480.webp`;
  recipe.source = { ...recipe.source, list: 'invented/image-prompts', list_commit: IMAGE_COMMIT, original_url: post(n), changes };
  return recipe;
}

const RECIPES = {
  'harbour-card': published('harbour-card', 1, {
    en: 'A card of a small harbour, 3:4 ratio, for the brand PURE CRUNCH.',
    zh: '一张小港口的卡片，比例3:4，品牌为PURE CRUNCH。',
    variables: [{ name: 'ratio', default: '3:4' }, { name: 'brand', default: 'PURE CRUNCH' }],
  }),
  'harbour-poster': published('harbour-poster', 2, {
    en: 'A flat poster of a small harbour, red boats.',
    zh: '一张小港口的扁平海报，红色的船。',
    variables: [{ name: 'boat colour', default: 'red' }],
  }),
  'lighthouse-print': published('lighthouse-print', 3, {
    en: 'A linocut print of a lighthouse on a cliff, two colours.',
    zh: '悬崖上灯塔的麻胶版画，双色。',
    changes: ['translated to Chinese'],
  }),
  'three-boats': published('three-boats', 4, {
    en: 'A print of 3 boats in a 3:4 frame.',
    zh: '一幅画着3只船的版画，3:4画幅，共3种颜色。',
    variables: [{ name: 'count', default: '3' }, { name: 'ratio', default: '3:4' }],
  }),
  friends: published('friends', 5, {
    en: 'One with dark brown hair, one with black hair, size A3.',
    zh: '一位dark brown头发，一位black头发，尺寸A3。',
    variables: [{ name: 'hair', default: 'dark brown' }, { name: 'hair', default: 'black' }, { name: 'Size!', default: 'A3' }],
  }),
  mug: published('mug', 6, {
    en: '{"subject":"a mug","colour":"#00AA88"}',
    zh: '{"subject":"一个马克杯","colour":"#00AA88"}',
    variables: [{ name: 'colour', default: '#00AA88' }],
  }),
};

async function workspace(t, recipes = RECIPES, { lists = { 'invented/image-prompts': LIST }, config } = {}) {
  const root = await tempDir(t);
  const dirs = { work: path.join(root, 'work'), content: path.join(root, 'content'), config: await configDir(t, config) };
  await mkdir(dirs.content, { recursive: true });
  for (const [slug, recipe] of Object.entries(recipes)) await writeFile(path.join(dirs.content, `${slug}.json`), `${JSON.stringify(recipe, null, 2)}\n`);
  const world = fakeWorld({ lists });
  const cli = async (...args) => {
    const lines = [];
    // No key at all: retemplate must not need one.
    const code = await main([...args, '--work-dir', dirs.work, '--config-dir', dirs.config, '--content-dir', dirs.content], {
      env: { GODANCE_API_BASE: API_BASE }, fetch: world.fetch, out: (line) => lines.push(line), sleep: noSleep,
    });
    return { code, text: lines.join('\n') };
  };
  const read = async (slug) => JSON.parse(await readFile(path.join(dirs.content, `${slug}.json`), 'utf8'));
  return { dirs, world, cli, read };
}

describe('retemplate', () => {
  test('--all as a dry run reports each recipe and why, and writes nothing', async (t) => {
    const { cli, read, world } = await workspace(t);
    const { code, text } = await cli('retemplate', '--all');
    assert.equal(code, 0);
    assert.match(text, /^gallery retemplate: dry run\. Nothing is written and no paid call is made\./);
    for (const line of [
      '  + harbour-card: would add a template, 2 variables (ratio, brand)',
      '  + mug: would add a template, 1 variable (colour)',
      '  + friends: would add a template, 3 variables (hair, hair 2, Size)',
      '  = harbour-poster: left as it is: the Chinese prompt does not contain the default of {{boat colour}} as written ("red"): it was translated, and a Chinese template cannot be made without translating again',
      '  = lighthouse-print: left as it is: its source prompt has no blanks',
      '  = three-boats: left as it is: the Chinese prompt has "3" 2 times, where the template uses {{count}} 1 time: which of them is the blank cannot be told',
      '6 recipes: 3 would gain a template, 3 left as they are',
    ]) {
      assert.ok(text.includes(line), `missing: ${line}\n${text}`);
    }
    for (const [slug, recipe] of Object.entries(RECIPES)) assert.deepEqual(await read(slug), recipe, `${slug} was written by a dry run`);
    // The lists were read, and nothing else: no paid request, and no key anywhere.
    assert.deepEqual(world.requests.filter((request) => request.method !== 'GET'), []);
    assert.equal(world.requests.every((request) => request.authorization === null), true);
    assert.ok(world.requests.every((request) => /^https:\/\/(api\.github\.com|raw\.githubusercontent\.com)\//.test(request.url)));
  });

  test('--apply adds the template, and changes nothing else in the recipe', async (t) => {
    const { cli, read } = await workspace(t);
    const { code, text } = await cli('retemplate', '--all', '--apply');
    assert.equal(code, 0);
    assert.match(text, /^gallery retemplate\. No paid call is made/);
    assert.match(text, /6 recipes: 3 gained a template, 3 left as they are/);
    assert.match(text, /the gallery folder is valid: 6 recipes/);

    const card = await read('harbour-card');
    assert.deepEqual(card.prompt, {
      en: RECIPES['harbour-card'].prompt.en,
      zh: RECIPES['harbour-card'].prompt.zh,
      zh_origin: 'machine',
      template: {
        en: 'A card of a small harbour, {{ratio}} ratio, for the brand {{brand}}.',
        zh: '一张小港口的卡片，比例{{ratio}}，品牌为{{brand}}。',
      },
      variables: [{ name: 'ratio', default: '3:4' }, { name: 'brand', default: 'PURE CRUNCH' }],
    });
    assert.deepEqual(card.source.changes, ['placeholders rewritten as {{name}}', 'translated to Chinese']);
    // Outputs, costs, the review and everything else are untouched.
    const { prompt: _p, source: { changes: _c, ...sourceAfter }, ...restAfter } = card;
    const { prompt: _q, source: { changes: _d, ...sourceBefore }, ...restBefore } = RECIPES['harbour-card'];
    assert.deepEqual({ ...restAfter, source: sourceAfter }, { ...restBefore, source: sourceBefore });
    assert.deepEqual(checkRecipe(card, { file: 'harbour-card.json' }), []);

    // The ones it could not do are exactly as they were.
    for (const slug of ['harbour-poster', 'lighthouse-print', 'three-boats']) assert.deepEqual(await read(slug), RECIPES[slug], slug);
  });

  test('names are fitted to the rule and a name with two defaults is split, with each change listed', async (t) => {
    const { cli, read } = await workspace(t);
    await cli('retemplate', 'friends', '--apply');
    const recipe = await read('friends');
    assert.equal(recipe.prompt.template.en, 'One with {{hair}} hair, one with {{hair 2}} hair, size {{Size}}.');
    assert.equal(recipe.prompt.template.zh, '一位{{hair}}头发，一位{{hair 2}}头发，尺寸{{Size}}。');
    assert.deepEqual(recipe.prompt.variables, [{ name: 'hair', default: 'dark brown' }, { name: 'hair 2', default: 'black' }, { name: 'Size', default: 'A3' }]);
    assert.deepEqual(recipe.source.changes, [
      'placeholders rewritten as {{name}}',
      'placeholder "hair" has more than one default; the one that defaults to "black" is "hair 2"',
      'placeholder "Size!" renamed "Size"',
      'translated to Chinese',
    ]);
  });

  test('a JSON-shaped prompt keeps a template that ends in "}"', async (t) => {
    const { cli, read } = await workspace(t);
    await cli('retemplate', 'mug', '--apply');
    const { prompt } = await read('mug');
    assert.deepEqual(prompt.template, { en: '{"subject":"a mug","colour":"{{colour}}"}', zh: '{"subject":"一个马克杯","colour":"{{colour}}"}' });
    assert.equal(renderTemplate(prompt.template.zh, defaultsIn(prompt.variables, 'zh')), prompt.zh);
  });

  test('one slug does that recipe only, and a second run finds it done', async (t) => {
    const { cli, read } = await workspace(t);
    const first = await cli('retemplate', 'harbour-card', '--apply');
    assert.match(first.text, /1 recipe: 1 gained a template, 0 left as they are/);
    assert.equal((await read('mug')).prompt.template, undefined);
    const second = await cli('retemplate', 'harbour-card', '--apply');
    assert.match(second.text, /= harbour-card: left as it is: it already has a template/);
    assert.match(second.text, /1 recipe: 0 gained a template, 1 left as it is/);
  });

  test('a prompt edited since it was published is left alone, and the report says where it differs', async (t) => {
    const edited = structuredClone(RECIPES['harbour-card']);
    edited.prompt.en = 'A card of a small harbour, 3:4 ratio, for the brand Pure Crunch.';
    const { cli, read } = await workspace(t, { 'harbour-card': edited });
    const { text } = await cli('retemplate', '--all', '--apply');
    assert.match(text, /= harbour-card: left as it is: the English prompt is not the source prompt with its blanks filled; they differ at character 53: the template gives "URE CRUNCH\." where the recipe has "ure Crunch\."/);
    assert.deepEqual(await read('harbour-card'), edited);
  });

  test('a recipe whose source cannot be read again is left alone, with the reason', async (t) => {
    const elsewhere = structuredClone(RECIPES['harbour-card']);
    elsewhere.slug = 'from-elsewhere';
    elsewhere.source.list = 'someone/other-list';
    const gone = structuredClone(RECIPES['harbour-card']);
    gone.slug = 'entry-gone';
    gone.source.original_url = post(999);
    const unpinned = structuredClone(RECIPES['harbour-card']);
    unpinned.slug = 'unpinned';
    unpinned.source.list_commit = 'main';
    // A configured list that cannot be reached: the test world serves only the image list.
    const unreadable = structuredClone(RECIPES['harbour-card']);
    unreadable.slug = 'unreadable';
    unreadable.source.list = 'invented/video-prompts';
    unreadable.source.list_commit = 'c'.repeat(40);

    const { cli } = await workspace(t, { 'from-elsewhere': elsewhere, 'entry-gone': gone, unpinned, unreadable });
    const { code, text } = await cli('retemplate', '--all', '--apply');
    assert.equal(code, 0);
    assert.match(text, /= from-elsewhere: left as it is: its list, someone\/other-list, is not one of the configured sources/);
    assert.match(text, /= entry-gone: left as it is: the entry it came from is not in invented\/image-prompts at aaaaaaaaaaaa/);
    assert.match(text, /= unpinned: left as it is: it does not pin its list to a full commit/);
    assert.match(text, /= unreadable: left as it is: its list could not be read at cccccccccccc: .*404/);
    assert.match(text, /4 recipes: 0 gained a template, 4 left as they are/);
  });

  test('a slug with no recipe says so; no slug, or a slug with --all, is misuse', async (t) => {
    const { cli } = await workspace(t);
    assert.match((await cli('retemplate', 'no-such-recipe')).text, /= no-such-recipe: left as it is: there is no recipe no-such-recipe\.json/);
    assert.equal((await cli('retemplate')).code, 2);
    assert.equal((await cli('retemplate', 'mug', '--all')).code, 2);
  });

  test('an empty gallery folder has nothing to do', async (t) => {
    const { cli } = await workspace(t, {});
    const { code, text } = await cli('retemplate', '--all');
    assert.equal(code, 0);
    assert.match(text, /there are no recipes in /);
  });
});

describe('templateFor', () => {
  const entry = (variants, lang = 'en') => ({ lang, variants });

  test('a list that supplies both languages gives each its own defaults', () => {
    const recipe = published('kite', 7, { en: 'A red kite over a salt flat.', zh: '盐滩上空一只红色风筝。', variables: [{ name: 'kite colour', default: 'red' }], changes: ['placeholders resolved'] });
    recipe.prompt.zh_origin = 'list';
    const made = templateFor(recipe, entry({
      en: 'A {argument name="kite colour" default="red"} kite over a salt flat.',
      zh: '盐滩上空一只{argument name="kite colour" default="红色"}风筝。',
    }));
    assert.deepEqual(made.recipe.prompt.template, { en: 'A {{kite colour}} kite over a salt flat.', zh: '盐滩上空一只{{kite colour}}风筝。' });
    assert.deepEqual(made.recipe.prompt.variables, [{ name: 'kite colour', default: 'red', zh_default: '红色' }]);
    assert.deepEqual(made.recipe.source.changes, ['placeholders rewritten as {{name}}']);
    assert.deepEqual(checkRecipe(made.recipe), []);
  });

  test('a Chinese source: the Chinese template is the list’s, the English is made from the published text', () => {
    const recipe = published('label', 8, { en: 'A tea label in 3:4.', zh: '一张3:4的茶叶标签。', variables: [{ name: 'ratio', default: '3:4' }], changes: ['placeholders resolved', 'translated to English'] });
    recipe.prompt.zh_origin = 'list';
    const made = templateFor(recipe, entry({ zh: '一张{argument name="ratio" default="3:4"}的茶叶标签。' }, 'zh'));
    assert.deepEqual(made.recipe.prompt.template, { en: 'A tea label in {{ratio}}.', zh: '一张{{ratio}}的茶叶标签。' });
    assert.deepEqual(made.recipe.source.changes, ['placeholders rewritten as {{name}}', 'translated to English']);
  });

  test('a recipe that never said its placeholders were resolved gets the change added', () => {
    const recipe = published('card', 1, { en: 'In 3:4.', zh: '比例3:4。', changes: [] });
    assert.deepEqual(templateFor(recipe, entry({ en: 'In {argument name="ratio" default="3:4"}.' })).recipe.source.changes, ['placeholders rewritten as {{name}}']);
  });

  test('what it refuses, and why', () => {
    const recipe = published('card', 1, { en: 'In 3:4.', zh: '比例3:4。' });
    const why = (variants, lang) => templateFor(recipe, entry(variants, lang)).why;
    assert.equal(why({ en: 'In 3:4.' }), 'its source prompt has no blanks');
    assert.match(why({ en: 'In {argument name="ratio" default="3:4"}. Also {{.' }), /^no template can be made from its source prompt: the prompt has a "\{\{" of its own/);
    assert.match(why({ en: 'In {argument name="ratio" default="4:3"}.' }), /^the English prompt is not the source prompt with its blanks filled; they differ at character 3/);
    assert.match(why({ en: 'In {argument name="ratio" default="3:4"}.', zh: '比例{argument name="比例" default="3:4"}，{argument name="other" default="x"}。' }), /do not have the same blanks/);
    assert.equal(templateFor({ ...recipe, prompt: { ...recipe.prompt, template: { en: 'x', zh: 'y' } } }, entry({ en: 'x' })).why, 'it already has a template');
  });
});

describe('templateFromPublished', () => {
  const variables = [{ name: 'a', default: '3:4' }, { name: 'b', default: '3' }];

  test('puts each default back as its marker, the longer default first', () => {
    assert.deepEqual(templateFromPublished('比例3:4，共3只。', '{{a}} and {{b}}', variables, 'zh'), { template: '比例{{a}}，共{{b}}只。' });
  });

  test('a marker used twice needs its default twice', () => {
    assert.deepEqual(templateFromPublished('3只，再3只，3:4。', '{{b}} {{b}} {{a}}', variables, 'zh'), { template: '{{b}}只，再{{b}}只，{{a}}。' });
    assert.match(templateFromPublished('3只，3:4。', '{{b}} {{b}} {{a}}', variables, 'zh').why, /has "3" 1 time, where the template uses \{\{b\}\} 2 times/);
  });

  test('a default that was translated, or is empty, cannot be found', () => {
    assert.match(templateFromPublished('红色的船。', '{{c}}', [{ name: 'c', default: 'red' }], 'zh').why, /does not contain the default of \{\{c\}\} as written \("red"\)/);
    assert.match(templateFromPublished('船。', '{{c}}', [{ name: 'c', default: '' }], 'zh').why, /the default of \{\{c\}\} is empty/);
  });
});
