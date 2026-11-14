/**
 * publish: write the recipes the operator approved. remove: take one down.
 *
 * Only the operator approves. Nothing in this tool writes the decisions
 * file; `publish` reads it and writes a recipe for each entry marked
 * approved there, and for nothing else.
 *
 * Every recipe is checked with the schema module before it is written
 * (src/lib/gallery-schema.js is the only definition of the format), and a
 * slug that another recipe already has is refused. Media is never
 * committed: a manifest in the work folder lists what to upload.
 */
import { existsSync } from 'node:fs';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { SLUG_PATTERN, checkRecipe, formatProblem, recipeFileName } from '../../../src/lib/gallery-schema.js';
import { postKey } from '../lib/text.js';
import { readJson, today, writeJson } from '../lib/work.js';

/** The recipe for one approved entry, key for key in the order the format lists them. */
export function buildRecipe({ entry, translated, run, decision, standardVersion, publishedOn }) {
  return {
    slug: translated.slug,
    title: translated.title,
    modality: entry.modality,
    tags: entry.category ? [entry.category] : [],
    prompt: translated.prompt,
    runs: [
      {
        model: run.model,
        prompt_lang: run.prompt_lang,
        params: run.params,
        cost_cents: run.cost_cents,
        generated_on: run.generated_on,
        outputs: run.outputs,
      },
    ],
    source: {
      list: entry.list,
      list_commit: entry.list_commit,
      licence: entry.licence,
      author: entry.author,
      author_url: entry.author_url,
      original_url: entry.original_url,
      changes: translated.changes,
    },
    review: { approved_on: decision.decided_on, standard_version: standardVersion },
    published_on: publishedOn,
  };
}

/** Every media path a recipe refers to. */
export const mediaOf = (recipe) =>
  recipe.runs.flatMap((run) => run.outputs.flatMap((output) => [output.file, output.thumb, output.poster].filter(Boolean)));

/**
 * @param {object} options
 * @param {object[]} options.entries
 * @param {Record<string, object>} options.translated
 * @param {Record<string, object>} options.runs
 * @param {{ standard_version: number, decisions: Record<string, object> }} options.decisions The operator's file.
 * @param {string} options.contentDir Where recipes live.
 * @param {string} options.manifestFile
 * @param {boolean} [options.apply] Write the files. Without it, only report.
 * @returns {Promise<{ written: string[], already: string[], refused: { slug: string, why: string[] }[], media: string[] }>}
 */
export async function publish({ entries, translated, runs, decisions, contentDir, manifestFile, apply = false, now = () => new Date() }) {
  const result = { written: [], already: [], refused: [], media: [] };
  const standardVersion = decisions?.standard_version;
  const byId = new Map(entries.map((entry) => [entry.id, entry]));
  const claimed = new Set();

  for (const [id, decision] of Object.entries(decisions?.decisions ?? {})) {
    if (decision.decision !== 'approved') continue;
    const entry = byId.get(id);
    const record = translated[id];
    const run = runs[id];
    const slug = record?.slug ?? decision.slug ?? id;
    if (!entry || record?.status !== 'done' || run?.status !== 'done') {
      result.refused.push({ slug, why: ['it is approved, but it has no finished run to publish'] });
      continue;
    }

    const recipe = buildRecipe({ entry, translated: record, run, decision, standardVersion, publishedOn: today(now()) });
    const fileName = recipeFileName(recipe.slug);
    const problems = checkRecipe(recipe, { file: fileName });
    if (problems.length > 0) {
      result.refused.push({ slug, why: problems.map(formatProblem) });
      continue;
    }

    const target = path.join(contentDir, fileName);
    if (existsSync(target)) {
      const existing = await readJson(target).catch(() => null);
      if (existing && postKey(existing.source?.original_url ?? '') === id) result.already.push(recipe.slug);
      else result.refused.push({ slug, why: [`the slug is already taken by ${fileName}`] });
      continue;
    }
    if (claimed.has(recipe.slug)) {
      result.refused.push({ slug, why: ['the slug is already taken by another recipe in this batch'] });
      continue;
    }
    claimed.add(recipe.slug);

    if (apply) {
      await mkdir(contentDir, { recursive: true });
      await writeFile(target, `${JSON.stringify(recipe, null, 2)}\n`);
    }
    result.written.push(recipe.slug);
    result.media.push(...mediaOf(recipe));
  }

  if (apply && result.media.length > 0) {
    const manifest = await readJson(manifestFile, { upload: [], delete: [] });
    manifest.upload = [...new Set([...manifest.upload, ...result.media])].sort();
    manifest.delete = manifest.delete.filter((file) => !result.media.includes(file));
    await writeJson(manifestFile, manifest);
  }
  return result;
}

/**
 * Take a recipe down: delete its file, put its original post on the
 * committed removal list so a later import cannot bring it back, and list
 * its media for deletion from public storage.
 */
export async function remove(slug, { contentDir, removalFile, manifestFile, reason = '', apply = false, now = () => new Date() }) {
  if (typeof slug !== 'string' || !SLUG_PATTERN.test(slug)) throw new Error(`remove: "${slug}" is not a slug`);
  const target = path.join(contentDir, recipeFileName(slug));
  let recipe;
  try {
    recipe = JSON.parse(await readFile(target, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') throw new Error(`remove: there is no recipe ${recipeFileName(slug)} in ${contentDir}`);
    throw error;
  }
  const originalUrl = recipe.source?.original_url;
  if (!originalUrl) throw new Error(`remove: ${recipeFileName(slug)} names no original post, so it cannot be kept from coming back`);
  const media = Array.isArray(recipe.runs) ? mediaOf(recipe) : [];

  if (apply) {
    const list = await readJson(removalFile, { removed: [] });
    if (!list.removed.some((item) => postKey(item.original_url) === postKey(originalUrl))) {
      list.removed.push({ original_url: originalUrl, slug, removed_on: today(now()), ...(reason ? { reason } : {}) });
      await writeJson(removalFile, list);
    }
    await rm(target);
    const manifest = await readJson(manifestFile, { upload: [], delete: [] });
    manifest.upload = manifest.upload.filter((file) => !media.includes(file));
    manifest.delete = [...new Set([...manifest.delete, ...media])].sort();
    await writeJson(manifestFile, manifest);
  }
  return { slug, original_url: originalUrl, media };
}
