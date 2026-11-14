import type { JobView } from './api';
import { isUuid } from './ids';

// Comparing attempts side by side: which jobs a link names, and what in a
// prompt differs from the first one's. Nothing here touches the network or
// the page, so the tests run it directly.

/** The fewest and the most jobs a comparison holds. */
export const COMPARE_MIN = 2;
export const COMPARE_MAX = 4;

/** The page that compares these jobs, in this order. */
export function compareLink(jobIds: readonly string[]): string {
  return `/compare?jobs=${jobIds.map(encodeURIComponent).join(',')}`;
}

/**
 * The job ids `?jobs=` names: valid ids, each once, in the order given, and
 * no more than {@link COMPARE_MAX}. Anything else in the list is ignored.
 */
export function compareIds(param: string | null): string[] {
  const ids: string[] = [];
  for (const raw of (param ?? '').split(',')) {
    const id = raw.trim().toLowerCase();
    if (isUuid(id) && !ids.includes(id)) ids.push(id);
    if (ids.length === COMPARE_MAX) break;
  }
  return ids;
}

/** A selection with `id` put in or taken out. Past {@link COMPARE_MAX} nothing more goes in. */
export function toggleSelected(selected: readonly string[], id: string): string[] {
  if (selected.includes(id)) return selected.filter((s) => s !== id);
  return selected.length >= COMPARE_MAX ? [...selected] : [...selected, id];
}

/** A piece of a prompt, and whether the first prompt lacks it. */
export interface DiffPart {
  text: string;
  changed: boolean;
}

// A word, a run of spaces, or any other single character (punctuation, a CJK character).
const TOKEN = /[\p{L}\p{N}_'’-]+|\s+|[^\s]/gu;
const CJK = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u;

function tokens(text: string): string[] {
  const out: string[] = [];
  for (const token of text.match(TOKEN) ?? []) {
    // Text without spaces between words is compared a character at a time.
    if (token.length > 1 && CJK.test(token)) out.push(...token);
    else out.push(token);
  }
  return out;
}

// Above this many cells the table is not built: the prompt is shown without marks.
const MAX_CELLS = 4_000_000;

/**
 * `text` cut into the pieces it shares with `base` and the pieces it
 * doesn't, by the longest run of words both have in order. The pieces join
 * back into `text` exactly. Null when the two are too long to compare.
 */
export function diffFrom(base: string, text: string): DiffPart[] | null {
  const a = tokens(base);
  const b = tokens(text);
  if (a.length * b.length > MAX_CELLS) return null;

  // lcs[i][j]: the longest common run of a[i..] and b[j..].
  const width = b.length + 1;
  const lcs = new Uint32Array((a.length + 1) * width);
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i * width + j] = a[i] === b[j]
        ? lcs[(i + 1) * width + j + 1] + 1
        : Math.max(lcs[(i + 1) * width + j], lcs[i * width + j + 1]);
    }
  }

  const parts: DiffPart[] = [];
  const push = (token: string, changed: boolean) => {
    const last = parts.at(-1);
    if (last && last.changed === changed) last.text += token;
    else parts.push({ text: token, changed });
  };
  let i = 0;
  let j = 0;
  while (j < b.length) {
    if (i < a.length && a[i] === b[j]) {
      push(b[j], false);
      i++;
      j++;
    } else if (i < a.length && lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) {
      i++;   // only the first prompt has this
    } else {
      // A space between two changed words is part of the change; on its own it is not worth a mark.
      push(b[j], b[j].trim() !== '' || parts.at(-1)?.changed === true);
      j++;
    }
  }
  return parts;
}

/** How long a job took, from when it started (or was made) to when it ended: "18 s", "2 min 5 s". */
export function durationText(job: JobView): string | null {
  if (!job.completedAt) return null;
  const end = Date.parse(job.completedAt);
  const start = Date.parse(job.startedAt ?? job.createdAt);
  if (Number.isNaN(end) || Number.isNaN(start) || end < start) return null;
  const seconds = Math.round((end - start) / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}
