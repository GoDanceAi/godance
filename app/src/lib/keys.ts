import { apiFetch, ApiError, type ApiKeySettings, type ApiKeySummary, type CreatedApiKey } from './api';

// API keys: the calls. What a person types about a key's limits, and how
// they read, is in lib/key-limits.ts.

export * from './key-limits';

/** GET /v1/keys: the caller's keys. Never a plaintext key. */
export function listKeys(): Promise<ApiKeySummary[]> {
  return apiFetch<ApiKeySummary[]>('/v1/keys');
}

/** POST /v1/keys. The answer holds the key itself, this once. */
export function createKey(body: { name: string } & ApiKeySettings): Promise<CreatedApiKey> {
  return apiFetch<CreatedApiKey>('/v1/keys', { method: 'POST', json: body });
}

/** PATCH /v1/keys/{id}: only the fields sent change. Answers the key. */
export function updateKey(id: string, settings: ApiKeySettings): Promise<ApiKeySummary> {
  return apiFetch<ApiKeySummary>(`/v1/keys/${encodeURIComponent(id)}`, { method: 'PATCH', json: settings });
}

/** DELETE /v1/keys/{id}: requests made with the key answer 401 from then on. */
export function revokeKey(id: string): Promise<void> {
  return apiFetch<void>(`/v1/keys/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** What the API said went wrong, or `fallback` when it said nothing a person can read. */
export function keyError(err: unknown, fallback: string): string {
  return err instanceof ApiError && !err.message.startsWith('HTTP ') ? err.detail || err.message : fallback;
}
