import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  apiFetch,
  ApiError,
  type AdminFunnel,
  type AdminFunnelStages,
} from '../../lib/api';

// The funnel: of the people who signed up in a period, how many went on to
// run something in the Studio, to pay for a top-up, and to generate with an
// API key. Counts only; the API leaves out any group under five people.

const DAY_MS = 24 * 60 * 60 * 1000;

/** A UTC calendar day as yyyy-MM-dd, `daysAgo` days before today. */
function utcDay(daysAgo: number): string {
  return new Date(Date.now() - daysAgo * DAY_MS).toISOString().slice(0, 10);
}

/** "64 (53%)": a count, and its share of the group it is out of. */
function Share({ count, of }: { count: number; of: number }) {
  return (
    <>
      {count.toLocaleString('en-US')}
      {of > 0 && <span className="text-fg-subtle"> ({Math.round((count / of) * 100)}%)</span>}
    </>
  );
}

function sourceLabel(source: string): string {
  if (source === 'gallery') return 'Gallery recipe';
  if (source === 'referral') return 'Invited by a user';
  if (source === 'none') return 'No source recorded';
  if (source === 'utm:other') return 'Other campaigns';
  return source.startsWith('utm:') ? `Campaign: ${source.slice(4)}` : source;
}

export default function FunnelSection() {
  // The same default as the summary above: the last 30 days, in UTC.
  const [from, setFrom] = useState(() => utcDay(29));
  const [to, setTo] = useState(() => utcDay(0));
  const [loaded, setLoaded] = useState<{ key: string; funnel: AdminFunnel | null; error: string | null } | null>(null);
  const key = `${from}|${to}`;

  useEffect(() => {
    if (!from || !to) return;
    let current = true;
    apiFetch<AdminFunnel>(`/v1/admin/funnel?from=${from}&to=${to}`)
      .then((funnel) => { if (current) setLoaded({ key, funnel, error: null }); })
      .catch((err) => {
        if (!current) return;
        const error = err instanceof ApiError && !err.message.startsWith('HTTP ')
          ? err.message
          : "Couldn't load the funnel.";
        setLoaded({ key, funnel: null, error });
      });
    return () => { current = false; };
  }, [from, to, key]);

  const funnel = loaded?.key === key ? loaded.funnel : null;
  const error = loaded?.key === key ? loaded.error : null;

  return (
    <section aria-labelledby="funnel-heading" className="mt-10">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="funnel-heading" className="text-lg font-semibold text-fg">From sign-up to the API</h2>
          <p className="mt-1 text-sm text-fg-muted">
            People who signed up in the period, and how many went on to each step.
            Groups of fewer than 5 people are left out.
          </p>
        </div>
        <div className="flex items-end gap-3">
          <DateField id="funnel-from" label="From" value={from} max={to} onChange={setFrom} />
          <DateField id="funnel-to" label="To" value={to} min={from} onChange={setTo} />
        </div>
      </div>

      {error && <p role="alert" className="mt-4 text-sm text-danger">{error}</p>}
      {!error && !funnel && <p className="mt-4 text-sm text-fg-faint">Loading…</p>}

      {funnel && (
        <div className="mt-4 space-y-4">
          <FunnelTable
            title="By week of sign-up"
            first="Week"
            rows={funnel.cohorts.map((c) => ({
              key: c.week,
              label: c.week,
              stages: c,
              extra: c.median_days_to_api_job === null ? '—' : c.median_days_to_api_job.toLocaleString('en-US'),
            }))}
            extraHeading="Median days to API job"
            empty="No week in this period has 5 sign-ups or more."
          />
          <FunnelTable
            title="By what brought them"
            first="Source"
            rows={funnel.by_source.map((s) => ({ key: s.source, label: sourceLabel(s.source), stages: s }))}
            empty="No source in this period has 5 sign-ups or more."
          />

          <div className="card p-0 overflow-x-auto">
            <h3 className="px-4 pt-4 text-sm font-semibold text-fg">Top gallery recipes</h3>
            <table className="mt-2 w-full text-sm">
              <thead className="bg-surface-alt border-y border-border">
                <tr className="text-left text-fg-subtle">
                  <th scope="col" className="px-4 py-2 font-semibold">Recipe</th>
                  <th scope="col" className="px-4 py-2 font-semibold text-right">People</th>
                  <th scope="col" className="px-4 py-2 font-semibold text-right">Jobs</th>
                  <th scope="col" className="px-4 py-2 font-semibold text-right">With an API job</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {funnel.gallery_recipes.length === 0 && (
                  <tr><td colSpan={4} className="px-4 py-5 text-center text-fg-faint">
                    No recipe was run by 5 people or more in this period.
                  </td></tr>
                )}
                {funnel.gallery_recipes.map((r) => (
                  <tr key={r.slug}>
                    <th scope="row" className="px-4 py-2.5 text-left font-mono text-xs font-normal text-fg">{r.slug}</th>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg">{r.users.toLocaleString('en-US')}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{r.jobs.toLocaleString('en-US')}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">
                      <Share count={r.users_with_api_job} of={r.users} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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

interface Row {
  key: string;
  label: ReactNode;
  stages: AdminFunnelStages;
  extra?: string;
}

/** One row per group: sign-ups, then each later step with its share of the sign-ups. */
function FunnelTable({ title, first, rows, extraHeading, empty }: {
  title: string;
  first: string;
  rows: Row[];
  extraHeading?: string;
  empty: string;
}) {
  const columns = extraHeading ? 6 : 5;
  return (
    <div className="card p-0 overflow-x-auto">
      <h3 className="px-4 pt-4 text-sm font-semibold text-fg">{title}</h3>
      <table className="mt-2 w-full text-sm">
        <thead className="bg-surface-alt border-y border-border">
          <tr className="text-left text-fg-subtle">
            <th scope="col" className="px-4 py-2 font-semibold">{first}</th>
            <th scope="col" className="px-4 py-2 font-semibold text-right">Signed up</th>
            <th scope="col" className="px-4 py-2 font-semibold text-right">Studio job</th>
            <th scope="col" className="px-4 py-2 font-semibold text-right">Paid top-up</th>
            <th scope="col" className="px-4 py-2 font-semibold text-right">API job</th>
            {extraHeading && <th scope="col" className="px-4 py-2 font-semibold text-right">{extraHeading}</th>}
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.length === 0 && (
            <tr><td colSpan={columns} className="px-4 py-5 text-center text-fg-faint">{empty}</td></tr>
          )}
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row" className="px-4 py-2.5 text-left font-normal text-fg">{row.label}</th>
              <td className="px-4 py-2.5 text-right tabular-nums text-fg">
                {row.stages.signed_up.toLocaleString('en-US')}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">
                <Share count={row.stages.first_studio_job} of={row.stages.signed_up} />
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">
                <Share count={row.stages.first_paid_top_up} of={row.stages.signed_up} />
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">
                <Share count={row.stages.first_api_job} of={row.stages.signed_up} />
              </td>
              {extraHeading && <td className="px-4 py-2.5 text-right tabular-nums text-fg-muted">{row.extra}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
