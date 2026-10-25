// Единая точка доступа к данным. Два режима:
//  - stub (по умолчанию): данные из stubs.ts, чтобы прототип кликался без бэка;
//  - live: реальные запросы к /api (включается VITE_API_MODE=live, когда бэк готов).
// Формы ответов совпадают, поэтому переключение режима не меняет экраны.

import type { CompanyContext, CompanyListItem, EventListItem, EventRead, Insight, Me, Page } from './types';
import { DEMO_COMPANIES, DEMO_COMPANY_IDS, DEMO_EVENTS, DEMO_USERS, stubInsight, userEvent, userInsight } from './stubs';
import { getMaterialByEvent, readyMaterials } from './sessionStore';
import { authHeader } from '../auth/keycloak';

const MODE = (import.meta.env.VITE_API_MODE as string) ?? 'stub';
export const IS_STUB = MODE !== 'live';

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: 'application/json', ...authHeader() } });
  if (!res.ok) throw new ApiError(res.status, `GET ${path} → ${res.status}`);
  return (await res.json()) as T;
}

async function post<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { method: 'POST', headers: { Accept: 'application/json', ...authHeader() } });
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

  /** ID компаний пользователя. Live: GET /api/companies (Spring Page). Stub: демо-ID. */
  async companyIds(): Promise<string[]> {
    if (IS_STUB) return [...DEMO_COMPANY_IDS];
    const page = await get<Page<CompanyListItem>>('/companies?size=100');
    return page.content.map((c) => c.companyId);
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
      // Готовые материалы, добавленные администратором, появляются в ленте первыми (крит. 05).
      const added = readyMaterials().map(userEvent);
      return [...added, ...DEMO_EVENTS];
    }
    // Live: только готовые события (у них есть инсайт); бэк отдаёт Spring Page.
    const page = await get<Page<EventListItem>>('/events?size=50&status=READY');
    return page.content.map(toEventRead);
  },

  /** Сохранённый инсайт без обращения к LLM. */
  async insight(companyId: string, eventId: string): Promise<Insight> {
    if (IS_STUB) {
      await delay(400);
      const added = getMaterialByEvent(eventId);
      if (added) return userInsight(companyId, added);
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
      const added = getMaterialByEvent(eventId);
      if (added) return userInsight(companyId, added);
      const i = stubInsight(companyId, eventId);
      if (!i) throw new ApiError(404, 'insight not found');
      return i;
    }
    return post<Insight>(`/companies/${encodeURIComponent(companyId)}/events/${eventId}/insight`);
  },
};

function toEventRead(e: EventListItem): EventRead {
  return { id: e.id, title: e.title, sourceType: e.sourceType, sourceRef: null, status: e.status, createdAt: e.createdAt, materialsVersion: null };
}

export function stubUser(key: string): Me | undefined {
  return DEMO_USERS[key];
}
