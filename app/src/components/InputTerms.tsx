import { useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { INPUT_TERMS } from '../lib/input-terms';

/** The input terms as text: what the user confirms, and what happens to an upload. */
export function InputTermsText() {
  return (
    <div className="text-sm text-fg">
      <p className="font-medium">
        {INPUT_TERMS.title}{' '}
        <span className="font-normal text-fg-subtle">· version {INPUT_TERMS.version}</span>
      </p>
      <p className="mt-2 text-fg-muted">{INPUT_TERMS.intro}</p>
      <ul aria-label="What you confirm" className="mt-2 list-disc space-y-1.5 pl-5">
        {INPUT_TERMS.confirmations.map((line) => <li key={line}>{line}</li>)}
      </ul>
      <p className="mt-4 font-medium">{INPUT_TERMS.handlingTitle}</p>
      <ul aria-label={INPUT_TERMS.handlingTitle} className="mt-2 list-disc space-y-1.5 pl-5 text-fg-muted">
        {INPUT_TERMS.handling.map((line) => <li key={line}>{line}</li>)}
      </ul>
    </div>
  );
}

/**
 * The Accept button for the input terms. Sends the version this app shows;
 * the API refuses any other. `onAccepted` runs once the account's state has
 * been read again.
 */
export function AcceptInputTerms({ onAccepted }: { onAccepted?: () => void }) {
  const { refresh } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const accept = async () => {
    setBusy(true);
    setError(null);
    try {
      await apiFetch('/v1/inputs/terms', { method: 'POST', json: { version: INPUT_TERMS.version } });
      await refresh();
      onAccepted?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not record your acceptance');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <button type="button" onClick={() => void accept()} disabled={busy} className="btn btn-sm btn-primary">
        {busy ? 'Saving…' : 'Accept'}
      </button>
      {error && <p role="alert" className="mt-3 text-sm text-danger">{error}</p>}
    </div>
  );
}
