import { useEffect, useRef, useState } from 'react';
import { formatCents } from '../../lib/auth';
import { getRecentUsage, type CustomerUsage } from '../../lib/billing';

export default function RecentUsagePanel() {
  const [rows, setRows] = useState<CustomerUsage[]>([]);
  const [filter, setFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const current = useRef(0);
  const mounted = useRef(true);
  async function load(signal?: AbortSignal) {
    const request = ++current.current;
    setLoading(true);
    setError('');
    try {
      const safeRows = await getRecentUsage(signal);
      if (mounted.current && request === current.current) setRows(safeRows);
    } catch (err) {
      if (mounted.current && request === current.current && !signal?.aborted) setError(err instanceof Error ? err.message : 'Usage could not be loaded.');
    } finally {
      if (mounted.current && request === current.current) setLoading(false);
    }
  }
  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController();
    void load(controller.signal);
    return () => { mounted.current = false; ++current.current; controller.abort(); };
  }, []);
  const shown = rows.filter((row) => row.model.toLowerCase().includes(filter.toLowerCase()));
  const total = shown.reduce((sum, row) => sum + BigInt(row.costCents), 0n);
  return <section className="card mt-8 space-y-4" aria-labelledby="recent-usage-title">
    <h2 id="recent-usage-title" className="text-lg font-semibold text-fg">Recent loaded usage</h2>
    <p className="text-sm text-fg-muted">Up to 200 recent records, newest first. Filters and totals cover only these loaded rows. This is not complete monthly, lifetime or payment history.</p>
    <button className="btn btn-sm btn-secondary" disabled={loading} onClick={() => void load()}>Refresh recent usage</button>
    {loading && <p role="status">Loading usage…</p>}
    {error && <p role="alert" className="text-sm text-danger">{error} Previously loaded rows, if any, remain below.</p>}
    {!loading && !error && rows.length === 0 && <p>No recent usage records.</p>}
    {rows.length > 0 && <>
      <label className="block text-sm text-fg-muted">Filter loaded models<input className="input mt-1 w-full" value={filter} onChange={(event) => setFilter(event.target.value)} /></label>
      <p className="text-sm">Recent loaded usage total ({shown.length} of {rows.length} loaded records): <strong>{total <= BigInt(Number.MAX_SAFE_INTEGER) ? formatCents(Number(total)) : 'Amount exceeds display limit'}</strong></p>
      <ul className="space-y-3">
        {shown.map((row) => <li key={row.id} className="rounded-md border border-border p-3 text-sm">
          <div className="flex flex-wrap justify-between gap-2"><strong className="break-all text-fg">{row.model}</strong><span>{formatCents(row.costCents)}</span></div>
          <p className="text-fg-muted">{new Date(row.recordedAt).toLocaleString()} · {row.modality}</p>
          <p className="text-fg-muted">Input tokens: {row.inputTokens} · Output tokens: {row.outputTokens}{row.cacheHit ? ' · Cache hit' : ''}{row.fallbackUsed ? ' · Fallback used' : ''}</p>
        </li>)}
      </ul>
    </>}
  </section>;
}
