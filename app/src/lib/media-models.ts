import {
  apiFetch,
  isVirtualModel,
  type MediaModality,
  type ModelEntry,
  type ModelListResponse,
} from './api';

// The image and video models in /v1/models, and what each one offers.

let catalog: Promise<ModelListResponse> | null = null;

/**
 * GET /v1/models, asked once per page load and shared by every page that
 * needs it. A failed answer is not kept, so the next caller asks again.
 */
export function loadCatalog(): Promise<ModelListResponse> {
  catalog ??= apiFetch<ModelListResponse>('/v1/models', { auth: false }).catch((err: unknown) => {
    catalog = null;
    throw err;
  });
  return catalog;
}

/** The concrete models of one kind. Routing presets have no price and are skipped. */
export function mediaModels(list: ModelListResponse, modality: MediaModality): ModelEntry[] {
  return list.data.filter((m): m is ModelEntry => !isVirtualModel(m) && m.godance.modality === modality);
}

export function modelName(m: ModelEntry): string {
  return m.godance.display_name?.trim() || m.id;
}

/** List price per image or per second of video, in microcents (1¢ = 10,000). */
function listMicrocents(m: ModelEntry): number {
  const p = m.godance.pricing;
  return (m.godance.modality === 'VIDEO' ? p.per_second_microcents : p.per_image_microcents) ?? 0;
}

/** Price per image or per second including the platform fee, in dollars; null when the fee is not published. */
export function unitPrice(m: ModelEntry): number | null {
  const pct = m.godance.markup_percent;
  const micro = listMicrocents(m);
  if (typeof pct !== 'number' || !Number.isFinite(pct) || micro <= 0) return null;
  return (micro * (1 + pct / 100)) / 1_000_000;
}

// As the marketing site prints model prices: sub-cent prices keep their digits.
function formatUsd(n: number): string {
  const decimals = n < 0.1 ? 4 : n < 10 ? 3 : 2;
  return '$' + n.toFixed(decimals).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
}

/** "$0.0336 per image", "$0.0966 per second"; null when unknown. */
export function priceLabel(m: ModelEntry): string | null {
  const usd = unitPrice(m);
  if (usd === null) return null;
  return `${formatUsd(usd)} per ${m.godance.modality === 'VIDEO' ? 'second' : 'image'}`;
}

function sortPrice(m: ModelEntry): number {
  return unitPrice(m) ?? listMicrocents(m) / 1_000_000;
}

/** The cheapest model; the first listed on a tie. */
export function cheapest(models: ModelEntry[]): ModelEntry | undefined {
  return models.reduce<ModelEntry | undefined>(
    (best, m) => (best === undefined || sortPrice(m) < sortPrice(best) ? m : best), undefined);
}

const PROVIDER_NAMES: Record<string, string> = {
  'alibaba-dashscope': 'Alibaba',
  bfl: 'Black Forest Labs',
  'google-vertex': 'Google',
  kling: 'Kling',
  minimax: 'MiniMax',
  openai: 'OpenAI',
  runway: 'Runway',
  stability: 'Stability AI',
  'volcengine-ark': 'ByteDance',
  xai: 'xAI',
};

export interface ProviderGroup {
  provider: string;
  name: string;
  models: ModelEntry[];
}

/** Models grouped by provider, providers by name, models cheapest first. */
export function groupByProvider(models: ModelEntry[]): ProviderGroup[] {
  const groups = new Map<string, ModelEntry[]>();
  for (const m of models) groups.set(m.owned_by, [...(groups.get(m.owned_by) ?? []), m]);
  return [...groups.entries()]
    .map(([provider, ms]) => ({
      provider,
      name: PROVIDER_NAMES[provider] ?? provider,
      models: [...ms].sort((a, b) => sortPrice(a) - sortPrice(b)),
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The controls a model offers, from its capabilities. With none published,
 * nothing is offered and nothing beyond the model and prompt is sent: absent
 * means unknown, not unlimited.
 */
export interface Controls {
  sizes: string[] | null;
  defaultSize: string | null;
  durations: number[] | null;     // video only
  defaultSeconds: number | null;
  maxN: number | null;            // only when above 1: a count control
}

const NO_CONTROLS: Controls = { sizes: null, defaultSize: null, durations: null, defaultSeconds: null, maxN: null };

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string' && v !== '') : [];
}

function positiveInts(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((v): v is number => Number.isInteger(v) && v > 0) : [];
}

export function controlsFor(m: ModelEntry | undefined, modality: MediaModality): Controls {
  const caps = m?.godance.capabilities;
  if (!caps) return NO_CONTROLS;
  const sizes = strings(caps.sizes);
  const durations = modality === 'VIDEO' ? positiveInts(caps.durations) : [];
  const maxN = Number.isInteger(caps.max_n) && (caps.max_n as number) > 1 ? caps.max_n as number : null;
  return {
    sizes: sizes.length ? sizes : null,
    defaultSize: sizes.length ? (sizes.includes(caps.default_size ?? '') ? caps.default_size! : sizes[0]) : null,
    durations: durations.length ? durations : null,
    defaultSeconds: durations.length
      ? (durations.includes(caps.default_seconds ?? 0) ? caps.default_seconds! : durations[0])
      : null,
    maxN,
  };
}
