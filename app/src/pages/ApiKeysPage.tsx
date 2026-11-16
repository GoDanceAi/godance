import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError, type ApiKeySummary, type ImageResponse } from '../lib/api';
import { formatCents } from '../lib/auth';
import {
  capState,
  DEFAULT_DAILY_CAP,
  expiryOfKey,
  hasCaps,
  isExpired,
  KEY_NAME_MAX_LENGTH,
  listKeys,
  revokeKey,
  updateKey,
} from '../lib/keys';
import { formatDate } from '../lib/time';
import CreateKey from '../components/keys/CreateKey';
import KeyLimits from '../components/keys/KeyLimits';

const IMAGE_RESPONSE_LABEL: Record<ImageResponse, string> = {
  async: 'Asynchronous',
  sync: 'Synchronous',
};

const COLUMNS = 6;

/**
 * /keys — the account's API keys: make one, see what each may spend and has
 * spent, change its limits and how its image requests answer, revoke it.
 * `?for=<name>` opens it ready to make a key for a recipe ("Run at scale"):
 * named after it, with the 5.00 USD daily cap such a key starts with.
 */
export default function ApiKeysPage() {
  const [searchParams] = useSearchParams();
  const forName = (searchParams.get('for') ?? '').trim().slice(0, KEY_NAME_MAX_LENGTH);
  const [keys, setKeys] = useState<ApiKeySummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The key whose image response setting is being saved.
  const [savingId, setSavingId] = useState<string | null>(null);
  // The key whose limits are open in the dialog.
  const [editing, setEditing] = useState<ApiKeySummary | null>(null);

  const load = async () => {
    try {
      setKeys(await listKeys());
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load keys');
    }
  };

  useEffect(() => { void load(); }, []);

  const revoke = async (id: string) => {
    if (!confirm('Revoke this key? Apps using it will start receiving 401.')) return;
    try {
      await revokeKey(id);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to revoke key');
    }
  };

  const changeImageResponse = async (id: string, imageResponse: ImageResponse) => {
    setSavingId(id);
    setError(null);
    try {
      const updated = await updateKey(id, { image_response: imageResponse });
      setKeys((cur) => cur && cur.map((k) => (k.id === id ? { ...k, ...updated } : k)));
    } catch (err) {
      // The list is unchanged, so the control goes back to what the key still is.
      setError(err instanceof ApiError && !err.message.startsWith('HTTP ')
        ? err.message
        : "Couldn't change how this key's image requests answer. Try again.");
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">API keys</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Use these with the OpenAI SDK by setting <code className="code-chip">api_key</code>.
        Plaintext is shown once at creation — there's no way to recover it later.
      </p>
      <p className="mt-2 text-sm text-fg-muted">
        A key can carry a daily and a monthly cap. Once it has spent that much, its requests are refused until the
        day or the month is over, so a script that runs away stops there.
      </p>

      <div className="mt-8">
        {forName && (
          <p className="mb-3 text-sm text-fg">
            A key for <span className="font-semibold">“{forName}”</span>: named after it, with a daily cap you can change.
          </p>
        )}
        {/* Remounted when the link changes, so its fields start from the new name. */}
        <CreateKey
          key={forName}
          defaultName={forName}
          defaultDailyCap={forName ? DEFAULT_DAILY_CAP : ''}
          submitLabel={forName ? 'Create a key for this' : 'Create key'}
          namePlaceholder="Key name (e.g. 'production backend')"
          chooseImageResponse
          onCreated={() => void load()}
        />
      </div>

      <p id="image-responses-help" className="mt-3 text-xs text-fg-muted">
        Synchronous: image requests wait for the result, as OpenAI's API does.
        Asynchronous: they return a job to poll.
      </p>

      {error && <p role="alert" className="mt-3 text-sm text-danger">{error}</p>}

      {/* The table scrolls inside its card on a narrow screen, so the page never moves sideways. */}
      <div className="mt-8 card overflow-x-auto" style={{ padding: 0 }}>
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle">
              <th scope="col" className="px-3 py-2.5 font-semibold">Key</th>
              <th scope="col" className="px-3 py-2.5 font-semibold">Image responses</th>
              <th scope="col" className="px-3 py-2.5 font-semibold">Spent, and its cap</th>
              <th scope="col" className="px-3 py-2.5 font-semibold">Expires</th>
              <th scope="col" className="px-3 py-2.5 font-semibold">Created</th>
              {/* Named for a screen reader without a hidden element: one positioned out of the
                  flow would not scroll with the table, and would widen the page on a phone. */}
              <th scope="col" aria-label="Actions" className="px-3 py-2.5" />
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {keys === null && (
              <tr><td colSpan={COLUMNS} className="px-4 py-6 text-center text-fg-faint">Loading…</td></tr>
            )}
            {keys?.length === 0 && (
              <tr><td colSpan={COLUMNS} className="px-4 py-6 text-center text-fg-faint">No keys yet.</td></tr>
            )}
            {keys?.map((k) => (
              <tr key={k.id}>
                <td className="px-3 py-3">
                  <p className="min-w-[9rem] break-words font-medium text-fg">{k.name}</p>
                  <p className="mt-0.5 whitespace-nowrap font-mono text-xs text-fg-muted">gd_live_{k.prefix}…</p>
                </td>
                <td className="px-3 py-3 text-fg-muted">
                  {k.image_response ? (
                    <select
                      value={k.image_response}
                      onChange={(e) => void changeImageResponse(k.id, e.target.value as ImageResponse)}
                      disabled={savingId !== null}
                      aria-label={`Image responses for ${k.name}`}
                      aria-describedby="image-responses-help"
                      className="input w-auto py-1 text-xs"
                    >
                      {(['async', 'sync'] as const).map((mode) => (
                        <option key={mode} value={mode}>{IMAGE_RESPONSE_LABEL[mode]}</option>
                      ))}
                    </select>
                  ) : '—'}
                </td>
                <td className="px-3 py-3 text-fg-muted">
                  {hasCaps(k) ? (
                    <>
                      <Spend label="Today" spent={k.spent_today_cents ?? 0} cap={k.daily_cap_cents} />
                      <Spend label="This month" spent={k.spent_month_cents ?? 0} cap={k.monthly_cap_cents} />
                    </>
                  ) : '—'}
                </td>
                <td className="px-3 py-3 text-fg-muted"><Expiry apiKey={k} /></td>
                <td className="whitespace-nowrap px-3 py-3 text-xs text-fg-muted">
                  <p>{new Date(k.createdAt).toLocaleDateString()}</p>
                  <p className="mt-0.5 text-fg-subtle">
                    {k.lastUsedAt ? `Last used ${new Date(k.lastUsedAt).toLocaleDateString()}` : 'Not used yet'}
                  </p>
                </td>
                <td className="whitespace-nowrap px-3 py-3 text-right">
                  {/* One above the other, so the table fits the page's width on a desktop. */}
                  <div className="flex flex-col items-end gap-1.5">
                    {hasCaps(k) && (
                      <button
                        type="button"
                        onClick={() => setEditing(k)}
                        aria-label={`Edit limits for ${k.name}`}
                        className="text-xs font-medium text-brand-600 hover:underline"
                      >
                        Edit limits
                      </button>
                    )}
                    <button
                      onClick={() => void revoke(k.id)}
                      className="text-xs text-danger hover:underline font-medium"
                    >
                      Revoke
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing && (
        <KeyLimits
          apiKey={editing}
          onClose={() => setEditing(null)}
          onSaved={(updated) => setKeys((cur) => cur && cur.map((k) => (k.id === updated.id ? { ...k, ...updated } : k)))}
        />
      )}
    </div>
  );
}

const SPEND_TONE = { ok: 'text-fg', near: 'text-warning', over: 'text-danger' } as const;

/** What a key has spent in a day or a month, against its cap for it. */
function Spend({ label, spent, cap }: { label: string; spent: number; cap: number | null | undefined }) {
  const state = capState(spent, cap);
  return (
    <p className="whitespace-nowrap text-xs">
      <span className="text-fg-subtle">{label}</span>{' '}
      <span className={`font-semibold tabular-nums ${SPEND_TONE[state]}`}>{formatCents(spent)}</span>{' '}
      {cap === null || cap === undefined
        ? <span className="text-fg-subtle">· no cap</span>
        : <span>of <span className="tabular-nums">{formatCents(cap)}</span>{state === 'over' && ' · cap reached'}</span>}
    </p>
  );
}

/** When a key stops working, and whether it already has. */
function Expiry({ apiKey }: { apiKey: ApiKeySummary }) {
  const expiry = expiryOfKey(apiKey);
  if (expiry === null) return <span className="text-xs text-fg-subtle">Never</span>;
  return (
    <span className="whitespace-nowrap text-xs">
      {isExpired(apiKey) && <span className="pill pill-rose mr-1.5">Expired</span>}
      <time dateTime={expiry}>{formatDate(expiry)}</time>
    </span>
  );
}
