/**
 * The open list never credits less than the recipe page (round 13 brief 02).
 *
 * The standard is the real recipe pages, built with `astro build` from the
 * same fixtures the list is generated from. Every link and every fact in a
 * page's Credit section must be in that recipe's entry, in both READMEs. The
 * removal contact and the output licence statement, which a page repeats for
 * its one recipe, must be in the README and the files beside it.
 *
 * The credit in the list is ported from the page (credit.js), so this is what
 * keeps the two together: a page that starts crediting more fails this test
 * until the list does too. Also held to the page: the first run's model and
 * cost, and the output's address on the media host.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const MARKETING = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = fileURLToPath(new URL('./fixtures/recipes/', import.meta.url));
const FIXTURE_REMOVALS = fileURLToPath(new URL('./fixtures/removal-list.json', import.meta.url));
const astroPackage = path.join(MARKETING, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

const SITE = 'https://godance.ai';
const ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: 'https://media.example.test/gallery',
  GALLERY_REMOVAL_CONTACT: 'removals@example.test',
  GALLERY_OUTPUT_LICENCE: 'Fixture statement: outputs may be reused freely.',
  GALLERY_RUN_THIS: 'false',
  SITE_URL: SITE,
};

// --- reading the built pages (as scripts/gallery-pages/pages.test.js does) ---

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
  return match ? decode(match[1] ?? match[2] ?? '') : null;
}

/** Visible text of some HTML, on one line. */
const textOf = (fragment) => decode(fragment.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();

/** The inner HTML of the first element carrying `marker`. */
function inner(html, marker) {
  const match = new RegExp(`<(\\w+)\\b[^>]*\\s${marker}[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  assert.ok(match, `an element with ${marker}`);
  return match[2];
}

/** The links in some HTML, as { href, text }. */
const anchorsIn = (fragment) =>
  [...fragment.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].map(([, attributes, body]) => ({
    href: attr(` ${attributes} `, 'href'),
    text: textOf(body),
  }));

// --- reading the list (as open-list.test.js does) ---

const unescape = (text) => text.replace(/\\([!-\/:-@[-`{-~])/g, '$1');

function linksIn(markdown) {
  const links = [];
  for (const [, text, href] of markdown.matchAll(/\[((?:\\.|[^\]\\])*)\]\(((?:\\.|[^\s()\\])+)\)/g)) {
    links.push({ text: unescape(text).replace(/`/g, '').replace(/\s+/g, ' ').trim(), href: unescape(href) });
  }
  return links;
}

function entryOf(readme, slug) {
  const anchor = `<a name="${slug}"></a>`;
  const start = readme.indexOf(anchor);
  assert.notEqual(start, -1, `an entry for ${slug}`);
  const rest = readme.slice(start + anchor.length);
  const end = rest.search(/<a name="[a-z0-9-]+"><\/a>|^## /m);
  return end === -1 ? readme.slice(start) : readme.slice(start, start + anchor.length + end);
}

// ---------------------------------------------------------------------------

let site;
let list;
const scratch = [];

before(async () => {
  const root = path.join(MARKETING, '.astro');
  await mkdir(root, { recursive: true });
  site = await mkdtemp(path.join(root, 'test-open-list-pages-'));
  scratch.push(site);
  const base = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
  const build = spawnSync(process.execPath, [ASTRO, 'build', '--outDir', site], {
    cwd: MARKETING,
    env: { ...base, ...ON, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' },
    encoding: 'utf8',
  });
  assert.equal(build.status, 0, `astro build:\n${build.stdout}\n${build.stderr}`);

  list = path.join(await mkdtemp(path.join(root, 'test-open-list-list-')), 'list');
  scratch.push(path.dirname(list));
  const { main } = await import('./cli.js');
  const lines = [];
  const code = await main(['--out', list, '--removal-list', FIXTURE_REMOVALS], { env: ON, log: (line) => lines.push(line) });
  assert.equal(code, 0, lines.join('\n'));
});

after(async () => {
  for (const dir of scratch) await rm(dir, { recursive: true, force: true });
});

const read = (file) => readFile(path.join(list, file), 'utf8');
const pageOf = (slug) => readFile(path.join(site, `gallery/${slug}/index.html`), 'utf8');

describe('the list credits as its pages do', () => {
  test('every link in a page\'s credit line is in the entry, in both READMEs, with the same text', async () => {
    const data = JSON.parse(await read('data/recipes.json'));
    assert.ok(data.recipes.length > 0);
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    for (const { slug } of data.recipes) {
      const page = await pageOf(slug);
      const promptLine = inner(inner(page, 'data-credit'), 'data-credit-prompt');
      const pageLinks = anchorsIn(promptLine);
      assert.ok(pageLinks.length >= 3, `${slug}: the page links its author, post and licence`);
      for (const [file, readme] of [['README.md', english], ['README_zh.md', chinese]]) {
        const links = linksIn(entryOf(readme, slug));
        for (const want of pageLinks) {
          // The link to the original post is labelled in the README's own language.
          const sameText = want.text !== 'original post' || file === 'README.md';
          assert.ok(
            links.some((link) => link.href === want.href && (!sameText || link.text === want.text)),
            `${slug} ${file}: "${want.text}" → ${want.href}`,
          );
        }
      }
      // A list the page names without a link is named in the entry too.
      for (const [, name] of promptLine.matchAll(/<span class="font-mono">([^<]*)<\/span>/g)) {
        for (const readme of [english, chinese]) assert.ok(unescape(entryOf(readme, slug)).includes(decode(name)), `${slug}: the list ${name}`);
      }
    }
  });

  test('what was changed, or that nothing was, is in the entry as the page says it', async () => {
    const data = JSON.parse(await read('data/recipes.json'));
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    for (const { slug } of data.recipes) {
      const said = textOf(inner(inner(await pageOf(slug), 'data-credit'), 'data-credit-changes'));
      const en = unescape(entryOf(english, slug));
      const zh = unescape(entryOf(chinese, slug));
      const listed = /^Changes from the original: (.*)\.$/.exec(said);
      if (listed) {
        for (const change of listed[1].split('; ')) {
          assert.ok(en.includes(change), `${slug} README.md: ${change}`);
          assert.ok(zh.includes(change), `${slug} README_zh.md: ${change}`);
        }
      } else {
        assert.match(said, /unchanged/, slug);
        assert.ok(en.includes('unchanged'), `${slug} README.md`);
        assert.ok(zh.includes('未作改动'), `${slug} README_zh.md`);
      }
    }
  });

  test('that Godance made the output, and the sample inputs, is in the entry as the page says it', async () => {
    const data = JSON.parse(await read('data/recipes.json'));
    const english = await read('README.md');
    const chinese = await read('README_zh.md');
    for (const { slug } of data.recipes) {
      const said = textOf(inner(inner(await pageOf(slug), 'data-credit'), 'data-credit-output'));
      assert.match(said, /generated by Godance from that prompt/, slug);
      assert.match(unescape(entryOf(english, slug)), /generated by Godance from that prompt/, `${slug} README.md`);
      assert.match(entryOf(chinese, slug), /由 Godance 根据该提示词生成/, `${slug} README_zh.md`);
      if (/sample input/.test(said)) {
        assert.match(unescape(entryOf(english, slug)), /sample inputs?: (it comes|they come) from no list and no photo/, `${slug} README.md`);
        assert.match(entryOf(chinese, slug), /示例输入图也由 Godance 生成，不来自任何清单或照片/, `${slug} README_zh.md`);
      }
    }
  });

  test('the output licence statement and the removal contact the pages show are in the list', async () => {
    const data = JSON.parse(await read('data/recipes.json'));
    const page = await pageOf(data.recipes[0].slug);
    const credit = inner(page, 'data-credit');
    assert.ok(textOf(inner(credit, 'data-credit-output')).includes(ON.GALLERY_OUTPUT_LICENCE), 'the page shows the statement');
    for (const file of ['README.md', 'README_zh.md', 'LICENSE.md']) assert.ok((await read(file)).includes(ON.GALLERY_OUTPUT_LICENCE), file);
    assert.equal(data.licences.outputs, ON.GALLERY_OUTPUT_LICENCE);

    const removal = anchorsIn(inner(credit, 'data-removal'));
    assert.ok(removal.length > 0, 'the page links the removal contact');
    for (const file of ['README.md', 'README_zh.md', 'CONTRIBUTING.md']) {
      const links = linksIn(await read(file));
      for (const want of removal) assert.ok(links.some((link) => link.href === want.href), `${file}: ${want.href}`);
    }
  });

  test("the first run's model and cost, and the output's address, are the page's", async () => {
    const data = JSON.parse(await read('data/recipes.json'));
    const english = await read('README.md');
    for (const entry of data.recipes) {
      const page = await pageOf(entry.slug);
      const facts = inner(page, 'data-run-facts');
      const model = anchorsIn(facts)[0].text;
      // The runs table is the one headed "Model"; an input recipe has a table of slots before it.
      const lines = entryOf(english, entry.slug).split('\n');
      const firstRow = lines[lines.findIndex((line) => line.startsWith('| Model |')) + 2];
      assert.ok(firstRow.startsWith(`| \`${model}\` |`), `${entry.slug}: model`);
      assert.ok(unescape(firstRow).includes(` ${textOf(inner(page, 'data-cost'))} `), `${entry.slug}: cost`);
      const ogImage = attr(/<meta property="og:image"[^>]*>/.exec(page)[0], 'content');
      const first = entry.runs[0].outputs[0];
      const still = entry.modality === 'VIDEO' ? first.poster : first.url;
      if (still) assert.equal(still, ogImage, `${entry.slug}: the output's address on the media host`);
    }
  });
});
