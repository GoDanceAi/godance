#!/usr/bin/env node
// @ts-check
/**
 * Push a generated open list to its own repository:
 *
 *   node scripts/open-list/publish.js --from <folder> --to <git URL> [--source <text>]
 *
 * Run by .github/workflows/open-list.yml once the gate has said yes and the
 * list has been generated. The workflow holds the deploy key in an ssh-agent
 * and pins GitHub's host keys (GIT_SSH_COMMAND); this script never sees the
 * key.
 *
 * - It clones the list's repository, shallow, and puts the generated files
 *   in place of the old ones: FILES in build.js, and no others, so anything
 *   else in that repository is left alone.
 * - It commits only when the list changed, with a message naming the recipes
 *   added and removed.
 * - It pushes that commit on top of what it cloned and never forces: if the
 *   repository moved on meanwhile, the push is refused and the run fails.
 *
 * Exits 0 when the list was pushed or had not changed, 1 when it fails, and
 * 2 when misused.
 */
import { spawnSync } from 'node:child_process';
import { access, copyFile, mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { FILES } from './build.js';

/** Who the commits are by: GitHub's own Actions account. */
const IDENTITY = [
  '-c', 'user.name=github-actions[bot]',
  '-c', 'user.email=41898282+github-actions[bot]@users.noreply.github.com',
  '-c', 'commit.gpgsign=false',
];

/**
 * @typedef {object} ListData What commitMessage reads of data/recipes.json.
 * @property {{ slug: string }[]} recipes
 */

/**
 * The commit message for a new version of the list: which recipes were added
 * and removed, by slug, and which changed. The subject names up to three of
 * each; the body names them all.
 *
 * @param {ListData | null} previous The list's data as it is in the repository, or null.
 * @param {ListData} next The list's data as generated.
 * @param {string} [source] Where it was generated from, e.g. "owner/repo@sha".
 */
export function commitMessage(previous, next, source = '') {
  const before = new Map((previous?.recipes ?? []).map((recipe) => [recipe.slug, JSON.stringify(recipe)]));
  const after = new Map(next.recipes.map((recipe) => [recipe.slug, JSON.stringify(recipe)]));
  const sorted = (/** @type {string[]} */ slugs) => [...slugs].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const added = sorted([...after.keys()].filter((slug) => !before.has(slug)));
  const removed = sorted([...before.keys()].filter((slug) => !after.has(slug)));
  const updated = sorted([...after.keys()].filter((slug) => before.has(slug) && before.get(slug) !== after.get(slug)));

  const name = (/** @type {string} */ verb, /** @type {string[]} */ slugs) =>
    slugs.length <= 3 ? `${verb} ${slugs.join(', ')}` : `${verb} ${slugs.length} recipes`;
  const parts = [];
  if (added.length > 0) parts.push(name('add', added));
  if (removed.length > 0) parts.push(name('remove', removed));
  if (parts.length === 0 && updated.length > 0) parts.push(name('update', updated));
  if (parts.length === 0) parts.push('update the text around the recipes');

  const body = [
    ...(added.length > 0 ? [`Added: ${added.join(', ')}`] : []),
    ...(removed.length > 0 ? [`Removed: ${removed.join(', ')}`] : []),
    ...(updated.length > 0 ? [`Updated: ${updated.join(', ')}`] : []),
    ...(source ? [`Generated from ${source}.`] : []),
  ];
  return `open list: ${parts.join('; ')}\n${body.length > 0 ? `\n${body.join('\n')}\n` : ''}`;
}

/**
 * Run git and return what it said; `ok` throws unless it succeeded.
 * @param {string[]} args
 * @param {{ cwd?: string, env: Record<string, string | undefined>, input?: string }} options
 */
function git(args, { cwd, env, input }) {
  const result = spawnSync('git', args, { cwd, env, input, encoding: 'utf8' });
  if (result.error) throw result.error;
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`.trim(),
    ok() {
      if (result.status !== 0) throw new Error(`git ${args.find((arg) => !arg.startsWith('-') && !arg.includes('=')) ?? ''} failed: ${this.output}`);
      return result.stdout;
    },
  };
}

/**
 * Publish the list in `from` to the repository at `to`.
 *
 * @param {object} options
 * @param {string} options.from The generator's output folder.
 * @param {string} options.to The list repository's git URL.
 * @param {string} [options.source] Where the list was generated from, for the commit message.
 * @param {Record<string, string | undefined>} [options.env] The environment git runs in.
 * @param {(line: string) => void} [options.log]
 * @param {() => Promise<void> | void} [options.beforePush] For tests: runs between the commit and the push.
 * @returns {Promise<{ pushed: boolean, branch?: string, message?: string }>}
 */
export async function publish({ from, to, source = '', env = process.env, log = (line) => console.log(line), beforePush }) {
  for (const file of FILES) {
    await access(path.join(from, file)).catch(() => {
      throw new Error(`${file} is missing from ${from}: generate the list first`);
    });
  }
  const next = JSON.parse(await readFile(path.join(from, 'data/recipes.json'), 'utf8'));

  const work = await mkdtemp(path.join(os.tmpdir(), 'open-list-publish-'));
  try {
    const repo = path.join(work, 'list');
    git(['-c', 'init.defaultBranch=main', 'clone', '--quiet', '--depth', '1', '--no-tags', '--', to, repo], { env }).ok();
    const branch = git(['symbolic-ref', '--short', 'HEAD'], { cwd: repo, env }).ok().trim();
    /** @type {ListData | null} */
    const previous = await readFile(path.join(repo, 'data/recipes.json'), 'utf8').then(JSON.parse, () => null);

    for (const file of FILES) {
      await mkdir(path.dirname(path.join(repo, file)), { recursive: true });
      await copyFile(path.join(from, file), path.join(repo, file));
    }
    git(['add', '--', ...FILES], { cwd: repo, env }).ok();
    const diff = git(['diff', '--cached', '--quiet'], { cwd: repo, env });
    if (diff.status === 0) {
      log(`open-list: the list on ${branch} is already this one, so nothing is committed or pushed`);
      return { pushed: false, branch };
    }
    if (diff.status !== 1) diff.ok();

    const message = commitMessage(previous, next, source);
    git([...IDENTITY, 'commit', '--quiet', '--file', '-'], { cwd: repo, env, input: message }).ok();
    await beforePush?.();
    // A plain push, which only ever adds to the branch: a branch that moved
    // on meanwhile refuses it, and nothing here overrides that.
    const push = git(['push', '--quiet', 'origin', `HEAD:refs/heads/${branch}`], { cwd: repo, env });
    if (push.status !== 0) {
      throw new Error(`the push to ${branch} was refused, and nothing was forced: ${push.output}`);
    }
    log(`open-list: pushed to ${branch}: ${message.split('\n')[0]}`);
    return { pushed: true, branch, message };
  } finally {
    await rm(work, { recursive: true, force: true });
  }
}

/**
 * @param {string[]} argv
 * @param {{ env?: Record<string, string | undefined>, log?: (line: string) => void }} [io]
 * @returns {Promise<number>}
 */
export async function main(argv, io = {}) {
  const env = io.env ?? process.env;
  const log = io.log ?? ((line) => console.log(line));
  let values;
  try {
    ({ values } = parseArgs({
      args: argv,
      options: { from: { type: 'string' }, to: { type: 'string' }, source: { type: 'string' } },
      strict: true,
      allowPositionals: false,
    }));
  } catch (error) {
    log(`open-list: ${error instanceof Error ? error.message : String(error)}`);
    return 2;
  }
  if (!values.from || !values.to) {
    log('Usage: node scripts/open-list/publish.js --from <folder> --to <git URL> [--source <text>]');
    return 2;
  }
  try {
    await publish({ from: path.resolve(values.from), to: values.to, source: values.source ?? '', env, log });
    return 0;
  } catch (error) {
    log(`open-list: ${error instanceof Error ? error.message : String(error)}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
