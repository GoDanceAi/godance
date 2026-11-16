/**
 * Comparison pages (round 13), and `runs` in the recipe JSON: the helpers
 * run directly, then real `astro build`s against the comparison fixtures,
 * read back from the output folder. One build has a pair of models at the
 * threshold, the other one recipe short of it.
 *
 * The build reads today's prices from a stand-in for GET /v1/models on
 * 127.0.0.1, so nothing leaves this machine. Each build writes to a folder of
 * its own under .astro/, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { DEFAULT_COMPARE_MIN, resolveGalleryConfig } from '../../src/lib/gallery-config.ts';
import {
  compareIntro,
  comparePath,
  compareSlug,
  compareTotals,
  comparisons,
  dollars,
  getKeyUrl,
  runThisUrl,
} from '../../src/lib/gallery-compare.ts';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = path.join(ROOT, 'fixtures/gallery/comparisons');
const astroPackage = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

const SITE = 'https://godance.ai';
const MEDIA = 'https://media.example.test/gallery/';
const APP = 'https://app.godance.ai';
const PAIR = 'gpt-image-2-vs-seedream-5.0';

/** The comparison fixtures, newest first, as the gallery orders them. */
const recipes = readdirSync(FIXTURES)
  .filter((name) => name.endsWith('.json'))
  .map((name) => JSON.parse(readFileSync(path.join(FIXTURES, name), 'utf8')))
  .sort((a, b) => b.published_on.localeCompare(a.published_on) || a.slug.localeCompare(b.slug));
const bySlug = Object.fromEntries(recipes.map((recipe) => [recipe.slug, recipe]));
const onBoth = recipes.filter((recipe) => ['gpt-image-2', 'seedream-5.0'].every((model) => recipe.runs.some((run) => run.model === model)));

/** Words that would declare a winner, which no comparison page may use. */
const VERDICT = /\b(winner|wins?|won|better|best|worse|worst|cheaper|cheapest|faster|fastest|recommended?|beats?|superior)\b/i;

// --- the helpers --------------------------------------------------------------

describe('which pairs get a page', () => {
  test('a pair gets one at the threshold, and not one recipe below it', () => {
    assert.equal(onBoth.length, 11, 'the fixtures put eleven recipes on both models');
    assert.deepEqual(comparisons(recipes, 11).map((pair) => pair.slug), [PAIR]);
    assert.deepEqual(comparisons(recipes, 12), []);
    // The default threshold is eight: the pair with eleven gets a page, the ones with two and one do not.
    assert.equal(DEFAULT_COMPARE_MIN, 8);
    assert.deepEqual(comparisons(recipes, DEFAULT_COMPARE_MIN).map((pair) => [pair.slug, pair.recipes.length]), [[PAIR, 11]]);
    assert.deepEqual(comparisons(recipes, 1).map((pair) => [pair.slug, pair.recipes.length]), [
      [PAIR, 11],
      ['flux-2-pro-vs-gpt-image-2', 2],
      ['gpt-image-2-vs-qwen-image-2.0', 1],
      ['qwen-image-2.0-vs-seedream-5.0', 1],
    ]);
  });

  test('a pair has one page whichever way it is named, and keeps the gallery\'s order of recipes', () => {
    assert.equal(compareSlug('seedream-5.0', 'gpt-image-2'), PAIR);
    assert.equal(comparePath('gpt-image-2', 'seedream-5.0'), `/gallery/compare/${PAIR}`);
    const [pair] = comparisons(recipes, 8);
    assert.deepEqual(pair.recipes.map((each) => each.recipe.slug), onBoth.map((recipe) => recipe.slug));
    assert.ok(pair.recipes.every((each) => each.a.model === 'gpt-image-2' && each.b.model === 'seedream-5.0'));
  });

  test('a recipe with two runs on one model counts once, with the first of them', () => {
    const twice = { ...bySlug['amber-tram-in-snow'], runs: [...bySlug['amber-tram-in-snow'].runs, { ...bySlug['amber-tram-in-snow'].runs[1], cost_cents: 99 }] };
    const [pair] = comparisons([twice], 1);
    assert.equal(pair.recipes.length, 1);
    assert.equal(pair.recipes[0].b.cost_cents, 4);
  });

  test('the totals, the averages and the introduction come from the runs, and name no winner', () => {
    const [pair] = comparisons(recipes, 8);
    const sum = (model) => onBoth.reduce((total, recipe) => total + recipe.runs.find((run) => run.model === model).cost_cents, 0);
    assert.deepEqual(compareTotals(pair), [
      { model: 'gpt-image-2', total: sum('gpt-image-2'), average: sum('gpt-image-2') / 11 },
      { model: 'seedream-5.0', total: sum('seedream-5.0'), average: sum('seedream-5.0') / 11 },
    ]);
    assert.deepEqual([dollars(6), dollars(66), dollars(5.909), dollars(0.5)], ['$0.06', '$0.66', '$0.059', '$0.005']);
    const intro = compareIntro(pair);
    assert.match(intro, /^11 image recipes from the Godance gallery were each run on both gpt-image-2 and seedream-5\.0 through the Godance API, between 10 November 2026 and 22 November 2026\./);
    assert.ok(intro.includes(`the gpt-image-2 runs cost ${dollars(sum('gpt-image-2'))}`), intro);
    assert.ok(intro.includes(`the seedream-5.0 runs ${dollars(sum('seedream-5.0'))}`), intro);
    assert.doesNotMatch(intro, VERDICT);
  });

  test('"Run this" and "Get an API key" say where the visit came from, as the recipe page does', () => {
    const recipe = bySlug['amber-tram-in-snow'];
    const campaign = 'utm_source=godance&utm_medium=gallery&utm_campaign=amber-tram-in-snow';
    assert.equal(runThisUrl(APP, recipe), `${APP}/create/image?example=amber-tram-in-snow&${campaign}`);
    assert.equal(getKeyUrl(APP, recipe), `${APP}/signup?${campaign}`);
  });

  test('GALLERY_COMPARE_MIN sets the threshold; empty is the default; anything but a whole number of at least 1 fails the build', () => {
    assert.equal(resolveGalleryConfig({}).compareMin, 8);
    assert.equal(resolveGalleryConfig({ GALLERY_COMPARE_MIN: '20' }).compareMin, 20);
    assert.equal(resolveGalleryConfig({ GALLERY_COMPARE_MIN: ' ' }).compareMin, 8);
    for (const bad of ['0', '-1', 'eight', '2.5']) {
      assert.throws(() => resolveGalleryConfig({ GALLERY_COMPARE_MIN: bad }), /GALLERY_COMPARE_MIN must be a whole number of recipes, at least 1/);
    }
  });
});

