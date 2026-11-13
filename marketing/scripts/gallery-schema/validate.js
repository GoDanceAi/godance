#!/usr/bin/env node
// @ts-check
/**
 * Check every recipe in a gallery folder against the format, without
 * starting Astro. Also checks that no two recipes share a slug.
 *
 *   npm run validate               the configured folder: GALLERY_CONTENT_DIR
 *                                  when set, src/content/gallery otherwise
 *   npm run validate -- <folder>   another folder, relative to where you run it
 *
 * Exits 0 when every recipe is valid (an empty folder is valid), 1 when any
 * is not, naming each file and the rule it breaks, and 2 when misused.
 */
import path from 'node:path';
import { formatReport, readRecipeFolder, resolveGalleryDir } from './read-recipes.js';

const USAGE = `Usage: npm run validate [-- <folder>]

Checks every recipe (*.json) in the gallery folder against the recipe format.
Without a folder it checks GALLERY_CONTENT_DIR when set, src/content/gallery otherwise.`;

const args = process.argv.slice(2);

if (args.includes('--help') || args.includes('-h')) {
  console.log(USAGE);
} else if (args.length > 1 || args[0]?.startsWith('-')) {
  console.error(USAGE);
  process.exitCode = 2;
} else {
  const dir = args[0] ? path.resolve(args[0]) : resolveGalleryDir();
  const { recipes, problems } = await readRecipeFolder(dir);
  if (problems.length > 0) {
    console.error(formatReport(dir, problems));
    process.exitCode = 1;
  } else {
    console.log(`gallery: ${recipes.length} recipe${recipes.length === 1 ? '' : 's'} in ${dir}, all valid`);
  }
}
