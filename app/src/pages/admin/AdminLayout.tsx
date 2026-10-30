import { Link, Navigate, NavLink, Outlet } from 'react-router-dom';
import { useAdmin } from '../../lib/admin';

// Shell for /admin/*: sub-nav tabs + client-side guard. Plain users are
// bounced to the dashboard (the backend role gate 403s them regardless).
export default function AdminLayout() {
  const { isAdmin, role, needsTwoFactor, loading } = useAdmin();

  if (loading) {
    return <div className="mx-auto max-w-6xl px-6 py-12 text-sm text-fg-faint">Checking access…</div>;
  }
  if (needsTwoFactor) {
    return (
      <div className="mx-auto max-w-3xl px-6 py-8">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Admin</h1>
        <section className="mt-8 card">
          <h2 className="font-semibold text-fg">Turn on two-factor sign-in</h2>
          <p className="mt-1 text-sm text-fg-muted">
            The admin console needs two-factor sign-in on your account. It takes a minute,
            from your settings.
          </p>
          <Link to="/settings" className="mt-4 btn btn-sm btn-primary">Go to settings</Link>
        </section>
      </div>
    );
  }
  if (!isAdmin) {
    return <Navigate to="/dashboard" replace />;
  }

  const tabClass = ({ isActive }: { isActive: boolean }) =>
    `nav-link ${isActive ? 'active' : ''}`;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Admin</h1>
        <span className="pill pill-violet">{role?.toLowerCase()} console</span>
      </div>
      <nav className="mt-4 flex items-center gap-1 border-b border-border pb-2">
        <NavLink to="/admin/proposals" className={tabClass}>Proposals</NavLink>
        <NavLink to="/admin/catalog" className={tabClass}>Catalog</NavLink>
        <NavLink to="/admin/users" className={tabClass}>Users</NavLink>
        <NavLink to="/admin/overview" className={tabClass}>Overview</NavLink>
      </nav>
      <div className="mt-6">
        <Outlet />
      </div>
    </div>
  );
}
