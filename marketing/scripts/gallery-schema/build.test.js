/**
 * The content collection, through a real `astro build`: a malformed recipe
 * fails the build even though no page reads the collection, and
 * GALLERY_CONTENT_DIR decides which folder is read.
 *
 * Each build writes to a temporary folder of its own, never to dist/.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { galleryFolder, imageRecipe, videoRecipe } from './example-recipes.js';
import { MARKETING_ROOT } from './read-recipes.js';

const astroPackage = path.join(MARKETING_ROOT, 'node_modules/astro');
const ASTRO = path.join(astroPackage, JSON.parse(readFileSync(path.join(astroPackage, 'package.json'), 'utf8')).bin.astro);

/**
 * Build the site with the gallery read from `galleryDir`.
 *
 * The output goes under .astro/, which is git-ignored: Astro moves its
 * prerendered files there into place by renaming them, which fails when the
 * output folder is on another disk, as the system temp folder can be.
 */
async function build(t, galleryDir) {
  const scratch = path.join(MARKETING_ROOT, '.astro');
  await mkdir(scratch, { recursive: true });
  const outDir = await mkdtemp(path.join(scratch, 'test-build-'));
  t.after(() => rm(outDir, { recursive: true, force: true }));
  const result = spawnSync(process.execPath, [ASTRO, 'build', '--outDir', outDir], {
    cwd: MARKETING_ROOT,
    env: { ...process.env, GALLERY_CONTENT_DIR: galleryDir, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' },
    encoding: 'utf8',
  });
  return { status: result.status, output: `${result.stdout}\n${result.stderr}`, outDir };
}

describe('npm run build', { timeout: 300_000 }, () => {
  test('builds when every recipe in GALLERY_CONTENT_DIR is valid', async (t) => {
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': imageRecipe(),
      'kite-over-a-salt-flat.json': videoRecipe(),
      'NOTICE.md': '# Notice\n',
    });
    const { status, output, outDir } = await build(t, dir);
    assert.equal(status, 0, output);
    assert.ok(existsSync(path.join(outDir, 'index.html')), 'the site was built');
  });

  test('fails on a malformed recipe, naming the file and the rule, and builds nothing', async (t) => {
    const noChinese = imageRecipe();
    delete noChinese.prompt.zh;
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': noChinese,
      'kite.json': videoRecipe(), // valid, but not under its own name
    });

    const { status, output, outDir } = await build(t, dir);
    assert.notEqual(status, 0, output);
    assert.ok(output.includes(`gallery: 2 problems in 2 files under ${dir}`), output);
    assert.match(output, / {2}paper-lantern-street-at-dusk\.json\n {4}\[prompt-languages\] prompt\.zh: required, and must not be empty/);
    assert.match(output, / {2}kite\.json\n {4}\[slug-file-name\] slug: "kite-over-a-salt-flat" must equal the file name/);
    assert.ok(!existsSync(path.join(outDir, 'index.html')), 'no page was written');
  });
});
