import { Link, NavLink, useNavigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, formatCents } from '../lib/auth';
import { useAdmin } from '../lib/admin';
import { LogoLockup, ThemeToggle } from './Brand';

interface Props { children: ReactNode }

export default function Layout({ children }: Props) {
  const { user, logout } = useAuth();
  const { isAdmin } = useAdmin();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `nav-link ${isActive ? 'active' : ''}`;

  return (
    <div className="min-h-screen flex flex-col bg-bg">
      <header className="bg-surface border-b border-border">
        <div className="mx-auto max-w-6xl px-6 py-3 flex items-center justify-between">
          <Link to="/" aria-label="Godance">
            <LogoLockup size={18} />
          </Link>

          {user && (
            <nav className="hidden md:flex items-center gap-1">
              <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
              <NavLink to="/chat" className={linkClass}>Chat</NavLink>
              <NavLink to="/keys" className={linkClass}>API keys</NavLink>
              <NavLink to="/billing" className={linkClass}>Billing</NavLink>
              <NavLink to="/settings" className={linkClass}>Settings</NavLink>
              {isAdmin && <NavLink to="/admin" className={linkClass}>Admin</NavLink>}
            </nav>
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

      <main className="flex-1">{children}</main>

      <footer className="border-t border-border py-4 text-center text-xs text-fg-faint">
        © {new Date().getFullYear()} Godance ·{' '}
        <a href="https://godance.ai" className="text-fg-muted hover:text-brand-600 transition-colors">
          godance.ai
        </a>
      </footer>
    </div>
  );
}
