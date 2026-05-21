import { useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth, formatCents } from '../lib/auth';

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
  const [amount, setAmount] = useState<number>(25_00);
  const [method, setMethod] = useState<PayMethod>('stripe');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!user) return null;

  const selected = METHODS.find((m) => m.key === method)!;

  const checkout = async () => {
    setError(null);
    setBusy(true);
    try {
      const resp = await apiFetch<TopUpResponse>(selected.endpoint, {
        method: 'POST',
        json: { amountCents: amount },
      });
      // Hosted checkout — redirect the browser. The webhook will credit the
      // balance asynchronously after payment completes.
      window.location.href = resp.checkoutUrl;
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to start checkout');
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Top up</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Current balance: <strong className="text-fg tabular-nums">{formatCents(user.creditBalanceCents)}</strong>. One
        balance, pay any way you like — card or USDT, same credits. They never expire;
        pricing is provider list + flat 5%.
      </p>

      <div className="mt-8 card space-y-6">
        {/* Amount picker */}
        <div>
          <label className="text-sm font-medium text-fg-muted">Amount</label>
          <div className="mt-2 flex flex-wrap gap-2">
            {PRESETS.map((p) => (
              <button
                key={p.cents}
                type="button"
                onClick={() => setAmount(p.cents)}
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
              min={1}
              max={1000}
              step={1}
              value={(amount / 100).toFixed(0)}
              onChange={(e) => setAmount(Math.round(Number(e.target.value) * 100))}
              className="input w-24"
            />
            <span className="text-sm text-fg-subtle">USD ($1 minimum, $1,000 maximum per top-up)</span>
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

        {error && <p className="text-sm text-danger">{error}</p>}

        <button
          onClick={() => void checkout()}
          disabled={busy || amount < 100 || amount > 100_000}
          className="btn btn-primary btn-lg w-full"
        >
          {busy ? 'Opening checkout…' : selected.cta}
        </button>

        <p className="text-xs text-fg-subtle">
          You'll be redirected to {selected.redirectName} to complete payment. Your
          balance updates automatically once the webhook confirms.
        </p>
      </div>
    </div>
  );
}
