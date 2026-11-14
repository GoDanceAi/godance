import { useEffect, useRef, useState } from 'react';
import { apiFetch, ApiError, type JobStatus, type JobView, type MediaModality, type SavedJobs } from './api';
import { formatCents } from './auth';
import { isUuid } from './ids';

// Generation jobs: reading them, watching one until it ends, and what to show.

export const STATUS_LABEL: Record<JobStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  DONE: 'Done',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

export function isTerminal(status: JobStatus): boolean {
  return status === 'DONE' || status === 'FAILED' || status === 'CANCELLED';
}

/** The Studio's two kinds. A job of any other modality is not shown as media. */
export function mediaModality(job: JobView): MediaModality | null {
  return job.modality === 'IMAGE' || job.modality === 'VIDEO' ? job.modality : null;
}

/** Job ids are UUIDs. Anything else is never sent to the API. */
export function isJobId(value: string): boolean {
  return isUuid(value);
}

export function fetchJob(id: string, signal?: AbortSignal): Promise<JobView> {
  return apiFetch<JobView>(`/v1/jobs/${encodeURIComponent(id)}`, { signal });
}

export interface JobListQuery {
  limit: number;
  before?: string;           // a job id: only jobs older than it
  modality?: MediaModality;
  saved?: boolean;           // true: only saved jobs
  recipe?: string;           // a recipe id: only the jobs that ran one of its versions
}

/** GET /v1/jobs: the caller's jobs, newest first. */
export function listJobs(query: JobListQuery, signal?: AbortSignal): Promise<JobView[]> {
  const params = new URLSearchParams({ limit: String(query.limit) });
  if (query.before) params.set('before', query.before);
  if (query.modality) params.set('modality', query.modality);
  if (query.saved) params.set('saved', 'true');
  if (query.recipe) params.set('recipe', query.recipe);
  return apiFetch<JobView[]>(`/v1/jobs?${params}`, { signal });
}

// ===== Saved generations =====
// Every job's files and prompt are deleted when its retention period ends.
// Saving one keeps it until it is un-saved or the account is closed.

/** The saved view: History with only saved generations. */
export const SAVED_VIEW = '/history?saved=1';

/** How long a job that is not saved is kept: the API's retention period. */
export const RETENTION_DAYS = 90;

/**
 * Whether the job gets a save control: a finished one whose files still
 * exist, read from an API that knows about saving.
 */
export function canSave(job: JobView): boolean {
  return job.status === 'DONE' && !job.purged && typeof job.saved === 'boolean';
}

/** PUT /v1/jobs/{id}/saved. A 409's `code` is `saved_limit_reached` or `job_not_saveable`. */
export function saveJob(id: string): Promise<void> {
  return apiFetch<void>(`/v1/jobs/${encodeURIComponent(id)}/saved`, { method: 'PUT' });
}

/** DELETE /v1/jobs/{id}/saved. */
export function unsaveJob(id: string): Promise<void> {
  return apiFetch<void>(`/v1/jobs/${encodeURIComponent(id)}/saved`, { method: 'DELETE' });
}

/** GET /v1/jobs/saved: the count against the limit. */
export function fetchSavedJobs(signal?: AbortSignal): Promise<SavedJobs> {
  return apiFetch<SavedJobs>('/v1/jobs/saved', { signal });
}

function retentionEnd(job: JobView): number | null {
  const made = Date.parse(job.createdAt);
  return Number.isNaN(made) ? null : made + RETENTION_DAYS * 24 * 60 * 60 * 1000;
}

/**
 * True once the job has outlived its retention period, which only a saved
 * job does: un-saved, its files are deleted at the next nightly run.
 */
export function pastRetention(job: JobView, now: number = Date.now()): boolean {
  const end = retentionEnd(job);
  return end !== null && end <= now;
}

/** True when the files are scheduled for deletion and the date has passed: the next nightly run deletes them. */
export function deletionOverdue(job: JobView, now: number = Date.now()): boolean {
  if (!job.mediaExpiresAt) return false;
  const due = Date.parse(job.mediaExpiresAt);
  return !Number.isNaN(due) && due <= now;
}

