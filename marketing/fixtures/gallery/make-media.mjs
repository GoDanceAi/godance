// @ts-check
/**
 * Draws the placeholder images for the fixture recipes: every image output,
 * its thumbnail, and every video poster named in fixtures/gallery/recipes/.
 * The pictures are abstract shapes made here, coloured from the recipe's
 * slug. Nothing is downloaded or copied from anywhere.
 *
 *   node fixtures/gallery/make-media.mjs
 *
 * The two tiny .webm clips were recorded once from a canvas animation and
 * are committed as they are; this script leaves them alone.
 */
import { mkdir, readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const here = path.dirname(fileURLToPath(import.meta.url));
const recipesDir = path.join(here, 'recipes');
const mediaDir = path.join(here, 'media');

/** A small stable number from a string. @param {string} text */
function hash(text) {
  let h = 2166136261;
  for (const ch of text) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  return h;
}

/**
 * An abstract picture: a two-colour gradient, a disc and a band.
 * @param {string} seed @param {number} width @param {number} height
 */
function picture(seed, width, height) {
  const h = hash(seed);
  const hue = h % 360;
  const cx = 20 + (h >>> 8) % 60;
  const cy = 20 + (h >>> 16) % 60;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 100 100" preserveAspectRatio="none">
  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="hsl(${hue} 70% 62%)"/>
    <stop offset="1" stop-color="hsl(${(hue + 70) % 360} 65% 32%)"/>
  </linearGradient></defs>
  <rect width="100" height="100" fill="url(#g)"/>
  <circle cx="${cx}" cy="${cy}" r="18" fill="hsl(${(hue + 180) % 360} 80% 78%)" opacity="0.85"/>
  <rect x="0" y="${70 + (h % 12)}" width="100" height="9" fill="hsl(${(hue + 30) % 360} 40% 18%)" opacity="0.55"/>
</svg>`;
}

/** Write one WebP no wider or taller than `limit`, in the shape given. */
async function draw(/** @type {string} */ file, /** @type {number} */ width, /** @type {number} */ height, /** @type {number} */ limit) {
  const scale = Math.min(1, limit / Math.max(width, height));
  const w = Math.round(width * scale);
  const h = Math.round(height * scale);
  const out = path.join(mediaDir, file);
  await mkdir(path.dirname(out), { recursive: true });
  await sharp(Buffer.from(picture(file.replace(/-480\.webp$|-poster\.webp$/, ''), w, h))).webp({ quality: 60 }).toFile(out);
  console.log(`${file}  ${w}x${h}`);
}

for (const name of (await readdir(recipesDir)).filter((n) => n.endsWith('.json')).sort()) {
  const recipe = JSON.parse(await readFile(path.join(recipesDir, name), 'utf8'));
  for (const run of recipe.runs) {
    for (const output of run.outputs) {
      if (recipe.modality === 'IMAGE') {
        // Stand-ins are far smaller than the sizes the recipes state.
        await draw(output.file, output.width, output.height, 512);
        await draw(output.thumb, output.width, output.height, 240);
      } else if (output.poster) {
        await draw(output.poster, output.width, output.height, 320);
      }
    }
  }
}
