// Build-time model catalog. Fetches GET /v1/models from the backend so the
// /models and /pricing pages always reflect the live catalog. Falls back to a
// static snapshot when the API is unreachable (offline dev / CI without net),
// so a build never breaks on a transient backend outage.

const API_BASE: string = import.meta.env.PUBLIC_API_BASE ?? 'http://localhost:8080';

export type Modality = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO_TTS' | 'AUDIO_STT' | 'EMBEDDING';

interface ModelPricing {
  input_microcents_per_1k?: number;
  output_microcents_per_1k?: number;
  per_image_microcents?: number;
  per_second_microcents?: number;
}
interface ModelEntry {
  id: string;
  owned_by: string;
  // virtual: routing presets (godance-fast/auto/quality), which have no pricing.
  // markup_percent: the per-model platform fee billed on top of pricing.
  // Absent on backends that predate it.
  godance: {
    modality: Modality; requires_async: boolean; pricing: ModelPricing;
    markup_percent?: number; virtual?: boolean;
  };
}
interface ModelListResponse { object: 'list'; data: ModelEntry[] }

export interface CatalogRow {
  id: string;
  provider: string;
  modality: Modality;
  requiresAsync: boolean;
  unit: string;          // long form, e.g. "per 1M tokens (input / output)"
  unitShort: string;     // table suffix, e.g. "per 1M"
  listPrice: string;     // provider list price
  fee: string;           // platform fee for this model, e.g. "12%"
  godancePrice: string;  // list price + fee
}

export interface CatalogGroup {
  modality: Modality;
  label: string;
  rows: CatalogRow[];
}

export interface Catalog {
  rows: CatalogRow[];
  groups: CatalogGroup[];
  live: boolean;         // true if fetched from the API, false if fallback snapshot
}

const MODALITY_ORDER: { modality: Modality; label: string }[] = [
  { modality: 'TEXT', label: 'Text models' },
  { modality: 'IMAGE', label: 'Image models' },
  { modality: 'VIDEO', label: 'Video models' },
  { modality: 'AUDIO_TTS', label: 'Text-to-speech models' },
  { modality: 'AUDIO_STT', label: 'Speech-to-text models' },
  { modality: 'EMBEDDING', label: 'Embedding models' },
];

function fmtUsd(n: number): string {
  if (!isFinite(n) || n <= 0) return '—';
  const decimals = n < 0.1 ? 4 : n < 10 ? 3 : 2;
  return '$' + n.toFixed(decimals).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
}

function rowFrom(m: ModelEntry): CatalogRow {
  const p = m.godance?.pricing ?? {};
  const modality = m.godance.modality;
  // Use the fee the backend bills at. If it isn't published (older backend),
  // show "—" rather than guess: NaN formats as "—".
  const pct = m.godance.markup_percent;
  const known = typeof pct === 'number' && isFinite(pct);
  const markup = known ? 1 + pct / 100 : NaN;
  const fee = known ? `${+pct.toFixed(2)}%` : '—';
  let unit = '', unitShort = '', listPrice = '', godancePrice = '';

  if (modality === 'IMAGE') {
    const usd = (p.per_image_microcents ?? 0) / 1_000_000;
    unit = 'per image'; unitShort = 'per image';
    listPrice = fmtUsd(usd); godancePrice = fmtUsd(usd * markup);
  } else if (modality === 'VIDEO') {
    const usd = (p.per_second_microcents ?? 0) / 1_000_000;
    unit = 'per second'; unitShort = 'per second';
    listPrice = fmtUsd(usd); godancePrice = fmtUsd(usd * markup);
  } else {
    // TEXT / EMBEDDING / AUDIO — priced per-1M units via the microcents-per-1k fields.
    const inUsd = (p.input_microcents_per_1k ?? 0) / 1000;
    const outUsd = (p.output_microcents_per_1k ?? 0) / 1000;
    unitShort = 'per 1M';
    if (outUsd > 0) {
      unit = 'per 1M tokens (input / output)';
      listPrice = `${fmtUsd(inUsd)} / ${fmtUsd(outUsd)}`;
      godancePrice = `${fmtUsd(inUsd * markup)} / ${fmtUsd(outUsd * markup)}`;
    } else {
      unit = 'per 1M tokens';
      listPrice = fmtUsd(inUsd);
      godancePrice = fmtUsd(inUsd * markup);
    }
  }

  return {
    id: m.id,
    provider: m.owned_by,
    modality,
    requiresAsync: !!m.godance.requires_async,
    unit, unitShort, listPrice, fee, godancePrice,
  };
}

