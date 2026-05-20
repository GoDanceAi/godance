import { useEffect, useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';

interface SecretResponse {
  secret: string;
  header: string;
}

export default function SettingsPage() {
  const [secret, setSecret] = useState<string | null>(null);
  const [header, setHeader] = useState<string>('X-Godance-Signature');
  const [revealed, setRevealed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [justRotated, setJustRotated] = useState(false);

  const load = async () => {
    try {
      const data = await apiFetch<SecretResponse>('/v1/webhooks/secret');
      setSecret(data.secret);
      setHeader(data.header);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load secret');
    }
  };

  useEffect(() => { void load(); }, []);

  const rotate = async () => {
    if (!confirm('Rotate the signing secret? The old secret stays valid for 24h so deploys don\'t drop webhooks.')) return;
    setBusy(true);
    setError(null);
    try {
      const data = await apiFetch<SecretResponse>('/v1/webhooks/secret/rotate', { method: 'POST' });
      setSecret(data.secret);
      setRevealed(true);
      setJustRotated(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Rotation failed');
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    if (!secret) return;
    await navigator.clipboard.writeText(secret);
  };

  return (
    <div className="mx-auto max-w-3xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight">Settings</h1>

      <section className="mt-8 rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="font-semibold">Webhook signing secret</h2>
        <p className="mt-1 text-sm text-gray-600">
          Outbound webhooks (async image/video job completions) are HMAC-signed with this
          secret in the <code className="bg-gray-100 px-1 rounded">{header}</code> header,
          Stripe format: <code className="bg-gray-100 px-1 rounded">t=&lt;unix&gt;,v1=&lt;hex&gt;</code>.
          After a rotation, both old and new signatures verify for 24 hours.
        </p>

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        {justRotated && (
          <p className="mt-3 text-sm text-emerald-700">
            ✓ Rotated. The previous secret is still accepted for the next 24 hours.
          </p>
        )}

        <div className="mt-5 flex items-stretch gap-2">
          <code className="flex-1 break-all font-mono text-xs bg-gray-50 border border-gray-200 rounded p-3">
            {secret === null
              ? 'Loading…'
              : revealed
                ? secret
                : 'whsec_••••••••••••••••••••••••••••••••••'}
          </code>
          {secret && (
            <button
              onClick={() => setRevealed((v) => !v)}
              className="px-3 text-sm font-medium border border-gray-300 rounded-md hover:bg-gray-50"
            >
              {revealed ? 'Hide' : 'Reveal'}
            </button>
          )}
          {secret && revealed && (
            <button
              onClick={() => void copy()}
              className="px-3 text-sm font-medium border border-gray-300 rounded-md hover:bg-gray-50"
            >
              Copy
            </button>
          )}
        </div>

        <button
          onClick={() => void rotate()}
          disabled={busy}
          className="mt-5 px-4 py-2 text-sm font-medium border border-red-300 text-red-700 rounded-md hover:bg-red-50 disabled:opacity-50"
        >
          {busy ? 'Rotating…' : 'Rotate secret'}
        </button>
      </section>

      <section className="mt-8 rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="font-semibold">Verification snippet</h2>
        <p className="mt-1 text-sm text-gray-600">
          Drop into your webhook handler. Walks all <code className="bg-gray-100 px-1 rounded">v1=</code>
          {' '}entries and accepts on any match — so rotations don't drop in-flight events.
        </p>
        <pre className="mt-3 bg-gray-900 text-gray-100 rounded-md p-4 text-xs overflow-x-auto">
{`import hmac, hashlib

def verify_godance(payload: str, header: str, secret: str) -> bool:
    t = int(header.split(',')[0][2:])
    sigs = [p[3:] for p in header.split(',') if p.startswith('v1=')]
    expected = hmac.new(
        secret.encode(), f"{t}.{payload}".encode(),
        hashlib.sha256
    ).hexdigest()
    return any(hmac.compare_digest(expected, s) for s in sigs)`}
        </pre>
      </section>
    </div>
  );
}
