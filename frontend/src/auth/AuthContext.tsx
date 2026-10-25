import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Me, Role } from '../api/types';
import { IS_STUB, api, stubUser } from '../api/client';
import { kcLogin, kcLogout } from './keycloak';

interface AuthState {
  me: Me | null;
  /** Вход тестового пользователя: stub — сразу, live — через Keycloak + /auth/me. */
  signIn: (userKey: string) => Promise<void>;
  signOut: () => void;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

const KEY = 'abi.auth.user';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(() => {
    try {
      const raw = sessionStorage.getItem(KEY);
      return raw ? (JSON.parse(raw) as Me) : null;
    } catch {
      return null;
    }
  });

  const value = useMemo<AuthState>(() => {
    const persist = (u: Me | null) => {
      try {
        if (u) sessionStorage.setItem(KEY, JSON.stringify(u));
        else sessionStorage.removeItem(KEY);
      } catch {
        /* приватный режим браузера */
      }
    };
    return {
      me,
      isAdmin: !!me?.roles.includes('SYSTEM_ADMIN' as Role),
      signIn: async (userKey: string) => {
        if (IS_STUB) {
          const u = stubUser(userKey) ?? null;
          setMe(u);
          persist(u);
          return;
        }
        await kcLogin(userKey);
        const u = await api.me();
        setMe(u);
        persist(u);
      },
      signOut: () => {
        kcLogout();
        setMe(null);
        persist(null);
      },
    };
  }, [me]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth вне AuthProvider');
  return ctx;
}
