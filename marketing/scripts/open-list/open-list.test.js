/**
 * The open recipe list (round 13 brief 02): `npm run open-list -- --out <dir>`
 * run against the recipes in ./fixtures/recipes, which it reads through the
 * same reader as `npm run validate`.
 *
 * The fixtures are invented: titles, prompts, authors and links are made up,
 * and the media they name exists nowhere. The list only links to media, so
 * none is needed. glass-lighthouse-at-night is on ./fixtures/removal-list.json.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { LICENCES } from '../../src/lib/gallery-schema.js';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { LICENCE_URLS } from './credit.js';

const MARKETING = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = fileURLToPath(new URL('./fixtures/recipes/', import.meta.url));
const FIXTURE_REMOVALS = fileURLToPath(new URL('./fixtures/removal-list.json', import.meta.url));
const REMOVED = 'glass-lighthouse-at-night';

const MEDIA = 'https://media.example.test/gallery/';
const CONTACT = 'removals@example.test';
const OUTPUT_LICENCE = 'Fixture statement: outputs may be reused freely.';
const FILES = ['CONTRIBUTING.md', 'LICENSE.md', 'README.md', 'README_zh.md', 'data/recipes.json'];

/** The gallery switched on against the fixtures, and nothing else from this machine's environment. */
const ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: MEDIA,
  GALLERY_REMOVAL_CONTACT: CONTACT,
  GALLERY_OUTPUT_LICENCE: OUTPUT_LICENCE,
  GALLERY_RUN_THIS: 'false',
};

/** Every fixture recipe, and the ones the list should hold: all but the removed one, newest first. */
const { recipes: fixtureFiles } = await readRecipeFolder(FIXTURES);
const fixtures = fixtureFiles.map((file) => file.data);
const listed = fixtures
  .filter((recipe) => recipe.slug !== REMOVED)
  .sort((a, b) => (a.published_on === b.published_on ? (a.slug < b.slug ? -1 : 1) : a.published_on < b.published_on ? 1 : -1));
const bySlug = Object.fromEntries(fixtures.map((recipe) => [recipe.slug, recipe]));

