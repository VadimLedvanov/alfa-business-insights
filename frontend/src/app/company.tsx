import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

// Выбранная компания — текущая компания Радара. Хранится в sessionStorage,
// доступна в шапке оболочки и на всех экранах; её можно сменить в любой момент.

interface CompanyState {
  companyId: string | null;
  setCompany: (id: string | null) => void;
}

const CompanyContext = createContext<CompanyState | null>(null);

const KEY = 'abi.company';

export function CompanyProvider({ children }: { children: ReactNode }) {
  const [companyId, setCompanyId] = useState<string | null>(() => {
    try {
      return sessionStorage.getItem(KEY);
    } catch {
      return null;
    }
  });

  const setCompany = useCallback((id: string | null) => {
    setCompanyId(id);
    try {
      if (id) sessionStorage.setItem(KEY, id);
      else sessionStorage.removeItem(KEY);
    } catch {
      /* приватный режим браузера */
    }
  }, []);

  const value = useMemo<CompanyState>(() => ({ companyId, setCompany }), [companyId, setCompany]);
  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>;
}

export function useCompany(): CompanyState {
  const ctx = useContext(CompanyContext);
  if (!ctx) throw new Error('useCompany вне CompanyProvider');
  return ctx;
}
