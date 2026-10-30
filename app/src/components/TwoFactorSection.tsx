import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import {
  apiFetch,
  ApiError,
  TOKEN_KEY,
  type TwoFactorEnabled,
  type TwoFactorEnrolment,
  type TwoFactorStatus,
} from '../lib/api';
import { useAuth } from '../lib/auth';
import { Icon } from './Brand';

type Step =
  | { name: 'idle' }
  | { name: 'confirm-password' }
  | { name: 'scan'; enrolment: TwoFactorEnrolment }
  | { name: 'recovery-codes'; codes: string[] }
  | { name: 'turn-off' };

/**
 * Turn two-factor sign-in on or off. The QR code is drawn here in the
 * browser, so the secret never goes to a QR service. Each step that changes
 * anything proves who the caller is again: the password, or for a social
 * account a recent sign-in, which the server enforces.
 */
export default function TwoFactorSection() {
  const { user, refresh } = useAuth();
  const [step, setStep] = useState<Step>({ name: 'idle' });
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState<number | null>(null);

  const enabled = user?.twoFactorEnabled ?? false;

  useEffect(() => {
    if (!enabled || step.name !== 'idle') return;
    let cancelled = false;
    apiFetch<TwoFactorStatus>('/v1/auth/2fa')
      .then((status) => { if (!cancelled) setRemaining(status.recoveryCodesRemaining); })
      .catch(() => { /* the section still works without the count */ });
    return () => { cancelled = true; };
  }, [enabled, step.name]);

  if (!user) return null;

  const go = (next: Step) => {
    setStep(next);
    setPassword('');
    setCode('');
    setError(null);
  };

  const run = async (action: () => Promise<void>, failure: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.message : failure);
    } finally {
      setBusy(false);
    }
  };

  const startEnrolment = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const enrolment = await apiFetch<TwoFactorEnrolment>('/v1/auth/2fa/enrol', {
        method: 'POST',
        json: user.hasPassword ? { currentPassword: password } : {},
      });
      go({ name: 'scan', enrolment });
    }, 'Could not start setting up two-factor sign-in');
  };

  const enable = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const on = await apiFetch<TwoFactorEnabled>('/v1/auth/2fa/enable', {
        method: 'POST',
        json: { code },
      });
      // Turning this on ended every session, this one too; this is its replacement.
      localStorage.setItem(TOKEN_KEY, on.session.accessToken);
      go({ name: 'recovery-codes', codes: on.recoveryCodes });
      await refresh();
    }, 'Could not turn on two-factor sign-in');
  };

  const disable = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      await apiFetch('/v1/auth/2fa/disable', {
        method: 'POST',
        json: user.hasPassword ? { currentPassword: password, code } : { code },
      });
      go({ name: 'idle' });
      await refresh();
    }, 'Could not turn off two-factor sign-in');
  };

  const passwordOrNote = user.hasPassword ? (
    <label className="block">
      <span className="text-sm font-medium text-fg">Current password</span>
      <input
        type="password"
        required
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoComplete="current-password"
        className="input mt-1.5"
      />
    </label>
  ) : (
    <p className="text-xs text-fg-subtle">
      For your security, this works within 10 minutes of signing in.
    </p>
  );

  const codeField = (label: string, hint?: string) => (
    <label className="block">
      <span className="text-sm font-medium text-fg">{label}</span>
      <input
        type="text"
        required
        value={code}
        onChange={(e) => setCode(e.target.value)}
        autoComplete="one-time-code"
        className="input mt-1.5"
      />
      {hint && <span className="mt-1 block text-xs text-fg-subtle">{hint}</span>}
    </label>
  );

  const buttons = (submit: string, busyLabel: string, style = 'btn-primary') => (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={busy} className={`btn btn-sm ${style}`}>
          {busy ? busyLabel : submit}
        </button>
        <button type="button" onClick={() => go({ name: 'idle' })} className="btn btn-sm btn-ghost">
          Cancel
        </button>
      </div>
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
    </>
  );

  return (
    <section className="mt-8 card">
      <h2 className="font-semibold text-fg">Two-factor sign-in</h2>

      {step.name === 'recovery-codes' ? (
        <RecoveryCodes codes={step.codes} onDone={() => go({ name: 'idle' })} />
      ) : step.name === 'scan' ? (
        <form onSubmit={enable} className="mt-4 max-w-sm space-y-4">
          <p className="text-sm text-fg-muted">
            Scan this with an authenticator app such as Google Authenticator, 1Password or Authy,
            then enter the 6-digit code it shows.
          </p>
          {/* White behind the code in both themes: scanners need the contrast. */}
          <div className="inline-block rounded bg-white p-3">
            <QRCodeSVG value={step.enrolment.otpauthUri} size={176} title="QR code for your authenticator app" />
          </div>
          <p className="text-xs text-fg-subtle">
            Can't scan it? Enter this key by hand:{' '}
            <code className="code-chip break-all">{step.enrolment.secret}</code>
          </p>
          {codeField('6-digit code')}
          {buttons('Turn on', 'Checking…')}
        </form>
      ) : step.name === 'confirm-password' ? (
        <form onSubmit={startEnrolment} className="mt-4 max-w-sm space-y-3">
          {passwordOrNote}
          {buttons('Continue', 'Checking…')}
        </form>
      ) : step.name === 'turn-off' ? (
        <form onSubmit={disable} className="mt-4 max-w-sm space-y-3">
          {passwordOrNote}
          {codeField('Code', 'From your authenticator app, or a recovery code if you lost it.')}
          {buttons('Turn off', 'Turning off…', 'btn-danger')}
        </form>
      ) : enabled ? (
        <>
          <p className="mt-1 text-sm text-fg-muted">
            <span className="font-medium text-fg">On.</span> Signing in needs a code from your
            authenticator app.
            {remaining !== null && ` ${remaining} recovery ${remaining === 1 ? 'code' : 'codes'} left.`}
          </p>
          <button type="button" onClick={() => go({ name: 'turn-off' })} className="mt-4 btn btn-sm btn-secondary">
            Turn off
          </button>
        </>
      ) : (
        <>
          <p className="mt-1 text-sm text-fg-muted">
            A stolen password won't be enough to get into your account or reach your saved card.
            Signing in will also need a code from an authenticator app.
          </p>
          <button type="button" onClick={() => go({ name: 'confirm-password' })} className="mt-4 btn btn-sm btn-primary">
            Turn on
          </button>
        </>
      )}
    </section>
  );
}

/** Shown once, right after turning 2FA on. Only their hashes are kept. */
function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(codes.join('\n'));
    setCopied(true);
  };

  return (
    <div className="mt-4 max-w-sm space-y-4">
      <p className="text-sm text-success">Two-factor sign-in is on.</p>
      <p className="text-sm text-fg-muted">
        Save these recovery codes somewhere safe. Each one works once, in place of a code from
        your app, if you lose your phone. You won't see them again.
      </p>
      <ul aria-label="Recovery codes" className="embedded-surface grid grid-cols-1 gap-1 p-3 font-mono text-xs sm:grid-cols-2">
        {codes.map((code) => <li key={code}>{code}</li>)}
      </ul>
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" onClick={() => void copy()} className="btn btn-sm btn-secondary">
          <Icon name="copy" size={14} />
          {copied ? 'Copied' : 'Copy'}
        </button>
        <button type="button" onClick={onDone} className="btn btn-sm btn-primary">
          I've saved them
        </button>
      </div>
    </div>
  );
}
