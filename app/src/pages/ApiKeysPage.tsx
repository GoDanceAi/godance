import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { apiFetch, ApiError, type ApiKeySummary } from '../lib/api';
import { Icon } from '../components/Brand';

interface CreateResponse {
  id: string;
  name: string;
  prefix: string;
  plaintext: string;     // ONLY returned at creation; never refetchable
  createdAt: string;
}

export default function ApiKeysPage() {
  const [keys, setKeys] = useState<ApiKeySummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [busy, setBusy] = useState(false);
  const [justCreated, setJustCreated] = useState<CreateResponse | null>(null);

  const load = async () => {
    try {
      const data = await apiFetch<ApiKeySummary[]>('/v1/keys');
      setKeys(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load keys');
    }
  };

  useEffect(() => { void load(); }, []);

  const createKey = async (e: FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const created = await apiFetch<CreateResponse>('/v1/keys', {
        method: 'POST',
        json: { name: newName.trim() },
      });
      setJustCreated(created);
      setNewName('');
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to create key');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id: string) => {
    if (!confirm('Revoke this key? Apps using it will start receiving 401.')) return;
    try {
      await apiFetch(`/v1/keys/${id}`, { method: 'DELETE' });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to revoke key');
    }
  };

  return (
    <div className="mx-auto max-w-3xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">API keys</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Use these with the OpenAI SDK by setting <code className="code-chip">api_key</code>.
        Plaintext is shown once at creation — there's no way to recover it later.
      </p>

      {justCreated && (
        <div className="mt-6 rounded-md border border-emerald-300 bg-emerald-50 p-4">
          <p className="text-sm font-semibold text-emerald-900">
            Key created: {justCreated.name}
          </p>
          <p className="mt-2 text-xs text-emerald-900">Copy it now — it won't be shown again.</p>
          <code className="mt-3 block break-all text-xs font-mono bg-surface border border-border rounded p-3 text-fg">
            {justCreated.plaintext}
          </code>
          <button
            onClick={() => setJustCreated(null)}
            className="mt-3 text-xs text-emerald-700 hover:underline"
          >
            I've saved it — dismiss
          </button>
        </div>
      )}

      <form onSubmit={createKey} className="mt-8 flex gap-2">
        <input
          type="text"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          placeholder="Key name (e.g. 'production backend')"
          className="input flex-1"
          disabled={busy}
        />
        <button
          type="submit"
          disabled={busy || !newName.trim()}
          className="btn btn-primary"
        >
          <Icon name="plus" size={14} />
          {busy ? 'Creating…' : 'Create key'}
        </button>
      </form>

      {error && <p className="mt-3 text-sm text-danger">{error}</p>}

      <div className="mt-8 card overflow-hidden" style={{ padding: 0 }}>
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle">
              <th className="px-4 py-2.5 font-semibold">Name</th>
              <th className="px-4 py-2.5 font-semibold">Prefix</th>
              <th className="px-4 py-2.5 font-semibold">Created</th>
              <th className="px-4 py-2.5 font-semibold">Last used</th>
              <th className="px-4 py-2.5"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {keys === null && (
              <tr><td colSpan={5} className="px-4 py-6 text-center text-fg-faint">Loading…</td></tr>
            )}
            {keys?.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-6 text-center text-fg-faint">No keys yet.</td></tr>
            )}
            {keys?.map((k) => (
              <tr key={k.id}>
                <td className="px-4 py-3 font-medium text-fg">{k.name}</td>
                <td className="px-4 py-3 font-mono text-xs text-fg-muted">gd_live_{k.prefix}…</td>
                <td className="px-4 py-3 text-fg-muted">{new Date(k.createdAt).toLocaleDateString()}</td>
                <td className="px-4 py-3 text-fg-muted">
                  {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleDateString() : '—'}
                </td>
                <td className="px-4 py-3 text-right">
                  <button
                    onClick={() => void revoke(k.id)}
                    className="text-xs text-danger hover:underline font-medium"
                  >
                    Revoke
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
