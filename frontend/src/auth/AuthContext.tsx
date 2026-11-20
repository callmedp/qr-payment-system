import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { tokenStore } from '../api/client';
import type { AuthResponse, UserView } from '../api/types';

const USER_KEY = 'qrpay.user';

interface AuthState {
  user: UserView | null;
  signIn: (response: AuthResponse) => void;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function readStoredUser(): UserView | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw && tokenStore.get() ? (JSON.parse(raw) as UserView) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserView | null>(readStoredUser);

  const signIn = useCallback((response: AuthResponse) => {
    tokenStore.set(response.accessToken);
    try {
      localStorage.setItem(USER_KEY, JSON.stringify(response.user));
    } catch {
      /* ignore */
    }
    setUser(response.user);
  }, []);

  const signOut = useCallback(() => {
    tokenStore.clear();
    try {
      localStorage.removeItem(USER_KEY);
    } catch {
      /* ignore */
    }
    setUser(null);
  }, []);

  // any 401 from the API (expired token) signs the user out
  useEffect(() => {
    window.addEventListener('qrpay:unauthorized', signOut);
    return () => window.removeEventListener('qrpay:unauthorized', signOut);
  }, [signOut]);

  const value = useMemo(() => ({ user, signIn, signOut }), [user, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
