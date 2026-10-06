import { useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { useAdmin } from '../lib/admin';

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

export function AdminProfileModal({ isOpen, onClose }: Props) {
  const { user, logout } = useAuth();
  const { adminEmail } = useAdmin();
  const navigate = useNavigate();

  if (!isOpen) return null;

  const email = user?.email || adminEmail || (import.meta.env.DEV ? 'admin@godance.ai' : 'Operator');
  const role = user?.role === 'ADMIN' ? 'SUPERADMIN' : (user?.role || 'ADMIN');

  const handleViewAuditLogs = () => {
    onClose();
    navigate('/admin/audits');
  };

  const handleLogout = () => {
    onClose();
    logout();
    navigate('/login');
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
      <div className="bg-surface border border-border rounded-2xl max-w-md w-full p-6 shadow-2xl animate-in fade-in zoom-in-95 duration-150 text-fg">
        {/* Modal Header */}
        <div className="flex items-center justify-between pb-4 border-b border-border">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-primary/10 border border-primary/20 flex items-center justify-center text-primary font-bold text-base">
              ⚙️
            </div>
            <div>
              <h3 className="text-base font-bold text-fg">Operator Profile</h3>
              <p className="text-xs text-fg-muted">Admin session credentials & privileges</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-fg-muted hover:text-fg hover:bg-surface-alt transition-colors"
            title="Close"
          >
            ✕
          </button>
        </div>

        {/* Profile Card */}
        <div className="mt-4 p-3.5 bg-surface-alt border border-border rounded-xl space-y-2.5 text-xs">
          <div className="flex items-center justify-between">
            <span className="text-fg-muted">Account:</span>
            <span className="font-semibold text-fg font-mono">{email}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-fg-muted">Role & Tier:</span>
            <span className="pill pill-violet text-[10px] uppercase font-bold py-0.5 px-2">
              {role}
            </span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-fg-muted">User ID:</span>
            <span className="text-fg-faint font-mono text-[11px] truncate max-w-[200px]">
              {user?.id || 'usr_root_superadmin'}
            </span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-fg-muted">Session Status:</span>
            <span className="text-emerald-600 dark:text-emerald-400 font-medium flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              Active (JWT Authorized)
            </span>
          </div>
        </div>

        {/* Privileges List */}
        <div className="mt-4 space-y-2">
          <div className="text-[10px] font-bold uppercase tracking-wider text-fg-faint">
            Granted Capabilities
          </div>
          <div className="grid grid-cols-2 gap-2 text-[11px]">
            <div className="p-2 rounded-lg bg-surface border border-border flex items-center gap-1.5 text-fg">
              <span className="text-emerald-600 dark:text-emerald-400">✓</span> Users & Caps
            </div>
            <div className="p-2 rounded-lg bg-surface border border-border flex items-center gap-1.5 text-fg">
              <span className="text-emerald-600 dark:text-emerald-400">✓</span> Billing & Refunds
            </div>
            <div className="p-2 rounded-lg bg-surface border border-border flex items-center gap-1.5 text-fg">
              <span className="text-emerald-600 dark:text-emerald-400">✓</span> Model Catalog
            </div>
            <div className="p-2 rounded-lg bg-surface border border-border flex items-center gap-1.5 text-fg">
              <span className="text-emerald-600 dark:text-emerald-400">✓</span> Audit Inspection
            </div>
          </div>
        </div>

        {/* Quick Actions */}
        <div className="mt-5 pt-4 border-t border-border flex flex-col sm:flex-row items-center justify-between gap-2.5">
          <button
            onClick={handleViewAuditLogs}
            className="w-full sm:w-auto px-3.5 py-2 text-xs font-semibold text-primary bg-primary/10 hover:bg-primary/20 border border-primary/30 rounded-lg transition-colors flex items-center justify-center gap-1.5"
          >
            <span>📜</span> View Audit Logs
          </button>

          <div className="flex items-center gap-2 w-full sm:w-auto justify-end">
            <button
              onClick={handleLogout}
              className="px-3.5 py-2 text-xs font-semibold text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/40 border border-rose-300 dark:border-rose-900/60 rounded-lg transition-colors"
            >
              Sign out
            </button>
            <button
              onClick={onClose}
              className="px-3.5 py-2 text-xs font-medium bg-surface-alt hover:bg-border text-fg rounded-lg transition-colors border border-border"
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
