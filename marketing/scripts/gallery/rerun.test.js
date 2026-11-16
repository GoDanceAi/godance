/**
 * rerun: add a run on another model to recipes that are already published.
 * It is a paid stage like generate: a dry run unless --apply is given, and
 * never without --max-spend-cents. The API is mocked: nothing here touches
 * the network or spends anything.
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { main } from './cli.js';
import { API_BASE, CATALOGUE, FAKE_KEY, configDir, fakeWorld, noSleep, tempDir } from './testing.js';

/** Published recipes in the gallery format, invented for the page tests. */
const FIXTURES = fileURLToPath(new URL('../../fixtures/gallery/recipes/', import.meta.url));

/**
 * An image model none of those recipes was run on, as the catalogue seeds it
 * (V11, V24): 35000 microcents an image, the 12% image fee, a default size and
 * no list of sizes.
 */
const SEEDREAM = {
  id: 'seedream-5.0',
  owned_by: 'volcengine-ark',
  godance: { modality: 'IMAGE', pricing: { per_image_microcents: 35000 }, markup_percent: 12, capabilities: { default_size: '1024x1024' } },
};

/** A work folder, a config folder and a gallery folder holding these published recipes, and a way to run the tool in them. */
async function workspace(t, { recipes, models = [...CATALOGUE, SEEDREAM] }) {
  const root = await tempDir(t);
  const dirs = { work: path.join(root, 'work'), content: path.join(root, 'content'), config: await configDir(t) };
  await mkdir(dirs.content, { recursive: true });
  for (const slug of recipes) await copyFile(path.join(FIXTURES, `${slug}.json`), path.join(dirs.content, `${slug}.json`));
  const world = fakeWorld({ models });
  const cli = async (...args) => {
    const lines = [];
    const code = await main([...args, '--work-dir', dirs.work, '--config-dir', dirs.config, '--content-dir', dirs.content], {
      env: { GODANCE_API_BASE: API_BASE, GODANCE_API_KEY: FAKE_KEY },
      fetch: world.fetch,
      out: (line) => lines.push(line),
      sleep: noSleep,
      now: () => new Date('2026-11-22T09:00:00Z'),
    });
    return { code, text: lines.join('\n') };
  };
  return { dirs, world, cli };
}

/** The text of each recipe file, by slug. */
const recipeFiles = async (dir, slugs) =>
  Object.fromEntries(await Promise.all(slugs.map(async (slug) => [slug, await readFile(path.join(dir, `${slug}.json`), 'utf8')])));

describe('rerun, as a dry run', () => {
  test('plans one job per recipe on the model named, and spends nothing', async (t) => {
    const published = ['tin-robot-tea-party', 'moss-covered-typewriter', 'glass-whale-over-rooftops'];
    const { dirs, world, cli } = await workspace(t, { recipes: published });
    const before = await recipeFiles(dirs.content, published);

    const { code, text } = await cli('rerun', '--models', 'seedream-5.0', '--slugs', 'tin-robot-tea-party,moss-covered-typewriter', '--max-spend-cents', '100');

    assert.equal(code, 0, text);
    assert.match(text, /^gallery rerun: dry run\. Nothing is written and no paid call is made\. Add --apply to do it\.$/m);
    // One job for each recipe asked for, on the model asked for, and none for the recipe that was not asked for.
    for (const slug of ['tin-robot-tea-party', 'moss-covered-typewriter']) {
      assert.equal(text.match(new RegExp(`^ +\\+ ${slug} on seedream-5\\.0\\b`, 'gm'))?.length, 1, `one job for ${slug}\n\n${text}`);
    }
    assert.doesNotMatch(text, /glass-whale-over-rooftops/);
    // seedream-5.0 holds 4 cents an image: 35000 microcents and the 12% fee, rounded up.
    assert.match(text, /^2 new runs would start, holding 8 cents/m);

    // Nothing is spent: the free, public catalogue is the only request, and it carries no key.
    assert.deepEqual(world.requests.map((request) => `${request.method} ${request.url} ${request.authorization}`), [`GET ${API_BASE}/v1/models null`]);
    assert.equal(existsSync(dirs.work), false, 'a dry run writes nothing: no state and no ledger');
    assert.deepEqual(await recipeFiles(dirs.content, published), before, 'the published recipes are as they were');
  });
});
