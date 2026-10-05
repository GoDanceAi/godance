import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ApiError,
  apiFetch,
  type AsyncJobAcknowledged,
  type GenerationJob,
  type ModelEntry,
  type ModelListResponse,
} from '../lib/api';
import { useAuth } from '../lib/auth';

type MediaKind = 'IMAGE' | 'VIDEO';

const TERMINAL_STATUSES = new Set<GenerationJob['status']>(['DONE', 'FAILED']);

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      window.clearTimeout(timer);
      resolve();
    }, { once: true });
  });
}

function dollarsFromMicrocents(value: number): string {
  return new Intl.NumberFormat('en-CA', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: value < 100_000 ? 3 : 2,
    maximumFractionDigits: 3,
  }).format(value / 1_000_000);
}

function priceLabel(model: ModelEntry): string {
  if (model.godance.modality === 'IMAGE') {
    const price = model.godance.pricing.per_image_microcents;
    return price == null ? 'Price unavailable' : `${dollarsFromMicrocents(price)} / image`;
  }
  const price = model.godance.pricing.per_second_microcents;
  return price == null ? 'Price unavailable' : `${dollarsFromMicrocents(price)} / second`;
}

function statusLabel(status: GenerationJob['status']): string {
  switch (status) {
    case 'QUEUED': return 'Queued';
    case 'RUNNING': return 'Generating';
    case 'DONE': return 'Ready';
    case 'FAILED': return 'Failed';
  }
}

