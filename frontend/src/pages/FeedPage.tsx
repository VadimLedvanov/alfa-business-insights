import { useEffect, useState } from 'react';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { InsightTile } from '../components/InsightTile';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { api, ApiError } from '../api/client';
import { deriveTile, type TileData } from '../lib/derive';
import type { CompanyContext, ImpactLevel, Insight, TopEvent } from '../api/types';
import './FeedPage.css';

type Filter = 'all' | 'risk' | 'opportunity' | 'indirect';
const FILTERS: { id: Filter; label: string }[] = [{ id: 'all', label: 'Все события' }, { id: 'risk', label: 'Риски' }, { id: 'opportunity', label: 'Возможности' }, { id: 'indirect', label: 'Через контрагентов' }];
interface FeedItem { event: TopEvent; insight: Insight | null; tile: TileData | null; error: string | null; rank: number; }
function matches(filter: Filter, impact?: ImpactLevel): boolean {
  if (filter === 'all') return true;
  if (!impact) return true;
  if (filter === 'opportunity') return impact === 'opportunity';
  if (filter === 'indirect') return impact === 'indirect';
  return impact === 'high' || impact === 'medium';
}

export function FeedPage() {
  const { companyId } = useCompany();
  const { navigate } = useRouter();
  const [ctx, setCtx] = useState<CompanyContext | null>(null);
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [tick, setTick] = useState(0);
  useEffect(() => { setFilter('all'); }, [companyId]);
  useEffect(() => {
    if (!companyId) return;
    let alive = true; setItems(null); setError(null); setCtx(null);
    (async () => {
      const [context, topEvents] = await Promise.all([api.company(companyId), api.topEvents(companyId)]);
      const withInsights = await Promise.all(topEvents.slice(0, 3).map(async (event, index): Promise<FeedItem> => {
        if (!event.insightGenerated) return { event, insight: null, tile: null, error: null, rank: index + 1 };
        try {
          const insight = await api.insight(companyId, event.id);
          return { event, insight, tile: deriveTile(context, insight), error: null, rank: index + 1 };
        } catch (e) {
          if (e instanceof ApiError && e.status === 404) return { event, insight: null, tile: null, error: null, rank: index + 1 };
          if (e instanceof ApiError && e.status === 401) throw e;
          return { event, insight: null, tile: null, error: e instanceof Error ? e.message : 'Разбор недоступен.', rank: index + 1 };
        }
      }));
      if (alive) { setCtx(context); setItems(withInsights); }
    })().catch((e) => alive && setError(e instanceof Error ? e.message : String(e)));
    return () => { alive = false; };
  }, [companyId, tick]);

  const visible = (items ?? []).filter((item) => matches(filter, item.tile?.impact));
  return <AppShell active="radar"><div className="page-head"><div><div className="eyebrow">ПЕРСОНАЛЬНЫЙ РАДАР</div><h1>Ваш бизнес — в контексте событий</h1><p className="sub">Три наиболее релевантных события для выбранной компании.</p></div><button className="button" onClick={() => setTick((n) => n + 1)}>↻ Обновить</button></div>
    <div className="radar-hero"><div><div className="eyebrow">ОТ СОБЫТИЯ — К РЕШЕНИЮ</div><h2>Увидеть главное.<br/>Понять влияние.</h2><p>Новости и аналитика с учётом отрасли, продуктов банка и связей вашей компании.</p>{items && <small>Показано {items.length} из 3 событий</small>}</div><div className="radar-orbits" aria-hidden="true"><i/><i/><i/><span>✦</span></div></div>
    <div className="feed-heading"><h2>События компании</h2><small>По релевантности</small></div><div className="feed-filters" role="group" aria-label="Фильтр событий">{FILTERS.map((f) => <button key={f.id} className={`feed-filter${filter === f.id ? ' feed-filter--active' : ''}`} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>{f.label}</button>)}</div>
    <div className="feed-layout"><section className="feed-list" aria-label="Три наиболее релевантных события"><LoadErr loading={items === null && !error} error={error} onRetry={() => setTick((n) => n + 1)}>{visible.length === 0 ? <div className="empty">Подходящих событий пока нет.</div> : visible.map((item) => <div className="feed-top-event" key={item.event.id}><small className="feed-top-event__rank">№ {item.rank} · оценка релевантности {item.event.relevanceScore.toLocaleString('ru-RU', { maximumFractionDigits: 2 })}</small>{item.tile ? <InsightTile event={{ ...item.event, sourceRef: null, materialsVersion: null }} tile={item.tile} onClick={() => navigate(`/insight/${item.event.id}`)} /> : <button type="button" className="card card--pad feed-event-card" onClick={() => navigate(`/insight/${item.event.id}`)}><span className="eyebrow">{new Date(item.event.createdAt).toLocaleDateString('ru-RU')} · {item.event.sourceType} · {item.event.status}</span><span className="feed-event-card__title">{item.event.title}</span><span className="feed-event-card__copy">Откройте событие, чтобы посмотреть материал и сформировать инсайд.</span><span className="feed-event-card__action">Открыть событие <span aria-hidden="true">→</span></span></button>}</div>)}</LoadErr></section><aside className="feed-aside">{ctx && <CompanyProfileCard ctx={ctx} onOpenProfile={() => navigate('/companies')} />}<section className="card card--pad note-card"><div className="eyebrow">КОНТЕКСТ ВАЖЕН</div><h3>Одна новость.<br/>Разное влияние.</h3><p>Радар учитывает отрасль, продукты банка и связи по платежам.</p></section></aside></div>
  </AppShell>;
}
