import { useEffect, useState } from 'react';
import { apiFetch, ApiError, type AdminUsageSummary } from '../../lib/api';
import { formatCents } from '../../lib/auth';

// Revenue vs provider cost, last 30 days, plus top models/users by spend.
// Chart is hand-rolled SVG — no chart dependency for four bars and a legend.

export default function OverviewPage() {
  const [summary, setSummary] = useState<AdminUsageSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<AdminUsageSummary>('/v1/admin/usage/summary?groupBy=day')
      .then(setSummary)
      .catch((err) => setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load summary'));
  }, []);

  if (error) return <p className="text-sm text-danger">{error}</p>;
  if (!summary) return <p className="text-sm text-fg-faint">Loading…</p>;

  const totalRevenue = summary.days.reduce((s, d) => s + d.revenueCents, 0);
  const totalCost = summary.days.reduce((s, d) => s + d.costCents, 0);
  const totalRequests = summary.days.reduce((s, d) => s + d.requests, 0);
  const margin = totalRevenue - totalCost;

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Revenue (charged to users) vs provider cost, {summary.from} → {summary.to} (UTC).
      </p>

      {/* Totals */}
      <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-3">
        <Stat label="Revenue" value={formatCents(totalRevenue)} />
        <Stat label="Provider cost" value={formatCents(totalCost)} />
        <Stat label="Gross margin" value={formatCents(margin)} accent={margin >= 0 ? 'good' : 'bad'} />
        <Stat label="Requests" value={totalRequests.toLocaleString('en-US')} />
      </div>

      {/* Chart */}
      <div className="mt-6 card">
        <div className="flex items-center justify-between">
          <p className="text-sm font-semibold text-fg">Daily revenue vs cost</p>
          <div className="flex items-center gap-4 text-xs text-fg-muted">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-sm" style={{ background: 'var(--gd-violet-500, #8b5cf6)' }} />
              revenue
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-sm" style={{ background: 'var(--gd-cyan-500, #06b6d4)' }} />
              cost
            </span>
          </div>
        </div>
        <RevenueChart days={summary.days} />
      </div>

      {/* Top tables */}
      <div className="mt-6 grid md:grid-cols-2 gap-4">
        <div className="card p-0 overflow-x-auto">
          <p className="px-4 pt-4 text-sm font-semibold text-fg">Top models by spend</p>
          <table className="mt-2 w-full text-sm">
            <thead className="bg-surface-alt border-y border-border">
              <tr className="text-left text-fg-subtle">
                <th className="px-4 py-2 font-semibold">Model</th>
                <th className="px-4 py-2 font-semibold text-right">Revenue</th>
                <th className="px-4 py-2 font-semibold text-right">Cost</th>
                <th className="px-4 py-2 font-semibold text-right">Reqs</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {summary.topModels.length === 0 && (
                <tr><td colSpan={4} className="px-4 py-5 text-center text-fg-faint">No usage in window.</td></tr>
              )}
              {summary.topModels.map((m) => (
                <tr key={m.modelName}>
                  <td className="px-4 py-2.5 font-mono text-xs text-fg">{m.modelName}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums text-fg">{formatCents(m.revenueCents)}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{formatCents(m.costCents)}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{m.requests.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="card p-0 overflow-x-auto">
          <p className="px-4 pt-4 text-sm font-semibold text-fg">Top users by spend</p>
          <table className="mt-2 w-full text-sm">
            <thead className="bg-surface-alt border-y border-border">
              <tr className="text-left text-fg-subtle">
                <th className="px-4 py-2 font-semibold">User</th>
                <th className="px-4 py-2 font-semibold text-right">Revenue</th>
                <th className="px-4 py-2 font-semibold text-right">Reqs</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {summary.topUsers.length === 0 && (
                <tr><td colSpan={3} className="px-4 py-5 text-center text-fg-faint">No usage in window.</td></tr>
              )}
              {summary.topUsers.map((u) => (
                <tr key={u.userId}>
                  <td className="px-4 py-2.5 text-fg break-all">{u.email}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums text-fg">{formatCents(u.revenueCents)}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{u.requests.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: 'good' | 'bad' }) {
  const color = accent === 'good'
    ? 'var(--color-success-fg)'
    : accent === 'bad'
      ? 'var(--color-danger-fg)'
      : 'var(--color-fg)';
  return (
    <div className="card">
      <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">{label}</p>
      <p className="mt-1 text-2xl font-bold tracking-tight tabular-nums" style={{ color }}>{value}</p>
    </div>
  );
}

function RevenueChart({ days }: { days: AdminUsageSummary['days'] }) {
  const W = 900;
  const H = 220;
  const PAD = { top: 10, right: 8, bottom: 24, left: 48 };
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;

  const max = Math.max(1, ...days.map((d) => Math.max(d.revenueCents, d.costCents)));
  const slot = innerW / Math.max(1, days.length);
  const barW = Math.max(2, Math.min(12, slot * 0.35));
  const y = (cents: number) => PAD.top + innerH * (1 - cents / max);

  // ~4 horizontal gridlines at round values
  const ticks = [0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));

  return (
    <div className="mt-3 overflow-x-auto">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full min-w-[640px]" role="img"
           aria-label="Daily revenue versus provider cost bar chart">
        {/* gridlines + labels */}
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)}
                  stroke="var(--color-border)" strokeWidth="1" />
            <text x={PAD.left - 6} y={y(t) + 3} textAnchor="end" fontSize="10"
                  fill="var(--color-fg-faint)">
              ${(t / 100).toFixed(t >= 10_000 ? 0 : 2)}
            </text>
          </g>
        ))}
        <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top + innerH} y2={PAD.top + innerH}
              stroke="var(--color-border-strong)" strokeWidth="1" />

        {days.map((d, i) => {
          const cx = PAD.left + slot * i + slot / 2;
          const showLabel = days.length <= 10 || i % Math.ceil(days.length / 10) === 0;
          return (
            <g key={d.date}>
              <title>{`${d.date} — revenue ${formatCents(d.revenueCents)}, cost ${formatCents(d.costCents)}, ${d.requests} req`}</title>
              <rect x={cx - barW - 1} width={barW}
                    y={y(d.revenueCents)} height={Math.max(0, PAD.top + innerH - y(d.revenueCents))}
                    rx="1" fill="var(--gd-violet-500, #8b5cf6)" />
              <rect x={cx + 1} width={barW}
                    y={y(d.costCents)} height={Math.max(0, PAD.top + innerH - y(d.costCents))}
                    rx="1" fill="var(--gd-cyan-500, #06b6d4)" />
              {showLabel && (
                <text x={cx} y={H - 8} textAnchor="middle" fontSize="9" fill="var(--color-fg-faint)">
                  {d.date.slice(5)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
