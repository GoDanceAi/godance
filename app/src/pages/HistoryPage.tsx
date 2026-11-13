import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, type JobView, type MediaModality } from '../lib/api';
import { useAuth } from '../lib/auth';
import { costText, listJobs, useWatchedJob } from '../lib/jobs';
import { formatAge, formatDateTime } from '../lib/time';
import { JobThumb } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';

const PAGE_SIZE = 24;

const FILTERS: { value: MediaModality | null; label: string; param: string | null }[] = [
  { value: null, label: 'All', param: null },
  { value: 'IMAGE', label: 'Images', param: 'image' },
  { value: 'VIDEO', label: 'Videos', param: 'video' },
];

function filterFrom(param: string | null): MediaModality | null {
  return FILTERS.find((f) => f.param !== null && f.param === param)?.value ?? null;
}

/** Every generation, newest first, a page at a time. */
export default function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const modality = filterFrom(searchParams.get('type'));

  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // Which list a "Load more" answer belongs to: a filter change starts a new one.
  const listId = useRef(0);

  useEffect(() => {
    const id = ++listId.current;
    setJobs(null);
    setError(null);
    setMoreFailed(false);
    listJobs({ limit: PAGE_SIZE, modality: modality ?? undefined })
      .then((page) => {
        if (id !== listId.current) return;
        setJobs(page);
        setHasMore(page.length === PAGE_SIZE);
      })
      .catch((err: unknown) => {
        if (id !== listId.current) return;
        setError(err instanceof ApiError ? err.detail || err.message : 'Could not load your history.');
      });
  }, [modality]);

  const loadMore = async () => {
    const last = jobs?.[jobs.length - 1];
    if (!last) return;
    const id = listId.current;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listJobs({ limit: PAGE_SIZE, before: last.id, modality: modality ?? undefined });
      if (id !== listId.current) return;
      setJobs((cur) => {
        const seen = new Set(cur?.map((j) => j.id));
        return [...(cur ?? []), ...page.filter((j) => !seen.has(j.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch {
      if (id === listId.current) setMoreFailed(true);
    } finally {
      if (id === listId.current) setLoadingMore(false);
    }
  };

  const pick = (param: string | null) =>
    setSearchParams(param ? { type: param } : {}, { replace: true });

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-fg">History</h1>
        <div role="group" aria-label="Show" className="inline-flex rounded-md border border-border bg-surface p-0.5">
          {FILTERS.map((f) => (
            <button
              key={f.label}
              type="button"
              aria-pressed={modality === f.value}
              onClick={() => pick(f.param)}
              className={`rounded-sm px-3 py-1.5 text-[13px] font-semibold transition-colors ${
                modality === f.value ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>
      <p className="mt-2 text-sm text-fg-muted">
        Images and videos you generate, in the Studio or with an API key.
      </p>

      {error && <p role="alert" className="mt-6 text-sm text-danger">{error}</p>}
      {!error && jobs === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {jobs?.length === 0 && <EmptyHistory modality={modality} />}

      {jobs !== null && jobs.length > 0 && (
        <>
          <ul className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {jobs.map((job) => <JobCard key={job.id} initial={job} />)}
          </ul>
          {hasMore && (
            <div className="mt-8 flex flex-col items-center gap-2">
              <button type="button" onClick={() => void loadMore()} disabled={loadingMore} className="btn btn-secondary">
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
              {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more. Try again.</p>}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function EmptyHistory({ modality }: { modality: MediaModality | null }) {
  const what = modality === 'IMAGE' ? 'images' : modality === 'VIDEO' ? 'videos' : 'generations';
  return (
    <div className="mt-6 card py-12 text-center">
      <p className="font-semibold text-fg">No {what} yet.</p>
      <p className="mt-1 text-sm text-fg-muted">Make one in the Studio and it shows up here.</p>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {modality !== 'VIDEO' && <Link to="/create/image" className="btn btn-primary btn-sm">Create an image</Link>}
        {modality !== 'IMAGE' && (
          <Link to="/create/video" className={`btn btn-sm ${modality === 'VIDEO' ? 'btn-primary' : 'btn-secondary'}`}>
            Create a video
          </Link>
        )}
      </div>
    </div>
  );
}

/** One generation. Polls while it runs, so the card turns to its result on its own. */
function JobCard({ initial }: { initial: JobView }) {
  const { refresh } = useAuth();
  const job = useWatchedJob(initial, () => void refresh());
  const title = job.prompt || (job.purged ? 'Prompt deleted with the files' : 'Untitled generation');

  return (
    <li className="card relative flex flex-col overflow-hidden p-0 card-hover">
      <JobThumb job={job} />
      <div className="flex flex-1 flex-col gap-2 p-3">
        <Link
          to={`/history/${job.id}`}
          className={`line-clamp-2 text-sm after:absolute after:inset-0 ${job.prompt ? 'text-fg' : 'italic text-fg-subtle'}`}
        >
          {title}
        </Link>
        <p className="truncate font-mono text-[11px] text-fg-subtle">{job.model}</p>
        <div className="mt-auto flex items-center justify-between gap-2 text-xs text-fg-subtle">
          <span aria-live="polite"><JobStatusPill status={job.status} /></span>
          <span className="tabular-nums">{costText(job)}</span>
        </div>
        <time dateTime={job.createdAt} title={formatDateTime(job.createdAt)} className="text-xs text-fg-faint">
          {formatAge(job.createdAt)}
        </time>
      </div>
    </li>
  );
}
