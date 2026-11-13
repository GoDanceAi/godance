/**
 * Content collections.
 *
 * `gallery` holds the recipes: one JSON file per recipe in
 * src/content/gallery/, or in the folder GALLERY_CONTENT_DIR names, so the
 * pages can be built against fixtures. The format is defined once, in
 * src/lib/gallery-schema.js.
 *
 * Astro loads and checks every collection at build, whether or not a page
 * reads it, so a malformed recipe fails `npm run build`.
 *
 * The folder is read by the same code as `npm run validate` rather than by
 * Astro's glob() loader, because glob():
 *  - never shows the schema the file name, so it cannot check that a slug
 *    equals it, and only warns when two recipes share a slug;
 *  - warns on every build while the folder is empty;
 *  - skips re-checking a file that has not changed since the last local
 *    build, even when the schema module has.
 */
import { defineCollection } from 'astro:content';
import type { Loader } from 'astro/loaders';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { recipeSchema } from './lib/gallery-schema.js';
import {
  formatReport,
  readRecipeFolder,
  resolveGalleryDir,
} from '../scripts/gallery-schema/read-recipes.js';

function recipeFolder(): Loader {
  return {
    name: 'gallery-recipes',
    async load({ config, store, parseData, generateDigest, watcher, logger }) {
      const root = fileURLToPath(config.root);
      const dir = resolveGalleryDir({ root });

      // All or nothing: the store only changes when every recipe is valid.
      async function sync() {
        const { recipes, problems } = await readRecipeFolder(dir);
        if (problems.length > 0) throw new Error(formatReport(dir, problems));

        store.clear();
        for (const { file, slug, data } of recipes) {
          const filePath = path.join(dir, file);
          store.set({
            id: slug,
            data: await parseData({ id: slug, data, filePath }),
            filePath: path.relative(root, filePath).split(path.sep).join('/'),
            digest: generateDigest(data),
          });
        }
      }

      await sync();

      // In dev, pick up recipes as they are added, edited or removed.
      if (!watcher) return;
      watcher.add(dir);
      const onChange = async (changed: string) => {
        if (!changed.endsWith('.json') || path.relative(dir, changed).startsWith('..')) return;
        try {
          await sync();
          logger.info(`Reloaded recipes from ${dir}`);
        } catch (error) {
          logger.error(error instanceof Error ? error.message : String(error));
        }
      };
      watcher.on('add', onChange);
      watcher.on('change', onChange);
      watcher.on('unlink', onChange);
    },
  };
}

export const collections = {
  gallery: defineCollection({
    loader: recipeFolder(),
    schema: recipeSchema,
  }),
};
