/**
 * A table as people have it: pasted from a spreadsheet (tab-separated) or
 * read from a CSV file. Written here rather than taken from a package, and
 * pure, so the tests run it directly.
 *
 * The rules are RFC 4180's:
 *   1. Records end at a line break (CRLF, LF or CR); the last one needs none.
 *   2. Fields are separated by one delimiter: a tab, a comma or a semicolon,
 *      whichever the first record uses (looked for in that order, outside
 *      quotes). Spaces are part of a field.
 *   3. A field that starts with a double quote runs to its closing quote and
 *      may hold delimiters, line breaks and quotes; a quote inside it is
 *      written twice.
 *
 * and, for what spreadsheets and people really produce:
 *   4. A byte-order mark at the start is dropped.
 *   5. A line break inside a quoted field is kept as "\n".
 *   6. A field that starts with a quote but is not a quoted field (text
 *      follows its closing quote, as in `"hello" he said`) is read as plain
 *      text, quotes and all.
 *   7. A quote that is never closed does not swallow the rows after it: its
 *      field is read as plain text, and the line it opened on is reported.
 *   8. The first record is the header. A row with fewer fields than the
 *      header is padded with empty ones; fields beyond the header are
 *      dropped and reported. Lines with nothing on them are skipped, and so
 *      are rows whose every cell is empty, which are reported.
 *   9. Two columns with one name are told apart: the second is "name (2)".
 */

export type Delimiter = '\t' | ',' | ';';

export interface ParsedTable {
  /** The delimiter found, or null for a single column. */
  delimiter: Delimiter | null;
  /** The column names, trimmed. A column with no name is "Column N". */
  header: string[];
  /** The rows under the header, each with exactly one field per column. */
  rows: string[][];
  /** What was dropped or guessed, in sentences for the person who gave the table. */
  notes: string[];
}

const DELIMITER_NAME: Record<Delimiter, string> = { '\t': 'tab', ',': 'comma', ';': 'semicolon' };

/** "tab", "comma" or "semicolon". */
export function delimiterName(delimiter: Delimiter): string {
  return DELIMITER_NAME[delimiter];
}

function isBreak(ch: string | undefined): boolean {
  return ch === '\n' || ch === '\r';
}

/** Rule 2: the first of tab, comma and semicolon that the first record has outside quotes. */
function findDelimiter(text: string): Delimiter | null {
  const seen = { '\t': false, ',': false, ';': false };
  let quoted = false;
  let fieldStart = true;
  // The first record is the first line with anything on it.
  let start = 0;
  while (start < text.length && isBreak(text[start])) start += 1;
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') i += 1;
        else quoted = false;
      }
      continue;
    }
    if (isBreak(ch)) break;
    if (ch === '"' && fieldStart) {
      quoted = true;
      fieldStart = false;
      continue;
    }
    if (ch === '\t' || ch === ',' || ch === ';') {
      seen[ch] = true;
      fieldStart = true;
    } else {
      fieldStart = false;
    }
  }
  return seen['\t'] ? '\t' : seen[','] ? ',' : seen[';'] ? ';' : null;
}

interface RawRecord {
  fields: string[];
  line: number;        // the line it starts on, from 1
  blank: boolean;      // nothing on the line at all
}

