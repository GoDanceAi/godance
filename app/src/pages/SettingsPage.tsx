import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Icon } from '../components/Brand';
import ResendEmailButton from '../components/ResendEmailButton';
import TwoFactorSection from '../components/TwoFactorSection';

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

      <EmailSection />

      <TwoFactorSection />

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

/**
 * Change the account's email, or add one for an X sign-in. The new address
 * replaces the current one only once its link is opened while signed in.
 */
function EmailSection() {
  const { user, refresh } = useAuth();
  const [editing, setEditing] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!user) return null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiFetch('/v1/auth/email', {
        method: 'POST',
        json: user.hasPassword ? { email, currentPassword: password } : { email },
      });
      await refresh();   // picks up pendingEmail
      setEditing(false);
      setEmail('');
      setPassword('');
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not change your email');
    } finally {
      setBusy(false);
    }
  };

  const cancel = () => {
    setEditing(false);
    setError(null);
    setPassword('');
  };

  return (
    <section className="mt-8 card">
      <h2 className="font-semibold text-fg">Email address</h2>
      <p className="mt-1 text-sm text-fg-muted">
        {user.hasEmail ? (
          <>
            <span className="font-medium text-fg">{user.email}</span>
            {user.emailVerified ? ' · verified' : ' · not verified yet'}
          </>
        ) : (
          "Your sign-in didn't share an email address with us."
        )}
      </p>

      {user.pendingEmail && !editing && (
        <div className="mt-4 text-sm text-fg-muted">
          <p>
            We sent a verification link to{' '}
            <strong className="text-fg">{user.pendingEmail}</strong>. Open it while signed in
            to {user.hasEmail ? 'switch to that address' : 'add it to your account'}.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <ResendEmailButton />
            <button type="button" onClick={() => setEditing(true)} className="btn btn-sm btn-ghost">
              Use a different address
            </button>
          </div>
        </div>
      )}

      {editing ? (
        <form onSubmit={(e) => void submit(e)} className="mt-4 max-w-sm space-y-3">
          <label className="block">
            <span className="text-sm font-medium text-fg">New email address</span>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              className="input mt-1.5"
            />
          </label>
          {user.hasPassword ? (
            <label className="block">
              <span className="text-sm font-medium text-fg">Current password</span>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="input mt-1.5"
              />
            </label>
          ) : (
            <p className="text-xs text-fg-subtle">
              For your security, this works within 10 minutes of signing in.
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" disabled={busy} className="btn btn-sm btn-primary">
              {busy ? 'Sending…' : 'Send verification link'}
            </button>
            <button type="button" onClick={cancel} className="btn btn-sm btn-ghost">
              Cancel
            </button>
          </div>
          {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        </form>
      ) : (
        !user.pendingEmail && (
          <button type="button" onClick={() => setEditing(true)} className="mt-4 btn btn-sm btn-secondary">
            {user.hasEmail ? 'Change email' : 'Add email'}
          </button>
        )
      )}
    </section>
  );
}
