import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { TOKEN_KEY } from '../lib/api';
import { useAuth } from '../lib/auth';

/**
 * Landing route for the OAuth2 round-trip. The backend success handler
 * (OAuthLoginSuccessHandler) mints the standard Godance JWT and redirects
 * here with the token in the URL *fragment*:
 *   /oauth/callback#access_token=<jwt>&token_type=Bearer&expires_at=<iso>
 * We store the token exactly as the email flow does, hydrate /me, and hand
 * off to the dashboard.
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

    if (token) {
      localStorage.setItem(TOKEN_KEY, token);
      // Scrub the token out of the address bar / history before continuing.
      window.history.replaceState(null, '', window.location.pathname);
      void refresh().then(() => navigate('/dashboard', { replace: true }));
    } else {
      const reason = error ?? 'Sign-in was cancelled or failed';
      navigate(`/login?oauth_error=${encodeURIComponent(reason)}`, { replace: true });
    }
  }, [navigate, refresh]);

  return (
    <div className="mx-auto max-w-sm px-6 py-16 text-center">
      <p className="text-sm text-fg-muted">Completing sign-in…</p>
    </div>
  );
}