/** A folder for one test, inside the project (git-ignored) rather than the system's temp folder. */
async function scratch(t) {
  const root = path.join(MARKETING, '.astro');
  await mkdir(root, { recursive: true });
  const dir = await mkdtemp(path.join(root, 'test-open-list-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

/**
 * Run the generator with this environment and no other, into a folder that
 * does not exist yet, so a refusal can be seen to write nothing.
 */
async function generate(t, { env = ON, removalList = FIXTURE_REMOVALS, args = [] } = {}) {
  const out = path.join(await scratch(t), 'list');
  const { main } = await import('./cli.js');
  const lines = [];
  const code = await main(['--out', out, ...(removalList ? ['--removal-list', removalList] : []), ...args], {
    env,
    log: (line) => lines.push(line),
  });
  return { code, out, log: lines.join('\n'), read: (file) => readFile(path.join(out, file), 'utf8') };
}

/** Every file under a folder, as forward-slash paths relative to it, sorted. */
async function filesIn(dir, prefix = '') {
  const found = [];
  for (const entry of await readdir(path.join(dir, prefix), { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) found.push(...(await filesIn(dir, relative)));
    else found.push(relative);
  }
  return found.sort();
}

/** Markdown's backslash escapes undone: the text as a reader sees it. */
const unescape = (text) => text.replace(/\\([!-\/:-@[-`{-~])/g, '$1');

/**
 * The links in a piece of a README, written in Markdown or in HTML, as
 * { text, href }, with the text as a reader sees it and without code marks.
 */
function linksIn(markdown) {
  const links = [];
  for (const [, text, href] of markdown.matchAll(/\[((?:\\.|[^\]\\])*)\]\(((?:\\.|[^\s()\\])+)\)/g)) {
    links.push({ text: unescape(text).replace(/`/g, ''), href: unescape(href) });
  }
  for (const [, href, text] of markdown.matchAll(/<a\b[^>]*\shref="([^"]*)"[^>]*>([\s\S]*?)<\/a>/g)) {
    links.push({ text: text.replace(/<[^>]+>/g, '').replace(/`/g, ''), href: href.replace(/&amp;/g, '&') });
  }
  return links;
}

/** One entry of a README: from its anchor to the next entry's anchor, or to the next section. */
function entryOf(readme, slug) {
  const anchor = `<a id="${slug}"></a>`;
  const start = readme.indexOf(anchor);
  assert.notEqual(start, -1, `the README has an entry for ${slug}, starting ${anchor}`);
  const rest = readme.slice(start + anchor.length);
  const end = rest.search(/<a id="[a-z0-9-]+"><\/a>|^## /m);
  return end === -1 ? readme.slice(start) : readme.slice(start, start + anchor.length + end);
}

/** A section of a README: from its "## " heading to the next one. */
function sectionOf(readme, heading) {
  const start = readme.indexOf(`\n## ${heading}\n`);
  assert.notEqual(start, -1, `the README has a section "${heading}"`);
  const end = readme.indexOf('\n## ', start + 1);
  return end === -1 ? readme.slice(start) : readme.slice(start, end);
}

/** The fenced code blocks in Markdown, as their exact contents. */
function codeBlocks(markdown) {
  const blocks = [];
  const lines = markdown.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const open = /^(`{3,})[^`]*$/.exec(lines[i]);
    if (!open) continue;
    const close = lines.findIndex((line, j) => j > i && line === open[1]);
    assert.notEqual(close, -1, `a code block opened with ${open[1]} is closed`);
    blocks.push(lines.slice(i + 1, close).join('\n'));
    i = close;
  }
  return blocks;
}

/** The images in Markdown and HTML, by URL. */
const imagesIn = (markdown) => [
  ...[...markdown.matchAll(/!\[(?:\\.|[^\]\\])*\]\(((?:\\.|[^\s()\\])+)\)/g)].map((match) => unescape(match[1])),
  ...[...markdown.matchAll(/<img\b[^>]*\ssrc="([^"]*)"/g)].map((match) => match[1].replace(/&amp;/g, '&')),
];

/** A media file's URL as the gallery writes it: each part of the path encoded. */
const mediaUrl = (file) => MEDIA + file.split('/').map(encodeURIComponent).join('/');

const cost = (cents) => `$${(cents / 100).toFixed(2)}`;
const campaign = (slug) => `utm_source=github&utm_medium=open-list&utm_campaign=${slug}`;

// ---------------------------------------------------------------------------

describe('README sections', () => {
  test('the English README: introduction, contents by type and by tag, one entry per recipe, statistics, licence and removal', async (t) => {
    const { code, log, read } = await generate(t);
    assert.equal(code, 0, log);
    const readme = await read('README.md');

    assert.match(readme, /^<!-- Generated from the Godance gallery[^\n]*-->\n\n# Godance open recipe list\n/);
    assert.ok(linksIn(readme).some((link) => link.href === 'README_zh.md'), 'links to the Chinese README');
    for (const claim of ['Reproducible.', 'Priced.', 'Multiple models.']) assert.ok(readme.includes(`**${claim}**`), claim);

    const headings = [...readme.matchAll(/^## (.+)$/gm)].map((match) => match[1]);
    assert.deepEqual(headings, ['Contents', 'Image', 'Video', 'Statistics', 'Licence', 'Removal']);

    const contents = sectionOf(readme, 'Contents');
    const contentLinks = linksIn(contents);
    const images = listed.filter((recipe) => recipe.modality === 'IMAGE');
    const videos = listed.filter((recipe) => recipe.modality === 'VIDEO');
    assert.ok(contents.includes(`[Image](#image) (${images.length})`), 'by type: images, with their count');
    assert.ok(contents.includes(`[Video](#video) (${videos.length})`), 'by type: videos, with their count');
    for (const tag of new Set(listed.flatMap((recipe) => recipe.tags))) {
      const line = contents.split('\n').find((each) => each.startsWith(`- \`${tag}\``));
      assert.ok(line, `by tag: ${tag}`);
      const tagged = listed.filter((recipe) => recipe.tags.includes(tag)).map((recipe) => `#${recipe.slug}`);
      assert.deepEqual(linksIn(line).map((link) => link.href), tagged, `by tag: every recipe tagged ${tag}, newest first`);
    }
    for (const anchor of ['#statistics', '#licence', '#removal']) {
      assert.ok(contentLinks.some((link) => link.href === anchor), anchor);
    }

    // One entry per listed recipe, each under its type, newest first.
    const anchors = [...readme.matchAll(/<a id="([a-z0-9-]+)"><\/a>/g)].map((match) => match[1]);
    assert.deepEqual(anchors, [...images, ...videos].map((recipe) => recipe.slug));
    for (const recipe of images) assert.ok(sectionOf(readme, 'Image').includes(`<a id="${recipe.slug}">`), recipe.slug);
    for (const recipe of videos) assert.ok(sectionOf(readme, 'Video').includes(`<a id="${recipe.slug}">`), recipe.slug);
    for (const recipe of listed) {
      assert.match(entryOf(readme, recipe.slug), /^<a id="[a-z0-9-]+"><\/a>.+\n/, `${recipe.slug}: a heading`);
      assert.ok(unescape(readme).includes(`### <a id="${recipe.slug}"></a>${recipe.title.en}\n`), `${recipe.slug}: its title`);
    }

    const statistics = unescape(sectionOf(readme, 'Statistics'));
    const runs = listed.reduce((sum, recipe) => sum + recipe.runs.length, 0);
    const spent = listed.reduce((sum, recipe) => sum + recipe.runs.reduce((cents, run) => cents + run.cost_cents, 0), 0);
    assert.ok(statistics.includes(`**Recipes:** ${listed.length}, of which ${images.length} image and ${videos.length} video`));
    assert.ok(statistics.includes(`**Runs:** ${runs}, which cost ${cost(spent)} in all`));
    for (const model of new Set(listed.flatMap((recipe) => recipe.runs.map((run) => run.model)))) {
      const count = listed.filter((recipe) => recipe.runs.some((run) => run.model === model)).length;
      assert.ok(statistics.includes(`\`${model}\` (${count})`), `models: ${model}`);
    }
    assert.ok(statistics.includes(`**Last updated:** ${listed[0].published_on}`));
    assert.ok(readme.includes(`last updated ${listed[0].published_on}`), 'and in the introduction');

    assert.ok(sectionOf(readme, 'Licence').includes(OUTPUT_LICENCE), 'the output licence statement');
    assert.ok(linksIn(sectionOf(readme, 'Removal')).some((link) => link.href === `mailto:${CONTACT}`), 'the removal contact');
  });

  test('the Chinese README has the same entries, with Chinese titles and prompts where the recipe has them', async (t) => {
    const { code, log, read } = await generate(t);
    assert.equal(code, 0, log);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');

    assert.match(chinese, /\n# Godance 开放配方清单\n/);
    assert.ok(linksIn(chinese).some((link) => link.href === 'README.md'), 'links to the English README');
    const headings = [...chinese.matchAll(/^## (.+)$/gm)].map((match) => match[1]);
    assert.deepEqual(headings, ['目录', '图片', '视频', '统计', '许可', '移除']);
    assert.ok(linksIn(sectionOf(chinese, '目录')).some((link) => link.href === '#图片'));

    const anchors = (readme) => [...readme.matchAll(/<a id="([a-z0-9-]+)"><\/a>/g)].map((match) => match[1]);
    assert.deepEqual(anchors(chinese), anchors(english));
    for (const recipe of listed) {
      const entry = entryOf(chinese, recipe.slug);
      assert.ok(unescape(entry).includes(`</a>${recipe.title.zh ?? recipe.title.en}\n`), `${recipe.slug}: its Chinese title, or the English one`);
      assert.ok(codeBlocks(entry).includes(recipe.prompt.zh), `${recipe.slug}: the Chinese prompt`);
      if (recipe.prompt.zh_origin === 'machine') assert.ok(entry.includes('（机器翻译）'), `${recipe.slug}: says the Chinese is a machine translation`);
    }
    assert.ok(unescape(sectionOf(chinese, '统计')).includes(`**最后更新：** ${listed[0].published_on}`));
  });
});

describe('entries', () => {
  test("the picture is the output's thumbnail or the video's poster, by its URL on the media host, linked to the recipe page", async (t) => {
    const { read } = await generate(t);
    const readme = await read('README.md');
    for (const recipe of listed) {
      const entry = entryOf(readme, recipe.slug);
      const output = recipe.runs[0].outputs[0];
      const still = recipe.modality === 'VIDEO' ? output.poster : output.thumb;
      const pictures = imagesIn(entry).filter((src) => !recipe.inputs?.some((slot) => src === mediaUrl(slot.sample.file)));
      if (still) {
        assert.deepEqual(pictures, [mediaUrl(still)], recipe.slug);
        const recipeUrl = `https://godance.ai/gallery/${recipe.slug}?${campaign(recipe.slug)}`;
        assert.ok(entry.includes(`](${recipeUrl})`), `${recipe.slug}: the picture links to the recipe page`);
      } else {
        assert.deepEqual(pictures, [], `${recipe.slug}: a video without a poster has no picture`);
      }
      if (recipe.modality === 'VIDEO') assert.match(entry, new RegExp(`▶ Video, ${output.duration_seconds} s, ${output.width}×${output.height}`));
    }
  });

  test('the prompt is in a code block, exactly, however many backticks it holds', async (t) => {
    const { read } = await generate(t);
    const readme = await read('README.md');
    for (const recipe of listed) assert.ok(codeBlocks(entryOf(readme, recipe.slug)).includes(recipe.prompt.en), recipe.slug);
    const awkward = entryOf(readme, 'receipt-with-marks');
    assert.ok(bySlug['receipt-with-marks'].prompt.en.includes('```'), 'the fixture holds a run of three backticks');
    assert.match(awkward, /^````text$/m, 'so its fence has four');
  });

  test("a run that sent the other language's prompt brings that prompt into the README too", async (t) => {
    const { read } = await generate(t);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    for (const recipe of listed) {
      for (const run of recipe.runs) {
        assert.ok(codeBlocks(entryOf(english, recipe.slug)).includes(recipe.prompt[run.prompt_lang]), `${recipe.slug}: ${run.model} in README.md`);
        assert.ok(codeBlocks(entryOf(chinese, recipe.slug)).includes(recipe.prompt[run.prompt_lang]), `${recipe.slug}: ${run.model} in README_zh.md`);
      }
    }
    assert.match(entryOf(english, 'neon-stall-poster'), /<summary>The Chinese prompt, which the <code>gpt-image-2<\/code> run sent/);
  });

  test('a template comes with its variables; a recipe from before templates lists its placeholders', async (t) => {
    const { read } = await generate(t);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    const templated = bySlug['neon-stall-poster'];
    for (const [readme, lang] of [[english, 'en'], [chinese, 'zh']]) {
      const entry = entryOf(readme, templated.slug);
      assert.ok(codeBlocks(entry).includes(templated.prompt.template[lang]), `the ${lang} template`);
      for (const variable of templated.prompt.variables) {
        const row = entry.split('\n').find((line) => line.startsWith(`| \`${variable.name}\``));
        assert.ok(row, `${lang}: a row for ${variable.name}`);
        assert.ok(unescape(row).includes(variable.default), `${lang}: ${variable.name}'s default`);
        if (variable.zh_default) assert.ok(row.includes(variable.zh_default), `${lang}: ${variable.name}'s Chinese default`);
      }
      // A default with a pipe stays in its cell.
      assert.ok(entry.includes('| Open \\| Late |'), `${lang}: the pipe in a default is escaped`);
    }
    const older = bySlug['ink-drop-in-water'];
    assert.ok(!older.prompt.template && older.prompt.variables.length > 0);
    assert.ok(entryOf(english, older.slug).includes(`- \`${older.prompt.variables[0].name}\`: ${older.prompt.variables[0].default}`));
  });

  test('an input slot shows its role, hint and sample, and how the sample was made', async (t) => {
    const { read } = await generate(t);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    const recipe = bySlug['kite-on-a-windy-hill'];
    const slot = recipe.inputs[0];
    for (const [readme, lang] of [[english, 'en'], [chinese, 'zh']]) {
      const entry = entryOf(readme, recipe.slug);
      const row = entry.split('\n').find((line) => line.startsWith(`| \`${slot.slot}\``));
      assert.ok(row, `${lang}: a row for the slot`);
      assert.ok(row.includes(lang === 'zh' ? '首帧' : 'First frame'), `${lang}: its role`);
      assert.ok(unescape(row).includes(lang === 'zh' ? slot.hint.zh : slot.hint.en), `${lang}: its hint`);
      assert.deepEqual(imagesIn(row), [mediaUrl(slot.sample.file)], `${lang}: its sample, by URL, its path encoded`);
      const from = slot.sample.generated_from;
      assert.ok(codeBlocks(entry).includes(from.prompt), `${lang}: the prompt that made the sample`);
      assert.ok(entry.includes(`\`${from.model}\``) && unescape(entry).includes(cost(from.cost_cents)) && entry.includes(from.generated_on), `${lang}: its model, cost and date`);
    }
  });

  test("every run's model, parameters, cost and date are in the entry", async (t) => {
    const { read } = await generate(t);
    const readme = await read('README.md');
    for (const recipe of listed) {
      const rows = entryOf(readme, recipe.slug).split('\n').filter((line) => line.startsWith('| `'));
      for (const run of recipe.runs) {
        const row = rows.find((line) => line.startsWith(`| \`${run.model}\` |`));
        assert.ok(row, `${recipe.slug}: a row for ${run.model}`);
        for (const [key, value] of Object.entries(run.params)) {
          assert.ok(row.includes(`\`${key}=${typeof value === 'string' ? value : JSON.stringify(value)}\``), `${recipe.slug}: ${key}`);
        }
        assert.ok(unescape(row).includes(` ${cost(run.cost_cents)} `), `${recipe.slug}: the cost as recorded`);
        assert.ok(row.includes(run.generated_on), `${recipe.slug}: the date`);
        assert.ok(row.includes(run.prompt_lang === 'zh' ? 'Chinese' : 'English'), `${recipe.slug}: the prompt it sent`);
      }
    }
  });

  test('links carry the open-list campaign: View recipe, and Get an API key while Run this is off', async (t) => {
    const { read } = await generate(t);
    for (const file of ['README.md', 'README_zh.md']) {
      const readme = await read(file);
      for (const recipe of listed) {
        const hrefs = linksIn(entryOf(readme, recipe.slug)).map((link) => link.href);
        assert.ok(hrefs.includes(`https://godance.ai/gallery/${recipe.slug}?${campaign(recipe.slug)}`), `${file} ${recipe.slug}: View recipe`);
        assert.ok(hrefs.includes(`https://app.godance.ai/signup?${campaign(recipe.slug)}`), `${file} ${recipe.slug}: Get an API key`);
        assert.ok(!hrefs.some((href) => href.includes('/create/')), `${file} ${recipe.slug}: no Run this while the pages have none`);
      }
    }
  });

  test('with Run this on, as on the recipe pages, an entry opens the recipe in the app', async (t) => {
    const { read } = await generate(t, { env: { ...ON, GALLERY_RUN_THIS: 'true' } });
    const readme = await read('README.md');
    for (const recipe of listed) {
      const links = linksIn(entryOf(readme, recipe.slug));
      const kind = recipe.modality === 'VIDEO' ? 'video' : 'image';
      const runThis = `https://app.godance.ai/create/${kind}?example=${recipe.slug}&${campaign(recipe.slug)}`;
      assert.ok(links.some((link) => link.text === 'Run this' && link.href === runThis), recipe.slug);
      assert.ok(links.some((link) => link.text === 'View recipe'), recipe.slug);
      assert.ok(!links.some((link) => link.href.includes('/signup')), recipe.slug);
    }
    const data = JSON.parse(await read('data/recipes.json'));
    assert.ok(data.recipes.every((entry) => entry.links.run_this && !entry.links.get_api_key));
  });

  test('SITE_URL and PUBLIC_APP_URL move the links, as they move the site', async (t) => {
    const env = { ...ON, SITE_URL: 'https://staging.example.test/', PUBLIC_APP_URL: 'https://app.staging.example.test' };
    const { code, log, read } = await generate(t, { env });
    assert.equal(code, 0, log);
    const links = linksIn(entryOf(await read('README.md'), 'brass-compass-on-map')).map((link) => link.href);
    assert.ok(links.includes(`https://staging.example.test/gallery/brass-compass-on-map?${campaign('brass-compass-on-map')}`));
    assert.ok(links.includes(`https://app.staging.example.test/signup?${campaign('brass-compass-on-map')}`));
  });
});

describe('credit', () => {
  test('a CC BY entry carries author, link, licence and changes, in both READMEs and in the data', async (t) => {
    const { recipes, problems } = await readRecipeFolder(FIXTURES);
    assert.deepEqual(problems, [], 'the fixtures are valid recipes');
    const all = recipes.map((file) => file.data);
    const recipe = all.find((each) => each.source.licence === 'CC-BY-4.0' && each.source.changes.length > 0);
    assert.ok(recipe, 'the fixtures hold a CC BY recipe with changes');
    const others = all.filter((each) => each.source.author_url !== recipe.source.author_url);
    assert.ok(others.length > 0, 'and a recipe by someone else');

    const { source } = recipe;
    // Linked as the recipe page links them (src/pages/gallery/[slug].astro):
    // a list named "owner/repo" is a GitHub repository, read at its commit.
    const listUrl = `https://github.com/${source.list}/tree/${source.list_commit}`;
    const licenceUrl = 'https://creativecommons.org/licenses/by/4.0/';

    const { code, log, read } = await generate(t);
    assert.equal(code, 0, log);

    for (const file of ['README.md', 'README_zh.md']) {
      const entry = entryOf(await read(file), recipe.slug);
      const links = linksIn(entry);
      const linked = ({ text, href }) => links.some((link) => link.href === href && (text === undefined || link.text === text));

      assert.ok(linked({ text: source.author, href: source.author_url }), `${file}: the author, linked to their page`);
      assert.ok(linked({ href: source.original_url }), `${file}: a link to the original post`);
      assert.ok(linked({ text: source.list, href: listUrl }), `${file}: the source list, linked at its commit`);
      assert.ok(linked({ text: source.licence, href: licenceUrl }), `${file}: the licence, linked to its text`);
      for (const change of source.changes) {
        assert.ok(unescape(entry).includes(change), `${file}: the change "${change}", as recorded`);
      }
      // The credit is this entry's own.
      for (const other of others) {
        assert.ok(!linked({ href: other.source.author_url }), `${file}: nothing in the entry credits ${other.source.author}`);
      }
    }

    const data = JSON.parse(await read('data/recipes.json'));
    const entry = data.recipes.find((each) => each.slug === recipe.slug);
    assert.ok(entry, 'data/recipes.json has the entry');
    assert.equal(entry.credit.author, source.author);
    assert.equal(entry.credit.author_url, source.author_url);
    assert.equal(entry.credit.original_url, source.original_url);
    assert.equal(entry.credit.list, source.list);
    assert.equal(entry.credit.list_url, listUrl);
    assert.equal(entry.credit.licence, source.licence);
    assert.equal(entry.credit.licence_url, licenceUrl);
    assert.deepEqual(entry.credit.changes, source.changes);
  });

  test('every licence the format allows has the page it links to', () => {
    assert.deepEqual(Object.keys(LICENCE_URLS).sort(), [...LICENCES].sort());
    for (const href of Object.values(LICENCE_URLS)) assert.match(href, /^https:\/\//);
  });

  test('a list that is not a GitHub repository is named but not linked, and a commit that is not a hash is left out of the link', async (t) => {
    const { read } = await generate(t);
    const readme = await read('README.md');
    const unnamed = bySlug['ink-drop-in-water'].source;
    const ink = entryOf(readme, 'ink-drop-in-water');
    assert.ok(ink.includes(`from the list \`${unnamed.list}\`,`), 'named, as code');
    assert.ok(!linksIn(ink).some((link) => link.text === unnamed.list), 'not linked');
    const kite = linksIn(entryOf(readme, 'kite-on-a-windy-hill'));
    assert.ok(kite.some((link) => link.href === 'https://github.com/example-org/invented-video-prompts'), 'the repository, at no commit');
    const data = JSON.parse(await read('data/recipes.json'));
    assert.equal(data.recipes.find((entry) => entry.slug === 'ink-drop-in-water').credit.list_url, null);
  });

  test('awkward characters in a credit stay text, and its links keep their targets', async (t) => {
    const { read } = await generate(t);
    for (const file of ['README.md', 'README_zh.md']) {
      const source = bySlug['receipt-with-marks'].source;
      const links = linksIn(entryOf(await read(file), 'receipt-with-marks'));
      assert.ok(links.some((link) => link.text === source.author && link.href === source.author_url), `${file}: brackets in the name, parentheses in the link`);
      assert.ok(links.some((link) => link.href === source.original_url), `${file}: a query in the original post's link`);
    }
  });

  test('every entry says how the prompt was changed, or that it was not, and that Godance made the output', async (t) => {
    const { read } = await generate(t);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    for (const recipe of listed) {
      const en = unescape(entryOf(english, recipe.slug));
      const zh = unescape(entryOf(chinese, recipe.slug));
      if (recipe.source.changes.length === 0) {
        assert.ok(en.includes('The prompt is used as published, unchanged.'), recipe.slug);
        assert.ok(zh.includes('提示词按原样使用，未作改动。'), recipe.slug);
      }
      assert.ok(en.includes(`The ${recipe.modality === 'VIDEO' ? 'video' : 'image'} was generated by Godance from that prompt`), recipe.slug);
      if (recipe.inputs) assert.ok(en.includes('and so was the sample input: it comes from no list and no photo'), recipe.slug);
    }
  });
});

describe('data/recipes.json', () => {
  test('holds the listed recipes in the same order, with every run and the media by URL', async (t) => {
    const { read } = await generate(t);
    const data = JSON.parse(await read('data/recipes.json'));
    assert.deepEqual(data.recipes.map((entry) => entry.slug), listed.map((recipe) => recipe.slug));
    assert.equal(data.last_updated, listed[0].published_on);
    assert.equal(data.licences.outputs, OUTPUT_LICENCE);
    assert.equal(data.removal_contact, CONTACT);
    for (const entry of data.recipes) {
      const recipe = bySlug[entry.slug];
      assert.deepEqual(entry.title, recipe.title);
      assert.deepEqual(entry.tags, recipe.tags);
      assert.equal(entry.prompt.en, recipe.prompt.en);
      assert.equal(entry.prompt.zh, recipe.prompt.zh);
      assert.deepEqual(entry.prompt.template, recipe.prompt.template);
      assert.deepEqual(entry.prompt.variables, recipe.prompt.variables);
      assert.deepEqual(
        entry.runs.map((run) => [run.model, run.prompt_lang, run.params, run.cost_cents, run.generated_on]),
        recipe.runs.map((run) => [run.model, run.prompt_lang, run.params, run.cost_cents, run.generated_on]),
      );
      recipe.runs.forEach((run, i) => {
        assert.deepEqual(entry.runs[i].outputs.map((output) => output.url), run.outputs.map((output) => mediaUrl(output.file)));
      });
      assert.equal(entry.links.recipe, `https://godance.ai/gallery/${recipe.slug}?${campaign(recipe.slug)}`);
      assert.deepEqual(entry.inputs?.map((slot) => slot.sample.url), recipe.inputs?.map((slot) => mediaUrl(slot.sample.file)));
    }
  });
});

describe('LICENSE.md and CONTRIBUTING.md', () => {
  test("LICENSE.md: each prompt licence with its link, the output statement, and a marked gap for Godance's own text", async (t) => {
    const { read } = await generate(t);
    const licence = await read('LICENSE.md');
    for (const name of new Set(listed.map((recipe) => recipe.source.licence))) {
      const count = listed.filter((recipe) => recipe.source.licence === name).length;
      const row = licence.split('\n').find((line) => line.startsWith(`| [${name}](`));
      assert.ok(row, name);
      assert.ok(row.includes(`](${LICENCE_URLS[name]}) | ${count} |`), `${name}: its link and how many prompts carry it`);
    }
    assert.ok(licence.includes(OUTPUT_LICENCE), 'the output licence statement');
    assert.match(licence, /## Godance's own text\n\n> \*\*TO BE FILLED IN BY THE OPERATOR/);
  });

  test('without an output licence statement, LICENSE.md marks that gap as well, and the READMEs say nothing in its place', async (t) => {
    const { code, log, read } = await generate(t, { env: { ...ON, GALLERY_OUTPUT_LICENCE: '' } });
    assert.equal(code, 0, log);
    assert.match(await read('LICENSE.md'), /> \*\*TO BE FILLED IN BY THE OPERATOR: the terms for the generated images and videos\*\*/);
    assert.ok(!(await read('README.md')).includes(OUTPUT_LICENCE));
    assert.equal(JSON.parse(await read('data/recipes.json')).licences.outputs, null);
  });

  test('CONTRIBUTING.md: the removal contact, and that pull requests editing entries are not merged', async (t) => {
    const { read } = await generate(t);
    const contributing = await read('CONTRIBUTING.md');
    assert.ok(linksIn(contributing).filter((link) => link.href === `mailto:${CONTACT}`).length >= 2, 'in English and in Chinese');
    assert.match(contributing, /pull requests that edit entries are not merged/);
    assert.match(contributing, /拉取请求不会被合并/);
  });

  test('a removal contact that is a URL is linked as it is', async (t) => {
    const form = 'https://example.test/removals';
    const { read } = await generate(t, { env: { ...ON, GALLERY_REMOVAL_CONTACT: form } });
    for (const file of ['README.md', 'README_zh.md', 'CONTRIBUTING.md']) {
      assert.ok(linksIn(await read(file)).some((link) => link.href === form), file);
    }
  });
});

describe('determinism', () => {
  test('the same content gives byte-identical output, wherever it is read from', async (t) => {
    const first = await generate(t);
    const second = await generate(t);
    // The same recipes in another folder, written in the opposite order.
    const copy = path.join(await scratch(t), 'recipes');
    await mkdir(copy);
    for (const name of (await readdir(FIXTURES)).reverse()) await copyFile(path.join(FIXTURES, name), path.join(copy, name));
    const moved = await generate(t, { env: { ...ON, GALLERY_CONTENT_DIR: copy } });

    for (const run of [first, second, moved]) assert.equal(run.code, 0, run.log);
    assert.deepEqual(await filesIn(first.out), FILES);
    for (const file of FILES) {
      const expected = await first.read(file);
      assert.equal(await second.read(file), expected, `${file}: a second run`);
      assert.equal(await moved.read(file), expected, `${file}: another folder`);
    }
  });

  test('"last updated" is the newest published_on, and every date in the list is a recipe\'s own', async (t) => {
    const { read } = await generate(t);
    const own = new Set(
      listed.flatMap((recipe) => [
        recipe.published_on,
        ...recipe.runs.map((run) => run.generated_on),
        ...(recipe.inputs ?? []).map((slot) => slot.sample.generated_from.generated_on),
      ]),
    );
    for (const file of FILES) {
      for (const [date] of (await read(file)).matchAll(/\b\d{4}-\d{2}-\d{2}\b/g)) assert.ok(own.has(date), `${file}: ${date}`);
    }
    assert.ok((await read('README.md')).includes(`last updated ${listed[0].published_on}`));
  });
});

describe('removal list', () => {
  /** Every value of a recipe that would show it is in a file. */
  const tracesOf = (recipe) => [
    recipe.slug,
    recipe.title.en,
    recipe.title.zh,
    recipe.prompt.en,
    recipe.prompt.zh,
    recipe.source.author,
    recipe.source.author_url,
    recipe.source.original_url,
    ...recipe.runs.flatMap((run) => run.outputs.map((output) => encodeURIComponent(output.file.split('/')[0]))),
  ].filter(Boolean);

  test('a recipe on the removal list is never written', async (t) => {
    const { code, log, read } = await generate(t);
    assert.equal(code, 0, log);
    assert.match(log, new RegExp(`left out, on the removal list: ${REMOVED}`));
    for (const file of FILES) {
      const contents = await read(file);
      for (const trace of tracesOf(bySlug[REMOVED])) assert.ok(!contents.includes(trace), `${file}: ${trace}`);
    }
    // Its tag was its alone, so the contents do not offer it either.
    assert.ok(!(await read('README.md')).includes('`night`'));
  });

  test('it is left out whether the list names its original post or its slug', async (t) => {
    const dir = await scratch(t);
    const target = bySlug['brass-compass-on-map'];
    const cases = {
      // The same post in another form: the import tool's key folds "www." and a trailing slash.
      post: { removed: [{ original_url: target.source.original_url.replace('https://', 'https://www.') + '/', slug: 'some-other-slug' }] },
      slug: { removed: [{ slug: target.slug }] },
    };
    for (const [name, list] of Object.entries(cases)) {
      const file = path.join(dir, `${name}.json`);
      await writeFile(file, JSON.stringify(list));
      const { code, log, read } = await generate(t, { removalList: file });
      assert.equal(code, 0, log);
      const data = JSON.parse(await read('data/recipes.json'));
      assert.ok(!data.recipes.some((entry) => entry.slug === target.slug), `by ${name}`);
      assert.equal(data.recipes.length, fixtures.length - 1, `by ${name}: only that one`);
    }
  });

  test('a removed recipe disappears on the next run', async (t) => {
    const dir = await scratch(t);
    const empty = path.join(dir, 'empty.json');
    await writeFile(empty, JSON.stringify({ removed: [] }));
    const before = await generate(t, { removalList: empty });
    assert.ok((await before.read('README.md')).includes(`<a id="${REMOVED}"></a>`), 'listed while it is not removed');
    assert.ok((await before.read('README.md')).includes(`last updated ${bySlug[REMOVED].published_on}`));
    const after = await generate(t);
    assert.ok(!(await after.read('README.md')).includes(REMOVED), 'gone once it is');
  });

  test('a removal list that cannot be read stops the run instead of being skipped', async (t) => {
    const dir = await scratch(t);
    const broken = path.join(dir, 'broken.json');
    await writeFile(broken, '{ "removed": [ { "reason": "names nothing" } ] }');
    for (const removalList of [path.join(dir, 'missing.json'), broken]) {
      const { code, log, out } = await generate(t, { removalList });
      assert.equal(code, 1, log);
      assert.match(log, /removal list/);
      assert.ok(!existsSync(out), 'nothing written');
    }
  });

  test('without --removal-list, the committed one is read', async () => {
    const { DEFAULT_REMOVAL_LIST } = await import('./cli.js');
    assert.equal(DEFAULT_REMOVAL_LIST, path.join(MARKETING, 'scripts/gallery/config/removal-list.json'));
    assert.ok(Array.isArray(JSON.parse(await readFile(DEFAULT_REMOVAL_LIST, 'utf8')).removed));
  });
});

describe('the switch', () => {
  test('the run is refused, and nothing written, while the gallery is off', async (t) => {
    const empty = await scratch(t);
    const off = {
      'no media base URL': { ...ON, GALLERY_MEDIA_BASE_URL: '' },
      'no removal contact': { ...ON, GALLERY_REMOVAL_CONTACT: '' },
      'no recipes': { ...ON, GALLERY_CONTENT_DIR: empty },
      // main as it is: nothing set in gallery-config.ts and no overrides.
      'no media base URL, no removal contact': { GALLERY_CONTENT_DIR: FIXTURES },
    };
    for (const [why, env] of Object.entries(off)) {
      const { code, log, out } = await generate(t, { env });
      assert.equal(code, 1, `${why}: ${log}`);
      assert.match(log, /^open-list: refused: the gallery is off \(/, why);
      assert.ok(log.includes(`(${why}`), `${why}: says why`);
      assert.match(log, /Nothing was written\.$/, why);
      assert.ok(!existsSync(out), `${why}: no folder`);
    }
  });

  test('a recipe the format refuses stops the run, naming it', async (t) => {
    const dir = await scratch(t);
    const broken = { ...bySlug['brass-compass-on-map'], prompt: { ...bySlug['brass-compass-on-map'].prompt, zh: '' } };
    await writeFile(path.join(dir, 'brass-compass-on-map.json'), JSON.stringify(broken));
    const { code, log, out } = await generate(t, { env: { ...ON, GALLERY_CONTENT_DIR: dir } });
    assert.equal(code, 1, log);
    assert.match(log, /brass-compass-on-map\.json[\s\S]*\[prompt-languages\]/);
    assert.ok(!existsSync(out));
  });
});

describe('what stays out of the output', () => {
  test('no absolute local path, environment value or key appears in the output', async (t) => {
    const secrets = {
      GODANCE_API_KEY: 'gd_live_test_openlist_not_a_real_key',
      OPEN_LIST_DEPLOY_KEY: '-----BEGIN OPENSSH PRIVATE KEY-----\nopen-list-test-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      GITHUB_TOKEN: 'ghs_openListTestNotARealToken0000',
      OPEN_LIST_REPO: 'example-org/open-list-test-target',
      HOME: '/home/open-list-test-user',
      INIT_CWD: MARKETING,
      SOME_UNRELATED_SETTING: 'open-list-test-marker-7f3a',
    };
    const { code, log, out, read } = await generate(t, { env: { ...ON, ...secrets } });
    assert.equal(code, 0, log);
    const forbidden = [
      ...Object.values(secrets),
      MARKETING,
      FIXTURES,
      FIXTURE_REMOVALS,
      out,
      os.homedir(),
      os.tmpdir(),
      process.cwd(),
    ];
    for (const file of FILES) {
      const contents = await read(file);
      for (const value of forbidden) assert.ok(!contents.includes(value), `${file}: ${value}`);
      assert.doesNotMatch(contents, /BEGIN [A-Z ]*PRIVATE KEY|gd_live_|\bgh[pousr]_[A-Za-z0-9]{8}/, file);
      assert.doesNotMatch(contents, /file:\/\/|(?<![\w:/.@-])\/(?:home|Users|mnt|tmp|var|root)\//, `${file}: an absolute path`);
    }
  });

  test('no media is copied: the list is five text files, and every picture is on the media host', async (t) => {
    const { out, read } = await generate(t);
    assert.deepEqual(await filesIn(out), FILES);
    for (const file of ['README.md', 'README_zh.md']) {
      const pictures = imagesIn(await read(file));
      assert.ok(pictures.length > 0, file);
      for (const src of pictures) assert.ok(src.startsWith(MEDIA), `${file}: ${src}`);
    }
  });
});

describe('the command line', () => {
  test('npm run open-list -- --out <dir> writes the same list', async (t) => {
    const out = path.join(await scratch(t), 'list');
    // npm_execpath is set when the tests run through npm; otherwise npm is on the PATH.
    const [command, ...prefix] = process.env.npm_execpath ? [process.execPath, process.env.npm_execpath] : ['npm'];
    const base = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
    const result = spawnSync(command, [...prefix, 'run', '--silent', 'open-list', '--', '--out', out, '--removal-list', FIXTURE_REMOVALS], {
      cwd: MARKETING,
      env: { ...base, ...ON },
      encoding: 'utf8',
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    const inProcess = await generate(t);
    for (const file of FILES) assert.equal(await readFile(path.join(out, file), 'utf8'), await inProcess.read(file), file);
  });

  test('--out is required, and an unknown option is refused', async (t) => {
    const { main } = await import('./cli.js');
    for (const argv of [[], ['--out'], ['--out', 'x', '--force'], ['stray']]) {
      const lines = [];
      assert.equal(await main(argv, { env: ON, log: (line) => lines.push(line) }), 2, argv.join(' '));
      assert.match(lines.join('\n'), /Usage: npm run open-list -- --out <folder>/);
    }
    const lines = [];
    assert.equal(await main(['--help'], { env: ON, log: (line) => lines.push(line) }), 0);
    assert.match(lines.join('\n'), /Refuses while the gallery is off/);
  });
});
