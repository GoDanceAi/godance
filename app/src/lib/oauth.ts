import { API_BASE } from './api';

// Marks an OAuth round-trip this tab started. sessionStorage is scoped to the
// tab and origin, so a link opened from an email or another site can't carry
// it; OAuthCallbackPage only trusts a `#error=` reason when it is present.
const PENDING_KEY = 'godance.oauth_pending';
const PENDING_MAX_AGE_MS = 10 * 60 * 1000;

export const OAUTH_GENERIC_ERROR = 'Sign-in was cancelled or failed';

/**
 * Hitting `${API_BASE}/oauth2/authorization/{id}` starts the provider
 * round-trip; the backend success handler (OAuthLoginSuccessHandler) sends
 * the browser back to `/oauth/callback#access_token=…` or `#error=<reason>`.
 */
export function startOAuth(id: string) {
  try {
    sessionStorage.setItem(PENDING_KEY, String(Date.now()));
  } catch {
    // Storage blocked: the callback falls back to the generic error message.
  }
  window.location.href = `${API_BASE}/oauth2/authorization/${id}`;
}

/** Whether this tab recently started a sign-in. Clears the marker either way. */
export function consumeOAuthPending(): boolean {
  try {
    const startedAt = Number(sessionStorage.getItem(PENDING_KEY));
    sessionStorage.removeItem(PENDING_KEY);
    return startedAt > 0 && Date.now() - startedAt < PENDING_MAX_AGE_MS;
  } catch {
    return false;
  }
}
