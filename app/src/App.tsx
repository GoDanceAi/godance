import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import { AuthProvider } from './lib/auth';
import { AdminProvider } from './lib/admin';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import VerifyEmailPage from './pages/VerifyEmailPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';
import ParentConsentPage from './pages/ParentConsentPage';
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
          {/* Public: signed out, it asks the reader to sign in or to release the address. */}
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
          {/* Public: a parent approves a child's account from the emailed link. */}
          <Route path="/parent-consent" element={<ParentConsentPage />} />

          {/* Authenticated */}
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
          <Route path="/chat"      element={<ProtectedRoute><ChatPage /></ProtectedRoute>} />
          <Route path="/keys"      element={<ProtectedRoute><ApiKeysPage /></ProtectedRoute>} />
          <Route path="/billing"   element={<ProtectedRoute><BillingPage /></ProtectedRoute>} />
          <Route path="/settings"  element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />

          {/* Admin console — nav + routes render only after /v1/admin/me
              returns 200; the server-side role gate stays the real one. */}
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
