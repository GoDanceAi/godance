/**
 * The gallery pages, through real `astro build`s read back from the output
 * folder: once with the gallery on against the fixtures, once with "Run this"
 * on as well, and with the gallery off.
 *
 * Each build writes to a folder of its own under .astro/, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { defaultsIn, renderTemplate } from '../../src/lib/gallery-template.js';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = path.join(ROOT, 'fixtures/gallery/recipes');
const astroPackage = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

const SITE = 'https://godance.ai';
const MEDIA = 'https://media.example.test/gallery/';
const CONTACT = 'removals@example.test';
const OUTPUT_LICENCE = 'Fixture statement: outputs may be reused freely.';
const ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: MEDIA.slice(0, -1), // no trailing slash: the config adds it
  GALLERY_REMOVAL_CONTACT: CONTACT,
  GALLERY_OUTPUT_LICENCE: OUTPUT_LICENCE,
};

/** The fixture recipes, newest first, as the pages order them. */
const recipes = readdirSync(FIXTURES)
  .filter((name) => name.endsWith('.json'))
  .map((name) => JSON.parse(readFileSync(path.join(FIXTURES, name), 'utf8')))
  .sort((a, b) => b.published_on.localeCompare(a.published_on) || a.slug.localeCompare(b.slug));
const bySlug = Object.fromEntries(recipes.map((recipe) => [recipe.slug, recipe]));
const models = [...new Set(recipes.flatMap((recipe) => recipe.runs.map((run) => run.model)))];

const outDirs = [];
after(async () => {
  for (const dir of outDirs) await rm(dir, { recursive: true, force: true });
});

/** Build the site with these gallery variables and no others. */
async function build(env) {
  const scratch = path.join(ROOT, '.astro');
  await mkdir(scratch, { recursive: true });
  const outDir = await mkdtemp(path.join(scratch, 'test-pages-'));
  outDirs.push(outDir);
  const base = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
  const result = spawnSync(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
    cwd: ROOT,
    env: { ...base, ...env, SITE_URL: SITE, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' },
    encoding: 'utf8',
  });
  return { status: result.status, output: `${result.stdout}\n${result.stderr}`, outDir };
}

// --- reading the built pages -------------------------------------------------

const read = (dir, file) => readFileSync(path.join(dir, file), 'utf8');
const pageOf = (dir, slug) => read(dir, `gallery/${slug}/index.html`);

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
  const match = new RegExp(`\\s${name}(?:="([^"]*)"|=([^\\s>]+))?(?=[\\s>])`).exec(tag);
  if (!match) return null;
  return decode(match[1] ?? match[2] ?? '');
}

