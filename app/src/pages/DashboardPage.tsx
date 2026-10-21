import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAuth, formatCents } from '../lib/auth';
import { apiFetch, ApiError } from '../lib/api';
import ResendEmailButton from '../components/ResendEmailButton';
import { Icon } from '../components/Brand';

export default function DashboardPage() {
  const { user } = useAuth();
  if (!user) return null; // ProtectedRoute already guarantees this; type-narrow

  const lowBalance = user.creditBalanceCents < 100; // < $1

  return (
    <div className="mx-auto max-w-5xl px-6 py-12">
      <h1 className="text-3xl font-bold tracking-tight text-fg">
        Hi, {user.fullName.split(' ')[0]}.
      </h1>

      {user.hasEmail
        ? !user.emailVerified && <VerifyEmailBanner email={user.pendingEmail ?? user.email} />
        : <AddEmailBanner pendingEmail={user.pendingEmail} />}

      {/* Balance card */}
      <div className="mt-8 card flex items-start justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
            Available credit
          </p>
          <p className="mt-2 text-4xl font-bold tracking-tight tabular-nums text-fg">
            {formatCents(user.creditBalanceCents)}
          </p>
          {lowBalance && (
            <p className="mt-2 text-sm text-warning">
              Low balance. Top up to keep requests flowing.
            </p>
          )}
        </div>
        <Link to="/billing" className="btn btn-primary">
          Top up
        </Link>
      </div>

      {/* Quick links */}
      <h2 className="mt-12 text-lg font-semibold text-fg">Get going</h2>
      <div className="mt-4 grid sm:grid-cols-2 gap-4">
        <DashboardCard
          to="/chat"
          title="Start a chat"
          body="Try any of 7 text models — GPT, Claude, Gemini — with one prompt box."
        />
        <DashboardCard
          to="/keys"
          title="Manage API keys"
          body="Create, list, and revoke keys for your apps. Plaintext returned once at creation."
        />
        <DashboardCard
          to="/billing"
          title="Top up balance"
          body="Card, ACH, or SEPA. Pass-through provider pricing plus a platform fee from 5%."
        />
        <DashboardCard
          to="/settings"
          title="Webhook secret"
          body="Fetch or rotate your signing secret for verifying async-job callbacks."
        />
      </div>

      <p className="mt-12 text-xs text-fg-faint">
        Usage history and per-model spend lands with the observability work in the next release.
      </p>
    </div>
  );
}

function DashboardCard({ to, title, body }: { to: string; title: string; body: string }) {
  return (
    <Link to={to} className="card card-hover block">
      <h3 className="font-semibold text-fg flex items-center justify-between">
        {title}
        <Icon name="chevronRight" size={16} />
      </h3>
      <p className="mt-1 text-sm text-fg-muted">{body}</p>
    </Link>
  );
}

const BANNER_CLASS = 'mt-6 rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900';

function VerifyEmailBanner({ email }: { email: string }) {
  return (
    <div className={BANNER_CLASS}>
      <p>
        We sent a verification link to <strong>{email}</strong>. Confirm it whenever you
        like — for now it's a heads-up; billing features will require it later.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <ResendEmailButton />
      </div>
    </div>
  );
}

/**
 * For an X sign-in, which shares no email. The address entered here only
 * becomes the account's email once its link is opened while signed in.
 */
function AddEmailBanner({ pendingEmail }: { pendingEmail: string | null }) {
  const { refresh } = useAuth();
  const [editing, setEditing] = useState(!pendingEmail);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiFetch('/v1/auth/email', { method: 'POST', json: { email } });
      await refresh();   // picks up pendingEmail
      setEditing(false);
      setEmail('');
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not add the address');
    } finally {
      setBusy(false);
    }
  };

  if (pendingEmail && !editing) {
    return (
      <div className={BANNER_CLASS}>
        <p>
          We sent a verification link to <strong>{pendingEmail}</strong>. Open it while signed
          in to add the address to your account.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <ResendEmailButton />
          <button type="button" onClick={() => setEditing(true)} className="btn btn-sm btn-ghost">
            Use a different address
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={BANNER_CLASS}>
      <p>
        Your sign-in didn't share an email address with us. Add one so we can reach you —
        billing features will require a verified email later.
      </p>
      <form onSubmit={(e) => void submit(e)} className="mt-3 flex flex-wrap items-center gap-3">
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="you@example.com"
          aria-label="Email address"
          autoComplete="email"
          className="input max-w-xs"
        />
        <button type="submit" disabled={busy} className="btn btn-sm btn-primary">
          {busy ? 'Sending…' : 'Send verification link'}
        </button>
        {pendingEmail && (
          <button type="button" onClick={() => setEditing(false)} className="btn btn-sm btn-ghost">
            Cancel
          </button>
        )}
        {error && <span role="alert" className="text-danger">{error}</span>}
      </form>
    </div>
  );
}