// --- the built pages -----------------------------------------------------------

/** Today's catalogue as GET /v1/models answers it, for the models of the fixtures. */
const CATALOGUE = [
  ['gpt-image-2', 'openai', 53000],
  ['seedream-5.0', 'volcengine-ark', 35000],
  ['qwen-image-2.0', 'alibaba-dashscope', 35000],
  ['flux-2-pro', 'bfl', 30000],
].map(([id, owner, micro]) => ({
  id, object: 'model', owned_by: owner,
  godance: { modality: 'IMAGE', requires_async: true, pricing: { per_image_microcents: micro }, markup_percent: 12 },
}));

let catalogue;
const outDirs = [];
before(async () => {
  catalogue = http.createServer((request, response) => {
    if (request.url === '/v1/models') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ object: 'list', data: CATALOGUE }));
    } else {
      response.writeHead(404);
      response.end();
    }
  });
  await new Promise((resolve) => catalogue.listen(0, '127.0.0.1', resolve));
});
after(async () => {
  await new Promise((resolve) => catalogue.close(resolve));
  for (const dir of outDirs) await rm(dir, { recursive: true, force: true });
});

/** Build the site against the comparison fixtures with these variables. Not spawnSync: the catalogue answers from this process. */
async function build(env) {
  const scratch = path.join(ROOT, '.astro');
  await mkdir(scratch, { recursive: true });
  const outDir = await mkdtemp(path.join(scratch, 'test-compare-'));
  outDirs.push(outDir);
  const base = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
  const child = spawn(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
    cwd: ROOT,
    env: {
      ...base,
      GALLERY_CONTENT_DIR: FIXTURES,
      GALLERY_MEDIA_BASE_URL: MEDIA,
      GALLERY_REMOVAL_CONTACT: 'removals@example.test',
      ...env,
      SITE_URL: SITE,
      PUBLIC_API_BASE: `http://127.0.0.1:${catalogue.address().port}`,
      ASTRO_TELEMETRY_DISABLED: '1',
      NO_COLOR: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (chunk) => (output += chunk));
  child.stderr.on('data', (chunk) => (output += chunk));
  const status = await new Promise((resolve) => child.on('close', resolve));
  return { status, output, outDir };
}

const read = (dir, file) => readFileSync(path.join(dir, file), 'utf8');
function walk(dir, prefix = '') {
  return readdirSync(path.join(dir, prefix)).flatMap((name) => {
    const relative = prefix ? `${prefix}/${name}` : name;
    return statSync(path.join(dir, relative)).isDirectory() ? walk(dir, relative) : [relative];
  });
}
const decode = (text) =>
  text.replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n))).replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
