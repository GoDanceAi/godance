import { useCallback, useEffect, useState } from 'react';
import {
  apiFetch,
  ApiError,
  type AdminAdjustResult,
  type AdminLedgerPage,
  type AdminUserSearchResponse,
  type AdminUserView,
  type UserRole,
} from '../../lib/api';
import { useAdmin } from '../../lib/admin';
import { formatCents, useAuth } from '../../lib/auth';

// Support console: find a user by email or UUID, inspect balance / caps /
// ledger, and, with the role for it, apply a manual credit adjustment or
// change the user's role (both audited server-side).

const ROLES: { role: UserRole; description: string }[] = [
  { role: 'USER', description: 'no console access' },
  { role: 'SUPPORT', description: 'reads the console' },
  { role: 'FINANCE', description: 'reads, adjusts credit' },
  { role: 'ADMIN', description: 'everything, including roles' },
];

export default function UsersPage() {
  const { role } = useAdmin();
  const [q, setQ] = useState('');
  const [results, setResults] = useState<AdminUserView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<AdminUserView | null>(null);

  const search = async () => {
    if (!q.trim()) return;
    setSearching(true);
    setError(null);
    try {
      const resp = await apiFetch<AdminUserSearchResponse>(
        `/v1/admin/users?q=${encodeURIComponent(q.trim())}`);
      setResults(resp.users);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Search failed');
    } finally {
      setSearching(false);
    }
  };

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Look up a user by email (substring) or UUID and inspect their ledger.
        {role !== 'SUPPORT' && ' Every credit adjustment is written to the audit log.'}
      </p>

      <form
        className="mt-4 flex gap-2 max-w-lg"
        onSubmit={(e) => { e.preventDefault(); void search(); }}
      >
        <input
          className="input"
          placeholder="email or user UUID…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={searching || !q.trim()}>
          {searching ? 'Searching…' : 'Search'}
        </button>
      </form>

      {error && <p className="mt-3 text-sm text-danger">{error}</p>}

      {results !== null && (
        <div className="mt-4 card p-0 overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-surface-alt border-b border-border">
              <tr className="text-left text-fg-subtle">
                <th className="px-4 py-2.5 font-semibold">Email</th>
                <th className="px-4 py-2.5 font-semibold">Role</th>
                <th className="px-4 py-2.5 font-semibold">Balance</th>
                <th className="px-4 py-2.5 font-semibold">Keys</th>
                <th className="px-4 py-2.5 font-semibold">Caps (d/m)</th>
                <th className="px-4 py-2.5 font-semibold">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {results.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-fg-faint">No users match.</td></tr>
              )}
              {results.map((u) => (
                <tr
                  key={u.id}
                  className="cursor-pointer hover:bg-bg-muted transition-colors"
                  onClick={() => setSelected(u)}
                >
                  <td className="px-4 py-3 font-medium text-fg">{u.email}</td>
                  <td className="px-4 py-3 text-fg-muted">{u.role}</td>
                  <td className="px-4 py-3 tabular-nums text-fg">{formatCents(u.creditBalanceCents)}</td>
                  <td className="px-4 py-3 tabular-nums text-fg-muted">{u.apiKeyCount}</td>
                  <td className="px-4 py-3 tabular-nums text-fg-muted text-xs">
                    {u.dailyCreditCapCents != null ? formatCents(u.dailyCreditCapCents) : '—'}
                    {' / '}
                    {u.monthlyCreditCapCents != null ? formatCents(u.monthlyCreditCapCents) : '—'}
                  </td>
                  <td className="px-4 py-3 text-fg-muted text-xs">
                    {new Date(u.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && (
        <UserDrawer
          user={selected}
          onClose={() => setSelected(null)}
          onUserUpdated={(u) => {
            setSelected(u);
            setResults((prev) => prev?.map((x) => (x.id === u.id ? u : x)) ?? null);
          }}
        />
      )}
    </div>
  );
}

function RoleCard({ user, onUserUpdated }: {
  user: AdminUserView;
  onUserUpdated: (u: AdminUserView) => void;
}) {
  const { user: me } = useAuth();
  const [draft, setDraft] = useState<UserRole>(user.role);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (me?.id === user.id) {
    return (
      <div className="mt-6 card">
        <p className="text-sm font-semibold text-fg">Role</p>
        <p className="mt-1 text-xs text-fg-subtle">
          {user.role}. You can't change your own role; ask another admin.
        </p>
      </div>
    );
  }

  const save = async () => {
    const ok = window.confirm(
      `Change ${user.email} from ${user.role} to ${draft}?\n` +
      'They are signed out everywhere and get the new role at their next login.');
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      onUserUpdated(await apiFetch<AdminUserView>(`/v1/admin/users/${user.id}/role`, {
        method: 'PUT',
        json: { role: draft },
      }));
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Role change failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-6 card">
      <p className="text-sm font-semibold text-fg">Role</p>
      <p className="mt-1 text-xs text-fg-subtle">
        Takes effect on their next request, signs them out, and lands in the audit log.
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <select
          aria-label="Role"
          className="input flex-1 min-w-[15rem]"
          value={draft}
          onChange={(e) => setDraft(e.target.value as UserRole)}
        >
          {ROLES.map((r) => (
            <option key={r.role} value={r.role}>{r.role} — {r.description}</option>
          ))}
        </select>
        <button
          className="btn btn-primary btn-sm"
          disabled={busy || draft === user.role}
          onClick={() => void save()}
        >
          {busy ? 'Saving…' : 'Save role'}
        </button>
      </div>
      {error && <p className="mt-2 text-xs text-danger">{error}</p>}
    </div>
  );
}

function UserDrawer({ user, onClose, onUserUpdated }: {
  user: AdminUserView;
  onClose: () => void;
  onUserUpdated: (u: AdminUserView) => void;
}) {
  const { role } = useAdmin();
  const [ledger, setLedger] = useState<AdminLedgerPage | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // Adjust form
  const [amount, setAmount] = useState('');       // dollars, signed
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const loadLedger = useCallback(async (p: number) => {
    try {
      const resp = await apiFetch<AdminLedgerPage>(`/v1/admin/users/${user.id}/ledger?page=${p}`);
      setLedger(resp);
      setPage(p);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load ledger');
    }
  }, [user.id]);

  useEffect(() => { void loadLedger(0); }, [loadLedger]);

  const amountCents = Math.round(Number(amount) * 100);
  const validAdjust = amount !== '' && !Number.isNaN(amountCents) && amountCents !== 0 && note.trim() !== '';

  const adjust = async () => {
    if (!validAdjust) return;
    const resulting = user.creditBalanceCents + amountCents;
    const ok = window.confirm(
      `Adjust ${user.email} by ${formatCents(amountCents)}?\n` +
      `Resulting balance: ${formatCents(resulting)}\nNote: ${note.trim()}`);
    if (!ok) return;
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const resp = await apiFetch<AdminAdjustResult>(`/v1/admin/users/${user.id}/credit-adjust`, {
        method: 'POST',
        json: {
          amountCents,
          note: note.trim(),
          idempotencyKey: crypto.randomUUID(),
        },
      });
      onUserUpdated(resp.user);
      setDone(`Applied ${formatCents(resp.entry.deltaCents)} — new balance ${formatCents(resp.user.creditBalanceCents)}`);
      setAmount('');
      setNote('');
      await loadLedger(0);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Adjustment failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 flex justify-end" role="dialog" aria-modal="true">
      {/* overlay */}
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <aside className="relative z-50 h-full w-full max-w-md overflow-y-auto bg-surface border-l border-border p-6 shadow-xl">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-lg font-bold text-fg break-all">{user.email}</h2>
            <p className="mt-0.5 text-xs text-fg-faint font-mono">{user.id}</p>
          </div>
          <button className="btn btn-sm btn-ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div className="embedded-surface p-3">
            <dt className="text-xs text-fg-subtle">Balance</dt>
            <dd className="mt-1 font-bold tabular-nums text-fg">{formatCents(user.creditBalanceCents)}</dd>
          </div>
          <div className="embedded-surface p-3">
            <dt className="text-xs text-fg-subtle">API keys</dt>
            <dd className="mt-1 font-bold tabular-nums text-fg">{user.apiKeyCount}</dd>
          </div>
          <div className="embedded-surface p-3">
            <dt className="text-xs text-fg-subtle">Daily cap</dt>
            <dd className="mt-1 tabular-nums text-fg">
              {user.dailyCreditCapCents != null ? formatCents(user.dailyCreditCapCents) : 'none'}
            </dd>
          </div>
          <div className="embedded-surface p-3">
            <dt className="text-xs text-fg-subtle">Monthly cap</dt>
            <dd className="mt-1 tabular-nums text-fg">
              {user.monthlyCreditCapCents != null ? formatCents(user.monthlyCreditCapCents) : 'none'}
            </dd>
          </div>
        </dl>

        {role === 'ADMIN' && <RoleCard user={user} onUserUpdated={onUserUpdated} />}

        {/* Credit adjust */}
        {role !== 'SUPPORT' && (
          <div className="mt-6 card">
            <p className="text-sm font-semibold text-fg">Adjust credit</p>
            <p className="mt-1 text-xs text-fg-subtle">
              Positive credits, negative debits (may push the balance below zero). Note is required
              and lands in the audit log.
            </p>
            <div className="mt-3 flex gap-2">
              <div className="relative">
                <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-fg-subtle">$</span>
                <input
                  type="number"
                  step="0.01"
                  className="input w-28 pl-7 tabular-nums"
                  placeholder="±0.00"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                />
              </div>
              <input
                className="input flex-1"
                placeholder="reason (required)"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </div>
            {validAdjust && (
              <p className="mt-2 text-xs text-fg-muted tabular-nums">
                Resulting balance: <strong className="text-fg">{formatCents(user.creditBalanceCents + amountCents)}</strong>
              </p>
            )}
            <button
              className="btn btn-primary btn-sm mt-3"
              disabled={!validAdjust || busy}
              onClick={() => void adjust()}
            >
              {busy ? 'Applying…' : 'Apply adjustment'}
            </button>
            {done && <p className="mt-2 text-xs" style={{ color: 'var(--color-success-fg)' }}>{done}</p>}
          </div>
        )}
        {error && <p className="mt-2 text-xs text-danger">{error}</p>}

        {/* Ledger */}
        <div className="mt-6">
          <p className="text-sm font-semibold text-fg">Ledger</p>
          <div className="mt-2 card p-0 overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="bg-surface-alt border-b border-border">
                <tr className="text-left text-fg-subtle">
                  <th className="px-3 py-2 font-semibold">When</th>
                  <th className="px-3 py-2 font-semibold">Δ</th>
                  <th className="px-3 py-2 font-semibold">Source</th>
                  <th className="px-3 py-2 font-semibold">Note</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {ledger === null && (
                  <tr><td colSpan={4} className="px-3 py-4 text-center text-fg-faint">Loading…</td></tr>
                )}
                {ledger?.entries.length === 0 && (
                  <tr><td colSpan={4} className="px-3 py-4 text-center text-fg-faint">No ledger entries.</td></tr>
                )}
                {ledger?.entries.map((e) => (
                  <tr key={e.id}>
                    <td className="px-3 py-2 text-fg-muted whitespace-nowrap">
                      {new Date(e.occurredAt).toLocaleString()}
                    </td>
                    <td className={`px-3 py-2 tabular-nums font-medium ${e.deltaCents < 0 ? 'text-danger' : 'text-fg'}`}>
                      {formatCents(e.deltaCents)}
                    </td>
                    <td className="px-3 py-2 text-fg-muted">{e.source}</td>
                    <td className="px-3 py-2 text-fg-muted max-w-[10rem] truncate" title={e.note ?? ''}>
                      {e.note ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {ledger && ledger.totalPages > 1 && (
            <div className="mt-2 flex items-center justify-between text-xs text-fg-muted">
              <button
                className="btn btn-sm btn-secondary"
                disabled={page === 0}
                onClick={() => void loadLedger(page - 1)}
              >
                ← Newer
              </button>
              <span className="tabular-nums">page {page + 1} / {ledger.totalPages}</span>
              <button
                className="btn btn-sm btn-secondary"
                disabled={page >= ledger.totalPages - 1}
                onClick={() => void loadLedger(page + 1)}
              >
                Older →
              </button>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
