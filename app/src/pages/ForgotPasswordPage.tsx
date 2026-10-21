import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch, ApiError } from '../lib/api';
import { Field } from './LoginPage';

/**
 * Request a password-reset email. The backend answers 202 whether or not
 * the address is registered, so this page never says either.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await apiFetch('/v1/auth/password/forgot', {
        method: 'POST',
        auth: false,
        json: { email },
      });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Request failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-sm px-6 py-16">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Reset your password</h1>
      {sent ? (
        <p className="mt-4 text-sm text-fg-muted">
          If a Godance account uses <strong>{email}</strong>, we've emailed it a reset link. The
          link works for one hour.
        </p>
      ) : (
        <>
          <p className="mt-2 text-sm text-fg-muted">
            We'll email you a link to choose a new password.
          </p>
          <form onSubmit={onSubmit} className="mt-8 space-y-4">
            <Field label="Email" type="email" value={email} onChange={setEmail} autoComplete="email" required />
            {error && <p className="text-sm text-danger">{error}</p>}
            <button type="submit" disabled={busy} className="btn btn-primary btn-lg w-full">
              {busy ? 'Sending…' : 'Email me a reset link'}
            </button>
          </form>
        </>
      )}
      <p className="mt-6 text-sm text-fg-muted">
        <Link to="/login" className="text-brand-600 hover:text-brand-700 underline">
          Back to sign in
        </Link>
      </p>
    </div>
  );
}
