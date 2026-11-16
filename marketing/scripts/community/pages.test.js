/**
 * The community pages (round 14 brief 02), through real `astro build`s read
 * back from the output folder. The build reads the API's public routes from
 * a stand-in on 127.0.0.1 (fixtures.js), so nothing leaves this machine.
 *
 * Builds: the community on against two public recipes; on against hostile
 * and broken records; on with the API unreachable; and off.
 *
 * Each build writes to a folder of its own under .astro/, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { AUTHOR, PROFILE, RECIPES, startPublicApi } from './fixtures.js';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const astroPackage = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

const SITE = 'https://godance.ai';
const APP = 'https://app.godance.ai';

const outDirs = [];
after(async () => {
  for (const dir of outDirs) await rm(dir, { recursive: true, force: true });
});

/**
 * Build the site with these variables, and none of the caller's own
 * community, gallery or API ones. Not spawnSync: the stand-in API answers
 * from this process.
 */
async function build(env) {
  const scratch = path.join(ROOT, '.astro');
  await mkdir(scratch, { recursive: true });
  const outDir = await mkdtemp(path.join(scratch, 'test-community-'));
  outDirs.push(outDir);
  const base = Object.fromEntries(Object.entries(process.env).filter(
    ([key]) => !key.startsWith('GALLERY_') && !key.startsWith('COMMUNITY_') && !key.startsWith('PUBLIC_'),
  ));
  const child = spawn(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
    cwd: ROOT,
    env: { ...base, ...env, SITE_URL: SITE, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (chunk) => (output += chunk));
  child.stderr.on('data', (chunk) => (output += chunk));
  const status = await new Promise((resolve) => child.on('close', resolve));
  return { status, output, outDir };
}

// --- reading the built pages -------------------------------------------------

const read = (dir, file) => readFileSync(path.join(dir, file), 'utf8');
const recipePage = (dir, id) => read(dir, `community/${id}/index.html`);
const profilePage = (dir, handle) => read(dir, `u/${handle}/index.html`);

/** The folders directly inside `dir/sub`, or none when it does not exist. */
function folders(dir, sub) {
  const at = path.join(dir, sub);
  if (!existsSync(at)) return [];
  return readdirSync(at).filter((name) => statSync(path.join(at, name)).isDirectory());
}

function walk(dir, prefix = '') {
  return readdirSync(path.join(dir, prefix)).flatMap((name) => {
    const relative = prefix ? `${prefix}/${name}` : name;
    return statSync(path.join(dir, relative)).isDirectory() ? walk(dir, relative) : [relative];
  });
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

/** Every opening tag with this name, as written. */
const tags = (html, name) => html.match(new RegExp(`<${name}\\b[^>]*>`, 'g')) ?? [];

/** An attribute's value from an opening tag, or null. */
function attr(tag, name) {
  const match = new RegExp(`\\s${name}(?:="([^"]*)"|=([^\\s>]+))?(?=[\\s>])`).exec(tag ?? '');
  if (!match) return null;
  return decode(match[1] ?? match[2] ?? '');
}

/** The text inside the first element carrying `marker` (an attribute, as written), tags removed. */
function inner(html, marker) {
  const match = new RegExp(`<(\\w+)\\b[^>]*\\s${marker}[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  return match ? decode(match[2].replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim() : null;
}

/** The element that carries `marker`, from its opening tag to the end of the element of the same name. */
function block(html, marker) {
  const start = html.indexOf(marker);
  if (start === -1) return null;
  const open = html.lastIndexOf('<', start);
  const name = /^<(\w+)/.exec(html.slice(open))[1];
  let depth = 0;
  const re = new RegExp(`<(/?)${name}\\b[^>]*>`, 'g');
  re.lastIndex = open;
  for (let match = re.exec(html); match; match = re.exec(html)) {
    depth += match[1] ? -1 : 1;
    if (depth === 0) return html.slice(open, match.index + match[0].length);
  }
  return null;
}

/** The visible text of the page's main content. */
const textOf = (html) =>
  decode(html.slice(html.indexOf('<main'), html.indexOf('</main>')).replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]+>/g, ' '))
    .replace(/\s+/g, ' ');

const title = (html) => decode(/<title>([\s\S]*?)<\/title>/.exec(html)[1]);
const canonical = (html) => attr(tags(html, 'link').find((tag) => attr(tag, 'rel') === 'canonical'), 'href');
const meta = (html, key) =>
  attr(tags(html, 'meta').find((tag) => attr(tag, 'name') === key || attr(tag, 'property') === key) ?? '', 'content');
const jsonLd = (html) =>
  [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((match) => JSON.parse(match[1]));
const hrefs = (html) => tags(html, 'a').map((tag) => attr(tag, 'href'));
const link = (html, marker) => tags(html, 'a').find((tag) => tag.includes(marker));
const sitemapLocs = (dir) => [...read(dir, 'sitemap-0.xml').matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1]);

/** The recipe cards a list holds, in order, by public id. */
const cards = (html) => tags(html, 'a').map((tag) => attr(tag, 'data-community-recipe')).filter(Boolean);

const [KOI, TEAPOT] = RECIPES;

/**
 * Nothing in the build is community output: no page, no link to one, no
 * sitemap entry, no community markup.
 */
function assertNoCommunity(dir) {
  assert.deepEqual(folders(dir, 'community'), [], 'no /community pages');
  assert.ok(!existsSync(path.join(dir, 'community')), 'no /community folder');
  assert.ok(!existsSync(path.join(dir, 'u')), 'no /u folder');
  for (const file of walk(dir)) {
    if (!/\.(html|xml|txt|json)$/.test(file)) continue;
    const body = read(dir, file);
    assert.ok(!/href="\/(community|u\/)/.test(body), `${file} links to a community page`);
    assert.ok(!/data-community|data-profile-page/.test(body), `${file} has community markup`);
  }
  assert.ok(!sitemapLocs(dir).some((loc) => /\/(community|u)\//.test(loc)), 'no community URL in the sitemap');
}

// --- the community on ---------------------------------------------------------

/** What a careless API might add to a public answer. None of it may reach a page. */
const PRIVATE = {
  email: 'private-author@example.test',
  user_id: '0f0e0d0c-0000-4000-8000-0000000000aa',
  source_job_id: '7f000101-0000-4000-8000-0000000000bb',
};

describe('community on, against two public recipes', { timeout: 300_000 }, () => {
  let api;
  let dir;
  before(async () => {
    api = await startPublicApi({
      recipes: RECIPES.map((recipe) => ({ ...recipe, ...PRIVATE, author: { ...recipe.author, email: PRIVATE.email } })),
      profiles: [{ ...PROFILE, ...PRIVATE }],
    });
    const result = await build({ COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: api.url });
    assert.equal(result.status, 0, result.output);
    assert.ok(!/community:/.test(result.output), `no community warning:\n${result.output}`);
    dir = result.outDir;
  });
  after(async () => {
    await api?.close();
  });

  test('two public recipes make two community pages, and their author one profile page', () => {
    for (const recipe of RECIPES) {
      assert.ok(
        existsSync(path.join(dir, 'community', recipe.public_id, 'index.html')),
        `/community/${recipe.public_id} is built`,
      );
    }
    assert.ok(existsSync(path.join(dir, 'u', AUTHOR.handle, 'index.html')), `/u/${AUTHOR.handle} is built`);

    const recipePages = folders(dir, 'community').filter((name) => name !== 'tag');
    assert.deepEqual(recipePages.sort(), RECIPES.map((recipe) => recipe.public_id).sort(), 'one page per recipe, and no other');
    assert.deepEqual(folders(dir, 'u'), [AUTHOR.handle], 'one page per author, and no other');
  });

  test('the build read only the public routes, with GET and no credential', () => {
    const community = api.requests.filter((request) => request.url !== '/v1/models');
    assert.deepEqual(community.map((request) => request.url), ['/v1/public/recipes?limit=100', `/v1/public/profiles/${AUTHOR.handle}`]);
    for (const request of api.requests) {
      assert.equal(request.method, 'GET');
      assert.equal(request.headers.authorization, undefined);
      assert.equal(request.headers.cookie, undefined);
    }
  });

  test('nothing the API sends beyond the public fields reaches any file', () => {
    for (const file of walk(dir)) {
      if (!/\.(html|xml|txt|json|js|css)$/.test(file)) continue;
      const body = read(dir, file);
      for (const value of Object.values(PRIVATE)) assert.ok(!body.includes(value), `${file} holds ${value}`);
    }
  });

  test('each recipe page has its own title, description, canonical URL and Open Graph image', () => {
    for (const recipe of RECIPES) {
      const html = recipePage(dir, recipe.public_id);
      const url = `${SITE}/community/${recipe.public_id}`;
      assert.ok(title(html).startsWith(`${recipe.title} — community ${recipe.modality} recipe by @${AUTHOR.handle}`), title(html));
      assert.equal(canonical(html), url);
      assert.equal(meta(html, 'og:url'), url);
      assert.equal(meta(html, 'robots'), 'index, follow');
      const description = meta(html, 'description');
      assert.ok(description.length > 20 && description.length <= 160, description);
    }
    // The description is the prompt the template makes with its defaults.
    assert.equal(meta(recipePage(dir, TEAPOT.public_id), 'description'), 'A teapot on a wooden table, soft window light.');
    // A picture's recipe shows its first example; a video's keeps the site's picture.
    assert.equal(meta(recipePage(dir, TEAPOT.public_id), 'og:image'), TEAPOT.examples[0].url);
    assert.equal(meta(recipePage(dir, KOI.public_id), 'og:image'), `${SITE}/og-default.png`);
  });

  test('a recipe page shows its examples: pictures, and video with controls that never plays by itself', () => {
    const teapot = recipePage(dir, TEAPOT.public_id);
    const pictures = tags(block(teapot, 'data-examples'), 'img');
    assert.deepEqual(pictures.map((tag) => attr(tag, 'src')), TEAPOT.examples.map((example) => example.url));
    assert.deepEqual(pictures.map((tag) => attr(tag, 'alt')), ['Teapot still life, example 1', 'Teapot still life, example 2']);
    assert.equal(attr(pictures[0], 'loading'), 'eager');
    assert.equal(attr(pictures[1], 'loading'), 'lazy');

    const koi = recipePage(dir, KOI.public_id);
    const [video] = tags(koi, 'video');
    assert.ok(video, 'a video element');
    assert.notEqual(attr(video, 'controls'), null);
    assert.equal(attr(video, 'autoplay'), null);
    assert.equal(attr(video, 'preload'), 'metadata');
    assert.equal(attr(tags(koi, 'source')[0], 'src'), KOI.examples[0].url);
    assert.equal(attr(tags(koi, 'source')[0], 'type'), 'video/mp4');
  });

  test('the template shows each variable marked, and the table gives its default', () => {
    for (const recipe of RECIPES) {
      const html = recipePage(dir, recipe.public_id);
      assert.equal(inner(html, 'id="template"'), recipe.template);
      const marks = [...block(html, 'id="template"').matchAll(/data-variable="([^"]+)"/g)].map((match) => match[1]);
      assert.deepEqual([...new Set(marks)], recipe.variables.map((variable) => variable.name));
      for (const variable of recipe.variables) {
        const row = block(html, `data-variable-row="${variable.name}"`);
        assert.ok(row, `${recipe.public_id}: ${variable.name}`);
        assert.ok(decode(row).includes(variable.default), `${variable.name}: ${variable.default}`);
      }
      assert.match(html, /data-copy="template"[^>]*>Copy template</);
    }
  });

  test('model and params are listed as published', () => {
    for (const recipe of RECIPES) {
      const html = recipePage(dir, recipe.public_id);
      assert.equal(inner(html, 'data-model'), recipe.model);
      for (const [key, value] of Object.entries(recipe.params)) {
        const fact = inner(html, `data-param="${key}"`);
        assert.equal(fact, `${key} ${value}`, `${recipe.public_id}: ${key}`);
      }
    }
  });

  test('licence, author, what it was made from, and runs, each with its link', () => {
    const teapot = recipePage(dir, TEAPOT.public_id);
    const licence = link(block(teapot, 'data-licence'), 'href');
    assert.equal(attr(licence, 'href'), 'https://creativecommons.org/licenses/by/4.0/');
    assert.equal(attr(licence, 'rel'), 'license noopener');
    assert.match(inner(teapot, 'data-licence-terms'), /crediting the author/);
    assert.equal(attr(link(teapot, 'data-author-link'), 'href'), `/u/${AUTHOR.handle}`);
    assert.equal(inner(teapot, 'data-author-link'), 'Mina');
    assert.equal(inner(teapot, 'data-author-followers'), '3 followers');
    assert.equal(inner(teapot, 'data-runs'), 'Run 12 times by 5 people');
    // From the gallery, which this build has off: named, not linked.
    assert.equal(inner(teapot, 'data-made-from="gallery"'), 'the gallery recipe enamel-teapot-on-marble');
    assert.equal(tags(block(teapot, 'data-made-from="gallery"'), 'a').length, 0);

    const koi = recipePage(dir, KOI.public_id);
    assert.equal(attr(link(block(koi, 'data-licence'), 'href'), 'href'), 'https://creativecommons.org/publicdomain/zero/1.0/');
    assert.equal(inner(koi, 'data-runs'), 'Not run by anyone else yet');
    // From another community recipe, which has a page: linked, by its title.
    assert.equal(inner(koi, 'data-made-from="published"'), `the community recipe ${TEAPOT.title}`);
    assert.equal(attr(link(block(koi, 'data-made-from="published"'), 'href'), 'href'), `/community/${TEAPOT.public_id}`);
  });

  test('"Run this" opens the app\'s Studio with the published recipe, and "Report" the app\'s report form', () => {
    for (const recipe of RECIPES) {
      const html = recipePage(dir, recipe.public_id);
      assert.equal(
        attr(link(html, 'data-run-this'), 'href'),
        `${APP}/create/${recipe.modality}?published=${recipe.public_id}&utm_source=godance&utm_medium=community&utm_campaign=${recipe.public_id}`,
      );
      assert.equal(attr(link(html, 'data-report-link'), 'href'), `${APP}/report?kind=published_recipe&id=${recipe.public_id}`);
      assert.equal(attr(link(html, 'data-report-link'), 'rel'), 'nofollow');
    }
  });

  test('the API call runs the published recipe, and its body is valid JSON', () => {
    const html = recipePage(dir, TEAPOT.public_id);
    const snippet = inner(html, 'data-api-snippet="create"');
    assert.ok(snippet.startsWith('curl https://api.godance.ai/v1/images/generations'), snippet);
    const raw = decode(/<code\b[^>]*data-api-snippet="create"[^>]*>([\s\S]*?)<\/code>/.exec(html)[1]);
    const body = JSON.parse(raw.split("<<'JSON'\n")[1].replace(/\nJSON$/, ''));
    assert.deepEqual(body, { model: 'gpt-image-2', recipe: { published_id: TEAPOT.public_id, variables: { thing: 'teapot' } }, size: '1024x1024' });
  });

  test('structured data names the author as the recipe\'s creator and Godance as the publisher', () => {
    const godance = { '@type': 'Organization', name: 'Godance', url: `${SITE}/` };
    const author = { '@type': 'Person', name: 'Mina', alternateName: `@${AUTHOR.handle}`, url: `${SITE}/u/${AUTHOR.handle}` };
    for (const recipe of RECIPES) {
      const url = `${SITE}/community/${recipe.public_id}`;
      const [data] = jsonLd(recipePage(dir, recipe.public_id));
      assert.equal(data['@context'], 'https://schema.org');
      const byId = Object.fromEntries(data['@graph'].map((node) => [node['@id'], node]));
      const page = byId[url];
      const work = byId[`${url}#recipe`];
      assert.equal(page['@type'], 'WebPage');
      assert.deepEqual(page.publisher, godance);
      assert.deepEqual(page.mainEntity, { '@id': `${url}#recipe` });
      assert.equal(work['@type'], 'CreativeWork');
      assert.equal(work.text, recipe.template);
      assert.deepEqual(work.creator, author);
      assert.deepEqual(work.publisher, godance);
      assert.equal(work.datePublished, recipe.published_at);
      assert.equal(work.interactionStatistic.userInteractionCount, recipe.runs);
      recipe.examples.forEach((example, i) => {
        const media = byId[`${url}#example-${i + 1}`];
        assert.equal(media['@type'], recipe.modality === 'video' ? 'VideoObject' : 'ImageObject');
        assert.equal(media.contentUrl, example.url);
        assert.deepEqual(media.creator, author);
      });
    }
    const koi = jsonLd(recipePage(dir, KOI.public_id))[0]['@graph'].find((node) => node['@type'] === 'CreativeWork');
    assert.equal(koi.license, 'https://creativecommons.org/publicdomain/zero/1.0/');
    assert.equal(koi.isBasedOn, `${SITE}/community/${TEAPOT.public_id}`);
  });

  test('the profile page: the author, how many follow them, their recipes newest first, Follow and Report', () => {
    const html = profilePage(dir, AUTHOR.handle);
    assert.equal(canonical(html), `${SITE}/u/${AUTHOR.handle}`);
    assert.ok(title(html).startsWith(`Mina (@${AUTHOR.handle})`), title(html));
    assert.equal(inner(html, 'class="text-3xl'), 'Mina');
    assert.equal(inner(html, 'data-handle'), `@${AUTHOR.handle}`);
    assert.equal(inner(html, 'data-followers'), '3 followers');
    assert.equal(inner(html, 'data-recipe-count'), '2 recipes');
    const profileLink = link(html, 'data-profile-link');
    assert.equal(attr(profileLink, 'href'), PROFILE.link);
    assert.equal(attr(profileLink, 'rel'), 'ugc nofollow noopener noreferrer');
    assert.deepEqual(cards(html), RECIPES.map((recipe) => recipe.public_id));
    assert.equal(
      attr(link(html, 'data-follow'), 'href'),
      `${APP}/following?follow=${AUTHOR.handle}&utm_source=godance&utm_medium=community&utm_campaign=${AUTHOR.handle}`,
    );
    assert.equal(attr(link(html, 'data-report-link'), 'href'), `${APP}/report?kind=profile&id=${AUTHOR.handle}`);

    const [data] = jsonLd(html);
    assert.equal(data['@type'], 'ProfilePage');
    assert.equal(data.mainEntity['@type'], 'Person');
    assert.equal(data.mainEntity.name, 'Mina');
    assert.deepEqual(data.mainEntity.sameAs, [PROFILE.link]);
    assert.equal(data.mainEntity.interactionStatistic.interactionType, 'https://schema.org/FollowAction');
    assert.equal(data.mainEntity.interactionStatistic.userInteractionCount, 3);
    assert.deepEqual(data.hasPart.map((part) => part.url), RECIPES.map((recipe) => `${SITE}/community/${recipe.public_id}`));
  });

  test('/community lists every recipe newest first, and filters by tag with a page per tag', () => {
    const index = read(dir, 'community/index.html');
    assert.equal(canonical(index), `${SITE}/community`);
    assert.deepEqual(cards(index), RECIPES.map((recipe) => recipe.public_id));
    assert.match(textOf(index), /2 recipes/);

    const filter = block(index, 'data-tag-filter');
    const pills = tags(filter, 'a').map((tag) => attr(tag, 'href'));
    const allTags = [...new Set(RECIPES.flatMap((recipe) => recipe.tags))];
    assert.deepEqual(pills.slice(1).sort(), allTags.map((tag) => `/community/tag/${tag}`).sort());
    assert.equal(pills[0], '/community');

    for (const tag of allTags) {
      const html = read(dir, `community/tag/${tag}/index.html`);
      assert.equal(canonical(html), `${SITE}/community/tag/${tag}`);
      assert.deepEqual(cards(html), RECIPES.filter((recipe) => recipe.tags.includes(tag)).map((recipe) => recipe.public_id), tag);
      const active = tags(block(html, 'data-tag-filter'), 'a').find((pill) => attr(pill, 'aria-current') === 'page');
      assert.equal(attr(active, 'href'), `/community/tag/${tag}`);
    }
    assert.deepEqual(folders(dir, 'community/tag').sort(), allTags.sort(), 'and no tag page without a recipe');

    // Each recipe page links its tags to their pages.
    assert.ok(hrefs(recipePage(dir, TEAPOT.public_id)).includes('/community/tag/still-life'));
  });

  test('the sitemap lists every community page', () => {
    const expected = [
      `${SITE}/community/`,
      ...RECIPES.map((recipe) => `${SITE}/community/${recipe.public_id}/`),
      ...[...new Set(RECIPES.flatMap((recipe) => recipe.tags))].map((tag) => `${SITE}/community/tag/${tag}/`),
      `${SITE}/u/${AUTHOR.handle}/`,
    ];
    assert.deepEqual(sitemapLocs(dir).filter((loc) => /\/(community|u)\//.test(loc)).sort(), expected.sort());
  });

  test('the rest of the site is built as before, with no link to the community in its navigation', () => {
    assert.ok(existsSync(path.join(dir, 'index.html')));
    assert.ok(existsSync(path.join(dir, 'models/index.html')));
    assert.ok(!hrefs(read(dir, 'index.html')).some((href) => href?.startsWith('/community')));
  });
});

// --- hostile and broken records ---------------------------------------------

describe('community on, with hostile text and broken records from the API', { timeout: 300_000 }, () => {
  const HOSTILE = {
    ...TEAPOT,
    public_id: 'h0st1le00001',
    title: 'Mugs </script><script>alert("title")</script>',
    template: 'A <img src=x onerror=alert(1)> {{thing}} on a shelf',
    variables: [{ name: 'thing', default: '"><svg onload=alert(2)>' }],
    tags: ['mugs'],
    made_from: null,
  };
  const ORPHAN = { ...TEAPOT, public_id: 'orphan000001', title: 'An author with no public profile', author: { handle: 'no-profile', followers: 0 }, made_from: null };
  const BROKEN = [{ ...TEAPOT, public_id: '../../escape' }, { ...TEAPOT, public_id: 'notitle00001', title: '' }];
  let api;
  let dir;
  let output;
  before(async () => {
    api = await startPublicApi({ recipes: [HOSTILE, ...BROKEN, ORPHAN], profiles: [PROFILE] });
    const result = await build({ COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: api.url });
    assert.equal(result.status, 0, result.output);
    ({ outDir: dir, output } = result);
  });
  after(async () => {
    await api?.close();
  });

  test('text from the API is shown as text: it can neither add markup nor close a script', () => {
    const html = recipePage(dir, HOSTILE.public_id);
    // Inside a quoted attribute value "<" is plain text; everywhere else it must be escaped.
    const markup = html.replace(/="[^"]*"/g, '=""');
    assert.ok(!markup.includes('<script>alert'), 'no script from the title');
    assert.ok(!markup.includes('<img src=x'), 'no picture from the template');
    assert.ok(!markup.includes('<svg onload'), 'no svg from a default');
    // Read with patterns that know a quoted value may hold ">".
    assert.equal(decode(/<meta property="og:title" content="([^"]*)"/.exec(html)[1]).split(' — ')[0], HOSTILE.title);
    assert.equal(decode(/\salt="([^"]*)"/.exec(block(html, 'data-examples'))[1]), `${HOSTILE.title}, example 1`);
    assert.equal(inner(html, 'class="mt-2 text-3xl'), HOSTILE.title);
    assert.equal(inner(html, 'id="template"'), HOSTILE.template);
    const [data] = jsonLd(html);
    assert.equal(data['@graph'][0].name, HOSTILE.title);
    assert.equal(title(html).split(' — ')[0], HOSTILE.title);
  });

  test('a record a page could not be made from is left out, and the build says so', () => {
    assert.deepEqual(folders(dir, 'community').filter((name) => name !== 'tag').sort(), [HOSTILE.public_id, ORPHAN.public_id].sort());
    assert.ok(!existsSync(path.join(dir, 'escape')) && !existsSync(path.join(ROOT, 'escape')));
    assert.match(output, /community: 2 public recipes could not be read and have no page/);
  });

  test('an author whose profile cannot be read has no page, and their recipe names them without a link', () => {
    assert.deepEqual(folders(dir, 'u'), [AUTHOR.handle]);
    const html = recipePage(dir, ORPHAN.public_id);
    assert.equal(link(html, 'data-author-link'), undefined);
    assert.match(inner(html, 'data-byline'), /^By @no-profile/);
    const work = jsonLd(html)[0]['@graph'].find((node) => node['@type'] === 'CreativeWork');
    assert.deepEqual(work.creator, { '@type': 'Person', name: '@no-profile', alternateName: '@no-profile' });
    assert.match(output, /community: the profile of @no-profile could not be read \(HTTP 404 not_found\); it has no page/);
  });
});

// --- the API unreachable, and the community off --------------------------------

describe('community on, with the API unreachable', { timeout: 300_000 }, () => {
  test('the build succeeds with no community output, and says why in a warning', async () => {
    const api = await startPublicApi();
    const { url } = api;
    await api.close(); // nothing listens there any more
    const { status, output, outDir } = await build({ COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: url });
    assert.equal(status, 0, output);
    assert.match(output, /community: the public API at http:\/\/127\.0\.0\.1:\d+ could not be read \(fetch failed[^)]*\)\. The build goes on without community pages\./);
    assertNoCommunity(outDir);
    assert.ok(existsSync(path.join(outDir, 'index.html')), 'the rest of the site is built');
    assert.ok(existsSync(path.join(outDir, 'models/index.html')), 'and the model catalog, from its snapshot');
  });
});

