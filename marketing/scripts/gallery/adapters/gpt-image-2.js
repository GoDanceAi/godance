/**
 * Adapter for Evolink-AI/awesome-gpt-image-2-API-Prompts (CC0-1.0).
 *
 * The prompts are in cases/<category>.md, one file per category, each with
 * translated siblings (cases/<category>_zh-CN.md and so on). The siblings
 * translate titles, not prompts. An entry comes in one of two forms:
 *
 *   ### Case 12: [Title](https://x.com/handle/status/1) (by [@handle](https://x.com/handle))
 *   …
 *   ```
 *   the prompt
 *   ```
 *
 *   ### Case 13: Title
 *   **Source**: [@handle](https://x.com/handle/status/2)
 *   **Prompt**:
 *   ```
 *   the prompt
 *   ```
 *
 * Case numbers repeat across the list, so the original post is the only key.
 * data/ingested_tweets.json is not read: it misses cases and its records
 * come in several shapes.
 *
 * Nothing is guessed. An entry whose markup is broken (a code block that
 * never closes, several blocks, no source link) is returned with a problem,
 * and `screen` sets it aside.
 */
import { detectInputNeed, detectLanguage, hanShare } from '../lib/text.js';

export const id = 'gpt-image-2';

const BASE_FILE = /^cases\/([a-z0-9-]+)\.md$/;
const ZH_FILE = /^cases\/([a-z0-9-]+)_zh-CN\.md$/;

/** The files this adapter reads, out of every path in the list. */
export const wanted = (paths) => paths.filter((path) => BASE_FILE.test(path) || ZH_FILE.test(path));

const CASE_HEADING = /^### Case (\d+): (.+?)\s*$/;
const LINKED_TITLE = /^\[(.+)\]\((https?:\/\/[^)\s]+)\) \(by \[@?(.+?)\]\((https?:\/\/[^)\s]+)\)\)$/;
const SOURCE_LINE = /^\*\*Source\*\*:?\s*\[@?([^\]]+)\]\((https?:\/\/[^)\s]+)\)/;

/** The profile a post on X belongs to, from the post's own address. */
function profileOf(postUrl) {
  try {
    const url = new URL(postUrl);
    const handle = url.pathname.split('/')[1];
    if (/^(www\.)?(x|twitter)\.com$/.test(url.hostname) && handle) return `https://x.com/${handle}`;
  } catch {
    // not a URL
  }
  return null;
}

/** Every case in one file, in order. */
export function parseCaseFile(text) {
  const cases = [];
  let current = null;
  let fence = null;

  const finish = () => {
    if (current) {
      if (fence) current.unclosed = true;
      cases.push(current);
    }
    current = null;
    fence = null;
  };

  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    const heading = CASE_HEADING.exec(line);
    // A case heading always starts a new case, even inside a code block that
    // was never closed: that is how one broken case is kept from swallowing the next.
    if (heading) {
      finish();
      const linked = LINKED_TITLE.exec(heading[2]);
      current = linked
        ? { number: Number(heading[1]), title: linked[1], url: linked[2], author: linked[3], authorUrl: linked[4], blocks: [] }
        : { number: Number(heading[1]), title: heading[2], url: null, author: null, authorUrl: null, blocks: [] };
      continue;
    }
    if (!current) continue;
    if (line.trim().startsWith('```')) {
      if (fence) {
        current.blocks.push(fence.join('\n'));
        fence = null;
      } else {
        fence = [];
      }
      continue;
    }
    if (fence) {
      fence.push(line);
      continue;
    }
    if (/^#{1,3} /.test(line)) {
      finish(); // some other section: the case is over
      continue;
    }
    const source = SOURCE_LINE.exec(line.trim());
    if (source && !current.url) {
      current.author = source[1];
      current.url = source[2];
      current.authorUrl = profileOf(source[2]);
    }
  }
  finish();
  return cases;
}

/**
 * @param {Record<string, string>} files Path to text, for every path `wanted` chose.
 * @returns {object[]} One raw entry per case.
 */
export function parse(files) {
  const entries = [];

  for (const path of Object.keys(files).sort()) {
    const base = BASE_FILE.exec(path);
    if (!base) continue;
    const category = base[1];

    // Chinese titles, by original post. The Chinese file repeats the prompt untranslated.
    const zhTitles = new Map();
    for (const zh of parseCaseFile(files[`cases/${category}_zh-CN.md`] ?? '')) {
      if (zh.url && hanShare(zh.title) > 0.2) zhTitles.set(zh.url, zh.title);
    }

    for (const found of parseCaseFile(files[path])) {
      const problems = [];
      if (found.unclosed) problems.push('its code block never closes');
      else if (found.blocks.length === 0) problems.push('it has no prompt block');
      else if (found.blocks.length > 1) problems.push(`it has ${found.blocks.length} code blocks`);
      const prompt = found.unclosed || found.blocks.length !== 1 ? '' : found.blocks[0].trim();
      if (problems.length === 0 && prompt === '') problems.push('its prompt block is empty');
      if (!found.url) problems.push('it has no link to the original post');
      if (found.url && !found.authorUrl) problems.push('it has no link to its author');

      const lang = detectLanguage(prompt);
      entries.push({
        title: found.title.trim(),
        title_zh: found.url ? zhTitles.get(found.url) : undefined,
        category,
        prompt,
        lang,
        variants: lang === 'other' ? {} : { [lang]: prompt },
        author: found.author?.trim() ?? null,
        author_url: found.authorUrl,
        original_url: found.url,
        needs_input: detectInputNeed(prompt),
        problems,
        where: `${path}, case ${found.number}`,
      });
    }
  }

  return entries;
}
