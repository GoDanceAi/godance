import { useState } from 'react';
import { apiFetch, ApiError } from '../lib/api';

type ResendStatus = 'idle' | 'sending' | 'sent' | 'error';

/** Re-sends the verification email (to the address being added or changed to, if any). */
export default function ResendEmailButton() {
  const [status, setStatus] = useState<ResendStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const resend = async () => {
    setStatus('sending');
    setError(null);
    try {
      await apiFetch('/v1/auth/verify/resend', { method: 'POST' });
      setStatus('sent');
    } catch (err) {
      setStatus('error');
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not send the email');
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={() => void resend()}
        disabled={status === 'sending'}
        className="btn btn-sm btn-secondary"
      >
        {status === 'sending' ? 'Sending…' : 'Resend email'}
      </button>
      {status === 'sent' && <span role="status">Sent. Check your inbox.</span>}
      {status === 'error' && <span role="alert" className="text-danger">{error}</span>}
    </>
  );
}
