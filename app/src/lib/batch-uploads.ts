import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from './api';
import { inputErrorMessage, uploadInput } from './inputs';

// The images of a batch, stored through POST /v1/files a few at a time.
// A file is stored once however many rows use it, and however often the
// matching changes.

/** How many uploads run at once. */
const PARALLEL = 3;

export type Upload =
  | { status: 'waiting' }
  | { status: 'uploading' }
  | { status: 'done'; fileId: string }
  | { status: 'failed'; error: string };

/** What tells two choices of the same file apart from two files: name, size and date. */
export function fileKey(file: File): string {
  return `${file.name}\u0000${file.size}\u0000${file.lastModified}`;
}

/**
 * A refusal that the next file would meet too: the account's upload rate or
 * its stored-file limit is reached, or inputs are not available to it.
 * The files still waiting are not sent after one of these.
 */
function stopsEverything(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false;
  return err.status === 429 || [
    'input_limit_reached', 'inputs_disabled', 'inputs_terms_required', 'inputs_not_allowed_for_account',
  ].includes(err.code ?? '');
}

export interface UploadCounts {
  wanted: number;
  done: number;
  failed: number;
}

export interface Uploader {
  /** Where a file stands, or undefined when it was never wanted. */
  of: (file: File) => Upload | undefined;
  /** The files to have stored: each not yet sent is sent, and one that is no longer wanted and still waiting is not. */
  want: (files: readonly File[]) => void;
  /** Sends the failed ones again. */
  retry: () => void;
  /** Over the files wanted now. */
  counts: UploadCounts;
  /** Goes up with every change, for a memo to depend on. */
  version: number;
}

export function useUploader(): Uploader {
  const uploads = useRef(new Map<string, Upload>());
  const wanted = useRef<readonly File[]>([]);
  const queue = useRef<File[]>([]);
  const active = useRef(0);
  const alive = useRef(true);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const changed = useCallback(() => {
    if (alive.current) setVersion((n) => n + 1);
  }, []);

  const pump = useCallback(() => {
    while (alive.current && active.current < PARALLEL && queue.current.length > 0) {
      const file = queue.current.shift() as File;
      const key = fileKey(file);
      if (uploads.current.get(key)?.status !== 'waiting') continue;
      active.current += 1;
      uploads.current.set(key, { status: 'uploading' });
      uploadInput(file, file.name).then(
        (stored) => { uploads.current.set(key, { status: 'done', fileId: stored.id }); },
        (err: unknown) => {
          const error = inputErrorMessage(err);
          uploads.current.set(key, { status: 'failed', error });
          if (stopsEverything(err)) {
            for (const waiting of queue.current.splice(0)) uploads.current.set(fileKey(waiting), { status: 'failed', error });
          }
        },
      ).finally(() => {
        active.current -= 1;
        changed();
        pump();
      });
    }
  }, [changed]);

  const enqueue = useCallback(() => {
    const seen = new Set<string>();
    queue.current = wanted.current.filter((file) => {
      const key = fileKey(file);
      if (seen.has(key)) return false;
      seen.add(key);
      return uploads.current.get(key)?.status === 'waiting';
    });
    changed();
    pump();
  }, [changed, pump]);

  const want = useCallback((files: readonly File[]) => {
    const keys = new Set(files.map(fileKey));
    for (const [key, upload] of uploads.current) {
      if (upload.status === 'waiting' && !keys.has(key)) uploads.current.delete(key);
    }
    for (const key of keys) {
      if (!uploads.current.has(key)) uploads.current.set(key, { status: 'waiting' });
    }
    wanted.current = files;
    enqueue();
  }, [enqueue]);

  const retry = useCallback(() => {
    for (const file of wanted.current) {
      const key = fileKey(file);
      if (uploads.current.get(key)?.status === 'failed') uploads.current.set(key, { status: 'waiting' });
    }
    enqueue();
  }, [enqueue]);

  const of = useCallback((file: File) => uploads.current.get(fileKey(file)), []);

  const counts = useMemo<UploadCounts>(() => {
    const keys = new Set(wanted.current.map(fileKey));
    let done = 0;
    let failed = 0;
    for (const key of keys) {
      const status = uploads.current.get(key)?.status;
      if (status === 'done') done += 1;
      else if (status === 'failed') failed += 1;
    }
    return { wanted: keys.size, done, failed };
    // The maps are refs: `version` is what says they changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version]);

  return { of, want, retry, counts, version };
}
