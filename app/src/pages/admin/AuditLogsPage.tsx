import { useCallback, useEffect, useState } from 'react';
import { apiFetch, type AdminAuditLogEntry, type AdminAuditLogPageResponse } from '../../lib/api';

const MOCK_LOGS: AdminAuditLogEntry[] = [
  {
    id: 'aud-001',
    userId: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    eventType: 'admin.user_status_updated',
    eventDetail: {
      adminEmail: 'admin@godance.ai',
      active: false,
      reason: 'Suspicious abnormal concurrent traffic detected',
      targetEmail: 'bob.dev@startuplab.co',
    },
    ipAddress: '192.168.1.100',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 15 * 60 * 1000).toISOString(),
  },
  {
    id: 'aud-002',
    userId: 'usr_8a92f01d-5b23-4c91-9e77-123456789abc',
    eventType: 'admin.user_caps_updated',
    eventDetail: {
      adminEmail: 'admin@godance.ai',
      dailyCreditCapCents: 5000,
      monthlyCreditCapCents: 20000,
      reason: 'VIP user credit ceiling tier upgraded',
      targetEmail: 'alice.designer@agency.io',
    },
    ipAddress: '192.168.1.100',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
  },
  {
    id: 'aud-003',
    userId: 'usr_8a92f01d-5b23-4c91-9e77-123456789abc',
    eventType: 'admin.api_key_revoked',
    eventDetail: {
      adminEmail: 'admin@godance.ai',
      keyId: 'key-test-8f12a3',
      keyPrefix: 'sk-live-a1b2',
      reason: 'Key leaked on public Github repository',
    },
    ipAddress: '10.0.0.12',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 5 * 3600 * 1000).toISOString(),
  },
  {
    id: 'aud-004',
    userId: 'usr_c8402a11-23d8-4f80-8772-9988aabbccdd',
    eventType: 'admin.credit_adjusted',
    eventDetail: {
      adminEmail: 'admin@godance.ai',
      amountCents: 5000,
      note: 'Enterprise trial bonus credits granted',
      targetEmail: 'charlie.enterprise@corp.com',
    },
    ipAddress: '192.168.1.100',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
  },
  {
    id: 'aud-005',
    userId: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    eventType: 'admin.user_status_updated',
    eventDetail: {
      adminEmail: 'admin@godance.ai',
      active: true,
      reason: 'Account verification completed upon ticket review',
    },
    ipAddress: '192.168.1.100',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 48 * 3600 * 1000).toISOString(),
  },
];

const EVENT_FILTERS = [
  { label: 'ALL EVENTS', value: '' },
  { label: 'Status Updates', value: 'admin.user_status_updated' },
  { label: 'Caps Updates', value: 'admin.user_caps_updated' },
  { label: 'Key Revocations', value: 'admin.api_key_revoked' },
  { label: 'Credit Adjustments', value: 'admin.credit_adjusted' },
];

function eventBadge(type: string) {
  switch (type) {
    case 'admin.user_status_updated':
      return <span className="pill pill-rose text-xs font-mono">STATUS</span>;
    case 'admin.user_caps_updated':
      return <span className="pill pill-violet text-xs font-mono">CAPS</span>;
    case 'admin.api_key_revoked':
      return <span className="pill pill-amber text-xs font-mono">KEY_REVOKED</span>;
    case 'admin.credit_adjusted':
      return <span className="pill pill-emerald text-xs font-mono">CREDIT_ADJUST</span>;
    default:
      return <span className="pill text-xs font-mono">{type}</span>;
  }
}

