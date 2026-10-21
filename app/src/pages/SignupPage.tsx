import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { ApiError } from '../lib/api';
import { Field } from './LoginPage';

export default function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await signup(email, password, fullName);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Signup failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-sm px-6 py-16">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Create your account</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Already have one?{' '}
        <Link to="/login" className="text-brand-600 hover:text-brand-700 underline">
          Sign in
        </Link>
      </p>
      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <Field label="Full name" type="text" value={fullName} onChange={setFullName} autoComplete="name" required />
        <Field label="Email" type="email" value={email} onChange={setEmail} autoComplete="email" required />
        <Field label="Password" type="password" value={password} onChange={setPassword} autoComplete="new-password" required minLength={12} />
        <p className="text-xs text-fg-subtle">
          Min 12 characters. We hash with BCrypt — the plaintext is never stored.
        </p>
        {error && <p className="text-sm text-danger">{error}</p>}
        <button type="submit" disabled={busy} className="btn btn-primary btn-lg w-full">
          {busy ? 'Creating…' : 'Create account'}
        </button>
      </form>
      <p className="mt-6 text-xs text-fg-subtle">
        By signing up you agree to our{' '}
        {/* New tab, so reading them doesn't throw away what's typed in the form. */}
        <a href="https://godance.ai/legal/terms" target="_blank" rel="noreferrer"
           className="underline hover:text-fg">Terms</a> and{' '}
        <a href="https://godance.ai/legal/privacy" target="_blank" rel="noreferrer"
           className="underline hover:text-fg">Privacy Policy</a>.
      </p>
    </div>
  );
}
