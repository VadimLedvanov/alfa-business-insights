import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { CompanyContext, EventRead, Insight } from '../api/types';

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

export function useCompanyContext(id: string | null): Async<CompanyContext> {
  return useAsync<CompanyContext>(async () => {
    if (!id) throw new Error('компания не выбрана');
    return api.company(id);
  }, [id]);
}

export function useEvent(id: string): Async<EventRead> {
  return useAsync<EventRead>(() => api.event(id), [id]);
}

export function useEvents(page = 0): Async<EventRead[]> {
  return useAsync<EventRead[]>(async () => (await api.events(page, false)).content.map((event) => ({
    ...event, sourceRef: null, materialsVersion: null,
  })), [page]);
}

export function useInsight(companyId: string | null, eventId: string | null): Async<Insight> {
  return useAsync<Insight>(async () => {
    if (!companyId || !eventId) throw new Error('нет компании или события');
    return api.insight(companyId, eventId);
  }, [companyId, eventId]);
}
