import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError, TOKEN_KEY, type SignInResponse } from '../lib/api';
import { useAuth } from '../lib/auth';
import { Field } from './LoginPage';

/**
 * Landing page for the password-reset email's link. The backend signs out
 * every earlier session and revokes the API keys and webhook secret, then
 * returns a fresh token, which we store exactly as login does. With 2FA on
 * it returns a challenge instead, and the sign-in page asks for the code.
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const navigate = useNavigate();
  const { refresh } = useAuth();

  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!token) return;
    setError(null);
    setBusy(true);
    try {
      const resp = await apiFetch<SignInResponse>('/v1/auth/password/reset', {
        method: 'POST',
        auth: false,
        json: { token, newPassword: password },
      });
      if ('twoFactorToken' in resp) {
        navigate('/login', { replace: true, state: { twoFactorToken: resp.twoFactorToken } });
        return;
      }
      localStorage.setItem(TOKEN_KEY, resp.accessToken);
      await refresh();
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Reset failed');
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <div className="mx-auto max-w-sm px-6 py-16">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Choose a new password</h1>
        <p className="mt-4 text-sm text-danger">Missing reset token.</p>
        <p className="mt-6 text-sm text-fg-muted">
          <Link to="/forgot-password" className="text-brand-600 hover:text-brand-700 underline">
            Request a new link
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm px-6 py-16">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Choose a new password</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Resetting signs out every session, on every device, and revokes this account's API keys
        and webhook signing secret. Create new ones afterwards.
      </p>
      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <Field label="New password" type="password" value={password} onChange={setPassword} autoComplete="new-password" required minLength={12} />
        <p className="text-xs text-fg-subtle">Min 12 characters.</p>
        {error && <p className="text-sm text-danger">{error}</p>}
        <button type="submit" disabled={busy} className="btn btn-primary btn-lg w-full">
          {busy ? 'Resetting…' : 'Reset password'}
        </button>
      </form>
    </div>
  );
}
