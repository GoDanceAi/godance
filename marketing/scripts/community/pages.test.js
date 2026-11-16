/**
 * The community pages (round 14 brief 02), through real `astro build`s read
 * back from the output folder. The build reads the API's public routes from
 * a stand-in on 127.0.0.1 (fixtures.js), so nothing leaves this machine.
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

/** The folders directly inside `dir/sub`, or none when it does not exist. */
function folders(dir, sub) {
  const at = path.join(dir, sub);
  if (!existsSync(at)) return [];
  return readdirSync(at).filter((name) => statSync(path.join(at, name)).isDirectory());
}

describe('community on, against two public recipes', { timeout: 300_000 }, () => {
  let api;
  let dir;
  before(async () => {
    api = await startPublicApi({ recipes: RECIPES, profiles: [PROFILE] });
    const result = await build({ COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: api.url });
    assert.equal(result.status, 0, result.output);
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
});
