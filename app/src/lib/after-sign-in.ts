import { useEffect, useRef } from 'react';
import type { MeResponse } from './api';
import { useAuth } from './auth';

// What the app hands in on a visitor's behalf once someone is signed in: an
// invite code (lib/referral), and what brought them here (lib/first-touch).
// Both are kept in localStorage until then, so they outlast a social sign-in
// round trip and a sign-in in another tab, and both run from this one hook.

/**
 * The id of the signed-in account, once it can use the API. A child's
 * account waiting for a parent's approval reaches almost none of it, so it
 * counts as signed out here until it is approved.
 */
export function signedInAccountId(user: MeResponse | null): string | null {
  return user && user.ageStatus !== 'CONSENT_PENDING' ? user.id : null;
}

/** {@link signedInAccountId} for the current session. */
export function useSignedInAccountId(): string | null {
  return signedInAccountId(useAuth().user);
}

/**
 * Runs `task` each time an account becomes signed in on this page: after a
 * password sign-up or sign-in, on the page a social sign-in comes back to,
 * and on the first load of a tab whose session began in another. It runs
 * again when a different account signs in. What `task` returns is its
 * cleanup, as an effect's is.
 */
export function useAfterSignIn(task: (userId: string) => void | (() => void)): void {
  const userId = useSignedInAccountId();
  const latest = useRef(task);
  latest.current = task;
  useEffect(() => {
    if (!userId) return;
    return latest.current(userId);
  }, [userId]);
}
