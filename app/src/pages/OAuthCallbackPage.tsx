import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { consumeLoginNonce, OAUTH_GENERIC_ERROR } from '../lib/oauth';

/**
 * Landing route for the OAuth2 round-trip. The backend success handler
 * (OAuthLoginSuccessHandler) puts the standard Godance JWT in the session
 * cookie and redirects here:
 *   /oauth/callback#token_type=Cookie&login_nonce=<nonce>
 * We hydrate /me and hand off to the dashboard. An account with 2FA on has no
 * cookie yet and arrives as `#two_factor_token=<challenge>`, and a refused
 * sign-in as `#error=<reason>`; both are handed to the login page in
 * navigation state. Any of them is accepted only when `login_nonce` is the
 * one this tab sent (see lib/oauth). A sign-in started from an app bundle
 * older than the cookie has the cookie too, plus `#access_token=`, which
 * nothing here reads.
 */
export default function OAuthCallbackPage() {
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;   // StrictMode double-invoke guard
    handled.current = true;

    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const query = new URLSearchParams(window.location.search);
    const twoFactorToken = fragment.get('two_factor_token');
    const error = fragment.get('error') ?? query.get('error');
    // Anyone can link to /oauth/callback with an error of their choosing;
    // only a round-trip this tab started carries its nonce.
    const nonceMatches = consumeLoginNonce(fragment.get('login_nonce'));

    if (twoFactorToken && nonceMatches) {
      navigate('/login', { replace: true, state: { twoFactorToken } });
    } else if (nonceMatches && !error) {
      // Scrub the fragment out of the address bar / history before continuing.
      window.history.replaceState(null, '', window.location.pathname);
      void refresh().then(() => navigate('/dashboard', { replace: true }));
    } else {
      // Replacing the history entry also drops the fragment from the address bar.
      let reason = OAUTH_GENERIC_ERROR;
      if (fragment.has('token_type') || twoFactorToken) {
        reason = "Sign-in couldn't be verified because it wasn't started in this tab. Please try again.";
      } else if (error && nonceMatches) {
        reason = error;
      }
      navigate('/login', { replace: true, state: { oauthError: reason } });
    }
  }, [navigate, refresh]);

  return (
    <div className="mx-auto max-w-sm px-6 py-16 text-center">
      <p className="text-sm text-fg-muted">Completing sign-in…</p>
    </div>
  );
}
