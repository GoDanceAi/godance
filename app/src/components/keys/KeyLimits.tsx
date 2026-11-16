import { useId, useState } from 'react';
import type { FormEvent } from 'react';
import type { ApiKeySettings, ApiKeySummary } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import {
  CAP_INVALID,
  capCents,
  capText,
  dayOf,
  expiryOf,
  expiryOfKey,
  EXPIRY_INVALID,
  keyError,
  today,
  updateKey,
} from '../../lib/keys';
import Dialog from '../Dialog';

/**
 * A key's limits, to change: the most it may spend in a day and in a month,
 * and the day it stops working. Only what was changed is sent; a field
 * emptied is cleared.
 */
export default function KeyLimits({ apiKey, onSaved, onClose }: {
  apiKey: ApiKeySummary;
  onSaved: (updated: ApiKeySummary) => void;
  onClose: () => void;
}) {
  const id = useId();
  const expiry = expiryOfKey(apiKey);
  const [daily, setDaily] = useState(capText(apiKey.daily_cap_cents));
  const [monthly, setMonthly] = useState(capText(apiKey.monthly_cap_cents));
  const [day, setDay] = useState(dayOf(expiry));
  const [busy, setBusy] = useState(false);
  const [problems, setProblems] = useState<{ daily?: boolean; monthly?: boolean; day?: boolean }>({});
  const [error, setError] = useState<string | null>(null);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const dailyCents = capCents(daily);
    const monthlyCents = capCents(monthly);
    // A day left as it was keeps the key's exact expiry, even one already past.
    const dayChanged = day !== dayOf(expiry);
    const nextExpiry = dayChanged ? expiryOf(day) : expiry;
    setProblems({ daily: dailyCents === 'invalid', monthly: monthlyCents === 'invalid', day: nextExpiry === 'invalid' });
    if (dailyCents === 'invalid' || monthlyCents === 'invalid' || nextExpiry === 'invalid') return;

    const changes: ApiKeySettings = {};
    if (dailyCents !== (apiKey.daily_cap_cents ?? null)) changes.daily_cap_cents = dailyCents;
    if (monthlyCents !== (apiKey.monthly_cap_cents ?? null)) changes.monthly_cap_cents = monthlyCents;
    if (dayChanged) changes.expires_at = nextExpiry;
    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }

    setBusy(true);
    setError(null);
    try {
      onSaved(await updateKey(apiKey.id, changes));
      onClose();
    } catch (err) {
      setError(keyError(err, "Couldn't save this key's limits. Try again."));
      setBusy(false);
    }
  };

  return (
    <Dialog title={`Limits for ${apiKey.name}`} onClose={onClose}>
      <p className="text-sm text-fg-muted">
        Once the key has spent a cap, its requests are refused until the day or the month is over. Days and
        months are counted in UTC. So far it has spent{' '}
        <strong className="font-semibold tabular-nums text-fg">{formatCents(apiKey.spent_today_cents ?? 0)}</strong> today
        and{' '}
        <strong className="font-semibold tabular-nums text-fg">{formatCents(apiKey.spent_month_cents ?? 0)}</strong> this
        month.
      </p>
      <form onSubmit={(e) => void save(e)} noValidate className="mt-4 space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor={`${id}-daily`} className="block text-sm font-medium text-fg">Daily cap (USD)</label>
            <input
              id={`${id}-daily`}
              type="text"
              inputMode="decimal"
              value={daily}
              onChange={(e) => setDaily(e.target.value)}
              placeholder="No cap"
              autoComplete="off"
              aria-invalid={problems.daily || undefined}
              className="input mt-1.5 tabular-nums"
              disabled={busy}
            />
          </div>
          <div>
            <label htmlFor={`${id}-monthly`} className="block text-sm font-medium text-fg">Monthly cap (USD)</label>
            <input
              id={`${id}-monthly`}
              type="text"
              inputMode="decimal"
              value={monthly}
              onChange={(e) => setMonthly(e.target.value)}
              placeholder="No cap"
              autoComplete="off"
              aria-invalid={problems.monthly || undefined}
              className="input mt-1.5 tabular-nums"
              disabled={busy}
            />
          </div>
        </div>
        <div>
          <label htmlFor={`${id}-expires`} className="block text-sm font-medium text-fg">Expires</label>
          <input
            id={`${id}-expires`}
            type="date"
            value={day}
            min={today()}
            onChange={(e) => setDay(e.target.value)}
            aria-invalid={problems.day || undefined}
            aria-describedby={`${id}-expires-help`}
            className="input mt-1.5 w-auto"
            disabled={busy}
          />
          <p id={`${id}-expires-help`} className="mt-1.5 text-xs text-fg-subtle">
            The key works until the end of that day. Empty: it does not expire.
          </p>
        </div>

        {(problems.daily || problems.monthly) && <p role="alert" className="text-xs text-danger">{CAP_INVALID}</p>}
        {problems.day && <p role="alert" className="text-xs text-danger">{EXPIRY_INVALID}</p>}
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}

        <div className="flex flex-wrap gap-2">
          <button type="submit" disabled={busy} className="btn btn-primary btn-sm">
            {busy ? 'Saving…' : 'Save limits'}
          </button>
          <button type="button" onClick={onClose} disabled={busy} className="btn btn-ghost btn-sm">Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}
