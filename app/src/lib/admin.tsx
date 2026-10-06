import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, type AdminMeResponse } from './api';
import { useAuth } from './auth';

// Admin gating for the SPA. One GET /v1/admin/me per login: 200 → show the
// Admin nav + routes, 403 → plain user. The server-side allowlist filter
// remains the real gate — this only decides what the UI renders.

interface AdminContextValue {
  isAdmin: boolean;
  adminEmail: string | null;
  loading: boolean; // true while the /v1/admin/me probe is in flight
}

const AdminContext = createContext<AdminContextValue>({ isAdmin: false, adminEmail: null, loading: false });

export function AdminProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [isAdmin, setIsAdmin] = useState(false);
  const [adminEmail, setAdminEmail] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!user) {
      setIsAdmin(false);
      setAdminEmail(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    apiFetch<AdminMeResponse>('/v1/admin/me')
      .then((res) => {
        if (!cancelled) {
          setIsAdmin(true);
          setAdminEmail(res.email);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setIsAdmin(false);
          setAdminEmail(null);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [user]);

  return (
    <AdminContext.Provider value={{ isAdmin, adminEmail, loading }}>
      {children}
    </AdminContext.Provider>
  );
}


export function useAdmin(): AdminContextValue {
  return useContext(AdminContext);
}
