import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { TOKEN_KEY } from '../lib/api';
import { useAuth } from '../lib/auth';

/**
 * Landing route for the OAuth2 round-trip. The backend success handler mints
 * the standard Godance JWT and redirects here as `?token=<jwt>` (or
 * `?error=<reason>` on failure). We store the token exactly as the email flow
 * does, hydrate /me, and hand off to the dashboard.
 */
export default function OAuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;   // StrictMode double-invoke guard
    handled.current = true;

    const token = params.get('token');
    const error = params.get('error');
    if (token) {
      localStorage.setItem(TOKEN_KEY, token);
      void refresh().then(() => navigate('/dashboard', { replace: true }));
    } else {
      const reason = error ?? 'Sign-in was cancelled or failed';
      navigate(`/login?oauth_error=${encodeURIComponent(reason)}`, { replace: true });
    }
  }, [params, navigate, refresh]);

  return (
    <div className="mx-auto max-w-sm px-6 py-16 text-center">
      <p className="text-sm text-fg-muted">Completing sign-in…</p>
    </div>
  );
}