/** The text inside the first element carrying `marker` (an attribute, as written). */
function inner(html, marker) {
  const match = new RegExp(`<(\\w+)\\b[^>]*\\s${marker}[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  return match ? decode(match[2].replace(/<[^>]+>/g, '')) : null;
}

const title = (html) => decode(/<title>([\s\S]*?)<\/title>/.exec(html)[1]);
const canonical = (html) => attr(tags(html, 'link').find((tag) => attr(tag, 'rel') === 'canonical'), 'href');
const meta = (html, key) =>
  attr(tags(html, 'meta').find((tag) => attr(tag, 'name') === key || attr(tag, 'property') === key) ?? '', 'content');

const jsonLd = (html) =>
  [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((match) => JSON.parse(match[1]));

/** The API snippets on a page, in order: [{ kind: 'create' | 'poll', text }]. */
const snippets = (html) =>
  [...html.matchAll(/<code\b[^>]*data-api-snippet="(\w+)"[^>]*>([\s\S]*?)<\/code>/g)].map((match) => ({
    kind: match[1],
    text: decode(match[2]),
  }));

/**
 * Runs a recipe page's copy script against a stand-in for the page: one Copy
 * button and the element it names. `clipboard` is what `navigator.clipboard`
 * is (undefined for a browser that has none).
 */
function copyHarness(html, { clipboard }) {
  const source = [...html.matchAll(/<script(?![^>]*application\/ld\+json)[^>]*>([\s\S]*?)<\/script>/g)]
    .map((match) => match[1])
    .find((text) => text.includes('data-copy'));
  assert.ok(source, 'the page carries the copy script');

  const target = { textContent: 'the text to copy' };
  const listeners = [];
  const button = {
    textContent: 'Copy',
    attributes: { 'data-copy': 'copy-target' },
    getAttribute(name) { return this.attributes[name] ?? null; },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    addEventListener(type, listener) { if (type === 'click') listeners.push(listener); },
  };
  const selected = [];
  const timers = [];
  vm.runInNewContext(source, {
    document: {
      querySelectorAll: (selector) => (selector === '[data-copy]' ? [button] : []),
      getElementById: (id) => (id === 'copy-target' ? target : null),
      createRange: () => ({ selectNodeContents: (node) => selected.push(node) }),
    },
    navigator: clipboard === undefined ? {} : { clipboard },
    window: { getSelection: () => ({ removeAllRanges() {}, addRange() {} }) },
    setTimeout: (callback) => timers.push(callback),
    clearTimeout() {},
  });
  return {
    button,
    target,
    selected,
    async click() {
      for (const listener of listeners) listener({ preventDefault() {} });
      await new Promise((resolve) => setImmediate(resolve));
    },
    runTimers() {
      for (const callback of timers.splice(0)) callback();
    },
  };
}

// --- the gallery on -----------------------------------------------------------

describe('gallery on, against the fixtures', { timeout: 300_000 }, () => {
  let dir;
  before(async () => {
    const result = await build(ON);
    assert.equal(result.status, 0, result.output);
    dir = result.outDir;
  });

  test('the fixtures cover what the brief asks for', () => {
    const outputs = recipes.flatMap((recipe) => recipe.runs.flatMap((run) => run.outputs));
    assert.ok(recipes.some((r) => r.modality === 'IMAGE'), 'an image');
    assert.ok(outputs.some((o) => o.duration_seconds && o.poster), 'a video with a poster');
    assert.ok(outputs.some((o) => o.duration_seconds && !o.poster), 'a video without a poster');
    assert.ok(recipes.some((r) => r.prompt.zh_origin === 'machine'), 'a machine-translated prompt');
    assert.ok(recipes.some((r) => r.prompt.zh_origin === 'list'), 'a prompt translated by the list');
    assert.ok(recipes.some((r) => r.prompt.variables?.length), 'a prompt with variables');
    assert.ok(recipes.some((r) => r.runs.length > 1), 'a recipe with two runs');
  });

  test('one page per recipe, each with its own title and canonical URL', () => {
    const titles = new Set();
    const canonicals = new Set();
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      titles.add(title(html));
      canonicals.add(canonical(html));
      assert.equal(canonical(html), `${SITE}/gallery/${recipe.slug}`);
      assert.ok(title(html).startsWith(recipe.title.en), title(html));
    }
    assert.equal(titles.size, recipes.length, 'titles are unique');
    assert.equal(canonicals.size, recipes.length, 'canonical URLs are unique');

    const pages = readdirSync(path.join(dir, 'gallery')).filter(
      (name) => name !== 'model' && statSync(path.join(dir, 'gallery', name)).isDirectory(),
    );
    assert.deepEqual(pages.sort(), recipes.map((r) => r.slug).sort(), 'and no page without a recipe');
  });

  test('the description is the start of the English prompt, and the output is the Open Graph image', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const description = meta(html, 'description');
      const start = recipe.prompt.en.replace(/\s+/g, ' ').slice(0, 40);
      assert.ok(description.startsWith(start), `${recipe.slug}: ${description}`);
      assert.ok(description.length <= 160, `${recipe.slug}: ${description.length} characters`);

      const output = recipe.runs[0].outputs[0];
      const picture = recipe.modality === 'IMAGE' ? output.file : output.poster;
      assert.equal(
        meta(html, 'og:image'),
        picture ? MEDIA + picture : `${SITE}/og-default.png`,
        `${recipe.slug}: og:image`,
      );
    }
  });

  test('both prompts are shown, the Chinese one marked zh-Hans', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const en = tags(html, 'p').find((tag) => attr(tag, 'id') === 'prompt-en');
      const zh = tags(html, 'p').find((tag) => attr(tag, 'id') === 'prompt-zh');
      assert.ok(en, `${recipe.slug}: English prompt block`);
      assert.equal(attr(en, 'lang'), null);
      assert.equal(attr(zh, 'lang'), 'zh-Hans', `${recipe.slug}: Chinese prompt block`);
      assert.equal(inner(html, 'id="prompt-en"'), recipe.prompt.en);
      assert.equal(inner(html, 'id="prompt-zh"'), recipe.prompt.zh);
      assert.equal(html.match(/data-copy="prompt-(en|zh)"/g)?.length, 2, `${recipe.slug}: a Copy button each`);
    }
  });

  test('the machine-translation note appears only where the prompt was machine-translated', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const noted = /machine translation/i.test(html);
      assert.equal(noted, recipe.prompt.zh_origin === 'machine', recipe.slug);
    }
  });

  test('the Chinese title sits beneath the title when there is one', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      assert.equal(inner(html, 'data-title-zh'), recipe.title.zh ?? null, recipe.slug);
      if (recipe.title.zh) {
        assert.equal(attr(tags(html, 'p').find((tag) => attr(tag, 'data-title-zh') !== null), 'lang'), 'zh-Hans');
      }
    }
  });

  test('variables are listed with their defaults, only on recipes that have them', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const listed = /<(section|div)\b[^>]*data-variables[^>]*>([\s\S]*?)<\/\1>/.exec(html);
      assert.equal(listed !== null, Boolean(recipe.prompt.variables?.length), recipe.slug);
      for (const variable of recipe.prompt.variables ?? []) {
        const text = decode(listed[2].replace(/<[^>]+>/g, '\n'));
        assert.ok(text.includes(variable.name) && text.includes(variable.default), `${recipe.slug}: ${variable.name}`);
      }
    }
  });

  test('the fixtures cover a template, a JSON-shaped one, and a recipe from before templates', () => {
    assert.ok(recipes.some((r) => r.prompt.template && r.prompt.variables.length === 2 && r.prompt.variables.some((v) => v.zh_default)), 'two variables, one with a Chinese default');
    assert.ok(recipes.some((r) => r.prompt.template?.en.endsWith('}}') && r.prompt.template.zh.endsWith('}}')), 'a template that ends in "}}"');
    assert.ok(recipes.some((r) => !r.prompt.template && r.prompt.variables?.length), 'variables and no template');
    assert.ok(recipes.some((r) => !r.prompt.template && !r.prompt.variables), 'no variables at all');
  });

  test('a recipe with a template shows it, each variable a marked chip, only where there is one', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const section = /<section\b[^>]*data-template(?=[\s>])[^>]*>([\s\S]*?)<\/section>\s*<\/div>\s*<div\b[^>]*data-variables/.exec(html);
      assert.equal(section !== null, Boolean(recipe.prompt.template), recipe.slug);
      assert.equal(/data-template-block/.test(html), Boolean(recipe.prompt.template), recipe.slug);
      if (!recipe.prompt.template) continue;

      for (const [lang, id] of [['en', 'template-en'], ['zh', 'template-zh']]) {
        // The text of the block is the template itself, character for character: that is what Copy template copies.
        assert.equal(inner(html, `id="${id}"`), recipe.prompt.template[lang], `${recipe.slug}: ${lang} template`);
        const block = new RegExp(`<p\\b[^>]*id="${id}"[^>]*>([\\s\\S]*?)</p>`).exec(html)[1];
        const chips = [...block.matchAll(/<mark\b[^>]*data-variable="([^"]*)"[^>]*>([\s\S]*?)<\/mark>/g)].map((m) => [decode(m[1]), decode(m[2])]);
        const markers = [...recipe.prompt.template[lang].matchAll(/\{\{([^{}]+)\}\}/g)].map((m) => m[1]);
        assert.deepEqual(chips, markers.map((name) => [name, `{{${name}}}`]), `${recipe.slug}: one chip per marker, in order`);
      }
      assert.equal(attr(tags(html, 'p').find((tag) => attr(tag, 'id') === 'template-zh'), 'lang'), 'zh-Hans');
      assert.equal(html.match(/data-copy="template-(en|zh)"/g)?.length, 2, `${recipe.slug}: a Copy template button each`);
      assert.equal(html.match(/>Copy template</g)?.length, 2, recipe.slug);
      assert.match(inner(html, 'data-template'), /In the Studio, each variable gets a field of its own\./);
    }
  });

  test('the variables table gives each default, in English and in Chinese', () => {
    for (const recipe of recipes.filter((r) => r.prompt.template)) {
      const html = pageOf(dir, recipe.slug);
      for (const variable of recipe.prompt.variables) {
        const row = new RegExp(`<tr\\b[^>]*data-variable-row="${variable.name}"[^>]*>([\\s\\S]*?)</tr>`).exec(html);
        assert.ok(row, `${recipe.slug}: a row for ${variable.name}`);
        const cells = [...row[1].matchAll(/<t[hd]\b[^>]*>([\s\S]*?)<\/t[hd]>/g)].map((m) => decode(m[1].replace(/<[^>]+>/g, '')).trim());
        assert.deepEqual(cells, [variable.name, variable.default, variable.zh_default ?? 'the same'], `${recipe.slug}: ${variable.name}`);
      }
    }
  });

  test('Copy on the prompt still copies the prompt with its blanks filled, not the template', () => {
    for (const recipe of recipes.filter((r) => r.prompt.template)) {
      const html = pageOf(dir, recipe.slug);
      assert.equal(inner(html, 'id="prompt-en"'), recipe.prompt.en, recipe.slug);
      assert.ok(!inner(html, 'id="prompt-en"').includes('{{'), recipe.slug);
      assert.equal(html.match(/data-copy="prompt-(en|zh)"/g)?.length, 2);
    }
  });

  test('model, parameters, recorded cost and date are shown, with a link to current prices', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const run = recipe.runs[0];
      const facts = decode(/<dl\b[^>]*data-run-facts[^>]*>([\s\S]*?)<\/dl>/.exec(html)[1].replace(/<[^>]+>/g, '\n'));
      assert.ok(facts.includes(run.model), recipe.slug);
      for (const [key, value] of Object.entries(run.params)) {
        assert.ok(facts.includes(key) && facts.includes(String(value)), `${recipe.slug}: ${key}`);
      }
      assert.equal(inner(html, 'data-cost'), `$${(run.cost_cents / 100).toFixed(2)}`, recipe.slug);
      assert.ok(html.includes(`datetime="${run.generated_on}"`), recipe.slug);
      assert.ok(tags(html, 'a').some((tag) => attr(tag, 'href') === '/pricing'), recipe.slug);
    }
  });

  test('credit, source licence and removal line are on every recipe page', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const credit = /<section\b[^>]*data-credit[^>]*>([\s\S]*?)<\/section>/.exec(html)[1];
      const links = tags(credit, 'a').map((tag) => attr(tag, 'href'));
      const text = decode(credit.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ');

      assert.ok(text.includes(recipe.source.author), `${recipe.slug}: author`);
      assert.ok(links.includes(recipe.source.author_url), `${recipe.slug}: author link`);
      assert.ok(links.includes(recipe.source.original_url), `${recipe.slug}: original post`);
      assert.ok(text.includes(recipe.source.list), `${recipe.slug}: source list`);
      assert.equal(inner(credit, 'data-source-licence'), recipe.source.licence, `${recipe.slug}: licence`);
      for (const change of recipe.source.changes) assert.ok(text.includes(change), `${recipe.slug}: ${change}`);
      if (recipe.source.changes.length === 0) assert.match(text, /unchanged/, recipe.slug);
      assert.ok(text.includes(OUTPUT_LICENCE), `${recipe.slug}: output licence statement`);
      assert.ok(inner(credit, 'data-removal').includes(CONTACT), `${recipe.slug}: removal line`);
      assert.ok(links.includes(`mailto:${CONTACT}`), `${recipe.slug}: removal link`);
    }
  });

  test('structured data parses and names the original author as the prompt\'s author', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const blocks = jsonLd(html);
      assert.equal(blocks.length, 1, recipe.slug);
      const graph = blocks[0]['@graph'];
      const url = `${SITE}/gallery/${recipe.slug}`;
      const page = graph.find((node) => node['@type'] === 'WebPage');
      const prompt = graph.find((node) => node['@id'] === `${url}#prompt`);
      const output = graph.find((node) => node['@id'] === `${url}#output`);

      assert.equal(prompt.author.name, recipe.source.author);
      assert.equal(prompt.author.url, recipe.source.author_url);
      assert.equal(prompt.isBasedOn, recipe.source.original_url);
      assert.match(prompt.license, /^https:\/\//);
      assert.equal(prompt.text, recipe.prompt.en);

      assert.equal(output.creator.name, 'Godance');
      assert.equal(page.publisher.name, 'Godance');
      assert.equal(page.url, url);
      assert.equal(output.author, undefined, 'the output is not attributed to the prompt\'s author');

      const first = recipe.runs[0].outputs[0];
      assert.equal(output.contentUrl, MEDIA + first.file);
      if (recipe.modality === 'VIDEO') {
        assert.equal(output['@type'], 'VideoObject');
        assert.equal(output.thumbnailUrl, first.poster ? MEDIA + first.poster : undefined, 'a thumbnail only with a poster');
      } else {
        assert.equal(output['@type'], 'ImageObject');
      }
    }
  });

  test('no text in a recipe can close a script element', () => {
    const html = pageOf(dir, 'handwritten-receipt-note');
    assert.ok(bySlug['handwritten-receipt-note'].prompt.en.includes('</script>'), 'the fixture carries the tag');
    assert.equal(html.match(/<\/script>/g).length, html.match(/<script\b/g).length, 'every </script> closes a real script');
    assert.equal(inner(html, 'id="prompt-en"'), bySlug['handwritten-receipt-note'].prompt.en);
  });

  test('nothing follows the closing </html> on any gallery page', () => {
    const pages = walk(dir).filter((file) => file.startsWith('gallery/') && file.endsWith('.html'));
    assert.ok(pages.length > recipes.length, 'recipe, gallery and model pages');
    for (const file of pages) {
      assert.ok(read(dir, file).trimEnd().endsWith('</html>'), `${file} ends with </html>`);
    }
  });

  test('Copy writes the named text to the clipboard and says so, then puts its label back', async () => {
    const written = [];
    const page = copyHarness(pageOf(dir, 'tin-robot-tea-party'), {
      clipboard: { writeText: (text) => { written.push(text); return Promise.resolve(); } },
    });
    await page.click();
    assert.deepEqual(written, ['the text to copy']);
    assert.equal(page.button.textContent, 'Copied');
    page.runTimers();
    assert.equal(page.button.textContent, 'Copy');
  });

  test('a refused clipboard write selects the text for the reader, and says so', async () => {
    const page = copyHarness(pageOf(dir, 'tin-robot-tea-party'), {
      clipboard: { writeText: () => Promise.reject(new Error('Write permission denied.')) },
    });
    await page.click();
    assert.deepEqual(page.selected, [page.target], 'the text is selected');
    assert.equal(page.button.textContent, 'Selected');
    page.runTimers();
    assert.equal(page.button.textContent, 'Copy');
  });

  test('a browser with no clipboard API gets the same fallback', async () => {
    const page = copyHarness(pageOf(dir, 'tin-robot-tea-party'), { clipboard: undefined });
    await page.click();
    assert.deepEqual(page.selected, [page.target]);
    assert.equal(page.button.textContent, 'Selected');
  });

  test('clicking Copy twice in a row still puts the original label back', async () => {
    const page = copyHarness(pageOf(dir, 'tin-robot-tea-party'), {
      clipboard: { writeText: () => Promise.resolve() },
    });
    await page.click();
    await page.click();
    page.runTimers();
    assert.equal(page.button.textContent, 'Copy');
  });

  test('"Run this" is absent while it is off, and "Get an API key" links to signup', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      assert.ok(!/Run this/.test(html) && !html.includes('?example='), recipe.slug);
      const cta = tags(html, 'a').find((tag) => attr(tag, 'data-get-key') !== null);
      // It says which recipe page the visit came from.
      assert.equal(attr(cta, 'href'), `https://app.godance.ai/signup?utm_source=godance&utm_medium=gallery&utm_campaign=${recipe.slug}`, recipe.slug);
    }
  });

  test('the API snippet reproduces the run, and its body is valid JSON for any prompt', () => {
    for (const recipe of recipes) {
      const found = snippets(pageOf(dir, recipe.slug));
      assert.equal(found.length, recipe.runs.length * 2, `${recipe.slug}: a request and a poll per run`);

      recipe.runs.forEach((run, i) => {
        const { kind, text } = found[i * 2];
        assert.equal(kind, 'create');
        const kindPath = recipe.modality === 'VIDEO' ? 'videos' : 'images';
        assert.ok(text.startsWith(`curl https://api.godance.ai/v1/${kindPath}/generations \\\n`), text);
        assert.ok(text.includes('-H "Authorization: Bearer $GODANCE_API_KEY"'), text);

        const heredoc = /<<'JSON'\n([\s\S]*)\nJSON$/.exec(text);
        assert.ok(heredoc, `${recipe.slug}: a quoted here-document`);
        const body = JSON.parse(heredoc[1]);
        // The run as it was sent, and the recipe it is a run of, which the API records on the job.
        assert.deepEqual(body, { model: run.model, prompt: recipe.prompt[run.prompt_lang], ...run.params, gallery_recipe: recipe.slug });
        assert.equal(Object.keys(body).at(-1), 'gallery_recipe');

        const poll = found[i * 2 + 1];
        assert.equal(poll.kind, 'poll');
        assert.ok(poll.text.includes('curl https://api.godance.ai/v1/jobs/$JOB_ID'), poll.text);
      });
    }
  });

  test('the snippet runs in a shell as shown: quotes, $, backtick and newlines arrive intact', (t) => {
    const recipe = bySlug['handwritten-receipt-note'];
    for (const needle of ['"', "'", '$', '`', '\n', '\\']) {
      assert.ok(recipe.prompt.en.includes(needle), `the fixture prompt contains ${JSON.stringify(needle)}`);
    }
    const { text } = snippets(pageOf(dir, recipe.slug))[0];

    // Paste the snippet into bash with curl replaced by something that prints
    // its arguments and its standard input.
    const script = `curl() { printf '%s\\n' "$@"; printf -- '--body--\\n'; cat; }\n${text}\n`;
    const shell = spawnSync('bash', ['-c', script], {
      encoding: 'utf8',
      env: { PATH: process.env.PATH, GODANCE_API_KEY: 'gd_test_key', HOME: '/nonexistent', TOTAL: 'expanded' },
    });
    if (shell.error) return t.skip(`bash is not available: ${shell.error.message}`);
    assert.equal(shell.status, 0, shell.stderr);

    const [args, body] = shell.stdout.split('--body--\n');
    assert.ok(args.includes('Authorization: Bearer gd_test_key'), 'the key variable is expanded in the header');
    assert.equal(JSON.parse(body).prompt, recipe.prompt.en, 'and nothing is expanded in the body');
  });

  test('images carry width, height and alt', () => {
    const pages = walk(dir, 'gallery').filter((file) => file.endsWith('.html'));
    let count = 0;
    for (const file of [...pages, 'index.html']) {
      for (const tag of tags(read(dir, file), 'img')) {
        if (!attr(tag, 'src')?.startsWith(MEDIA)) continue;
        count += 1;
        assert.match(attr(tag, 'width') ?? '', /^\d+$/, `${file}: ${tag}`);
        assert.match(attr(tag, 'height') ?? '', /^\d+$/, `${file}: ${tag}`);
        assert.ok(attr(tag, 'alt'), `${file}: ${tag}`);
      }
    }
    assert.ok(count > recipes.length, `${count} gallery images checked`);
  });

  test('images on the gallery page are lazy-loaded', () => {
    const cards = tags(read(dir, 'gallery/index.html'), 'img').filter((tag) => attr(tag, 'src')?.startsWith(MEDIA));
    assert.ok(cards.length > 0);
    for (const tag of cards) assert.equal(attr(tag, 'loading'), 'lazy', tag);
  });

  test('video has controls, never autoplays, and preloads nothing when it has a poster', () => {
    const everyPage = walk(dir).filter((file) => file.endsWith('.html'));
    for (const file of everyPage) {
      for (const tag of tags(read(dir, file), 'video')) {
        assert.equal(attr(tag, 'autoplay'), null, `${file}: ${tag}`);
      }
    }
    for (const recipe of recipes.filter((r) => r.modality === 'VIDEO')) {
      const html = pageOf(dir, recipe.slug);
      const videos = tags(html, 'video');
      const outputs = recipe.runs.flatMap((run) => run.outputs);
      assert.equal(videos.length, outputs.length, recipe.slug);
      videos.forEach((tag, i) => {
        assert.notEqual(attr(tag, 'controls'), null, recipe.slug);
        assert.equal(attr(tag, 'poster'), outputs[i].poster ? MEDIA + outputs[i].poster : null, recipe.slug);
        assert.equal(attr(tag, 'preload'), outputs[i].poster ? 'none' : 'metadata', recipe.slug);
      });
      assert.ok(html.includes(`src="${MEDIA}${outputs[0].file}"`), recipe.slug);
    }
  });

  test('a recipe with more than one run shows each run with its model, cost and output', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const others = [...html.matchAll(/<div\b[^>]*data-run="([^"]+)"/g)].map((match) => match[1]);
      assert.deepEqual(others, recipe.runs.slice(1).map((run) => run.model), recipe.slug);
      for (const run of recipe.runs) {
        assert.ok(html.includes(`$${(run.cost_cents / 100).toFixed(2)}`), `${recipe.slug}: cost of ${run.model}`);
        for (const output of run.outputs) assert.ok(html.includes(MEDIA + output.file), `${recipe.slug}: ${output.file}`);
      }
    }
  });

  test('related recipes: up to six, never the recipe itself, each sharing a tag or a model', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const section = /<section\b[^>]*data-related[^>]*>([\s\S]*?)<\/section>/.exec(html);
      const related = section ? [...section[1].matchAll(/data-recipe="([^"]+)"/g)].map((match) => match[1]) : [];
      assert.ok(related.length <= 6 && !related.includes(recipe.slug), recipe.slug);
      const myModels = recipe.runs.map((run) => run.model);
      for (const slug of related) {
        const other = bySlug[slug];
        const shares =
          other.tags.some((tag) => recipe.tags.includes(tag)) || other.runs.some((run) => myModels.includes(run.model));
        assert.ok(shares, `${recipe.slug} → ${slug}`);
      }
    }
  });

  test('the gallery page lists every recipe, newest first, without JavaScript', () => {
    const html = read(dir, 'gallery/index.html');
    const listed = [...html.matchAll(/<a\b[^>]*data-recipe="([^"]+)"/g)].map((match) => match[1]);
    assert.deepEqual(listed, recipes.map((r) => r.slug));
    for (const tag of tags(html, 'a').filter((t) => attr(t, 'data-recipe') !== null)) {
      assert.equal(attr(tag, 'hidden'), null, 'no card is hidden in the markup');
      assert.equal(attr(tag, 'href'), `/gallery/${attr(tag, 'data-recipe')}`);
    }
    assert.equal(canonical(html), `${SITE}/gallery`);
    // A mark on videos.
    for (const recipe of recipes) {
      const card = new RegExp(`<a\\b[^>]*data-recipe="${recipe.slug}"[\\s\\S]*?</a>`).exec(html)[0];
      assert.equal(/data-video-mark/.test(card), recipe.modality === 'VIDEO', recipe.slug);
    }
  });

  test('the model filter is plain links, and search data is not inlined', () => {
    const html = read(dir, 'gallery/index.html');
    const links = tags(html, 'a').map((tag) => attr(tag, 'href'));
    for (const model of models) assert.ok(links.includes(`/gallery/model/${model}`), model);
    // The type, tag and search controls need script, and stay hidden without it.
    assert.match(html, /<div\b[^>]*data-gallery-js[^>]*\shidden[\s>]/);
    assert.ok(html.includes('data-search-url="/gallery/_search.json"'));
    assert.ok(!html.includes(bySlug['tin-robot-tea-party'].prompt.zh), 'prompts are not in the gallery page');
  });

  test('model pages exist only for models with recipes, and list the right ones', () => {
    assert.deepEqual(readdirSync(path.join(dir, 'gallery/model')).sort(), [...models].sort());
    for (const model of models) {
      const html = read(dir, `gallery/model/${model}/index.html`);
      const listed = [...html.matchAll(/<a\b[^>]*data-recipe="([^"]+)"/g)].map((match) => match[1]);
      const expected = recipes.filter((r) => r.runs.some((run) => run.model === model)).map((r) => r.slug);
      assert.deepEqual(listed, expected, model);
      assert.equal(canonical(html), `${SITE}/gallery/model/${model}`);
    }
    // On a model page the card shows that model's run, not the first run.
    const flux = read(dir, 'gallery/model/flux-2/index.html');
    assert.ok(flux.includes(`${MEDIA}glass-whale-over-rooftops/flux-2-0-480.webp`));
  });

  test('/gallery/index.json matches the coordination shape', () => {
    const data = JSON.parse(read(dir, 'gallery/index.json'));
    assert.deepEqual(Object.keys(data), ['recipes']);
    assert.deepEqual(
      data.recipes,
      recipes.map((recipe) => {
        const output = recipe.runs[0].outputs[0];
        const thumb = recipe.modality === 'IMAGE' ? output.thumb : (output.poster ?? null);
        return {
          slug: recipe.slug,
          title: recipe.title,
          modality: recipe.modality,
          model: recipe.runs[0].model,
          tags: recipe.tags,
          thumb: thumb === null ? null : MEDIA + thumb,
          published_on: recipe.published_on,
          ...(recipe.prompt.template ? { has_variables: true } : {}),
        };
      }),
    );
    assert.ok(data.recipes.some((r) => r.thumb === null), 'a video without a poster has a null thumb');
    // has_variables is true where there is a template to fill in, and absent everywhere else.
    assert.deepEqual(data.recipes.filter((r) => 'has_variables' in r).map((r) => [r.slug, r.has_variables]).sort(),
      recipes.filter((r) => r.prompt.template).map((r) => [r.slug, true]).sort());
  });

  test('/gallery/<slug>.json matches the coordination shape', () => {
    for (const recipe of recipes) {
      const run = recipe.runs[0];
      assert.deepEqual(JSON.parse(read(dir, `gallery/${recipe.slug}.json`)), {
        slug: recipe.slug,
        title: recipe.title,
        modality: recipe.modality,
        prompt: {
          en: recipe.prompt.en,
          zh: recipe.prompt.zh,
          ...(recipe.prompt.template ? { template: recipe.prompt.template, variables: recipe.prompt.variables } : {}),
        },
        run: { model: run.model, prompt_lang: run.prompt_lang, params: run.params },
        url: `${SITE}/gallery/${recipe.slug}`,
      });
    }
    const json = readdirSync(path.join(dir, 'gallery')).filter((name) => name.endsWith('.json'));
    assert.deepEqual(json.sort(), ['_search.json', 'index.json', ...recipes.map((r) => `${r.slug}.json`)].sort());
  });

  test('a recipe with variables serves its template as the round-10 contract has it', () => {
    const data = JSON.parse(read(dir, 'gallery/neon-noodle-cart-poster.json'));
    assert.deepEqual(data.prompt, {
      en: 'A flat-colour poster of a night noodle cart under a teal neon sign that reads "Midnight Ramen", steam rising, bold shapes.',
      zh: '一张平涂风格的夜间面摊海报，青绿色霓虹招牌上写着“Midnight Ramen”，热气升腾，大块几何造型。',
      template: {
        en: 'A flat-colour poster of a night noodle cart under a {{sign colour}} neon sign that reads "{{sign text}}", steam rising, bold shapes.',
        zh: '一张平涂风格的夜间面摊海报，{{sign colour}}霓虹招牌上写着“{{sign text}}”，热气升腾，大块几何造型。',
      },
      variables: [
        { name: 'sign colour', default: 'teal', zh_default: '青绿色' },
        { name: 'sign text', default: 'Midnight Ramen' },
      ],
    });
    // What the contract promises a reader of this file: the templates give back the prompts.
    for (const recipe of recipes.filter((r) => r.prompt.template)) {
      const { prompt } = JSON.parse(read(dir, `gallery/${recipe.slug}.json`));
      assert.equal(renderTemplate(prompt.template.en, defaultsIn(prompt.variables, 'en')), prompt.en, recipe.slug);
      assert.equal(renderTemplate(prompt.template.zh, defaultsIn(prompt.variables, 'zh')), prompt.zh, recipe.slug);
    }
    // A recipe from before templates serves no variables: there is nothing to fill them into.
    assert.deepEqual(Object.keys(JSON.parse(read(dir, 'gallery/moss-covered-typewriter.json')).prompt), ['en', 'zh']);
  });

  test('search data covers titles and prompts in both languages', () => {
    const data = JSON.parse(read(dir, 'gallery/_search.json'));
    assert.deepEqual(data.recipes.map((r) => r.slug), recipes.map((r) => r.slug));
    for (const entry of data.recipes) {
      const recipe = bySlug[entry.slug];
      for (const part of [recipe.title.en, recipe.title.zh, recipe.prompt.en, recipe.prompt.zh]) {
        if (part) assert.ok(entry.text.includes(part.toLowerCase()), `${entry.slug}: ${part.slice(0, 20)}`);
      }
    }
  });

  test('the navigation, the home page and /models link to the gallery', () => {
    const home = read(dir, 'index.html');
    assert.ok(tags(home, 'a').some((tag) => attr(tag, 'href') === '/gallery' && /nav-link/.test(tag)), 'navigation entry');
    const strip = /<section\b[^>]*data-gallery-strip[^>]*>([\s\S]*?)<\/section>/.exec(home);
    assert.ok(strip, 'home-page strip');
    const shown = [...strip[1].matchAll(/data-recipe="([^"]+)"/g)].map((match) => match[1]);
    assert.deepEqual(shown, recipes.slice(0, 6).map((r) => r.slug), 'the six newest');
    assert.ok(tags(strip[1], 'a').some((tag) => attr(tag, 'href') === '/gallery'));

    // /models links a model to its page when it is both in the catalog and in
    // the gallery. Which models the catalog lists depends on whether the API
    // answered during the build, so check each row that is there.
    const modelsPage = read(dir, 'models/index.html');
    const rows = [...modelsPage.matchAll(/<td class="[^"]*font-mono[^"]*">([\s\S]*?)<\/td>/g)].map((match) => match[1]);
    assert.ok(rows.length > 0, 'the catalog has rows');
    let linked = 0;
    for (const row of rows) {
      const id = decode(row.replace(/<[^>]+>[\s\S]*$/, '')).trim();
      const hrefs = tags(row, 'a').map((tag) => attr(tag, 'href'));
      assert.deepEqual(hrefs, models.includes(id) ? [`/gallery/model/${id}`] : [], `the ${id} row`);
      linked += hrefs.length;
    }
    // The offline catalog lists flux-2, which the fixtures have a run on.
    if (rows.some((row) => /^\s*flux-2\s*</.test(row))) assert.ok(linked > 0, 'flux-2 links to its examples');
  });

  test('the sitemap picks up the gallery pages', () => {
    const sitemap = read(dir, 'sitemap-0.xml');
    const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1]);
    const expected = [
      `${SITE}/gallery/`,
      ...recipes.map((r) => `${SITE}/gallery/${r.slug}/`),
      ...models.map((m) => `${SITE}/gallery/model/${m}/`),
    ];
    assert.deepEqual(locs.filter((loc) => loc.includes('/gallery')).sort(), expected.sort());
  });

  test('_headers opens the gallery JSON to any origin, cached for a few minutes', () => {
    const rule = /^\/gallery\/\*\.json\n((?: {2}.+\n?)+)/m.exec(read(dir, '_headers'));
    assert.ok(rule, 'a /gallery/*.json rule');
    assert.match(rule[1], /Access-Control-Allow-Origin: \*/);
    const maxAge = Number(/Cache-Control: .*max-age=(\d+)/.exec(rule[1])[1]);
    assert.ok(maxAge >= 60 && maxAge <= 900, `max-age=${maxAge}`);
  });
});

