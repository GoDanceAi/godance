import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ApiError, type Batch } from '../lib/api';
import { useAuth } from '../lib/auth';
import { canCancel, cancelBatch, fetchBatch, isBatchId, isLive, resumeBatch, watchBatch } from '../lib/batches';
import { formatDateTime } from '../lib/time';
import BatchDownloads from '../components/batch/BatchDownloads';
import BatchPaused from '../components/batch/BatchPaused';
import BatchProgress from '../components/batch/BatchProgress';
import BatchRows from '../components/batch/BatchRows';
import BatchStatusPill from '../components/batch/BatchStatusPill';

type Load = { batch: Batch } | { missing: true } | { failed: string };

/** /batches/:id — one batch: how far it is, its rows, a pause to deal with, and its results. */
export default function BatchPage() {
  const { batchId = '' } = useParams();
  const [load, setLoad] = useState<Load | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoad(null);
    if (!isBatchId(batchId)) {
      setLoad({ missing: true });
      return;
    }
    fetchBatch(batchId)
      .then((batch) => { if (!cancelled) setLoad({ batch }); })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad(err instanceof ApiError && err.status === 404
          ? { missing: true }
          : { failed: err instanceof ApiError ? err.detail || err.message : 'Could not load this batch.' });
      });
    return () => { cancelled = true; };
  }, [batchId]);

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <Link to="/batches" className="text-sm text-fg-muted hover:text-fg">← Batches</Link>
      {load === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {load && 'missing' in load && (
        <div className="mt-6 card py-10 text-center">
          <p className="font-semibold text-fg">Batch not found.</p>
          <p className="mt-1 text-sm text-fg-muted">It may belong to another account, or the link is incomplete.</p>
        </div>
      )}
      {load && 'failed' in load && <p role="alert" className="mt-6 text-sm text-danger">{load.failed}</p>}
      {load && 'batch' in load && <BatchDetail key={load.batch.id} initial={load.batch} />}
    </div>
  );
}

/** The row `?row=` names, counted from 0 as the API counts, or null. */
function linkedRow(param: string | null): number | null {
  if (param === null || !/^\d{1,6}$/.test(param)) return null;
  return Number(param);
}

function message(err: unknown, fallback: string): string {
  return err instanceof ApiError && !err.message.startsWith('HTTP ') ? err.message : fallback;
}

function BatchDetail({ initial }: { initial: Batch }) {
  const { refresh } = useAuth();
  const [searchParams] = useSearchParams();
  const linked = linkedRow(searchParams.get('row'));
  const [batch, setBatch] = useState(initial);
  const [lost, setLost] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [resumeError, setResumeError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // Read every few seconds while anything in it can still change, and not after.
  const live = isLive(batch) && !lost;
  useEffect(() => {
    if (!live) return;
    return watchBatch(batch.id, setBatch, () => setLost(true));
  }, [batch.id, live]);

  // What the rows cost comes off the balance as they run: the header follows.
  const spent = useRef(batch.spent_cents);
  useEffect(() => {
    if (spent.current === batch.spent_cents) return;
    spent.current = batch.spent_cents;
    void refresh();
  }, [batch.spent_cents, refresh]);

  const resume = async () => {
    setResuming(true);
    setResumeError(null);
    try {
      setBatch(await resumeBatch(batch.id));
    } catch (err) {
      setResumeError(message(err, "Couldn't resume the batch. Try again."));
      // It may have moved on by itself (cancelled, or finished): show it as it stands.
      if (err instanceof ApiError && err.code === 'batch_not_resumable') {
        fetchBatch(batch.id).then(setBatch, () => { /* the message above stands */ });
      }
    } finally {
      setResuming(false);
    }
  };

  const cancel = async () => {
    if (!window.confirm('Cancel this batch? Rows that are running now will finish and be charged. '
      + "Rows that have not started are skipped. This can't be undone.")) {
      return;
    }
    setCancelling(true);
    setCancelError(null);
    try {
      setBatch(await cancelBatch(batch.id));
    } catch (err) {
      setCancelError(message(err, "Couldn't cancel the batch. Try again."));
    } finally {
      setCancelling(false);
    }
  };

  const running = batch.rows_running ?? 0;
  const anyFinished = batch.rows_done + batch.rows_failed > 0;

  return (
    <article className="mt-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">Batch</p>
      <header className="mt-1 flex flex-wrap items-center gap-3">
        <h1 className="min-w-0 break-words text-2xl font-bold tracking-tight text-fg">
          {batch.recipe_title ?? (batch.recipe_id ? 'Recipe batch' : 'Batch of a deleted recipe')}
        </h1>
        <span aria-live="polite"><BatchStatusPill status={batch.status} /></span>
        {canCancel(batch) && (
          <button type="button" onClick={() => void cancel()} disabled={cancelling} className="btn btn-danger btn-sm ml-auto">
            {cancelling ? 'Cancelling…' : 'Cancel batch'}
          </button>
        )}
      </header>
      <p className="mt-1 text-sm text-fg-subtle">
        Started {formatDateTime(batch.created_at)}
        <span aria-hidden="true"> · </span><span className="break-all font-mono text-[12px]">{batch.model}</span>
        {batch.max_concurrency !== undefined && (
          <><span aria-hidden="true"> · </span>{batch.max_concurrency} {batch.max_concurrency === 1 ? 'row' : 'rows'} at a time</>
        )}
        {batch.recipe_id && (
          <>
            <span aria-hidden="true"> · </span>
            <Link to={`/recipes/${batch.recipe_id}`} className="text-brand-600 hover:underline">Open the recipe</Link>
          </>
        )}
      </p>
      {cancelError && <p role="alert" className="mt-2 text-sm text-danger">{cancelError}</p>}

      {(batch.status === 'QUEUED' || batch.status === 'RUNNING') && !lost && (
        <p className="mt-3 text-sm text-fg-muted">This page updates by itself while the batch runs. You can leave it and come back.</p>
      )}
      {batch.status === 'CANCELLED' && (
        <p className="mt-3 text-sm text-fg-muted">
          Cancelled: the rows that had not started were skipped.
          {running > 0 && ` ${running.toLocaleString('en-US')} that had started ${running === 1 ? 'is' : 'are'} still finishing.`}
        </p>
      )}
      {lost && (
        <p role="alert" className="mt-3 text-sm text-danger">
          This batch can no longer be read, so the page has stopped updating. Reload it to try again.
        </p>
      )}

      <div className="mt-5 space-y-6">
        <BatchProgress batch={batch} />
        {batch.status === 'PAUSED' && (
          <BatchPaused batch={batch} resuming={resuming} error={resumeError} onResume={() => void resume()} />
        )}
        {anyFinished && <BatchDownloads batchId={batch.id} />}
        <BatchRows batch={batch} linked={linked} />
      </div>
    </article>
  );
}
