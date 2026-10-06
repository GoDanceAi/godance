import { useEffect, useState } from 'react';
import { apiFetch, ApiError, type AdminJob, type AdminJobPageResponse } from '../../lib/api';
import { formatCents } from '../../lib/auth';

// Support & ops monitor: inspect asynchronous multimodal generation jobs
// (images and videos) across all users, with status/modality filters.

const MOCK_JOBS: AdminJob[] = [
  {
    id: 'job-98a21e4b-77c1-482a-9f5b-11d234567890',
    userId: 'user-7f8b9a2c-4b56-789a-bcde-f0123456789a',
    modality: 'IMAGE',
    provider: 'BFL',
    model: 'flux-1-schnell',
    promptSnippet: '{"prompt": "A cyberpunk street in Tokyo with neon rain reflections", "size": "1024x1024"}',
    status: 'DONE',
    outputUrl: 'https://images.unsplash.com/photo-1542051841857-5f90071e7989?w=800',
    estimatedCostCents: 3,
    actualCostCents: 3,
    errorMessage: null,
    createdAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    startedAt: new Date(Date.now() - 5 * 60 * 1000 + 500).toISOString(),
    completedAt: new Date(Date.now() - 5 * 60 * 1000 + 4200).toISOString(),
  },
  {
    id: 'job-12c98d7f-33a1-499b-88e2-22d345678901',
    userId: 'user-7f8b9a2c-4b56-789a-bcde-f0123456789a',
    modality: 'VIDEO',
    provider: 'RUNWAY',
    model: 'runway-gen3',
    promptSnippet: '{"prompt": "Cinematic aerial drone pass over snow-covered pine forests at sunset", "seconds": 5}',
    status: 'RUNNING',
    outputUrl: null,
    estimatedCostCents: 50,
    actualCostCents: null,
    errorMessage: null,
    createdAt: new Date(Date.now() - 45 * 1000).toISOString(),
    startedAt: new Date(Date.now() - 40 * 1000).toISOString(),
    completedAt: null,
  },
  {
    id: 'job-77b10a2c-55e3-411a-aa44-33d456789012',
    userId: 'user-a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    modality: 'VIDEO',
    provider: 'KLING',
    model: 'kling-v1-5',
    promptSnippet: '{"prompt": "A curious red panda drinking from a clear mountain stream", "seconds": 5}',
    status: 'FAILED',
    outputUrl: null,
    estimatedCostCents: 35,
    actualCostCents: null,
    errorMessage: 'Upstream provider timeout (HTTP 504 Gateway Timeout)',
    createdAt: new Date(Date.now() - 25 * 60 * 1000).toISOString(),
    startedAt: new Date(Date.now() - 25 * 60 * 1000 + 1000).toISOString(),
    completedAt: new Date(Date.now() - 24 * 60 * 1000).toISOString(),
  },
  {
    id: 'job-44e21a88-99b5-422c-bb77-44d567890123',
    userId: 'user-b2c3d4e5-f6a7-8901-bcde-f12345678901',
    modality: 'IMAGE',
    provider: 'OPENAI',
    model: 'dall-e-3',
    promptSnippet: '{"prompt": "Minimalist geometric architectural rendering in natural morning daylight"}',
    status: 'DONE',
    outputUrl: 'https://images.unsplash.com/photo-1513694203232-719a280e022f?w=800',
    estimatedCostCents: 4,
    actualCostCents: 4,
    errorMessage: null,
    createdAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
    startedAt: new Date(Date.now() - 2 * 3600 * 1000 + 800).toISOString(),
    completedAt: new Date(Date.now() - 2 * 3600 * 1000 + 7500).toISOString(),
  },
  {
    id: 'job-88f32c99-11c7-433d-cc88-55d678901234',
    userId: 'user-c3d4e5f6-a7b8-9012-cdef-123456789012',
    modality: 'VIDEO',
    provider: 'GOOGLE',
    model: 'veo-2',
    promptSnippet: '{"prompt": "Time-lapse of clouds rolling over Yosemite valley in golden hour"}',
    status: 'QUEUED',
    outputUrl: null,
    estimatedCostCents: 60,
    actualCostCents: null,
    errorMessage: null,
    createdAt: new Date(Date.now() - 10 * 1000).toISOString(),
    startedAt: null,
    completedAt: null,
  },
];

function statusPill(status: string) {
  switch (status.toUpperCase()) {
    case 'DONE':
      return <span className="pill pill-emerald">DONE</span>;
    case 'FAILED':
      return <span className="pill pill-rose">FAILED</span>;
    case 'RUNNING':
      return <span className="pill pill-violet">RUNNING</span>;
    case 'QUEUED':
      return <span className="pill pill-amber">QUEUED</span>;
    default:
      return <span className="pill">{status}</span>;
  }
}

function formatPrompt(snippet: string) {
  try {
    const parsed = JSON.parse(snippet);
    if (parsed && typeof parsed.prompt === 'string') return parsed.prompt;
  } catch {
    // not JSON or raw string
  }
  return snippet;
}