async function fetchModels(): Promise<ModelEntry[] | null> {
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 6000);
    const res = await fetch(`${API_BASE}/v1/models`, {
      signal: ctrl.signal,
      headers: { Accept: 'application/json' },
    });
    clearTimeout(timer);
    if (!res.ok) return null;
    const body = (await res.json()) as ModelListResponse;
    // List concrete models only. A routing preset bills at whichever model
    // serves it, so it would show up here with blank prices.
    const models = Array.isArray(body?.data)
      ? body.data.filter((m) => m.godance?.virtual !== true)
      : [];
    return models.length ? models : null;
  } catch {
    return null;
  }
}

function group(rows: CatalogRow[]): CatalogGroup[] {
  return MODALITY_ORDER
    .map(({ modality, label }) => ({
      modality,
      label,
      rows: rows
        .filter((r) => r.modality === modality)
        .sort((a, b) => a.provider.localeCompare(b.provider) || a.id.localeCompare(b.id)),
    }))
    .filter((g) => g.rows.length > 0);
}

export async function loadCatalog(): Promise<Catalog> {
  const live = await fetchModels();
  const rows = live ? live.map(rowFrom) : FALLBACK_ROWS;
  return { rows, groups: group(rows), live: !!live };
}

// Static snapshot — only used when /v1/models is unreachable at build time.
// List prices only: the per-model fee isn't known offline, so the fee and
// Godance price render as "—".
const FALLBACK_ROWS: CatalogRow[] = [
  ['gpt-4o', 'openai', 'TEXT', false, '$2.50 / $10'],
  ['gpt-4o-mini', 'openai', 'TEXT', false, '$0.15 / $0.6'],
  ['gpt-4.1', 'openai', 'TEXT', false, '$2 / $8'],
  ['claude-3-5-sonnet-20241022', 'anthropic', 'TEXT', false, '$3 / $15'],
  ['claude-3-5-haiku-20241022', 'anthropic', 'TEXT', false, '$0.8 / $4'],
  ['gemini-1.5-pro', 'google', 'TEXT', false, '$1.25 / $5'],
  ['gemini-1.5-flash', 'google', 'TEXT', false, '$0.075 / $0.3'],
  ['gpt-image-1', 'openai', 'IMAGE', false, '$0.04'],
  ['gpt-image-1-hd', 'openai', 'IMAGE', true, '$0.08'],
  ['flux-1.1-pro', 'bfl', 'IMAGE', true, '$0.04'],
  ['flux-2', 'bfl', 'IMAGE', true, '$0.05'],
  ['sora-2', 'openai', 'VIDEO', true, '$0.15'],
  ['seedance-1.0', 'volcengine', 'VIDEO', true, '$0.08'],
].map(([id, provider, modality, async_, listPrice]) => {
  const m = modality as Modality;
  const unitShort = m === 'IMAGE' ? 'per image' : m === 'VIDEO' ? 'per second' : 'per 1M';
  const unit = m === 'IMAGE' ? 'per image'
    : m === 'VIDEO' ? 'per second'
    : 'per 1M tokens (input / output)';
  return {
    id: id as string,
    provider: provider as string,
    modality: m,
    requiresAsync: async_ as boolean,
    unit, unitShort,
    listPrice: listPrice as string,
    fee: '—',
    godancePrice: '—',
  };
});
