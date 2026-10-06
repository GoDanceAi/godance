import { useCallback, useEffect, useState } from 'react';
import {
  apiFetch,
  type AdminAuditLogEntry,
  type AdminPaymentPageResponse,
  type AdminPaymentTransaction,
  type AdminWebhookHealthResponse,
} from '../../lib/api';

const MOCK_TRANSACTIONS: AdminPaymentTransaction[] = [
  {
    id: 'tx-001',
    userId: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    userEmail: 'bob.dev@startuplab.co',
    deltaCents: 5000,
    source: 'STRIPE',
    sourceRef: 'cs_live_a1b2c3d4e5f6g7h8i9j0k1l2',
    idempotencyKey: 'stripe-evt_1P8x9y2eZvKYlo2C',
    note: 'Stripe Checkout: $50.00 Standard Pack',
    occurredAt: new Date(Date.now() - 25 * 60 * 1000).toISOString(),
  },
  {
    id: 'tx-002',
    userId: 'usr_8a92f01d-5b23-4c91-9e77-123456789abc',
    userEmail: 'alice.designer@agency.io',
    deltaCents: 20000,
    source: 'NOWPAYMENTS',
    sourceRef: 'np_pay_9948201837',
    idempotencyKey: 'nowpayments-inv_882910',
    note: 'NOWPayments: 200 USDT (TRC-20)',
    occurredAt: new Date(Date.now() - 90 * 60 * 1000).toISOString(),
  },
  {
    id: 'tx-003',
    userId: 'usr_c8402a11-23d8-4f80-8772-9988aabbccdd',
    userEmail: 'charlie.enterprise@corp.com',
    deltaCents: -5000,
    source: 'REFUND',
    sourceRef: 're_live_3M7xq02eZvKYlo2C',
    idempotencyKey: 'refund-re_live_3M7xq02eZvKYlo2C',
    note: 'Disputed accidental double-charge refunded',
    occurredAt: new Date(Date.now() - 4 * 3600 * 1000).toISOString(),
  },
  {
    id: 'tx-004',
    userId: 'usr_e5210982-1244-482a-bc91-382910294821',
    userEmail: 'david.ai@research.edu',
    deltaCents: 10000,
    source: 'ADMIN_ADJUSTMENT',
    sourceRef: 'admin@godance.ai',
    idempotencyKey: 'reconcile-pi_3M7xq02eZvKYlo2C',
    note: 'Offline bank transfer manual reconcile: Wire #884920',
    occurredAt: new Date(Date.now() - 8 * 3600 * 1000).toISOString(),
  },
  {
    id: 'tx-005',
    userId: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    userEmail: 'bob.dev@startuplab.co',
    deltaCents: 2000,
    source: 'STRIPE',
    sourceRef: 'in_1P8x3b2eZvKYlo2C012',
    idempotencyKey: 'autorecharge-sub_8839210',
    note: 'Auto-recharge trigger: balance fell below $5 threshold',
    occurredAt: new Date(Date.now() - 22 * 3600 * 1000).toISOString(),
  },
];

