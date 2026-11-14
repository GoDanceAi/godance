import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, type JobView, type ModelListResponse } from '../lib/api';
import { formatCents, useAuth } from '../lib/auth';
import {
  costText,
  deletionOverdue,
  fetchJob,
  isJobId,
  isTerminal,
  mediaModality,
  STATUS_LABEL,
  useWatchedJob,
} from '../lib/jobs';
import { controlsFor, loadCatalog, mediaModels, modelName } from '../lib/media-models';
import { sendError, type SendError } from '../lib/send-error';
import { rerunBody, startGeneration, useEstimate } from '../lib/studio';
import { formatDate, formatDateTime } from '../lib/time';
import CopyButton from '../components/CopyButton';
import { JobOutputs } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';
import SaveJobButton from '../components/SaveJobButton';
import SendErrorNotice from '../components/SendErrorNotice';
import VariableValues from '../components/VariableValues';

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

/** GET /v1/models, once it is needed; 'failed' when it could not be read, null while it loads. */
function useCatalog(needed: boolean): ModelListResponse | 'failed' | null {
  const [catalog, setCatalog] = useState<ModelListResponse | 'failed' | null>(null);
  useEffect(() => {
    if (!needed) return;
    let cancelled = false;
    loadCatalog().then(
      (list) => { if (!cancelled) setCatalog(list); },
      () => { if (!cancelled) setCatalog('failed'); },
    );
    return () => { cancelled = true; };
  }, [needed]);
  return catalog;
}

function JobDetail({ initial }: { initial: JobView }) {
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const watched = useWatchedJob(initial, () => void refresh());
  // What a save or un-save made here changed. Only a finished job has the
  // control, so nothing newer arrives from the polling to replace it.
  const [savedState, setSavedState] = useState<Pick<JobView, 'saved' | 'mediaExpiresAt'> | null>(null);
  const job = savedState ? { ...watched, ...savedState } : watched;
  const modality = mediaModality(job);
  const canRerun = Boolean(modality && job.prompt);
  // Run again sends what the model takes now, so it waits for the model list.
  const catalog = useCatalog(canRerun);
  const model = catalog && catalog !== 'failed' && modality
    ? mediaModels(catalog, modality).find((m) => m.id === job.model)
    : undefined;
  const retired = catalog !== null && catalog !== 'failed' && !model;
  const rerun = modality && catalog !== null && !retired
    ? rerunBody(job, catalog === 'failed' ? null : controlsFor(model, modality))
    : null;
  const price = useEstimate(rerun);
  // How many the job asked for, when Run again asks for fewer.
  const asked = typeof job.params?.n === 'number' ? job.params.n : 1;
  const fewer = rerun && model && asked > (rerun.n ?? 1) ? rerun.n ?? 1 : null;
  const noun = modality === 'VIDEO' ? 'video' : 'image';
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
        <SaveJobButton
          job={job}
          onChange={(next) => setSavedState({ saved: next.saved, mediaExpiresAt: next.mediaExpiresAt })}
          className="btn btn-secondary btn-sm ml-auto"
        />
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
      {job.status === 'DONE' && !job.purged && <KeptUntil job={job} />}

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

      {job.recipe && !job.purged && job.recipe.variables && Object.keys(job.recipe.variables).length > 0 && (
        <section className="mt-6">
          <h2 className="text-sm font-semibold text-fg">Variables</h2>
          <div className="mt-2 card">
            <VariableValues recipe={job.recipe} className="text-sm" />
          </div>
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
          {job.recipe && (
            <>
              <dt className="text-fg-subtle">Recipe</dt>
              <dd className="text-fg">
                <Link to={`/recipes/${job.recipe.id}`} className="text-brand-600 hover:underline">
                  Your recipe, version {job.recipe.version}
                </Link>
              </dd>
            </>
          )}
          <Detail label="Cost" value={costText(job)} />
          <Detail label="Created" value={formatDateTime(job.createdAt)} />
          {job.completedAt && <Detail label="Finished" value={formatDateTime(job.completedAt)} />}
        </dl>
      </section>

      {canRerun && modality && (
        <div className="mt-6 flex flex-wrap items-center gap-2">
          {!retired && (
            <button type="button" onClick={() => void runAgain()} disabled={starting || !rerun} className="btn btn-primary">
              {starting ? 'Starting…' : <>Run again{price !== null && <> <span aria-hidden="true">·</span> {formatCents(price)}</>}</>}
            </button>
          )}
          <Link to={`/create/${modality.toLowerCase()}?job=${encodeURIComponent(job.id)}`} className="btn btn-secondary">
            Open in Studio
          </Link>
        </div>
      )}
      {fewer !== null && model && (
        <p className="mt-3 text-sm text-fg-muted">
          {modelName(model)} makes {fewer === 1 ? `one ${noun}` : `up to ${fewer} ${noun}s`} per request,
          so Run again asks for {fewer === 1 ? 'one' : fewer}.
        </p>
      )}
      {canRerun && retired && (
        <p className="mt-3 text-sm text-fg-muted">
          {job.model} isn&apos;t available any more. Open it in the Studio to run the prompt on another model.
        </p>
      )}
      {error && <SendErrorNotice error={error} what="this generation" then="to keep creating" className="mt-3" />}
    </article>
  );
}

/**
 * How long a finished job's files are kept: for as long as it is saved, or
 * until its deletion date. Nothing when the API gives no date.
 */
function KeptUntil({ job }: { job: JobView }) {
  if (job.saved) {
    return (
      <p className="mt-3 text-sm text-fg-muted">
        <strong className="font-semibold text-fg">Saved.</strong> These files are kept until you un-save
        this generation or close your account.
      </p>
    );
  }
  if (!job.mediaExpiresAt) return null;
  if (deletionOverdue(job)) {
    return (
      <p className="mt-3 text-sm text-fg-muted">
        These files are <strong className="font-semibold text-fg">due to be deleted</strong> at the next
        nightly clean-up. Download anything you want to keep, or save this generation again.
      </p>
    );
  }
  return (
    <p className="mt-3 text-sm text-fg-muted">
      These files will be deleted on <strong className="font-semibold text-fg">{formatDate(job.mediaExpiresAt)}</strong>.
      Download anything you want to keep.
    </p>
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
