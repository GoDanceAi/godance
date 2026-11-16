import { useEffect, useState } from 'react';
import { apiFetch, ApiError, type AdminRoutingMargins } from '../../lib/api';
import { useAdmin } from '../../lib/admin';
import { formatCents } from '../../lib/auth';

// Round 14 (round 4's Phase 2): what routed requests earned. For each routing
// policy and the rule of it that chose the model, the requests it served,
// what their callers paid, what the served models cost, and the spread.
// Finance and admins only, as the route is; for anyone else the section is
// not shown and nothing is asked.

const DAY_MS = 24 * 60 * 60 * 1000;

/** A UTC calendar day as yyyy-MM-dd, `daysAgo` days before today. */
function utcDay(daysAgo: number): string {
  return new Date(Date.now() - daysAgo * DAY_MS).toISOString().slice(0, 10);
}

/** A user's own policy is stored as usr_<user id>:<name>; the name is what its owner calls it. */
function policyLabel(policy: string): { name: string; owner: string | null } {
  const own = /^usr_([0-9a-f-]{36}):(.+)$/.exec(policy);
  return own ? { name: own[2], owner: own[1] } : { name: policy, owner: null };
}

export default function RoutingMarginsSection() {
  const { role } = useAdmin();
  if (role !== 'FINANCE' && role !== 'ADMIN') return null;
  return <Margins />;
}

function Margins() {
  // The same default as the summary above: the last 30 days, in UTC.
  const [from, setFrom] = useState(() => utcDay(29));
  const [to, setTo] = useState(() => utcDay(0));
  const [loaded, setLoaded] = useState<{ key: string; margins: AdminRoutingMargins | null; error: string | null } | null>(null);
  const key = `${from}|${to}`;

  useEffect(() => {
    if (!from || !to) return;
    let current = true;
    apiFetch<AdminRoutingMargins>(`/v1/admin/routing/margins?from=${from}&to=${to}`)
      .then((margins) => { if (current) setLoaded({ key, margins, error: null }); })
      .catch((err) => {
        if (!current) return;
        const error = err instanceof ApiError && !err.message.startsWith('HTTP ')
          ? err.message
          : "Couldn't load the routing margins.";
        setLoaded({ key, margins: null, error });
      });
    return () => { current = false; };
  }, [from, to, key]);

  const margins = loaded?.key === key ? loaded.margins : null;
  const error = loaded?.key === key ? loaded.error : null;

  return (
    <section aria-labelledby="routing-margins-heading" className="mt-10">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="routing-margins-heading" className="text-lg font-semibold text-fg">Routing margins</h2>
          <p className="mt-1 text-sm text-fg-muted">
            Requests to a routing policy, by the rule that chose the model: what callers paid,
            what the served models cost, and the spread. Failed attempts cost nothing.
          </p>
        </div>
        <div className="flex items-end gap-3">
          <DateField id="margins-from" label="From" value={from} max={to} onChange={setFrom} />
          <DateField id="margins-to" label="To" value={to} min={from} onChange={setTo} />
        </div>
      </div>

      {error && <p role="alert" className="mt-4 text-sm text-danger">{error}</p>}
      {!error && !margins && <p className="mt-4 text-sm text-fg-faint">Loading…</p>}

      {margins && (
        <div className="mt-4 card p-0 overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Routing margins by policy and rule</caption>
            <thead className="bg-surface-alt border-b border-border">
              <tr className="text-left text-fg-subtle">
                <th scope="col" className="px-4 py-2 font-semibold">Policy</th>
                <th scope="col" className="px-4 py-2 font-semibold">Rule</th>
                <th scope="col" className="px-4 py-2 font-semibold text-right">Requests</th>
                <th scope="col" className="px-4 py-2 font-semibold text-right">Paid</th>
                <th scope="col" className="px-4 py-2 font-semibold text-right">Cost</th>
                <th scope="col" className="px-4 py-2 font-semibold text-right">Spread</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {margins.rows.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-5 text-center text-fg-faint">
                  No request in this period went through a routing policy.
                </td></tr>
              )}
              {margins.rows.map((r) => {
                const label = policyLabel(r.policy);
                return (
                  <tr key={`${r.policy}|${r.rule}`}>
                    <th scope="row" className="px-4 py-2.5 text-left font-mono text-xs font-normal text-fg">
                      {label.name}
                      {label.owner && <span className="block font-sans text-[11px] text-fg-subtle">a user's policy</span>}
                    </th>
                    <td className="px-4 py-2.5 font-mono text-xs text-fg-muted">{r.rule}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg">{r.requests.toLocaleString('en-US')}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg">{formatCents(r.paid_cents)}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{formatCents(r.cost_cents)}</td>
                    <td className={`px-4 py-2.5 text-right tabular-nums ${r.spread_cents < 0 ? 'text-danger' : 'text-fg'}`}>
                      {formatCents(r.spread_cents)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
            {margins.rows.length > 0 && (
              <tfoot className="border-t border-border-strong">
                <tr>
                  <th scope="row" colSpan={2} className="px-4 py-2.5 text-left font-semibold text-fg">Total</th>
                  <td className="px-4 py-2.5 text-right font-semibold tabular-nums text-fg">
                    {margins.total.requests.toLocaleString('en-US')}
                  </td>
                  <td className="px-4 py-2.5 text-right font-semibold tabular-nums text-fg">{formatCents(margins.total.paid_cents)}</td>
                  <td className="px-4 py-2.5 text-right font-semibold tabular-nums text-fg-muted">{formatCents(margins.total.cost_cents)}</td>
                  <td className={`px-4 py-2.5 text-right font-semibold tabular-nums ${margins.total.spread_cents < 0 ? 'text-danger' : 'text-fg'}`}>
                    {formatCents(margins.total.spread_cents)}
                  </td>
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
    </section>
  );
}

function DateField({ id, label, value, min, max, onChange }: {
  id: string;
  label: string;
  value: string;
  min?: string;
  max?: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-xs font-semibold text-fg-subtle">{label}</label>
      <input
        id={id}
        type="date"
        value={value}
        min={min}
        max={max}
        onChange={(e) => { if (e.target.value) onChange(e.target.value); }}
        className="input mt-1 text-sm"
      />
    </div>
  );
}
