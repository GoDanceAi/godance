/**
 * fetch: read entries from each source list at its pinned commit.
 *
 * For every source: check the licence file first and refuse the list when it
 * is missing or is not the licence the source is configured with; read the
 * text files its adapter asks for; drop entries whose original post was
 * already seen (in this list or an earlier one) or is on the removal list.
 *
 * Only text is ever requested. The lists' own images and videos are never
 * downloaded: the reader refuses any path that is not a text file.
 */
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import * as gptImage2 from '../adapters/gpt-image-2.js';
import * as seedance2 from '../adapters/seedance-2.js';
import { detectLicence } from '../lib/licence.js';
import { postKey } from '../lib/text.js';

export const ADAPTERS = { [gptImage2.id]: gptImage2, [seedance2.id]: seedance2 };

const LICENCE_FILE = /^(LICEN[CS]E|COPYING)(\.(md|txt))?$/i;
const TEXT_FILE = /\.(md|json|txt)$/i;
const FULL_COMMIT = /^[0-9a-f]{40}$/;

/** Check the sources file: every list is pinned to a full commit and has an adapter. */
export function checkSources(sources) {
  for (const source of sources) {
    for (const field of ['id', 'adapter', 'repo', 'commit', 'licence', 'modality', 'model']) {
      if (typeof source[field] !== 'string' || source[field] === '') throw new Error(`sources: "${source.id ?? '?'}" has no ${field}`);
    }
    if (!FULL_COMMIT.test(source.commit)) throw new Error(`sources: "${source.id}" must be pinned to a full 40-character commit, not "${source.commit}"`);
    if (!ADAPTERS[source.adapter]) throw new Error(`sources: "${source.id}" names an adapter that does not exist: ${source.adapter}`);
    if (!/^[\w.-]+\/[\w.-]+$/.test(source.repo)) throw new Error(`sources: "${source.id}" has a repo that is not owner/name`);
  }
  return sources;
}

/**
 * Reads a public list at a pinned commit: its file names, and the text of
 * one file. With a cache folder it reads from there first, and saves what
 * it downloads when `save` is set. A commit never changes, so neither does
 * the cache.
 */
export function createListReader({ fetch = globalThis.fetch, cacheDir = null, save = false } = {}) {
  const headers = { 'User-Agent': 'gallery-import', Accept: 'application/vnd.github+json' };
  const folder = (source) => path.join(cacheDir, source.id, source.commit);

  async function cached(file) {
    if (!cacheDir) return null;
    return readFile(file, 'utf8').catch(() => null);
  }
  async function keep(file, text) {
    if (!cacheDir || !save) return;
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, text);
  }
  async function get(url) {
    const response = await fetch(url, { headers });
    if (!response.ok) throw new Error(`${url}: ${response.status}`);
    return response.text();
  }

  return {
    /** Every file path in the list at the pinned commit. */
    async paths(source) {
      const file = cacheDir ? path.join(folder(source), 'paths.json') : null;
      const saved = file ? await cached(file) : null;
      if (saved) return JSON.parse(saved);
      const tree = JSON.parse(await get(`https://api.github.com/repos/${source.repo}/git/trees/${source.commit}?recursive=1`));
      if (tree.truncated) throw new Error(`${source.repo}: the file listing is too long to read in one request`);
      const paths = tree.tree.filter((item) => item.type === 'blob').map((item) => item.path);
      if (file) await keep(file, JSON.stringify(paths));
      return paths;
    },
    /** The text of one file. Refuses anything that is not a text file. */
    async text(source, filePath) {
      const name = filePath.split('/').at(-1);
      if (!TEXT_FILE.test(name) && !LICENCE_FILE.test(name)) throw new Error(`refusing to read ${filePath}: only text files are read from a list`);
      const file = cacheDir ? path.join(folder(source), 'files', filePath) : null;
      const saved = file ? await cached(file) : null;
      if (saved !== null) return saved;
      const encoded = filePath.split('/').map(encodeURIComponent).join('/');
      const text = await get(`https://raw.githubusercontent.com/${source.repo}/${source.commit}/${encoded}`);
      if (file) await keep(file, text);
      return text;
    },
  };
}

/**
 * @param {object} options
 * @param {object[]} options.sources From sources.json, in order of precedence.
 * @param {{ original_url: string }[]} [options.removed] The removal list.
 * @param {{ paths: Function, text: Function }} options.reader
 * @returns {Promise<{ entries: object[], dropped: object[], sources: object[] }>}
 */
export async function fetchEntries({ sources, removed = [], reader }) {
  checkSources(sources);
  const removedKeys = new Set(removed.map((item) => postKey(item.original_url)).filter(Boolean));
  const seen = new Map();
  const result = { entries: [], dropped: [], sources: [] };

  for (const source of sources) {
    const report = { id: source.id, repo: source.repo, commit: source.commit, licence: source.licence, read: 0, kept: 0, duplicates: 0, removed: 0, refused: null };
    result.sources.push(report);

    const paths = await reader.paths(source);
    const licencePath = paths.find((item) => LICENCE_FILE.test(item));
    const licence = licencePath ? detectLicence(await reader.text(source, licencePath)) : null;
    if (licence !== source.licence) {
      report.refused = !licencePath
        ? 'it has no licence file'
        : licence
          ? `its licence file grants ${licence}, not the ${source.licence} this source is configured with`
          : `its licence file is not ${source.licence}, nor any licence a recipe may carry`;
      continue;
    }

    const adapter = ADAPTERS[source.adapter];
    const files = {};
    for (const filePath of adapter.wanted(paths)) files[filePath] = await reader.text(source, filePath);

    for (const raw of adapter.parse(files)) {
      report.read++;
      const key = raw.original_url ? postKey(raw.original_url) : null;
      const where = `${source.repo}: ${raw.where}`;
      if (key && removedKeys.has(key)) {
        report.removed++;
        result.dropped.push({ reason: 'removed', where, original_url: raw.original_url });
        continue;
      }
      // An entry with no usable link still gets an id, so `screen` can report it.
      const id = key ?? `nokey-${createHash('sha256').update(where).digest('hex').slice(0, 16)}`;
      if (seen.has(id)) {
        report.duplicates++;
        result.dropped.push({ reason: 'duplicate', where, original_url: raw.original_url, of: seen.get(id) });
        continue;
      }
      seen.set(id, where);
      report.kept++;
      result.entries.push({
        id,
        source: source.id,
        list: source.repo,
        list_commit: source.commit,
        licence: source.licence,
        modality: source.modality,
        model: source.model,
        ...raw,
      });
    }
  }
  return result;
}
