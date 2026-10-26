import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

// Простой хэш-роутер: путь живёт в location.hash (#/home, #/insight/evt-rate-14).
// Достаточно для кликабельного прототипа и работает статикой без серверных правил.

interface RouterState {
  path: string;
  navigate: (to: string) => void;
}

const RouterContext = createContext<RouterState | null>(null);

function currentPath(): string {
  const h = window.location.hash.replace(/^#/, '');
  return h || '/';
}

export function RouterProvider({ children }: { children: ReactNode }) {
  const [path, setPath] = useState(currentPath);

  useEffect(() => {
    const onHash = () => setPath(currentPath());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const value = useMemo<RouterState>(
    () => ({
      path,
      navigate: (to: string) => {
        if (currentPath() === to) return;
        window.location.hash = to;
        window.scrollTo(0, 0);
      },
    }),
    [path],
  );

  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

export function useRouter(): RouterState {
  const ctx = useContext(RouterContext);
  if (!ctx) throw new Error('useRouter вне RouterProvider');
  return ctx;
}
