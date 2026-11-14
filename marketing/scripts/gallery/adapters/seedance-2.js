/**
 * Adapter for YouMind-OpenLab/awesome-seedance-2-prompts (CC BY 4.0).
 *
 * README.md and README_zh.md carry the same entries, in English and in
 * Chinese: a few featured ones, then the newest hundred. The README is
 * regenerated daily, so each pinned commit holds a different set; the rest
 * of the maintainers' catalogue is on their website, not in the list.
 *
 *   ### Title                                   (featured: "### No. 1: Title")
 *   ![English](…badge/lang-English-blue)        the language the author wrote in
 *   #### 📝 Prompt
 *   ```
 *   the prompt
 *   ```
 *   **[🎬 Watch Video →](…?id=123)**            the catalogue id joins the two files
 *   **Author:** [Name](https://x.com/name) | **Source:** [Link](https://x.com/name/status/1) | **Published:** Nov 12, 2026
 *
 * Featured entries give author, source and date as a bulleted list instead.
 *
 * The author's own text is the prompt in the language the badge names. The
 * other language is the list's translation, which the recipe uses as it is.
 */
import { detectInputNeed, hanShare, statedSeconds } from '../lib/text.js';

export const id = 'seedance-2';

export const wanted = (paths) => paths.filter((path) => path === 'README.md' || path === 'README_zh.md');

const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

/** "March 15, 2026" or "Nov 12, 2026" as 2026-03-15; null for anything else. */
export function isoDate(text) {
  const match = /^([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),\s*(\d{4})$/.exec(text.trim());
  const month = match && MONTHS[match[1].toLowerCase()];
  if (!month) return null;
  const day = Number(match[2]);
  const date = new Date(Date.UTC(Number(match[3]), month - 1, day));
  if (date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return date.toISOString().slice(0, 10);
}

const AUTHOR = /\*\*(?:Author|作者)[:：]?\*\*[:：]?\s*\[([^\]]*)\]\((https?:\/\/[^)\s]+)\)/;
const SOURCE = /\*\*(?:Source|来源)[:：]?\*\*[:：]?\s*\[[^\]]*\]\((https?:\/\/[^)\s]+)\)/;
const PUBLISHED = /\*\*(?:Published|发布时间|发布日期|发布)[:：]?\*\*[:：]?\s*([^|*]+)/;
const LANG_BADGE = /^!\[([^\]]+)\]\(https:\/\/img\.shields\.io\/badge\/lang-/;

/** Every entry in one README, in order. */
export function parseReadme(text) {
  const entries = [];
  let current = null;
  let fence = null;
  let inPrompt = false;

  const finish = () => {
    if (current && (current.blocks.length > 0 || current.sourceUrl)) entries.push(current);
    current = null;
    inPrompt = false;
  };

  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    if (line.trim().startsWith('```')) {
      if (fence) {
        if (current && inPrompt) current.blocks.push(fence.join('\n'));
        fence = null;
      } else {
        fence = [];
      }
      continue;
    }
    // Inside a code block every line is prompt text, headings included.
    if (fence) {
      fence.push(line);
      continue;
    }
    if (/^#{1,2} /.test(line)) {
      finish();
      continue;
    }
    if (line.startsWith('### ')) {
      finish();
      current = { title: line.slice(4).replace(/^No\.\s*\d+[:：]\s*/, '').trim(), blocks: [], badge: null };
      continue;
    }
    if (!current) continue;
    if (line.startsWith('#### ')) {
      inPrompt = /Prompt|提示词/.test(line);
      continue;
    }
    const badge = LANG_BADGE.exec(line);
    if (badge) current.badge = badge[1];
    const author = AUTHOR.exec(line);
    if (author) Object.assign(current, { author: author[1].trim(), authorUrl: author[2] });
    const source = SOURCE.exec(line);
    if (source) current.sourceUrl = source[1];
    const published = PUBLISHED.exec(line);
    if (published) current.published = published[1].trim();
    const catalogueId = /[?&]id=(\d+)/.exec(line);
    if (catalogueId) current.catalogueId = catalogueId[1];
  }
  if (fence && current) current.unclosed = true;
  finish();
  return entries;
}

const BADGE_LANGUAGE = { English: 'en', 中文: 'zh', 简体中文: 'zh', Chinese: 'zh' };

/**
 * @param {Record<string, string>} files Path to text, for every path `wanted` chose.
 * @returns {object[]} One raw entry per prompt in the English README.
 */
export function parse(files) {
  const english = parseReadme(files['README.md'] ?? '');
  const chinese = parseReadme(files['README_zh.md'] ?? '');
  const key = (entry) => entry.catalogueId ?? entry.sourceUrl ?? null;
  const zhByKey = new Map(chinese.filter((entry) => key(entry)).map((entry) => [key(entry), entry]));

  return english.map((en) => {
    const zh = key(en) ? zhByKey.get(key(en)) : undefined;
    const problems = [];
    if (en.unclosed || zh?.unclosed) problems.push('its code block never closes');
    else if (en.blocks.length !== 1) problems.push(en.blocks.length === 0 ? 'it has no prompt block' : `it has ${en.blocks.length} code blocks`);
    if (!en.sourceUrl) problems.push('it has no link to the original post');
    if (!en.authorUrl) problems.push('it has no link to its author');

    const enPrompt = en.blocks.length === 1 ? en.blocks[0].trim() : '';
    const zhPrompt = zh && zh.blocks.length === 1 ? zh.blocks[0].trim() : '';
    if (problems.length === 0 && enPrompt === '') problems.push('its prompt block is empty');

    // What the list supplies in each language. The Chinese README falls back
    // to English text for some entries; that is not a Chinese variant.
    const variants = {};
    if (enPrompt !== '' && hanShare(enPrompt) <= 0.3) variants.en = enPrompt;
    if (zhPrompt !== '' && hanShare(zhPrompt) > 0.3) variants.zh = zhPrompt;

    const lang = BADGE_LANGUAGE[en.badge] ?? 'other';
    const prompt = variants[lang] ?? '';
    if (problems.length === 0 && lang !== 'other' && prompt === '') {
      problems.push(`the list does not carry the author's ${lang === 'zh' ? 'Chinese' : 'English'} text`);
    }

    const everything = `${en.title}\n${enPrompt}\n${zhPrompt}`;
    return {
      title: en.title,
      title_zh: zh && hanShare(zh.title) > 0.2 ? zh.title : undefined,
      category: null,
      prompt,
      lang,
      variants,
      author: en.author ?? null,
      author_url: en.authorUrl ?? null,
      original_url: en.sourceUrl ?? null,
      published: en.published ? (isoDate(en.published) ?? undefined) : undefined,
      needs_input: detectInputNeed(everything),
      stated_seconds: statedSeconds(everything),
      problems,
      where: `README.md, "${en.title}"`,
    };
  });
}
