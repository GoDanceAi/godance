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
        await apiFetch(`/v1/auth/verify?token=${encodeURIComponent(token)}`, { auth: false });
        setStatus('success');
        // Refresh /me so emailVerified flips immediately for any logged-in session.
        void refresh();
      } catch (err) {
        setStatus('error');
        setError(err instanceof ApiError ? err.detail || err.message : 'Verification failed');
      }
    })();
  }, [token, refresh]);

  return (
    <div className="mx-auto max-w-md px-6 py-16 text-center">
      {status === 'verifying' && <p className="text-gray-600">Verifying your email…</p>}
      {status === 'success' && (
        <>
          <h1 className="text-2xl font-bold tracking-tight">Email verified.</h1>
          <p className="mt-3 text-sm text-gray-600">You're all set.</p>
          <Link to="/dashboard" className="inline-block mt-6 text-brand-600 underline">
            Go to dashboard →
          </Link>
        </>
      )}
      {status === 'error' && (
        <>
          <h1 className="text-2xl font-bold tracking-tight text-red-700">Couldn't verify.</h1>
          <p className="mt-3 text-sm text-gray-600">{error}</p>
          <Link to="/dashboard" className="inline-block mt-6 text-brand-600 underline">
            Back to dashboard →
          </Link>
        </>
      )}
    </div>
  );
}
