import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, NavLink, useNavigate, useSearchParams } from 'react-router-dom';
import type { JobStatus, JobView, MediaModality, ModelEntry } from '../lib/api';
import { formatCents, useAuth } from '../lib/auth';
import { getCurl, JOB_ID_VAR, postCurl } from '../lib/curl';
import { isTerminal, outputItems, watchJob } from '../lib/jobs';
import { controlsFor, groupByProvider, loadCatalog, mediaModels, modelName, priceLabel } from '../lib/media-models';
import { sendError, type SendError } from '../lib/send-error';
import {
  generationBody,
  generationPath,
  PROMPT_MAX_LENGTH,
  startGeneration,
  useEstimate,
  type StudioValues,
} from '../lib/studio';
import { fitValues, readPrefill, resolvePrefill, type Prefill } from '../lib/studio-prefill';
import type { GalleryRecipe, RecipeLang } from '../lib/recipe';
import CopyButton from '../components/CopyButton';
import GalleryExamples from '../components/GalleryExamples';
import { JobOutputs } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';
import RecipeSource from '../components/RecipeSource';
import SaveJobButton from '../components/SaveJobButton';
import SendErrorNotice from '../components/SendErrorNotice';

const WORDS = {
  IMAGE: {
    one: 'image',
    many: 'images',
    count: 'Number of images',
    placeholder: 'A lighthouse on a sea cliff at dusk, oil painting',
  },
  VIDEO: {
    one: 'video',
    many: 'videos',
    count: 'Number of videos',
    placeholder: 'A paper boat drifting down a rainy street, slow tracking shot',
  },
} as const;

const NO_VALUES: StudioValues = { model: '', prompt: '', size: null, n: 1, seconds: null };

/** The job the result panel shows: known by id once accepted, read in full from its first poll. */
interface Current {
  id: string;
  status: JobStatus;
  job: JobView | null;
  prompt: string;      // as sent, for Copy prompt before the first reading
  gone?: boolean;      // can no longer be read
}

/**
 * The Studio: a playground for the public API. Every run is the request an
 * API key would make, priced before it is sent, and the page shows it.
 */
