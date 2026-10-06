import { useState } from 'react';
import { Link, NavLink, useNavigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, formatCents } from '../lib/auth';
import { useAdmin } from '../lib/admin';
import { LogoLockup, ThemeToggle } from './Brand';
import { ServerStatusBadge } from './ServerStatusBadge';
import { AdminProfileModal } from './AdminProfileModal';

interface Props { children: ReactNode }

export default function Layout({ children }: Props) {
  const { user, logout } = useAuth();
  const { isAdmin, adminEmail } = useAdmin();
  const location = useLocation();
  const navigate = useNavigate();

  const [showProfileModal, setShowProfileModal] = useState(false);

  const isAdminRoute = location.pathname.startsWith('/admin');

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `nav-link ${isActive ? 'active' : ''}`;

  return (
    <div className="min-h-screen flex flex-col bg-bg">
      {isAdminRoute ? (
        /* Dedicated Admin Console Header */
        <header className="bg-surface border-b border-border sticky top-0 z-40 backdrop-blur-md">
          <div className="px-6 py-2.5 flex items-center justify-between">
            {/* Left: Brand + Admin Console Badge */}
            <div className="flex items-center gap-3">
              <Link to="/admin/overview" className="flex items-center gap-2.5" aria-label="Godance Admin Console">
                <LogoLockup size={18} />
                <span className="text-[10px] font-bold tracking-wider uppercase px-2 py-0.5 rounded-md bg-purple-950/80 text-purple-300 border border-purple-800 shadow-2xs">
                  Admin Console
                </span>
              </Link>
            </div>

            {/* Center: Live Server & Gateway Health Connection Indicator */}
            <div className="hidden sm:flex items-center">
              <ServerStatusBadge />
            </div>

            {/* Right: Setup, Go to App, Admin Profile with Role, Theme, Logout */}
            <div className="flex items-center gap-2.5">
              <Link
                to="/dashboard"
                className="text-xs font-medium text-fg-muted hover:text-fg transition-colors flex items-center gap-1 px-2.5 py-1 rounded-md hover:bg-surface-alt"
                title="Switch to Consumer App"
              >
                <span>Go to App</span>
                <span className="text-[10px]">↗</span>
              </Link>

              {/* Admin Profile & Session Cog Icon Button */}
              <button
                onClick={() => setShowProfileModal(true)}
                className="p-1.5 rounded-md text-fg-muted hover:text-fg hover:bg-surface-alt transition-colors"
                title="Admin Profile & Session Privileges"
                aria-label="Admin Profile"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
              </button>

              <div className="h-4 w-px bg-border hidden sm:block" />

              {/* Admin User Info & Role */}
              <div className="flex items-center gap-2">
                <span className="text-xs text-fg-muted font-medium hidden md:inline">
                  {user?.email || adminEmail || (import.meta.env.DEV ? 'admin@godance.ai' : 'Operator')}
                </span>
                <span className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-bold tracking-wider bg-violet-950/80 text-violet-300 border border-violet-800 shadow-2xs">
                  {user?.role === 'ADMIN' ? 'SUPERADMIN' : (user?.role || 'ADMIN')}
                </span>
              </div>


              <ThemeToggle compact />

              <button
                onClick={handleLogout}
                className="text-xs font-semibold text-rose-400 hover:text-rose-300 hover:bg-rose-950/40 px-2.5 py-1 rounded-md transition-colors border border-rose-900/50"
                title="Log out of Admin Console"
              >
                Log out
              </button>
            </div>
          </div>
        </header>
      ) : (
        /* Standard Consumer Header */
        <header className="bg-surface border-b border-border">
          <div className="mx-auto max-w-6xl px-6 py-3 flex items-center justify-between">
            <Link to="/" aria-label="Godance">
              <LogoLockup size={18} />
            </Link>

            {user ? (
              <nav className="hidden md:flex items-center gap-1">
                <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
                <NavLink to="/chat" className={linkClass}>Chat</NavLink>
                <NavLink to="/keys" className={linkClass}>API keys</NavLink>
                <NavLink to="/billing" className={linkClass}>Billing</NavLink>
                <NavLink to="/settings" className={linkClass}>Settings</NavLink>
                {(isAdmin || import.meta.env.DEV) && <NavLink to="/admin" className={linkClass}>Admin</NavLink>}
              </nav>
            ) : (
              import.meta.env.DEV && (
                <nav className="hidden md:flex items-center gap-1">
                  <NavLink to="/admin" className={linkClass}>Admin</NavLink>
                </nav>
              )
            )}

            {user ? (
              <div className="flex items-center gap-3">
                <span className="hidden md:inline text-sm text-fg-muted">
                  <span className="font-medium text-fg">{formatCents(user.creditBalanceCents)}</span>
                  <span className="text-fg-faint"> · {user.email}</span>
                </span>
                <ThemeToggle compact />
                <button
                  onClick={handleLogout}
                  className="text-sm font-medium text-fg-muted hover:text-fg transition-colors"
                >
                  Log out
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-3">
                <ThemeToggle compact />
                <Link to="/login" className="text-sm font-medium text-fg-muted hover:text-fg transition-colors">
                  Sign in
                </Link>
                <Link to="/signup" className="btn btn-primary btn-sm">
                  Start free
                </Link>
              </div>
            )}
          </div>
        </header>
      )}

      <main className="flex-1">{children}</main>

      <footer className="border-t border-border py-4 text-center text-xs text-fg-faint">
        © {new Date().getFullYear()} Godance ·{' '}
        <a href="https://godance.ai" className="text-fg-muted hover:text-brand-600 transition-colors">
          godance.ai
        </a>
      </footer>

      {/* Admin Profile & Session Modal */}
      <AdminProfileModal isOpen={showProfileModal} onClose={() => setShowProfileModal(false)} />
    </div>
  );
}


