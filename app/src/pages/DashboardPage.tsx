import { Link } from 'react-router-dom';
import { useAuth, formatCents } from '../lib/auth';

export default function DashboardPage() {
  const { user } = useAuth();
  if (!user) return null; // ProtectedRoute already guarantees this; type-narrow

  const lowBalance = user.creditBalanceCents < 100; // < $1

  return (
    <div className="mx-auto max-w-5xl px-6 py-12">
      <h1 className="text-3xl font-bold tracking-tight">
        Hi, {user.fullName.split(' ')[0]}.
      </h1>

      {!user.emailVerified && (
        <div className="mt-6 rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900">
          We sent a verification link to <strong>{user.email}</strong>. Confirm it whenever you
          like — for now it's a heads-up; billing features will require it later.
        </div>
      )}

      {/* Balance card */}
      <div className="mt-8 rounded-lg border border-gray-200 bg-white p-6 flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-gray-500 uppercase tracking-wider">
            Available credit
          </p>
          <p className="mt-2 text-4xl font-bold tracking-tight">
            {formatCents(user.creditBalanceCents)}
          </p>
          {lowBalance && (
            <p className="mt-2 text-sm text-amber-700">
              Low balance. Top up to keep requests flowing.
            </p>
          )}
        </div>
        <Link
          to="/billing"
          className="bg-brand-600 hover:bg-brand-700 text-white font-medium px-4 py-2 rounded-md text-sm"
        >
          Top up
        </Link>
      </div>

      {/* Quick links */}
      <h2 className="mt-12 text-lg font-semibold">Get going</h2>
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
          body="Card or USDC. Pass-through provider pricing plus a flat 5% fee."
        />
        <DashboardCard
          to="/settings"
          title="Webhook secret"
          body="Fetch or rotate your signing secret for verifying async-job callbacks."
        />
      </div>

      <p className="mt-12 text-xs text-gray-400">
        Usage history and per-model spend lands with the observability work in the next release.
      </p>
    </div>
  );
}

function DashboardCard({ to, title, body }: { to: string; title: string; body: string }) {
  return (
    <Link
      to={to}
      className="block rounded-lg border border-gray-200 bg-white p-5 hover:border-brand-600 hover:shadow-sm transition"
    >
      <h3 className="font-semibold">{title}</h3>
      <p className="mt-1 text-sm text-gray-600">{body}</p>
    </Link>
  );
}
