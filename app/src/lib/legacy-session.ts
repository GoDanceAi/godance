import { API_BASE } from './api';

// One-time move for a browser signed in before the session cookie existed.
// This file is the app's only use of localStorage for auth, and it exists to
// empty it: the token goes to the API, which puts it in the HttpOnly cookie
// with the expiry it already has, and the stored copy is deleted. Delete this
// file, with POST /v1/auth/session, once no client is issued a stored token.
const LEGACY_TOKEN_KEY = 'godance.token';

export async function adoptLegacySession(): Promise<void> {
  let token: string | null;
  try {
    token = localStorage.getItem(LEGACY_TOKEN_KEY);
  } catch {
    return;   // Storage blocked: nothing can be stored there either.
  }
  if (!token) return;

  try {
    const res = await fetch(`${API_BASE}/v1/auth/session`, {
      method: 'POST',
      credentials: 'include',
      headers: { Authorization: `Bearer ${token}` },
    });
    // Moved, or refused for good (expired, revoked). A 5xx leaves it for the
    // next load, so a blip in the API doesn't sign anyone out.
    if (res.ok || (res.status >= 400 && res.status < 500)) {
      localStorage.removeItem(LEGACY_TOKEN_KEY);
    }
  } catch {
    /* Network error: the next load tries again. */
  }
}
