import { useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, formatCents } from '../lib/auth';
import { useAdmin } from '../lib/admin';
import { useCommunity } from '../lib/community-context';
import { useReferral } from '../lib/referral';
import { carryDestination, destinationFrom } from '../lib/return-to';
import { LogoLockup, ThemeToggle } from './Brand';

interface Props { children: ReactNode }

const GROUPS = [
  { label: 'Create', links: [{ to: '/create/image', label: 'Image Studio' }, { to: '/create/video', label: 'Video Studio' }, { to: '/chat', label: 'Chat' }] },
  { label: 'Library', links: [{ to: '/history', label: 'History' }, { to: '/recipes', label: 'Recipes' }, { to: '/batches', label: 'Batches' }] },
  { label: 'Developer tools', links: [{ to: '/keys', label: 'API keys' }] },
  { label: 'Account', links: [{ to: '/billing', label: 'Billing' }, { to: '/settings', label: 'Settings' }] },
];

export default function Layout({ children }: Props) {
  const { user, logout } = useAuth();
  const { isAdmin, needsTwoFactor } = useAdmin();
  const { info: referral } = useReferral();
  const { open: communityOpen } = useCommunity();
  const navigate = useNavigate();
  // On the sign-in and sign-up pages, the header's links keep where the
  // visitor was going too (lib/return-to).
  const location = useLocation();
  const from = destinationFrom(location.state);
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
    `nav-link inline-flex ${isActive ? 'active' : ''}`;

  // A child's account waiting for consent reaches no page but the waiting screen.
  const showNav = user !== null && user.ageStatus !== 'CONSENT_PENDING';

  return (
    <div className="min-h-screen flex flex-col bg-bg">
      <header className="bg-surface border-b border-border">
        <div className="mx-auto max-w-6xl px-6 py-3 flex items-center justify-between gap-4">
          <Link to="/" aria-label="Godance" className="shrink-0">
            <LogoLockup size={18} />
          </Link>

          {user ? (
            // The links keep their room; a long address is the part that gives way.
            <div className="flex min-w-0 items-center gap-3">
              <span className="hidden md:flex min-w-0 items-baseline text-sm text-fg-muted whitespace-nowrap">
                <span className="font-medium text-fg">{formatCents(user.creditBalanceCents)}</span>
                {user.hasEmail && (
                  <span title={user.email ?? undefined} className="hidden xl:block min-w-0 truncate text-fg-faint">
                    &nbsp;· {user.email}
                  </span>
                )}
              </span>
              <ThemeToggle compact />
              <button
                onClick={() => void handleLogout()}
                className="shrink-0 whitespace-nowrap text-sm font-medium text-fg-muted hover:text-fg transition-colors"
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
          <nav aria-label="Main" className="border-t border-border">
            <details open className="mx-auto max-w-6xl px-6 py-2">
              <summary className="cursor-pointer py-1 text-sm font-semibold text-fg">Menu</summary>
              <div className="pt-2 pb-3">
                <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
                <div className="mt-3 grid grid-cols-1 gap-4 min-[360px]:grid-cols-2 md:grid-cols-4">
                  {GROUPS.map((group) => (
                    <div key={group.label} role="group" aria-label={group.label}>
                      <p className="mb-1 text-xs font-semibold text-fg-subtle">{group.label}</p>
                      <div className="flex flex-col items-start gap-1">
                        {group.links.map((item) => <NavLink key={item.to} to={item.to} className={linkClass}>{item.label}</NavLink>)}
                        {group.label === 'Library' && communityOpen && <NavLink to="/following" className={linkClass}>Following</NavLink>}
                        {group.label === 'Developer tools' && <a href="https://godance.ai/docs/#mcp" className="nav-link">MCP documentation</a>}
                        {group.label === 'Account' && referral?.enabled && <NavLink to="/referral" className={linkClass}>Invite</NavLink>}
                        {group.label === 'Account' && (isAdmin || needsTwoFactor) && <NavLink to="/admin" className={linkClass}>Admin</NavLink>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </details>
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
