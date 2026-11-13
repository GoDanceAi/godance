/**
 * Small text helpers shared by the adapters and the stages: what language a
 * prompt is in, whether it needs an input file, what it says about length
 * and shape, and how a title becomes a slug.
 */
import { createHash } from 'node:crypto';

const HAN = /[㐀-鿿]/g;
const KANA = /[぀-ヿ]/;
const HANGUL = /[가-힯]/;

/** Share of the non-space characters that are Chinese characters. */
export function hanShare(text) {
  const solid = text.replace(/\s/g, '');
  return solid.length === 0 ? 0 : (text.match(HAN) ?? []).length / solid.length;
}

/**
 * The language a prompt is written in: 'en', 'zh', or 'other' (Japanese,
 * Korean). An English prompt that quotes some Chinese text to render is
 * still English.
 */
export function detectLanguage(text) {
  if (KANA.test(text) || HANGUL.test(text)) {
    // A stray kana or hangul word inside an English prompt does not change its language.
    const cjk = (text.match(/[぀-ヿ㐀-鿿가-힯]/g) ?? []).length;
    if (cjk / Math.max(1, text.replace(/\s/g, '').length) > 0.3) return 'other';
  }
  return hanShare(text) > 0.3 ? 'zh' : 'en';
}

/**
 * Signs that a prompt cannot be run from text alone. Each is [kind, pattern];
 * the first that matches wins.
 */
const INPUT_SIGNS = [
  ['image', /@\s?(image|img|photo|pic|ref|reference|图片|图|参考图)\s?\d*/i],
  ['video', /@\s?(video|clip|视频)\s?\d*/i],
  ['audio', /@\s?(audio|voice|music|音频|音乐)\s?\d*/i],
  ['image', /\[(image|img|photo|图片)\s?\d*\]/i],
  ['video', /\[(video|视频)\s?\d*\]/i],
  ['audio', /\[(audio|音频)\s?\d*\]/i],
  ['image', /<<<\s?image/i],
  ['video', /\b(reference|uploaded|attached|provided|input|source|original)\s+(video|clip|footage)\b/i],
  ['audio', /\b(reference|uploaded|attached|provided|input|source)\s+(audio|voice|song|track|music)\b/i],
  ['image', /\b(reference|uploaded|attached|provided|input|source|supplied|given)\s+(image|images|photo|photos|picture|pictures|selfie|portrait|screenshot|logo|sketch|drawing|product photo)\b/i],
  ['image', /\b(this|the|my|your)\s+(uploaded|attached|provided|reference)\b/i],
  ['image', /\b(person|subject|character|man|woman|girl|boy|face|product|object|pet|dog|cat)\s+(in|from)\s+(the|this|my|your)\s+(image|photo|picture|reference|selfie|upload)/i],
  ['image', /\b(image|photo|picture|figure)\s?[1-9]\b/i],
  ['image', /\b(keep|preserve|maintain|retain)\s+(the\s+)?(same\s+|original\s+|exact\s+)?(face|identity|facial features|likeness)/i],
  ['image', /\b(upload|attach)\s+(a|an|your|the|one|two)\b/i],
  ['image', /\bbased on (the|this|my|your) (photo|image|picture|selfie|upload)/i],
  ['image', /\b(first|last|start|starting|end|ending)[ -]frame\b/i],
  ['image', /\b(image|photo)-to-(image|video)\b/i],
  ['video', /\bvideo-to-video\b/i],
  ['audio', /\baudio-to-video\b/i],
  ['image', /参考图|参考图片|上传|附图|垫图|原图|首帧|尾帧|图[一二三1-9]\b|图片[一二三1-9]|この画像|添付/],
  ['video', /参考视频|视频[一二三1-9]/],
  ['audio', /参考音频|音频[一二三1-9]/],
];

/** 'image', 'video' or 'audio' when the prompt needs such an input, otherwise null. */
export function detectInputNeed(text) {
  for (const [kind, pattern] of INPUT_SIGNS) if (pattern.test(text)) return kind;
  return null;
}

/** The longest clip length, in seconds, that the text names (3 to 60), or null. */
export function statedSeconds(text) {
  // Not the tail of a longer number or a fraction: "1/250 second" names no clip length.
  const found = [...text.matchAll(/(?<![\d/.,])(\d{1,2})\s?[-–]?\s?(?:seconds?|secs?|秒)/gi)]
    .map((match) => Number(match[1]))
    .filter((n) => n >= 3 && n <= 60);
  return found.length > 0 ? Math.max(...found) : null;
}

/** 'portrait', 'landscape' or 'square', from what the prompt says about its frame. */
export function statedShape(text) {
  const ratio = /\b(\d{1,2})\s?[:：]\s?(\d{1,2})\b/.exec(text);
  if (ratio) {
    const [w, h] = [Number(ratio[1]), Number(ratio[2])];
    if (w > 0 && h > 0 && w <= 32 && h <= 32) return w === h ? 'square' : w > h ? 'landscape' : 'portrait';
  }
  if (/\b(vertical|portrait orientation|tall format)\b|竖屏|竖版|竖构图/i.test(text)) return 'portrait';
  if (/\b(horizontal|landscape orientation|widescreen|wide format)\b|横屏|横版|横构图/i.test(text)) return 'landscape';
  return 'square';
}

/** Lowercase letters, digits and hyphens, at most 80 characters: what the recipe format and the app accept. */
export function slugify(title) {
  const slug = title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.slice(0, 80).replace(/-+$/, '');
}

/** A slug nobody has taken: the plain one, or the first of -2, -3, … that is free. */
export function uniqueSlug(title, taken) {
  const base = slugify(title);
  if (base === '') return null;
  if (!taken.has(base)) return base;
  for (let n = 2; n < 1000; n++) {
    const suffix = `-${n}`;
    const candidate = base.slice(0, 80 - suffix.length).replace(/-+$/, '') + suffix;
    if (!taken.has(candidate)) return candidate;
  }
  return null;
}

/**
 * A stable key for an original post, so the same post is recognised across
 * lists, across runs and in the removal list. A post on X is keyed by its
 * status id, whatever handle or host the link was written with.
 */
export function postKey(url) {
  let parsed;
  try {
    parsed = new URL(url.trim());
  } catch {
    return null;
  }
  const host = parsed.hostname.toLowerCase().replace(/^(www|mobile)\./, '');
  if (host === 'x.com' || host === 'twitter.com') {
    const status = /\/status(?:es)?\/(\d+)/.exec(parsed.pathname);
    if (status) return `x-${status[1]}`;
  }
  const normal = `${host}${parsed.pathname.replace(/\/+$/, '')}`.toLowerCase();
  return `u-${createHash('sha256').update(normal).digest('hex').slice(0, 16)}`;
}

/** Text safe to drop into a log line or a summary. */
export const oneLine = (text, max = 100) => {
  const flat = String(text).replace(/\s+/g, ' ').trim();
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
};
