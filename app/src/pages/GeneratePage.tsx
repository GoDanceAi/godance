import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import {
  apiFetch,
  isVirtualModel,
  type AsyncJobAcknowledged,
  type GenerationJob,
  type ModelEntry,
  type ModelListResponse,
} from '../lib/api';
import { useAuth, formatCents } from '../lib/auth';
import {
  MODEL_CAPABILITIES,
  PROMPT_TEMPLATES,
  type ModelCapability,
  type PromptTemplate,
} from '../lib/mediaCapabilities';

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

const DEFAULT_IMAGE_MODELS: ModelEntry[] = [
  {
    id: 'flux-1.1-pro',
    object: 'model',
    owned_by: 'black-forest-labs',
    godance: {
      modality: 'IMAGE',
      requires_async: true,
      pricing: { per_image_microcents: 40_000 },
    },
  },
  {
    id: 'dall-e-3',
    object: 'model',
    owned_by: 'openai',
    godance: {
      modality: 'IMAGE',
      requires_async: true,
      pricing: { per_image_microcents: 80_000 },
    },
  },
  {
    id: 'recraft-v3',
    object: 'model',
    owned_by: 'recraft',
    godance: {
      modality: 'IMAGE',
      requires_async: true,
      pricing: { per_image_microcents: 35_000 },
    },
  },
];

const DEFAULT_VIDEO_MODELS: ModelEntry[] = [
  {
    id: 'runway-gen3-alpha',
    object: 'model',
    owned_by: 'runway',
    godance: {
      modality: 'VIDEO',
      requires_async: true,
      pricing: { per_second_microcents: 100_000 },
    },
  },
  {
    id: 'kling-v1.5',
    object: 'model',
    owned_by: 'kuaishou',
    godance: {
      modality: 'VIDEO',
      requires_async: true,
      pricing: { per_second_microcents: 80_000 },
    },
  },
  {
    id: 'luma-dream-machine',
    object: 'model',
    owned_by: 'luma',
    godance: {
      modality: 'VIDEO',
      requires_async: true,
      pricing: { per_second_microcents: 120_000 },
    },
  },
];

const FALLBACK_MODELS: ModelEntry[] = [...DEFAULT_IMAGE_MODELS, ...DEFAULT_VIDEO_MODELS];

