import { useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth, formatCents } from '../lib/auth';

interface TopUpResponse {
  sessionId: string;
  checkoutUrl: string;
  amountCents: number;
}

const PRESETS = [
  { label: '$10',  cents: 10_00  },
  { label: '$25',  cents: 25_00  },
  { label: '$100', cents: 100_00 },
  { label: '$500', cents: 500_00 },
];

export default function BillingPage() {
  const { user } = useAuth();
  const [amount, setAmount] = useState<number>(25_00);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!user) return null;

  const checkout = async () => {
    setError(null);
    setBusy(true);
    try {
      const resp = await apiFetch<TopUpResponse>('/v1/billing/topup', {
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
        Current balance: <strong className="text-fg tabular-nums">{formatCents(user.creditBalanceCents)}</strong>. Credits never
        expire; pricing is provider list + flat 5%.
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
          <div className="mt-2 p-4 rounded-md border border-brand-600 bg-brand-50">
            <p className="font-semibold text-sm text-fg">Card / ACH / SEPA</p>
            <p className="text-xs text-fg-subtle mt-0.5">via Stripe</p>
          </div>
        </div>

        {error && <p className="text-sm text-danger">{error}</p>}

        <button
          onClick={() => void checkout()}
          disabled={busy || amount < 100 || amount > 100_000}
          className="btn btn-primary btn-lg w-full"
        >
          {busy ? 'Opening checkout…' : 'Continue to Stripe'}
        </button>

        <p className="text-xs text-fg-subtle">
          You'll be redirected to Stripe Checkout to complete payment. Your
          balance updates automatically once the webhook confirms.
        </p>
      </div>
    </div>
  );
}
