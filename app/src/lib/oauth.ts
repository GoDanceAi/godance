import { API_BASE } from './api';

// A fresh random nonce per sign-in, kept in this tab's sessionStorage and sent
// to the backend, which stores it with the OAuth authorization request and
// echoes it back as `#login_nonce=` (OAuthLoginNonce, OAuthLoginSuccessHandler).
// OAuthCallbackPage accepts a token or an error only when the echo matches, so
// a crafted link to /oauth/callback#access_token=… or #error=… is refused:
// whoever wrote the link can't know this tab's nonce.
const NONCE_KEY = 'godance.oauth_nonce';

export const OAUTH_GENERIC_ERROR = 'Sign-in was cancelled or failed';

/**
 * Hitting `${API_BASE}/oauth2/authorization/{id}` starts the provider
 * round-trip; the backend success handler (OAuthLoginSuccessHandler) sends
 * the browser back to `/oauth/callback#access_token=…` or `#error=<reason>`,
 * plus `&login_nonce=<nonce>`.
 */
export function startOAuth(id: string) {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const nonce = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  try {
    sessionStorage.setItem(NONCE_KEY, nonce);
  } catch {
    // Storage blocked: the callback can't match the nonce and refuses the sign-in.
  }
  window.location.href = `${API_BASE}/oauth2/authorization/${id}?login_nonce=${nonce}`;
}

/** Whether `received` is the nonce this tab sent. Clears it either way, so each nonce works once. */
export function consumeLoginNonce(received: string | null): boolean {
  try {
    const expected = sessionStorage.getItem(NONCE_KEY);
    sessionStorage.removeItem(NONCE_KEY);
    return expected !== null && received === expected;
  } catch {
    return false;
  }
}
