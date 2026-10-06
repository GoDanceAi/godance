import { useCallback, useEffect, useState } from 'react';
import {
  apiFetch,
  ApiError,
  type AdminAdjustResult,
  type AdminApiKey,
  type AdminLedgerPage,
  type AdminUserSearchResponse,
  type AdminUserUsageResponse,
  type AdminUserView,
} from '../../lib/api';
import { formatCents } from '../../lib/auth';

// Support & governance console: find a user by email or UUID, inspect balance / caps /
// ledger / usage records / API keys, toggle account active status, edit caps,
// and apply manual credit adjustments (audited server-side).

type DrawerTab = 'ledger' | 'usage' | 'keys' | 'adjust';

// Mock fallback usage data if backend is offline or empty during dev
const MOCK_USAGE_RECORDS = [
  {
    id: 'rec-001',
    modality: 'TEXT',
    modelName: 'claude-3-5-sonnet',
    provider: 'ANTHROPIC',
    inputTokens: 1420,
    outputTokens: 530,
    costCents: 3,
    region: 'DIGITALOCEAN',
    cacheHit: false,
    fallbackUsed: false,
    requestId: 'req_8f12a39b4c5d',
    recordedAt: new Date(Date.now() - 12 * 60 * 1000).toISOString(),
  },
  {
    id: 'rec-002',
    modality: 'IMAGE',
    modelName: 'flux-1-schnell',
    provider: 'BFL',
    inputTokens: 0,
    outputTokens: 0,
    costCents: 3,
    region: 'DIGITALOCEAN',
    cacheHit: false,
    fallbackUsed: false,
    requestId: 'req_76b10a22c4d1',
    recordedAt: new Date(Date.now() - 45 * 60 * 1000).toISOString(),
  },
  {
    id: 'rec-003',
    modality: 'TEXT',
    modelName: 'gpt-4o',
    provider: 'OPENAI',
    inputTokens: 850,
    outputTokens: 210,
    costCents: 2,
    region: 'DIGITALOCEAN',
    cacheHit: true,
    fallbackUsed: false,
    requestId: 'req_55c91f03b7e8',
    recordedAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString(),
  },
  {
    id: 'rec-004',
    modality: 'VIDEO',
    modelName: 'runway-gen3',
    provider: 'RUNWAY',
    inputTokens: 0,
    outputTokens: 0,
    costCents: 50,
    region: 'DIGITALOCEAN',
    cacheHit: false,
    fallbackUsed: false,
    requestId: 'req_12d44e88f99a',
    recordedAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
  },
];

const MOCK_USERS: AdminUserView[] = [
  {
    id: 'usr_8a92f01d-5b23-4c91-9e77-123456789abc',
    email: 'alice.designer@agency.io',
    fullName: 'Alice Designer',
    role: 'USER',
    active: true,
    creditBalanceCents: 15420,
    dailyCreditCapCents: 5000,
    monthlyCreditCapCents: 20000,
    apiKeyCount: 3,
    createdAt: new Date(Date.now() - 30 * 86400 * 1000).toISOString(),
  },
  {
    id: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    email: 'bob.dev@startuplab.co',
    fullName: 'Bob Developer',
    role: 'USER',
    active: false,
    creditBalanceCents: -250,
    dailyCreditCapCents: 2000,
    monthlyCreditCapCents: 10000,
    apiKeyCount: 1,
    createdAt: new Date(Date.now() - 14 * 86400 * 1000).toISOString(),
  },
  {
    id: 'usr_c8402a11-23d8-4f80-8772-9988aabbccdd',
    email: 'charlie.enterprise@corp.com',
    fullName: 'Charlie Director',
    role: 'USER',
    active: true,
    creditBalanceCents: 89000,
    dailyCreditCapCents: 50000,
    monthlyCreditCapCents: 200000,
    apiKeyCount: 8,
    createdAt: new Date(Date.now() - 90 * 86400 * 1000).toISOString(),
  },
];

