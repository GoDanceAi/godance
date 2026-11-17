import { apiFetch } from './api';

export interface AdminFeatures {
  inputs: { enabled: boolean; allow_people: boolean };
  community: { enabled: boolean };
  byok: { enabled: boolean };
  routing_policies: { enabled: boolean; allow_list_restricted: boolean };
  referrals: { enabled: boolean };
  mcp: { enabled: boolean };
}

/** Refuse incomplete/malformed data instead of supplying optimistic defaults. */
export async function getAdminFeatures(signal: AbortSignal): Promise<AdminFeatures> {
  const response = await apiFetch<unknown>('/v1/admin/features', { signal });
  const group = (name: string): Record<string, unknown> => {
    if (!response || typeof response !== 'object') throw new Error('Invalid feature configuration');
    const value = (response as Record<string, unknown>)[name];
    if (!value || typeof value !== 'object') throw new Error('Invalid feature configuration');
    return value as Record<string, unknown>;
  };
  const boolean = (name: string, field = 'enabled'): boolean => {
    const value = group(name)[field];
    if (typeof value !== 'boolean') throw new Error('Incomplete feature configuration');
    return value;
  };
  // Explicit field allowlist: retain neither unknown payload fields nor raw properties.
  return {
    inputs: { enabled: boolean('inputs'), allow_people: boolean('inputs', 'allow_people') },
    community: { enabled: boolean('community') },
    byok: { enabled: boolean('byok') },
    routing_policies: {
      enabled: boolean('routing_policies'),
      allow_list_restricted: boolean('routing_policies', 'allow_list_restricted'),
    },
    referrals: { enabled: boolean('referrals') },
    mcp: { enabled: boolean('mcp') },
  };
}
