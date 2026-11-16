import { useEffect, useRef, useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useReferral } from '../lib/referral';
import { centsInput, parseUsdCents, formatUsdCents as formatCents } from '../lib/billing';
import AutoRechargePanel from '../components/billing/AutoRechargePanel';
import RecentUsagePanel from '../components/billing/RecentUsagePanel';

interface TopUpResponse {
  sessionId: string;
  checkoutUrl: string;
  amountCents: number;
}

type PayMethod = 'stripe' | 'usdt';

const PRESETS = [
  { label: '$10',  cents: 10_00  },
  { label: '$25',  cents: 25_00  },
  { label: '$100', cents: 100_00 },
  { label: '$500', cents: 500_00 },
];

// Both rails credit the same balance; the user just picks how they pay.
const METHODS: {
  key: PayMethod; title: string; sub: string; endpoint: string; cta: string; redirectName: string;
}[] = [
  {
    key: 'stripe',
    title: 'Card / ACH / SEPA',
    sub: 'via Stripe',
    endpoint: '/v1/billing/topup',
    cta: 'Continue to Stripe',
    redirectName: 'Stripe Checkout',
  },
  {
    key: 'usdt',
    title: 'USDT',
    sub: 'via NOWPayments',
    endpoint: '/v1/billing/nowpayments/topup',
    cta: 'Continue to NOWPayments',
    redirectName: 'NOWPayments',
  },
];

export default function BillingPage() {
  const { user } = useAuth();
  return user ? <BillingContents key={user.id} /> : null;
}

function BillingContents() {
  const { user, refresh } = useAuth();
  const { info: referral } = useReferral();
  const [amountText, setAmountText] = useState('25.00');
  const amount = parseUsdCents(amountText);
  const [method, setMethod] = useState<PayMethod>('stripe');
  const [saveCard, setSaveCard] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const pending = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  if (!user) return null;

  const selected = METHODS.find((m) => m.key === method)!;

  const checkout = async () => {
    if (pending.current || amount === null || amount <= 0) return;
    pending.current = true;
    setError(null);
    setBusy(true);
    try {
      const resp = await apiFetch<TopUpResponse>(selected.endpoint, {
        method: 'POST',
        json: { amountCents: amount, ...(method === 'stripe' && saveCard ? { save_card: true } : {}) },
      });
      // Hosted checkout — redirect the browser. The webhook will credit the
      // balance asynchronously after payment completes.
      if (mounted.current) window.location.href = resp.checkoutUrl;
    } catch (err) {
      if (mounted.current) {
        setError(err instanceof ApiError ? err.detail || err.message : 'Failed to start checkout. No automatic retry was made.');
        setBusy(false);
      }
    } finally {
      pending.current = false;
    }
  };

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Top up</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Current balance: <strong className="text-fg tabular-nums">{formatCents(user.creditBalanceCents)}</strong>. One
        balance, pay any way you like — card or USDT, same credits. They never expire;
        pricing is provider list plus a platform fee (5% text, 12% image, 10–15% video).
      </p>
      <p className="mt-2 text-sm text-fg-muted">Returning from checkout does not confirm payment. Your balance changes after the payment provider confirms it, which may take time.</p>
      <button type="button" className="btn btn-sm btn-secondary mt-3" disabled={refreshing} onClick={async () => {
        setRefreshing(true);
        await refresh();
        if (mounted.current) setRefreshing(false);
      }}>{refreshing ? 'Refreshing balance…' : 'Refresh balance'}</button>
      {referral?.enabled && !!referral.pending_reward_cents && (
        <p className="mt-2 text-sm text-fg-muted">
          You were invited: your first card top-up of {formatCents(referral.min_topup_cents)} or more
          adds <strong className="text-fg">{formatCents(referral.pending_reward_cents)}</strong> to your balance.
        </p>
      )}

      <div className="mt-8 card space-y-6">
        {/* Amount picker */}
        <div>
          <label className="text-sm font-medium text-fg-muted">Amount</label>
          <div className="mt-2 flex flex-wrap gap-2">
            {PRESETS.map((p) => (
              <button
                key={p.cents}
                type="button"
                onClick={() => setAmountText(centsInput(p.cents))}
                disabled={busy}
                className={`btn btn-sm ${amount === p.cents ? 'btn-primary' : 'btn-secondary'}`}
              >
                {p.label}
              </button>
            ))}
          </div>
          <div className="mt-3 flex items-center gap-2">
            <span className="text-sm text-fg-subtle">Or custom:</span>
            <input
              type="number"
              aria-label="Top-up amount (USD)"
              min={0.01}
              step={0.01}
              value={amountText}
              onChange={(e) => setAmountText(e.target.value)}
              disabled={busy}
              className="input w-24"
            />
            <span className="text-sm text-fg-subtle">USD. The server validates the deployment’s top-up limits.</span>
          </div>
        </div>

        {/* Payment method */}
        <div>
          <label className="text-sm font-medium text-fg-muted">Payment method</label>
          <div className="mt-2 grid grid-cols-2 gap-3">
            {METHODS.map((m) => {
              const active = m.key === method;
              return (
                <button
                  key={m.key}
                  type="button"
                  onClick={() => setMethod(m.key)}
                  disabled={busy}
                  aria-pressed={active}
                  className="text-left p-4 rounded-md border transition-colors hover:border-border-strong"
                  style={{
                    background: 'var(--color-surface)',
                    borderColor: active ? 'var(--color-primary)' : 'var(--color-border)',
                    boxShadow: active ? 'var(--shadow-focus)' : undefined,
                  }}
                >
                  <p className="font-semibold text-sm text-fg">{m.title}</p>
                  <p className="text-xs text-fg-subtle mt-0.5">{m.sub}</p>
                </button>
              );
            })}
          </div>
        </div>

        {method === 'stripe' && <label className="flex items-start gap-3 text-sm text-fg-muted">
          <input type="checkbox" className="mt-1" checked={saveCard} disabled={busy} onChange={(event) => setSaveCard(event.target.checked)} />
          <span>Save my card for future off-session charges (when I am not present). Saving a card does not enable auto-recharge; review and confirm that separately below. Bank methods cannot be used for auto-recharge.</span>
        </label>}

        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        {(amount === null || amount <= 0) && <p className="text-sm text-danger">Enter a positive USD amount with up to two decimal places.</p>}

        <button
          onClick={() => void checkout()}
          disabled={busy || amount === null || amount <= 0}
          className="btn btn-primary btn-lg w-full"
        >
          {busy ? 'Opening checkout…' : selected.cta}
        </button>

        <p className="text-xs text-fg-subtle">
          You'll be redirected to {selected.redirectName} to complete payment. Your
          balance updates automatically once the webhook confirms.
        </p>
      </div>
      <AutoRechargePanel />
      <RecentUsagePanel />
    </div>
  );
}
