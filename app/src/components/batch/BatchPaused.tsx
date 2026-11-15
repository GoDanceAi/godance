import { Link } from 'react-router-dom';
import type { Batch } from '../../lib/api';
import { formatCents, useAuth } from '../../lib/auth';

/** Whether the API's sentence about a cap is about an API key's cap rather than the account's. */
function aboutKey(detail: string | null | undefined): boolean {
  return /api[- ]?key/i.test(detail ?? '');
}

/**
 * A paused batch: why it stopped starting rows, where to deal with that,
 * and Resume. Out of credit points at Billing. A spending cap is named in
 * the API's own words and points at API keys (a key's cap) or Settings (the
 * account's). Rows that had already started may still be finishing.
 */
export default function BatchPaused({ batch, resuming, error, onResume }: {
  batch: Batch;
  resuming: boolean;
  /** Why the last Resume was refused. */
  error: string | null;
  onResume: () => void;
}) {
  const { user } = useAuth();
  const running = batch.rows_running ?? 0;
  const left = Math.max(0, batch.rows_total - batch.rows_done - batch.rows_failed - (batch.rows_skipped ?? 0) - running);
  const keyCap = aboutKey(batch.pause_detail);
  const caps = [
    user?.dailyCreditCapCents != null ? `${formatCents(user.dailyCreditCapCents)} a day` : null,
    user?.monthlyCreditCapCents != null ? `${formatCents(user.monthlyCreditCapCents)} a month` : null,
  ].filter((cap): cap is string => cap !== null);

  return (
    <section aria-labelledby="batch-paused" className="card border-[var(--color-warning-fg)]" data-pause-reason={batch.pause_reason ?? 'unknown'}>
      <h2 id="batch-paused" className="text-sm font-semibold text-fg">
        {batch.pause_reason === 'insufficient_credit' ? 'Paused: not enough credit'
          : batch.pause_reason === 'spending_cap' ? 'Paused: a spending cap was reached'
          : 'Paused'}
      </h2>

      {batch.pause_reason === 'insufficient_credit' && (
        <p className="mt-1 text-sm text-fg-muted">
          The balance did not cover the next row, so no more rows are started. Nothing is lost.{' '}
          <Link to="/billing" className="text-brand-600 hover:text-brand-700 underline">Top up on the Billing page</Link>,
          then resume and it goes on from where it stopped.
        </p>
      )}

      {batch.pause_reason === 'spending_cap' && (
        <>
          <p className="mt-1 text-sm text-fg-muted">
            {batch.pause_detail
              ? <>The next row would pass a cap: <span className="text-fg" data-pause-detail>{batch.pause_detail}</span>.</>
              : 'The next row would pass a spending cap.'}
            {' '}Topping up does not lift a cap.
          </p>
          <p className="mt-1 text-sm text-fg-muted">
            {keyCap ? (
              <>
                The cap is on the API key that started this batch; see{' '}
                <Link to="/keys" className="text-brand-600 hover:text-brand-700 underline">API keys</Link>.
                It frees up when its day or month ends (UTC). Resume then.
              </>
            ) : (
              <>
                The cap is on your account{caps.length > 0 && <> ({caps.join(', ')})</>}; see{' '}
                <Link to="/settings" className="text-brand-600 hover:text-brand-700 underline">Settings</Link>.
                It frees up when its day or month ends (UTC). Resume then.
              </>
            )}
          </p>
        </>
      )}

      {batch.pause_reason === null && (
        <p className="mt-1 text-sm text-fg-muted">No more rows are started until it is resumed.</p>
      )}

      <p className="mt-2 text-xs text-fg-subtle">
        {left.toLocaleString('en-US')} {left === 1 ? 'row has' : 'rows have'} not started.
        {running > 0 && ` ${running.toLocaleString('en-US')} that had started ${running === 1 ? 'is' : 'are'} still finishing.`}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <button type="button" onClick={onResume} disabled={resuming} className="btn btn-primary">
          {resuming ? 'Resuming…' : 'Resume'}
        </button>
      </div>
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
    </section>
  );
}
