import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  apiFetch,
  TOKEN_KEY,
  UNAUTHORIZED_EVENT,
  type AuthResponse,
  type MeResponse,
} from './api';

interface AuthContextValue {
  user: MeResponse | null;
  loading: boolean;                  // true on first mount while we hydrate /me
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string, fullName: string) => Promise<void>;
  logout: () => void;
  refresh: () => Promise<void>;      // re-fetch /me (after a top-up etc.)
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(!!localStorage.getItem(TOKEN_KEY));

  const refresh = useCallback(async () => {
    try {
      const me = await apiFetch<MeResponse>('/v1/auth/me');
      setUser(me);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Hydrate on first mount if there's already a stored token.
  useEffect(() => {
    if (localStorage.getItem(TOKEN_KEY)) {
      void refresh();
    } else {
      setLoading(false);
    }
  }, [refresh]);

  // Listen for backend 401s (token expired, revoked, etc.) — clear local state.
  useEffect(() => {
    const handler = () => setUser(null);
    window.addEventListener(UNAUTHORIZED_EVENT, handler);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const resp = await apiFetch<AuthResponse>('/v1/auth/login', {
      method: 'POST',
      auth: false,
      json: { email, password },
    });
    localStorage.setItem(TOKEN_KEY, resp.accessToken);
    await refresh();
  }, [refresh]);

  const signup = useCallback(async (email: string, password: string, fullName: string) => {
    const resp = await apiFetch<AuthResponse>('/v1/auth/signup', {
      method: 'POST',
      auth: false,
      json: { email, password, fullName },
    });
    localStorage.setItem(TOKEN_KEY, resp.accessToken);
    await refresh();
  }, [refresh]);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, signup, logout, refresh }}>
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