describe('community off', { timeout: 300_000 }, () => {
  test('with the switch as committed, the build asks the API nothing and has no community output', async () => {
    const api = await startPublicApi();
    try {
      const { status, output, outDir } = await build({ PUBLIC_API_BASE: api.url });
      assert.equal(status, 0, output);
      assertNoCommunity(outDir);
      assert.deepEqual(api.requests.filter((request) => request.url.startsWith('/v1/public/')), []);
      assert.ok(!/community:/.test(output), output);
    } finally {
      await api.close();
    }
  });

  test('switched off for a build, the same', async () => {
    const api = await startPublicApi();
    try {
      const { status, output, outDir } = await build({ COMMUNITY_ENABLED: 'false', PUBLIC_API_BASE: api.url });
      assert.equal(status, 0, output);
      assertNoCommunity(outDir);
      assert.deepEqual(api.requests.filter((request) => request.url.startsWith('/v1/public/')), []);
    } finally {
      await api.close();
    }
  });

  test('a malformed switch value fails the build instead of leaving the community off', async () => {
    const { status, output } = await build({ COMMUNITY_ENABLED: 'maybe' });
    assert.notEqual(status, 0, output);
    assert.match(output, /COMMUNITY_ENABLED must be "true" or "false", not "maybe"/);
  });
});
