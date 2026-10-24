// Единая точка доступа к данным. Два режима:
//  - stub (по умолчанию): данные из stubs.ts, чтобы прототип кликался без бэка;
//  - live: реальные запросы к /api (включается VITE_API_MODE=live, когда бэк готов).
// Формы ответов совпадают, поэтому переключение режима не меняет экраны.

import type { CompanyContext, EventRead, Insight, Me } from './types';
import { DEMO_COMPANIES, DEMO_COMPANY_IDS, DEMO_EVENTS, DEMO_USERS, stubInsight } from './stubs';

const MODE = (import.meta.env.VITE_API_MODE as string) ?? 'stub';
export const IS_STUB = MODE !== 'live';

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (!res.ok) throw new ApiError(res.status, `GET ${path} → ${res.status}`);
  return (await res.json()) as T;
}

async function post<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { method: 'POST', headers: { Accept: 'application/json' }, credentials: 'include' });
  if (!res.ok) throw new ApiError(res.status, `POST ${path} → ${res.status}`);
  return (await res.json()) as T;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export const api = {
  /** Текущий пользователь. В stub-режиме выбранного тестового пользователя отдаёт AuthContext. */
  async me(): Promise<Me> {
    return get<Me>('/auth/me');
  },

  /** Список компаний пользователя. У бэка пока нет ручки — в обоих режимах возвращаем демо-ID. */
  async companyIds(): Promise<string[]> {
    return [...DEMO_COMPANY_IDS];
  },

  async company(id: string): Promise<CompanyContext> {
    if (IS_STUB) {
      await delay(250);
      const c = DEMO_COMPANIES[id];
      if (!c) throw new ApiError(404, `company ${id} not found`);
      return c;
    }
    return get<CompanyContext>(`/companies/${encodeURIComponent(id)}`);
  },

  async events(): Promise<EventRead[]> {
    if (IS_STUB) {
      await delay(250);
      return DEMO_EVENTS;
    }
    return get<EventRead[]>('/events');
  },

  /** Сохранённый инсайт без обращения к LLM. */
  async insight(companyId: string, eventId: string): Promise<Insight> {
    if (IS_STUB) {
      await delay(400);
      const i = stubInsight(companyId, eventId);
      if (!i) throw new ApiError(404, 'insight not found');
      return i;
    }
    return get<Insight>(`/companies/${encodeURIComponent(companyId)}/events/${eventId}/insight`);
  },

  /** Запустить генерацию инсайта (POST). В прода — до 4 вызовов LLM; повтор отдаёт сохранённый. */
  async generateInsight(companyId: string, eventId: string): Promise<Insight> {
    if (IS_STUB) {
      await delay(900);
      const i = stubInsight(companyId, eventId);
      if (!i) throw new ApiError(404, 'insight not found');
      return i;
    }
    return post<Insight>(`/companies/${encodeURIComponent(companyId)}/events/${eventId}/insight`);
  },
};

export function stubUser(key: string): Me | undefined {
  return DEMO_USERS[key];
}
