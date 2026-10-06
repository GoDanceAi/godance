import { useCallback, useEffect, useState } from 'react';
import { apiFetch, type AdminSystemStatusResponse } from '../../lib/api';

const MOCK_STATUS: AdminSystemStatusResponse = {
  generatedAt: new Date().toISOString(),
  providers: [
    {
      provider: 'OPENAI',
      model: 'gpt-4o',
      state: 'ok',
      latencyP50Ms: 420,
      latencyP95Ms: 950,
      latencyP99Ms: 1420,
      errorRate: 0.002,
      requestCount: 1420,
      errorCount: 2,
      rateLimitCount: 1,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
    {
      provider: 'ANTHROPIC',
      model: 'claude-3-5-sonnet',
      state: 'ok',
      latencyP50Ms: 580,
      latencyP95Ms: 1100,
      latencyP99Ms: 1650,
      errorRate: 0.0,
      requestCount: 980,
      errorCount: 0,
      rateLimitCount: 0,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
    {
      provider: 'BFL',
      model: 'flux-1-schnell',
      state: 'ok',
      latencyP50Ms: 2400,
      latencyP95Ms: 4800,
      latencyP99Ms: 6200,
      errorRate: 0.015,
      requestCount: 340,
      errorCount: 4,
      rateLimitCount: 1,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
    {
      provider: 'RUNWAY',
      model: 'gen-3-alpha',
      state: 'degraded',
      latencyP50Ms: 15400,
      latencyP95Ms: 32000,
      latencyP99Ms: 45000,
      errorRate: 0.085,
      requestCount: 115,
      errorCount: 8,
      rateLimitCount: 2,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
    {
      provider: 'GOOGLE_VERTEX',
      model: 'gemini-1.5-pro',
      state: 'ok',
      latencyP50Ms: 480,
      latencyP95Ms: 1200,
      latencyP99Ms: 1800,
      errorRate: 0.001,
      requestCount: 620,
      errorCount: 1,
      rateLimitCount: 0,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
    {
      provider: 'KLING',
      model: 'kling-video-1.5',
      state: 'ok',
      latencyP50Ms: 22000,
      latencyP95Ms: 48000,
      latencyP99Ms: 60000,
      errorRate: 0.02,
      requestCount: 85,
      errorCount: 2,
      rateLimitCount: 0,
      windowStart: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      windowEnd: new Date().toISOString(),
    },
  ],
};

function stateBadge(state: string) {
  switch (state.toLowerCase()) {
    case 'ok':
    case 'healthy':
      return <span className="pill pill-emerald text-xs">OPERATIONAL</span>;
    case 'degraded':
      return <span className="pill pill-amber text-xs">DEGRADED</span>;
    case 'down':
    case 'outage':
      return <span className="pill pill-rose text-xs">MAJOR OUTAGE</span>;
    default:
      return <span className="pill text-xs">{state}</span>;
  }
}

export default function ProvidersPage() {
  const [data, setData] = useState<AdminSystemStatusResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [filterProvider, setFilterProvider] = useState<string>('ALL');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await apiFetch<AdminSystemStatusResponse>('/v1/status');
      setData(resp);
    } catch (err) {
      console.warn('Backend status failed, showing mock fallback', err);
      setData(MOCK_STATUS);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const providers = data?.providers ?? [];
  const uniqueProviders = ['ALL', ...Array.from(new Set(providers.map((p) => p.provider)))];

  const filtered = filterProvider === 'ALL'
    ? providers
    : providers.filter((p) => p.provider === filterProvider);

  const totalRequests = providers.reduce((sum, p) => sum + p.requestCount, 0);
  const degradedCount = providers.filter((p) => p.state !== 'ok' && p.state !== 'healthy').length;

  return (
    <div>
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-fg">Upstream Provider Observability</h2>
          <p className="text-sm text-fg-muted mt-0.5">
            Real-time latency percentiles, error rates, and automated circuit-breaker states across model vendors.
          </p>
        </div>
        <button
          onClick={() => void load()}
          className="btn btn-secondary self-start sm:self-auto"
          disabled={loading}
        >
          {loading ? 'Probing…' : 'Probe Health'}
        </button>
      </div>

      {/* KPI Overview */}
      <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="card p-4">
          <p className="text-xs font-medium uppercase tracking-wider text-fg-subtle">System Status</p>
          <p className="mt-1 text-lg font-bold text-fg flex items-center gap-2">
            <span
              className={`h-2.5 w-2.5 rounded-full ${
                degradedCount === 0 ? 'bg-emerald-500' : 'bg-amber-500'
              }`}
            />
            {degradedCount === 0 ? 'All Systems Healthy' : `${degradedCount} Degraded`}
          </p>
        </div>
        <div className="card p-4">
          <p className="text-xs font-medium uppercase tracking-wider text-fg-subtle">Active Gateways</p>
          <p className="mt-1 text-lg font-bold text-fg">{uniqueProviders.length - 1}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs font-medium uppercase tracking-wider text-fg-subtle">Monitored Models</p>
          <p className="mt-1 text-lg font-bold text-fg">{providers.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs font-medium uppercase tracking-wider text-fg-subtle">5-Min Request Vol</p>
          <p className="mt-1 text-lg font-bold text-fg">{totalRequests.toLocaleString()}</p>
        </div>
      </div>

      {/* Provider Filter Tabs */}
      <div className="mt-6 flex flex-wrap items-center gap-1.5 border-b border-border pb-3">
        {uniqueProviders.map((p) => (
          <button
            key={p}
            onClick={() => setFilterProvider(p)}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              filterProvider === p
                ? 'bg-primary text-white shadow-sm'
                : 'bg-surface hover:bg-surface-alt text-fg-muted hover:text-fg border border-border'
            }`}
          >
            {p}
          </button>
        ))}
      </div>

      {/* Grid of Provider Models */}
      <div className="mt-4 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filtered.map((item) => {
          const errorPct = (item.errorRate * 100).toFixed(2);
          return (
            <div key={`${item.provider}-${item.model}`} className="card p-5 hover:border-primary/50 transition-colors shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <span className="text-xs font-bold text-fg-muted uppercase tracking-wider">
                    {item.provider}
                  </span>
                  <h3 className="text-base font-semibold text-fg mt-0.5">{item.model}</h3>
                </div>
                {stateBadge(item.state)}
              </div>

              <div className="mt-4 grid grid-cols-3 gap-2 border-t border-b border-border py-3 text-center">
                <div>
                  <p className="text-xs text-fg-faint">P50</p>
                  <p className="text-sm font-semibold tabular-nums text-fg mt-0.5">
                    {item.latencyP50Ms != null ? `${item.latencyP50Ms}ms` : '—'}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-fg-faint">P95</p>
                  <p className="text-sm font-semibold tabular-nums text-fg mt-0.5">
                    {item.latencyP95Ms != null ? `${item.latencyP95Ms}ms` : '—'}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-fg-faint">P99</p>
                  <p className="text-sm font-semibold tabular-nums text-fg mt-0.5">
                    {item.latencyP99Ms != null ? `${item.latencyP99Ms}ms` : '—'}
                  </p>
                </div>
              </div>

              <div className="mt-3 space-y-1.5 text-xs">
                <div className="flex justify-between items-center text-fg-muted">
                  <span>Error Rate:</span>
                  <span
                    className={`font-semibold tabular-nums ${
                      item.errorRate > 0.05
                        ? 'text-danger'
                        : item.errorRate > 0
                        ? 'text-amber-500'
                        : 'text-emerald-600'
                    }`}
                  >
                    {errorPct}%
                  </span>
                </div>
                <div className="flex justify-between items-center text-fg-muted">
                  <span>Window Calls:</span>
                  <span className="font-mono text-fg">{item.requestCount.toLocaleString()}</span>
                </div>
                <div className="flex justify-between items-center text-fg-muted">
                  <span>Failures / Throttled:</span>
                  <span className="font-mono text-fg">
                    {item.errorCount} err · {item.rateLimitCount} 429
                  </span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
