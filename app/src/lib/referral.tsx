import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { apiFetch } from './api';
import { useAuth } from './auth';

// Referrals: a user shares <app>/signup?ref=<code>, and when someone who
// signs up with it first tops up, both are credited. The server decides who
// is eligible; the app keeps the code from the link, hands it in once
// someone is signed in, and shows the offer only while the API says it's on.

/** GET /v1/referrals/me. While referrals are off it says so and nothing else. */
export type ReferralInfo =
  | { enabled: false }
  | {
      enabled: true;
      code: string;
      referrer_reward_cents: number;
      referee_reward_cents: number;
      min_topup_cents: number;
      invited: number;
      rewarded: number;
      earned_cents: number;
      // What this account gets on a qualifying top-up as an invited user; null when not invited.
      pending_reward_cents: number | null;
    };

const STORAGE_KEY = 'godance.referral';
// The server's alphabet: digits and capitals with no look-alikes (no 0/O, 1/I/L).
const CODE_SHAPE = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$/;
// Long enough to outlast a social sign-in round trip and an email opened later.
const KEEP_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Keep the `ref` of an invite link (`?ref=<code>`) when it has the shape of
 * a code. localStorage, not sessionStorage, so it survives a social sign-in
 * and a sign-in in another tab. A newer link replaces an older code.
 */
export function captureReferralCode(search: string): void {
  const code = new URLSearchParams(search).get('ref')?.trim().toUpperCase();
  if (!code || !CODE_SHAPE.test(code)) return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ code, expires: Date.now() + KEEP_MS }));
  } catch {
    // Storage blocked: there is nowhere to keep it.
  }
}

/** The kept code, or null. An expired or unreadable one is dropped. */
export function storedReferralCode(): string | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return null;
    let kept: { code?: unknown; expires?: unknown } | null = null;
    try {
      kept = JSON.parse(raw) as { code?: unknown; expires?: unknown };
    } catch {
      kept = null;
    }
    if (kept && typeof kept.code === 'string' && CODE_SHAPE.test(kept.code)
        && typeof kept.expires === 'number' && kept.expires > Date.now()) {
      return kept.code;
    }
    localStorage.removeItem(STORAGE_KEY);
    return null;
  } catch {
    return null;
  }
}

/** Forget `code`, unless a newer link has replaced it meanwhile. */
function forgetReferralCode(code: string): void {
  try {
    if (storedReferralCode() === code) localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage blocked: nothing was kept.
  }
}

let claiming: Promise<void> | null = null;

/**
 * Hand the kept code in for the signed-in account, once, and forget it
 * whatever the answer: the server decides, and a refused code is no use
 * later. Nothing is shown either way, and nothing navigates.
 */
function claimStoredReferralCode(): Promise<void> {
  if (claiming) return claiming;
  const code = storedReferralCode();
  if (!code) return Promise.resolve();
  claiming = apiFetch('/v1/referrals/claim', { method: 'POST', json: { code } })
    .then(() => undefined, () => undefined)
    .finally(() => {
      forgetReferralCode(code);
      claiming = null;
    });
  return claiming;
}

/** On a page an invite link can open (/signup, /login): keep its code. */
export function useCaptureReferral(): void {
  const { search } = useLocation();
  useEffect(() => {
    captureReferralCode(search);
  }, [search]);
}

interface ReferralState {
  info: ReferralInfo | null;   // null: signed out, still loading, or the API couldn't say
  loading: boolean;
}

const ReferralContext = createContext<ReferralState>({ info: null, loading: false });

/**
 * For each signed-in account: hand in a kept code, then read the account's
 * referral view. An account waiting for a parent's approval can reach
 * neither, so it is left alone.
 */
export function ReferralProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const userId = user && user.ageStatus !== 'CONSENT_PENDING' ? user.id : null;
  // Whose view this is, so a newly signed-in account reads as loading from
  // its very first render, before the request below has even started.
  const [loaded, setLoaded] = useState<{ userId: string; info: ReferralInfo | null } | null>(null);

  useEffect(() => {
    if (!userId) return;
    let current = true;
    void (async () => {
      await claimStoredReferralCode();
      let info: ReferralInfo | null = null;
      try {
        info = await apiFetch<ReferralInfo>('/v1/referrals/me');
      } catch {
        info = null;
      }
      if (current) setLoaded({ userId, info });
    })();
    return () => {
      current = false;
    };
  }, [userId]);

  const state = useMemo<ReferralState>(() => {
    if (!userId) return { info: null, loading: false };
    if (loaded?.userId !== userId) return { info: null, loading: true };
    return { info: loaded.info, loading: false };
  }, [userId, loaded]);

  return <ReferralContext.Provider value={state}>{children}</ReferralContext.Provider>;
}

/** The signed-in account's referral view; `info` is null until it has loaded. */
export function useReferral(): ReferralState {
  return useContext(ReferralContext);
}
