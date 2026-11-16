import { useId, useState } from 'react';
import type { FormEvent } from 'react';
import type { ApiKeySettings, CreatedApiKey, ImageResponse } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import {
  CAP_INVALID,
  capCents,
  createKey,
  expiryOf,
  EXPIRY_INVALID,
  KEY_NAME_MAX_LENGTH,
  keyError,
  today,
} from '../../lib/keys';
import { formatDate } from '../../lib/time';
import { Icon } from '../Brand';
import CopyButton from '../CopyButton';

/**
 * Makes an API key: its name, the most it may spend in a day, and when it
 * stops working. The key itself is shown once, right here, and never again.
 *
 * On the API keys page the cap starts empty and the form also asks how image
 * requests answer. For a recipe ("Create a key for this") the name is the
 * recipe's and the cap starts at 5.00 USD; both can be changed first.
 */
export default function CreateKey({
  defaultName = '',
  defaultDailyCap = '',
  submitLabel = 'Create key',
  namePlaceholder,
  chooseImageResponse = false,
  onCreated,
}: {
  defaultName?: string;
  defaultDailyCap?: string;
  submitLabel?: string;
  namePlaceholder?: string;
  chooseImageResponse?: boolean;
  onCreated?: (created: CreatedApiKey) => void;
}) {
  const id = useId();
  const [name, setName] = useState(defaultName);
  const [cap, setCap] = useState(defaultDailyCap);
  const [day, setDay] = useState('');
  // '' leaves the choice to the server: the new key then shows what it was given.
  const [imageResponse, setImageResponse] = useState<ImageResponse | ''>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [capError, setCapError] = useState(false);
  const [dayError, setDayError] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim() || busy) return;
    const cents = capCents(cap);
    const expiry = expiryOf(day);
    setCapError(cents === 'invalid');
    setDayError(expiry === 'invalid');
    if (cents === 'invalid' || expiry === 'invalid') return;

    // Only what was chosen is sent: a key made with a bare name is what it always was.
    const settings: ApiKeySettings = {};
    if (imageResponse) settings.image_response = imageResponse;
    if (cents !== null) settings.daily_cap_cents = cents;
    if (expiry !== null) settings.expires_at = expiry;

    setBusy(true);
    setError(null);
    try {
      const made = await createKey({ name: name.trim(), ...settings });
      setCreated(made);
      setName(defaultName);
      setCap(defaultDailyCap);
      setDay('');
      setImageResponse('');
      onCreated?.(made);
    } catch (err) {
      setError(keyError(err, 'The key could not be created. Try again.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      {created && <KeyShownOnce created={created} onDismiss={() => setCreated(null)} />}

      <form onSubmit={(e) => void submit(e)} noValidate className={`flex flex-wrap items-end gap-x-3 gap-y-3 ${created ? 'mt-4' : ''}`}>
        <div className="min-w-0 flex-1 basis-56">
          <label htmlFor={`${id}-name`} className="block text-xs font-medium text-fg">Key name</label>
          <input
            id={`${id}-name`}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={namePlaceholder}
            maxLength={KEY_NAME_MAX_LENGTH}
            autoComplete="off"
            aria-label="Key name"
            className="input mt-1"
            disabled={busy}
          />
        </div>
        {chooseImageResponse && (
          <div>
            <label htmlFor={`${id}-images`} className="block text-xs font-medium text-fg">Image responses</label>
            <select
              id={`${id}-images`}
              value={imageResponse}
              onChange={(e) => setImageResponse(e.target.value as ImageResponse | '')}
              aria-label="Image responses for the new key"
              className="input mt-1 w-auto"
              disabled={busy}
            >
              <option value="">Image responses: default</option>
              <option value="sync">Image responses: synchronous</option>
              <option value="async">Image responses: asynchronous</option>
            </select>
          </div>
        )}
        <div className="w-32">
          <label htmlFor={`${id}-cap`} className="block text-xs font-medium text-fg">Daily cap (USD)</label>
          <input
            id={`${id}-cap`}
            type="text"
            inputMode="decimal"
            value={cap}
            onChange={(e) => {
              setCap(e.target.value);
              setCapError(false);
            }}
            placeholder="No cap"
            autoComplete="off"
            aria-invalid={capError || undefined}
            aria-describedby={capError ? `${id}-cap-error` : undefined}
            className="input mt-1 tabular-nums"
            disabled={busy}
          />
        </div>
        <div>
          <label htmlFor={`${id}-expires`} className="block text-xs font-medium text-fg">
            Expires <span className="font-normal text-fg-subtle">(optional)</span>
          </label>
          <input
            id={`${id}-expires`}
            type="date"
            value={day}
            min={today()}
            onChange={(e) => {
              setDay(e.target.value);
              setDayError(false);
            }}
            aria-invalid={dayError || undefined}
            aria-describedby={dayError ? `${id}-expires-error` : undefined}
            className="input mt-1 w-auto"
            disabled={busy}
          />
        </div>
        <button type="submit" disabled={busy || !name.trim()} className="btn btn-primary">
          <Icon name="plus" size={14} />
          {busy ? 'Creating…' : submitLabel}
        </button>
      </form>

      {capError && <p id={`${id}-cap-error`} role="alert" className="mt-2 text-xs text-danger">{CAP_INVALID}</p>}
      {dayError && <p id={`${id}-expires-error`} role="alert" className="mt-2 text-xs text-danger">{EXPIRY_INVALID}</p>}
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
    </div>
  );
}

/** The new key, this once: what to do with it, and what it may spend. */
function KeyShownOnce({ created, onDismiss }: { created: CreatedApiKey; onDismiss: () => void }) {
  const cap = created.daily_cap_cents ?? null;
  const expires = created.expires_at ?? null;
  return (
    <div role="status" aria-label="New API key" className="embedded-surface p-4">
      <p className="min-w-0 break-words text-sm font-semibold text-success">Key created: {created.name}</p>
      <p className="mt-2 text-sm text-fg-muted">
        Set it as <code className="code-chip">GODANCE_API_KEY</code>. Copy it now: it won't be shown again.
      </p>
      <code className="mt-3 block break-all rounded border border-border bg-surface p-3 font-mono text-xs text-fg">
        {created.plaintext}
      </code>
      <p className="mt-2 text-xs text-fg-subtle">
        {cap !== null
          ? `It can spend up to ${formatCents(cap)} a day.`
          : 'It has no daily cap: it can spend up to your balance.'}
        {expires && ` It stops working after ${formatDate(expires)}.`}
        {' '}The code on this site reads the variable, never the key itself.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <CopyButton text={created.plaintext} label="Copy key" />
        <button type="button" onClick={onDismiss} className="btn btn-ghost btn-sm">
          I've saved it — dismiss
        </button>
      </div>
    </div>
  );
}
