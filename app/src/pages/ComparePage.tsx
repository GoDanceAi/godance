import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, type JobView } from '../lib/api';
import { COMPARE_MAX, COMPARE_MIN, compareIds, diffFrom, durationText } from '../lib/compare';
import { costText, fetchJob, outputItems } from '../lib/jobs';
import { formatDateTime } from '../lib/time';
import JobStatusPill from '../components/JobStatusPill';
import VariableValues from '../components/VariableValues';

/** One job of the comparison, as read: the job, that it isn't the caller's to read, or that reading failed. */
type Slot = { id: string; job: JobView } | { id: string; missing: true } | { id: string; failed: true };

const PARAM_LABELS: { key: 'size' | 'n' | 'seconds' | 'style' | 'quality'; label: string; unit?: string }[] = [
  { key: 'size', label: 'Size' },
  { key: 'seconds', label: 'Duration', unit: ' seconds' },
  { key: 'n', label: 'Count' },
  { key: 'style', label: 'Style' },
  { key: 'quality', label: 'Quality' },
];

/**
 * /compare?jobs=<id>,<id>… — two to four of the user's generations side by
 * side: what each made, with which model, settings and variable values, how
 * its prompt differs from the first one's, and what it cost.
 */
export default function ComparePage() {
  const [searchParams] = useSearchParams();
  const ids = compareIds(searchParams.get('jobs'));
  const key = ids.join(',');
  const [slots, setSlots] = useState<Slot[] | null>(null);

  useEffect(() => {
    const wanted = key === '' ? [] : key.split(',');
    if (wanted.length < COMPARE_MIN) return;
    let cancelled = false;
    setSlots(null);
    // Each job is read on its own: one that can't be read leaves the others in place.
    void Promise.all(wanted.map((id) => fetchJob(id).then(
      (job): Slot => ({ id, job }),
      (err: unknown): Slot => (err instanceof ApiError && err.status === 404 ? { id, missing: true } : { id, failed: true }),
    ))).then((read) => { if (!cancelled) setSlots(read); });
    return () => { cancelled = true; };
  }, [key]);

  // The first job that can be read is what the others' prompts are compared with.
  const first = slots?.find((slot): slot is { id: string; job: JobView } => 'job' in slot && Boolean(slot.job.prompt));

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <Link to="/history" className="text-sm text-fg-muted hover:text-fg">← History</Link>
      <h1 className="mt-4 text-2xl font-bold tracking-tight text-fg">Compare</h1>

      {ids.length < COMPARE_MIN ? (
        <div className="mt-6 card py-10 text-center">
          <p className="font-semibold text-fg">Pick at least two generations to compare.</p>
          <p className="mt-1 text-sm text-fg-muted">
            In History, choose Select, tick two to four generations, then Compare. A recipe's page compares its attempts.
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            <Link to="/history" className="btn btn-primary btn-sm">Open History</Link>
            <Link to="/recipes" className="btn btn-secondary btn-sm">Open Recipes</Link>
          </div>
        </div>
      ) : (
        <>
          <p className="mt-2 text-sm text-fg-muted">
            {ids.length} generations side by side. In each prompt after the first, what differs from the first is marked.
          </p>
          {slots === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
          {slots !== null && (
            // Side by side from the first breakpoint that fits two; below it, one after another.
            <ol className={`mt-6 grid grid-cols-1 gap-4 ${
              slots.length >= COMPARE_MAX ? 'sm:grid-cols-2 xl:grid-cols-4' : slots.length === 3 ? 'sm:grid-cols-2 lg:grid-cols-3' : 'sm:grid-cols-2'}`}>
              {slots.map((slot, i) => (
                <li key={slot.id} className="card min-w-0 p-4">
                  <Column slot={slot} position={i + 1} base={first && first.id !== slot.id ? first.job.prompt ?? null : null} />
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </div>
  );
}

function Column({ slot, position, base }: { slot: Slot; position: number; base: string | null }) {
  if (!('job' in slot)) {
    return (
      <>
        <h2 className="text-sm font-semibold text-fg">Generation {position}</h2>
        <p className="mt-2 text-sm text-fg-muted">
          {'missing' in slot
            ? 'Not found. It may belong to another account, or the link is incomplete.'
            : "Couldn't be loaded. Reload the page to try again."}
        </p>
      </>
    );
  }
  const job = slot.job;
  const params = job.params ?? {};
  const shownParams = PARAM_LABELS.filter((p) => params[p.key] !== undefined && params[p.key] !== null && params[p.key] !== '');
  const took = durationText(job);

  return (
    <article aria-label={`Generation ${position}`}>
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-fg">
          <Link to={`/history/${job.id}`} className="hover:text-brand-600 hover:underline">Generation {position}</Link>
        </h2>
        <JobStatusPill status={job.status} />
      </header>

      <Outputs job={job} position={position} />

      <dl className="mt-4 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm">
        <dt className="text-fg-subtle">Model</dt>
        <dd className="break-all font-mono text-[13px] text-fg">{job.model}</dd>
        {shownParams.map((p) => (
          <div key={p.key} className="contents">
            <dt className="text-fg-subtle">{p.label}</dt>
            <dd className="text-fg">{`${params[p.key]}${p.unit ?? ''}`}</dd>
          </div>
        ))}
        <dt className="text-fg-subtle">Cost</dt>
        <dd className="font-semibold tabular-nums text-fg">{costText(job)}</dd>
        <dt className="text-fg-subtle">Time</dt>
        <dd className="text-fg">{took ?? '—'}</dd>
        <dt className="text-fg-subtle">Made</dt>
        <dd className="text-fg">{formatDateTime(job.createdAt)}</dd>
        {job.recipe && (
          <>
            <dt className="text-fg-subtle">Recipe</dt>
            <dd className="text-fg">
              <Link to={`/recipes/${job.recipe.id}`} className="text-brand-600 hover:underline">Version {job.recipe.version}</Link>
            </dd>
          </>
        )}
      </dl>

      {job.recipe && (
        <section className="mt-4">
          <h3 className="text-xs font-semibold text-fg">Variables</h3>
          <VariableValues recipe={job.recipe} className="mt-1.5" />
        </section>
      )}

      <section className="mt-4">
        <h3 className="text-xs font-semibold text-fg">Prompt</h3>
        {job.purged ? (
          <p className="mt-1.5 text-sm text-fg-muted">The prompt was deleted with the files. The cost and date are kept.</p>
        ) : job.prompt ? (
          <Prompt text={job.prompt} base={base} />
        ) : (
          <p className="mt-1.5 text-sm text-fg-muted">No prompt was recorded.</p>
        )}
      </section>
    </article>
  );
}

/** What a job made, small enough to sit beside the others; each file opens in full. */
function Outputs({ job, position }: { job: JobView; position: number }) {
  if (job.purged) {
    return (
      <p className="mt-3 rounded-md border border-dashed border-border-strong p-4 text-center text-sm text-fg-muted">
        The files were deleted at the end of the retention period.
      </p>
    );
  }
  const items = outputItems(job);
  if (items.length === 0) {
    return (
      <p className="mt-3 rounded-md border border-dashed border-border-strong p-4 text-center text-sm text-fg-muted">
        {job.status === 'DONE' ? 'No files came back.' : job.status === 'FAILED' ? 'Failed: nothing was made.'
          : job.status === 'CANCELLED' ? 'Cancelled: nothing was made.' : 'Still in progress.'}
      </p>
    );
  }
  return (
    <ul className={`mt-3 grid gap-2 ${items.length > 1 ? 'grid-cols-2' : ''}`}>
      {items.map((item, i) => {
        const which = items.length > 1 ? `, ${item.kind} ${i + 1} of ${items.length}` : '';
        const name = `Generation ${position}${which}`;
        return (
          <li key={item.key}>
            {item.kind === 'video' ? (
              <video src={item.url} controls playsInline preload="metadata" aria-label={name} className="w-full rounded-md bg-black" />
            ) : (
              <a href={item.url} target="_blank" rel="noopener noreferrer" aria-label={`Open ${name} in full`}>
                <img src={item.url} alt={name} loading="lazy" className="w-full rounded-md bg-bg-muted" />
              </a>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** A prompt, with what the first generation's prompt lacks marked. `base` is null for the first one itself. */
function Prompt({ text, base }: { text: string; base: string | null }) {
  const parts = base === null ? null : diffFrom(base, text);
  const same = parts !== null && parts.every((part) => !part.changed) && base === text;
  return (
    <>
      <p className="mt-1.5 whitespace-pre-wrap break-words embedded-surface p-3 text-sm text-fg">
        {parts === null
          ? text
          : parts.map((part, i) => (part.changed
            ? <mark key={i} className="rounded-sm border-b border-warning bg-[var(--color-warning-bg)] text-fg">{part.text}</mark>
            : <span key={i}>{part.text}</span>))}
      </p>
      {same && <p className="mt-1 text-xs text-fg-subtle">The same prompt as the first.</p>}
      {base !== null && parts === null && (
        <p className="mt-1 text-xs text-fg-subtle">Too long to mark the differences.</p>
      )}
    </>
  );
}
