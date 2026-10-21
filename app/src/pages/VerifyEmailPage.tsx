import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';

type Status = 'verifying' | 'success' | 'error';

export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const { refresh } = useAuth();

  const [status, setStatus] = useState<Status>(token ? 'verifying' : 'error');
  const [error, setError] = useState<string | null>(token ? null : 'Missing verification token');

  useEffect(() => {
    if (!token) return;
    (async () => {
      try {
        // Sent with the session's token: the API only verifies the signed-in account.
        await apiFetch(`/v1/auth/verify?token=${encodeURIComponent(token)}`);
        setStatus('success');
        // Refresh /me so emailVerified flips immediately.
        void refresh();
      } catch (err) {
        setStatus('error');
        setError(err instanceof ApiError ? err.detail || err.message : 'Verification failed');
      }
    })();
  }, [token, refresh]);

  return (
    <div className="mx-auto max-w-md px-6 py-16 text-center">
      {status === 'verifying' && <p className="text-fg-muted">Verifying your email…</p>}
      {status === 'success' && (
        <>
          <h1 className="text-2xl font-bold tracking-tight text-fg">Email verified.</h1>
          <p className="mt-3 text-sm text-fg-muted">You're all set.</p>
          <Link
            to="/dashboard"
            className="mt-6 inline-flex items-center gap-1.5 text-brand-600 hover:text-brand-700 font-medium"
          >
            Go to dashboard
          </Link>
        </>
      )}
      {status === 'error' && (
        <>
          <h1 className="text-2xl font-bold tracking-tight text-danger">Couldn't verify.</h1>
          <p className="mt-3 text-sm text-fg-muted">{error}</p>
          <Link
            to="/dashboard"
            className="mt-6 inline-flex items-center gap-1.5 text-brand-600 hover:text-brand-700 font-medium"
          >
            Back to dashboard
          </Link>
        </>
      )}
    </div>
  );
}
