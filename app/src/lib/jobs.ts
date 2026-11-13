import { useEffect, useRef, useState } from 'react';
import { apiFetch, ApiError, type JobStatus, type JobView, type MediaModality } from './api';
import { formatCents } from './auth';

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

const JOB_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Job ids are UUIDs. Anything else is never sent to the API. */
export function isJobId(value: string): boolean {
  return JOB_ID.test(value);
}

export function fetchJob(id: string, signal?: AbortSignal): Promise<JobView> {
  return apiFetch<JobView>(`/v1/jobs/${encodeURIComponent(id)}`, { signal });
}

export interface JobListQuery {
  limit: number;
  before?: string;           // a job id: only jobs older than it
  modality?: MediaModality;
}

/** GET /v1/jobs: the caller's jobs, newest first. */
export function listJobs(query: JobListQuery, signal?: AbortSignal): Promise<JobView[]> {
  const params = new URLSearchParams({ limit: String(query.limit) });
  if (query.before) params.set('before', query.before);
  if (query.modality) params.set('modality', query.modality);
  return apiFetch<JobView[]>(`/v1/jobs?${params}`, { signal });
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