/** The job as the API returns it after a save or an un-save: a saved job has no deletion date. */
export function withSaved(job: JobView, saved: boolean): JobView {
  const end = retentionEnd(job);
  return { ...job, saved, mediaExpiresAt: saved || end === null ? null : new Date(end).toISOString() };
}

/** How long to wait before the next reading: every 2 s for the first 30 s of watching, then every 5 s. */
export function pollDelay(elapsedMs: number): number {
  return elapsedMs < 30_000 ? 2_000 : 5_000;
}

/**
 * Reads a job on {@link pollDelay}'s schedule until it ends, passing each
 * reading to `onUpdate`. The first reading is one delay away: the caller
 * already holds the job's current state. `onGone` hears when the job can no
 * longer be read (not found, signed out); a dropped connection or a server
 * error is passing, so those are retried. Returns the function that stops it.
 */
export function watchJob(
  id: string,
  onUpdate: (job: JobView) => void,
  onGone?: (err: ApiError) => void,
): () => void {
  const controller = new AbortController();
  const started = Date.now();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  const tick = async () => {
    try {
      const job = await fetchJob(id, controller.signal);
      if (stopped) return;
      onUpdate(job);
      if (isTerminal(job.status)) return;
    } catch (err) {
      if (stopped) return;
      if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 429) {
        onGone?.(err);
        return;
      }
    }
    timer = setTimeout(() => void tick(), pollDelay(Date.now() - started));
  };

  timer = setTimeout(() => void tick(), pollDelay(0));
  return () => {
    stopped = true;
    clearTimeout(timer);
    controller.abort();
  };
}

/**
 * A job as last read, kept current while it runs. `onEnd` hears the reading
 * that ends it. The caller keys the component by job id.
 */
export function useWatchedJob(initial: JobView, onEnd?: (job: JobView) => void): JobView {
  const [job, setJob] = useState(initial);
  const onEndRef = useRef(onEnd);
  useEffect(() => { onEndRef.current = onEnd; });

  const live = !isTerminal(job.status);
  useEffect(() => {
    if (!live) return;
    return watchJob(job.id, (next) => {
      setJob(next);
      if (isTerminal(next.status)) onEndRef.current?.(next);
    });
  }, [job.id, live]);

  return job;
}

export interface OutputItem {
  key: string;
  url: string;
  kind: 'image' | 'video';
  revisedPrompt: string | null;
}

/**
 * The files to show for a job: every output, or the single `outputUrl` when
 * the API sends no outputs (one from before they were recorded). None once
 * the files are purged.
 */
export function outputItems(job: JobView): OutputItem[] {
  if (job.purged) return [];
  const fallback = job.modality === 'VIDEO' ? 'video' : 'image';
  if (job.outputs?.length) {
    return [...job.outputs]
      .sort((a, b) => a.index - b.index)
      .map((o) => ({
        key: String(o.index),
        url: o.url,
        kind: o.contentType?.startsWith('video/') ? 'video'
          : o.contentType?.startsWith('image/') ? 'image'
          : fallback,
        revisedPrompt: o.revisedPrompt ?? null,
      }));
  }
  return job.outputUrl ? [{ key: '0', url: job.outputUrl, kind: fallback, revisedPrompt: null }] : [];
}

/**
 * What the job cost. While it runs, the most it can cost: the amount held,
 * which is charged in full only if every output arrives. A failed job is
 * refunded in full.
 */
export function costText(job: JobView): string {
  switch (job.status) {
    case 'DONE': {
      const cents = job.actualCostCents ?? job.estimatedCostCents;
      return cents === null ? '—' : formatCents(cents);
    }
    case 'QUEUED':
    case 'RUNNING':
      return job.estimatedCostCents === null ? '—' : `up to ${formatCents(job.estimatedCostCents)}`;
    case 'FAILED':
      return 'Refunded';
    default:
      return '—';
  }
}
