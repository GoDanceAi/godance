import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from '../lib/auth';

/**
 * Gate for authenticated routes. While the initial /me check is in flight
 * we render a tiny skeleton so the page doesn't flash to /login and back.
 */
export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[40vh] text-gray-400 text-sm">
        Loading…
      </div>
    );
  }

  if (!user) {
    // Preserve the intended destination so /login can redirect back after auth.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <>{children}</>;
}