export default function StudioPage({ modality }: { modality: MediaModality }) {
  const { user, refresh } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const search = searchParams.toString();
  const words = WORDS[modality];

  const [models, setModels] = useState<ModelEntry[] | null>(null);
  const [catalogFailed, setCatalogFailed] = useState(false);
  const [prefill, setPrefill] = useState<Prefill | null>(null);
  const [ready, setReady] = useState(false);
  const [values, setValues] = useState<StudioValues>(NO_VALUES);
  const [note, setNote] = useState<string | null>(null);
  const [current, setCurrent] = useState<Current | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<SendError | null>(null);
  const [codeOpen, setCodeOpen] = useState(false);
  // The gallery recipe the form was filled from (?example=), and its prompt's language.
  const [recipe, setRecipe] = useState<{ recipe: GalleryRecipe; lang: RecipeLang } | null>(null);
  // Opened with nothing to load, or with nothing that could be: the Studio offers examples.
  const [startedEmpty, setStartedEmpty] = useState(false);
  // The query this page wrote itself after a submit, which needs no loading.
  const ownSearch = useRef<string | null>(null);
  const resultRef = useRef<HTMLElement>(null);

  useEffect(() => {
    let cancelled = false;
    loadCatalog()
      .then((list) => { if (!cancelled) setModels(mediaModels(list, modality)); })
      .catch(() => { if (!cancelled) setCatalogFailed(true); });
    return () => { cancelled = true; };
  }, [modality]);

  // Whatever the URL asks to load (studio-prefill.ts decides what that is).
  useEffect(() => {
    if (search === ownSearch.current) return;
    let cancelled = false;
    setReady(false);
    void resolvePrefill(readPrefill(new URLSearchParams(search)), modality).then((result) => {
      if (cancelled) return;
      if (result.otherModality) {
        navigate({ pathname: `/create/${result.otherModality.toLowerCase()}`, search }, { replace: true });
        return;
      }
      const job = result.job;
      setCurrent(job ? { id: job.id, status: job.status, job, prompt: job.prompt ?? '' } : null);
      setPrefill(result);
    });
    return () => { cancelled = true; };
  }, [search, modality, navigate]);

  // The form's starting values, once both the URL's and the catalog are in.
  useEffect(() => {
    if (!prefill || !models) return;
    const fitted = fitValues(prefill.values, models, modality);
    setValues(fitted.values);
    setNote(prefill.note ?? fitted.note ?? null);
    setRecipe(prefill.recipe ? { recipe: prefill.recipe, lang: prefill.recipe.run.promptLang } : null);
    setStartedEmpty(!prefill.job && !prefill.recipe && Object.keys(prefill.values).length === 0);
    setPrefill(null);
    setReady(true);
  }, [prefill, models, modality]);

  // Credit added in another tab (a top-up, a parent's approval) shows when this one is back in view.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refresh]);

  // Watch the current job until it ends; the balance moves when it does.
  const liveId = current && !current.gone && !isTerminal(current.status) ? current.id : null;
  useEffect(() => {
    if (!liveId) return;
    return watchJob(
      liveId,
      (job) => {
        setCurrent((cur) => (cur?.id === job.id ? { ...cur, status: job.status, job } : cur));
        if (isTerminal(job.status)) void refresh();
      },
      () => setCurrent((cur) => (cur?.id === liveId ? { ...cur, gone: true } : cur)),
    );
  }, [liveId, refresh]);

  const model = models?.find((m) => m.id === values.model);
  const controls = controlsFor(model, modality);
  const request = ready && model ? generationBody(modality, values, controls) : null;
  const price = useEstimate(request);
  const canSubmit = request !== null && values.prompt.trim() !== '' && !submitting;

  if (!user) return null;

  const update = (patch: Partial<StudioValues>) => setValues((v) => ({ ...v, ...patch }));
  const pickModel = (id: string) =>
    setValues((v) => fitValues({ ...v, model: id }, models ?? [], modality).values);
  // An example loads as its link would: through the URL, so Back returns to the empty Studio.
  const pickExample = (slug: string) => setSearchParams({ example: slug });
  const switchLanguage = (lang: RecipeLang) => {
    const text = recipe?.recipe.prompt[lang];
    if (!recipe || !text) return;
    setRecipe({ ...recipe, lang });
    update({ prompt: text.slice(0, PROMPT_MAX_LENGTH) });
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit || !request) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const ack = await startGeneration(modality, request);
      setCurrent({ id: ack.job_id, status: ack.status ?? 'QUEUED', job: null, prompt: request.prompt });
      // In the URL, so a reload picks the run back up.
      const next = new URLSearchParams({ job: ack.job_id });
      ownSearch.current = next.toString();
      setSearchParams(next, { replace: true });
      resultRef.current?.scrollIntoView?.({ block: 'nearest' });
    } catch (err) {
      setSubmitError(sendError(err, 'The generation could not be started.'));
    } finally {
      setSubmitting(false);
      void refresh();
    }
  };

  const groups = models ? groupByProvider(models) : [];

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Create</h1>
        <nav aria-label="Studio" className="inline-flex rounded-md border border-border bg-surface p-0.5">
          {(['IMAGE', 'VIDEO'] as const).map((m) => (
            <NavLink
              key={m}
              to={`/create/${m.toLowerCase()}`}
              className={({ isActive }) =>
                `rounded-sm px-4 py-1.5 text-[13px] font-semibold transition-colors ${
                  isActive ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
            >
              {m === 'IMAGE' ? 'Image' : 'Video'}
            </NavLink>
          ))}
        </nav>
      </div>
      <p className="mt-2 text-sm text-fg-muted">
        Each run is a request to the Godance API, priced before you send it.
        What you make is kept in <Link to="/history" className="text-brand-600 hover:underline">History</Link>.
      </p>

      {ready && startedEmpty && current === null && <GalleryExamples modality={modality} onPick={pickExample} />}

      {/* grid-cols-1 is minmax(0, 1fr): long curl lines scroll in their block instead of widening the page. */}
      <div className="mt-6 grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <form onSubmit={(e) => void submit(e)} aria-label={`Create ${words.one === 'image' ? 'an image' : 'a video'}`}
              className="card space-y-5">
          {recipe && <RecipeSource recipe={recipe.recipe} lang={recipe.lang} onLanguage={switchLanguage} />}
          <div>
            <label htmlFor="studio-prompt" className="block text-sm font-medium text-fg">Prompt</label>
            <textarea
              id="studio-prompt"
              lang={recipe?.lang === 'zh' ? 'zh-Hans' : undefined}
              value={values.prompt}
              onChange={(e) => update({ prompt: e.target.value })}
              maxLength={PROMPT_MAX_LENGTH}
              rows={5}
              placeholder={words.placeholder}
              disabled={!ready}
              className="input mt-1.5 resize-y"
            />
          </div>

          <div>
            <label htmlFor="studio-model" className="block text-sm font-medium text-fg">Model</label>
            <select
              id="studio-model"
              value={values.model}
              onChange={(e) => pickModel(e.target.value)}
              disabled={!ready || groups.length === 0}
              className="input mt-1.5"
            >
              {!ready && <option value="">{catalogFailed ? 'Models unavailable' : 'Loading models…'}</option>}
              {ready && groups.map((g) => (
                <optgroup key={g.provider} label={g.name}>
                  {g.models.map((m) => {
                    const price = priceLabel(m);
                    return <option key={m.id} value={m.id}>{price ? `${modelName(m)} · ${price}` : modelName(m)}</option>;
                  })}
                </optgroup>
              ))}
            </select>
            {model?.godance.description && (
              <p className="mt-1.5 text-xs text-fg-subtle">{model.godance.description}</p>
            )}
          </div>

          {(controls.sizes || controls.durations || controls.maxN) && (
            <div className="grid grid-cols-2 gap-4">
              {controls.sizes && (
                <SelectField
                  id="studio-size"
                  label="Size"
                  value={values.size ?? ''}
                  options={controls.sizes.map((s) => ({ value: s, label: s }))}
                  onChange={(v) => update({ size: v })}
                />
              )}
              {controls.durations && (
                <SelectField
                  id="studio-seconds"
                  label="Duration"
                  value={String(values.seconds ?? '')}
                  options={controls.durations.map((s) => ({ value: String(s), label: `${s} seconds` }))}
                  onChange={(v) => update({ seconds: Number(v) })}
                />
              )}
              {controls.maxN && (
                <SelectField
                  id="studio-n"
                  label={words.count}
                  value={String(values.n)}
                  options={Array.from({ length: controls.maxN }, (_, i) => ({ value: String(i + 1), label: String(i + 1) }))}
                  onChange={(v) => update({ n: Number(v) })}
                />
              )}
            </div>
          )}

          {note && <p className="text-xs text-warning">{note}</p>}
          {catalogFailed && (
            <p role="alert" className="text-sm text-danger">
              Couldn't load the models. Reload the page to try again.
            </p>
          )}
          {ready && groups.length === 0 && (
            <p className="text-sm text-fg-muted">No {words.one} models are available right now.</p>
          )}
          {submitError && (
            <SendErrorNotice error={submitError} what="this generation" then="to keep creating" />
          )}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <button type="submit" disabled={!canSubmit} className="btn btn-primary btn-lg">
              {submitting ? 'Starting…' : (
                <>Generate{price !== null && <> <span aria-hidden="true">·</span> {formatCents(price)}</>}</>
              )}
            </button>
            <p className="text-xs text-fg-subtle">
              Balance <span className="font-semibold tabular-nums text-fg">{formatCents(user.creditBalanceCents)}</span>
            </p>
          </div>

          <div className="border-t border-border pt-4">
            <button
              type="button"
              onClick={() => setCodeOpen((open) => !open)}
              aria-expanded={codeOpen}
              aria-controls="studio-code"
              className="btn btn-ghost btn-sm px-0"
            >
              View code {codeOpen ? '▴' : '▾'}
            </button>
            {codeOpen && (
              <div id="studio-code" className="mt-3 space-y-4">
                <p className="text-xs text-fg-muted">
                  The same request with an API key. Set <code className="code-chip">GODANCE_API_KEY</code> to
                  a key from <Link to="/keys" className="text-brand-600 hover:underline">API keys</Link>.
                </p>
                {request ? (
                  <>
                    <CodeSnippet title="Start the job" code={postCurl(generationPath(modality), request)} />
                    <CodeSnippet
                      title="Check on it"
                      code={getCurl(`/v1/jobs/${current?.id ?? JOB_ID_VAR}`)}
                      note={current ? undefined : 'JOB_ID is the job_id the first request answers with.'}
                    />
                  </>
                ) : (
                  <p className="text-xs text-fg-faint">Loading…</p>
                )}
              </div>
            )}
          </div>
        </form>

        <section ref={resultRef} aria-label="Result" className="card scroll-mt-4">
          <Result
            current={current}
            modality={modality}
            onJobChange={(job) => setCurrent((cur) => (cur?.id === job.id ? { ...cur, job } : cur))}
          />
        </section>
      </div>
    </div>
  );
}

function SelectField({ id, label, value, options, onChange }: {
  id: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-fg">{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="input mt-1.5">
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}

function CodeSnippet({ title, code, note }: { title: string; code: string; note?: string }) {
  return (
    <figure aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <figcaption className="text-xs font-semibold text-fg-subtle">{title}</figcaption>
        <CopyButton text={code} className="btn btn-ghost btn-sm" />
      </div>
      <pre className="code-block mt-1.5"><code>{code}</code></pre>
      {note && <p className="mt-1.5 text-xs text-fg-faint">{note}</p>}
    </figure>
  );
}

function statusLine(current: Current, words: (typeof WORDS)[MediaModality]): string {
  if (current.gone) return "This generation can't be read any more.";
  switch (current.status) {
    case 'QUEUED': return 'Queued. The model starts on it shortly.';
    case 'RUNNING': return 'Running.';
    case 'DONE': {
      const count = current.job ? outputItems(current.job).length : 0;
      return count > 0 ? `Done: ${count} ${count === 1 ? words.one : words.many}.` : 'Done.';
    }
    case 'FAILED': return 'Failed.';
    case 'CANCELLED': return 'Cancelled.';
  }
}

function Result({ current, modality, onJobChange }: {
  current: Current | null;
  modality: MediaModality;
  onJobChange: (job: JobView) => void;   // the job was saved or un-saved here
}) {
  const words = WORDS[modality];
  const job = current?.job ?? null;
  const prompt = job?.prompt ?? current?.prompt ?? '';
  const running = current !== null && !current.gone && !isTerminal(current.status);

  return (
    <>
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-fg">Result</h2>
        {current && <JobStatusPill status={current.status} />}
      </div>

      {/* Status changes are announced. */}
      <div role="status" className="mt-1 text-sm text-fg-muted">
        {current && <p>{statusLine(current, words)}</p>}
        {current?.status === 'FAILED' && (
          <div className="embedded-surface mt-3 p-4">
            {job?.errorMessage && <p className="font-medium text-danger">{job.errorMessage}</p>}
            <p className={job?.errorMessage ? 'mt-1' : ''}>The charge was returned to your balance.</p>
          </div>
        )}
      </div>

      {!current && (
        <div className="mt-4 flex aspect-[4/3] items-center justify-center rounded-md border border-dashed border-border-strong p-6 text-center text-sm text-fg-faint">
          Your {words.many} will appear here.
        </div>
      )}

      {running && (
        <>
          {modality === 'VIDEO' && (
            <p className="mt-3 text-sm text-fg-muted">
              Videos usually take 1–5 minutes. It's safe to leave this page: the result will also be
              in <Link to="/history" className="text-brand-600 hover:underline">History</Link>.
            </p>
          )}
          <div aria-hidden="true" className="mt-4 aspect-[4/3] animate-pulse rounded-md bg-bg-muted" />
        </>
      )}

      {current?.status === 'DONE' && job && <JobOutputs job={job} className="mt-4" />}

      {current && (isTerminal(current.status) || current.gone) && (
        <div className="mt-4 flex flex-wrap gap-2">
          {prompt && <CopyButton text={prompt} label="Copy prompt" />}
          {job && !current.gone && <SaveJobButton job={job} onChange={onJobChange} />}
          <Link to={`/history/${current.id}`} className="btn btn-ghost btn-sm">Open in History</Link>
        </div>
      )}
    </>
  );
}