/** The visible text of the page's main content. */
const textOf = (html) => decode(html.slice(html.indexOf('<main'), html.indexOf('</main>')).replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ');
const hrefs = (html) => [...html.matchAll(/<a\b[^>]*\shref="([^"]*)"/g)].map((match) => decode(match[1]));
const jsonLd = (html) => [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((match) => JSON.parse(match[1]));
/** The element that carries `marker`, from its opening tag to the end of the element of the same name that follows it. */
function block(html, marker) {
  const start = html.indexOf(marker);
  if (start === -1) return null;
  const open = html.lastIndexOf('<', start);
  const name = /^<(\w+)/.exec(html.slice(open))[1];
  let depth = 0;
  const tags = new RegExp(`<(/?)${name}\\b[^>]*>`, 'g');
  tags.lastIndex = open;
  for (let match = tags.exec(html); match; match = tags.exec(html)) {
    depth += match[1] ? -1 : 1;
    if (depth === 0) return html.slice(open, match.index + match[0].length);
  }
  return null;
}

describe('a pair at the threshold', { timeout: 300_000 }, () => {
  let dir;
  before(async () => {
    const result = await build({ GALLERY_COMPARE_MIN: '11', GALLERY_RUN_THIS: 'true' });
    assert.equal(result.status, 0, result.output);
    dir = result.outDir;
  });

  test('gets a page, and no other pair does', () => {
    assert.deepEqual(readdirSync(path.join(dir, 'gallery/compare')).sort(), [PAIR, 'index.html'].sort());
    const html = read(dir, `gallery/compare/${PAIR}/index.html`);
    assert.equal(decode(/<title>([\s\S]*?)<\/title>/.exec(html)[1]), 'gpt-image-2 vs seedream-5.0: 11 prompts, outputs and costs | Godance gallery');
    assert.match(html, new RegExp(`<link rel="canonical" href="${SITE}/gallery/compare/${PAIR.replace(/\./g, '\\.')}"`));
    for (const file of walk(dir).filter((name) => /\.(html|xml|json)$/.test(name))) {
      for (const below of ['flux-2-pro-vs-gpt-image-2', 'gpt-image-2-vs-qwen-image-2.0', 'qwen-image-2.0-vs-seedream-5.0']) {
        assert.ok(!read(dir, file).includes(below), `${file} links to ${below}, which has too few recipes`);
      }
    }
  });

  test('opens with what the data shows, and declares no winner anywhere', () => {
    const html = read(dir, `gallery/compare/${PAIR}/index.html`);
    const [pair] = comparisons(recipes, 11);
    assert.ok(textOf(html).includes(compareIntro(pair)), 'the introduction is the one made from the data');
    assert.doesNotMatch(textOf(html), VERDICT);
  });

  test('a summary table: recipes compared, cost and average per model, and today\'s price linked to /pricing', () => {
    const html = read(dir, `gallery/compare/${PAIR}/index.html`);
    const [pair] = comparisons(recipes, 11);
    const today = { 'gpt-image-2': '$0.0594 per image', 'seedream-5.0': '$0.0392 per image' };
    for (const totals of compareTotals(pair)) {
      const row = block(html, `data-compare-model="${totals.model}"`);
      assert.ok(row, totals.model);
      const cells = textOf(`<main>${row}</main>`).trim();
      assert.equal(cells, `${totals.model} 11 ${dollars(totals.total)} ${dollars(totals.average)} ${today[totals.model]}`);
      assert.ok(hrefs(row).includes('/pricing'), `${totals.model}: today's price links to /pricing`);
      assert.ok(hrefs(row).includes(`/gallery/model/${encodeURIComponent(totals.model)}`), `${totals.model}: its model page`);
    }
  });

  test('each recipe: both outputs side by side, each with its cost, the recipe\'s page and "Run this"', () => {
    const html = read(dir, `gallery/compare/${PAIR}/index.html`);
    const order = [...html.matchAll(/data-compare-recipe="([^"]+)"/g)].map((match) => match[1]);
    assert.deepEqual(order, onBoth.map((recipe) => recipe.slug));
    for (const recipe of onBoth) {
      const section = block(html, `data-compare-recipe="${recipe.slug}"`);
      for (const model of ['gpt-image-2', 'seedream-5.0']) {
        const run = recipe.runs.find((each) => each.model === model);
        const side = block(section, `data-compare-run="${model}"`);
        assert.ok(side.includes(`src="${MEDIA}${run.outputs[0].file.split('/').map(encodeURIComponent).join('/')}"`), `${recipe.slug}: the ${model} output`);
        assert.equal(textOf(`<main>${block(side, 'data-compare-cost')}</main>`).trim(), `$${(run.cost_cents / 100).toFixed(2)}`);
      }
      assert.ok(hrefs(section).includes(`/gallery/${recipe.slug}`), `${recipe.slug}: a link to the recipe`);
      assert.ok(hrefs(section).includes(runThisUrl(APP, recipe)), `${recipe.slug}: "Run this"`);
    }
  });

  test('structured data, and a place in the sitemap, as the recipe pages have', () => {
    const html = read(dir, `gallery/compare/${PAIR}/index.html`);
    const [graph] = jsonLd(html).filter((data) => data['@graph']);
    const [page, list] = graph['@graph'];
    assert.deepEqual([page['@type'], page.url, page.mainEntity['@id']], ['WebPage', `${SITE}/gallery/compare/${PAIR}`, `${SITE}/gallery/compare/${PAIR}#recipes`]);
    assert.equal(list['@type'], 'ItemList');
    assert.deepEqual(list.itemListElement.map((item) => item.url), onBoth.map((recipe) => `${SITE}/gallery/${recipe.slug}`));
    const sitemap = read(dir, 'sitemap-0.xml');
    assert.ok(sitemap.includes(`<loc>${SITE}/gallery/compare/</loc>`), 'the index');
    assert.ok(sitemap.includes(`<loc>${SITE}/gallery/compare/${PAIR}/</loc>`), 'the page');
  });

  test('the index lists it, and the pages of its two models link to it', () => {
    const index = read(dir, 'gallery/compare/index.html');
    assert.deepEqual([...index.matchAll(/data-compare-link="([^"]+)"/g)].map((match) => match[1]), [PAIR]);
    assert.ok(hrefs(index).includes(`/gallery/compare/${PAIR}`));
    assert.equal(jsonLd(index).find((data) => data['@type'] === 'CollectionPage').mainEntity.numberOfItems, 1);
    for (const model of ['gpt-image-2', 'seedream-5.0']) {
      const page = read(dir, `gallery/model/${model}/index.html`);
      assert.ok(hrefs(block(page, 'data-model-comparisons')).includes(`/gallery/compare/${PAIR}`), `${model}'s page links to the comparison`);
    }
    for (const model of ['flux-2-pro', 'qwen-image-2.0']) {
      assert.ok(!read(dir, `gallery/model/${model}/index.html`).includes('data-model-comparisons'), `${model} has no comparison`);
    }
  });

  test('the recipe JSON carries every run, in order, and keeps run as the first', () => {
    for (const recipe of recipes) {
      const data = JSON.parse(read(dir, `gallery/${recipe.slug}.json`));
      assert.deepEqual(data.runs, recipe.runs.map((run) => ({ model: run.model, prompt_lang: run.prompt_lang, params: run.params, cost_cents: run.cost_cents })));
      const { cost_cents: _cost, ...first } = data.runs[0];
      assert.deepEqual(data.run, first);
    }
    assert.deepEqual(JSON.parse(read(dir, 'gallery/windmill-at-blue-hour.json')).runs.map((run) => run.model), ['gpt-image-2', 'seedream-5.0', 'qwen-image-2.0']);
  });
});

describe('one recipe short of the threshold', { timeout: 300_000 }, () => {
  test('there is no comparison page, no index and no link', async () => {
    const { status, output, outDir } = await build({ GALLERY_COMPARE_MIN: '12' });
    assert.equal(status, 0, output);
    assert.ok(existsSync(path.join(outDir, 'gallery/amber-tram-in-snow/index.html')), 'the gallery itself is built');
    assert.ok(!existsSync(path.join(outDir, 'gallery/compare')), 'no /gallery/compare');
    for (const file of walk(outDir).filter((name) => /\.(html|xml|json)$/.test(name))) {
      assert.ok(!read(outDir, file).includes('/gallery/compare'), `${file} links to a comparison`);
    }
  });
});
