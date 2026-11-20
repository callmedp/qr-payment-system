import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import type { Role } from './api/types';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import AuthPage from './pages/AuthPage';
import CustomerHome from './pages/customer/CustomerHome';
import ScanPay from './pages/customer/ScanPay';
import MerchantDashboard from './pages/merchant/MerchantDashboard';

function homeFor(role: Role) {
  return role === 'MERCHANT' ? '/merchant' : '/customer';
}

function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== role) return <Navigate to={homeFor(user.role)} replace />;
  return <>{children}</>;
}

export default function App() {
  const { user } = useAuth();
  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to={homeFor(user.role)} replace /> : <AuthPage />} />
      <Route
        element={
          <RequireRole role="CUSTOMER">
            <Layout />
          </RequireRole>
        }
      >
        <Route path="/customer" element={<CustomerHome />} />
        <Route path="/customer/pay" element={<ScanPay />} />
      </Route>
      <Route
        element={
          <RequireRole role="MERCHANT">
            <Layout />
          </RequireRole>
        }
      >
        <Route path="/merchant" element={<MerchantDashboard />} />
      </Route>
      <Route path="*" element={<Navigate to={user ? homeFor(user.role) : '/login'} replace />} />
    </Routes>
  );
}
