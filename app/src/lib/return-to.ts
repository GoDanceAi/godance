/**
 * Where a visitor goes once signed in.
 *
 * A destination only ever comes from the route the visitor was actually on:
 * ProtectedRoute and VerifyEmailPage hand it to /login in navigation state,
 * and the sign-in and sign-up pages pass it to each other the same way. It is
 * never read from the query string, so a crafted link can't choose where
 * anyone lands after signing in.
 *
 * A social sign-in leaves the app and comes back with a full page load, which
 * navigation state doesn't survive. For that trip alone the destination waits
 * in this tab's sessionStorage, as the sign-in nonce does (lib/oauth). The
 * callback takes it on every visit, so it is used at most once, and follows
 * it only after a round trip this tab started, when it is still a path inside
 * the app and was kept less than 30 minutes ago.
 */

const KEPT_KEY = 'godance.return_to';
const MAX_AGE_MS = 30 * 60 * 1000;
// A clock that steps back a little while the visitor is away is not tampering.
const CLOCK_SKEW_MS = 60 * 1000;

/**
 * A path inside the app: it starts with exactly one slash (`//host` and `/\host`
 * name another host), holds no backslash or control character a URL parser
 * could fold into one, and resolves to this origin.
 */
export function isAppPath(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 2000) return false;
  if (!value.startsWith('/') || value.startsWith('//')) return false;
  if (/[\\\u0000-\u001f\u007f]/.test(value)) return false;
  try {
    return new URL(value, window.location.origin).origin === window.location.origin;
  } catch {
    return false;
  }
}

/** The destination in a page's navigation state, when it is a path inside the app. */
export function destinationFrom(state: unknown): string | null {
  const from = typeof state === 'object' && state !== null ? (state as { from?: unknown }).from : undefined;
  return isAppPath(from) ? from : null;
}

/** Navigation state that hands `from` on to the next sign-in page, or none. */
export function carryDestination(from: string | null): { from: string } | undefined {
  return from ? { from } : undefined;
}

/**
 * Called just before a social sign-in leaves the app: keeps `from` for the
 * way back, or, with no destination, forgets one an abandoned attempt kept.
 */
export function keepForSocialSignIn(from: string | null): void {
  try {
    if (from !== null && isAppPath(from)) {
      sessionStorage.setItem(KEPT_KEY, JSON.stringify({ path: from, savedAt: Date.now() }));
    } else {
      sessionStorage.removeItem(KEPT_KEY);
    }
  } catch {
    // Storage blocked: the sign-in still works and lands on the dashboard.
  }
}

/** The destination kept for a social sign-in. It is removed on reading, whether or not it is still good. */
export function takeKeptDestination(now = Date.now()): string | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(KEPT_KEY);
    sessionStorage.removeItem(KEPT_KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;
  try {
    const kept: unknown = JSON.parse(raw);
    if (typeof kept !== 'object' || kept === null) return null;
    const { path, savedAt } = kept as { path?: unknown; savedAt?: unknown };
    if (typeof savedAt !== 'number' || !Number.isFinite(savedAt)) return null;
    if (savedAt > now + CLOCK_SKEW_MS || now - savedAt > MAX_AGE_MS) return null;
    return isAppPath(path) ? path : null;
  } catch {
    return null;
  }
}