/** Rules 1–7: the text cut into records of fields. */
function readRecords(text: string, delimiter: Delimiter | null, notes: string[]): RawRecord[] {
  const records: RawRecord[] = [];
  const length = text.length;
  let at = 0;
  let line = 1;

  while (at < length) {
    const record: RawRecord = { fields: [], line, blank: false };
    const recordStart = at;
    // One field per turn, until the record's line break or the end of the text.
    for (;;) {
      let value: string | null = null;
      if (text[at] === '"') {
        // Rule 3: a quoted field, if a delimiter or a line end follows its closing quote.
        let i = at + 1;
        let out = '';
        let lines = 0;
        let closed = false;
        while (i < length) {
          const ch = text[i];
          if (ch === '"') {
            if (text[i + 1] === '"') {
              out += '"';
              i += 2;
              continue;
            }
            closed = true;
            i += 1;
            break;
          }
          if (ch === '\r') {
            // Rule 5.
            out += '\n';
            lines += 1;
            i += text[i + 1] === '\n' ? 2 : 1;
            continue;
          }
          if (ch === '\n') lines += 1;
          out += ch;
          i += 1;
        }
        if (!closed) {
          // Rule 7: read as plain text below, so one stray quote does not swallow the rows after it.
          notes.push(`A quote opened on line ${line} is never closed, so that field was read as plain text.`);
        } else if (i >= length || isBreak(text[i]) || (delimiter !== null && text[i] === delimiter)) {
          value = out;
          at = i;
          line += lines;
        }
        // Otherwise rule 6: not a quoted field after all; read it as plain text below.
      }
      if (value === null) {
        let i = at;
        while (i < length && !isBreak(text[i]) && !(delimiter !== null && text[i] === delimiter)) i += 1;
        value = text.slice(at, i);
        at = i;
      }
      record.fields.push(value);

      if (at >= length) break;
      if (delimiter !== null && text[at] === delimiter) {
        at += 1;
        // A delimiter at the very end of the text is followed by one last, empty field.
        if (at >= length) {
          record.fields.push('');
          break;
        }
        continue;
      }
      // A line break ends the record.
      at += text[at] === '\r' && text[at + 1] === '\n' ? 2 : 1;
      line += 1;
      break;
    }
    record.blank = record.fields.length === 1 && record.fields[0] === '' && text[recordStart] !== '"';
    records.push(record);
  }
  return records;
}

function list(numbers: number[], most = 5): string {
  const shown = numbers.slice(0, most).join(', ');
  return numbers.length > most ? `${shown} and ${numbers.length - most} more` : shown;
}

/**
 * The table a text holds: its header and the rows under it. A text with
 * nothing in it is a table with no columns and no rows.
 */
export function parseTable(input: string): ParsedTable {
  const notes: string[] = [];
  // Rule 4.
  const text = input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;
  const delimiter = findDelimiter(text);
  const records = readRecords(text, delimiter, notes).filter((record) => !record.blank);
  if (records.length === 0) return { delimiter, header: [], rows: [], notes };

  // Rule 9.
  const taken = new Map<string, number>();
  const header = records[0].fields.map((raw, i) => {
    const name = raw.trim() || `Column ${i + 1}`;
    const count = (taken.get(name) ?? 0) + 1;
    taken.set(name, count);
    return count === 1 ? name : `${name} (${count})`;
  });
  const rows: string[][] = [];
  const tooLong: number[] = [];
  let empty = 0;
  for (const record of records.slice(1)) {
    // A row of empty cells, as a spreadsheet's unused rows paste: nothing to run.
    if (record.fields.every((field) => field.trim() === '')) {
      empty += 1;
      continue;
    }
    const row = record.fields.slice(0, header.length);
    while (row.length < header.length) row.push('');
    if (record.fields.length > header.length) tooLong.push(rows.length + 1);
    rows.push(row);
  }
  if (tooLong.length > 0) {
    notes.push(`${tooLong.length === 1 ? 'Row' : 'Rows'} ${list(tooLong)} ${tooLong.length === 1 ? 'has' : 'have'} `
      + `more fields than the header has columns. The extra fields were left out.`);
  }
  if (empty > 0) {
    notes.push(`${empty === 1 ? 'One empty row was' : `${empty} empty rows were`} left out.`);
  }
  return { delimiter, header, rows, notes };
}

export interface DecodedFile {
  text: string;
  /** Set when the bytes were not UTF-8 and had to be read another way. */
  note: string | null;
}

/**
 * A CSV file's bytes as text. UTF-8 with or without a byte-order mark, and
 * UTF-16 when its mark says so (a spreadsheet's "Unicode text"). Bytes that
 * are not valid UTF-8 are read as Windows-1252, which is what an older
 * spreadsheet export is, and the note says how to avoid the guess.
 */
export function decodeTableFile(bytes: Uint8Array): DecodedFile {
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return { text: new TextDecoder('utf-16le').decode(bytes), note: null };
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return { text: new TextDecoder('utf-16be').decode(bytes), note: null };
  }
  try {
    // Drops a UTF-8 byte-order mark by itself.
    return { text: new TextDecoder('utf-8', { fatal: true }).decode(bytes), note: null };
  } catch {
    return {
      text: new TextDecoder('windows-1252').decode(bytes),
      note: 'This file is not saved as UTF-8, so accented letters may be wrong. '
        + 'If they are, save it as “CSV UTF-8” and choose it again.',
    };
  }
}
