// Локальное хранилище добавленных администратором материалов (на время сессии).
// Нужно, чтобы показать сценарий крит. 05: админ добавляет материал → он проходит
// статусы обработки → в ленте компании появляется новый инсайт.
// В проде это делает бэкенд; здесь — демонстрация пайплайна без заготовленных ответов.

export type MaterialStatus = 'queued' | 'processing' | 'ready' | 'error';
export type MaterialKind = 'link' | 'text' | 'pdf';

export interface Material {
  id: string;
  title: string;
  kind: MaterialKind;
  status: MaterialStatus;
  addedAt: number;
  eventId: string; // событие, которое появится в ленте, когда статус станет ready
}

const KEY = 'abi.materials';
const listeners = new Set<() => void>();

function read(): Material[] {
  try {
    return JSON.parse(sessionStorage.getItem(KEY) || '[]') as Material[];
  } catch {
    return [];
  }
}
function write(items: Material[]) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(items.slice(0, 30)));
  } catch {
    /* приватный режим */
  }
  listeners.forEach((l) => l());
}

export function listMaterials(): Material[] {
  return read().sort((a, b) => b.addedAt - a.addedAt);
}

export function readyMaterials(): Material[] {
  return listMaterials().filter((m) => m.status === 'ready');
}

export function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function addMaterial(title: string, kind: MaterialKind): Material {
  const id = `mat-${Date.now()}`;
  const m: Material = { id, title, kind, status: 'queued', addedAt: Date.now(), eventId: `evt-${id}` };
  write([m, ...read()]);
  // Имитация обработки: очередь → обработка → готово.
  setTimeout(() => setStatus(id, 'processing'), 900);
  setTimeout(() => setStatus(id, 'ready'), 3200);
  return m;
}

export function setStatus(id: string, status: MaterialStatus) {
  write(read().map((m) => (m.id === id ? { ...m, status } : m)));
}

export function getMaterialByEvent(eventId: string): Material | null {
  return read().find((m) => m.eventId === eventId) ?? null;
}
