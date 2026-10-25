import { useEffect, useState } from 'react';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { InsightTile } from '../components/InsightTile';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { api, ApiError } from '../api/client';
import { deriveTile, type TileData } from '../lib/derive';
import type { CompanyContext, EventListItem, ImpactLevel, Insight, Page } from '../api/types';
import './FeedPage.css';

type Filter = 'all' | 'risk' | 'opportunity' | 'indirect';
const FILTERS: { id: Filter; label: string }[] = [{ id: 'all', label: 'Все события' }, { id: 'risk', label: 'Риски' }, { id: 'opportunity', label: 'Возможности' }, { id: 'indirect', label: 'Через контрагентов' }];
interface FeedItem { event: EventListItem; insight: Insight | null; tile: TileData | null; error: string | null; }
function matches(filter: Filter, impact?: ImpactLevel): boolean {
  if (filter === 'all') return true;
  if (filter === 'opportunity') return impact === 'opportunity';
  if (filter === 'indirect') return impact === 'indirect';
  return impact === 'high' || impact === 'medium';
}

export function FeedPage() {
  const { companyId } = useCompany();
  const { navigate } = useRouter();
  const [ctx, setCtx] = useState<CompanyContext | null>(null);
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [pagination, setPagination] = useState<Page<EventListItem> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [page, setPage] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => { setPage(0); setFilter('all'); }, [companyId]);
  useEffect(() => {
    if (!companyId) return;
    let alive = true; setItems(null); setError(null); setCtx(null);
    (async () => {
      const [context, events] = await Promise.all([api.company(companyId), api.events(page, false)]);
      const withInsights = await Promise.all(events.content.map(async (event): Promise<FeedItem> => {
        if (event.status !== 'READY') return { event, insight: null, tile: null, error: null };
        try {
          const insight = await api.insight(companyId, event.id);
          return { event, insight, tile: deriveTile(context, insight), error: null };
        } catch (e) {
          if (e instanceof ApiError && e.status === 404) return { event, insight: null, tile: null, error: null };
          if (e instanceof ApiError && e.status === 401) throw e;
          return { event, insight: null, tile: null, error: e instanceof Error ? e.message : 'Разбор недоступен.' };
        }
      }));
      if (alive) { setCtx(context); setItems(withInsights); setPagination(events); }
    })().catch((e) => alive && setError(e instanceof Error ? e.message : String(e)));
    return () => { alive = false; };
  }, [companyId, page, tick]);

  const generate = async (event: EventListItem) => {
    if (!companyId || event.sourceType !== 'TEXT') return;
    setPending(event.id); setError(null);
    try {
      const insight = await api.generateInsight(companyId, event.id);
      if (ctx) setItems((current) => current?.map((item) => item.event.id === event.id ? { event: { ...event, status: 'READY' }, insight, tile: deriveTile(ctx, insight), error: null } : item) ?? null);
      navigate(`/insight/${event.id}`);
    } catch (e) { setItems((current) => current?.map((item) => item.event.id === event.id ? { ...item, error: e instanceof Error ? e.message : 'Не удалось сформировать разбор.' } : item) ?? null); }
    finally { setPending(null); }
  };

  const visible = (items ?? []).filter((item) => matches(filter, item.tile?.impact));
  return <AppShell active="radar"><div className="page-head"><div><div className="eyebrow">ПЕРСОНАЛЬНЫЙ РАДАР</div><h1>Ваш бизнес — в контексте событий</h1><p className="sub">Важное для вашей компании. С объяснением, почему это важно.</p></div><button className="button" onClick={() => setTick((n) => n + 1)}>↻ Обновить</button></div>
    <div className="radar-hero"><div><div className="eyebrow">ОТ СОБЫТИЯ — К РЕШЕНИЮ</div><h2>Увидеть главное.<br/>Понять влияние.</h2><p>Новости и аналитика с учётом отрасли, продуктов банка и связей вашей компании.</p>{pagination && <small>{pagination.totalElements} событий доступно</small>}</div><div className="radar-orbits" aria-hidden="true"><i/><i/><i/><span>✦</span></div></div>
    <div className="feed-heading"><h2>События компании</h2><small>Сначала новые события</small></div><div className="feed-filters" role="group" aria-label="Фильтр событий">{FILTERS.map((f) => <button key={f.id} className={`feed-filter${filter === f.id ? ' feed-filter--active' : ''}`} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>{f.label}</button>)}</div>
    <div className="feed-layout"><section className="feed-list" aria-label="События"><LoadErr loading={items === null && !error} error={error} onRetry={() => setTick((n) => n + 1)}>{visible.length === 0 ? <div className="empty">Подходящих событий пока нет.<br/>Выберите другую компанию или фильтр.</div> : visible.map((item) => item.tile ? <InsightTile key={item.event.id} event={{ ...item.event, sourceRef: null, materialsVersion: null }} tile={item.tile} onClick={() => navigate(`/insight/${item.event.id}`)} /> : <article key={item.event.id} className="card card--pad"><div className="eyebrow">{new Date(item.event.createdAt).toLocaleDateString('ru-RU')} · {item.event.sourceType} · {item.event.status}</div><h3>{item.event.title}</h3><p className="sources__hint">{item.error || (item.event.sourceType === 'TEXT' ? 'Текст можно разобрать с учётом профиля выбранной компании.' : 'Для URL и PDF извлечение текста пока не подключено.')}</p>{item.event.sourceType === 'TEXT' && <button className="button button--primary" disabled={pending === item.event.id} onClick={() => void generate(item.event)}>{pending === item.event.id ? 'Формируем разбор…' : 'Сформировать разбор'}</button>}</article>)}</LoadErr>{pagination && pagination.totalPages > 1 && <div className="pagination"><button className="button" disabled={page === 0} onClick={() => setPage(page - 1)}>← Назад</button><span>{page + 1} / {pagination.totalPages}</span><button className="button" disabled={page + 1 >= pagination.totalPages} onClick={() => setPage(page + 1)}>Далее →</button></div>}</section><aside className="feed-aside">{ctx && <CompanyProfileCard ctx={ctx} onOpenProfile={() => navigate('/companies')} />}<section className="card card--pad note-card"><div className="eyebrow">КОНТЕКСТ ВАЖЕН</div><h3>Одна новость.<br/>Разное влияние.</h3><p>Радар учитывает отрасль, продукты банка и связи по платежам.</p></section></aside></div>
  </AppShell>;
}
