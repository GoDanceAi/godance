// @ts-check
/**
 * Whether the gallery is on, decided the way the site decides it
 * (`loadGallery` in src/lib/gallery-recipes.ts): the media base URL and the
 * removal contact are both set, and at least one recipe exists.
 *
 * Nothing here needs `npm ci`, so the workflow's gate can ask before
 * anything is installed. The generator asks the same question with the
 * recipes it read through the gallery's reader.
 */
import { readdir } from 'node:fs/promises';
import path from 'node:path';

/**
 * The same two names as in src/lib/gallery-schema.js, which the gate cannot
 * import before `npm ci` (it loads Zod). gate.test.js holds them equal.
 */
export const GALLERY_DIR_ENV = 'GALLERY_CONTENT_DIR';
export const DEFAULT_GALLERY_DIR = 'src/content/gallery';

/**
 * Why the gallery is off: one reason for each missing input, in the words a
 * log line uses. Empty when it is on.
 *
 * @param {{ mediaBaseUrl: string, removalContact: string }} config As resolveGalleryConfig returns it.
 * @param {number} recipes How many recipes the gallery folder holds.
 * @returns {string[]}
 */
export function galleryOffReasons(config, recipes) {
  const reasons = [];
  if (config.mediaBaseUrl === '') reasons.push('no media base URL');
  if (config.removalContact === '') reasons.push('no removal contact');
  if (recipes === 0) reasons.push('no recipes');
  return reasons;
}

/**
 * The gallery folder as an absolute path: GALLERY_CONTENT_DIR when set, the
 * default otherwise, relative to the marketing project root.
 *
 * @param {string} root
 * @param {Record<string, string | undefined>} env
 */
export function galleryDir(root, env) {
  return path.resolve(root, env[GALLERY_DIR_ENV]?.trim() || DEFAULT_GALLERY_DIR);
}

/**
 * How many recipe files a gallery folder holds: every .json file in it or
 * below it, the files the gallery's reader reads. None when it does not exist.
 *
 * @param {string} dir
 * @returns {Promise<number>}
 */
export async function countRecipeFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => []);
  let count = 0;
  for (const entry of entries) {
    if (entry.isDirectory()) count += await countRecipeFiles(path.join(dir, entry.name));
    else if (entry.isFile() && entry.name.endsWith('.json')) count++;
  }
  return count;
}
