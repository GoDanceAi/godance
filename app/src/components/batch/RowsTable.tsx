import { useEffect, useState } from 'react';
import type { RecipeInputSlot, RecipeVariable } from '../../lib/api';
import { rowNumber } from '../../lib/batch-table';
import type { Upload } from '../../lib/batch-uploads';

/** How many rows one page of the table shows: a table of 500 never renders 500 rows at once. */
export const ROWS_TABLE_PAGE = 25;

/** One row as the table shows it. Worked out only for the rows on the page. */
export interface TableRow {
  /** The values the row gives; a variable left out uses its default. */
  values: Record<string, string>;
  /** The file each slot gets, in the slots' order. */
  files: (File | null)[];
  /** What stops this row from running, in words. */
  problems: string[];
}

const UPLOAD_TEXT: Record<Upload['status'], string> = {
  waiting: 'waiting to upload',
  uploading: 'uploading',
  done: 'uploaded',
  failed: 'upload failed',
};

/**
 * Every row of the batch, a page at a time: what each variable gets (the
 * default is marked), the image for each slot and where its upload stands,
 * and what is wrong with the row, if anything. The row the API refused is
 * brought onto the page and marked, with the API's reason.
 */
export default function RowsTable({ variables, slots, rowCount, rowAt, uploadOf, flagged, error }: {
  variables: readonly RecipeVariable[];
  slots: readonly RecipeInputSlot[];
  rowCount: number;
  rowAt: (index: number) => TableRow;
  uploadOf: (file: File) => Upload | undefined;
  /** The rows with a problem, in order. */
  flagged: readonly number[];
  /** The row the API refused (counted from 0) and why. */
  error: { row: number; reason: string } | null;
}) {
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(rowCount / ROWS_TABLE_PAGE));
  const current = Math.min(page, pages - 1);
  const first = current * ROWS_TABLE_PAGE;
  const last = Math.min(rowCount, first + ROWS_TABLE_PAGE);
  const errorRow = error && error.row < rowCount ? error.row : null;

  // The refused row comes onto the page, each time the API refuses one.
  useEffect(() => {
    if (error && error.row < rowCount) setPage(Math.floor(error.row / ROWS_TABLE_PAGE));
    // Not on a change of the row count alone: only a new refusal moves the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error]);

  const indexes = Array.from({ length: last - first }, (_, i) => first + i);
  const firstFlagged = flagged[0];

  return (
    <section aria-labelledby="batch-all-rows">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="batch-all-rows" className="text-sm font-semibold text-fg">All rows</h2>
        <p className="text-xs text-fg-subtle">
          {flagged.length === 0
            ? `${rowCount.toLocaleString('en-US')} ${rowCount === 1 ? 'row' : 'rows'}, none with a problem`
            : `${flagged.length.toLocaleString('en-US')} of ${rowCount.toLocaleString('en-US')} ${rowCount === 1 ? 'row' : 'rows'} `
              + `${flagged.length === 1 ? 'has' : 'have'} a problem`}
        </p>
      </div>

      {error && errorRow !== null && (
        <p role="alert" className="mt-2 text-sm text-danger" data-row-error-reason>
          Row {rowNumber(errorRow)}: {error.reason}
        </p>
      )}

      <div role="region" aria-label="All rows of the batch" tabIndex={0} className="mt-2 card p-0 overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">
            Rows {rowNumber(first)} to {last} of {rowCount}. A value marked default comes from the recipe.
          </caption>
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle">
              <th scope="col" className="px-3 py-2 font-semibold">Row</th>
              {variables.map((variable) => (
                <th key={variable.name} scope="col" className="px-3 py-2 font-mono text-[12px] font-semibold">{variable.name}</th>
              ))}
              {slots.map((slot) => (
                <th key={slot.slot} scope="col" className="px-3 py-2 font-mono text-[12px] font-semibold">{slot.slot}</th>
              ))}
              <th scope="col" className="px-3 py-2 font-semibold">Check</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {indexes.map((index) => {
              const row = rowAt(index);
              const refused = errorRow === index;
              const bad = refused || row.problems.length > 0;
              return (
                <tr
                  key={index}
                  data-row={rowNumber(index)}
                  data-row-refused={refused ? 'true' : undefined}
                  className={refused ? 'bg-[var(--color-danger-bg)]' : undefined}
                >
                  <th scope="row" className="whitespace-nowrap px-3 py-2 text-left font-medium tabular-nums text-fg">
                    {rowNumber(index)}
                  </th>
                  {variables.map((variable) => {
                    const given = Object.hasOwn(row.values, variable.name);
                    const value = given ? row.values[variable.name] : variable.default;
                    return (
                      <td key={variable.name} className="max-w-[16rem] px-3 py-2 align-top">
                        <span className={`block truncate ${given ? 'text-fg' : 'italic text-fg-subtle'}`} title={value}>
                          {value === '' ? 'empty' : value}
                        </span>
                        {!given && <span className="text-[11px] text-fg-faint">default</span>}
                      </td>
                    );
                  })}
                  {slots.map((slot, at) => {
                    const file = row.files[at] ?? null;
                    const upload = file ? uploadOf(file) : undefined;
                    return (
                      <td key={slot.slot} className="max-w-[14rem] px-3 py-2 align-top">
                        {file ? (
                          <>
                            <span className="block truncate text-fg" title={file.name}>{file.name}</span>
                            <span className={`text-[11px] ${upload?.status === 'failed' ? 'text-danger' : 'text-fg-faint'}`}>
                              {UPLOAD_TEXT[upload?.status ?? 'waiting']}
                            </span>
                          </>
                        ) : (
                          <span className="text-warning">No image</span>
                        )}
                      </td>
                    );
                  })}
                  <td className={`px-3 py-2 align-top ${bad ? 'text-danger' : 'text-fg-subtle'}`}>
                    {refused && <span className="block">Refused: {error?.reason}</span>}
                    {row.problems.map((problem) => <span key={problem} className="block">{problem}</span>)}
                    {!bad && 'Ready'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {(pages > 1 || firstFlagged !== undefined) && (
        <nav aria-label="Pages of rows" className="mt-2 flex flex-wrap items-center gap-2 text-xs text-fg-muted">
          {pages > 1 && (
            <>
              <button type="button" className="btn btn-secondary btn-sm" disabled={current === 0}
                      onClick={() => setPage(current - 1)}>
                Previous rows
              </button>
              <span aria-live="polite" className="tabular-nums">
                Rows {rowNumber(first)}–{last} of {rowCount.toLocaleString('en-US')}
              </span>
              <button type="button" className="btn btn-secondary btn-sm" disabled={current >= pages - 1}
                      onClick={() => setPage(current + 1)}>
                Next rows
              </button>
            </>
          )}
          {firstFlagged !== undefined && (firstFlagged < first || firstFlagged >= last) && (
            <button type="button" className="btn btn-ghost btn-sm"
                    onClick={() => setPage(Math.floor(firstFlagged / ROWS_TABLE_PAGE))}>
              Show row {rowNumber(firstFlagged)}, the first with a problem
            </button>
          )}
        </nav>
      )}
    </section>
  );
}
