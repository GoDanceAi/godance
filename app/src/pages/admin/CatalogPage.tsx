import { Fragment, useCallback, useEffect, useState } from 'react';
import {
  apiFetch,
  ApiError,
  type AdminCatalogModel,
  type AdminCatalogListResponse,
  type AdminPriceHistoryEntry,
  type AdminPriceHistoryResponse,
} from '../../lib/api';

// Model catalog management: enable/disable, per-model markup override,
// price history on expand. Prices are microcents/1k tokens (text) or
// flat microcents (image/video) — rendered as dollars.

function money(microcents: number, per: 'per1M' | 'flat'): string {
  if (microcents === 0) return '—';
  const dollars = per === 'per1M' ? microcents / 1000 : microcents / 1_000_000;
  return `$${dollars.toLocaleString('en-US', { maximumFractionDigits: 2 })}`;
}

function priceSummary(m: AdminCatalogModel): string {
  if (m.perImageMicrocents > 0) return `${money(m.perImageMicrocents, 'flat')}/image`;
  if (m.perSecondMicrocents > 0) return `${money(m.perSecondMicrocents, 'flat')}/sec`;
  return `${money(m.inputMicrocentsPer1k, 'per1M')} in · ${money(m.outputMicrocentsPer1k, 'per1M')} out /1M`;
}

