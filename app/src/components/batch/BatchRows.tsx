import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { Batch, BatchRow, BatchRowOutput } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import { rowNumber } from '../../lib/batch-table';
import { fetchBatchRows, finishedRows, ROWS_PAGE, rowUnfinished } from '../../lib/batches';
import { RowStatusPill } from './BatchStatusPill';

type View = 'table' | 'grid';

/** A row's values as one line: "color: blue · surface: oak desk". */
function valuesText(variables: Record<string, string>): string {
  return Object.entries(variables).map(([name, value]) => `${name}: ${value === '' ? 'empty' : value}`).join(' · ');
}

function sorted(outputs: BatchRowOutput[] | undefined): BatchRowOutput[] {
  return [...(outputs ?? [])].sort((a, b) => a.index - b.index);
}

/** One output as a square picture; a video shows a frame. An empty `alt` means the link around it names it. */
function OutputThumb({ output, alt }: { output: BatchRowOutput; alt: string }) {
  return output.content_type.startsWith('video/') ? (
    <video
      src={`${output.url}#t=0.1`}
      preload="metadata"
      muted
      playsInline
      aria-label={alt || undefined}
      aria-hidden={alt === '' ? true : undefined}
      tabIndex={-1}
      className="aspect-square w-full bg-bg-muted object-cover"
    />
  ) : (
    <img src={output.url} alt={alt} loading="lazy" decoding="async" className="aspect-square w-full bg-bg-muted object-cover" />
  );
}

/**
 * The rows of a batch, fifty at a time in row order: each with its number,
 * its values, its status, what it made, what it cost and why it failed. A
 * row with a job opens that job's page. The same rows can be shown as a
 * grid of their outputs. While the batch runs, the rows that can still
 * change are read again as its counts move.
 *
 * `linked` is the row a job's page pointed at (counted from 0): the list
 * starts at its page and marks it.
 */
