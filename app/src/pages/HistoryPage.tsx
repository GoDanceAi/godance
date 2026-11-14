import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, type JobView, type MediaModality, type SavedJobs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { costText, fetchSavedJobs, listJobs, useWatchedJob } from '../lib/jobs';
import { formatAge, formatDateTime } from '../lib/time';
import { Icon } from '../components/Brand';
import { JobThumb } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';
import SaveJobButton from '../components/SaveJobButton';

const PAGE_SIZE = 24;

const FILTERS: { value: MediaModality | null; label: string; param: string | null }[] = [
  { value: null, label: 'All', param: null },
  { value: 'IMAGE', label: 'Images', param: 'image' },
  { value: 'VIDEO', label: 'Videos', param: 'video' },
];

function filterFrom(param: string | null): MediaModality | null {
  return FILTERS.find((f) => f.param !== null && f.param === param)?.value ?? null;
}

/**
 * Every generation, newest first, a page at a time. `?saved=1` is the saved
 * view: only the generations kept past their deletion date, with how many
 * there are against the account's limit.
 */
export default function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const modality = filterFrom(searchParams.get('type'));
  const savedOnly = searchParams.get('saved') === '1';

  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // Which list a "Load more" answer belongs to: a filter change starts a new one.
  const listId = useRef(0);
  // The saved view's count against the limit; null until read, or when it can't be.
  const [savedJobs, setSavedJobs] = useState<SavedJobs | null>(null);

  // An API from before saving ignores the filter and marks nothing saved: show nothing as saved.
  const shown = useCallback(
    (page: JobView[]) => (savedOnly ? page.filter((j) => j.saved === true) : page),
    [savedOnly],
  );

  useEffect(() => {
    const id = ++listId.current;
    setJobs(null);
    setError(null);
    setMoreFailed(false);
    listJobs({ limit: PAGE_SIZE, modality: modality ?? undefined, saved: savedOnly })
      .then((page) => {
        if (id !== listId.current) return;
        setJobs(shown(page));
        setHasMore(page.length === PAGE_SIZE);
      })
      .catch((err: unknown) => {
        if (id !== listId.current) return;
        setError(err instanceof ApiError ? err.detail || err.message : 'Could not load your history.');
      });
  }, [modality, savedOnly, shown]);

  const readSavedJobs = useCallback(() => {
    fetchSavedJobs().then(setSavedJobs, () => setSavedJobs(null));
  }, []);
  useEffect(() => {
    setSavedJobs(null);
    if (savedOnly) readSavedJobs();
  }, [savedOnly, readSavedJobs]);

  const loadMore = async () => {
    const last = jobs?.[jobs.length - 1];
    if (!last) return;
    const id = listId.current;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listJobs({
        limit: PAGE_SIZE, before: last.id, modality: modality ?? undefined, saved: savedOnly,
      });
      if (id !== listId.current) return;
      setJobs((cur) => {
        const seen = new Set(cur?.map((j) => j.id));
        return [...(cur ?? []), ...shown(page).filter((j) => !seen.has(j.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch {
      if (id === listId.current) setMoreFailed(true);
    } finally {
      if (id === listId.current) setLoadingMore(false);
    }
  };

  const show = (type: string | null, saved: boolean) =>
    setSearchParams({ ...(type ? { type } : {}), ...(saved ? { saved: '1' } : {}) }, { replace: true });
  const typeParam = FILTERS.find((f) => f.value === modality)?.param ?? null;

  // A card was saved or un-saved. In the saved view an un-saved one leaves the list.
  const onSavedChange = (job: JobView) => {
    setJobs((cur) => cur && (savedOnly && !job.saved
      ? cur.filter((j) => j.id !== job.id)
      : cur.map((j) => (j.id === job.id ? job : j))));
    if (savedOnly) readSavedJobs();
  };
  const atLimit = savedJobs !== null && savedJobs.count >= savedJobs.limit;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-fg">History</h1>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            aria-pressed={savedOnly}
            onClick={() => show(typeParam, !savedOnly)}
            className={`inline-flex items-center gap-1.5 rounded-md border px-3 py-2 text-[13px] font-semibold transition-colors ${
              savedOnly ? 'border-brand-600 bg-brand-600 text-white' : 'border-border bg-surface text-fg-muted hover:text-fg'}`}
          >
            <Icon name={savedOnly ? 'bookmarkFilled' : 'bookmark'} size={14} />
            Saved only
          </button>
          <div role="group" aria-label="Show" className="inline-flex rounded-md border border-border bg-surface p-0.5">
            {FILTERS.map((f) => (
              <button
                key={f.label}
                type="button"
                aria-pressed={modality === f.value}
                onClick={() => show(f.param, savedOnly)}
                className={`rounded-sm px-3 py-1.5 text-[13px] font-semibold transition-colors ${
                  modality === f.value ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
      </div>
      {savedOnly ? (
        <p className="mt-2 text-sm text-fg-muted">
          Saved generations are kept until you un-save them or close your account.
          {savedJobs && (
            <>
              {' '}
              <span aria-live="polite">
                <strong className="font-semibold tabular-nums text-fg">
                  {savedJobs.count} of {savedJobs.limit}
                </strong>{' '}
                saved.
                {atLimit && ' That is the most an account can keep: un-save one to save another.'}
              </span>
            </>
          )}
        </p>
      ) : (
        <p className="mt-2 text-sm text-fg-muted">
          Images and videos you generate, in the Studio or with an API key.
        </p>
      )}

      {error && <p role="alert" className="mt-6 text-sm text-danger">{error}</p>}
      {!error && jobs === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {jobs?.length === 0 && (savedOnly ? <NothingSaved modality={modality} /> : <EmptyHistory modality={modality} />)}

      {jobs !== null && jobs.length > 0 && (
        <>
          <ul className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {jobs.map((job) => <JobCard key={job.id} initial={job} onSavedChange={onSavedChange} />)}
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

function NothingSaved({ modality }: { modality: MediaModality | null }) {
  const what = modality === 'IMAGE' ? 'images' : modality === 'VIDEO' ? 'videos' : 'generations';
  return (
    <div className="mt-6 card py-12 text-center">
      <p className="font-semibold text-fg">No saved {what} yet.</p>
      <p className="mt-1 text-sm text-fg-muted">
        Save a finished generation to keep it past its deletion date.
      </p>
      <div className="mt-5 flex justify-center">
        <Link to="/history" className="btn btn-secondary btn-sm">Show everything</Link>
      </div>
    </div>
  );
}

/**
 * One generation. Polls while it runs, so the card turns to its result on
 * its own. A finished one can be saved from here.
 */
function JobCard({ initial, onSavedChange }: { initial: JobView; onSavedChange: (job: JobView) => void }) {
  const { refresh } = useAuth();
  const watched = useWatchedJob(initial, () => void refresh());
  // What a save or un-save made here changed. Only a finished job has the
  // control, so nothing newer arrives from the polling to replace it.
  const [savedState, setSavedState] = useState<Pick<JobView, 'saved' | 'mediaExpiresAt'> | null>(null);
  const job = savedState ? { ...watched, ...savedState } : watched;
  const title = job.prompt || (job.purged ? 'Prompt deleted with the files' : 'Untitled generation');
  const changed = (next: JobView) => {
    setSavedState({ saved: next.saved, mediaExpiresAt: next.mediaExpiresAt });
    onSavedChange(next);
  };

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
        <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
          <time dateTime={job.createdAt} title={formatDateTime(job.createdAt)} className="text-xs text-fg-faint">
            {formatAge(job.createdAt)}
          </time>
          {/* Above the card's link, which covers the whole card. */}
          <SaveJobButton job={job} onChange={changed} className="btn btn-ghost btn-sm relative z-10 -mr-2 px-2 py-1" />
        </div>
      </div>
    </li>
  );
}
