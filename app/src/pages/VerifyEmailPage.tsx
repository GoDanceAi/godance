import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';

type Status = 'verifying' | 'success' | 'error';

/**
 * Landing page for the link in the verification email. The API verifies
 * only the signed-in account's own email: a click from the inbox alone must
 * not confirm an address someone else registered with their own password.
 * Signed out, this page asks the person to sign in and comes back here.
 */
export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const location = useLocation();
  const { user, loading, refresh } = useAuth();
  const signedIn = user !== null;
  const attempted = useRef(false);   // StrictMode double-invoke guard; the token is single-use

  const [status, setStatus] = useState<Status>(token ? 'verifying' : 'error');
  const [error, setError] = useState<string | null>(token ? null : 'Missing verification token');

  useEffect(() => {
    if (!token || !signedIn || attempted.current) return;
    attempted.current = true;
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
  }, [token, signedIn, refresh]);

  if (token && !loading && !signedIn) {
    return (
      <div className="mx-auto max-w-md px-6 py-16 text-center">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Sign in to verify your email.</h1>
        <p className="mt-3 text-sm text-fg-muted">
          Sign in to the account you created with this email address. We'll finish verifying it
          right after.
        </p>
        <p className="mt-3 text-sm text-fg-muted">
          Didn't create a Godance account? You can close this page. Nothing changes.
        </p>
        <Link
          to="/login"
          state={{ from: location.pathname + location.search }}
          className="btn btn-primary mt-6"
        >
          Sign in
        </Link>
      </div>
    );
  }

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
