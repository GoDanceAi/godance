import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, type AdminMeResponse, type UserRole } from './api';
import { useAuth } from './auth';

// Admin gating for the SPA. One GET /v1/admin/me per login: 200 → show the
// Admin nav + routes for the caller's role, 403 → plain user. The server-side
// role filter remains the real gate — this only decides what the UI renders.
// A role change ends the account's sessions, so a new login re-probes.

interface AdminContextValue {
  isAdmin: boolean; // may open the console: any role /v1/admin/me admits
  role: UserRole | null; // null for a plain user
  loading: boolean; // true while the /v1/admin/me probe is in flight
}

const AdminContext = createContext<AdminContextValue>({ isAdmin: false, role: null, loading: false });

export function AdminProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  // The probe result, tagged with the user it was made for. Loading is
  // derived from it rather than set in the effect: effects run after render,
  // so the first render with a user would otherwise read "not loading, not
  // admin" and AdminLayout would bounce a cold load of /admin/* to the
  // dashboard. Keyed on the id, so refresh() (a new user object) doesn't
  // re-probe.
  const [probe, setProbe] = useState<{ userId: string; role: UserRole | null } | null>(null);

  useEffect(() => {
    if (!userId) {
      setProbe(null);
      return;
    }
    let cancelled = false;
    apiFetch<AdminMeResponse>('/v1/admin/me')
      .then((me) => me.role, () => null)
      .then((role) => { if (!cancelled) setProbe({ userId, role }); });
    return () => { cancelled = true; };
  }, [userId]);

  const resolved = userId !== null && probe?.userId === userId;
  const role = resolved ? probe.role : null;
  const loading = userId !== null && !resolved;

  return (
    <AdminContext.Provider value={{ isAdmin: role !== null, role, loading }}>
      {children}
    </AdminContext.Provider>
  );
}

export function useAdmin(): AdminContextValue {
  return useContext(AdminContext);
}