const MOCK_EXCEPTIONS: AdminAuditLogEntry[] = [
  {
    id: 'exc-001',
    userId: null,
    eventType: 'billing.webhook.rejected',
    eventDetail: {
      reason: 'Stripe signature verification failed (stripe-signature header timestamp outside tolerance)',
      clientIp: '54.187.205.235',
      header: 't=1712390120,v1=9f82ab...c120',
    },
    ipAddress: '54.187.205.235',
    outcome: 'FAILURE',
    createdAt: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
  },
  {
    id: 'exc-002',
    userId: 'usr_f1928374-1234-5678-abcd-0987654321fe',
    eventType: 'billing.autorecharge.failed',
    eventDetail: {
      reason: 'Your card was declined: card_expired (exp_month: 09, exp_year: 2026)',
      paymentMethodId: 'pm_1P89xyz2eZvKYlo2C',
      targetEmail: 'elena.founder@saas.com',
      amountCents: 5000,
    },
    ipAddress: '10.0.0.1',
    outcome: 'FAILURE',
    createdAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString(),
  },
  {
    id: 'exc-003',
    userId: null,
    eventType: 'billing.webhook.ignored',
    eventDetail: {
      reason: 'Unhandled Stripe event type: invoice.created (no action required in pre-debit billing mode)',
      stripeEventId: 'evt_1P8abc999xyz',
    },
    ipAddress: '54.187.205.235',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 6 * 3600 * 1000).toISOString(),
  },
  {
    id: 'exc-004',
    userId: 'usr_b34c19ef-9a71-48ea-b2f0-c585ee715806',
    eventType: 'billing.topup.duplicate',
    eventDetail: {
      reason: 'Duplicate webhook replay: payment intent already credited',
      paymentRef: 'cs_live_a1b2c3d4e5f6g7h8i9j0k1l2',
      idempotencyKey: 'stripe-evt_1P8x9y2eZvKYlo2C',
    },
    ipAddress: '54.187.205.235',
    outcome: 'SUCCESS',
    createdAt: new Date(Date.now() - 12 * 3600 * 1000).toISOString(),
  },
];

type TabType = 'transactions' | 'exceptions';

