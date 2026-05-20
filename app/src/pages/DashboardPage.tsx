import { Link } from 'react-router-dom';
import { useAuth, formatCents } from '../lib/auth';
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

      {!user.emailVerified && (
        <div className="mt-6 rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900">
          We sent a verification link to <strong>{user.email}</strong>. Confirm it whenever you
          like — for now it's a heads-up; billing features will require it later.
        </div>
      )}

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
          body="Card, ACH, or SEPA. Pass-through provider pricing plus a flat 5% fee."
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