export default function CatalogPage() {
  const [models, setModels] = useState<AdminCatalogModel[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [history, setHistory] = useState<Record<number, AdminPriceHistoryEntry[] | 'loading'>>({});
  const [markupDraft, setMarkupDraft] = useState<Record<number, string>>({});

  const load = useCallback(async () => {
    try {
      const resp = await apiFetch<AdminCatalogListResponse>('/v1/admin/pricing/models');
      setModels(resp.models);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Failed to load catalog');
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const patch = async (m: AdminCatalogModel, body: { enabled?: boolean; markupPercent?: number }) => {
    setBusyId(m.id);
    setError(null);
    try {
      const updated = await apiFetch<AdminCatalogModel>(`/v1/admin/pricing/models/${m.id}`, {
        method: 'PATCH',
        json: body,
      });
      setModels((prev) => prev?.map((x) => (x.id === m.id ? updated : x)) ?? null);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Update failed');
    } finally {
      setBusyId(null);
    }
  };

  const toggleExpand = async (m: AdminCatalogModel) => {
    if (expandedId === m.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(m.id);
    if (!history[m.id]) {
      setHistory((h) => ({ ...h, [m.id]: 'loading' }));
      try {
        const resp = await apiFetch<AdminPriceHistoryResponse>(`/v1/admin/pricing/models/${m.id}/history`);
        setHistory((h) => ({ ...h, [m.id]: resp.history }));
      } catch {
        setHistory((h) => ({ ...h, [m.id]: [] }));
      }
    }
  };

  const saveMarkup = async (m: AdminCatalogModel) => {
    const raw = markupDraft[m.id];
    const value = Number(raw);
    if (raw === undefined || raw === '' || Number.isNaN(value) || value < 0) {
      setError('Markup must be a non-negative number');
      return;
    }
    await patch(m, { markupPercent: value });
    setMarkupDraft((d) => {
      const next = { ...d };
      delete next[m.id];
      return next;
    });
  };

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Full model catalog, including disabled rows. Toggling or changing markup takes
        effect immediately — no restart.
      </p>

      {error && <p className="mt-3 text-sm text-danger">{error}</p>}

      <div className="mt-4 card p-0 overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-surface-alt border-b border-border">
            <tr className="text-left text-fg-subtle">
              <th className="px-4 py-2.5 font-semibold">Model</th>
              <th className="px-4 py-2.5 font-semibold">Provider</th>
              <th className="px-4 py-2.5 font-semibold">Modality</th>
              <th className="px-4 py-2.5 font-semibold">Price</th>
              <th className="px-4 py-2.5 font-semibold">Markup</th>
              <th className="px-4 py-2.5 font-semibold">Status</th>
              <th className="px-4 py-2.5"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {models === null && !error && (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-fg-faint">Loading…</td></tr>
            )}
            {models?.length === 0 && (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-fg-faint">Catalog is empty.</td></tr>
            )}
            {models?.map((m) => (
              <Fragment key={m.id}>
                <tr className={m.enabled ? '' : 'opacity-60'}>
                  <td className="px-4 py-3 font-mono text-xs font-medium text-fg">
                    <button
                      type="button"
                      onClick={() => void toggleExpand(m)}
                      className="hover:text-brand-600 cursor-pointer"
                      title="Show price history"
                    >
                      {expandedId === m.id ? '▾' : '▸'} {m.modelName}
                    </button>
                  </td>
                  <td className="px-4 py-3 text-fg-muted text-xs">{m.provider ?? '—'}</td>
                  <td className="px-4 py-3 text-fg-muted text-xs">{m.modality ?? '—'}</td>
                  <td className="px-4 py-3 text-fg-muted tabular-nums text-xs">{priceSummary(m)}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1.5">
                      <input
                        type="number"
                        min={0}
                        step={0.5}
                        className="input w-20 !py-1 text-xs tabular-nums"
                        value={markupDraft[m.id] ?? String(m.effectiveMarkupPercent)}
                        onChange={(e) => setMarkupDraft((d) => ({ ...d, [m.id]: e.target.value }))}
                      />
                      <span className="text-xs text-fg-subtle">%</span>
                      {markupDraft[m.id] !== undefined &&
                        Number(markupDraft[m.id]) !== m.effectiveMarkupPercent && (
                        <button
                          className="btn btn-sm btn-primary"
                          disabled={busyId === m.id}
                          onClick={() => void saveMarkup(m)}
                        >
                          Save
                        </button>
                      )}
                      {m.markupOverride === null && (
                        <span className="text-[10px] text-fg-faint" title="Tier default — no per-model override">
                          default
                        </span>
                      )}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`pill ${m.enabled ? 'pill-emerald' : 'pill-rose'}`}>
                      {m.enabled ? 'ACTIVE' : m.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      className={`btn btn-sm ${m.enabled ? 'btn-danger' : 'btn-secondary'}`}
                      disabled={busyId === m.id}
                      onClick={() => void patch(m, { enabled: !m.enabled })}
                    >
                      {m.enabled ? 'Disable' : 'Enable'}
                    </button>
                  </td>
                </tr>
                {expandedId === m.id && (
                  <tr>
                    <td colSpan={7} className="px-6 pb-4 pt-1">
                      <div className="embedded-surface p-3">
                        <p className="text-xs font-semibold text-fg-subtle uppercase tracking-wider">
                          Price history
                        </p>
                        {history[m.id] === 'loading' && (
                          <p className="mt-2 text-xs text-fg-faint">Loading…</p>
                        )}
                        {Array.isArray(history[m.id]) && (history[m.id] as AdminPriceHistoryEntry[]).length === 0 && (
                          <p className="mt-2 text-xs text-fg-faint">No observations recorded yet.</p>
                        )}
                        {Array.isArray(history[m.id]) && (history[m.id] as AdminPriceHistoryEntry[]).length > 0 && (
                          <table className="mt-2 w-full text-xs">
                            <thead>
                              <tr className="text-left text-fg-subtle">
                                <th className="py-1 pr-4 font-semibold">Observed</th>
                                <th className="py-1 pr-4 font-semibold">Field</th>
                                <th className="py-1 pr-4 font-semibold">Old → New</th>
                                <th className="py-1 font-semibold">Source</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                              {(history[m.id] as AdminPriceHistoryEntry[]).slice(0, 10).map((h) => (
                                <tr key={h.id}>
                                  <td className="py-1.5 pr-4 text-fg-muted tabular-nums">
                                    {new Date(h.observedAt).toLocaleDateString()}
                                  </td>
                                  <td className="py-1.5 pr-4 text-fg-muted">{h.field ?? '—'}</td>
                                  <td className="py-1.5 pr-4 text-fg tabular-nums">
                                    {h.oldValue ?? '—'} → {h.newValue ?? '—'}
                                    <span className="text-fg-faint"> µ¢</span>
                                  </td>
                                  <td className="py-1.5 text-fg-muted">{h.source ?? '—'}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
