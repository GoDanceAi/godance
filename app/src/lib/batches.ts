import { useEffect, useMemo, useRef, useState } from 'react';
import {
  API_BASE,
  apiErrorFrom,
  apiFetch,
  ApiError,
  UNAUTHORIZED_EVENT,
  type Batch,
  type BatchEstimate,
  type BatchRequest,
  type BatchRow,
  type BatchRowStatus,
  type BatchStatus,
} from './api';
import { isUuid } from './ids';

// Recipe batches (round 12): one version of a recipe run over many rows.
// The calls, reading a batch until nothing in it can change, and its result files.

const BASE = '/v1/recipe-batches';

/** Batch ids are UUIDs. Anything else is never sent to the API. */
export function isBatchId(value: string): boolean {
  return isUuid(value);
}

/** POST /v1/recipe-batches/estimate: the same body as create. Nothing is started or charged. */
export function estimateBatch(body: BatchRequest, signal?: AbortSignal): Promise<BatchEstimate> {
  return apiFetch<BatchEstimate>(`${BASE}/estimate`, { method: 'POST', json: body, signal });
}

/** POST /v1/recipe-batches: starts the batch. A 400 that names a row carries `row` (see {@link badRow}). */
export async function createBatch(body: BatchRequest): Promise<Batch> {
  const batch = await apiFetch<Batch>(BASE, { method: 'POST', json: body });
  if (typeof batch?.id !== 'string') throw new Error('The batch could not be started.');
  return batch;
}

/** GET /v1/recipe-batches: the caller's batches, newest first; `before` is a batch id, for the page after it. */
export function listBatches(
  query: { limit: number; before?: string; recipeId?: string },
  signal?: AbortSignal,
): Promise<Batch[]> {
  const params = new URLSearchParams({ limit: String(query.limit) });
  if (query.before) params.set('before', query.before);
  if (query.recipeId) params.set('recipe_id', query.recipeId);
  return apiFetch<Batch[]>(`${BASE}?${params}`, { signal });
}

export function fetchBatch(id: string, signal?: AbortSignal): Promise<Batch> {
  return apiFetch<Batch>(`${BASE}/${encodeURIComponent(id)}`, { signal });
}

/** How many rows one page of a batch's rows holds. */
export const ROWS_PAGE = 50;

/**
 * GET /v1/recipe-batches/{id}/rows, in row order. `after` is the index of
 * the last row already held; left out, the rows start at the first.
 */
export function fetchBatchRows(id: string, after: number | null, signal?: AbortSignal): Promise<BatchRow[]> {
  const params = new URLSearchParams({ limit: String(ROWS_PAGE) });
  if (after !== null && after >= 0) params.set('after', String(after));
  return apiFetch<BatchRow[]>(`${BASE}/${encodeURIComponent(id)}/rows?${params}`, { signal });
}

