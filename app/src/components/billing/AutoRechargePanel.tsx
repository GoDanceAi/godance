import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import { centsInput, DRAFT_AUTO_RECHARGE_CONSENT, getAutoRecharge, parseUsdCents, setAutoRecharge, type AutoRecharge } from '../../lib/billing';

const describe = (error: unknown) => error instanceof Error ? error.message : 'Could not load auto-recharge.';

export default function AutoRechargePanel() {
  const [config, setConfig] = useState<AutoRecharge | null>(null);
  const [threshold, setThreshold] = useState('5.00');
  const [amount, setAmount] = useState('25.00');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState<{ threshold_cents: number; topup_cents: number } | null>(null);
  const mounted = useRef(true);
  const pending = useRef(false);
  const readId = useRef(0);

  async function load(replaceInputs = false, signal?: AbortSignal) {
    const request = ++readId.current;
    setLoading(true);
    setError('');
    try {
      const next = await getAutoRecharge(signal);
      if (!mounted.current || request !== readId.current) return;
      setConfig(next);
      if (replaceInputs) {
        if (next.threshold_cents !== null) setThreshold(centsInput(next.threshold_cents));
        if (next.topup_cents !== null) setAmount(centsInput(next.topup_cents));
      }
    } catch (err) {
      if (mounted.current && request === readId.current && !signal?.aborted) setError(describe(err));
    } finally {
      if (mounted.current && request === readId.current) setLoading(false);
    }
  }

  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController();
    void load(true, controller.signal);
    return () => { mounted.current = false; controller.abort(); ++readId.current; };
  }, []);

  const thresholdCents = parseUsdCents(threshold);
  const topupCents = parseUsdCents(amount);
  const valid = thresholdCents !== null && topupCents !== null && topupCents > 0;

  async function write(enabled: boolean) {
    if (pending.current || (enabled && !review)) return;
    pending.current = true;
    setBusy(true);
    setError('');
    try {
      const next = await setAutoRecharge(enabled ? { enabled, ...review! } : { enabled });
      if (!mounted.current) return;
      setConfig(next);
      setReview(null);
    } catch (err) {
      if (!mounted.current) return;
      if (err instanceof ApiError && err.status === 409) {
        // A non-card method may have been cleared. Refresh status, preserving draft inputs.
        await load(false);
      }
      if (mounted.current) {
        setError(`${describe(err)} Your change was not confirmed. Refresh status before trying again.`);
        setReview(null);
      }
    } finally {
      pending.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  return <section className="card mt-8 space-y-4" aria-labelledby="auto-recharge-title">
    <h2 id="auto-recharge-title" className="text-lg font-semibold text-fg">Auto-recharge</h2>
    {loading && <p role="status">Loading auto-recharge…</p>}
    {error && <p role="alert" className="text-sm text-danger">{error}</p>}
    <button type="button" className="btn btn-sm btn-secondary" disabled={loading || busy} onClick={() => void load(!config)}>Refresh auto-recharge status</button>
    {config && <>
      <p role="status">Auto-recharge is {config.enabled ? 'enabled' : 'disabled'}.</p>
      <p className="text-sm text-fg-muted">Confirmed threshold: {config.threshold_cents === null ? 'Not configured' : formatCents(config.threshold_cents)}. Recharge: {config.topup_cents === null ? 'Not configured' : formatCents(config.topup_cents)}.</p>
      <p className="text-sm text-fg-muted">{config.payment_method_on_file ? 'A saved payment method is on file. Enabling verifies that it is a card.' : 'No saved card is available. Save a card during a Stripe top-up before enabling.'}</p>
      {config.last_failure_at && <p className="text-sm text-danger">Last auto-recharge failure: {new Date(config.last_failure_at).toLocaleString()}. {config.last_failure_reason || 'No further details available.'}</p>}
      <form className="space-y-3" onSubmit={(event) => {
        event.preventDefault();
        if (valid && config.payment_method_on_file && !busy && !loading && !error) setReview({ threshold_cents: thresholdCents!, topup_cents: topupCents! });
      }}>
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="text-sm text-fg-muted">Balance threshold (USD)<input className="input mt-1 w-full" inputMode="decimal" value={threshold} onChange={(event) => setThreshold(event.target.value)} disabled={busy} /></label>
          <label className="text-sm text-fg-muted">Recharge amount (USD)<input className="input mt-1 w-full" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} disabled={busy} /></label>
        </div>
        {!valid && <p className="text-sm text-danger">Enter valid USD amounts with up to two decimal places. Threshold may be zero; recharge must be positive.</p>}
        <p className="text-xs text-fg-subtle">The server validates the deployment’s amount limits. Changing these fields does not enable or update charging until you review and confirm.</p>
        <div className="flex flex-wrap gap-3">
          <button className="btn btn-primary" disabled={!valid || !config.payment_method_on_file || busy || loading || !!error}>Review auto-recharge</button>
          {config.enabled && <button type="button" className="btn btn-secondary" disabled={busy || loading || !!error} onClick={() => void write(false)}>Disable auto-recharge</button>}
        </div>
      </form>
    </>}
    {review && <RechargeReview threshold={review.threshold_cents} amount={review.topup_cents} busy={busy} onCancel={() => { if (!pending.current) setReview(null); }} onConfirm={() => void write(true)} />}
  </section>;
}

function RechargeReview({ threshold, amount, busy, onCancel, onConfirm }: { threshold: number; amount: number; busy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => { dialog.current?.close(); before?.focus(); };
  }, []);
  return <dialog ref={dialog} aria-labelledby="recharge-review-title" onCancel={(event) => { event.preventDefault(); onCancel(); }} className="m-auto w-full max-w-lg rounded-md border border-border bg-surface p-6 text-fg shadow-xl backdrop:bg-black/40">
    <h2 id="recharge-review-title" className="text-lg font-semibold">Review auto-recharge</h2>
    <p className="mt-4">When your balance falls below <strong>{formatCents(threshold)} USD</strong>, charge your saved card <strong>{formatCents(amount)} USD</strong> to add credits.</p>
    <p className="mt-4 text-sm text-fg-muted">{DRAFT_AUTO_RECHARGE_CONSENT}</p>
    <div className="mt-6 flex flex-wrap gap-3">
      <button autoFocus className="btn btn-secondary" disabled={busy} onClick={onCancel}>Cancel</button>
      <button className="btn btn-primary" disabled={busy} onClick={onConfirm}>{busy ? 'Saving…' : 'Confirm auto-recharge'}</button>
    </div>
  </dialog>;
}
