import { useEffect, useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { Icon } from '../components/Brand';

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
      <h1 className="text-2xl font-bold tracking-tight text-fg">Settings</h1>

      <section className="mt-8 card">
        <h2 className="font-semibold text-fg">Webhook signing secret</h2>
        <p className="mt-1 text-sm text-fg-muted">
          Outbound webhooks (async image/video job completions) are HMAC-signed with this
          secret in the <code className="code-chip">{header}</code> header,
          Stripe format: <code className="code-chip">t=&lt;unix&gt;,v1=&lt;hex&gt;</code>.
          After a rotation, both old and new signatures verify for 24 hours.
        </p>

        {error && <p className="mt-3 text-sm text-danger">{error}</p>}

        {justRotated && (
          <p className="mt-3 text-sm text-success">
            Rotated. The previous secret is still accepted for the next 24 hours.
          </p>
        )}

        <div className="mt-5 flex items-stretch gap-2">
          <code className="flex-1 break-all embedded-surface font-mono text-xs p-3">
            {secret === null
              ? 'Loading…'
              : revealed
                ? secret
                : 'whsec_••••••••••••••••••••••••••••••••••'}
          </code>
          {secret && (
            <button
              onClick={() => setRevealed((v) => !v)}
              className="btn btn-secondary btn-sm"
            >
              <Icon name={revealed ? 'eyeOff' : 'eye'} size={14} />
              {revealed ? 'Hide' : 'Reveal'}
            </button>
          )}
          {secret && revealed && (
            <button
              onClick={() => void copy()}
              className="btn btn-secondary btn-sm"
            >
              <Icon name="copy" size={14} />
              Copy
            </button>
          )}
        </div>

        <button
          onClick={() => void rotate()}
          disabled={busy}
          className="btn btn-danger btn-sm mt-5"
        >
          {busy ? 'Rotating…' : 'Rotate secret'}
        </button>
      </section>

      <section className="mt-8 card">
        <h2 className="font-semibold text-fg">Verification snippet</h2>
        <p className="mt-1 text-sm text-fg-muted">
          Drop into your webhook handler. Walks all <code className="code-chip">v1=</code>
          {' '}entries and accepts on any match — so rotations don't drop in-flight events.
        </p>
        <pre className="code-block mt-3">
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
