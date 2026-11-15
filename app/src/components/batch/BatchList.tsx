import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, type Batch } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import { finishedRows, listBatches } from '../../lib/batches';
import { formatAge, formatDateTime } from '../../lib/time';
import BatchStatusPill from './BatchStatusPill';

/**
 * Batches, newest first, a page at a time: each with its status, how far it
 * is and what it has spent. With `recipeId`, only that recipe's batches, and
 * the recipe is not named again on each line.
 */
export default function BatchList({ recipeId, pageSize, empty }: {
  recipeId?: string;
  pageSize: number;
  /** What to show when there are none. */
  empty: ReactNode;
}) {
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listBatches({ limit: pageSize, recipeId })
      .then((page) => {
        if (cancelled) return;
        setBatches(page);
        setHasMore(page.length === pageSize);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.detail || err.message : 'Could not load the batches.');
      });
    return () => { cancelled = true; };
  }, [recipeId, pageSize]);

  const loadMore = async () => {
    const last = batches?.at(-1);
    if (!last) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listBatches({ limit: pageSize, recipeId, before: last.id });
      setBatches((current) => {
        const seen = new Set(current?.map((b) => b.id));
        return [...(current ?? []), ...page.filter((b) => !seen.has(b.id))];
      });
      setHasMore(page.length === pageSize);
    } catch {
      setMoreFailed(true);
    } finally {
      setLoadingMore(false);
    }
  };

  if (error) return <p role="alert" className="mt-3 text-sm text-danger">{error}</p>;
  if (batches === null) return <p className="mt-3 text-sm text-fg-faint">Loading…</p>;
  if (batches.length === 0) return <>{empty}</>;

  return (
    <>
      <ul aria-label="Batches" className="mt-3 card divide-y divide-border p-0">
        {batches.map((batch) => {
          const finished = finishedRows(batch);
          const share = batch.rows_total > 0 ? Math.min(1, finished / batch.rows_total) : 0;
          const rows = `${batch.rows_total.toLocaleString('en-US')} ${batch.rows_total === 1 ? 'row' : 'rows'}`;
          return (
            <li key={batch.id} className="relative flex flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3 hover:bg-surface-alt">
              <div className="min-w-0 flex-1 basis-48">
                <Link to={`/batches/${batch.id}`} className="break-words font-medium text-fg after:absolute after:inset-0">
                  {recipeId ? `Batch of ${rows}` : batch.recipe_title ?? (batch.recipe_id ? 'A recipe' : 'A deleted recipe')}
                  <span className="sr-only">, batch started {formatDateTime(batch.created_at)}</span>
                </Link>
                <p className="mt-0.5 text-xs text-fg-subtle">
                  <time dateTime={batch.created_at} title={formatDateTime(batch.created_at)}>{formatAge(batch.created_at)}</time>
                  <span aria-hidden="true"> · </span>
                  <span className="break-all font-mono text-[11px]">{batch.model}</span>
                </p>
              </div>
              <BatchStatusPill status={batch.status} />
              <div className="w-36">
                <p className="text-xs text-fg-muted">
                  <span className="tabular-nums">{finished.toLocaleString('en-US')} of {rows}</span>
                  {batch.rows_failed > 0 && <span className="text-danger"> · {batch.rows_failed.toLocaleString('en-US')} failed</span>}
                </p>
                {/* The sentence above says it; the bar is for the eye. */}
                <div aria-hidden="true" className="mt-1 h-1.5 overflow-hidden rounded-full bg-bg-muted">
                  <div className="h-full rounded-full bg-brand-600" style={{ width: `${share * 100}%` }} />
                </div>
              </div>
              <p className="w-32 text-right text-sm">
                <span className="font-semibold tabular-nums text-fg">{formatCents(batch.spent_cents)}</span>
                <span className="block text-xs text-fg-subtle">of est. {formatCents(batch.estimated_total_cents)}</span>
              </p>
            </li>
          );
        })}
      </ul>
      {hasMore && (
        <div className="mt-6 flex flex-col items-center gap-2">
          <button type="button" onClick={() => void loadMore()} disabled={loadingMore} className="btn btn-secondary">
            {loadingMore ? 'Loading…' : 'Load more'}
          </button>
          {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more. Try again.</p>}
        </div>
      )}
    </>
  );
}