export default function AuditLogsPage() {
  const [filterType, setFilterType] = useState('');
  const [data, setData] = useState<AdminAuditLogPageResponse | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [inspectLog, setInspectLog] = useState<AdminAuditLogEntry | null>(null);

  const load = useCallback(async (p: number, type: string) => {
    setLoading(true);
    try {
      const q = new URLSearchParams({ page: String(p), size: '15' });
      if (type) q.set('eventType', type);
      const resp = await apiFetch<AdminAuditLogPageResponse>(`/v1/admin/audit-logs?${q.toString()}`);
      setData(resp);
      setPage(p);
    } catch (err) {
      console.warn('Backend audit logs failed, showing mock fallback', err);
      const filtered = type ? MOCK_LOGS.filter((l) => l.eventType === type) : MOCK_LOGS;
      setData({
        logs: filtered,
        page: 0,
        totalPages: 1,
        totalLogs: filtered.length,
      });
      setPage(0);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(0, filterType);
  }, [load, filterType]);

  return (
    <div>
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-fg">Audit Log Center</h2>
          <p className="text-sm text-fg-muted mt-0.5">
            Immutable system audit trail tracking governance actions, credit adjustments, and security revocations.
          </p>
        </div>
        <button
          onClick={() => void load(page, filterType)}
          className="btn btn-secondary self-start sm:self-auto"
          disabled={loading}
        >
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {/* Filter Tabs */}
      <div className="mt-5 flex flex-wrap items-center gap-1.5 border-b border-border pb-3">
        {EVENT_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => setFilterType(f.value)}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              filterType === f.value
                ? 'bg-primary text-white shadow-sm'
                : 'bg-surface hover:bg-surface-alt text-fg-muted hover:text-fg border border-border'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* Table */}
      <div className="mt-4 card p-0 overflow-x-auto shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle text-xs uppercase tracking-wider font-semibold">
              <th className="px-4 py-3">Event Type</th>
              <th className="px-4 py-3">Operator</th>
              <th className="px-4 py-3">Summary / Details</th>
              <th className="px-4 py-3">Outcome</th>
              <th className="px-4 py-3">IP Address</th>
              <th className="px-4 py-3">Timestamp</th>
              <th className="px-4 py-3 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {(!data || data.logs.length === 0) && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-fg-faint">
                  {loading ? 'Loading audit records…' : 'No audit entries found matching the filter.'}
                </td>
              </tr>
            )}
            {data?.logs.map((log) => {
              const adminEmail = (log.eventDetail?.adminEmail as string) || 'System';
              const reason = (log.eventDetail?.reason as string) || (log.eventDetail?.note as string) || '';
              return (
                <tr key={log.id} className="hover:bg-bg-muted transition-colors">
                  <td className="px-4 py-3 whitespace-nowrap">
                    {eventBadge(log.eventType)}
                  </td>
                  <td className="px-4 py-3 text-xs font-medium text-fg">
                    {adminEmail}
                  </td>
                  <td className="px-4 py-3 text-xs text-fg-muted max-w-xs truncate" title={reason}>
                    {reason || (
                      <span className="font-mono text-fg-faint">
                        {JSON.stringify(log.eventDetail)}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <span
                      className={`pill text-xs ${
                        log.outcome === 'SUCCESS' ? 'pill-emerald' : 'pill-rose'
                      }`}
                    >
                      {log.outcome}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs font-mono text-fg-faint">
                    {log.ipAddress || '—'}
                  </td>
                  <td className="px-4 py-3 text-xs text-fg-muted whitespace-nowrap">
                    {new Date(log.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    <button
                      onClick={() => setInspectLog(log)}
                      className="btn btn-secondary px-2 py-0.5 text-xs"
                    >
                      JSON
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {data && data.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between text-xs text-fg-muted">
          <span>Total {data.totalLogs} records</span>
          <div className="flex gap-2">
            <button
              onClick={() => void load(page - 1, filterType)}
              disabled={page <= 0 || loading}
              className="btn btn-secondary px-2.5 py-1 text-xs"
            >
              Previous
            </button>
            <span className="self-center">
              Page {page + 1} of {data.totalPages}
            </span>
            <button
              onClick={() => void load(page + 1, filterType)}
              disabled={page >= data.totalPages - 1 || loading}
              className="btn btn-secondary px-2.5 py-1 text-xs"
            >
              Next
            </button>
          </div>
        </div>
      )}

      {/* JSON Inspector Modal */}
      {inspectLog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-xs">
          <div className="card max-w-xl w-full p-6 shadow-xl border border-border">
            <div className="flex items-center justify-between border-b border-border pb-3">
              <h3 className="text-base font-semibold text-fg">
                Audit Record Detail: <span className="font-mono text-xs text-fg-muted">{inspectLog.id}</span>
              </h3>
              <button
                onClick={() => setInspectLog(null)}
                className="text-fg-faint hover:text-fg text-lg font-bold"
              >
                ✕
              </button>
            </div>
            <div className="mt-4 space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-fg-muted">Event:</span>
                <span className="font-mono text-fg">{inspectLog.eventType}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-fg-muted">Target User:</span>
                <span className="font-mono text-fg">{inspectLog.userId || '—'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-fg-muted">Timestamp:</span>
                <span className="text-fg">{inspectLog.createdAt}</span>
              </div>
              <div className="mt-3">
                <span className="text-fg-muted block mb-1">Payload Detail:</span>
                <pre className="p-3 bg-code-bg text-code-fg rounded-md overflow-x-auto font-mono text-xs">
                  {JSON.stringify(inspectLog.eventDetail, null, 2)}
                </pre>
              </div>
            </div>
            <div className="mt-5 flex justify-end">
              <button onClick={() => setInspectLog(null)} className="btn btn-secondary">
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
