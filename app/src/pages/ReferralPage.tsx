import { useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { formatCents } from '../lib/auth';
import { useReferral } from '../lib/referral';

/**
 * The account's invite link and how it's doing. Shown only while the API
 * says referrals are on; otherwise this address leads back to the dashboard.
 * Every amount comes from the API.
 */
export default function ReferralPage() {
  const { info, loading } = useReferral();
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle');

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[40vh] text-fg-faint text-sm">
        Loading…
      </div>
    );
  }
  if (!info?.enabled) {
    return <Navigate to="/dashboard" replace />;
  }

  const link = `${window.location.origin}/signup?ref=${info.code}`;
  const youGet = info.referrer_reward_cents > 0 ? formatCents(info.referrer_reward_cents) : null;
  const theyGet = info.referee_reward_cents > 0 ? formatCents(info.referee_reward_cents) : null;
  const minimum = formatCents(info.min_topup_cents);
  // At least one is set, or the API wouldn't say referrals are on.
  const offer: ReactNode = youGet && theyGet
    ? <>you get <strong className="text-fg">{youGet}</strong> and they get <strong className="text-fg">{theyGet}</strong></>
    : youGet
      ? <>you get <strong className="text-fg">{youGet}</strong></>
      : <>they get <strong className="text-fg">{theyGet}</strong></>;

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Invite friends</h1>
      <p className="mt-2 text-sm text-fg-muted">
        Share your link. When someone signs up with it and makes their first top-up of {minimum} or
        more, {offer} in credit.
      </p>

      <div className="mt-8 card space-y-6">
        <div>
          <label htmlFor="referral-link" className="text-sm font-medium text-fg-muted">Your invite link</label>
          <div className="mt-2 flex gap-2">
            <input
              id="referral-link"
              type="text"
              readOnly
              value={link}
              onFocus={(e) => e.target.select()}
              className="input flex-1 min-w-0"
            />
            <button type="button" onClick={() => void copyLink()} className="btn btn-secondary">
              {copy === 'copied' ? 'Copied' : 'Copy'}
            </button>
          </div>
          {copy === 'failed' && (
            <p role="alert" className="mt-2 text-xs text-fg-subtle">
              Couldn't copy it. Select the link and copy it yourself.
            </p>
          )}
        </div>

        <dl className="grid grid-cols-3 gap-3">
          <Stat label="Invited" value={String(info.invited)} />
          <Stat label="Rewarded" value={String(info.rewarded)} />
          <Stat label="Earned" value={formatCents(info.earned_cents)} />
        </dl>
      </div>

      <section className="mt-8" aria-labelledby="referral-terms">
        <h2 id="referral-terms" className="text-sm font-semibold text-fg">Terms</h2>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-fg-subtle">
          <li>
            Your link works for people who are new to Godance: they sign up with it and haven't
            topped up yet. Each account can be invited once.
          </li>
          <li>
            The reward is paid once, on your friend's first card top-up of {minimum} or more.
          </li>
          <li>Both accounts must belong to people 16 or older.</li>
          {youGet && (
            <li>
              Rewards for inviting are limited each month. Past the limit, your friend still gets
              theirs.
            </li>
          )}
          <li>Rewards are Godance credit added to your balance. They can't be exchanged for cash.</li>
          <li>We don't tell you who signed up with your link.</li>
          <li>We may change or end this offer at any time.</li>
        </ul>
      </section>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-border p-3">
      <dt className="text-xs text-fg-subtle">{label}</dt>
      <dd className="mt-1 text-lg font-semibold text-fg tabular-nums">{value}</dd>
    </div>
  );
}
