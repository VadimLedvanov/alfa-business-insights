import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Me } from '../api/types';
import { api } from '../api/client';
import { hasToken, kcLogin, kcLogout } from './keycloak';

interface AuthState {
  me: Me | null;
  loading: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => void;
  isAdmin: boolean;
}
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(hasToken);
  const signOut = () => {
    kcLogout(); setMe(null);
    try { sessionStorage.removeItem('abi.auth.user'); } catch { /* Old demo session. */ }
    try { sessionStorage.removeItem('abi.company'); } catch { /* Storage may be disabled. */ }
    window.dispatchEvent(new Event('abi:identity-changed'));
  };
  useEffect(() => {
    let alive = true;
    const expired = () => { if (alive) signOut(); };
    window.addEventListener('abi:unauthorized', expired);
    if (hasToken()) api.me().then((user) => { if (alive) setMe(user); }).catch(expired).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; window.removeEventListener('abi:unauthorized', expired); };
  }, []);
  const signIn = async (username: string, password: string) => {
    signOut();
    try { await kcLogin(username, password); setMe(await api.me()); }
    catch (error) { signOut(); throw error; }
  };
  return <AuthContext.Provider value={{ me, loading, signIn, signOut, isAdmin: !!me?.roles.includes('SYSTEM_ADMIN') }}>{children}</AuthContext.Provider>;
}
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('AuthProvider required');
  return context;
}
