import { useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { ApiError, API_BASE } from '../lib/api';

// Spring Security OAuth2 Client registration ids. Hitting
// `${API_BASE}/oauth2/authorization/{id}` starts the provider round-trip; the
// backend success handler mints the standard Godance JWT and redirects to
// `/oauth/callback?token=…` (see OAuthCallbackPage).
const OAUTH_PROVIDERS: { id: string; label: string; icon: ReactNode }[] = [
  { id: 'google', label: 'Google', icon: <GoogleIcon /> },
  { id: 'apple', label: 'Apple', icon: <AppleIcon /> },
  { id: 'x', label: 'X', icon: <XIcon /> },
];

function startOAuth(id: string) {
  window.location.href = `${API_BASE}/oauth2/authorization/${id}`;
}

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/dashboard';
  const oauthError = searchParams.get('oauth_error');

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(email, password);
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Login failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-sm px-6 py-16">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Sign in</h1>
      <p className="mt-2 text-sm text-fg-muted">
        New here?{' '}
        <Link to="/signup" className="text-brand-600 hover:text-brand-700 underline">
          Create an account
        </Link>
      </p>

      <div className="mt-8 space-y-2">
        {OAUTH_PROVIDERS.map((p) => (
          <button
            key={p.id}
            type="button"
            onClick={() => startOAuth(p.id)}
            className="btn btn-secondary w-full"
          >
            {p.icon}
            <span className="ml-1">Continue with {p.label}</span>
          </button>
        ))}
      </div>

      <div className="my-6 flex items-center gap-3 text-xs text-fg-faint">
        <span className="h-px flex-1 bg-border" />
        or with email
        <span className="h-px flex-1 bg-border" />
      </div>

      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email" type="email" value={email} onChange={setEmail} autoComplete="email" required />
        <Field label="Password" type="password" value={password} onChange={setPassword} autoComplete="current-password" required />
        {(error || oauthError) && <p className="text-sm text-danger">{error ?? oauthError}</p>}
        <button type="submit" disabled={busy} className="btn btn-primary btn-lg w-full">
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}

function Field(props: {
  label: string;
  type: string;
  value: string;
  onChange: (v: string) => void;
  autoComplete?: string;
  required?: boolean;
  minLength?: number;
}) {
  return (
    <label className="block">
      <span className="text-sm font-medium text-fg">{props.label}</span>
      <input
        type={props.type}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        autoComplete={props.autoComplete}
        required={props.required}
        minLength={props.minLength}
        className="input mt-1.5"
      />
    </label>
  );
}

// --- Provider marks -------------------------------------------------------

function GoogleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 18 18" aria-hidden="true">
      <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z" />
      <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.34A9 9 0 0 0 9 18z" />
      <path fill="#FBBC05" d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.94H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.06l3.01-2.34z" />
      <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.46 3.44 1.35l2.58-2.58A9 9 0 0 0 .96 4.94l3.01 2.34C4.68 5.16 6.66 3.58 9 3.58z" />
    </svg>
  );
}

function AppleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M17.05 12.04c-.03-2.9 2.37-4.3 2.48-4.36-1.35-1.98-3.46-2.25-4.21-2.28-1.79-.18-3.5 1.05-4.41 1.05-.91 0-2.32-1.03-3.81-1-1.96.03-3.77 1.14-4.78 2.9-2.04 3.54-.52 8.78 1.46 11.65.97 1.4 2.12 2.98 3.63 2.92 1.46-.06 2.01-.94 3.77-.94 1.76 0 2.26.94 3.81.91 1.57-.03 2.57-1.43 3.53-2.84 1.11-1.63 1.57-3.21 1.6-3.29-.04-.02-3.07-1.18-3.1-4.68M14.13 3.62C14.95 2.64 15.5 1.28 15.35 0c-1.1.04-2.43.73-3.27 1.7-.76.87-1.42 2.25-1.24 3.58 1.22.09 2.47-.62 3.29-1.66" />
    </svg>
  );
}

function XIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M18.9 1.15h3.68l-8.04 9.19L24 22.85h-7.4l-5.8-7.58-6.64 7.58H.48l8.6-9.83L0 1.15h7.6l5.24 6.93 6.06-6.93zm-1.29 19.5h2.04L6.48 3.24H4.3l13.31 17.41z" />
    </svg>
  );
}

export { Field };
