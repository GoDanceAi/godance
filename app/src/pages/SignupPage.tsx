import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { apiFetch, ApiError } from '../lib/api';
import { birthMonthParam, needsParentalConsent } from '../lib/age';
import { Field } from './LoginPage';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

// Every year from this one back to 1900, newest first. No default is picked:
// a neutral age screen asks everyone and doesn't steer the answer.
const THIS_YEAR = new Date().getUTCFullYear();
const YEARS = Array.from({ length: THIS_YEAR - 1900 + 1 }, (_, i) => THIS_YEAR - i);

export default function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [birthMonth, setBirthMonth] = useState('');
  const [birthYear, setBirthYear] = useState('');
  const [parentEmail, setParentEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [taken, setTaken] = useState(false);
  const [busy, setBusy] = useState(false);

  const born = birthMonth && birthYear ? { year: Number(birthYear), month: Number(birthMonth) } : null;
  const child = born !== null && needsParentalConsent(born.year, born.month);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!born) return; // the selects are required
    setError(null);
    setTaken(false);
    if (child && parentEmail.trim().toLowerCase() === email.trim().toLowerCase()) {
      setError("Your parent's or guardian's email address must be different from yours.");
      return;
    }
    setBusy(true);
    try {
      await signup({
        email,
        password,
        fullName,
        birthMonth: birthMonthParam(born.year, born.month),
        ...(child ? { parentEmail } : {}),
      });
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : 'Signup failed');
      setTaken(err instanceof ApiError && err.status === 409);
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
        <fieldset>
          <legend className="text-sm font-medium text-fg">Date of birth</legend>
          <div className="mt-1.5 grid grid-cols-2 gap-3">
            <select
              aria-label="Birth month"
              value={birthMonth}
              onChange={(e) => setBirthMonth(e.target.value)}
              autoComplete="bday-month"
              required
              className="input"
            >
              <option value="" disabled>Month</option>
              {MONTHS.map((name, i) => (
                <option key={name} value={i + 1}>{name}</option>
              ))}
            </select>
            <select
              aria-label="Birth year"
              value={birthYear}
              onChange={(e) => setBirthYear(e.target.value)}
              autoComplete="bday-year"
              required
              className="input"
            >
              <option value="" disabled>Year</option>
              {YEARS.map((y) => (
                <option key={y} value={y}>{y}</option>
              ))}
            </select>
          </div>
        </fieldset>
        {child && (
          <div className="space-y-2">
            <Field label="Parent or guardian's email" type="email" value={parentEmail} onChange={setParentEmail} autoComplete="off" required />
            <p className="text-xs text-fg-subtle">
              Because of your age, a parent or guardian needs to approve your account before you
              can use Godance. We'll email them a link, and they approve by adding credit to your
              account with their card. Until then, your account stays locked.
            </p>
          </div>
        )}
        {error && <p className="text-sm text-danger">{error}</p>}
        {taken && <ReleaseLinkOffer email={email} />}
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

type LinkStatus = 'idle' | 'sending' | 'sent' | 'error';

/**
 * Shown when signup finds the address taken. If the account holding it
 * never verified it, someone else may have signed up with this address:
 * the link lets its owner release it.
 */
function ReleaseLinkOffer({ email }: { email: string }) {
  const [status, setStatus] = useState<LinkStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    setStatus('sending');
    setError(null);
    try {
      await apiFetch('/v1/auth/email/release-link', { method: 'POST', json: { email }, auth: false });
      setStatus('sent');
    } catch (err) {
      setStatus('error');
      setError(err instanceof ApiError ? err.detail || err.message : 'Could not send the link');
    }
  };

  if (status === 'sent') {
    return (
      <p role="status" className="rounded-md border border-border p-3 text-sm text-fg-muted">
        If that account never verified this address, we've emailed it a link. Open it to release
        the address, then sign up again.
      </p>
    );
  }

  return (
    <div className="rounded-md border border-border p-3 text-sm text-fg-muted">
      <p>
        Didn't create that account? If it never verified this address, we can email the address
        a link to release it.
      </p>
      <button
        type="button"
        onClick={() => void send()}
        disabled={status === 'sending'}
        className="btn btn-sm btn-secondary mt-3"
      >
        {status === 'sending' ? 'Sending…' : 'Email me a release link'}
      </button>
      {error && <p role="alert" className="mt-2 text-danger">{error}</p>}
    </div>
  );
}
