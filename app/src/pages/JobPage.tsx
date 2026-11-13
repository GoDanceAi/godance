import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, type JobView } from '../lib/api';
import { formatCents, useAuth } from '../lib/auth';
import { costText, fetchJob, isJobId, isTerminal, mediaModality, STATUS_LABEL, useWatchedJob } from '../lib/jobs';
import { sendError, type SendError } from '../lib/send-error';
import { rerunBody, startGeneration, useEstimate } from '../lib/studio';
import { formatDate, formatDateTime } from '../lib/time';
import CopyButton from '../components/CopyButton';
import { JobOutputs } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';
import SendErrorNotice from '../components/SendErrorNotice';

type Load = { job: JobView } | { missing: true } | { failed: string };

/** /history/:jobId — one generation in full. */
export default function JobPage() {
  const { jobId = '' } = useParams();
  const [load, setLoad] = useState<Load | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoad(null);
    if (!isJobId(jobId)) {
      setLoad({ missing: true });
      return;
    }
    fetchJob(jobId)
      .then((job) => { if (!cancelled) setLoad({ job }); })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad(err instanceof ApiError && err.status === 404
          ? { missing: true }
          : { failed: err instanceof ApiError ? err.detail || err.message : 'Could not load this generation.' });
      });
    return () => { cancelled = true; };
  }, [jobId]);

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <Link to="/history" className="text-sm text-fg-muted hover:text-fg">← History</Link>
      {load === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {load && 'missing' in load && (
        <div className="mt-6 card py-10 text-center">
          <p className="font-semibold text-fg">Generation not found.</p>
          <p className="mt-1 text-sm text-fg-muted">It may belong to another account, or the link is incomplete.</p>
        </div>
      )}
      {load && 'failed' in load && <p role="alert" className="mt-6 text-sm text-danger">{load.failed}</p>}
      {load && 'job' in load && <JobDetail key={load.job.id} initial={load.job} />}
    </div>
  );
}

const PARAM_LABELS: { key: 'size' | 'n' | 'seconds' | 'style' | 'quality'; label: string; unit?: string }[] = [
  { key: 'size', label: 'Size' },
  { key: 'seconds', label: 'Duration', unit: ' seconds' },
  { key: 'n', label: 'Count' },
  { key: 'style', label: 'Style' },
  { key: 'quality', label: 'Quality' },
];

function JobDetail({ initial }: { initial: JobView }) {
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const job = useWatchedJob(initial, () => void refresh());
  const modality = mediaModality(job);
  const rerun = rerunBody(job);
  const price = useEstimate(rerun);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<SendError | null>(null);

  const runAgain = async () => {
    if (!rerun || !modality) return;
    setStarting(true);
    setError(null);
    try {
      const ack = await startGeneration(modality, rerun);
      void refresh();
      navigate(`/create/${modality.toLowerCase()}?job=${encodeURIComponent(ack.job_id)}`);
    } catch (err) {
      setError(sendError(err, 'The generation could not be started.'));
      setStarting(false);
      void refresh();
    }
  };

  const params = job.params ?? {};
  const shownParams = PARAM_LABELS.filter((p) => params[p.key] !== undefined && params[p.key] !== null && params[p.key] !== '');
  const kind = modality === 'VIDEO' ? 'Video' : 'Image';

  return (
    <article className="mt-4">
      <header className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-fg">{kind}</h1>
        <span aria-live="polite"><JobStatusPill status={job.status} /></span>
        <span className="text-sm text-fg-subtle">{formatDateTime(job.createdAt)}</span>
      </header>

      {!isTerminal(job.status) && (
        <p className="mt-3 text-sm text-fg-muted">
          {STATUS_LABEL[job.status]}. This page updates when it finishes.
        </p>
      )}

      {job.status === 'FAILED' && (
        <div className="embedded-surface mt-4 p-4 text-sm">
          {job.errorMessage && <p className="font-medium text-danger">{job.errorMessage}</p>}
          <p className={`text-fg-muted ${job.errorMessage ? 'mt-1' : ''}`}>The charge was returned to your balance.</p>
        </div>
      )}

      {(job.status === 'DONE' || job.purged) && <JobOutputs job={job} className="mt-6" />}
      {job.status === 'DONE' && !job.purged && job.mediaExpiresAt && (
        <p className="mt-3 text-sm text-fg-muted">
          These files will be deleted on <strong className="font-semibold text-fg">{formatDate(job.mediaExpiresAt)}</strong>.
          Download anything you want to keep.
        </p>
      )}

      {job.purged ? (
        <p className="mt-6 text-sm text-fg-muted">The prompt was deleted with the files. The cost and date are kept.</p>
      ) : job.prompt && (
        <section className="mt-6">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold text-fg">Prompt</h2>
            <CopyButton text={job.prompt} label="Copy prompt" />
          </div>
          <p className="mt-2 whitespace-pre-wrap break-words embedded-surface p-4 text-sm text-fg">{job.prompt}</p>
        </section>
      )}

      <section className="mt-6">
        <h2 className="text-sm font-semibold text-fg">Details</h2>
        <dl className="mt-2 card grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-fg-subtle">Model</dt>
          <dd className="font-mono text-[13px] text-fg">{job.model}</dd>
          {shownParams.map((p) => (
            <Detail key={p.key} label={p.label} value={`${params[p.key]}${p.unit ?? ''}`} />
          ))}
          <Detail label="Cost" value={costText(job)} />
          <Detail label="Created" value={formatDateTime(job.createdAt)} />
          {job.completedAt && <Detail label="Finished" value={formatDateTime(job.completedAt)} />}
        </dl>
      </section>

      {rerun && modality && (
        <div className="mt-6 flex flex-wrap items-center gap-2">
          <button type="button" onClick={() => void runAgain()} disabled={starting} className="btn btn-primary">
            {starting ? 'Starting…' : <>Run again{price !== null && <> <span aria-hidden="true">·</span> {formatCents(price)}</>}</>}
          </button>
          <Link to={`/create/${modality.toLowerCase()}?job=${encodeURIComponent(job.id)}`} className="btn btn-secondary">
            Open in Studio
          </Link>
        </div>
      )}
      {error && <SendErrorNotice error={error} what="this generation" then="to keep creating" className="mt-3" />}
    </article>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-fg-subtle">{label}</dt>
      <dd className="text-fg">{value}</dd>
    </>
  );
}
