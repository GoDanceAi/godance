import { useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, formatCents } from '../lib/auth';
import { useAdmin } from '../lib/admin';
import { carryDestination, destinationFrom } from '../lib/return-to';
import { LogoLockup, ThemeToggle } from './Brand';

interface Props { children: ReactNode }

const NAV: { to: string; label: string }[] = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/chat', label: 'Chat' },
  { to: '/create', label: 'Create' },
  { to: '/history', label: 'History' },
  { to: '/keys', label: 'API keys' },
  { to: '/billing', label: 'Billing' },
  { to: '/settings', label: 'Settings' },
];

export default function Layout({ children }: Props) {
  const { user, logout } = useAuth();
  const { isAdmin, needsTwoFactor } = useAdmin();
  const navigate = useNavigate();
  // On the sign-in and sign-up pages, the header's links keep where the
  // visitor was going too (lib/return-to).
  const from = destinationFrom(useLocation().state);

  const [logoutFailed, setLogoutFailed] = useState(false);

  const handleLogout = async () => {
    setLogoutFailed(false);
    try {
      await logout();
      navigate('/login');
    } catch {
      setLogoutFailed(true);
    }
  };

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `nav-link ${isActive ? 'active' : ''}`;

  // A child's account waiting for consent reaches no page but the waiting screen.
  const showNav = user !== null && user.ageStatus !== 'CONSENT_PENDING';
  const links = (
    <>
      {NAV.map((item) => (
        <NavLink key={item.to} to={item.to} className={linkClass}>{item.label}</NavLink>
      ))}
      {(isAdmin || needsTwoFactor) && <NavLink to="/admin" className={linkClass}>Admin</NavLink>}
    </>
  );

  return (
    <div className="min-h-screen flex flex-col bg-bg">
      <header className="bg-surface border-b border-border">
        <div className="mx-auto max-w-6xl px-6 py-3 flex items-center justify-between gap-4">
          <Link to="/" aria-label="Godance" className="shrink-0">
            <LogoLockup size={18} />
          </Link>

          {/* In the header row where it fits; below it, its own scrolling row. */}
          {showNav && (
            <nav aria-label="Main" className="hidden lg:flex items-center gap-1">
              {links}
            </nav>
          )}

          {user ? (
            <div className="flex items-center gap-3">
              <span className="hidden md:inline text-sm text-fg-muted whitespace-nowrap">
                <span className="font-medium text-fg">{formatCents(user.creditBalanceCents)}</span>
                {user.hasEmail && <span className="hidden xl:inline text-fg-faint"> · {user.email}</span>}
              </span>
              <ThemeToggle compact />
              <button
                onClick={() => void handleLogout()}
                className="text-sm font-medium text-fg-muted hover:text-fg transition-colors"
              >
                Log out
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <ThemeToggle compact />
              <Link to="/login" state={carryDestination(from)}
                    className="text-sm font-medium text-fg-muted hover:text-fg transition-colors">
                Sign in
              </Link>
              <Link to="/signup" state={carryDestination(from)} className="btn btn-primary btn-sm">
                Start free
              </Link>
            </div>
          )}
        </div>

        {showNav && (
          <nav aria-label="Main" className="lg:hidden border-t border-border">
            <div className="mx-auto max-w-6xl px-4 py-1.5 flex items-center gap-1 overflow-x-auto whitespace-nowrap [scrollbar-width:none]">
              {links}
            </div>
          </nav>
        )}
      </header>

      {logoutFailed && (
        <p role="alert" className="mx-auto w-full max-w-6xl px-6 pt-3 text-right text-sm text-danger">
          Couldn't log out. Try again.
        </p>
      )}

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
