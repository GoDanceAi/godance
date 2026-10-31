import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';

type ResendStatus = 'idle' | 'sending' | 'sent' | 'error';

/**
 * What a child's account sees until a parent approves it. The session can
 * only read /v1/auth/me and re-send the parent's email; approval ends it
 * (the server bumps the session version), so the child signs in again.
 */
export default function ParentConsentWaiting() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState<ResendStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const resend = async () => {
    setStatus('sending');
    setError(null);
    try {
      await apiFetch('/v1/auth/parental-consent/resend', { method: 'POST' });
      setStatus('sent');
    } catch (err) {
      setStatus('error');
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not send the email');
    }
  };

  const signOut = async () => {
    try {
      await logout();
      navigate('/login');
    } catch {
      setStatus('error');
      setError("Couldn't sign out. Try again.");
    }
  };

  return (
    <div className="mx-auto max-w-lg px-6 py-16">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Waiting for your parent's approval</h1>
      <p className="mt-4 text-sm text-fg-muted">
        We emailed{' '}
        <strong className="text-fg">{user?.parentEmailMasked ?? 'your parent or guardian'}</strong>{' '}
        a link to approve your account. They approve by adding credit to your account with their
        card. Until then, you can't use Godance.
      </p>
      <p className="mt-3 text-sm text-fg-muted">
        The link works for 7 days. Once they've approved, sign out and sign in again to start.
      </p>
      <div className="mt-8 flex flex-wrap items-center gap-3 text-sm">
        <button
          type="button"
          onClick={() => void resend()}
          disabled={status === 'sending'}
          className="btn btn-primary"
        >
          {status === 'sending' ? 'Sending…' : 'Email them again'}
        </button>
        <button type="button" onClick={() => void signOut()} className="btn btn-secondary">
          Sign out
        </button>
      </div>
      {status === 'sent' && (
        <p role="status" className="mt-4 text-sm text-fg-muted">
          Sent a new link. The one in the earlier email no longer works.
        </p>
      )}
      {status === 'error' && <p role="alert" className="mt-4 text-sm text-danger">{error}</p>}
    </div>
  );
}
