import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { TOKEN_KEY } from '../lib/api';
import { useAuth } from '../lib/auth';
import { consumeLoginNonce, OAUTH_GENERIC_ERROR } from '../lib/oauth';

/**
 * Landing route for the OAuth2 round-trip. The backend success handler
 * (OAuthLoginSuccessHandler) mints the standard Godance JWT and redirects
 * here with the token in the URL *fragment*:
 *   /oauth/callback#access_token=<jwt>&token_type=Bearer&expires_at=<iso>&login_nonce=<nonce>
 * We store the token exactly as the email flow does, hydrate /me, and hand
 * off to the dashboard. A refused sign-in arrives as `#error=<reason>` and
 * is handed to the login page in navigation state. Either is accepted only
 * when `login_nonce` is the one this tab sent (see lib/oauth).
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
    const token = fragment.get('access_token');
    const error = fragment.get('error') ?? query.get('error');
    // Anyone can link to /oauth/callback with a token or error of their
    // choosing; only a round-trip this tab started carries its nonce.
    const nonceMatches = consumeLoginNonce(fragment.get('login_nonce'));

    if (token && nonceMatches) {
      localStorage.setItem(TOKEN_KEY, token);
      // Scrub the token out of the address bar / history before continuing.
      window.history.replaceState(null, '', window.location.pathname);
      void refresh().then(() => navigate('/dashboard', { replace: true }));
    } else {
      // A token without this tab's nonce wasn't requested here: never store
      // it. Replacing the history entry also drops it from the address bar.
      let reason = OAUTH_GENERIC_ERROR;
      if (token) {
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
