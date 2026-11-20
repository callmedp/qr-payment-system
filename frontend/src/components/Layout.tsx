import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Layout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;
  const isMerchant = user.role === 'MERCHANT';

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="logo">▦</span> QrPay
          <span className="role-tag">{isMerchant ? 'Merchant' : 'Customer'}</span>
        </div>
        <nav>
          {isMerchant ? (
            <NavLink to="/merchant" end>Dashboard</NavLink>
          ) : (
            <>
              <NavLink to="/customer" end>Wallet</NavLink>
              <NavLink to="/customer/pay">Scan &amp; Pay</NavLink>
            </>
          )}
        </nav>
        <div className="user">
          <span className="user-name">{user.fullName}</span>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              signOut();
              navigate('/login');
            }}
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