export default function BatchRows({ batch, linked }: { batch: Batch; linked: number | null }) {
  const startPage = linked !== null && linked < batch.rows_total ? Math.floor(linked / ROWS_PAGE) : 0;
  // The pages held, by page number: page 0 is rows 0–49.
  const [pages, setPages] = useState<ReadonlyMap<number, BatchRow[]>>(new Map());
  const [range, setRange] = useState({ first: startPage, last: startPage });
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState<'earlier' | 'more' | null>(null);
  const [moreFailed, setMoreFailed] = useState(false);
  const [view, setView] = useState<View>('table');
  const held = useRef(pages);
  held.current = pages;
  // Two readings of one page can come back out of order: only a newer one replaces what is held.
  const asked = useRef(0);
  const applied = useRef(new Map<number, number>());
  const marked = useRef<HTMLTableRowElement | null>(null);

  const load = useCallback(async (page: number): Promise<BatchRow[]> => {
    asked.current += 1;
    const turn = asked.current;
    const rows = await fetchBatchRows(batch.id, page === 0 ? null : page * ROWS_PAGE - 1);
    if ((applied.current.get(page) ?? 0) < turn) {
      applied.current.set(page, turn);
      setPages((current) => new Map(current).set(page, rows));
    }
    return rows;
  }, [batch.id]);

  // The first page shown.
  useEffect(() => {
    let cancelled = false;
    load(startPage).catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [load, startPage]);

  // The row a job pointed at is brought into view, once, when its page has been drawn.
  const loaded = pages.has(startPage);
  const shown = useRef(false);
  useEffect(() => {
    if (!loaded || shown.current || !marked.current) return;
    shown.current = true;
    marked.current.scrollIntoView({ block: 'center' });
  }, [loaded]);

  // As the batch's counts move, the held pages that still have a row to change are read again,
  // in order. Rows start in order, so nothing after a page that ends on a waiting row has changed.
  const stamp = `${batch.status}|${finishedRows(batch)}|${batch.rows_done}|${batch.rows_failed}|${batch.rows_running ?? ''}`;
  const firstStamp = useRef(stamp);
  useEffect(() => {
    if (stamp === firstStamp.current) return;
    firstStamp.current = '';
    let cancelled = false;
    void (async () => {
      const numbers = [...held.current.keys()].sort((a, b) => a - b);
      for (const page of numbers) {
        if (cancelled) return;
        if (!held.current.get(page)?.some(rowUnfinished)) continue;
        try {
          const rows = await load(page);
          if (rows.at(-1)?.status === 'PENDING') return;
        } catch {
          return;   // the next change of the counts tries again
        }
      }
    })();
    return () => { cancelled = true; };
  }, [stamp, load]);

  const extend = async (direction: 'earlier' | 'more') => {
    const page = direction === 'earlier' ? range.first - 1 : range.last + 1;
    setLoading(direction);
    setMoreFailed(false);
    try {
      await load(page);
      setRange((current) => (direction === 'earlier' ? { ...current, first: page } : { ...current, last: page }));
    } catch {
      setMoreFailed(true);
    } finally {
      setLoading(null);
    }
  };

  const rows: BatchRow[] = [];
  for (let page = range.first; page <= range.last; page += 1) rows.push(...(pages.get(page) ?? []));
  const hasEarlier = range.first > 0;
  const hasMore = (range.last + 1) * ROWS_PAGE < batch.rows_total;
  const outputs = rows.flatMap((row) => sorted(row.outputs).map((output) => ({ row, output })));

  return (
    <section aria-labelledby="batch-rows-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="batch-rows-heading" className="text-sm font-semibold text-fg">Rows</h2>
        <div role="group" aria-label="Show the rows as" className="inline-flex rounded-md border border-border bg-surface p-0.5">
          {(['table', 'grid'] as const).map((each) => (
            <button
              key={each}
              type="button"
              aria-pressed={view === each}
              onClick={() => setView(each)}
              className={`rounded-sm px-3 py-1.5 text-[13px] font-semibold transition-colors ${
                view === each ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
            >
              {each === 'table' ? 'Table' : 'Grid'}
            </button>
          ))}
        </div>
      </div>

      {failed && <p role="alert" className="mt-3 text-sm text-danger">Couldn't load the rows. Reload the page to try again.</p>}
      {!failed && !loaded && <p className="mt-3 text-sm text-fg-faint">Loading…</p>}

      {loaded && hasEarlier && (
        <div className="mt-3">
          <button type="button" onClick={() => void extend('earlier')} disabled={loading !== null} className="btn btn-secondary btn-sm">
            {loading === 'earlier' ? 'Loading…' : 'Show earlier rows'}
          </button>
        </div>
      )}

      {loaded && view === 'table' && (
        <div role="region" aria-label="Rows of the batch" tabIndex={0} className="mt-3 card p-0 overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">
              {rows.length > 0
                ? `Rows ${rowNumber(rows[0].index)} to ${rowNumber(rows[rows.length - 1].index)} of ${batch.rows_total}`
                : 'No rows'}
            </caption>
            <thead className="bg-surface-alt border-b border-border">
              <tr className="text-left text-fg-subtle">
                <th scope="col" className="px-3 py-2 font-semibold">Row</th>
                <th scope="col" className="px-3 py-2 font-semibold">Values</th>
                <th scope="col" className="px-3 py-2 font-semibold">Status</th>
                <th scope="col" className="px-3 py-2 font-semibold">Output</th>
                <th scope="col" className="px-3 py-2 text-right font-semibold">Cost</th>
                <th scope="col" className="px-3 py-2 font-semibold">Error</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.length === 0 && (
                <tr><td colSpan={6} className="px-3 py-5 text-center text-fg-faint">This batch has no rows.</td></tr>
              )}
              {rows.map((row) => {
                const isLinked = linked === row.index;
                const first = sorted(row.outputs)[0];
                const values = row.variables === null ? null : valuesText(row.variables);
                return (
                  <tr
                    key={row.index}
                    ref={isLinked ? marked : undefined}
                    data-row={rowNumber(row.index)}
                    data-linked-row={isLinked ? 'true' : undefined}
                    className={isLinked ? 'bg-surface-alt' : undefined}
                  >
                    <th scope="row" className="whitespace-nowrap px-3 py-2 text-left font-medium tabular-nums text-fg">
                      {row.job_id ? (
                        <Link to={`/history/${row.job_id}`} className="text-brand-600 hover:underline"
                              aria-label={`Row ${rowNumber(row.index)}: open its generation`}>
                          {rowNumber(row.index)}
                        </Link>
                      ) : rowNumber(row.index)}
                      {isLinked && <span className="ml-2 text-[11px] font-normal text-fg-subtle">the row you came from</span>}
                    </th>
                    <td className="max-w-[20rem] px-3 py-2 align-top">
                      {values === null ? (
                        <span className="italic text-fg-subtle">Values deleted</span>
                      ) : values === '' ? (
                        <span className="italic text-fg-subtle">The recipe's defaults</span>
                      ) : (
                        <span className="line-clamp-2 break-words text-fg" title={values}>{values}</span>
                      )}
                    </td>
                    <td className="px-3 py-2 align-top"><RowStatusPill status={row.status} /></td>
                    <td className="px-3 py-2 align-top">
                      {first && row.job_id ? (
                        <Link to={`/history/${row.job_id}`} className="block w-12 overflow-hidden rounded-md"
                              aria-label={`Row ${rowNumber(row.index)}: open its output`}>
                          <OutputThumb output={first} alt="" />
                        </Link>
                      ) : first ? (
                        <span className="block w-12 overflow-hidden rounded-md"><OutputThumb output={first} alt="" /></span>
                      ) : (
                        <span className="text-fg-faint">—</span>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-3 py-2 text-right align-top tabular-nums text-fg">
                      {row.cost_cents === null ? <span className="text-fg-faint">—</span> : formatCents(row.cost_cents)}
                    </td>
                    <td className="max-w-[18rem] px-3 py-2 align-top text-danger">
                      {row.error && <span className="line-clamp-3 break-words" title={row.error}>{row.error}</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {loaded && view === 'grid' && (
        outputs.length === 0 ? (
          <p className="mt-3 text-sm text-fg-muted">None of these rows has an output yet.</p>
        ) : (
          <ul aria-label="Outputs of the rows" className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {outputs.map(({ row, output }) => {
              const name = `Row ${rowNumber(row.index)}${(row.outputs?.length ?? 0) > 1 ? `, output ${output.index + 1}` : ''}`;
              return (
                <li key={`${row.index}-${output.index}`} className="card overflow-hidden p-0">
                  {row.job_id ? (
                    <Link to={`/history/${row.job_id}`} aria-label={`${name}: open its generation`} className="block">
                      <OutputThumb output={output} alt={name} />
                    </Link>
                  ) : (
                    <OutputThumb output={output} alt={name} />
                  )}
                  <p className="truncate px-3 py-2 text-xs text-fg-subtle" title={row.variables ? valuesText(row.variables) : undefined}>
                    <span className="font-semibold text-fg">{name}</span>
                    {row.variables && valuesText(row.variables) !== '' && <> · {valuesText(row.variables)}</>}
                  </p>
                </li>
              );
            })}
          </ul>
        )
      )}

      {loaded && (hasMore || moreFailed) && (
        <div className="mt-4 flex flex-col items-center gap-2">
          {hasMore && (
            <button type="button" onClick={() => void extend('more')} disabled={loading !== null} className="btn btn-secondary">
              {loading === 'more' ? 'Loading…' : 'Load more rows'}
            </button>
          )}
          {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more rows. Try again.</p>}
        </div>
      )}
      {loaded && rows.length > 0 && (
        <p className="mt-2 text-center text-xs text-fg-faint">
          Rows {rowNumber(rows[0].index)}–{rowNumber(rows[rows.length - 1].index)} of {batch.rows_total.toLocaleString('en-US')}
        </p>
      )}
    </section>
  );
}
