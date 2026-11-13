// @ts-check
/**
 * Reads a gallery folder and checks every recipe in it against the format
 * in src/lib/gallery-schema.js.
 *
 * The `validate` command and the content collection's loader both go
 * through here, so `npm run validate` and `npm run build` apply exactly the
 * same rules and report them in the same words.
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkRecipe, formatProblem, galleryContentDir } from '../../src/lib/gallery-schema.js';

/** @typedef {import('../../src/lib/gallery-schema.js').Recipe} Recipe */
/** @typedef {import('../../src/lib/gallery-schema.js').RecipeProblem} RecipeProblem */

/**
 * A problem, and the file it is in (relative to the gallery folder).
 * @typedef {RecipeProblem & { file: string }} FileProblem
 */

/**
 * A recipe that passed every check.
 * @typedef {object} RecipeFile
 * @property {string} file Relative to the gallery folder.
 * @property {string} slug
 * @property {Recipe} data
 */

/** The marketing project root: the folder that holds package.json. */
export const MARKETING_ROOT = fileURLToPath(new URL('../../', import.meta.url));

/**
 * The gallery folder as an absolute path. `GALLERY_CONTENT_DIR` overrides
 * the default; a relative value is relative to the marketing project root,
 * the same as Astro resolves its own paths.
 *
 * @param {{ root?: string, env?: Record<string, string | undefined> }} [options]
 */
export function resolveGalleryDir({ root = MARKETING_ROOT, env = process.env } = {}) {
  return path.resolve(root, galleryContentDir(env));
}

/**
 * Every .json file under `dir`, as forward-slash paths relative to it.
 * Subfolders are searched too, so that a recipe put in one is reported
 * rather than silently left out.
 *
 * @param {string} dir
 * @param {string} [prefix]
 * @returns {Promise<string[]>}
 */
async function listJsonFiles(dir, prefix = '') {
  /** @type {string[]} */
  const found = [];
  for (const entry of await readdir(path.join(dir, prefix), { withFileTypes: true })) {
    const relative = prefix === '' ? entry.name : `${prefix}/${entry.name}`;
    if (entry.isDirectory()) found.push(...(await listJsonFiles(dir, relative)));
    else if (entry.isFile() && entry.name.endsWith('.json')) found.push(relative);
  }
  return found.sort();
}

/**
 * Read and check every recipe in a folder. Nothing is thrown for a bad
 * recipe: every problem is returned, each naming its file and its rule.
 * An empty folder is valid. Files that are not .json are ignored.
 *
 * @param {string} dir
 * @returns {Promise<{ recipes: RecipeFile[], problems: FileProblem[] }>}
 */
export async function readRecipeFolder(dir) {
  const isFolder = await stat(dir).then(
    (stats) => stats.isDirectory(),
    () => false,
  );
  if (!isFolder) {
    return {
      recipes: [],
      problems: [{ file: '.', rule: 'folder', path: '', message: 'the gallery folder does not exist' }],
    };
  }

  /** @type {FileProblem[]} */
  const problems = [];
  /** @type {{ file: string, data: unknown }[]} */
  const parsed = [];

  for (const file of await listJsonFiles(dir)) {
    const contents = await readFile(path.join(dir, file), 'utf8');
    /** @type {unknown} */
    let data;
    try {
      data = JSON.parse(contents);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      problems.push({ file, rule: 'json', path: '', message: `not valid JSON: ${reason}` });
      continue;
    }
    parsed.push({ file, data });
    for (const problem of checkRecipe(data, { file })) problems.push({ file, ...problem });
  }

  // No two recipes share a slug, valid or not.
  /** @type {Map<string, string[]>} */
  const filesBySlug = new Map();
  for (const { file, data } of parsed) {
    const slug = typeof data === 'object' && data !== null && 'slug' in data ? data.slug : undefined;
    if (typeof slug === 'string') filesBySlug.set(slug, [...(filesBySlug.get(slug) ?? []), file]);
  }
  for (const [slug, files] of filesBySlug) {
    if (files.length < 2) continue;
    for (const file of files) {
      const others = files.filter((other) => other !== file).join(', ');
      problems.push({ file, rule: 'duplicate-slug', path: 'slug', message: `"${slug}" is also used by ${others}` });
    }
  }

  const failed = new Set(problems.map((problem) => problem.file));
  /** @type {RecipeFile[]} */
  const recipes = [];
  for (const { file, data } of parsed) {
    if (failed.has(file)) continue;
    const recipe = /** @type {Recipe} */ (data);
    recipes.push({ file, slug: recipe.slug, data: recipe });
  }

  return { recipes, problems };
}

/**
 * The problems as text, grouped by file:
 *
 *   gallery: 2 problems in 1 file under <dir>
 *
 *     some-recipe.json
 *       [prompt-languages] prompt.zh: required, and must not be empty
 *       [licence] source.licence: must be one of CC0-1.0, CC-BY-4.0, MIT, Apache-2.0
 *
 * @param {string} dir
 * @param {FileProblem[]} problems
 */
export function formatReport(dir, problems) {
  /** @type {Map<string, FileProblem[]>} */
  const byFile = new Map();
  for (const problem of problems) byFile.set(problem.file, [...(byFile.get(problem.file) ?? []), problem]);

  const count = (/** @type {number} */ n, /** @type {string} */ noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;
  const lines = [`gallery: ${count(problems.length, 'problem')} in ${count(byFile.size, 'file')} under ${dir}`];
  for (const [file, fileProblems] of [...byFile].sort(([a], [b]) => a.localeCompare(b))) {
    lines.push('', `  ${file}`);
    for (const problem of fileProblems) lines.push(`    ${formatProblem(problem)}`);
  }
  return lines.join('\n');
}