// --- "Run this" on ------------------------------------------------------------

describe('gallery on, with "Run this" on', { timeout: 300_000 }, () => {
  let dir;
  before(async () => {
    const result = await build({ ...ON, GALLERY_RUN_THIS: 'true' });
    assert.equal(result.status, 0, result.output);
    dir = result.outDir;
  });

  test('"Run this" opens the recipe in the app, by modality', () => {
    for (const recipe of recipes) {
      const html = pageOf(dir, recipe.slug);
      const cta = tags(html, 'a').find((tag) => attr(tag, 'data-run-this') !== null);
      const kind = recipe.modality === 'VIDEO' ? 'video' : 'image';
      assert.equal(attr(cta, 'href'),
        `https://app.godance.ai/create/${kind}?example=${recipe.slug}&utm_source=godance&utm_medium=gallery&utm_campaign=${recipe.slug}`, recipe.slug);
      assert.ok(!/Get an API key/.test(html), recipe.slug);
    }
  });
});

// --- the gallery off ----------------------------------------------------------

/**
 * Nothing in the build mentions the gallery: no file, link, markup, style or
 * script. The one exception is the header rule in _headers, which is copied
 * from public/ as it is and applies to no file while the gallery is off.
 */
function assertNoGallery(dir) {
  assert.ok(!existsSync(path.join(dir, 'gallery')), 'no /gallery files');
  for (const file of walk(dir)) {
    if (file === '_headers' || !/\.(html|xml|txt|json|css|js)$/.test(file)) continue;
    assert.ok(!/gallery/i.test(read(dir, file)), `${file} mentions the gallery`);
  }
  const sitemap = read(dir, 'sitemap-0.xml');
  assert.ok(!sitemap.includes('gallery'), 'no gallery URL in the sitemap');
  for (const page of ['index.html', 'models/index.html']) {
    const hrefs = tags(read(dir, page), 'a').map((tag) => attr(tag, 'href'));
    assert.ok(!hrefs.some((href) => href?.includes('gallery')), `${page} has no gallery link`);
  }
}

