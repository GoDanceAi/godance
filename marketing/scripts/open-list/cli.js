#!/usr/bin/env node
// @ts-check
/**
 * Generate the open recipe list from the gallery:
 *
 *   npm run open-list -- --out <folder> [--removal-list <file>]
 *
 * The recipes are read through the same reader as `npm run validate`
 * (GALLERY_CONTENT_DIR when set, src/content/gallery otherwise), and the
 * switch from src/lib/gallery-config.ts with the same GALLERY_* overrides as
 * the build. It writes README.md, README_zh.md, data/recipes.json,
 * LICENSE.md and CONTRIBUTING.md to <folder>, and nothing else.
 *
 * - It refuses, and writes nothing, while the gallery is off: it lists only
 *   what the gallery shows.
 * - A recipe on the removal list (scripts/gallery/config/removal-list.json,
 *   or --removal-list) is never written, whether its original post or its
 *   slug is the one listed.
 * - SITE_URL and PUBLIC_APP_URL move its links, as they move the site's own.
 *
 * Relative paths are relative to where you run npm. Exits 0 when the list is
 * written, 1 when it is refused or fails, and 2 when misused.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { resolveGalleryConfig } from '../../src/lib/gallery-config.ts';
import { formatReport, readRecipeFolder, resolveGalleryDir } from '../gallery-schema/read-recipes.js';
import { postKey } from '../gallery/lib/text.js';
import { configPaths } from '../gallery/lib/work.js';
import { FILES, buildList } from './build.js';
import { galleryOffReasons } from './switch.js';

/** @typedef {import('../../src/lib/gallery-schema.js').Recipe} Recipe */

/** The committed removal list, written by the import tool's `remove`. */
export const DEFAULT_REMOVAL_LIST = configPaths().removalList;

/** Slugs the gallery refuses, because its JSON endpoints use them (src/lib/gallery-recipes.ts). */
const RESERVED_SLUGS = ['index'];

const USAGE = `Usage: npm run open-list -- --out <folder> [--removal-list <file>]

Writes the open recipe list (${FILES.join(', ')}) to <folder>,
from the gallery's recipes. Refuses while the gallery is off.

  --out <folder>          where to write the list (created if needed)
  --removal-list <file>   the removal list to honour; default:
                          scripts/gallery/config/removal-list.json`;

/** The run cannot go ahead, and nothing was written. */
class Refusal extends Error {}

/**
 * An origin from the environment, or its default, without a trailing slash.
 * @param {Record<string, string | undefined>} env @param {string} name @param {string} fallback
 */
function originFrom(env, name, fallback) {
  const value = env[name]?.trim() || fallback;
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Refusal(`${name} must be an absolute http or https URL, not "${value}"`);
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Refusal(`${name} must be an absolute http or https URL, not "${value}"`);
  }
  return parsed.href.replace(/\/+$/, '');
}

/**
 * The removal list: the original posts and the slugs it names.
 * @param {string} file
 */
async function readRemovalList(file) {
  /** @type {unknown} */
  let data;
  try {
    data = JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    throw new Refusal(`cannot read the removal list ${file}: ${error instanceof Error ? error.message : String(error)}`);
  }
  const removed = typeof data === 'object' && data !== null && 'removed' in data ? data.removed : undefined;
  if (!Array.isArray(removed)) throw new Refusal(`the removal list ${file} must be { "removed": [ … ] }`);
  /** @type {Set<string>} */
  const posts = new Set();
  /** @type {Set<string>} */
  const slugs = new Set();
  removed.forEach((item, i) => {
    const url = typeof item?.original_url === 'string' ? item.original_url : null;
    const slug = typeof item?.slug === 'string' ? item.slug : null;
    const key = url === null ? null : postKey(url);
    if (key === null && slug === null) {
      throw new Refusal(`the removal list ${file}: removed[${i}] names neither an original_url nor a slug`);
    }
    if (key !== null) posts.add(key);
    if (slug !== null) slugs.add(slug);
  });
  return { posts, slugs };
}

