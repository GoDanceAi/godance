#!/usr/bin/env node
// @ts-check
/**
 * The gate of .github/workflows/open-list.yml: whether this run publishes
 * the open list. It publishes only when all three of these hold:
 *
 *   - the repository variable OPEN_LIST_REPO names the list's repository
 *   - the secret OPEN_LIST_DEPLOY_KEY is set
 *   - the gallery is on
 *
 * Otherwise the run is skipped, and succeeds, and the log has one line for
 * each condition that is not met. A value that is set but malformed fails the
 * run instead, so that a typo is seen rather than skipped over.
 *
 * The workflow runs exactly this, from marketing/, before anything is
 * installed, so it imports nothing that `npm ci` provides:
 *
 *   OPEN_LIST_REPO=<the variable, or empty> \
 *   OPEN_LIST_DEPLOY_KEY_SET=<"true" when the secret is set, "false" when not> \
 *   node scripts/open-list/gate.js
 *
 * The secret itself never reaches this step: the workflow tells it only
 * whether the secret is set. The answer goes to $GITHUB_OUTPUT as
 * publish=true or publish=false, and is printed when that is not set.
 */
import { appendFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolveGalleryConfig } from '../../src/lib/gallery-config.ts';
import { countRecipeFiles, galleryDir, galleryOffReasons } from './switch.js';

/** The marketing project root. */
const ROOT = fileURLToPath(new URL('../../', import.meta.url));

/** A repository as GitHub names it: owner/name. */
const REPOSITORY = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})\/[A-Za-z0-9._-]{1,100}$/;

/** A value that is set but cannot be right. The run fails instead of skipping. */
export class GateError extends Error {}

/**
 * Where each of the gallery's missing inputs is set, for the log.
 * @param {string} reason From galleryOffReasons.
 * @param {string} folder The gallery folder, as the log names it.
 */
function whereSet(reason, folder) {
  if (reason === 'no recipes') return `no recipes in ${folder}`;
  return `${reason} in marketing/src/lib/gallery-config.ts`;
}

/**
 * Decide. Each condition gives one line: "ok" when it holds, "skip" when it
 * does not.
 *
 * @param {Record<string, string | undefined>} env
 * @param {{ root?: string }} [options]
 * @returns {Promise<{ publish: boolean, lines: string[] }>}
 */
export async function decide(env, { root = ROOT } = {}) {
  /** @type {string[]} */
  const lines = [];
  let met = 0;

  const repo = env.OPEN_LIST_REPO?.trim() ?? '';
  if (repo === '') {
    lines.push('open-list: skip: the repository variable OPEN_LIST_REPO is not set');
  } else if (!REPOSITORY.test(repo)) {
    throw new GateError(`the repository variable OPEN_LIST_REPO must name a repository as "owner/name", not "${repo}"`);
  } else if (env.GITHUB_REPOSITORY && repo.toLowerCase() === env.GITHUB_REPOSITORY.toLowerCase()) {
    throw new GateError(`OPEN_LIST_REPO names this repository (${repo}); the list is pushed to a repository of its own`);
  } else {
    lines.push(`open-list: ok: the repository variable OPEN_LIST_REPO is set: ${repo}`);
    met++;
  }

  const keySet = env.OPEN_LIST_DEPLOY_KEY_SET?.trim() ?? '';
  if (keySet === 'true') {
    lines.push('open-list: ok: the secret OPEN_LIST_DEPLOY_KEY is set');
    met++;
  } else if (keySet === 'false' || keySet === '') {
    lines.push('open-list: skip: the secret OPEN_LIST_DEPLOY_KEY is not set');
  } else {
    // Never echoed: it could be the key itself, passed here by mistake.
    throw new GateError('OPEN_LIST_DEPLOY_KEY_SET must be "true" or "false"');
  }

  let config;
  try {
    config = resolveGalleryConfig(env);
  } catch (error) {
    throw new GateError(error instanceof Error ? error.message : String(error));
  }
  const dir = galleryDir(root, env);
  const relative = path.relative(root, dir);
  const folder = relative.startsWith('..') || path.isAbsolute(relative) ? dir : `marketing/${relative.split(path.sep).join('/')}`;
  const off = galleryOffReasons(config, await countRecipeFiles(dir));
  if (off.length > 0) {
    lines.push(`open-list: skip: the gallery is off: ${off.map((reason) => whereSet(reason, folder)).join('; ')}`);
  } else {
    lines.push('open-list: ok: the gallery is on');
    met++;
  }

  const publish = met === 3;
  if (publish) {
    lines.push(`open-list: publishing the list to ${repo}`);
  } else {
    lines.push('open-list: skipped: nothing is generated and nothing is pushed, and this run succeeds');
    // Shown on the run's page by GitHub, so the reason is visible without opening the log.
    const reasons = lines.filter((line) => line.startsWith('open-list: skip: ')).map((line) => line.slice('open-list: skip: '.length));
    lines.push(`::notice title=Open list skipped::${reasons.join('; ')}`);
  }
  return { publish, lines };
}

/**
 * @param {Record<string, string | undefined>} [env]
 * @param {(line: string) => void} [log]
 * @returns {Promise<number>} The exit code.
 */
export async function main(env = process.env, log = (line) => console.log(line)) {
  let decision;
  try {
    decision = await decide(env);
  } catch (error) {
    if (!(error instanceof GateError)) throw error;
    log(`::error title=Open list gate::${error.message}`);
    return 1;
  }
  for (const line of decision.lines) log(line);
  if (env.GITHUB_OUTPUT) await appendFile(env.GITHUB_OUTPUT, `publish=${decision.publish}\n`);
  else log(`open-list: publish=${decision.publish} (GITHUB_OUTPUT is not set, so it is only printed)`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main();
}