describe('gallery off', { timeout: 300_000 }, () => {
  test('with the config empty, recipes or not, the build has no gallery output', async () => {
    const { status, output, outDir } = await build({ GALLERY_CONTENT_DIR: FIXTURES });
    assert.equal(status, 0, output);
    assertNoGallery(outDir);
    assert.ok(existsSync(path.join(outDir, 'index.html')), 'the rest of the site is built');
  });

  test('with the config set but no recipe, the build has no gallery output', async () => {
    const { status, output, outDir } = await build({
      GALLERY_MEDIA_BASE_URL: MEDIA,
      GALLERY_REMOVAL_CONTACT: CONTACT,
    });
    assert.equal(status, 0, output);
    assertNoGallery(outDir);
  });

  test('with only the media base set, the gallery stays off', async () => {
    const { status, output, outDir } = await build({ GALLERY_CONTENT_DIR: FIXTURES, GALLERY_MEDIA_BASE_URL: MEDIA });
    assert.equal(status, 0, output);
    assertNoGallery(outDir);
  });

  test('a malformed switch value fails the build instead of leaving the gallery off', async () => {
    const { status, output } = await build({
      GALLERY_CONTENT_DIR: FIXTURES,
      GALLERY_MEDIA_BASE_URL: 'media.example.test/gallery',
      GALLERY_REMOVAL_CONTACT: CONTACT,
    });
    assert.notEqual(status, 0, output);
    assert.match(output, /the media base URL must be an absolute URL/);
  });

  test('a recipe that fails validation fails the build with the gallery on', async (t) => {
    const { cp, writeFile } = await import('node:fs/promises');
    const scratch = path.join(ROOT, '.astro');
    await mkdir(scratch, { recursive: true });
    const broken = await mkdtemp(path.join(scratch, 'test-recipes-'));
    t.after(() => rm(broken, { recursive: true, force: true }));
    await cp(FIXTURES, broken, { recursive: true });
    const recipe = structuredClone(bySlug['tin-robot-tea-party']);
    delete recipe.prompt.zh;
    await writeFile(path.join(broken, 'tin-robot-tea-party.json'), JSON.stringify(recipe));

    const { status, output, outDir } = await build({ ...ON, GALLERY_CONTENT_DIR: broken });
    assert.notEqual(status, 0, output);
    assert.match(output, /tin-robot-tea-party\.json\n\s+\[prompt-languages\] prompt\.zh/);
    assert.ok(!existsSync(path.join(outDir, 'gallery')), 'no gallery page was written');
  });
});