export function PaymentsPage() {
  const [activeTab, setActiveTab] = useState<TabType>('transactions');
  const [transactions, setTransactions] = useState<AdminPaymentTransaction[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string>('ALL');
  const [page, setPage] = useState<number>(0);
  const [totalPages, setTotalPages] = useState<number>(1);
  const [totalTransactions, setTotalTransactions] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Webhook Health & Exceptions
  const [healthData, setHealthData] = useState<AdminWebhookHealthResponse | null>(null);

  // Modals
  const [showReconcileModal, setShowReconcileModal] = useState<boolean>(false);
  const [reconcileUserId, setReconcileUserId] = useState<string>('');
  const [reconcileAmountUsd, setReconcileAmountUsd] = useState<string>('50');
  const [reconcileRef, setReconcileRef] = useState<string>('');
  const [reconcileNote, setReconcileNote] = useState<string>('');
  const [reconcileSubmitting, setReconcileSubmitting] = useState<boolean>(false);
  const [reconcileError, setReconcileError] = useState<string | null>(null);

  const [showRefundModal, setShowRefundModal] = useState<boolean>(false);
  const [refundUserId, setRefundUserId] = useState<string>('');
  const [refundAmountUsd, setRefundAmountUsd] = useState<string>('20');
  const [refundRef, setRefundRef] = useState<string>('');
  const [refundReason, setRefundReason] = useState<string>('');
  const [refundSubmitting, setRefundSubmitting] = useState<boolean>(false);
  const [refundError, setRefundError] = useState<string | null>(null);

  const [selectedException, setSelectedException] = useState<AdminAuditLogEntry | null>(null);

  const loadTransactions = useCallback(async (src: string, p: number) => {
    setLoading(true);
    setError(null);
    try {
      const q = new URLSearchParams();
      if (src && src !== 'ALL') q.set('source', src);
      q.set('page', String(p));
      q.set('size', '20');

      const data = await apiFetch<AdminPaymentPageResponse>(`/v1/admin/payments?${q.toString()}`);
      setTransactions(data.transactions || []);
      setTotalPages(data.totalPages || 1);
      setTotalTransactions(data.totalTransactions || 0);
    } catch (err: unknown) {
      if (import.meta.env.DEV) {
        let filtered = MOCK_TRANSACTIONS;
        if (src && src !== 'ALL') {
          filtered = MOCK_TRANSACTIONS.filter((t) => t.source === src);
        }
        setTransactions(filtered);
        setTotalPages(1);
        setTotalTransactions(filtered.length);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load payments');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  const loadWebhookHealth = useCallback(async () => {
    try {
      const data = await apiFetch<AdminWebhookHealthResponse>('/v1/admin/payments/webhook-health');
      setHealthData(data);
    } catch {
      if (import.meta.env.DEV) {
        setHealthData({
          totalRejected: 1,
          totalIgnored: 1,
          totalAutoRechargeFailed: 1,
          totalDuplicate: 1,
          recentExceptions: MOCK_EXCEPTIONS,
        });
      }
    }
  }, []);

  useEffect(() => {
    loadTransactions(sourceFilter, page);
  }, [loadTransactions, sourceFilter, page]);

  useEffect(() => {
    loadWebhookHealth();
  }, [loadWebhookHealth]);

  const handleSourceChange = (src: string) => {
    setSourceFilter(src);
    setPage(0);
  };

  const handleReconcileSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setReconcileSubmitting(true);
    setReconcileError(null);
    try {
      const amountCents = Math.round(parseFloat(reconcileAmountUsd) * 100);
      if (isNaN(amountCents) || amountCents <= 0) {
        throw new Error('Please enter a valid positive dollar amount');
      }
      if (!reconcileUserId.trim()) throw new Error('User UUID is required');
      if (!reconcileRef.trim()) throw new Error('External payment reference is required');

      await apiFetch<AdminPaymentTransaction>('/v1/admin/payments/reconcile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: reconcileUserId.trim(),
          amountCents,
          paymentRef: reconcileRef.trim(),
          note: reconcileNote.trim() || 'Manual reconcile',
        }),
      });

      setShowReconcileModal(false);
      setReconcileUserId('');
      setReconcileRef('');
      setReconcileNote('');
      loadTransactions(sourceFilter, page);
    } catch (err: unknown) {
      setReconcileError(err instanceof Error ? err.message : 'Reconcile failed');
    } finally {
      setReconcileSubmitting(false);
    }
  };

  const handleRefundSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setRefundSubmitting(true);
    setRefundError(null);
    try {
      const amountCents = Math.round(parseFloat(refundAmountUsd) * 100);
      if (isNaN(amountCents) || amountCents <= 0) {
        throw new Error('Please enter a valid positive dollar amount');
      }
      if (!refundUserId.trim()) throw new Error('User UUID is required');
      if (!refundReason.trim()) throw new Error('Refund reason is required');

      await apiFetch<AdminPaymentTransaction>('/v1/admin/payments/refund', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: refundUserId.trim(),
          amountCents,
          paymentRef: refundRef.trim() || undefined,
          reason: refundReason.trim(),
        }),
      });

      setShowRefundModal(false);
      setRefundUserId('');
      setRefundRef('');
      setRefundReason('');
      loadTransactions(sourceFilter, page);
    } catch (err: unknown) {
      setRefundError(err instanceof Error ? err.message : 'Refund failed');
    } finally {
      setRefundSubmitting(false);
    }
  };

  // KPI calculations
  const totalSettledCents = transactions
    .filter((t) => t.deltaCents > 0)
    .reduce((sum, t) => sum + t.deltaCents, 0);
  const totalRefundCents = Math.abs(
    transactions
      .filter((t) => t.deltaCents < 0)
      .reduce((sum, t) => sum + t.deltaCents, 0)
  );

  const getSourceBadge = (src: string) => {
    switch (src) {
      case 'STRIPE':
        return 'bg-purple-900/40 text-purple-300 border-purple-800';
      case 'NOWPAYMENTS':
        return 'bg-amber-900/40 text-amber-300 border-amber-800';
      case 'REFUND':
        return 'bg-rose-900/40 text-rose-300 border-rose-800';
      case 'ADMIN_ADJUSTMENT':
        return 'bg-blue-900/40 text-blue-300 border-blue-800';
      default:
        return 'bg-neutral-800 text-neutral-300 border-neutral-700';
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-fg flex items-center gap-2">
            <span>💳</span> Payments & Billing Exceptions
          </h1>
          <p className="text-sm text-fg-muted mt-1">
            Global payment ledger, manual reconciliation, refunds, and webhook anomaly diagnostics.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowReconcileModal(true)}
            className="px-3.5 py-1.5 text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg shadow-xs transition-colors flex items-center gap-1.5"
          >
            <span>+</span> Reconcile Payment (补发入账)
          </button>
          <button
            onClick={() => setShowRefundModal(true)}
            className="px-3.5 py-1.5 text-xs font-semibold bg-rose-600/90 hover:bg-rose-600 text-white rounded-lg shadow-xs transition-colors flex items-center gap-1.5"
          >
            <span>-</span> Issue Refund (退款冲正)
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-surface border border-border rounded-xl p-4 shadow-2xs">
          <div className="text-xs font-medium text-fg-muted">Total Settled (Loaded Page)</div>
          <div className="text-2xl font-bold text-emerald-600 dark:text-emerald-400 mt-1">
            ${(totalSettledCents / 100).toFixed(2)}
          </div>
          <div className="text-[11px] text-fg-faint mt-1 flex items-center gap-1">
            <span>✓</span> Successful top-ups & credits
          </div>
        </div>

        <div className="bg-surface border border-border rounded-xl p-4 shadow-2xs">
          <div className="text-xs font-medium text-fg-muted">Total Transactions</div>
          <div className="text-2xl font-bold text-fg mt-1">{totalTransactions}</div>
          <div className="text-[11px] text-fg-faint mt-1">Global ledger transactions count</div>
        </div>

        <div className="bg-surface border border-border rounded-xl p-4 shadow-2xs">
          <div className="text-xs font-medium text-fg-muted">Refunds & Clawbacks</div>
          <div className="text-2xl font-bold text-rose-600 dark:text-rose-400 mt-1">
            ${(totalRefundCents / 100).toFixed(2)}
          </div>
          <div className="text-[11px] text-fg-faint mt-1">Settled disputes and reversals</div>
        </div>

        <div className="bg-surface border border-border rounded-xl p-4 shadow-2xs">
          <div className="text-xs font-medium text-fg-muted">Webhook Anomalies (24h)</div>
          <div className="text-2xl font-bold text-amber-600 dark:text-amber-400 mt-1">
            {(healthData?.totalRejected ?? 0) + (healthData?.totalAutoRechargeFailed ?? 0)}
          </div>
          <div className="text-[11px] text-fg-faint mt-1 flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
            Signature drops & failed auto-charges
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border gap-6">
        <button
          onClick={() => setActiveTab('transactions')}
          className={`pb-3 text-sm font-semibold transition-colors relative flex items-center gap-2 ${
            activeTab === 'transactions'
              ? 'text-primary border-b-2 border-primary'
              : 'text-fg-muted hover:text-fg'
          }`}
        >
          <span>📜</span> Payment Ledger & History
          <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-surface-alt text-fg-muted border border-border">
            {totalTransactions}
          </span>
        </button>
        <button
          onClick={() => setActiveTab('exceptions')}
          className={`pb-3 text-sm font-semibold transition-colors relative flex items-center gap-2 ${
            activeTab === 'exceptions'
              ? 'text-primary border-b-2 border-primary'
              : 'text-fg-muted hover:text-fg'
          }`}
        >
          <span>⚠️</span> Webhook & Billing Exceptions
          {((healthData?.recentExceptions?.length ?? 0) > 0) && (
            <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-amber-50 dark:bg-amber-950 text-amber-700 dark:text-amber-400 border border-amber-300 dark:border-amber-800">
              {healthData?.recentExceptions?.length}
            </span>
          )}
        </button>
      </div>

      {/* TAB 1: Transactions */}
      {activeTab === 'transactions' && (
        <div className="space-y-4">
          {/* Channel Filters */}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-1.5">
              {['ALL', 'STRIPE', 'NOWPAYMENTS', 'REFUND', 'ADMIN_ADJUSTMENT'].map((src) => (
                <button
                  key={src}
                  onClick={() => handleSourceChange(src)}
                  className={`px-3 py-1 text-xs rounded-lg font-medium transition-colors border ${
                    sourceFilter === src
                      ? 'bg-primary/10 text-primary border-primary/30 font-semibold shadow-2xs'
                      : 'bg-surface-alt text-fg-muted border-border hover:text-fg hover:border-border'
                  }`}
                >
                  {src === 'ALL' ? 'All Channels' : src.replace('_', ' ')}
                </button>
              ))}
            </div>

            <button
              onClick={() => loadTransactions(sourceFilter, page)}
              className="px-3 py-1 text-xs text-fg-muted hover:text-fg bg-surface border border-border rounded-lg hover:bg-surface-alt transition-colors"
            >
              ⟳ Refresh
            </button>
          </div>

          {error && (
            <div className="p-3 text-sm text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-lg">
              {error}
            </div>
          )}

          {/* Table */}
          <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-2xs">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs text-fg">
                <thead className="bg-surface-alt text-fg-muted font-semibold border-b border-border uppercase tracking-wider text-[11px]">
                  <tr>

                    <th className="py-3 px-4">Transaction / Time</th>
                    <th className="py-3 px-4">User</th>
                    <th className="py-3 px-4">Channel</th>
                    <th className="py-3 px-4">External Ref</th>
                    <th className="py-3 px-4 text-right">Amount</th>
                    <th className="py-3 px-4">Note / Details</th>
                    <th className="py-3 px-4 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-800/60 font-mono">
                  {loading ? (
                    <tr>
                      <td colSpan={7} className="py-8 text-center text-neutral-500">
                        Loading transactions...
                      </td>
                    </tr>
                  ) : transactions.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="py-8 text-center text-neutral-500">
                        No transactions found for this channel filter.
                      </td>
                    </tr>
                  ) : (
                    transactions.map((tx) => {
                      const isPositive = tx.deltaCents > 0;
                      return (
                        <tr key={tx.id} className="hover:bg-neutral-800/40 transition-colors">
                          <td className="py-3 px-4">
                            <div className="font-semibold text-neutral-200">{tx.id.substring(0, 14)}</div>
                            <div className="text-[10px] text-neutral-500 font-sans mt-0.5">
                              {new Date(tx.occurredAt).toLocaleString()}
                            </div>
                          </td>
                          <td className="py-3 px-4 font-sans">
                            <div className="text-neutral-200 font-medium">{tx.userEmail || 'Unknown'}</div>
                            <div className="text-[10px] text-neutral-500 font-mono">
                              {tx.userId ? tx.userId.substring(0, 16) + '...' : '-'}
                            </div>
                          </td>
                          <td className="py-3 px-4">
                            <span
                              className={`inline-block px-2 py-0.5 rounded text-[10px] font-medium border ${getSourceBadge(
                                tx.source
                              )}`}
                            >
                              {tx.source}
                            </span>
                          </td>
                          <td className="py-3 px-4 text-neutral-300 text-[11px]">
                            {tx.sourceRef ? (
                              <span className="bg-neutral-950 px-1.5 py-0.5 rounded border border-neutral-800">
                                {tx.sourceRef.length > 24
                                  ? tx.sourceRef.substring(0, 22) + '...'
                                  : tx.sourceRef}
                              </span>
                            ) : (
                              <span className="text-neutral-600">-</span>
                            )}
                          </td>
                          <td className="py-3 px-4 text-right">
                            <span
                              className={`font-semibold text-sm ${
                                isPositive ? 'text-emerald-400' : 'text-rose-400'
                              }`}
                            >
                              {isPositive ? '+' : '-'}${Math.abs(tx.deltaCents / 100).toFixed(2)}
                            </span>
                          </td>
                          <td className="py-3 px-4 font-sans text-neutral-400 max-w-xs truncate text-[11px]">
                            {tx.note || <span className="text-neutral-600 italic">No notes</span>}
                          </td>
                          <td className="py-3 px-4 text-right">
                            {isPositive && (
                              <button
                                onClick={() => {
                                  setRefundUserId(tx.userId);
                                  setRefundAmountUsd((tx.deltaCents / 100).toFixed(2));
                                  setRefundRef(tx.sourceRef || tx.id);
                                  setShowRefundModal(true);
                                }}
                                className="px-2 py-1 text-[11px] text-rose-400 hover:text-rose-300 hover:bg-rose-950/40 rounded border border-rose-900/60 transition-colors font-sans"
                              >
                                Refund
                              </button>
                            )}
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="p-3 border-t border-neutral-800 flex items-center justify-between text-xs text-neutral-400">
                <div>
                  Page <span className="text-neutral-200">{page + 1}</span> of{' '}
                  <span className="text-neutral-200">{totalPages}</span>
                </div>
                <div className="flex gap-2">
                  <button
                    disabled={page === 0}
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    className="px-2.5 py-1 bg-neutral-800 hover:bg-neutral-700 disabled:opacity-40 disabled:hover:bg-neutral-800 rounded transition-colors text-neutral-200"
                  >
                    Previous
                  </button>
                  <button
                    disabled={page >= totalPages - 1}
                    onClick={() => setPage((p) => p + 1)}
                    className="px-2.5 py-1 bg-neutral-800 hover:bg-neutral-700 disabled:opacity-40 disabled:hover:bg-neutral-800 rounded transition-colors text-neutral-200"
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* TAB 2: Exceptions */}
      {activeTab === 'exceptions' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
            <div className="bg-neutral-900 border border-neutral-800 rounded-lg p-3">
              <div className="text-[11px] text-neutral-400">Rejected Signatures</div>
              <div className="text-xl font-bold text-rose-400 mt-0.5">
                {healthData?.totalRejected ?? 0}
              </div>
            </div>
            <div className="bg-neutral-900 border border-neutral-800 rounded-lg p-3">
              <div className="text-[11px] text-neutral-400">Ignored Events</div>
              <div className="text-xl font-bold text-neutral-200 mt-0.5">
                {healthData?.totalIgnored ?? 0}
              </div>
            </div>
            <div className="bg-neutral-900 border border-neutral-800 rounded-lg p-3">
              <div className="text-[11px] text-neutral-400">Auto-Recharge Fails</div>
              <div className="text-xl font-bold text-amber-400 mt-0.5">
                {healthData?.totalAutoRechargeFailed ?? 0}
              </div>
            </div>
            <div className="bg-neutral-900 border border-neutral-800 rounded-lg p-3">
              <div className="text-[11px] text-neutral-400">Duplicate Webhooks</div>
              <div className="text-xl font-bold text-blue-400 mt-0.5">
                {healthData?.totalDuplicate ?? 0}
              </div>
            </div>
          </div>

          {/* Exceptions Table */}
          <div className="bg-neutral-900 border border-neutral-800 rounded-xl overflow-hidden shadow-sm">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs text-neutral-300">
                <thead className="bg-neutral-950/60 text-neutral-400 font-semibold border-b border-neutral-800 uppercase tracking-wider text-[11px]">
                  <tr>
                    <th className="py-3 px-4">Event Type</th>
                    <th className="py-3 px-4">Outcome</th>
                    <th className="py-3 px-4">Source IP</th>
                    <th className="py-3 px-4">Timestamp</th>
                    <th className="py-3 px-4">Reason / Diagnosis</th>
                    <th className="py-3 px-4 text-right">Payload</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-800/60 font-mono">
                  {(healthData?.recentExceptions || []).length === 0 ? (
                    <tr>
                      <td colSpan={6} className="py-8 text-center text-neutral-500 font-sans">
                        No billing or webhook exceptions recorded in the last 24 hours. All systems healthy!
                      </td>
                    </tr>
                  ) : (
                    healthData?.recentExceptions.map((exc) => (
                      <tr key={exc.id} className="hover:bg-neutral-800/40 transition-colors">
                        <td className="py-3 px-4 font-semibold text-neutral-200">
                          {exc.eventType}
                        </td>
                        <td className="py-3 px-4">
                          <span
                            className={`inline-block px-2 py-0.5 rounded text-[10px] font-medium border ${
                              exc.outcome === 'FAILURE'
                                ? 'bg-rose-900/40 text-rose-300 border-rose-800'
                                : 'bg-neutral-800 text-neutral-300 border-neutral-700'
                            }`}
                          >
                            {exc.outcome}
                          </span>
                        </td>
                        <td className="py-3 px-4 text-neutral-400">{exc.ipAddress || '-'}</td>
                        <td className="py-3 px-4 font-sans text-neutral-400 text-[11px]">
                          {new Date(exc.createdAt).toLocaleString()}
                        </td>
                        <td className="py-3 px-4 font-sans text-neutral-300 max-w-sm truncate text-[11px]">
                          {String(exc.eventDetail?.reason || 'No description')}
                        </td>
                        <td className="py-3 px-4 text-right font-sans">
                          <button
                            onClick={() => setSelectedException(exc)}
                            className="px-2 py-1 text-[11px] text-indigo-400 hover:text-indigo-300 hover:bg-indigo-950/40 rounded border border-indigo-900/60 transition-colors"
                          >
                            View Detail
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* RECONCILE MODAL */}
      {showReconcileModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
          <div className="bg-surface border border-border rounded-xl max-w-lg w-full p-6 shadow-2xl animate-in fade-in zoom-in-95 duration-150 text-fg">
            <h3 className="text-lg font-bold text-fg flex items-center gap-2">
              <span>💳</span> Manual Reconcile (掉单补发入账)
            </h3>
            <p className="text-xs text-fg-muted mt-1">
              Credit a user account for a confirmed external payment that missed webhook processing.
              Strictly guarded with duplicate idempotency check.
            </p>

            <form onSubmit={handleReconcileSubmit} className="mt-4 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-fg-muted mb-1">
                  User ID (UUID) *
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. usr_b34c19ef-9a71-48ea-b2f0-c585ee715806"
                  value={reconcileUserId}
                  onChange={(e) => setReconcileUserId(e.target.value)}
                  className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary font-mono"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold text-fg-muted mb-1">
                    Amount ($ USD) *
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0.01"
                    required
                    value={reconcileAmountUsd}
                    onChange={(e) => setReconcileAmountUsd(e.target.value)}
                    className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg focus:outline-hidden focus:border-primary font-mono"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-fg-muted mb-1">
                    External Payment Ref *
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="cs_live_... / pi_..."
                    value={reconcileRef}
                    onChange={(e) => setReconcileRef(e.target.value)}
                    className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-fg-muted mb-1">
                  Note / Reason
                </label>
                <textarea
                  rows={2}
                  placeholder="e.g. Offline bank transfer confirmed or Stripe missed webhook"
                  value={reconcileNote}
                  onChange={(e) => setReconcileNote(e.target.value)}
                  className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary"
                />
              </div>

              {reconcileError && (
                <div className="p-2.5 text-xs text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-lg">
                  {reconcileError}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowReconcileModal(false)}
                  className="px-4 py-2 text-xs text-fg-muted hover:bg-surface-alt rounded-lg transition-colors border border-border"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={reconcileSubmitting}
                  className="px-4 py-2 text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg transition-colors disabled:opacity-50"
                >
                  {reconcileSubmitting ? 'Crediting...' : 'Confirm Reconcile'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* REFUND MODAL */}
      {showRefundModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
          <div className="bg-surface border border-border rounded-xl max-w-lg w-full p-6 shadow-2xl animate-in fade-in zoom-in-95 duration-150 text-fg">
            <h3 className="text-lg font-bold text-fg flex items-center gap-2">
              <span>⚠️</span> Issue Refund & Clawback (退款冲正)
            </h3>
            <p className="text-xs text-fg-muted mt-1">
              Debits user balance and records an audited REFUND ledger entry.
            </p>

            <form onSubmit={handleRefundSubmit} className="mt-4 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-fg-muted mb-1">
                  User ID (UUID) *
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. usr_b34c19ef-9a71-48ea-b2f0-c585ee715806"
                  value={refundUserId}
                  onChange={(e) => setRefundUserId(e.target.value)}
                  className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary font-mono"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold text-fg-muted mb-1">
                    Refund Amount ($ USD) *
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0.01"
                    required
                    value={refundAmountUsd}
                    onChange={(e) => setRefundAmountUsd(e.target.value)}
                    className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg focus:outline-hidden focus:border-primary font-mono"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-fg-muted mb-1">
                    Original Payment Ref
                  </label>
                  <input
                    type="text"
                    placeholder="re_... or cs_..."
                    value={refundRef}
                    onChange={(e) => setRefundRef(e.target.value)}
                    className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-fg-muted mb-1">
                  Refund Reason *
                </label>
                <textarea
                  rows={2}
                  required
                  placeholder="e.g. Customer dispute settlement / accidental duplicate payment"
                  value={refundReason}
                  onChange={(e) => setRefundReason(e.target.value)}
                  className="w-full bg-surface-alt border border-border rounded-lg px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-hidden focus:border-primary"
                />
              </div>

              {refundError && (
                <div className="p-2.5 text-xs text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-lg">
                  {refundError}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowRefundModal(false)}
                  className="px-4 py-2 text-xs text-fg-muted hover:bg-surface-alt rounded-lg transition-colors border border-border"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={refundSubmitting}
                  className="px-4 py-2 text-xs font-semibold bg-rose-600 hover:bg-rose-500 text-white rounded-lg transition-colors disabled:opacity-50"
                >
                  {refundSubmitting ? 'Processing...' : 'Confirm Refund'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* EXCEPTION DETAIL MODAL */}
      {selectedException && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
          <div className="bg-surface border border-border rounded-xl max-w-lg w-full p-6 shadow-2xl animate-in fade-in zoom-in-95 duration-150 text-fg">
            <div className="flex items-center justify-between pb-3 border-b border-border">
              <h3 className="text-base font-bold text-fg">Exception Diagnostic Detail</h3>
              <button
                onClick={() => setSelectedException(null)}
                className="text-fg-muted hover:text-fg text-sm font-semibold p-1 hover:bg-surface-alt rounded-md"
              >
                ✕
              </button>
            </div>

            <div className="mt-4 space-y-3 text-xs">
              <div>
                <span className="text-fg-muted">Event Type: </span>
                <span className="text-fg font-mono font-semibold">
                  {selectedException.eventType}
                </span>
              </div>
              <div>
                <span className="text-fg-muted">Outcome: </span>
                <span className="text-rose-600 dark:text-rose-400 font-mono font-semibold">
                  {selectedException.outcome}
                </span>
              </div>
              <div>
                <span className="text-fg-muted">Source IP: </span>
                <span className="text-fg font-mono">
                  {selectedException.ipAddress || '-'}
                </span>
              </div>
              <div>
                <span className="text-fg-muted">Timestamp: </span>
                <span className="text-fg font-mono">
                  {selectedException.createdAt}
                </span>
              </div>

              <div>
                <span className="text-fg-muted block mb-1">Event Detail (JSON):</span>
                <pre className="bg-surface-alt border border-border p-3 rounded-lg overflow-x-auto text-[11px] text-emerald-600 dark:text-emerald-400 font-mono max-h-56">
                  {JSON.stringify(selectedException.eventDetail, null, 2)}
                </pre>
              </div>
            </div>

            <div className="mt-5 flex justify-end">
              <button
                onClick={() => setSelectedException(null)}
                className="px-4 py-1.5 text-xs bg-surface-alt hover:bg-border text-fg rounded-lg transition-colors font-medium border border-border"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

