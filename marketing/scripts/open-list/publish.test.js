/**
 * Publishing the list to its repository (publish.js), against local bare
 * repositories in the system's temp folder, with git's own settings and the
 * user's kept out. The workflow does the same over SSH with a deploy key.
 *
 * It commits only when the list changed, names the recipes added and
 * removed, leaves alone what the list does not own, and never forces a push.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { FILES } from './build.js';
import { commitMessage, main, publish } from './publish.js';

const FIXTURES = fileURLToPath(new URL('./fixtures/recipes/', import.meta.url));
const FIXTURE_REMOVALS = fileURLToPath(new URL('./fixtures/removal-list.json', import.meta.url));
const ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: 'https://media.example.test/gallery',
  GALLERY_REMOVAL_CONTACT: 'removals@example.test',
  GALLERY_OUTPUT_LICENCE: 'Fixture statement: outputs may be reused freely.',
};

/** A folder in the system's temp folder, removed when the test ends. */
async function temp(t, prefix) {
  const dir = await mkdtemp(path.join(os.tmpdir(), prefix));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

/** An environment for git with no system or user settings, and no prompts. */
async function gitEnv(t) {
  const home = await temp(t, 'open-list-home-');
  const config = path.join(home, 'gitconfig');
  await writeFile(config, '');
  return {
    PATH: process.env.PATH,
    HOME: home,
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_CONFIG_GLOBAL: config,
    GIT_CEILING_DIRECTORIES: os.tmpdir(),
    GIT_TERMINAL_PROMPT: '0',
  };
}

const run = (env, cwd, ...args) => execFileSync('git', args, { cwd, env, encoding: 'utf8' });
const commit = (env, cwd, message) =>
  run(env, cwd, '-c', 'user.name=Someone', '-c', 'user.email=someone@example.test', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', message);

/** A bare repository, empty, as the operator creates it. */
async function remote(t, env) {
  const dir = path.join(await temp(t, 'open-list-remote-'), 'list.git');
  run(env, os.tmpdir(), 'init', '--quiet', '--bare', '--initial-branch', 'main', dir);
  return dir;
}

/** The generated list, with these extra slugs removed. */
async function generate(t, removeSlugs = []) {
  const dir = await temp(t, 'open-list-out-');
  let removalList = FIXTURE_REMOVALS;
  if (removeSlugs.length > 0) {
    const list = JSON.parse(await readFile(FIXTURE_REMOVALS, 'utf8'));
    list.removed.push(...removeSlugs.map((slug) => ({ slug, removed_on: '2026-11-15' })));
    removalList = path.join(dir, 'removal-list.json');
    await writeFile(removalList, JSON.stringify(list));
  }
  const out = path.join(dir, 'list');
  const { main: generateMain } = await import('./cli.js');
  const lines = [];
  const code = await generateMain(['--out', out, '--removal-list', removalList], { env: ON, log: (line) => lines.push(line) });
  assert.equal(code, 0, lines.join('\n'));
  return out;
}

/** Make a change in the remote from a clone of its own, as someone else would. */
async function pushFromElsewhere(t, env, bare, file, contents) {
  const clone = path.join(await temp(t, 'open-list-other-'), 'clone');
  run(env, os.tmpdir(), 'clone', '--quiet', bare, clone);
  await writeFile(path.join(clone, file), contents);
  run(env, clone, 'add', file);
  commit(env, clone, `add ${file}`);
  run(env, clone, 'push', '--quiet', 'origin', 'HEAD:main');
}

const quiet = () => {};
const log = (env, bare) => run(env, os.tmpdir(), '--git-dir', bare, 'log', '--format=%an%n%B%x00', 'main').split('\0\n').filter(Boolean);
const tree = (env, bare) => run(env, os.tmpdir(), '--git-dir', bare, 'ls-tree', '-r', '--name-only', 'main').trim().split('\n');
const show = (env, bare, file) => run(env, os.tmpdir(), '--git-dir', bare, 'show', `main:${file}`);

describe('publishing', () => {
  test('the first run puts the whole list in an empty repository, and its message names the recipes added', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    const list = await generate(t);
    const result = await publish({ from: list, to: bare, source: 'example-org/source@0123abc', env, log: quiet });
    assert.equal(result.pushed, true);
    assert.equal(result.branch, 'main');

    const [only, ...older] = log(env, bare);
    assert.equal(older.length, 0);
    const slugs = JSON.parse(await readFile(path.join(list, 'data/recipes.json'), 'utf8')).recipes.map((recipe) => recipe.slug).sort();
    assert.match(only, /^github-actions\[bot\]\nopen list: add 6 recipes\n/);
    assert.ok(only.includes(`\nAdded: ${slugs.join(', ')}\n`));
    assert.ok(only.includes('\nGenerated from example-org/source@0123abc.'));
    assert.deepEqual(tree(env, bare), [...FILES].sort());
    for (const file of FILES) assert.equal(show(env, bare, file), await readFile(path.join(list, file), 'utf8'), file);
  });

  test('the same list again changes nothing: no commit, no push', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    const list = await generate(t);
    await publish({ from: list, to: bare, env, log: quiet });
    const again = await publish({ from: list, to: bare, env, log: quiet });
    assert.equal(again.pushed, false);
    assert.equal(log(env, bare).length, 1);
  });

  test('a recipe taken down is named in the commit, and files the list does not own stay', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    await publish({ from: await generate(t), to: bare, env, log: quiet });
    await pushFromElsewhere(t, env, bare, 'NOTES.md', 'Kept by the operator.\n');

    const without = await generate(t, ['brass-compass-on-map']);
    const result = await publish({ from: without, to: bare, env, log: quiet });
    assert.equal(result.pushed, true);
    assert.match(log(env, bare)[0], /^github-actions\[bot\]\nopen list: remove brass-compass-on-map\n\nRemoved: brass-compass-on-map\n/);
    assert.ok(tree(env, bare).includes('NOTES.md'), 'the operator\'s file is kept');
    assert.ok(!show(env, bare, 'README.md').includes('brass-compass-on-map'));
  });

  test('if the repository moved on meanwhile, the push is refused and nothing is forced', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    await publish({ from: await generate(t), to: bare, env, log: quiet });
    const without = await generate(t, ['brass-compass-on-map']);

    await assert.rejects(
      publish({
        from: without,
        to: bare,
        env,
        log: quiet,
        beforePush: () => pushFromElsewhere(t, env, bare, 'OTHER.md', 'Pushed while the list was being published.\n'),
      }),
      /the push to main was refused, and nothing was forced/,
    );
    const [latest] = log(env, bare);
    assert.match(latest, /^Someone\nadd OTHER\.md/, 'the other push stands');
    assert.ok(show(env, bare, 'README.md').includes('brass-compass-on-map'), 'the list there is unchanged');
  });

  test('a list that was not generated in full is not published', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    const list = await generate(t);
    await rm(path.join(list, 'LICENSE.md'));
    await assert.rejects(publish({ from: list, to: bare, env, log: quiet }), /LICENSE\.md is missing/);
    assert.throws(() => log(env, bare), 'nothing was pushed');
  });

  test('from the command line', async (t) => {
    const env = await gitEnv(t);
    const bare = await remote(t, env);
    const list = await generate(t);
    const lines = [];
    assert.equal(await main(['--from', list, '--to', bare, '--source', 'example-org/source@0123abc'], { env, log: (line) => lines.push(line) }), 0);
    assert.match(lines.join('\n'), /^open-list: pushed to main: open list: add 6 recipes$/m);
    assert.equal(await main(['--from', list], { env, log: quiet }), 2);
    assert.equal(await main(['--from', list, '--to', bare, '--force'], { env, log: quiet }), 2);
  });
});

describe('the commit message', () => {
  const data = (...slugs) => ({ recipes: slugs.map((slug) => ({ slug, title: { en: slug } })) });

  test('names the recipes added and removed, up to three in the subject and all in the body', () => {
    assert.equal(commitMessage(null, data('b', 'a')), 'open list: add a, b\n\nAdded: a, b\n');
    assert.equal(commitMessage(data('a', 'b'), data('a', 'c')), 'open list: add c; remove b\n\nAdded: c\nRemoved: b\n');
    assert.equal(
      commitMessage(data('a'), data('a', 'b', 'c', 'd', 'e'), 'owner/repo@abc1234'),
      'open list: add 4 recipes\n\nAdded: b, c, d, e\nGenerated from owner/repo@abc1234.\n',
    );
  });

  test('names a recipe that changed when none was added or removed, and says so when only the text around them did', () => {
    const before = data('a', 'b');
    const after = { recipes: [{ slug: 'a', title: { en: 'A, retitled' } }, before.recipes[1]] };
    assert.equal(commitMessage(before, after), 'open list: update a\n\nUpdated: a\n');
    assert.equal(commitMessage(before, data('a', 'b')), 'open list: update the text around the recipes\n');
  });
});
