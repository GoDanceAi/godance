/**
 * The work folder (marketing/.gallery-work/, git-ignored) and the committed
 * configuration next to the tool. Each stage keeps its state in one JSON
 * file, written whole and atomically, so a stage that is interrupted can be
 * run again and picks up where it stopped.
 */
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const TOOL_ROOT = fileURLToPath(new URL('../', import.meta.url));
export const MARKETING_ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const DEFAULT_WORK_DIR = path.join(MARKETING_ROOT, '.gallery-work');
export const DEFAULT_CONFIG_DIR = path.join(TOOL_ROOT, 'config');
export const DEFAULT_CONTENT_DIR = path.join(MARKETING_ROOT, 'src/content/gallery');

/** Where everything in a work folder lives. */
export function workPaths(root = DEFAULT_WORK_DIR) {
  return {
    root,
    sources: path.join(root, 'sources'), // the lists' text at their pinned commits
    entries: path.join(root, 'entries.json'), // fetch
    screened: path.join(root, 'screened.json'), // screen
    translated: path.join(root, 'translated.json'), // translate
    runs: path.join(root, 'runs.json'), // generate: one record per entry, job id included
    ledger: path.join(root, 'ledger.json'), // every cent spent, by any stage
    media: path.join(root, 'media'), // generated files, in the layout recipes refer to
    review: path.join(root, 'review'),
    reviewSheet: path.join(root, 'review', 'index.html'),
    decisions: path.join(root, 'review', 'decisions.json'), // written by the operator only
    manifest: path.join(root, 'manifest.json'), // media to upload, and to delete
  };
}

/** The committed files the operator edits. */
export function configPaths(root = DEFAULT_CONFIG_DIR) {
  return {
    root,
    sources: path.join(root, 'sources.json'),
    modelMap: path.join(root, 'model-map.json'),
    screenTerms: path.join(root, 'screen-terms.json'),
    removalList: path.join(root, 'removal-list.json'),
  };
}

export async function readJson(file, fallback) {
  let text;
  try {
    text = await readFile(file, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT' && fallback !== undefined) return fallback;
    throw error;
  }
  return JSON.parse(text);
}

const writing = new Map();

/**
 * Write a JSON file whole: to a sibling first, then renamed into place, so
 * a crash never leaves half a file. Writes to one file happen one after
 * another, in the order they were asked for.
 */
export function writeJson(file, data) {
  const text = `${JSON.stringify(data, null, 2)}\n`; // taken now, so later changes to `data` are not mixed in
  const turn = (writing.get(file) ?? Promise.resolve()).then(async () => {
    await mkdir(path.dirname(file), { recursive: true });
    const partial = `${file}.${process.pid}.partial`;
    await writeFile(partial, text);
    await rename(partial, file);
  });
  writing.set(file, turn.catch(() => {}));
  return turn;
}

/** Today as an ISO date. */
export const today = (now = new Date()) => now.toISOString().slice(0, 10);
