import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import { AuthProvider } from './lib/auth';
import { AdminProvider } from './lib/admin';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import VerifyEmailPage from './pages/VerifyEmailPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';
import DashboardPage from './pages/DashboardPage';
import ChatPage from './pages/ChatPage';
import ApiKeysPage from './pages/ApiKeysPage';
import BillingPage from './pages/BillingPage';
import SettingsPage from './pages/SettingsPage';
import AdminLayout from './pages/admin/AdminLayout';
import ProposalsPage from './pages/admin/ProposalsPage';
import CatalogPage from './pages/admin/CatalogPage';
import UsersPage from './pages/admin/UsersPage';
import OverviewPage from './pages/admin/OverviewPage';

export default function App() {
  return (
    <AuthProvider>
      <AdminProvider>
      <Layout>
        <Routes>
          {/* Public */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/oauth/callback" element={<OAuthCallbackPage />} />

          {/* Authenticated */}
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
          <Route path="/chat"      element={<ProtectedRoute><ChatPage /></ProtectedRoute>} />
          <Route path="/keys"      element={<ProtectedRoute><ApiKeysPage /></ProtectedRoute>} />
          <Route path="/billing"   element={<ProtectedRoute><BillingPage /></ProtectedRoute>} />
          <Route path="/settings"  element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />

          {/* Admin console — nav + routes render only after /v1/admin/me
              returns 200; the server-side allowlist stays the real gate. */}
          <Route path="/admin" element={<ProtectedRoute><AdminLayout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/admin/proposals" replace />} />
            <Route path="proposals" element={<ProposalsPage />} />
            <Route path="catalog"   element={<CatalogPage />} />
            <Route path="users"     element={<UsersPage />} />
            <Route path="overview"  element={<OverviewPage />} />
          </Route>

          {/* Fallback */}
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </Layout>
      </AdminProvider>
    </AuthProvider>
  );
}
