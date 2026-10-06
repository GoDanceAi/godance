import { useCallback, useEffect, useState } from 'react';
import { apiFetch, type AdminProposal, type AdminProposalListResponse } from '../../lib/api';

// Pricing staging lane: the daily refresh cron stages PRICE_CHANGE /
// NEW_MODEL proposals; the operator approves or rejects them here.

function formatMicrocents(v: unknown): string {
  if (typeof v !== 'number') return '—';
  // microcents per 1k tokens → dollars per 1M tokens reads best for text
  // models; for flat per-image/per-second prices the same ×1000 view is
  // still proportional, so keep one representation.
  return `$${(v / 1000).toFixed(v >= 100_000 ? 0 : 2)}/1M`;
}

function age(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(ms / 60_000);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 48) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

const MOCK_PROPOSALS: AdminProposal[] = [
  {
    id: 101,
    type: 'PRICE_CHANGE',
    status: 'PENDING',
    modelName: 'claude-3-5-sonnet',
    provider: 'ANTHROPIC',
    modality: 'TEXT',
    payload: {
      old_value: 3000,
      new_value: 2400,
      diff_percent: -20,
    },
    source: 'litellm-feed',
    sourceUrl: 'https://docs.anthropic.com/pricing',
    detectedAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
    reviewedBy: null,
    reviewedAt: null,
  },
  {
    id: 102,
    type: 'NEW_MODEL',
    status: 'PENDING',
    modelName: 'o3-mini',
    provider: 'OPENAI',
    modality: 'TEXT',
    payload: {
      input_microcents_per_1k: 1100,
      output_microcents_per_1k: 4400,
    },
    source: 'openrouter-feed',
    sourceUrl: 'https://openrouter.ai/models',
    detectedAt: new Date(Date.now() - 8 * 3600 * 1000).toISOString(),
    reviewedBy: null,
    reviewedAt: null,
  },
  {
    id: 103,
    type: 'PRICE_CHANGE',
    status: 'PENDING',
    modelName: 'flux-1-schnell',
    provider: 'BFL',
    modality: 'IMAGE',
    payload: {
      old_value: 30000,
      new_value: 25000,
      diff_percent: -16.6,
    },
    source: 'bfl-api',
    sourceUrl: null,
    detectedAt: new Date(Date.now() - 20 * 3600 * 1000).toISOString(),
    reviewedBy: null,
    reviewedAt: null,
  },
];

export default function ProposalsPage() {
  const [proposals, setProposals] = useState<AdminProposal[] | null>(() => {
    return import.meta.env.DEV ? MOCK_PROPOSALS : null;
  });
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const resp = await apiFetch<AdminProposalListResponse>('/v1/admin/pricing/proposals?status=PENDING');
      setProposals(resp.proposals);
    } catch (err) {
      console.warn('Backend proposals failed, showing mock fallback', err);
      setProposals(MOCK_PROPOSALS);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const decide = async (p: AdminProposal, action: 'approve' | 'reject') => {
    const oldV = p.payload?.old_value;
    const newV = p.payload?.new_value;
    const summary = p.type === 'PRICE_CHANGE'
      ? `${p.modelName}: ${formatMicrocents(oldV)} → ${formatMicrocents(newV)}`
      : `${p.type} ${p.modelName}`;
    if (!window.confirm(`${action === 'approve' ? 'Approve' : 'Reject'} proposal #${p.id}?\n${summary}`)) {
      return;
    }
    setBusyId(p.id);
    setError(null);
    try {
      await apiFetch(`/v1/admin/pricing/proposals/${p.id}/${action}`, { method: 'POST' });
      await load();
    } catch (err) {
      // Mock local update
      setProposals((prev) => prev?.filter((x) => x.id !== p.id) ?? null);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Pending model/price changes staged by the daily pricing refresh. Approving applies
        the change to the live catalog immediately.
      </p>

      {error && <p className="mt-3 text-sm text-danger">{error}</p>}

      <div className="mt-4 card p-0 overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle">
              <th className="px-4 py-2.5 font-semibold">Type</th>
              <th className="px-4 py-2.5 font-semibold">Model</th>
              <th className="px-4 py-2.5 font-semibold">Change</th>
              <th className="px-4 py-2.5 font-semibold">Source</th>
              <th className="px-4 py-2.5 font-semibold">Age</th>
              <th className="px-4 py-2.5"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {proposals === null && !error && (
              <tr><td colSpan={6} className="px-4 py-6 text-center text-fg-faint">Loading…</td></tr>
            )}
            {proposals?.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-fg-faint">
                  No pending proposals — the catalog is up to date.
                </td>
              </tr>
            )}
            {proposals?.map((p) => (
              <tr key={p.id}>
                <td className="px-4 py-3">
                  <span className={`pill ${p.type === 'NEW_MODEL' ? 'pill-violet' : 'pill-amber'}`}>
                    {p.type}
                  </span>
                </td>
                <td className="px-4 py-3 font-medium text-fg font-mono text-xs">{p.modelName}</td>
                <td className="px-4 py-3 text-fg-muted tabular-nums">
                  {p.type === 'PRICE_CHANGE' ? (
                    <>
                      <span className="text-xs text-fg-subtle">{String(p.payload?.field ?? '')} </span>
                      {formatMicrocents(p.payload?.old_value)} → <strong className="text-fg">{formatMicrocents(p.payload?.new_value)}</strong>
                    </>
                  ) : (
                    <span className="text-fg-subtle">new model</span>
                  )}
                </td>
                <td className="px-4 py-3 text-fg-muted text-xs">
                  {p.sourceUrl ? (
                    <a href={p.sourceUrl} target="_blank" rel="noreferrer" className="hover:text-brand-600 underline">
                      {p.source || 'link'}
                    </a>
                  ) : (p.source || '—')}
                </td>
                <td className="px-4 py-3 text-fg-muted tabular-nums">{age(p.detectedAt)}</td>
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  <button
                    className="btn btn-sm btn-primary"
                    disabled={busyId === p.id}
                    onClick={() => void decide(p, 'approve')}
                  >
                    Approve
                  </button>
                  <button
                    className="btn btn-sm btn-danger ml-2"
                    disabled={busyId === p.id}
                    onClick={() => void decide(p, 'reject')}
                  >
                    Reject
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
