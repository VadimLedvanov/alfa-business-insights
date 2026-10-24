import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Me, Role } from '../api/types';
import { IS_STUB, api, stubUser } from '../api/client';

interface AuthState {
  me: Me | null;
  signInStub: (userKey: string) => void;
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

  // В live-режиме можно будет подтянуть реального пользователя через Keycloak + /auth/me.
  void api;
  void IS_STUB;

  const value = useMemo<AuthState>(() => {
    const persist = (u: Me | null) => {
      try {
        if (u) sessionStorage.setItem(KEY, JSON.stringify(u));
        else sessionStorage.removeItem(KEY);
      } catch {
        /* приватный режим браузера — не критично */
      }
    };
    return {
      me,
      isAdmin: !!me?.roles.includes('SYSTEM_ADMIN' as Role),
      signInStub: (userKey: string) => {
        const u = stubUser(userKey) ?? null;
        setMe(u);
        persist(u);
      },
      signOut: () => {
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