export default function GeneratePage() {
  const { refresh } = useAuth();
  const [kind, setKind] = useState<MediaKind>('IMAGE');
  const [models, setModels] = useState<ModelEntry[]>([]);
  const [modelId, setModelId] = useState('');
  const [prompt, setPrompt] = useState('');
  const [videoSeconds, setVideoSeconds] = useState('');
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [job, setJob] = useState<GenerationJob | null>(null);
  const pollController = useRef<AbortController | null>(null);

  const availableModels = useMemo(
    () => models.filter((model) => model.godance.modality === kind),
    [kind, models],
  );
  const selectedModel = availableModels.find((model) => model.id === modelId) ?? null;

  useEffect(() => {
    let cancelled = false;
    apiFetch<ModelListResponse>('/v1/models', { auth: false })
      .then((response) => {
        if (cancelled) return;
        setModels(response.data.filter((model) =>
          model.godance.modality === 'IMAGE' || model.godance.modality === 'VIDEO'));
        setCatalogError(null);
      })
      .catch((cause) => {
        if (cancelled) return;
        setCatalogError(cause instanceof ApiError ? cause.message : 'Could not load the model catalog.');
      })
      .finally(() => {
        if (!cancelled) setCatalogLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!availableModels.some((model) => model.id === modelId)) {
      setModelId(availableModels[0]?.id ?? '');
    }
  }, [availableModels, modelId]);

  useEffect(() => () => pollController.current?.abort(), []);

  const switchKind = (next: MediaKind) => {
    if (next === kind || submitting) return;
    pollController.current?.abort();
    setKind(next);
    setJob(null);
    setError(null);
  };

  const pollJob = async (jobId: string, signal: AbortSignal) => {
    while (!signal.aborted) {
      const latest = await apiFetch<GenerationJob>(`/v1/jobs/${jobId}`, { signal });
      setJob(latest);
      if (TERMINAL_STATUSES.has(latest.status)) return;
      await wait(2_000, signal);
    }
  };

  const generate = async () => {
    if (!prompt.trim() || !modelId || submitting) return;

    pollController.current?.abort();
    const controller = new AbortController();
    pollController.current = controller;
    setSubmitting(true);
    setError(null);
    setJob(null);

    try {
      const path = kind === 'IMAGE' ? '/v1/images/generations' : '/v1/videos/generations';
      const body = kind === 'IMAGE'
        ? { model: modelId, prompt: prompt.trim(), n: 1, async: true }
        : {
            model: modelId,
            prompt: prompt.trim(),
            n: 1,
            ...(videoSeconds ? { seconds: Number(videoSeconds) } : {}),
          };
      const acknowledged = await apiFetch<AsyncJobAcknowledged>(path, {
        method: 'POST',
        json: body,
        signal: controller.signal,
      });
      setJob({
        id: acknowledged.job_id,
        status: acknowledged.status,
        modality: kind,
        provider: selectedModel?.owned_by ?? '',
        model: modelId,
        estimatedCostCents: null,
        actualCostCents: null,
        outputUrl: null,
        errorMessage: null,
        createdAt: new Date().toISOString(),
        startedAt: null,
        completedAt: null,
      });
      await pollJob(acknowledged.job_id, controller.signal);
    } catch (cause) {
      if ((cause as Error).name !== 'AbortError') {
        setError(cause instanceof ApiError ? cause.detail || cause.message : 'Generation failed.');
      }
    } finally {
      if (!controller.signal.aborted) {
        setSubmitting(false);
        void refresh();
      }
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void generate();
  };

  return (
    <div className="mx-auto max-w-6xl px-6 py-10">
      <div className="max-w-2xl">
        <p className="text-xs font-semibold uppercase tracking-wider text-brand-600">Media workspace</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight text-fg">Create with any media model</h1>
        <p className="mt-2 text-sm text-fg-muted">
          Pick a live model, describe what you need, and keep the provider details visible.
        </p>
      </div>

      <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <form onSubmit={onSubmit} className="card space-y-5 self-start">
          <fieldset disabled={submitting}>
            <legend className="sr-only">Media type</legend>
            <div className="grid grid-cols-2 rounded-md border border-border-strong p-1">
              {(['IMAGE', 'VIDEO'] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => switchKind(value)}
                  className={`rounded-sm px-4 py-2 text-sm font-semibold transition-colors ${
                    kind === value ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'
                  }`}
                >
                  {value === 'IMAGE' ? 'Image' : 'Video'}
                </button>
              ))}
            </div>
          </fieldset>

          <div>
            <label htmlFor="media-model" className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
              Model
            </label>
            <select
              id="media-model"
              value={modelId}
              onChange={(event) => setModelId(event.target.value)}
              disabled={catalogLoading || submitting || availableModels.length === 0}
              className="input mt-2"
            >
              {catalogLoading && <option value="">Loading models…</option>}
              {!catalogLoading && availableModels.length === 0 && <option value="">No models available</option>}
              {availableModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.id} · {model.owned_by}
                </option>
              ))}
            </select>
            {selectedModel && (
              <div className="mt-2 flex items-center justify-between gap-3 text-xs text-fg-faint">
                <span>{selectedModel.owned_by}</span>
                <span>{priceLabel(selectedModel)}</span>
              </div>
            )}
            {catalogError && <p className="mt-2 text-sm text-danger">{catalogError}</p>}
          </div>

          <div>
            <label htmlFor="media-prompt" className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
              Prompt
            </label>
            <textarea
              id="media-prompt"
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              disabled={submitting}
              rows={7}
              maxLength={4_000}
              placeholder={kind === 'IMAGE'
                ? 'A quiet Toronto street after rain, cinematic light…'
                : 'A slow aerial move over a coastal village at sunrise…'}
              className="input mt-2 resize-y"
            />
            <p className="mt-1 text-right text-xs text-fg-faint">{prompt.length} / 4,000</p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {kind === 'IMAGE' ? (
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Outputs
                </p>
                <div className="input mt-2 text-fg-muted">1 image</div>
              </div>
            ) : (
              <div>
                <label htmlFor="video-duration" className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Duration
                </label>
                <select
                  id="video-duration"
                  value={videoSeconds}
                  onChange={(event) => setVideoSeconds(event.target.value)}
                  disabled={submitting}
                  className="input mt-2"
                >
                  <option value="">Provider default</option>
                  <option value="5">5 seconds</option>
                  <option value="10">10 seconds</option>
                </select>
              </div>
            )}
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">Format</p>
              <div className="input mt-2 text-fg-muted">Provider default</div>
            </div>
          </div>

          <div className="rounded-md border border-dashed border-border-strong bg-bg-muted p-4">
            <p className="text-sm font-medium text-fg">Reference images</p>
            <p className="mt-1 text-xs leading-relaxed text-fg-faint">
              Not available yet. The current generation API does not expose reference-image uploads or per-model support.
            </p>
          </div>

          {error && <p role="alert" className="text-sm text-danger">{error}</p>}

          <button
            type="submit"
            disabled={!prompt.trim() || !modelId || submitting}
            className="btn btn-primary w-full"
          >
            {submitting ? 'Generating…' : `Generate ${kind === 'IMAGE' ? 'image' : 'video'}`}
          </button>
        </form>

        <section className="card min-h-[32rem] flex flex-col" aria-live="polite">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">Output</p>
              <h2 className="mt-1 text-lg font-semibold text-fg">Latest generation</h2>
            </div>
            {job && (
              <span className={`pill ${job.status === 'DONE' ? 'pill-emerald' : job.status === 'FAILED' ? 'pill-rose' : 'pill-violet'}`}>
                {statusLabel(job.status)}
              </span>
            )}
          </div>

          {!job && (
            <div className="flex flex-1 items-center justify-center py-20 text-center">
              <div className="max-w-sm">
                <div className="mx-auto h-16 w-16 rounded-xl border border-dashed border-border-strong bg-bg-muted" />
                <p className="mt-5 font-medium text-fg">Your generation will appear here</p>
                <p className="mt-1 text-sm text-fg-faint">Choose a model and submit a prompt to start.</p>
              </div>
            </div>
          )}

          {job && (job.status === 'QUEUED' || job.status === 'RUNNING') && (
            <div className="flex flex-1 items-center justify-center py-20 text-center">
              <div>
                <div className="mx-auto h-12 w-12 animate-pulse rounded-full bg-brand-100" />
                <p className="mt-5 font-medium text-fg">{statusLabel(job.status)}</p>
                <p className="mt-1 text-sm text-fg-faint">This page updates automatically.</p>
              </div>
            </div>
          )}

          {job?.status === 'DONE' && job.outputUrl && (
            <div className="mt-5 flex flex-1 flex-col">
              <div className="flex flex-1 items-center justify-center overflow-hidden rounded-md bg-bg-muted">
                {job.modality === 'IMAGE' ? (
                  <img src={job.outputUrl} alt={prompt} className="max-h-[34rem] w-full object-contain" />
                ) : (
                  <video src={job.outputUrl} controls className="max-h-[34rem] w-full" />
                )}
              </div>
              <div className="mt-4 flex items-center justify-between gap-4 text-xs text-fg-faint">
                <span>{job.model}{job.actualCostCents != null ? ` · $${(job.actualCostCents / 100).toFixed(2)}` : ''}</span>
                <a href={job.outputUrl} target="_blank" rel="noreferrer" className="btn btn-secondary btn-sm">
                  Open original
                </a>
              </div>
            </div>
          )}

          {job?.status === 'DONE' && !job.outputUrl && (
            <div className="mt-5 flex flex-1 items-center justify-center text-sm text-fg-muted">
              The job completed without a downloadable output.
            </div>
          )}

          {job?.status === 'FAILED' && (
            <div className="flex flex-1 items-center justify-center py-20 text-center">
              <div className="max-w-md">
                <p className="font-medium text-danger">Generation failed</p>
                <p className="mt-2 text-sm text-fg-muted">{job.errorMessage || 'The provider did not return an output.'}</p>
                <button type="button" onClick={() => void generate()} className="btn btn-secondary mt-5">
                  Try again
                </button>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
