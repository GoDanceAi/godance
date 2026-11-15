import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../../lib/api';
import { downloadResults, formatBytes, type ResultKind } from '../../lib/batches';

type Problem =
  | { kind: 'too-large'; detail: string | null }   // the outputs are more than one zip may hold
  | { kind: 'failed'; file: ResultKind; message: string };

/**
 * A batch's results as files: a CSV with one line per row, and a zip of the
 * output files. A zip the API refuses as too large says so, and offers the
 * CSV, which links to every output.
 */
export default function BatchDownloads({ batchId }: { batchId: string }) {
  const [busy, setBusy] = useState<ResultKind | null>(null);
  const [bytes, setBytes] = useState(0);
  const [problem, setProblem] = useState<Problem | null>(null);
  const abort = useRef<AbortController | null>(null);

  // Leaving the page stops a download that is still arriving.
  useEffect(() => () => abort.current?.abort(), []);

  const download = async (kind: ResultKind) => {
    if (busy) return;
    const controller = new AbortController();
    abort.current = controller;
    setBusy(kind);
    setBytes(0);
    setProblem(null);
    try {
      await downloadResults(batchId, kind, setBytes, controller.signal);
    } catch (err) {
      if (controller.signal.aborted) return;
      if (kind === 'zip' && err instanceof ApiError && (err.code === 'results_too_large' || err.status === 413)) {
        setProblem({ kind: 'too-large', detail: err.message.startsWith('HTTP ') ? null : err.message });
      } else {
        setProblem({
          kind: 'failed',
          file: kind,
          message: err instanceof ApiError && !err.message.startsWith('HTTP ')
            ? err.message
            : `Couldn't download the ${kind === 'csv' ? 'CSV' : 'zip'}. Try again.`,
        });
      }
    } finally {
      if (!controller.signal.aborted) setBusy(null);
    }
  };

  const label = (kind: ResultKind, idle: string) => (busy === kind
    ? `Downloading…${bytes > 0 ? ` ${formatBytes(bytes)}` : ''}`
    : idle);

  return (
    <section aria-labelledby="batch-results">
      <h2 id="batch-results" className="text-sm font-semibold text-fg">Results</h2>
      <p className="mt-1 text-sm text-fg-muted">
        The CSV has a line for every row, with a link to each output. The links expire, so download it
        again when you need fresh ones. The zip holds the output files themselves.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => void download('csv')} disabled={busy !== null} className="btn btn-secondary">
          {label('csv', 'Download CSV')}
        </button>
        <button type="button" onClick={() => void download('zip')} disabled={busy !== null} className="btn btn-secondary">
          {label('zip', 'Download zip')}
        </button>
      </div>
      <p role="status" aria-live="polite" className="sr-only">
        {busy ? `Downloading the ${busy === 'csv' ? 'CSV' : 'zip'}.` : ''}
      </p>

      {problem?.kind === 'too-large' && (
        <div role="alert" className="mt-3 embedded-surface p-4 text-sm" data-results-too-large>
          <p className="font-medium text-fg">The outputs are too large to pack into one zip.</p>
          <p className="mt-1 text-fg-muted">
            {problem.detail && <>{problem.detail} </>}
            The CSV links to every output, so each file can be fetched from there.
          </p>
          <button type="button" onClick={() => void download('csv')} disabled={busy !== null} className="btn btn-primary btn-sm mt-3">
            Download the CSV instead
          </button>
        </div>
      )}
      {problem?.kind === 'failed' && <p role="alert" className="mt-3 text-sm text-danger">{problem.message}</p>}
    </section>
  );
}
