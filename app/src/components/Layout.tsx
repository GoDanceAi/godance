import { Link, NavLink, useNavigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, formatCents } from '../lib/auth';

interface Props { children: ReactNode }

export default function Layout({ children }: Props) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `px-3 py-2 rounded-md text-sm font-medium ${
      isActive ? 'bg-brand-50 text-brand-700' : 'text-gray-700 hover:bg-gray-100'
    }`;

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-gray-200">
        <div className="mx-auto max-w-6xl px-6 py-3 flex items-center justify-between">
          <Link to="/" className="text-xl font-bold tracking-tight">
            <span className="text-brand-600">go</span>dance
          </Link>
          {user && (
            <nav className="hidden md:flex items-center gap-1">
              <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
              <NavLink to="/chat" className={linkClass}>Chat</NavLink>
              <NavLink to="/keys" className={linkClass}>API keys</NavLink>
              <NavLink to="/billing" className={linkClass}>Billing</NavLink>
              <NavLink to="/settings" className={linkClass}>Settings</NavLink>
            </nav>
          )}
          {user ? (
            <div className="flex items-center gap-4">
              <span className="text-sm text-gray-600 hidden md:inline">
                {formatCents(user.creditBalanceCents)} · {user.email}
              </span>
              <button
                onClick={handleLogout}
                className="text-sm font-medium text-gray-700 hover:text-gray-900"
              >
                Log out
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <Link to="/login" className="text-sm font-medium hover:text-brand-600">
                Sign in
              </Link>
              <Link
                to="/signup"
                className="bg-brand-600 hover:bg-brand-700 text-white text-sm font-medium px-4 py-2 rounded-md"
              >
                Start free
              </Link>
            </div>
          )}
        </div>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="border-t border-gray-200 py-4 text-center text-xs text-gray-500">
        © {new Date().getFullYear()} Godance ·{' '}
        <a href="https://godance.ai" className="hover:text-brand-600">godance.ai</a>
      </footer>
    </div>
  );
}