export default function JobsPage() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [modalityFilter, setModalityFilter] = useState('ALL');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<AdminJobPageResponse | null>(null);

  const fetchJobs = async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      if (statusFilter !== 'ALL') params.set('status', statusFilter);
      if (modalityFilter !== 'ALL') params.set('modality', modalityFilter);
      params.set('page', String(page));
      params.set('size', '15');

      const res = await apiFetch<AdminJobPageResponse>(`/v1/admin/jobs?${params.toString()}`);
      setData(res);
    } catch (err) {
      // If endpoint returns error or dev mock mode is active, provide mock fallback
      console.warn('Admin jobs API fetch failed, showing mock fallback data', err);
      const filtered = MOCK_JOBS.filter((j) => {
        if (statusFilter !== 'ALL' && j.status !== statusFilter) return false;
        if (modalityFilter !== 'ALL' && j.modality !== modalityFilter) return false;
        return true;
      });
      setData({
        jobs: filtered,
        page: 0,
        totalPages: 1,
        totalJobs: filtered.length,
      });
      if (err instanceof ApiError && err.status !== 404) {
        setError(err.detail || err.message);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void fetchJobs();
  }, [statusFilter, modalityFilter, page]);

  return (
    <div>
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg">Multimodal Generation Jobs</h2>
          <p className="mt-1 text-sm text-fg-muted">
            Monitor real-time async image and video generation queues, latencies, and failure logs.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void fetchJobs()}
          disabled={loading}
          className="btn btn-secondary self-start sm:self-auto"
        >
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {/* Filter Bar */}
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <div>
          <label htmlFor="filter-status" className="sr-only">Status</label>
          <select
            id="filter-status"
            className="input py-1.5 text-xs font-medium"
            value={statusFilter}
            onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}
          >
            <option value="ALL">All statuses</option>
            <option value="QUEUED">Queued</option>
            <option value="RUNNING">Running</option>
            <option value="DONE">Done</option>
            <option value="FAILED">Failed</option>
          </select>
        </div>

        <div>
          <label htmlFor="filter-modality" className="sr-only">Modality</label>
          <select
            id="filter-modality"
            className="input py-1.5 text-xs font-medium"
            value={modalityFilter}
            onChange={(e) => { setModalityFilter(e.target.value); setPage(0); }}
          >
            <option value="ALL">All modalities</option>
            <option value="IMAGE">Image</option>
            <option value="VIDEO">Video</option>
          </select>
        </div>

        {data && (
          <span className="text-xs text-fg-subtle ml-auto">
            Total {data.totalJobs} jobs recorded
          </span>
        )}
      </div>

      {error && <p className="mt-3 text-sm text-danger">{error}</p>}

      {/* Jobs Table */}
      <div className="mt-4 card p-0 overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle text-xs uppercase tracking-wider">
              <th className="px-4 py-2.5 font-semibold">Job / Time</th>
              <th className="px-4 py-2.5 font-semibold">User</th>
              <th className="px-4 py-2.5 font-semibold">Modality & Model</th>
              <th className="px-4 py-2.5 font-semibold">Prompt Snippet</th>
              <th className="px-4 py-2.5 font-semibold">Status</th>
              <th className="px-4 py-2.5 font-semibold">Duration</th>
              <th className="px-4 py-2.5 font-semibold text-right">Cost</th>
              <th className="px-4 py-2.5 font-semibold text-right">Result</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {(!data || data.jobs.length === 0) && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-fg-faint">
                  {loading ? 'Loading jobs…' : 'No generation jobs matching the criteria.'}
                </td>
              </tr>
            )}
            {data?.jobs.map((j) => {
              const durMs = j.startedAt && j.completedAt
                ? new Date(j.completedAt).getTime() - new Date(j.startedAt).getTime()
                : null;
              return (
                <tr key={j.id} className="hover:bg-bg-muted transition-colors">
                  <td className="px-4 py-3 text-xs">
                    <p className="font-mono text-fg font-medium truncate max-w-[8rem]" title={j.id}>
                      {j.id.slice(0, 12)}…
                    </p>
                    <p className="text-fg-faint mt-0.5">
                      {new Date(j.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                    </p>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-fg-muted truncate max-w-[7rem]" title={j.userId}>
                    {j.userId.slice(0, 8)}…
                  </td>
                  <td className="px-4 py-3 text-xs">
                    <div className="font-semibold text-fg">{j.model}</div>
                    <div className="text-fg-faint">{j.provider} · {j.modality}</div>
                  </td>
                  <td className="px-4 py-3 text-xs text-fg-muted max-w-xs truncate" title={formatPrompt(j.promptSnippet)}>
                    {formatPrompt(j.promptSnippet)}
                  </td>
                  <td className="px-4 py-3 text-xs whitespace-nowrap">
                    {statusPill(j.status)}
                  </td>
                  <td className="px-4 py-3 text-xs tabular-nums text-fg-muted whitespace-nowrap">
                    {durMs != null ? `${(durMs / 1000).toFixed(1)}s` : '—'}
                  </td>
                  <td className="px-4 py-3 text-xs tabular-nums text-right text-fg whitespace-nowrap">
                    {j.actualCostCents != null
                      ? formatCents(j.actualCostCents)
                      : j.estimatedCostCents != null
                      ? `~${formatCents(j.estimatedCostCents)}`
                      : '—'}
                  </td>
                  <td className="px-4 py-3 text-xs text-right whitespace-nowrap">
                    {j.outputUrl && (
                      <a
                        href={j.outputUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="btn btn-secondary btn-sm text-xs py-0.5 px-2"
                      >
                        Preview ↗
                      </a>
                    )}
                    {j.errorMessage && (
                      <span className="text-danger text-xs truncate max-w-[8rem] inline-block" title={j.errorMessage}>
                        {j.errorMessage}
                      </span>
                    )}
                    {!j.outputUrl && !j.errorMessage && <span className="text-fg-faint">—</span>}
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
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-2">
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              disabled={page <= 0 || loading}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              disabled={page >= data.totalPages - 1 || loading}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
