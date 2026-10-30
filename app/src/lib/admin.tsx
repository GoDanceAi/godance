import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, ApiError, type AdminMeResponse } from './api';
import { useAuth } from './auth';

// Admin gating for the SPA. One GET /v1/admin/me per login: 200 → show the
// Admin nav + routes, 403 → plain user, or an admin who must turn on 2FA
// first. The server-side allowlist filter remains the real gate — this only
// decides what the UI renders.

interface AdminContextValue {
  isAdmin: boolean;
  needsTwoFactor: boolean; // an admin, refused the console until 2FA is on
  loading: boolean; // true while the /v1/admin/me probe is in flight
}

const AdminContext = createContext<AdminContextValue>({ isAdmin: false, needsTwoFactor: false, loading: false });

export function AdminProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const twoFactorEnabled = user?.twoFactorEnabled ?? false;
  // The probe result, tagged with the user it was made for. Loading is
  // derived from it rather than set in the effect: effects run after render,
  // so the first render with a user would otherwise read "not loading, not
  // admin" and AdminLayout would bounce a cold load of /admin/* to the
  // dashboard. Keyed on the id, so refresh() (a new user object) doesn't
  // re-probe, and on the 2FA state, which is what changes the answer.
  const [probe, setProbe] = useState<{
    userId: string;
    isAdmin: boolean;
    needsTwoFactor: boolean;
  } | null>(null);

  useEffect(() => {
    if (!userId) {
      setProbe(null);
      return;
    }
    let cancelled = false;
    apiFetch<AdminMeResponse>('/v1/admin/me')
      .then(
        () => ({ isAdmin: true, needsTwoFactor: false }),
        (err: unknown) => ({
          isAdmin: false,
          needsTwoFactor: err instanceof ApiError && err.code === 'two_factor_required',
        }),
      )
      .then((result) => { if (!cancelled) setProbe({ userId, ...result }); });
    return () => { cancelled = true; };
  }, [userId, twoFactorEnabled]);

  const resolved = userId !== null && probe?.userId === userId;
  const isAdmin = resolved && probe.isAdmin;
  const needsTwoFactor = resolved && probe.needsTwoFactor;
  const loading = userId !== null && !resolved;

  return (
    <AdminContext.Provider value={{ isAdmin, needsTwoFactor, loading }}>
      {children}
    </AdminContext.Provider>
  );
}

export function useAdmin(): AdminContextValue {
  return useContext(AdminContext);
}