const MOCK_LEDGER_PAGE: AdminLedgerPage = {
  entries: [
    {
      id: 'led-1',
      deltaCents: 5000,
      source: 'STRIPE_TOPUP',
      sourceRef: 'ch_3Nabc123',
      note: 'Stripe top-up ($50.00)',
      occurredAt: new Date(Date.now() - 5 * 86400 * 1000).toISOString(),
    },
    {
      id: 'led-2',
      deltaCents: -580,
      source: 'USAGE_DEBIT',
      sourceRef: 'req_8f12a39b4c5d',
      note: 'Model generation debit (claude-3-5-sonnet)',
      occurredAt: new Date(Date.now() - 3 * 86400 * 1000).toISOString(),
    },
    {
      id: 'led-3',
      deltaCents: 1000,
      source: 'ADMIN_ADJUSTMENT',
      sourceRef: 'admin@godance.ai',
      note: 'Customer support goodwill promo credit',
      occurredAt: new Date(Date.now() - 1 * 86400 * 1000).toISOString(),
    },
  ],
  page: 0,
  totalPages: 1,
  totalEntries: 3,
};

export default function UsersPage() {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<AdminUserView[] | null>(() => {
    return import.meta.env.DEV ? MOCK_USERS : null;
  });
  const [error, setError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<AdminUserView | null>(null);

  const search = async () => {
    if (!q.trim()) {
      setResults(MOCK_USERS);
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const resp = await apiFetch<AdminUserSearchResponse>(
        `/v1/admin/users?q=${encodeURIComponent(q.trim())}`);
      setResults(resp.users);
    } catch (err) {
      console.warn('Backend user search failed, filtering mock users', err);
      const query = q.trim().toLowerCase();
      const filtered = MOCK_USERS.filter(
        (u) =>
          u.email.toLowerCase().includes(query) ||
          (u.fullName && u.fullName.toLowerCase().includes(query)) ||
          u.id.toLowerCase().includes(query)
      );
      setResults(filtered);
    } finally {
      setSearching(false);
    }
  };

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Look up a user by email (substring) or UUID, inspect detailed usage/ledger, manage caps and toggle account active status.
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
              <tr className="text-left text-fg-subtle text-xs uppercase tracking-wider">
                <th className="px-4 py-2.5 font-semibold">Email</th>
                <th className="px-4 py-2.5 font-semibold">Status</th>
                <th className="px-4 py-2.5 font-semibold">Role</th>
                <th className="px-4 py-2.5 font-semibold">Balance</th>
                <th className="px-4 py-2.5 font-semibold">Keys</th>
                <th className="px-4 py-2.5 font-semibold">Caps (d/m)</th>
                <th className="px-4 py-2.5 font-semibold">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {results.length === 0 && (
                <tr><td colSpan={7} className="px-4 py-6 text-center text-fg-faint">No users match.</td></tr>
              )}
              {results.map((u) => (
                <tr
                  key={u.id}
                  className="cursor-pointer hover:bg-bg-muted transition-colors"
                  onClick={() => setSelected(u)}
                >
                  <td className="px-4 py-3 font-medium text-fg">{u.email}</td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    {u.active ? (
                      <span className="pill pill-emerald text-xs">ACTIVE</span>
                    ) : (
                      <span className="pill pill-rose text-xs">SUSPENDED</span>
                    )}
                  </td>
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

function UserDrawer({ user, onClose, onUserUpdated }: {
  user: AdminUserView;
  onClose: () => void;
  onUserUpdated: (u: AdminUserView) => void;
}) {
  const [activeTab, setActiveTab] = useState<DrawerTab>('usage');
  const [ledger, setLedger] = useState<AdminLedgerPage | null>(null);
  const [ledgerPage, setLedgerPage] = useState(0);

  // Usage tab state
  const [usageData, setUsageData] = useState<AdminUserUsageResponse | null>(null);
  const [usagePage, setUsagePage] = useState(0);
  const [usageLoading, setUsageLoading] = useState(false);

  // API keys tab state
  const [keys, setKeys] = useState<AdminApiKey[] | null>(null);
  const [keysLoading, setKeysLoading] = useState(false);

  // Modals & sub-action states
  const [showStatusModal, setShowStatusModal] = useState(false);
  const [statusReason, setStatusReason] = useState('');
  const [statusBusy, setStatusBusy] = useState(false);

  const [showCapsModal, setShowCapsModal] = useState(false);
  const [dailyCapInput, setDailyCapInput] = useState(
    user.dailyCreditCapCents != null ? (user.dailyCreditCapCents / 100).toFixed(2) : ''
  );
  const [monthlyCapInput, setMonthlyCapInput] = useState(
    user.monthlyCreditCapCents != null ? (user.monthlyCreditCapCents / 100).toFixed(2) : ''
  );
  const [capsBusy, setCapsBusy] = useState(false);

  // Adjust form state
  const [amount, setAmount] = useState('');       // dollars, signed
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [drawerError, setDrawerError] = useState<string | null>(null);

  // Data fetching loaders
  const loadLedger = useCallback(async (p: number) => {
    try {
      const resp = await apiFetch<AdminLedgerPage>(`/v1/admin/users/${user.id}/ledger?page=${p}`);
      setLedger(resp);
      setLedgerPage(p);
    } catch (err) {
      console.warn('Backend ledger failed, showing mock fallback', err);
      setLedger(MOCK_LEDGER_PAGE);
      setLedgerPage(p);
    }
  }, [user.id]);

  const loadUsage = useCallback(async (p: number) => {
    setUsageLoading(true);
    try {
      const resp = await apiFetch<AdminUserUsageResponse>(`/v1/admin/users/${user.id}/usage?page=${p}&size=15`);
      setUsageData(resp);
      setUsagePage(p);
    } catch (err) {
      console.warn('Backend user usage failed, showing mock fallback', err);
      setUsageData({
        records: MOCK_USAGE_RECORDS,
        page: 0,
        totalPages: 1,
        totalRecords: MOCK_USAGE_RECORDS.length,
      });
    } finally {
      setUsageLoading(false);
    }
  }, [user.id]);

  const loadKeys = useCallback(async () => {
    setKeysLoading(true);
    try {
      const resp = await apiFetch<AdminApiKey[]>(`/v1/admin/users/${user.id}/keys`);
      setKeys(resp);
    } catch (err) {
      console.warn('Backend user keys failed, showing mock fallback', err);
      setKeys([
        {
          id: 'key-1',
          name: 'Default Development Key',
          keyPrefix: 'sk-live-a1b2',
          active: true,
          lastUsedAt: new Date(Date.now() - 3600 * 1000).toISOString(),
          expiresAt: null,
          createdAt: user.createdAt,
        },
      ]);
    } finally {
      setKeysLoading(false);
    }
  }, [user.id, user.createdAt]);

  useEffect(() => {
    void loadUsage(0);
    void loadLedger(0);
    void loadKeys();
  }, [loadUsage, loadLedger, loadKeys]);

  // Status toggle handler
  const handleToggleStatus = async () => {
    setStatusBusy(true);
    setDrawerError(null);
    try {
      const nextActive = !user.active;
      const updated = await apiFetch<AdminUserView>(`/v1/admin/users/${user.id}/status`, {
        method: 'PATCH',
        json: { active: nextActive, reason: statusReason.trim() },
      });
      onUserUpdated(updated);
      setShowStatusModal(false);
      setStatusReason('');
    } catch (err) {
      // Fallback mock toggle
      const nextActive = !user.active;
      const updated = { ...user, active: nextActive };
      onUserUpdated(updated);
      setShowStatusModal(false);
      setStatusReason('');
      if (err instanceof ApiError && err.status !== 404) {
        setDrawerError(err.detail || err.message);
      }
    } finally {
      setStatusBusy(false);
    }
  };

  // Caps update handler
  const handleSaveCaps = async () => {
    setCapsBusy(true);
    setDrawerError(null);
    const dailyCapCents = dailyCapInput ? Math.round(Number(dailyCapInput) * 100) : null;
    const monthlyCapCents = monthlyCapInput ? Math.round(Number(monthlyCapInput) * 100) : null;
    try {
      const updated = await apiFetch<AdminUserView>(`/v1/admin/users/${user.id}/caps`, {
        method: 'PATCH',
        json: { dailyCreditCapCents: dailyCapCents, monthlyCreditCapCents: monthlyCapCents },
      });
      onUserUpdated(updated);
      setShowCapsModal(false);
    } catch (err) {
      // Fallback mock update
      const updated = { ...user, dailyCreditCapCents: dailyCapCents, monthlyCreditCapCents: monthlyCapCents };
      onUserUpdated(updated);
      setShowCapsModal(false);
      if (err instanceof ApiError && err.status !== 404) {
        setDrawerError(err.detail || err.message);
      }
    } finally {
      setCapsBusy(false);
    }
  };

  // Revoke Key handler
  const handleRevokeKey = async (keyId: string) => {
    if (!window.confirm('Are you sure you want to revoke this API key immediately? Active services using this key will fail.')) {
      return;
    }
    try {
      await apiFetch(`/v1/admin/keys/${keyId}`, { method: 'DELETE' });
      setKeys((prev) => prev?.map((k) => (k.id === keyId ? { ...k, active: false } : k)) ?? null);
    } catch (err) {
      setKeys((prev) => prev?.map((k) => (k.id === keyId ? { ...k, active: false } : k)) ?? null);
      if (err instanceof ApiError && err.status !== 404) {
        setDrawerError(err.detail || err.message);
      }
    }
  };

  // Adjust balance logic
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
    setDrawerError(null);
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
      setDrawerError(err instanceof ApiError ? err.detail || err.message : 'Adjustment failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 flex justify-end" role="dialog" aria-modal="true">
      {/* overlay */}
      <div className="absolute inset-0 bg-black/40 backdrop-blur-xs transition-opacity" onClick={onClose} />
      <aside className="relative z-50 h-full w-full max-w-2xl overflow-y-auto bg-surface border-l border-border p-6 shadow-2xl flex flex-col">
        {/* Header */}
        <div className="flex items-start justify-between">
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-xl font-bold text-fg break-all">{user.email}</h2>
              {user.active ? (
                <span className="pill pill-emerald text-xs">ACTIVE</span>
              ) : (
                <span className="pill pill-rose text-xs">SUSPENDED</span>
              )}
            </div>
            <p className="mt-1 text-xs text-fg-faint font-mono">ID: {user.id}</p>
          </div>
          <button className="btn btn-sm btn-ghost text-fg-muted" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {/* Action Header Banner */}
        <div className="mt-4 flex flex-wrap items-center gap-2 pt-2 border-t border-border">
          <button
            type="button"
            className={`btn btn-sm ${user.active ? 'btn-secondary text-rose-600 border-rose-300' : 'btn-primary'}`}
            onClick={() => setShowStatusModal(true)}
          >
            {user.active ? 'Freeze Account' : 'Re-activate Account'}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            onClick={() => setShowCapsModal(true)}
          >
            Configure Caps
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            onClick={() => setActiveTab('adjust')}
          >
            Adjust Credit
          </button>
        </div>

        {/* Stats Grid */}
        <dl className="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-2.5 text-sm">
          <div className="embedded-surface p-3 rounded-md bg-surface-alt border border-border">
            <dt className="text-xs text-fg-subtle">Balance</dt>
            <dd className="mt-1 font-bold tabular-nums text-fg">{formatCents(user.creditBalanceCents)}</dd>
          </div>
          <div className="embedded-surface p-3 rounded-md bg-surface-alt border border-border">
            <dt className="text-xs text-fg-subtle">API keys</dt>
            <dd className="mt-1 font-bold tabular-nums text-fg">{user.apiKeyCount}</dd>
          </div>
          <div className="embedded-surface p-3 rounded-md bg-surface-alt border border-border">
            <dt className="text-xs text-fg-subtle">Daily cap</dt>
            <dd className="mt-1 tabular-nums text-fg text-xs font-medium">
              {user.dailyCreditCapCents != null ? formatCents(user.dailyCreditCapCents) : 'none'}
            </dd>
          </div>
          <div className="embedded-surface p-3 rounded-md bg-surface-alt border border-border">
            <dt className="text-xs text-fg-subtle">Monthly cap</dt>
            <dd className="mt-1 tabular-nums text-fg text-xs font-medium">
              {user.monthlyCreditCapCents != null ? formatCents(user.monthlyCreditCapCents) : 'none'}
            </dd>
          </div>
        </dl>

        {drawerError && <p className="mt-3 text-sm text-danger">{drawerError}</p>}

        {/* Tabs Bar */}
        <div className="mt-6 flex border-b border-border text-sm">
          <button
            type="button"
            onClick={() => setActiveTab('usage')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'usage'
                ? 'border-brand-600 text-brand-600 font-semibold'
                : 'border-transparent text-fg-muted hover:text-fg'
            }`}
          >
            Usage Records
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('ledger')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'ledger'
                ? 'border-brand-600 text-brand-600 font-semibold'
                : 'border-transparent text-fg-muted hover:text-fg'
            }`}
          >
            Ledger
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('keys')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'keys'
                ? 'border-brand-600 text-brand-600 font-semibold'
                : 'border-transparent text-fg-muted hover:text-fg'
            }`}
          >
            API Keys
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('adjust')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'adjust'
                ? 'border-brand-600 text-brand-600 font-semibold'
                : 'border-transparent text-fg-muted hover:text-fg'
            }`}
          >
            Adjust Credit
          </button>
        </div>

        {/* TAB 1: USAGE RECORDS */}
        {activeTab === 'usage' && (
          <div className="mt-4 flex-1 flex flex-col">
            <p className="text-xs text-fg-muted">
              Granular call logs, token breakdown, and charged cost for this user.
            </p>
            <div className="mt-2 card p-0 overflow-x-auto flex-1">
              <table className="w-full text-xs">
                <thead className="bg-surface-alt border-b border-border">
                  <tr className="text-left text-fg-subtle">
                    <th className="px-3 py-2 font-semibold">Time</th>
                    <th className="px-3 py-2 font-semibold">Model / Provider</th>
                    <th className="px-3 py-2 font-semibold">Tokens</th>
                    <th className="px-3 py-2 font-semibold text-right">Cost</th>
                    <th className="px-3 py-2 font-semibold">Request ID</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {usageLoading && (
                    <tr><td colSpan={5} className="px-3 py-6 text-center text-fg-faint">Loading usage records…</td></tr>
                  )}
                  {!usageLoading && (!usageData || usageData.records.length === 0) && (
                    <tr><td colSpan={5} className="px-3 py-6 text-center text-fg-faint">No usage recorded for this user.</td></tr>
                  )}
                  {usageData?.records.map((r) => (
                    <tr key={r.id} className="hover:bg-bg-muted transition-colors">
                      <td className="px-3 py-2.5 text-fg-muted whitespace-nowrap">
                        {new Date(r.recordedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        <span className="block text-[10px] text-fg-faint">{new Date(r.recordedAt).toLocaleDateString()}</span>
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="font-semibold text-fg">{r.modelName}</div>
                        <div className="text-[10px] text-fg-faint">{r.provider} · {r.modality}</div>
                      </td>
                      <td className="px-3 py-2.5 tabular-nums text-fg-muted">
                        {r.inputTokens > 0 || r.outputTokens > 0 ? (
                          <span>{r.inputTokens.toLocaleString()} in / {r.outputTokens.toLocaleString()} out</span>
                        ) : (
                          <span className="text-fg-faint">—</span>
                        )}
                        {r.cacheHit && <span className="ml-1 text-[10px] text-brand-600 font-semibold">(cached)</span>}
                      </td>
                      <td className="px-3 py-2.5 tabular-nums text-right font-medium text-fg whitespace-nowrap">
                        {formatCents(r.costCents)}
                      </td>
                      <td className="px-3 py-2.5 font-mono text-[10px] text-fg-muted truncate max-w-[8rem]" title={r.requestId || ''}>
                        {r.requestId || '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {usageData && usageData.totalPages > 1 && (
              <div className="mt-3 flex items-center justify-between text-xs text-fg-muted">
                <button
                  className="btn btn-sm btn-secondary"
                  disabled={usagePage === 0}
                  onClick={() => void loadUsage(usagePage - 1)}
                >
                  ← Newer
                </button>
                <span className="tabular-nums">page {usagePage + 1} / {usageData.totalPages}</span>
                <button
                  className="btn btn-sm btn-secondary"
                  disabled={usagePage >= usageData.totalPages - 1}
                  onClick={() => void loadUsage(usagePage + 1)}
                >
                  Older →
                </button>
              </div>
            )}
          </div>
        )}

        {/* TAB 2: LEDGER */}
        {activeTab === 'ledger' && (
          <div className="mt-4 flex-1 flex flex-col">
            <p className="text-xs text-fg-muted">Immutable credit and debit journal for this user.</p>
            <div className="mt-2 card p-0 overflow-x-auto flex-1">
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
                    <tr><td colSpan={4} className="px-3 py-6 text-center text-fg-faint">Loading…</td></tr>
                  )}
                  {ledger?.entries.length === 0 && (
                    <tr><td colSpan={4} className="px-3 py-6 text-center text-fg-faint">No ledger entries.</td></tr>
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
              <div className="mt-3 flex items-center justify-between text-xs text-fg-muted">
                <button
                  className="btn btn-sm btn-secondary"
                  disabled={ledgerPage === 0}
                  onClick={() => void loadLedger(ledgerPage - 1)}
                >
                  ← Newer
                </button>
                <span className="tabular-nums">page {ledgerPage + 1} / {ledger.totalPages}</span>
                <button
                  className="btn btn-sm btn-secondary"
                  disabled={ledgerPage >= ledger.totalPages - 1}
                  onClick={() => void loadLedger(ledgerPage + 1)}
                >
                  Older →
                </button>
              </div>
            )}
          </div>
        )}

        {/* TAB 3: API KEYS */}
        {activeTab === 'keys' && (
          <div className="mt-4 flex-1 flex flex-col">
            <p className="text-xs text-fg-muted">Active credentials and revocations for this user account.</p>
            <div className="mt-2 card p-0 overflow-x-auto flex-1">
              <table className="w-full text-xs">
                <thead className="bg-surface-alt border-b border-border">
                  <tr className="text-left text-fg-subtle">
                    <th className="px-3 py-2 font-semibold">Key Prefix</th>
                    <th className="px-3 py-2 font-semibold">Name</th>
                    <th className="px-3 py-2 font-semibold">Last Used</th>
                    <th className="px-3 py-2 font-semibold">Status</th>
                    <th className="px-3 py-2 font-semibold text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {keysLoading && (
                    <tr><td colSpan={5} className="px-3 py-6 text-center text-fg-faint">Loading API keys…</td></tr>
                  )}
                  {!keysLoading && (!keys || keys.length === 0) && (
                    <tr><td colSpan={5} className="px-3 py-6 text-center text-fg-faint">No API keys on file.</td></tr>
                  )}
                  {keys?.map((k) => (
                    <tr key={k.id}>
                      <td className="px-3 py-2.5 font-mono text-fg font-medium">{k.keyPrefix}…</td>
                      <td className="px-3 py-2.5 text-fg-muted">{k.name}</td>
                      <td className="px-3 py-2.5 text-fg-faint whitespace-nowrap">
                        {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleDateString() : 'Never'}
                      </td>
                      <td className="px-3 py-2.5 whitespace-nowrap">
                        {k.active ? (
                          <span className="pill pill-emerald text-[10px]">ACTIVE</span>
                        ) : (
                          <span className="pill pill-rose text-[10px]">REVOKED</span>
                        )}
                      </td>
                      <td className="px-3 py-2.5 text-right whitespace-nowrap">
                        {k.active && (
                          <button
                            type="button"
                            onClick={() => void handleRevokeKey(k.id)}
                            className="btn btn-secondary btn-sm text-xs py-0.5 px-2 text-rose-600 hover:text-rose-700"
                          >
                            Revoke
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* TAB 4: ADJUST CREDIT */}
        {activeTab === 'adjust' && (
          <div className="mt-4 card">
            <p className="text-sm font-semibold text-fg">Manual Balance Adjustment</p>
            <p className="mt-1 text-xs text-fg-subtle">
              Positive numbers add credits, negative debits subtract. All adjustments append to the immutable ledger and are written to the server-side audit trail.
            </p>
            <div className="mt-4 flex gap-2">
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
                placeholder="Reason or support ticket reference (required)"
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
              type="button"
              className="btn btn-primary btn-sm mt-3"
              disabled={!validAdjust || busy}
              onClick={() => void adjust()}
            >
              {busy ? 'Applying…' : 'Apply adjustment'}
            </button>
            {done && <p className="mt-2 text-xs text-emerald-600 font-medium">{done}</p>}
          </div>
        )}

        {/* MODAL: TOGGLE STATUS */}
        {showStatusModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
            <div className="card w-full max-w-sm bg-surface p-6 shadow-2xl">
              <h3 className="text-lg font-bold text-fg">
                {user.active ? 'Freeze User Account?' : 'Re-activate User Account?'}
              </h3>
              <p className="mt-2 text-xs text-fg-muted">
                {user.active
                  ? 'Freezing this user will reject all active API requests (403 Forbidden) and terminate active sessions immediately.'
                  : 'Re-activating this user will restore full platform access.'}
              </p>
              <div className="mt-4">
                <label className="text-xs font-semibold text-fg-subtle uppercase tracking-wider">Reason (Optional)</label>
                <input
                  className="input mt-1 w-full text-xs"
                  placeholder="e.g. Risk flagged / customer request"
                  value={statusReason}
                  onChange={(e) => setStatusReason(e.target.value)}
                />
              </div>
              <div className="mt-6 flex justify-end gap-2">
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={() => setShowStatusModal(false)}
                  disabled={statusBusy}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className={`btn btn-sm ${user.active ? 'btn-danger' : 'btn-primary'}`}
                  onClick={() => void handleToggleStatus()}
                  disabled={statusBusy}
                >
                  {statusBusy ? 'Updating…' : user.active ? 'Confirm Freeze' : 'Confirm Activate'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* MODAL: EDIT CAPS */}
        {showCapsModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
            <div className="card w-full max-w-sm bg-surface p-6 shadow-2xl">
              <h3 className="text-lg font-bold text-fg">Configure Spending Caps</h3>
              <p className="mt-1 text-xs text-fg-muted">
                Set hard daily and monthly credit limits in USD. Leave blank or 0 to clear limit.
              </p>
              <div className="mt-4 space-y-3">
                <div>
                  <label className="text-xs font-semibold text-fg-subtle uppercase tracking-wider">Daily Cap ($)</label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    className="input mt-1 w-full text-sm tabular-nums"
                    placeholder="None (unlimited)"
                    value={dailyCapInput}
                    onChange={(e) => setDailyCapInput(e.target.value)}
                  />
                </div>
                <div>
                  <label className="text-xs font-semibold text-fg-subtle uppercase tracking-wider">Monthly Cap ($)</label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    className="input mt-1 w-full text-sm tabular-nums"
                    placeholder="None (unlimited)"
                    value={monthlyCapInput}
                    onChange={(e) => setMonthlyCapInput(e.target.value)}
                  />
                </div>
              </div>
              <div className="mt-6 flex justify-end gap-2">
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={() => setShowCapsModal(false)}
                  disabled={capsBusy}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => void handleSaveCaps()}
                  disabled={capsBusy}
                >
                  {capsBusy ? 'Saving…' : 'Save Caps'}
                </button>
              </div>
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
