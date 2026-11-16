import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../lib/api';
import {
  feePercent,
  listProviderKeys,
  providerName,
  removeProviderKey,
  saveProviderKey,
  type ProviderCredential,
} from '../lib/provider-keys';
import { formatDateTime } from '../lib/time';

/**
 * Settings → Provider keys (round 14, bring your own key). Shown only while
 * at least one provider is offered: with none, the API lists nothing and
 * Settings is as it was.
 */
export default function ProviderKeysSection() {
  const [entries, setEntries] = useState<ProviderCredential[] | null>(null);

  const load = async () => {
    try {
      setEntries(await listProviderKeys());
    } catch {
      // Not offered, or not reachable: the section stays away.
      setEntries(null);
    }
  };

  useEffect(() => { void load(); }, []);

  if (!entries || !entries.some((e) => e.status !== 'not_supported')) return null;
  const fee = feePercent(entries[0].fee_basis_points);

  return (
    <section className="mt-8 card" aria-labelledby="provider-keys-heading">
      <h2 id="provider-keys-heading" className="font-semibold text-fg">Provider keys</h2>
      <p className="mt-1 text-sm text-fg-muted">
        Use your own key with a provider: its requests then go out on your key and the provider bills you,
        while Godance charges a fee of {fee} of the list price.
      </p>
      <ul className="mt-4 space-y-4">
        {entries.map((entry) => (
          <ProviderKeyRow key={entry.provider} entry={entry} onChanged={(next) => {
            setEntries((all) => all && all.map((e) => (e.provider === next.provider ? next : e)));
          }} />
        ))}
      </ul>
    </section>
  );
}

const STATUS: Record<ProviderCredential['status'], { text: string; tone: string }> = {
  valid: { text: 'Working', tone: 'text-success' },
  invalid: { text: 'Refused by the provider: replace it', tone: 'text-danger' },
  unchecked: { text: 'Not checked yet', tone: 'text-fg-muted' },
  unsupported_adapter: { text: 'Stored, but not used yet: requests go out on Godance’s key', tone: 'text-fg-muted' },
  not_supported: { text: 'No longer offered: requests go out on Godance’s key', tone: 'text-fg-muted' },
  none: { text: 'No key', tone: 'text-fg-muted' },
};

function ProviderKeyRow({ entry, onChanged }: {
  entry: ProviderCredential;
  onChanged: (next: ProviderCredential) => void;
}) {
  const name = providerName(entry.provider);
  const stored = entry.status !== 'none';
  const offered = entry.status !== 'not_supported';
  const [key, setKey] = useState('');
  const [fallback, setFallback] = useState(entry.fallback_to_platform);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { setFallback(entry.fallback_to_platform); }, [entry.fallback_to_platform]);

  const fail = (err: unknown, fallbackText: string) =>
    setError(err instanceof ApiError ? err.detail || err.message : fallbackText);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!key.trim()) return;
    setBusy(true);
    setError(null);
    try {
      onChanged(await saveProviderKey(entry.provider, key.trim(), fallback));
      setKey('');
    } catch (err) {
      fail(err, 'The key could not be saved');
    } finally {
      setBusy(false);
    }
  };

  const toggleFallback = async (next: boolean) => {
    setFallback(next);
    if (!stored) return;
    setBusy(true);
    setError(null);
    try {
      onChanged(await saveProviderKey(entry.provider, null, next));
    } catch (err) {
      setFallback(!next);
      fail(err, 'The setting could not be saved');
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!confirm(`Remove your ${name} key? Its requests will go out on Godance’s key at Godance’s price.`)) return;
    setBusy(true);
    setError(null);
    try {
      await removeProviderKey(entry.provider);
      onChanged({ ...entry, last4: null, status: entry.status === 'not_supported' ? 'not_supported' : 'none',
        fallback_to_platform: false, checked_at: null });
    } catch (err) {
      fail(err, 'The key could not be removed');
    } finally {
      setBusy(false);
    }
  };

  const status = STATUS[entry.status];
  const usedFor = entry.used_for.length > 0 ? `Used for ${entry.used_for.join(' and ')}.` : null;

  return (
    <li className="embedded-surface p-4" aria-label={`${name} key`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-medium text-fg">{name}</h3>
        <span className={`text-sm ${status.tone}`} data-testid="provider-key-status">{status.text}</span>
      </div>
      {stored && entry.last4 && (
        <p className="mt-1 text-sm text-fg-muted">
          Key ending in <code className="code-chip">{entry.last4}</code>
          {entry.checked_at ? `, checked ${formatDateTime(entry.checked_at)}` : ''}.
          {usedFor ? ` ${usedFor}` : ''}
        </p>
      )}
      {!stored && usedFor && <p className="mt-1 text-sm text-fg-muted">{usedFor}</p>}
      {offered && (
        <form className="mt-3 flex flex-col gap-2 sm:flex-row" onSubmit={save}>
          <label className="sr-only" htmlFor={`provider-key-${entry.provider}`}>{name} API key</label>
          <input
            id={`provider-key-${entry.provider}`}
            className="input flex-1"
            type="password"
            autoComplete="off"
            spellCheck={false}
            placeholder={stored ? `Replace your ${name} key` : `Your ${name} API key`}
            value={key}
            onChange={(e) => setKey(e.target.value)}
            disabled={busy}
          />
          <button type="submit" className="btn btn-primary" disabled={busy || !key.trim()}>
            {stored ? 'Replace key' : 'Save key'}
          </button>
        </form>
      )}
      {offered && (
        <label className="mt-3 flex items-start gap-2 text-sm text-fg-muted">
          <input type="checkbox" checked={fallback} disabled={busy}
                 onChange={(e) => void toggleFallback(e.target.checked)} />
          <span>If {name} refuses my key, use Godance’s key instead, at Godance’s price.</span>
        </label>
      )}
      {stored && (
        <button type="button" className="mt-3 btn btn-secondary btn-sm" onClick={() => void remove()} disabled={busy}>
          Remove key
        </button>
      )}
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
    </li>
  );
}
