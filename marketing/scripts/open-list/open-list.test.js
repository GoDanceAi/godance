/**
 * The open recipe list (round 13 brief 02): `npm run open-list -- --out <dir>`
 * run against the recipes in ./fixtures/recipes, which it reads through the
 * same reader as `npm run validate`.
 *
 * The fixtures are invented: titles, prompts, authors and links are made up,
 * and the media they name exists nowhere. The list only links to media, so
 * none is needed.
 */
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';

const MARKETING = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = fileURLToPath(new URL('./fixtures/recipes/', import.meta.url));

const MEDIA = 'https://media.example.test/gallery/';
const CONTACT = 'removals@example.test';
const OUTPUT_LICENCE = 'Fixture statement: outputs may be reused freely.';

/** The gallery switched on against the fixtures, and nothing else from this machine's environment. */
const ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: MEDIA,
  GALLERY_REMOVAL_CONTACT: CONTACT,
  GALLERY_OUTPUT_LICENCE: OUTPUT_LICENCE,
  GALLERY_RUN_THIS: 'false',
};

/**
 * Run the generator into a fresh folder with this environment and no other.
 * The folder is inside the project (git-ignored), not in the system's temp
 * folder, and is removed when the test ends.
 */
async function generate(t, env = ON, ...args) {
  const scratch = path.join(MARKETING, '.astro');
  await mkdir(scratch, { recursive: true });
  const out = await mkdtemp(path.join(scratch, 'test-open-list-'));
  t.after(() => rm(out, { recursive: true, force: true }));
  const { main } = await import('./cli.js');
  const lines = [];
  const code = await main(['--out', out, ...args], { env, log: (line) => lines.push(line) });
  return { code, log: lines.join('\n'), read: (file) => readFile(path.join(out, file), 'utf8') };
}

/** Markdown's backslash escapes undone: the text as a reader sees it. */
const unescape = (text) => text.replace(/\\([!-\/:-@[-`{-~])/g, '$1');

/**
 * The links in a piece of a README, written in Markdown or in HTML, as
 * { text, href }, with the text as a reader sees it and without code marks.
 */
function linksIn(markdown) {
  const links = [];
  for (const [, text, href] of markdown.matchAll(/\[((?:\\.|[^\]\\])*)\]\(<?([^\s)>]+)>?\)/g)) {
    links.push({ text: unescape(text).replace(/`/g, ''), href });
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
});
