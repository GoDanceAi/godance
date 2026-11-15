import { useState } from 'react';
import { decodeTableFile, delimiterName, type ParsedTable } from '../../lib/csv';

/** The largest table file read into the page. Five hundred rows of long values are far below it. */
const MAX_FILE_BYTES = 5 * 1024 * 1024;
/** How many of the parser's notes are listed before the rest are counted. */
const NOTES_SHOWN = 4;

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
}

/**
 * Where the rows come from: a table pasted from a spreadsheet, or a CSV
 * file, which is read into the same box so it can be seen and corrected.
 * Says what was found: how many rows and columns, and anything left out.
 */
export default function RowsSource({ text, table, note, onChange }: {
  text: string;
  table: ParsedTable;
  /** How a chosen file was read, when that needed saying. */
  note: string | null;
  onChange: (text: string, note: string | null) => void;
}) {
  const [fileProblem, setFileProblem] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);

  const readFile = async (file: File | undefined) => {
    if (!file) return;
    setFileProblem(null);
    if (file.size > MAX_FILE_BYTES) {
      setFileProblem(`${file.name} is larger than 5 MB, which is more than a batch can hold. Split it and choose a part.`);
      return;
    }
    try {
      const decoded = decodeTableFile(new Uint8Array(await file.arrayBuffer()));
      setFileName(file.name);
      onChange(decoded.text, decoded.note);
    } catch {
      setFileProblem(`${file.name} could not be read. Try again, or paste the table instead.`);
    }
  };

  const hasTable = table.header.length > 0;

  return (
    <section aria-labelledby="batch-rows">
      <h2 id="batch-rows" className="text-sm font-semibold text-fg">Rows</h2>
      <p className="mt-1 text-sm text-fg-muted">
        Paste a table from a spreadsheet, or choose a CSV file. The first row holds the column names,
        and every row under it is one generation.
      </p>

      <label htmlFor="batch-table" className="mt-3 block text-xs font-medium text-fg">Paste a table</label>
      <textarea
        id="batch-table"
        value={text}
        onChange={(e) => {
          setFileName(null);
          onChange(e.target.value, null);
        }}
        rows={7}
        wrap="off"
        spellCheck={false}
        autoComplete="off"
        placeholder={'color\tsurface\nblue\toak desk\ngreen\tmarble counter'}
        className="input mt-1 font-mono text-[12px]"
      />

      <div className="mt-2 flex flex-wrap items-center gap-2">
        {/* The input itself takes the focus and the keys; the label is what is seen and clicked. */}
        <label className="btn btn-secondary btn-sm cursor-pointer focus-within:ring-2 focus-within:ring-brand-500">
          Choose a CSV file
          <input
            type="file"
            accept=".csv,.tsv,.txt,text/csv,text/tab-separated-values,text/plain"
            className="sr-only"
            aria-label="CSV file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';   // so the same file can be chosen again after it is edited
              void readFile(file);
            }}
          />
        </label>
        {text !== '' && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => {
              setFileName(null);
              setFileProblem(null);
              onChange('', null);
            }}
          >
            Clear the table
          </button>
        )}
        {fileName && <span className="min-w-0 truncate text-xs text-fg-subtle">Read from {fileName}</span>}
      </div>
      {fileProblem && <p role="alert" className="mt-2 text-sm text-danger">{fileProblem}</p>}

      <div aria-live="polite" className="mt-3 text-sm">
        {hasTable && (
          <p className="text-fg">
            <strong className="font-semibold">
              {plural(table.rows.length, 'row')}, {plural(table.header.length, 'column')}
            </strong>
            {table.delimiter && (
              <span className="text-fg-subtle"> · separated by {delimiterName(table.delimiter)}s</span>
            )}
          </p>
        )}
        {hasTable && table.rows.length === 0 && (
          <p className="mt-1 text-warning">Only the column names so far. Add a row under them.</p>
        )}
      </div>
      {(note || table.notes.length > 0) && (
        <ul aria-label="About this table" className="mt-1 space-y-1 text-xs text-warning">
          {note && <li>{note}</li>}
          {/* eslint-disable-next-line react/no-array-index-key -- two notes can read the same */}
          {table.notes.slice(0, NOTES_SHOWN).map((line, i) => <li key={i}>{line}</li>)}
          {table.notes.length > NOTES_SHOWN && (
            <li>And {plural(table.notes.length - NOTES_SHOWN, 'more note')} like these.</li>
          )}
        </ul>
      )}
    </section>
  );
}
