import type { CompanyContext, CompanyListItem, DocumentMetadata, EventListItem, EventRead, Insight, InsightMessage, Me, Page, TopEvent } from './types';
import { authHeader, kcLogout } from '../auth/keycloak';

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); this.name = 'ApiError'; }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(`/api${path}`, { ...init, headers: { ...authHeader(), ...init.headers } });
  if (response.ok) return response;
  if (response.status === 401) {
    kcLogout();
    window.dispatchEvent(new Event('abi:unauthorized'));
  }
  const fallback = response.status === 401 ? 'Сессия завершена. Войдите снова.'
    : response.status === 403 ? 'Недостаточно прав для этого действия.'
    : response.status === 413 ? 'Файл превышает допустимый размер 50 МБ.'
    : response.status === 404 ? 'Данные не найдены.' : `Не удалось выполнить запрос (${response.status}).`;
  const details = await response.json().catch(() => null) as { detail?: string; message?: string } | null;
  throw new ApiError(response.status, details?.detail || details?.message || fallback);
}

async function json<T>(path: string, init?: RequestInit): Promise<T> { return (await request(path, init)).json() as Promise<T>; }

export const api = {
  me: () => json<Me>('/auth/me'),
  companies: (page = 0) => json<Page<CompanyListItem>>(`/companies?page=${page}&size=20`),
  company: (id: string) => json<CompanyContext>(`/companies/${encodeURIComponent(id)}`),
  events: (page = 0, readyOnly = true) => json<Page<EventListItem>>(`/events?page=${page}&size=20${readyOnly ? '&status=READY' : ''}`),
  topEvents: (companyId: string) => json<TopEvent[]>(`/companies/${encodeURIComponent(companyId)}/events/top`),
  event: (id: string) => json<EventRead>(`/events/${encodeURIComponent(id)}`),
  insight: (companyId: string, eventId: string) => json<Insight>(`/companies/${encodeURIComponent(companyId)}/events/${encodeURIComponent(eventId)}/insight`),
  generateInsight: (companyId: string, eventId: string) => json<Insight>(`/companies/${encodeURIComponent(companyId)}/events/${encodeURIComponent(eventId)}/insight`, { method: 'POST' }),
  messages: (companyId: string, eventId: string) => json<InsightMessage[]>(`/companies/${encodeURIComponent(companyId)}/events/${encodeURIComponent(eventId)}/messages`),
  documents: (page = 0) => json<Page<DocumentMetadata>>(`/admin/documents?page=${page}&size=20`),
  document: (id: string) => json<DocumentMetadata>(`/documents/${encodeURIComponent(id)}`),
  documentFile: async (id: string) => (await request(`/documents/${encodeURIComponent(id)}/file`)).blob(),
  deleteDocument: async (id: string) => { await request(`/admin/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }); },
  uploadDocument: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return json<DocumentMetadata>('/admin/documents', { method: 'POST', body });
  },
  createEvent: (title: string, sourceType: 'TEXT' | 'URL', sourceRef: string) => json<EventRead>('/events', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title, sourceType, sourceRef }),
  }),
};
