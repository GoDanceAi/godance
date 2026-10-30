import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError, type ParentalConsentView, type TopUpResponse } from '../lib/api';
import { formatCents } from '../lib/auth';

const PRESETS = [5_00, 10_00, 25_00];

type Load =
  | { state: 'loading' }
  | { state: 'ready'; view: ParentalConsentView }
  | { state: 'error'; status: number; detail: string };

/**
 * The parent's page, opened from the consent email (/parent-consent?token=…).
 * Public: the token is the proof, so calls go out without the browser's own
 * session, if it has one. Shows the direct notice, then the parent approves
 * by paying a top-up on the child's account through Stripe Checkout, which
 * returns here with ?status=approved or ?status=cancelled.
 */
export default function ParentConsentPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const status = params.get('status');

  if (status === 'approved') {
    return (
      <Shell title="Thank you. You've approved the account.">
        <p>
          Your payment went through and the credit is on your child's account. We've emailed them
          to say they can sign in and start. It can take a minute to come through.
        </p>
        <p>
          To review or delete their data, or to withdraw your consent, email{' '}
          <a href="mailto:hello@godance.ai" className="underline">hello@godance.ai</a>.
        </p>
      </Shell>
    );
  }
  if (status === 'cancelled') {
    return (
      <Shell title="No payment was made.">
        <p>
          The account is still waiting for your approval. To approve it, open the link in the
          email again.
        </p>
      </Shell>
    );
  }
  if (!token) {
    return (
      <Shell title="Approve your child's Godance account">
        <p className="text-danger">This page needs the link from the email we sent you.</p>
      </Shell>
    );
  }
  return <ConsentForm token={token} />;
}

function ConsentForm({ token }: { token: string }) {
  const [load, setLoad] = useState<Load>({ state: 'loading' });
  const [amount, setAmount] = useState<number>(10_00);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<ParentalConsentView>(`/v1/auth/parental-consent?token=${encodeURIComponent(token)}`, { auth: false })
      .then((view) => {
        if (cancelled) return;
        setLoad({ state: 'ready', view });
        setAmount((a) => Math.min(Math.max(a, view.minTopupCents), view.maxTopupCents));
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad(err instanceof ApiError
          ? { state: 'error', status: err.status, detail: err.detail || err.message }
          : { state: 'error', status: 0, detail: 'Could not load this page. Try again.' });
      });
    return () => { cancelled = true; };
  }, [token]);

  if (load.state === 'loading') {
    return <Shell title="Approve your child's Godance account"><p>Loading…</p></Shell>;
  }
  if (load.state === 'error') {
    const title = load.status === 410 ? 'This link has expired.'
      : load.status === 404 ? "This link can't be used."
      : 'Something went wrong.';
    return (
      <Shell title={title}>
        <p role="alert">{load.detail}</p>
      </Shell>
    );
  }

  const { view } = load;
  const { notice } = view;
  const valid = amount >= view.minTopupCents && amount <= view.maxTopupCents;

  const approve = async () => {
    setError(null);
    setBusy(true);
    try {
      const resp = await apiFetch<TopUpResponse>('/v1/auth/parental-consent/checkout', {
        method: 'POST',
        auth: false,
        json: { token, amountCents: amount },
      });
      window.location.href = resp.checkoutUrl;
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not start the payment');
      setBusy(false);
    }
  };

  return (
    <Shell title={`Approve ${view.childFirstName}'s Godance account`}>
      <p>
        {view.childFirstName} ({view.childEmailMasked}) signed up for Godance, an app that answers
        questions and makes images and videos with AI, and gave us your email address as their parent
        or guardian. Because they are a child, we need your consent before we collect or use their
        personal information. Until you approve, their account is locked.
      </p>

      <section aria-labelledby="collected" className="space-y-2">
        <h2 id="collected" className="text-base font-semibold text-fg">What Godance collects</h2>
        <ul className="list-disc pl-5 space-y-1">
          {notice.collected.map((item) => <li key={item}>{item}</li>)}
        </ul>
        <p>{notice.notStored}</p>
      </section>

      <section aria-labelledby="sharing" className="space-y-2">
        <h2 id="sharing" className="text-base font-semibold text-fg">Who else sees it</h2>
        <p>{notice.sharing}</p>
        <p>{notice.noSale}</p>
      </section>

      <section aria-labelledby="choices" className="space-y-2">
        <h2 id="choices" className="text-base font-semibold text-fg">Your choices</h2>
        <p>
          {notice.parentRights}{' '}
          <a href={notice.privacyPolicyUrl} target="_blank" rel="noreferrer" className="underline">
            Read the full privacy policy
          </a>.
        </p>
      </section>

      <section aria-labelledby="approve" className="card space-y-4">
        <h2 id="approve" className="text-base font-semibold text-fg">Approve with your card</h2>
        <p>
          You approve by adding credit to {view.childFirstName}'s account with your own card, an
          amount you choose. The card payment is how we confirm that a parent is approving. The
          credit is theirs to spend on Godance.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {PRESETS.filter((c) => c >= view.minTopupCents && c <= view.maxTopupCents).map((cents) => (
            <button
              key={cents}
              type="button"
              onClick={() => setAmount(cents)}
              aria-pressed={amount === cents}
              className={`btn btn-sm ${amount === cents ? 'btn-primary' : 'btn-secondary'}`}
            >
              {formatCents(cents).replace('.00', '')}
            </button>
          ))}
          <label className="flex items-center gap-2 text-fg-subtle">
            <span>Other amount (USD)</span>
            <input
              type="number"
              min={view.minTopupCents / 100}
              max={view.maxTopupCents / 100}
              step={1}
              value={(amount / 100).toFixed(0)}
              onChange={(e) => setAmount(Math.round(Number(e.target.value) * 100))}
              className="input w-24"
            />
          </label>
        </div>
        <p className="text-xs text-fg-subtle">
          {formatCents(view.minTopupCents)} minimum, {formatCents(view.maxTopupCents)} maximum.
        </p>
        <p>
          You'll pay <strong className="text-fg tabular-nums">{valid ? formatCents(amount) : '—'}</strong>.
        </p>
        {error && <p role="alert" className="text-danger">{error}</p>}
        <button
          type="button"
          onClick={() => void approve()}
          disabled={busy || !valid}
          className="btn btn-primary btn-lg w-full"
        >
          {busy ? 'Opening checkout…' : 'Approve and add credit'}
        </button>
        <p className="text-xs text-fg-subtle">
          You'll pay on Stripe's secure checkout page. Not your child, or you don't approve? Close
          this page: the account stays locked. To have it deleted, email {notice.contactEmail}.
        </p>
      </section>
    </Shell>
  );
}

function Shell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-2xl px-6 py-12">
      <h1 className="text-2xl font-bold tracking-tight text-fg">{title}</h1>
      <div className="mt-6 space-y-6 text-sm text-fg-muted">{children}</div>
    </div>
  );
}
