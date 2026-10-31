import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  apiFetch,
  ApiError,
  SESSION_CALL,
  UNAUTHORIZED_EVENT,
  type MeResponse,
  type SignInResponse,
} from './api';
import { adoptLegacySession } from './legacy-session';

interface AuthContextValue {
  user: MeResponse | null;
  loading: boolean;                  // true on first mount while we hydrate /me
  // Resolves to a challenge when the account has 2FA on, else null: signed in.
  login: (email: string, password: string) => Promise<string | null>;
  completeTwoFactor: (challenge: string, code: string) => Promise<void>;
  signup: (input: SignupInput) => Promise<void>;
  logout: () => Promise<void>;       // rejects, still signed in, if the API couldn't be reached
  refresh: () => Promise<void>;      // re-fetch /me (after a top-up etc.)
}

/** birthMonth is YYYY-MM; parentEmail is sent only for someone under 16. */
export interface SignupInput {
  email: string;
  password: string;
  fullName: string;
  birthMonth: string;
  parentEmail?: string;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const latestRefresh = useRef(0);

  const refresh = useCallback(async () => {
    // Every page load asks, so a slow first answer of "signed out" can land
    // after a sign-in: only the latest call may set the user.
    const call = ++latestRefresh.current;
    try {
      const me = await apiFetch<MeResponse>('/v1/auth/me', { auth: false, credentials: 'include' });
      if (call === latestRefresh.current) setUser(me);
    } catch {
      if (call === latestRefresh.current) setUser(null);
    } finally {
      if (call === latestRefresh.current) setLoading(false);
    }
  }, []);

  // Hydrate on first mount. Page script can't see the cookie, so only the API can say.
  useEffect(() => {
    void adoptLegacySession().then(refresh);
  }, [refresh]);

  // Listen for backend 401s (session expired, revoked, etc.) — clear local state.
  useEffect(() => {
    const handler = () => setUser(null);
    window.addEventListener(UNAUTHORIZED_EVENT, handler);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const resp = await apiFetch<SignInResponse>('/v1/auth/login', {
      ...SESSION_CALL,
      json: { email, password },
    });
    if (resp.twoFactorToken) return resp.twoFactorToken;
    await refresh();
    return null;
  }, [refresh]);

  const completeTwoFactor = useCallback(async (challenge: string, code: string) => {
    // auth: false because a challenge is not a session. Its 401 means it
    // expired, which the sign-in page handles, not that a session ended.
    await apiFetch('/v1/auth/2fa/verify', {
      ...SESSION_CALL,
      headers: { ...SESSION_CALL.headers, Authorization: `Bearer ${challenge}` },
      json: { code },
    });
    await refresh();
  }, [refresh]);

  const signup = useCallback(async (input: SignupInput) => {
    await apiFetch('/v1/auth/signup', { ...SESSION_CALL, json: input });
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    // Only the API can remove an HttpOnly cookie, so a failed call leaves the
    // session alive and must not look signed out. A 401 means it already ended.
    try {
      await apiFetch('/v1/auth/logout', { method: 'POST', auth: false, credentials: 'include' });
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 401)) throw err;
    }
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, completeTwoFactor, signup, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be inside <AuthProvider>');
  return ctx;
}

/** Format cents as a USD currency string. Centralised so balance rendering is consistent. */
export function formatCents(cents: number): string {
  return (cents / 100).toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}
