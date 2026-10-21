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
  const [isAdmin, setIsAdmin] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!user) {
      setIsAdmin(false);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    apiFetch<AdminMeResponse>('/v1/admin/me')
      .then(() => { if (!cancelled) setIsAdmin(true); })
      .catch(() => { if (!cancelled) setIsAdmin(false); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [user]);

  return (
    <AdminContext.Provider value={{ isAdmin, loading }}>
      {children}
    </AdminContext.Provider>
  );
}

export function useAdmin(): AdminContextValue {
  return useContext(AdminContext);
}