/**
 * Write the list, or refuse.
 * @param {{ env: Record<string, string | undefined>, out: string, removalList: string, log: (line: string) => void }} options
 */
async function writeList({ env, out, removalList, log }) {
  const config = resolveGalleryConfig(env);
  const settings = {
    ...config,
    site: originFrom(env, 'SITE_URL', 'https://godance.ai'),
    app: originFrom(env, 'PUBLIC_APP_URL', 'https://app.godance.ai'),
  };

  // As the site does it, the recipes are read only once the switch is set:
  // first the two settings (the count stands in until there are recipes to
  // count), then whether there is a recipe at all.
  const unset = galleryOffReasons(config, /* recipes, counted below */ 1);
  if (unset.length > 0) {
    throw new Refusal(
      `the gallery is off (${unset.join(', ')}), so there is no list to write. ` +
        'The operator switches it on in src/lib/gallery-config.ts (or GALLERY_MEDIA_BASE_URL and GALLERY_REMOVAL_CONTACT).',
    );
  }
  const dir = resolveGalleryDir({ env });
  const { recipes, problems } = await readRecipeFolder(dir);
  if (problems.length > 0) throw new Refusal(formatReport(dir, problems));
  if (recipes.length === 0) throw new Refusal(`the gallery is off (no recipes in ${dir}), so there is no list to write`);

  const removal = await readRemovalList(removalList);
  /** @type {Recipe[]} */
  const listed = [];
  /** @type {string[]} */
  const left = [];
  for (const { data: recipe } of recipes) {
    const post = postKey(recipe.source.original_url);
    if (removal.slugs.has(recipe.slug) || (post !== null && removal.posts.has(post))) left.push(recipe.slug);
    else listed.push(recipe);
  }
  const reserved = listed.find((recipe) => RESERVED_SLUGS.includes(recipe.slug));
  if (reserved) {
    throw new Refusal(`the slug "${reserved.slug}" is taken by /gallery/${reserved.slug}.json, so the gallery refuses this recipe`);
  }

  const files = buildList(listed, settings);
  await mkdir(path.join(out, 'data'), { recursive: true });
  for (const [name, contents] of files) await writeFile(path.join(out, name), contents);

  log(`open-list: ${listed.length} recipe${listed.length === 1 ? '' : 's'} written to ${out}`);
  if (left.length > 0) log(`open-list: left out, on the removal list: ${left.sort().join(', ')}`);
}

/**
 * The command line. `io.env` stands in for the environment and `io.log`
 * for the console, so that tests can run it in-process.
 *
 * @param {string[]} argv
 * @param {{ env?: Record<string, string | undefined>, log?: (line: string) => void }} [io]
 * @returns {Promise<number>} The exit code.
 */
export async function main(argv, io = {}) {
  const env = io.env ?? process.env;
  const log = io.log ?? ((line) => console.log(line));

  let values;
  try {
    ({ values } = parseArgs({
      args: argv,
      options: { out: { type: 'string' }, 'removal-list': { type: 'string' }, help: { type: 'boolean', short: 'h' } },
      strict: true,
      allowPositionals: false,
    }));
  } catch (error) {
    log(`open-list: ${error instanceof Error ? error.message : String(error)}\n\n${USAGE}`);
    return 2;
  }
  if (values.help) {
    log(USAGE);
    return 0;
  }
  if (!values.out) {
    log(`open-list: --out is required\n\n${USAGE}`);
    return 2;
  }

  // npm runs scripts from the package folder; INIT_CWD is where it was run.
  const from = env.INIT_CWD || process.cwd();
  try {
    await writeList({
      env,
      out: path.resolve(from, values.out),
      removalList: values['removal-list'] ? path.resolve(from, values['removal-list']) : DEFAULT_REMOVAL_LIST,
      log,
    });
    return 0;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    log(error instanceof Refusal ? `open-list: refused: ${message}. Nothing was written.` : `open-list: ${message}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
