import { apiFetch } from './api';

/**
 * Bring your own key (round 14): a provider key of the user's own, as
 * `GET /v1/provider-credentials` lists it. Never the key itself: `last4` is
 * all of it the API ever returns.
 */
export interface ProviderCredential {
  provider: string;
  last4: string | null;
  /**
   * `valid`, `invalid` (the provider refused it), `unchecked`,
   * `unsupported_adapter` (stored, but no request uses it yet),
   * `not_supported` (stored for a provider no longer offered) or `none`.
   */
  status: 'valid' | 'invalid' | 'unchecked' | 'unsupported_adapter' | 'not_supported' | 'none';
  fallback_to_platform: boolean;
  /** What requests to this provider use the key for: `chat`, `images`. */
  used_for: string[];
  checked_at: string | null;
  /** Godance's fee on a request on the key, in basis points of the list price: 500 is 5%. */
  fee_basis_points: number;
}

export const listProviderKeys = () => apiFetch<ProviderCredential[]>('/v1/provider-credentials');

/** Stores a key once the provider accepts it; `apiKey` null changes only the fallback setting. */
export const saveProviderKey = (provider: string, apiKey: string | null, fallbackToPlatform: boolean) =>
  apiFetch<ProviderCredential>(`/v1/provider-credentials/${encodeURIComponent(provider)}`, {
    method: 'PUT',
    json: apiKey === null
      ? { fallback_to_platform: fallbackToPlatform }
      : { api_key: apiKey, fallback_to_platform: fallbackToPlatform },
  });

export const removeProviderKey = (provider: string) =>
  apiFetch<void>(`/v1/provider-credentials/${encodeURIComponent(provider)}`, { method: 'DELETE' });

/** A provider's name as people know it. */
export function providerName(provider: string): string {
  const names: Record<string, string> = { OPENAI: 'OpenAI', ANTHROPIC: 'Anthropic' };
  return names[provider] ?? provider.charAt(0) + provider.slice(1).toLowerCase().replace(/_/g, ' ');
}

/** Basis points as a percent, without a trailing zero: 500 → "5%", 250 → "2.5%". */
export function feePercent(basisPoints: number): string {
  return `${(basisPoints / 100).toString()}%`;
}
