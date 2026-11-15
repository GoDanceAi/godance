import type { Batch } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import { BATCH_STATUS_LABEL, finishedRows, isLive } from '../../lib/batches';

function count(n: number): string {
  return n.toLocaleString('en-US');
}

/**
 * What a screen reader is told as the batch goes: while it runs, how far it
 * is in steps of ten percent, so it is not interrupted by every row; once
 * nothing more can change, how it ended.
 */
function spoken(batch: Batch): string {
  const finished = finishedRows(batch);
  const failed = batch.rows_failed > 0 ? `, ${count(batch.rows_failed)} failed` : '';
  if (!isLive(batch)) {
    const skipped = (batch.rows_skipped ?? 0) > 0 ? `, ${count(batch.rows_skipped ?? 0)} skipped` : '';
    return `${BATCH_STATUS_LABEL[batch.status]}. ${count(batch.rows_done)} of ${count(batch.rows_total)} rows done${failed}${skipped}.`;
  }
  const tenths = batch.rows_total > 0 ? Math.floor((finished / batch.rows_total) * 10) : 0;
  return `${BATCH_STATUS_LABEL[batch.status]}. About ${tenths * 10}% of the rows have finished${failed}.`;
}

/**
 * Where a batch stands: how many rows have finished out of all of them,
 * what it has spent against its estimate, and each count by name. The bar
 * is a progress bar to assistive technology, and the steps are announced.
 */
export default function BatchProgress({ batch }: { batch: Batch }) {
  const finished = finishedRows(batch);
  const running = batch.rows_running ?? 0;
  const skipped = batch.rows_skipped ?? 0;
  const waiting = Math.max(0, batch.rows_total - finished - running);
  const share = batch.rows_total > 0 ? Math.min(1, finished / batch.rows_total) : 0;

  return (
    <section aria-labelledby="batch-progress" className="card">
      <h2 id="batch-progress" className="sr-only">Progress</h2>
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
        <p className="text-sm font-semibold text-fg" data-batch-finished>
          {count(finished)} of {count(batch.rows_total)} rows finished
        </p>
        <p className="text-sm text-fg-muted" data-batch-spent>
          Spent <strong className="font-semibold tabular-nums text-fg">{formatCents(batch.spent_cents)}</strong>
          {' '}of an estimated <span className="tabular-nums">{formatCents(batch.estimated_total_cents)}</span>
        </p>
      </div>
      <div
        role="progressbar"
        aria-label="Rows finished"
        aria-valuemin={0}
        aria-valuemax={batch.rows_total}
        aria-valuenow={finished}
        aria-valuetext={`${finished} of ${batch.rows_total} rows finished`}
        className="mt-3 h-2.5 overflow-hidden rounded-full bg-bg-muted"
      >
        <div className="h-full rounded-full bg-brand-600 transition-[width]" style={{ width: `${share * 100}%` }} />
      </div>
      <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs">
        <Count label="Done" value={batch.rows_done} />
        <Count label="Failed" value={batch.rows_failed} tone={batch.rows_failed > 0 ? 'text-danger' : undefined} />
        {batch.rows_running !== undefined && <Count label="Running" value={running} />}
        {waiting > 0 && <Count label="Waiting" value={waiting} />}
        {skipped > 0 && <Count label="Skipped" value={skipped} />}
        <Count label="Total" value={batch.rows_total} />
      </dl>
      <p role="status" aria-live="polite" className="sr-only">{spoken(batch)}</p>
    </section>
  );
}

function Count({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-fg-subtle">{label}</dt>
      <dd className={`font-semibold tabular-nums ${tone ?? 'text-fg'}`}>{count(value)}</dd>
    </div>
  );
}
