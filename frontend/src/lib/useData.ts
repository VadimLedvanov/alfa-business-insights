import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { CompanyContext, EventRead, Insight } from '../api/types';
import { companyTitle } from './format';

export interface Async<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): Async<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { data, loading, error, reload: () => setTick((t) => t + 1) };
}

export interface CompanyListItem {
  id: string;
  title: string;
  industry: string | null;
}

/** Список компаний пользователя с подписями (ID + отрасль). */
export function useCompanies(): Async<CompanyListItem[]> {
  return useAsync<CompanyListItem[]>(async () => {
    const ids = await api.companyIds();
    const ctxs = await Promise.all(ids.map((id) => api.company(id)));
    return ctxs.map((c) => ({ id: c.profile.companyId, title: companyTitle(c), industry: c.profile.mainIndustryName }));
  }, []);
}

export function useCompanyContext(id: string | null): Async<CompanyContext> {
  return useAsync<CompanyContext>(async () => {
    if (!id) throw new Error('компания не выбрана');
    return api.company(id);
  }, [id]);
}

export function useEvents(): Async<EventRead[]> {
  return useAsync<EventRead[]>(() => api.events(), []);
}

export function useInsight(companyId: string | null, eventId: string | null): Async<Insight> {
  return useAsync<Insight>(async () => {
    if (!companyId || !eventId) throw new Error('нет компании или события');
    return api.insight(companyId, eventId);
  }, [companyId, eventId]);
}
