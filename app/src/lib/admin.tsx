import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, type AdminMeResponse } from './api';
import { useAuth } from './auth';

// Admin gating for the SPA. One GET /v1/admin/me per login: 200 → show the
// Admin nav + routes, 403 → plain user. The server-side allowlist filter
// remains the real gate — this only decides what the UI renders.

interface AdminContextValue {
  isAdmin: boolean;
  loading: boolean; // true while the /v1/admin/me probe is in flight
}

const AdminContext = createContext<AdminContextValue>({ isAdmin: false, loading: false });

export function AdminProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  // The probe result, tagged with the user it was made for. Loading is
  // derived from it rather than set in the effect: effects run after render,
  // so the first render with a user would otherwise read "not loading, not
  // admin" and AdminLayout would bounce a cold load of /admin/* to the
  // dashboard. Keyed on the id, so refresh() (a new user object) doesn't
  // re-probe.
  const [probe, setProbe] = useState<{ userId: string; isAdmin: boolean } | null>(null);

  useEffect(() => {
    if (!userId) {
      setProbe(null);
      return;
    }
    let cancelled = false;
    apiFetch<AdminMeResponse>('/v1/admin/me')
      .then(() => true, () => false)
      .then((isAdmin) => { if (!cancelled) setProbe({ userId, isAdmin }); });
    return () => { cancelled = true; };
  }, [userId]);

  const resolved = userId !== null && probe?.userId === userId;
  const isAdmin = resolved && probe.isAdmin;
  const loading = userId !== null && !resolved;

  return (
    <AdminContext.Provider value={{ isAdmin, loading }}>
      {children}
    </AdminContext.Provider>
  );
}

export function useAdmin(): AdminContextValue {
  return useContext(AdminContext);
}