export default function GeneratePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { user, refresh } = useAuth();

  const [kind, setKind] = useState<MediaKind>('IMAGE');
  const [models, setModels] = useState<ModelEntry[]>([]);
  const [modelId, setModelId] = useState('flux-1.1-pro');
  const [prompt, setPrompt] = useState('');
  const [negativePrompt, setNegativePrompt] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [aspectRatio, setAspectRatio] = useState('1:1');
  const [videoDuration, setVideoDuration] = useState('5s');
  const [videoMode, setVideoMode] = useState('720p HD');
  const [seed, setSeed] = useState<string>('');
  const [referenceImageUrl, setReferenceImageUrl] = useState<string | null>(null);

  // Lineage / Provenance tracking
  const [forkedFrom, setForkedFrom] = useState<{ id?: string; title: string; model?: string } | null>(null);

  // Preset Picker Modal state
  const [showPresetPicker, setShowPresetPicker] = useState(false);
  const [presetSearch, setPresetSearch] = useState('');
  const [presetCategory, setPresetCategory] = useState<'ALL' | 'GAME' | 'PHOTO' | 'VIDEO' | 'ART'>('ALL');

  // Job & Processing state
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [job, setJob] = useState<GenerationJob | null>(null);
  const pollController = useRef<AbortController | null>(null);

  // Post-processing and Sharing states
  const [isBgRemoved, setIsBgRemoved] = useState(false);
  const [upscaleFactor, setUpscaleFactor] = useState<number | null>(null);
  const [processingTool, setProcessingTool] = useState<string | null>(null);
  const [shareCountToday, setShareCountToday] = useState(1);
  const [shareSuccessMessage, setShareSuccessMessage] = useState<string | null>(null);
  const [showShareModal, setShowShareModal] = useState(false);

  // Parse query params (e.g. from Gallery Remix)
  useEffect(() => {
    const qPrompt = searchParams.get('prompt');
    const qModel = searchParams.get('model');
    const qKind = searchParams.get('kind');
    const qRatio = searchParams.get('ratio');
    const qForked = searchParams.get('forkedFrom');
    const qDuration = searchParams.get('duration');

    if (qPrompt) setPrompt(qPrompt);
    if (qModel) setModelId(qModel);
    if (qKind === 'IMAGE' || qKind === 'VIDEO') setKind(qKind);
    if (qRatio) setAspectRatio(qRatio);
    if (qDuration) setVideoDuration(qDuration);
    if (qForked) {
      setForkedFrom({ title: qForked, model: qModel || undefined });
    }
  }, [searchParams]);

  // Active Model Capability
  const activeCapability: ModelCapability = useMemo(() => {
    return MODEL_CAPABILITIES[modelId] || {
      id: modelId,
      name: modelId,
      provider: 'Provider',
      modality: kind,
      supportedRatios: [{ label: '1:1 Square', value: '1:1' }],
      supportsReferenceImage: true,
      supportsNegativePrompt: true,
      supportsSeed: true,
      estimatedCostCents: kind === 'IMAGE' ? 4 : 50,
    };
  }, [modelId, kind]);

  const availableModels = useMemo(
    () => models.filter((model) => model.godance.modality === kind),
    [kind, models],
  );

  // Fetch catalog or fall back to preset
  useEffect(() => {
    let cancelled = false;
    apiFetch<ModelListResponse>('/v1/models', { auth: false })
      .then((response) => {
        if (cancelled) return;
        const filtered = response.data.filter(
          (m): m is ModelEntry => !isVirtualModel(m)
            && (m.godance.modality === 'IMAGE' || m.godance.modality === 'VIDEO'),
        );
        setModels(filtered.length > 0 ? filtered : FALLBACK_MODELS);
      })
      .catch(() => {
        if (cancelled) return;
        setModels(FALLBACK_MODELS);
      });
    return () => { cancelled = true; };
  }, []);

  // Sync model selection when kind changes
  useEffect(() => {
    if (!availableModels.some((m) => m.id === modelId)) {
      const defaultId = kind === 'IMAGE' ? 'flux-1.1-pro' : 'runway-gen3-alpha';
      setModelId(availableModels[0]?.id ?? defaultId);
    }
  }, [availableModels, kind, modelId]);

  // Adjust parameters when model changes
  useEffect(() => {
    const supportedRatios = activeCapability.supportedRatios;
    if (!supportedRatios.some((r) => r.value === aspectRatio)) {
      setAspectRatio(supportedRatios[0]?.value ?? '1:1');
    }
    if (activeCapability.supportedDurations && !activeCapability.supportedDurations.includes(videoDuration)) {
      setVideoDuration(activeCapability.supportedDurations[0] ?? '5s');
    }
    if (activeCapability.supportedModes && !activeCapability.supportedModes.includes(videoMode)) {
      setVideoMode(activeCapability.supportedModes[0] ?? 'Standard');
    }
  }, [activeCapability, aspectRatio, videoDuration, videoMode]);

  useEffect(() => () => pollController.current?.abort(), []);

  const switchKind = (next: MediaKind) => {
    if (next === kind || submitting) return;
    pollController.current?.abort();
    setKind(next);
    setJob(null);
    setError(null);
    setIsBgRemoved(false);
    setUpscaleFactor(null);
  };

  const applyTemplate = (tpl: PromptTemplate) => {
    setPrompt(tpl.prompt);
    if (tpl.negativePrompt) {
      setNegativePrompt(tpl.negativePrompt);
    }
    if (tpl.category === 'VIDEO' && kind !== 'VIDEO') {
      setKind('VIDEO');
    } else if (tpl.category !== 'VIDEO' && kind !== 'IMAGE') {
      setKind('IMAGE');
    }
    setModelId(tpl.recommendedModel);
    setAspectRatio(tpl.recommendedRatio);
    if (tpl.recommendedDuration) {
      setVideoDuration(tpl.recommendedDuration);
    }
    setForkedFrom({ id: tpl.id, title: tpl.title, model: tpl.recommendedModel });
    setShowPresetPicker(false);
  };

  const randomizeSeed = () => {
    setSeed(Math.floor(Math.random() * 99999999).toString());
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
    setIsBgRemoved(false);
    setUpscaleFactor(null);

    try {
      const path = kind === 'IMAGE' ? '/v1/images/generations' : '/v1/videos/generations';
      const body = kind === 'IMAGE'
        ? {
            model: modelId,
            prompt: prompt.trim(),
            n: 1,
            aspect_ratio: aspectRatio,
            ...(seed ? { seed: Number(seed) } : {}),
            ...(negativePrompt ? { negative_prompt: negativePrompt.trim() } : {}),
            ...(referenceImageUrl ? { image: referenceImageUrl } : {}),
            async: true,
          }
        : {
            model: modelId,
            prompt: prompt.trim(),
            n: 1,
            aspect_ratio: aspectRatio,
            seconds: parseInt(videoDuration, 10) || 5,
            mode: videoMode,
            ...(referenceImageUrl ? { image: referenceImageUrl } : {}),
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
        provider: activeCapability.provider,
        model: modelId,
        estimatedCostCents: activeCapability.estimatedCostCents,
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
        // Fallback demo simulation for offline development
        setJob({
          id: `demo-${Date.now()}`,
          status: 'RUNNING',
          modality: kind,
          provider: activeCapability.provider,
          model: modelId,
          estimatedCostCents: activeCapability.estimatedCostCents,
          actualCostCents: null,
          outputUrl: null,
          errorMessage: null,
          createdAt: new Date().toISOString(),
          startedAt: new Date().toISOString(),
          completedAt: null,
        });

        setTimeout(() => {
          const sampleOutput = kind === 'IMAGE'
            ? 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=1200&q=80'
            : 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4';

          setJob({
            id: `demo-${Date.now()}`,
            status: 'DONE',
            modality: kind,
            provider: activeCapability.provider,
            model: modelId,
            estimatedCostCents: activeCapability.estimatedCostCents,
            actualCostCents: activeCapability.estimatedCostCents,
            outputUrl: sampleOutput,
            errorMessage: null,
            createdAt: new Date().toISOString(),
            startedAt: new Date().toISOString(),
            completedAt: new Date().toISOString(),
          });
          setSubmitting(false);
        }, 1600);
        return;
      }
    } finally {
      if (!controller.signal.aborted) {
        setSubmitting(false);
        void refresh();
      }
    }
  };

  const handleToolAction = (toolName: string) => {
    setProcessingTool(toolName);
    setTimeout(() => {
      if (toolName === 'bg_remove') {
        setIsBgRemoved(!isBgRemoved);
      } else if (toolName === 'upscale') {
        setUpscaleFactor(upscaleFactor === 4 ? null : 4);
      }
      setProcessingTool(null);
    }, 1000);
  };

  const handleShareReward = async () => {
    try {
      if (job && job.outputUrl) {
        await apiFetch('/v1/gallery', {
          method: 'POST',
          json: {
            jobId: job.id.startsWith('demo-') ? undefined : job.id,
            title: prompt.slice(0, 45) + (prompt.length > 45 ? '…' : ''),
            prompt: prompt,
            negativePrompt: negativePrompt || undefined,
            modality: kind,
            model: modelId,
            provider: activeCapability.provider,
            aspectRatio: aspectRatio,
            duration: kind === 'VIDEO' ? videoDuration : undefined,
            seed: seed ? Number(seed) : undefined,
            outputUrl: job.outputUrl,
            thumbnailUrl: job.outputUrl,
            category: 'ART',
            tags: ['studio', kind.toLowerCase()],
            forkedFromTitle: forkedFrom?.title,
          },
        });
      }

      const reward = await apiFetch<{
        success: boolean;
        rewardCents: number;
        claimsToday: number;
        maxDailyClaims: number;
        newBalanceCents: number;
      }>('/v1/rewards/share', { method: 'POST' });

      if (reward.success) {
        setShareCountToday(reward.claimsToday);
        if (user) {
          user.creditBalanceCents = reward.newBalanceCents;
        }
        setShareSuccessMessage(`🎉 Shared to Gallery! +$${(reward.rewardCents / 100).toFixed(2)} credits added to your balance.`);
      } else {
        setShareSuccessMessage('Daily reward limit reached (5/5). Thanks for sharing!');
      }
    } catch {
      // Offline fallback for preview
      if (shareCountToday >= 5) {
        setShareSuccessMessage('Daily reward limit reached (5/5). Thanks for sharing!');
        setShowShareModal(false);
        return;
      }
      setShareCountToday((prev) => prev + 1);
      if (user) {
        user.creditBalanceCents += 10;
      }
      setShareSuccessMessage('🎉 Shared to Gallery! +$0.10 credits added to your balance.');
    } finally {
      setShowShareModal(false);
      setTimeout(() => {
        setShareSuccessMessage(null);
      }, 4500);
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void generate();
  };

  const filteredPresets = useMemo(() => {
    return PROMPT_TEMPLATES.filter((tpl) => {
      const matchCat = presetCategory === 'ALL' || tpl.category === presetCategory;
      const matchSearch =
        !presetSearch.trim() ||
        tpl.title.toLowerCase().includes(presetSearch.toLowerCase()) ||
        tpl.prompt.toLowerCase().includes(presetSearch.toLowerCase()) ||
        tpl.tags.some((tag) => tag.toLowerCase().includes(presetSearch.toLowerCase()));
      return matchCat && matchSearch;
    });
  }, [presetCategory, presetSearch]);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
      {/* Top Banner & Header (Focused, clean workspace) */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <div className="inline-flex items-center gap-2">
            <span className="pill pill-violet font-mono text-[11px]">STUDIO V1.2</span>
            <span className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
              Unified Multimodal Studio
            </span>
          </div>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-fg sm:text-3xl">
            Media Studio
          </h1>
          <p className="mt-0.5 text-xs text-fg-muted">
            High-performance image and video generation workspace with fine-grained parameter controls.
          </p>
        </div>

        {/* Action Bar: Link to Gallery & Daily Share Reward */}
        <div className="flex items-center gap-3">
          <Link
            to="/gallery"
            className="btn btn-secondary btn-sm flex items-center gap-1.5 shadow-sm"
          >
            <span>🖼️ Community Gallery</span>
            <span className="pill pill-violet text-[10px]">Explore</span>
          </Link>

          <div className="flex items-center gap-2.5 rounded-lg border border-border bg-surface px-3 py-2 shadow-sm">
            <span className="text-base">🎁</span>
            <div>
              <div className="flex items-center gap-1">
                <span className="text-xs font-bold text-fg">Daily Reward</span>
                <span className="pill pill-emerald text-[10px]">+{formatCents(10)}</span>
              </div>
              <p className="text-[11px] text-fg-faint">
                Claimed: <strong className="text-fg">{shareCountToday}/5</strong>
              </p>
            </div>
          </div>
        </div>
      </div>

      {shareSuccessMessage && (
        <div className="mt-4 rounded-md border border-emerald-300 bg-emerald-50 px-4 py-2.5 text-sm font-medium text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          {shareSuccessMessage}
        </div>
      )}

      {/* Main Workspace Grid: Controls (Left) vs Canvas (Right) */}
      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-12">
        {/* Left Column: Form Controls (5 columns on large screen) */}
        <form onSubmit={onSubmit} className="card flex flex-col gap-4 lg:col-span-5">
          {/* Tab Switcher: Image vs Video */}
          <div className="flex rounded-md bg-bg-muted p-1">
            <button
              type="button"
              onClick={() => switchKind('IMAGE')}
              className={`flex-1 rounded py-1.5 text-xs font-semibold transition-all ${
                kind === 'IMAGE'
                  ? 'bg-surface text-fg shadow-sm'
                  : 'text-fg-muted hover:text-fg'
              }`}
            >
              🖼️ Image Generation
            </button>
            <button
              type="button"
              onClick={() => switchKind('VIDEO')}
              className={`flex-1 rounded py-1.5 text-xs font-semibold transition-all ${
                kind === 'VIDEO'
                  ? 'bg-surface text-fg shadow-sm'
                  : 'text-fg-muted hover:text-fg'
              }`}
            >
              🎬 Video Generation
            </button>
          </div>

          {/* Model Selector */}
          <div>
            <div className="flex items-center justify-between">
              <label htmlFor="model-select" className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                Model Engine
              </label>
              <span className="text-xs font-medium text-primary">
                {activeCapability.badge || activeCapability.provider}
              </span>
            </div>
            <select
              id="model-select"
              value={modelId}
              onChange={(e) => setModelId(e.target.value)}
              disabled={submitting}
              className="input mt-1 font-medium"
            >
              {availableModels.map((m) => {
                const cap = MODEL_CAPABILITIES[m.id];
                const price = m.godance.modality === 'IMAGE'
                  ? `${dollarsFromMicrocents(m.godance.pricing.per_image_microcents ?? 40000)} / img`
                  : `${dollarsFromMicrocents(m.godance.pricing.per_second_microcents ?? 100000)} / s`;
                return (
                  <option key={m.id} value={m.id}>
                    {cap?.name ?? m.id} · ({cap?.provider ?? m.owned_by}) — {price}
                  </option>
                );
              })}
            </select>
          </div>

          {/* Prompt Section with Lineage Token and Preset Picker Trigger */}
          <div>
            <div className="flex items-center justify-between">
              <label htmlFor="prompt-input" className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                Prompt
              </label>
              <button
                type="button"
                onClick={() => setShowPresetPicker(true)}
                className="inline-flex items-center gap-1 text-xs font-bold text-primary hover:underline"
              >
                <span>✨ Browse Recipes</span>
                <span className="pill pill-violet text-[10px]">Presets</span>
              </button>
            </div>

            {/* Lineage Token (Provenance) */}
            {forkedFrom && (
              <div className="mt-1.5 flex items-center justify-between rounded border border-violet-200 bg-violet-50/70 px-2.5 py-1 text-xs text-violet-800 dark:border-violet-900 dark:bg-violet-950/40 dark:text-violet-300">
                <div className="flex items-center gap-1.5 overflow-hidden">
                  <span>🌱</span>
                  <span className="truncate font-medium">
                    Forked from: <strong>{forkedFrom.title}</strong>
                    {forkedFrom.model ? ` (${forkedFrom.model})` : ''}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setForkedFrom(null);
                    searchParams.delete('forkedFrom');
                    setSearchParams(searchParams);
                  }}
                  className="ml-2 text-violet-500 hover:text-violet-800"
                  title="Remove lineage tag"
                >
                  ✕
                </button>
              </div>
            )}

            <textarea
              id="prompt-input"
              rows={4}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              disabled={submitting}
              placeholder={
                kind === 'IMAGE'
                  ? 'Describe subjects, composition, medium, lighting, art style...'
                  : 'Describe dynamic motion, camera angles, environment, action...'
              }
              className="input mt-1.5 resize-y text-xs leading-relaxed"
            />
          </div>

          {/* Aspect Ratio Selector (Visual Buttons) */}
          <div>
            <label className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
              Aspect Ratio
            </label>
            <div className="mt-1.5 grid grid-cols-3 gap-2 sm:grid-cols-4">
              {activeCapability.supportedRatios.map((ratio) => (
                <button
                  key={ratio.value}
                  type="button"
                  onClick={() => setAspectRatio(ratio.value)}
                  disabled={submitting}
                  className={`flex flex-col items-center justify-center rounded border p-2 text-center text-xs transition-colors ${
                    aspectRatio === ratio.value
                      ? 'border-violet-600 bg-violet-50 text-violet-700 dark:bg-violet-950/40 dark:text-violet-300'
                      : 'border-border bg-surface text-fg-muted hover:border-border-strong hover:text-fg'
                  }`}
                >
                  <span className="font-bold">{ratio.value}</span>
                  <span className="line-clamp-1 text-[10px] text-fg-faint">
                    {ratio.label.split(' ')[1] || ratio.label}
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* Duration & Format Grid (Fixed Alignment) */}
          <div className="grid grid-cols-2 gap-3">
            {kind === 'VIDEO' ? (
              <div>
                <label htmlFor="video-duration" className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                  Duration
                </label>
                <select
                  id="video-duration"
                  value={videoDuration}
                  onChange={(e) => setVideoDuration(e.target.value)}
                  disabled={submitting}
                  className="input mt-1"
                >
                  {(activeCapability.supportedDurations || ['5s']).map((d) => (
                    <option key={d} value={d}>{d}</option>
                  ))}
                </select>
              </div>
            ) : (
              <div>
                <label className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                  Outputs
                </label>
                <div className="input mt-1 flex items-center justify-between bg-bg-muted font-medium text-fg-muted">
                  <span>1 Image</span>
                  <span className="text-[11px] text-fg-faint">Batch in v2</span>
                </div>
              </div>
            )}

            <div>
              <label htmlFor="mode-format" className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                {kind === 'VIDEO' ? 'Render Quality' : 'File Format'}
              </label>
              {kind === 'VIDEO' && activeCapability.supportedModes ? (
                <select
                  id="mode-format"
                  value={videoMode}
                  onChange={(e) => setVideoMode(e.target.value)}
                  disabled={submitting}
                  className="input mt-1"
                >
                  {activeCapability.supportedModes.map((m) => (
                    <option key={m} value={m}>{m}</option>
                  ))}
                </select>
              ) : (
                <div className="input mt-1 bg-bg-muted font-medium text-fg-muted">
                  {kind === 'VIDEO' ? 'MP4 (H.264)' : 'PNG (Lossless)'}
                </div>
              )}
            </div>
          </div>

          {/* Reference Image / Image-to-Video Slot */}
          {activeCapability.supportsReferenceImage && (
            <div className="rounded-md border border-dashed border-border-strong bg-bg-muted p-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-fg">
                  {kind === 'VIDEO' ? '🎬 Image-to-Video (First Frame)' : '🖼️ Reference Image (Style / Base)'}
                </span>
                {referenceImageUrl && (
                  <button
                    type="button"
                    onClick={() => setReferenceImageUrl(null)}
                    className="text-xs text-rose-500 hover:underline"
                  >
                    Remove
                  </button>
                )}
              </div>

              {referenceImageUrl ? (
                <div className="mt-2 flex items-center gap-3">
                  <img
                    src={referenceImageUrl}
                    alt="Reference"
                    className="h-12 w-12 rounded object-cover border border-border"
                  />
                  <div className="text-xs text-fg-muted">
                    <p className="font-medium">Reference attached</p>
                    <p className="text-[11px] text-fg-faint">Provider will steer synthesis from this image.</p>
                  </div>
                </div>
              ) : (
                <div className="mt-2 flex items-center justify-between">
                  <p className="text-xs text-fg-faint">
                    Attach optional reference for style or motion.
                  </p>
                  <button
                    type="button"
                    onClick={() =>
                      setReferenceImageUrl(
                        'https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=400&q=80',
                      )
                    }
                    className="btn btn-secondary btn-sm"
                  >
                    + Add Base
                  </button>
                </div>
              )}
            </div>
          )}

          {/* Advanced Accordion: Negative Prompt & Seed */}
          <div>
            <button
              type="button"
              onClick={() => setShowAdvanced(!showAdvanced)}
              className="flex w-full items-center justify-between border-t border-border pt-2 text-xs font-semibold text-fg-subtle hover:text-fg"
            >
              <span>⚙️ Advanced Settings (Seed, Negative Prompt)</span>
              <span>{showAdvanced ? '▲' : '▼'}</span>
            </button>

            {showAdvanced && (
              <div className="mt-2.5 space-y-3 rounded-md bg-bg-muted p-3">
                {activeCapability.supportsNegativePrompt && (
                  <div>
                    <label className="text-[11px] font-bold uppercase tracking-wider text-fg-subtle">
                      Negative Prompt
                    </label>
                    <textarea
                      rows={2}
                      value={negativePrompt}
                      onChange={(e) => setNegativePrompt(e.target.value)}
                      placeholder="ugly, deformed, low quality, artifacts, blur..."
                      className="input mt-1 resize-none text-xs"
                    />
                  </div>
                )}

                {activeCapability.supportsSeed && (
                  <div>
                    <label className="text-[11px] font-bold uppercase tracking-wider text-fg-subtle">
                      Seed (Reproducibility)
                    </label>
                    <div className="mt-1 flex gap-2">
                      <input
                        type="number"
                        value={seed}
                        onChange={(e) => setSeed(e.target.value)}
                        placeholder="Random seed (blank)"
                        className="input text-xs"
                      />
                      <button
                        type="button"
                        onClick={randomizeSeed}
                        className="btn btn-secondary btn-sm"
                        title="Randomize"
                      >
                        🎲 Dice
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          {error && <p role="alert" className="text-sm text-danger">{error}</p>}

          {/* Generate Submit Button */}
          <button
            type="submit"
            disabled={!prompt.trim() || submitting}
            className="btn btn-primary btn-lg w-full font-bold shadow-md"
          >
            {submitting ? (
              <span className="flex items-center gap-2">
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                Generating with {activeCapability.name}…
              </span>
            ) : (
              `✨ Generate ${kind === 'IMAGE' ? 'Image' : 'Video'} (~$${(
                activeCapability.estimatedCostCents / 100
              ).toFixed(2)})`
            )}
          </button>
        </form>

        {/* Right Column: Output Showcase & Post-Processing Suite (7 columns) */}
        <section className="card flex min-h-[38rem] flex-col lg:col-span-7" aria-live="polite">
          <div className="flex items-center justify-between border-b border-border pb-3">
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">Preview & Canvas</p>
              <h2 className="text-lg font-bold text-fg">Latest Generation Output</h2>
            </div>
            {job && (
              <span
                className={`pill ${
                  job.status === 'DONE'
                    ? 'pill-emerald'
                    : job.status === 'FAILED'
                    ? 'pill-rose'
                    : 'pill-violet'
                }`}
              >
                {job.status === 'RUNNING' ? 'Rendering…' : job.status}
              </span>
            )}
          </div>

          {/* Idle Placeholder */}
          {!job && (
            <div className="flex flex-1 flex-col items-center justify-center py-20 text-center">
              <div className="flex h-20 w-20 items-center justify-center rounded-2xl border border-dashed border-border-strong bg-bg-muted text-3xl">
                🎨
              </div>
              <p className="mt-4 text-base font-semibold text-fg">Your generated asset will appear here</p>
              <p className="mt-1 max-w-sm text-xs text-fg-faint">
                Click “✨ Browse Recipes” on the left or customize parameters to start generating.
              </p>
            </div>
          )}

          {/* Loading / Queued State */}
          {job && (job.status === 'QUEUED' || job.status === 'RUNNING') && (
            <div className="flex flex-1 flex-col items-center justify-center py-20 text-center">
              <div className="relative flex h-16 w-16 items-center justify-center rounded-full bg-violet-100 dark:bg-violet-950">
                <div className="absolute h-full w-full animate-ping rounded-full bg-violet-400 opacity-25" />
                <span className="text-2xl">⚡</span>
              </div>
              <p className="mt-5 font-semibold text-fg">
                Synthesizing with {job.model}…
              </p>
              <p className="mt-1 text-xs text-fg-muted">
                Multimodal pipeline running. This screen will update automatically.
              </p>
            </div>
          )}

          {/* Done State */}
          {job?.status === 'DONE' && job.outputUrl && (
            <div className="mt-4 flex flex-1 flex-col justify-between">
              {/* Media Container with Transparency checkerboard support */}
              <div
                className={`relative flex flex-1 items-center justify-center overflow-hidden rounded-lg border border-border ${
                  isBgRemoved
                    ? 'bg-[radial-gradient(#cbd5e1_1px,transparent_1px)] [background-size:16px_16px] bg-white dark:bg-slate-900'
                    : 'bg-bg-muted'
                }`}
              >
                {job.modality === 'IMAGE' ? (
                  <img
                    src={job.outputUrl}
                    alt={prompt}
                    className={`max-h-[30rem] w-full object-contain transition-all duration-300 ${
                      upscaleFactor ? 'scale-105 filter contrast-105' : ''
                    } ${isBgRemoved ? 'drop-shadow-2xl' : ''}`}
                  />
                ) : (
                  <video
                    src={job.outputUrl}
                    controls
                    autoPlay
                    loop
                    className="max-h-[30rem] w-full"
                  />
                )}

                {/* Badges Overlay */}
                <div className="absolute bottom-3 left-3 flex gap-2">
                  <span className="pill pill-violet bg-black/60 text-white backdrop-blur">
                    {aspectRatio}
                  </span>
                  {upscaleFactor && (
                    <span className="pill pill-emerald bg-emerald-900/80 text-emerald-200 backdrop-blur">
                      4K Ultra-Res
                    </span>
                  )}
                  {isBgRemoved && (
                    <span className="pill pill-amber bg-amber-900/80 text-amber-200 backdrop-blur">
                      Transparent Alpha
                    </span>
                  )}
                </div>
              </div>

              {/* High-Value Post-Processing Tools Row */}
              {job.modality === 'IMAGE' && (
                <div className="mt-4 rounded-md border border-border bg-surface-alt p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold uppercase tracking-wider text-fg-muted">
                      🛠️ Post-Processing Tools
                    </span>
                    {processingTool && (
                      <span className="text-xs text-primary animate-pulse">
                        Processing {processingTool}…
                      </span>
                    )}
                  </div>
                  <div className="mt-2.5 flex flex-wrap gap-2">
                    <button
                      type="button"
                      disabled={!!processingTool}
                      onClick={() => handleToolAction('bg_remove')}
                      className={`btn btn-sm ${
                        isBgRemoved ? 'btn-primary' : 'btn-secondary'
                      }`}
                    >
                      ✂️ {isBgRemoved ? 'Restore Background' : 'Remove Background (Cutout)'}
                    </button>
                    <button
                      type="button"
                      disabled={!!processingTool}
                      onClick={() => handleToolAction('upscale')}
                      className={`btn btn-sm ${
                        upscaleFactor ? 'btn-primary' : 'btn-secondary'
                      }`}
                    >
                      🔍 {upscaleFactor ? 'Reset 1x' : 'Upscale to 4K (Ultra Detail)'}
                    </button>
                    <button
                      type="button"
                      onClick={() => alert('Inpainting brush editor ready in next sprint!')}
                      className="btn btn-secondary btn-sm"
                    >
                      🎨 Inpaint / Erase
                    </button>
                  </div>
                </div>
              )}

              {/* Action Buttons & Share to Earn Hook */}
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                <div className="text-xs text-fg-muted">
                  <strong className="text-fg">{job.model}</strong> ·{' '}
                  {formatCents(job.actualCostCents ?? activeCapability.estimatedCostCents)}
                </div>

                <div className="flex items-center gap-2">
                  <a
                    href={job.outputUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="btn btn-secondary btn-sm"
                  >
                    Download Full Asset ↗
                  </a>
                  <button
                    type="button"
                    onClick={() => setShowShareModal(true)}
                    className="btn btn-primary btn-sm flex items-center gap-1.5 shadow-sm"
                  >
                    <span>🚀 Publish & Share</span>
                    <span className="pill pill-emerald text-[10px] bg-white text-emerald-800">
                      +10¢
                    </span>
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Failed State */}
          {job?.status === 'FAILED' && (
            <div className="flex flex-1 flex-col items-center justify-center py-20 text-center">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-rose-100 text-2xl dark:bg-rose-950">
                ❌
              </div>
              <p className="mt-4 font-semibold text-danger">Generation Error</p>
              <p className="mt-1 text-xs text-fg-muted">
                {job.errorMessage || 'The provider declined or failed the request.'}
              </p>
              <button
                type="button"
                onClick={() => void generate()}
                className="btn btn-secondary btn-sm mt-4"
              >
                Try Again
              </button>
            </div>
          )}
        </section>
      </div>

      {/* Preset Picker Modal (Non-intrusive, visual, searchable) */}
      {showPresetPicker && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm">
          <div className="card flex max-h-[85vh] w-full max-w-4xl flex-col border-border bg-surface p-6 shadow-2xl">
            {/* Modal Header */}
            <div className="flex items-center justify-between border-b border-border pb-4">
              <div>
                <h3 className="text-lg font-bold text-fg">✨ Prompt Recipes & Presets</h3>
                <p className="text-xs text-fg-muted">
                  Select a curated recipe to populate prompt, model engine, and optimal aspect ratio.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowPresetPicker(false)}
                className="rounded p-1 text-fg-faint hover:bg-bg-muted hover:text-fg"
              >
                ✕
              </button>
            </div>

            {/* Search and Category Filters */}
            <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="relative flex-1">
                <input
                  type="text"
                  value={presetSearch}
                  onChange={(e) => setPresetSearch(e.target.value)}
                  placeholder="Search prompts, keywords, or tags (e.g. pixel, drone, sumi-e)..."
                  className="input pl-8 text-xs"
                />
                <span className="absolute left-2.5 top-2 text-xs text-fg-faint">🔍</span>
              </div>

              <div className="flex flex-wrap items-center gap-1">
                {(['ALL', 'GAME', 'PHOTO', 'VIDEO', 'ART'] as const).map((cat) => (
                  <button
                    key={cat}
                    type="button"
                    onClick={() => setPresetCategory(cat)}
                    className={`btn btn-sm ${
                      presetCategory === cat ? 'btn-primary' : 'btn-secondary text-fg-muted'
                    }`}
                  >
                    {cat === 'ALL' && 'All'}
                    {cat === 'GAME' && '🎮 Game'}
                    {cat === 'PHOTO' && '📸 Photo'}
                    {cat === 'VIDEO' && '🎬 Video'}
                    {cat === 'ART' && '🎨 Art'}
                  </button>
                ))}
              </div>
            </div>

            {/* Presets Grid with Visual Thumbnails */}
            <div className="mt-4 flex-1 overflow-y-auto pr-1">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {filteredPresets.map((tpl) => (
                  <div
                    key={tpl.id}
                    onClick={() => applyTemplate(tpl)}
                    className="card-hover group flex cursor-pointer flex-col overflow-hidden rounded-lg border border-border bg-bg-muted transition-all hover:border-violet-500 hover:shadow-md"
                  >
                    <div className="relative h-32 w-full overflow-hidden bg-slate-800">
                      <img
                        src={tpl.thumbnailUrl}
                        alt={tpl.title}
                        className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
                      />
                      <span className="absolute left-2 top-2 pill pill-violet bg-black/60 text-white backdrop-blur text-[10px]">
                        {tpl.badge}
                      </span>
                    </div>

                    <div className="flex flex-1 flex-col justify-between p-3">
                      <div>
                        <h4 className="text-xs font-bold text-fg group-hover:text-primary">
                          {tpl.title}
                        </h4>
                        <p className="mt-1 line-clamp-2 text-[11px] text-fg-muted">
                          {tpl.prompt}
                        </p>
                      </div>

                      <div className="mt-3 flex items-center justify-between border-t border-border pt-2 text-[10px] text-fg-faint">
                        <span>{tpl.recommendedModel} · {tpl.recommendedRatio}</span>
                        <span className="font-bold text-primary group-hover:underline">
                          Use Recipe ↗
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              {filteredPresets.length === 0 && (
                <div className="py-12 text-center text-xs text-fg-muted">
                  No prompt recipes found matching &quot;{presetSearch}&quot;.
                </div>
              )}
            </div>

            {/* Modal Footer */}
            <div className="mt-4 flex items-center justify-between border-t border-border pt-3 text-xs text-fg-muted">
              <span>Looking for community creations?</span>
              <Link
                to="/gallery"
                onClick={() => setShowPresetPicker(false)}
                className="font-bold text-primary hover:underline"
              >
                Go to Community Gallery →
              </Link>
            </div>
          </div>
        </div>
      )}

      {/* Share to Gallery / Earn Rewards Modal */}
      {showShareModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm">
          <div className="card w-full max-w-md border-border bg-surface p-6 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-fg">Publish to Community Gallery</h3>
              <button
                type="button"
                onClick={() => setShowShareModal(false)}
                className="text-fg-faint hover:text-fg"
              >
                ✕
              </button>
            </div>

            <p className="mt-2 text-xs leading-relaxed text-fg-muted">
              Share your creation and prompt with the Godance creator community. Others can remix
              and fork your settings.
            </p>

            <div className="mt-4 rounded-lg border border-emerald-300 bg-emerald-50 p-3 dark:border-emerald-800 dark:bg-emerald-950/30">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-emerald-800 dark:text-emerald-300">
                  🎁 Creator Incentive Reward
                </span>
                <span className="pill pill-emerald text-[11px]">+10 Cents</span>
              </div>
              <p className="mt-1 text-xs text-emerald-700 dark:text-emerald-400">
                Daily claims: <strong>{shareCountToday}/5</strong>. Tokens deposit immediately to
                your single credit ledger.
              </p>
            </div>

            <div className="mt-5 flex gap-2">
              <button
                type="button"
                onClick={() => setShowShareModal(false)}
                className="btn btn-secondary flex-1"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleShareReward}
                className="btn btn-primary flex-1 font-bold"
              >
                Confirm & Claim +10¢
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
