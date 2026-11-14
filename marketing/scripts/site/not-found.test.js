/**
 * The not-found page, through a real `astro build` read back from the output
 * folder.
 *
 * Cloudflare Pages answers an unknown path with a 404 only when the build has
 * a 404.html at its top level. Without one it answers with the home page and
 * a 200, so these checks guard the file's place as well as its content.
 *
 * The build writes to a folder of its own under .astro/, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const astroPackage = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

describe('the not-found page', { timeout: 300_000 }, () => {
  let outDir;
  let page;

  before(async () => {
    // Under .astro/, which is git-ignored and on the same disk as the project:
    // Astro moves prerendered files into place by renaming them.
    const scratch = path.join(ROOT, '.astro');
    await mkdir(scratch, { recursive: true });
    outDir = await mkdtemp(path.join(scratch, 'test-site-'));
    // The site as it ships: no gallery variables from the caller's shell.
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GALLERY_')));
    const result = spawnSync(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
      cwd: ROOT,
      env: { ...env, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' },
      encoding: 'utf8',
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    page = existsSync(path.join(outDir, '404.html')) ? readFileSync(path.join(outDir, '404.html'), 'utf8') : null;
  });

  after(async () => {
    if (outDir) await rm(outDir, { recursive: true, force: true });
  });

  test('is written as 404.html at the top of the build, where Cloudflare Pages looks for it', () => {
    assert.ok(page !== null, '404.html exists at the top level');
    assert.ok(!existsSync(path.join(outDir, '404/index.html')), 'and not as /404/index.html');
  });

  test('says the page was not found, and links home', () => {
    assert.match(page, /<h1[^>]*>Page not found<\/h1>/);
    assert.match(page, /<a href="\/"[^>]*>Go to the home page<\/a>/);
  });

  test('asks crawlers not to index it, and is not in the sitemap', () => {
    assert.match(page, /<meta name="robots" content="noindex, follow"/);
    const sitemap = readFileSync(path.join(outDir, 'sitemap-0.xml'), 'utf8');
    assert.ok(!sitemap.includes('404'), 'no 404 URL in the sitemap');
  });

  test('every link, stylesheet, script and image starts at the root, because the page is served at any address', () => {
    const urls = [...page.matchAll(/\s(?:href|src)="([^"]*)"/g)].map((match) => match[1]);
    assert.ok(urls.length > 10, 'the page has its navigation, footer and assets');
    const relative = urls.filter((url) => !/^(\/|https?:\/\/|mailto:|#)/.test(url));
    assert.deepEqual(relative, [], 'no URL is relative to the current address');
  });

  test('a missing bundle under /_astro/ keeps its own plain 404', () => {
    const own = readFileSync(path.join(outDir, '_astro/404.html'), 'utf8');
    assert.match(own, /<p>Not found\.<\/p>/);
    assert.ok(!own.includes('<nav'), 'it is not the site page');
  });
});
