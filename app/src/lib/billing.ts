import { apiFetch } from './api';

/** Parse decimal dollars using integer operations; server bounds remain authoritative. */
export function parseUsdCents(text: string): number | null {
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) return null;
  const [whole, fraction = ''] = text.split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  return cents <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(cents) : null;
}

export function centsInput(cents: number): string {
  const integer = BigInt(cents);
  return `${integer / 100n}.${String(integer % 100n).padStart(2, '0')}`;
}

/** Preserve the last cent even at the safe-integer boundary in review copy. */
export function formatUsdCents(cents: number): string {
  if (!Number.isSafeInteger(cents)) return 'Unavailable';
  const integer = BigInt(cents);
  const positive = integer < 0n ? -integer : integer;
  return `${integer < 0n ? '-' : ''}$${(positive / 100n).toLocaleString('en-US')}.${String(positive % 100n).padStart(2, '0')}`;
}

export interface AutoRecharge {
  enabled: boolean;
  threshold_cents: number | null;
  topup_cents: number | null;
  payment_method_on_file: boolean;
  last_failure_at: string | null;
  last_failure_reason: string | null;
}

function checkedAutoRecharge(value: AutoRecharge): AutoRecharge {
  const amountValid = (amount: unknown) => amount === null || (typeof amount === 'number' && Number.isSafeInteger(amount) && amount >= 0);
  if (!value || typeof value.enabled !== 'boolean' || typeof value.payment_method_on_file !== 'boolean'
      || !amountValid(value.threshold_cents) || !amountValid(value.topup_cents)) {
    throw new Error('Auto-recharge status is unavailable. Refresh status before making changes.');
  }
  return value;
}

export async function getAutoRecharge(signal?: AbortSignal): Promise<AutoRecharge> {
  return checkedAutoRecharge(await apiFetch('/v1/billing/auto-recharge', { signal }));
}

export async function setAutoRecharge(config: { enabled: boolean; threshold_cents?: number; topup_cents?: number }): Promise<AutoRecharge> {
  return checkedAutoRecharge(await apiFetch('/v1/billing/auto-recharge', { method: 'PUT', json: config }));
}

export interface CustomerUsage {
  id: string;
  recordedAt: string;
  model: string;
  modality: string;
  inputTokens: number;
  outputTokens: number;
  costCents: number;
  cacheHit: boolean;
  fallbackUsed: boolean;
}

function safeCount(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new Error('Usage contains an invalid amount or count. Please refresh later.');
  }
  return value;
}

/** Explicit allowlist: discard internal provider cost and every unlisted field before view/export state. */
export function customerUsage(row: Record<string, unknown>): CustomerUsage {
  return {
    id: String(row.id), recordedAt: String(row.recordedAt), model: String(row.model), modality: String(row.modality),
    inputTokens: safeCount(row.inputTokens), outputTokens: safeCount(row.outputTokens), costCents: safeCount(row.costCents),
    cacheHit: row.cacheHit === true, fallbackUsed: row.fallbackUsed === true,
  };
}

export async function getRecentUsage(signal?: AbortSignal): Promise<CustomerUsage[]> {
  return (await apiFetch<Record<string, unknown>[]>('/v1/usage?limit=200', { signal })).map(customerUsage);
}

// DRAFT: operator review of this single consent source is required before rollout.
export const DRAFT_AUTO_RECHARGE_CONSENT =
  'By confirming, you authorize Godance to charge your saved card off-session (while you are not present) whenever your balance falls below the threshold shown, adding the recharge amount shown. You can disable auto-recharge here.';
