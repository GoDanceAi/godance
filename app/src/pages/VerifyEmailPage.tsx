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
  const [errorStatus, setErrorStatus] = useState<number | null>(null);

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
        setErrorStatus(err instanceof ApiError ? err.status : null);
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
        <Link
          to="/login"
          state={{ from: location.pathname + location.search }}
          className="btn btn-primary mt-6"
        >
          Sign in
        </Link>
        <ReleaseAddress token={token} />
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
          {/* 403: the link belongs to another account, maybe one made with this person's address. */}
          {token && errorStatus === 403 && <ReleaseAddress token={token} />}
        </>
      )}
    </div>
  );
}

type ReleaseStatus = 'idle' | 'confirming' | 'releasing' | 'released' | 'error';

/**
 * For whoever reads this inbox but didn't create the account the link is
 * for: frees the address from that account (which never verified it) so
 * they can sign up or sign in with it themselves. The account isn't deleted.
 */
function ReleaseAddress({ token }: { token: string }) {
  const [status, setStatus] = useState<ReleaseStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const release = async () => {
    setStatus('releasing');
    setError(null);
    try {
      await apiFetch('/v1/auth/email/release', { method: 'POST', json: { token }, auth: false });
      setStatus('released');
    } catch (err) {
      setStatus('error');
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not release the address');
    }
  };

  if (status === 'released') {
    return (
      <div className="mt-8 border-t border-border pt-6 text-sm text-fg-muted">
        <p role="status" className="font-medium text-fg">Address released.</p>
        <p className="mt-1">You can sign up with it now, or sign in with Google or Apple.</p>
        <Link to="/signup" className="btn btn-sm btn-secondary mt-4">
          Create an account
        </Link>
      </div>
    );
  }

  return (
    <div className="mt-8 border-t border-border pt-6 text-sm text-fg-muted">
      <p>Didn't create a Godance account with this address?</p>
      {status === 'idle' ? (
        <button type="button" onClick={() => setStatus('confirming')} className="btn btn-sm btn-ghost mt-3">
          Release this address
        </button>
      ) : (
        <>
          <p className="mt-2">
            This removes your address from that account, so you can use it for your own. The
            account itself isn't deleted.
          </p>
          <div className="mt-3 flex flex-wrap items-center justify-center gap-3">
            <button
              type="button"
              onClick={() => void release()}
              disabled={status === 'releasing'}
              className="btn btn-sm btn-secondary"
            >
              {status === 'releasing' ? 'Releasing…' : 'Release it'}
            </button>
            <button type="button" onClick={() => setStatus('idle')} className="btn btn-sm btn-ghost">
              Cancel
            </button>
          </div>
          {error && <p role="alert" className="mt-3 text-danger">{error}</p>}
        </>
      )}
    </div>
  );
}
