import { useState } from 'react';
import { Navigate, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAdmin } from '../../lib/admin';
import { ServerStatusBadge } from '../../components/ServerStatusBadge';


// Nav group and item definitions
interface NavItem {
  to: string;
  label: string;
  shortLabel: string;
  icon: (active: boolean) => React.ReactNode;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

const NAV_SECTIONS: NavSection[] = [
  {
    title: 'ANALYTICS & BILLING',
    items: [
      {
        to: '/admin/overview',
        label: 'Financial Overview',
        shortLabel: 'Overview',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
          </svg>
        ),
      },
      {
        to: '/admin/payments',
        label: 'Billing & Payments',
        shortLabel: 'Payments',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
          </svg>
        ),
      },
    ],
  },
  {
    title: 'USER GOVERNANCE',
    items: [
      {
        to: '/admin/users',
        label: 'Users & Accounts',
        shortLabel: 'Users',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
          </svg>
        ),
      },
    ],
  },
  {
    title: 'OPERATIONS',
    items: [
      {
        to: '/admin/jobs',
        label: 'Jobs Monitoring',
        shortLabel: 'Jobs',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
          </svg>
        ),
      },
      {
        to: '/admin/providers',
        label: 'Provider Health',
        shortLabel: 'Providers',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        ),
      },
    ],
  },
  {
    title: 'MODELS & PRICING',
    items: [
      {
        to: '/admin/catalog',
        label: 'Model Catalog',
        shortLabel: 'Catalog',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2 1.5 3 3.5 3h9c2 0 3.5-1 3.5-3V7c0-2-1.5-3-3.5-3h-9C5.5 4 4 5 4 7zm0 3h16" />
          </svg>
        ),
      },
      {
        to: '/admin/proposals',
        label: 'Price Proposals',
        shortLabel: 'Proposals',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
          </svg>
        ),
      },
    ],
  },
  {
    title: 'SECURITY & AUDITS',
    items: [
      {
        to: '/admin/audits',
        label: 'Audit Log Center',
        shortLabel: 'Audits',
        icon: (active) => (
          <svg className={`w-4 h-4 ${active ? 'text-primary' : 'text-fg-muted'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
          </svg>
        ),
      },
    ],
  },
];

export default function AdminLayout() {
  const { isAdmin, loading } = useAdmin();
  const location = useLocation();
  const isDev = import.meta.env.DEV;

  const [collapsed, setCollapsed] = useState<boolean>(() => {
    return localStorage.getItem('godance.admin.sidebar_collapsed') === 'true';
  });

  const toggleCollapse = () => {
    setCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem('godance.admin.sidebar_collapsed', String(next));
      return next;
    });
  };

  if (loading && !isDev) {
    return (
      <div className="mx-auto max-w-6xl px-6 py-12 text-sm text-fg-faint">
        Checking access…
      </div>
    );
  }
  if (!isAdmin && !isDev) {
    return <Navigate to="/dashboard" replace />;
  }

  // Derive active label for breadcrumb
  let activeLabel = 'Console';
  for (const s of NAV_SECTIONS) {
    for (const item of s.items) {
      if (location.pathname === item.to) {
        activeLabel = item.label;
        break;
      }
    }
  }

  return (
    <div className="flex min-h-[calc(100vh-57px)] bg-bg">
      {/* Collapsible Left Sidebar */}
      <aside
        className={`border-r border-border bg-surface transition-all duration-200 flex flex-col shrink-0 ${
          collapsed ? 'w-16' : 'w-60'
        }`}
      >
        {/* Sidebar Header - Strict h-12 height to align with top breadcrumb bar */}
        <div className="h-12 border-b border-border px-3 flex items-center justify-between shrink-0">
          {!collapsed && (
            <div className="flex items-center gap-2 overflow-hidden px-1">
              <span className="font-semibold text-sm tracking-tight text-fg truncate">
                Admin Console
              </span>
              <span className="pill pill-violet text-[10px] uppercase font-bold py-0 px-1.5">
                v1.2
              </span>
            </div>
          )}
          <button
            onClick={toggleCollapse}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            className="p-1.5 rounded-md hover:bg-surface-alt text-fg-muted hover:text-fg transition-colors mx-auto"
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            <svg
              className="w-4 h-4 transition-transform"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              {collapsed ? (
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 5l7 7-7 7M5 5l7 7-7 7" />
              ) : (
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
              )}
            </svg>
          </button>
        </div>

        {/* Navigation Sections */}
        <nav className="flex-1 overflow-y-auto py-3 px-2 space-y-3">
          {NAV_SECTIONS.map((sec, idx) => (
            <div key={sec.title}>
              {/* Smooth transition for section title */}
              <div
                className={`overflow-hidden transition-all duration-200 ease-in-out ${
                  collapsed ? 'max-h-0 opacity-0 mb-0' : 'max-h-5 opacity-100 mb-1.5'
                }`}
              >
                <p className="px-2.5 text-[10px] font-bold uppercase tracking-wider text-fg-faint truncate">
                  {sec.title}
                </p>
              </div>

              {/* Subtle separator in collapsed mode */}
              {collapsed && idx > 0 && (
                <div className="my-2 mx-1 border-t border-border/40" />
              )}

              <div className="space-y-0.5">
                {sec.items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    title={collapsed ? item.label : undefined}
                    className={({ isActive }) =>
                      `flex items-center gap-2.5 px-2.5 py-2 rounded-md text-xs font-medium transition-colors ${
                        isActive
                          ? 'bg-primary/10 text-primary font-semibold'
                          : 'text-fg-muted hover:bg-surface-alt hover:text-fg'
                      } ${collapsed ? 'justify-center px-0' : ''}`
                    }
                  >
                    {({ isActive }) => (
                      <>
                        {item.icon(isActive)}
                        {!collapsed && <span className="truncate">{item.label}</span>}
                      </>
                    )}
                  </NavLink>
                ))}
              </div>
            </div>
          ))}
        </nav>

        {/* Sidebar Footer */}
        <div className="p-3 border-t border-border shrink-0">
          {!collapsed ? (
            <div className="text-[11px] text-fg-faint flex items-center justify-between">
              <span>Gateway Health</span>
              <ServerStatusBadge compact />
            </div>
          ) : (
            <div className="flex justify-center" title="Gateway Health">
              <ServerStatusBadge compact />
            </div>
          )}
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col min-w-0 overflow-y-auto">
        {/* Top Breadcrumb Bar - Strict h-12 height to align with sidebar header */}
        <div className="h-12 border-b border-border bg-surface px-6 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2 text-xs text-fg-muted">
            <span className="text-fg-faint">Admin</span>
            <span>/</span>
            <span className="font-semibold text-fg">{activeLabel}</span>
          </div>
          <div className="flex items-center gap-3">
            <div className="sm:hidden">
              <ServerStatusBadge compact />
            </div>
            <span className="pill pill-violet text-xs hidden sm:inline">Operator Space</span>
          </div>
        </div>



        {/* Outlet View Container */}
        <div className="flex-1 p-6 max-w-7xl w-full mx-auto">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
