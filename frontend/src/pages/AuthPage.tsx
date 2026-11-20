import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { Api } from '../api/endpoints';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner } from '../components/ui';

const CATEGORIES = [
  { code: '5812', label: 'Restaurants & cafés' },
  { code: '5411', label: 'Grocery stores' },
  { code: '5999', label: 'Retail shops' },
  { code: '4121', label: 'Taxis & rideshare' },
  { code: '5912', label: 'Pharmacies' },
];

export default function AuthPage() {
  const { signIn } = useAuth();
  const [tab, setTab] = useState<'login' | 'register'>('login');
  const [role, setRole] = useState<Role>('CUSTOMER');
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    password: '',
    pin: '',
    businessName: '',
    categoryCode: '5812',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response =
        tab === 'login'
          ? await Api.login(form.email, form.password)
          : role === 'CUSTOMER'
            ? await Api.registerCustomer({ fullName: form.fullName, email: form.email, password: form.password, pin: form.pin })
            : await Api.registerMerchant({
                fullName: form.fullName,
                email: form.email,
                password: form.password,
                businessName: form.businessName,
                categoryCode: form.categoryCode,
              });
      signIn(response);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-hero">
        <div className="brand brand-lg">
          <span className="logo">▦</span> QrPay
        </div>
        <p className="lede">Scan. Confirm. Paid.</p>
        <ul className="hero-points">
          <li>Merchants generate signed, single-use QR codes</li>
          <li>Customers pay from a wallet with a PIN</li>
          <li>Merchants see the payment land in real time</li>
        </ul>
        <p className="hint">
          Tip: open a second browser profile (or a private window) so you can be the merchant in one and the customer in
          the other.
        </p>
      </div>

      <form className="card auth-card" onSubmit={submit}>
        <div className="segmented">
          <button type="button" className={tab === 'login' ? 'active' : ''} onClick={() => setTab('login')}>
            Sign in
          </button>
          <button type="button" className={tab === 'register' ? 'active' : ''} onClick={() => setTab('register')}>
            Create account
          </button>
        </div>

        {tab === 'register' && (
          <>
            <div className="role-picker">
              {(['CUSTOMER', 'MERCHANT'] as Role[]).map((r) => (
                <label key={r} className={`role-option ${role === r ? 'selected' : ''}`}>
                  <input type="radio" name="role" checked={role === r} onChange={() => setRole(r)} />
                  <strong>{r === 'CUSTOMER' ? 'I want to pay' : 'I want to get paid'}</strong>
                  <span className="hint">{r === 'CUSTOMER' ? 'Customer wallet + ₹10,000 demo balance' : 'Merchant with QR codes'}</span>
                </label>
              ))}
            </div>
            <label>
              Full name
              <input required value={form.fullName} onChange={set('fullName')} autoComplete="name" />
            </label>
          </>
        )}

        <label>
          Email
          <input required type="email" value={form.email} onChange={set('email')} autoComplete="email" />
        </label>
        <label>
          Password
          <input
            required
            type="password"
            minLength={tab === 'register' ? 8 : undefined}
            value={form.password}
            onChange={set('password')}
            autoComplete={tab === 'login' ? 'current-password' : 'new-password'}
          />
        </label>

        {tab === 'register' && role === 'CUSTOMER' && (
          <label>
            Wallet PIN <span className="hint">(4–6 digits, asked on every payment)</span>
            <input
              required
              inputMode="numeric"
              pattern="\d{4,6}"
              type="password"
              value={form.pin}
              onChange={set('pin')}
              autoComplete="off"
            />
          </label>
        )}

        {tab === 'register' && role === 'MERCHANT' && (
          <>
            <label>
              Business name
              <input required value={form.businessName} onChange={set('businessName')} />
            </label>
            <label>
              Category
              <select value={form.categoryCode} onChange={set('categoryCode')}>
                {CATEGORIES.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.label} ({c.code})
                  </option>
                ))}
              </select>
            </label>
          </>
        )}

        <ErrorBanner message={error} />
        <button className="btn btn-block" type="submit" disabled={busy}>
          {busy ? 'Please wait…' : tab === 'login' ? 'Sign in' : 'Create account'}
        </button>
      </form>
    </div>
  );
}