/** POST …/cancel: rows that are running finish and are charged; the rest are skipped. Answers the batch. */
export function cancelBatch(id: string): Promise<Batch> {
  return apiFetch<Batch>(`${BASE}/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
}

/** POST …/resume: a paused batch goes on from where it stopped. A 409's `code` is `batch_not_resumable`. */
export function resumeBatch(id: string): Promise<Batch> {
  return apiFetch<Batch>(`${BASE}/${encodeURIComponent(id)}/resume`, { method: 'POST' });
}

// ===== What a batch is doing =====

export const BATCH_STATUS_LABEL: Record<BatchStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  PAUSED: 'Paused',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
};

export const ROW_STATUS_LABEL: Record<BatchRowStatus, string> = {
  PENDING: 'Waiting',
  RUNNING: 'Running',
  DONE: 'Done',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
};

/** Rows that will not change again: done, failed, and skipped by a cancel. */
export function finishedRows(batch: Batch): number {
  return batch.rows_done + batch.rows_failed + (batch.rows_skipped ?? 0);
}

/**
 * Whether anything about the batch can still change by itself: it is
 * waiting or running, or it is paused or cancelled with rows still
 * finishing. A batch that is paused with nothing running waits for Resume.
 */
export function isLive(batch: Batch): boolean {
  return batch.status === 'QUEUED' || batch.status === 'RUNNING' || (batch.rows_running ?? 0) > 0;
}

/** Whether the batch can still be cancelled. */
export function canCancel(batch: Batch): boolean {
  return batch.status === 'QUEUED' || batch.status === 'RUNNING' || batch.status === 'PAUSED';
}

/** A row that can still change. */
export function rowUnfinished(row: BatchRow): boolean {
  return row.status === 'PENDING' || row.status === 'RUNNING';
}

/** How often a batch is read while it is live. */
export const BATCH_POLL_MS = 3_000;

/**
 * Reads a batch every {@link BATCH_POLL_MS} until it is no longer live,
 * passing each reading to `onUpdate`. The first reading is one delay away:
 * the caller already holds the batch as it stands. `onGone` hears when it
 * can no longer be read (not found, signed out); a dropped connection or a
 * server error is passing, so those are retried. Returns the function that
 * stops it.
 */
export function watchBatch(
  id: string,
  onUpdate: (batch: Batch) => void,
  onGone?: (err: ApiError) => void,
): () => void {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  const tick = async () => {
    try {
      const batch = await fetchBatch(id, controller.signal);
      if (stopped) return;
      onUpdate(batch);
      if (!isLive(batch)) return;
    } catch (err) {
      if (stopped) return;
      if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 429) {
        onGone?.(err);
        return;
      }
    }
    timer = setTimeout(() => void tick(), BATCH_POLL_MS);
  };

  timer = setTimeout(() => void tick(), BATCH_POLL_MS);
  return () => {
    stopped = true;
    clearTimeout(timer);
    controller.abort();
  };
}

// ===== The estimate =====

const ESTIMATE_DELAY_MS = 400;

export type BatchLimits = NonNullable<BatchEstimate['limits']>;

let lastLimits: BatchLimits | null = null;

/**
 * The API's limits on a batch (rows, and rows at a time), as the last
 * estimate of this page load gave them. Null until one has been seen: only
 * an estimate says them.
 */
export function knownLimits(): BatchLimits | null {
  return lastLimits;
}

function validLimits(limits: BatchEstimate['limits']): limits is BatchLimits {
  return limits !== undefined && limits !== null
    && Number.isInteger(limits.max_rows) && limits.max_rows > 0
    && Number.isInteger(limits.max_concurrency) && limits.max_concurrency > 0;
}

export interface EstimateState {
  /** The answer for exactly the request given, or null: not asked, still asking, or refused. */
  estimate: BatchEstimate | null;
  /** Why the API refused to price exactly this request, or null. */
  error: unknown;
  /** True while the request given has no answer yet. */
  asking: boolean;
  /** Asks again for the same request, after a failure. */
  retry: () => void;
  /** What tells this request from any other: the same for two requests that say the same. */
  key: string | null;
}

/**
 * What a batch would cost, from POST /v1/recipe-batches/estimate once the
 * request has been still for a moment. An answer is kept only for the
 * request it priced: change anything and it is gone until the new one is
 * priced, which is what lets Start wait for it.
 */
export function useBatchEstimate(body: BatchRequest | null): EstimateState {
  const key = useMemo(() => (body ? JSON.stringify(body) : null), [body]);
  const [answer, setAnswer] = useState<{ key: string; estimate: BatchEstimate | null; error: unknown } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const latest = useRef(body);
  latest.current = body;

  useEffect(() => {
    const request = latest.current;
    if (!key || !request) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      estimateBatch(request, controller.signal).then(
        (estimate) => {
          if (controller.signal.aborted) return;
          if (!Number.isInteger(estimate?.total_cents) || !Number.isInteger(estimate?.rows)) {
            setAnswer({ key, estimate: null, error: new Error('The batch could not be priced.') });
            return;
          }
          if (validLimits(estimate.limits)) lastLimits = estimate.limits;
          setAnswer({ key, estimate, error: null });
        },
        (error: unknown) => { if (!controller.signal.aborted) setAnswer({ key, estimate: null, error }); },
      );
    }, ESTIMATE_DELAY_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [key, attempt]);

  const mine = answer !== null && answer.key === key ? answer : null;
  return {
    estimate: mine?.estimate ?? null,
    error: mine?.error ?? null,
    asking: key !== null && mine === null,
    retry: () => {
      setAnswer(null);
      setAttempt((n) => n + 1);
    },
    key,
  };
}

// ===== A refusal that names a row =====

/** The row a 400 names, counted from 0 as the API counts, or null when it names none. */
export function badRow(err: unknown): number | null {
  const row = err instanceof ApiError ? err.problem?.row : undefined;
  return typeof row === 'number' && Number.isInteger(row) && row >= 0 ? row : null;
}

/**
 * The API's reason without its own "row N: " at the front. The API counts
 * rows from 0 and the page from 1, so the page says which row itself.
 */
export function rowReason(err: unknown, fallback: string): string {
  const text = err instanceof ApiError && !err.message.startsWith('HTTP ') ? err.message : fallback;
  return text.replace(/^row \d+:\s*/i, '');
}

// ===== Result files =====

export type ResultKind = 'csv' | 'zip';

/** The name a result file is saved under. */
export function resultFileName(id: string, kind: ResultKind): string {
  return `batch-${id.slice(0, 8)}-results.${kind}`;
}

/**
 * Fetches results.csv or results.zip and hands it to the browser to save.
 * Not through {@link apiFetch}: these routes answer a file, so the request
 * says nothing about wanting JSON. The session cookie goes with it, which
 * is why it is fetched and saved from memory rather than opened as a link.
 * `onProgress` hears how many bytes have arrived. A refusal is thrown as an
 * ApiError; a zip that is too large has the code `results_too_large`.
 */
export async function downloadResults(
  id: string,
  kind: ResultKind,
  onProgress?: (bytes: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${API_BASE}${BASE}/${encodeURIComponent(id)}/results.${kind}`, {
    credentials: 'include',
    signal,
  });
  if (res.status === 401) window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
  if (!res.ok) throw await apiErrorFrom(res);

  const type = kind === 'csv' ? 'text/csv' : 'application/zip';
  let blob: Blob;
  if (res.body) {
    const reader = res.body.getReader();
    const chunks: BlobPart[] = [];
    let bytes = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      bytes += value.byteLength;
      onProgress?.(bytes);
    }
    blob = new Blob(chunks, { type });
  } else {
    blob = new Blob([await res.arrayBuffer()], { type });
  }

  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = resultFileName(id, kind);
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // After the click has been taken: the browser copies what it saves.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** "12.4 MB", "640 KB". */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
