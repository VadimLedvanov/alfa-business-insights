import { useEffect, useState } from 'react';
import { Button } from '@alfalab/core-components/button';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { InsightTile } from '../components/InsightTile';
import { LinkMIcon, ClockMIcon, ArrowsCwMIcon } from '../ui/icons';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { api } from '../api/client';
import { deriveTile, type TileData } from '../lib/derive';
import type { CompanyContext, EventRead, ImpactLevel, Insight } from '../api/types';
import './FeedPage.css';

type Filter = 'all' | 'risk' | 'opportunity' | 'indirect';
const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'Все события' },
  { id: 'risk', label: 'Риски' },
  { id: 'opportunity', label: 'Возможности' },
  { id: 'indirect', label: 'Через связанные компании' },
];

interface FeedItem { event: EventRead; insight: Insight; tile: TileData; }

function matches(filter: Filter, impact: ImpactLevel): boolean {
  if (filter === 'all') return true;
  if (filter === 'opportunity') return impact === 'opportunity';
  if (filter === 'indirect') return impact === 'indirect';
  return impact === 'high' || impact === 'medium'; // риски — прямое влияние
}

export function FeedPage() {
  const { companyId } = useCompany();
  const { navigate } = useRouter();
  const [ctx, setCtx] = useState<CompanyContext | null>(null);
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!companyId) return;
    let alive = true;
    setItems(null);
    setError(null);
    (async () => {
      const [context, events] = await Promise.all([api.company(companyId), api.events()]);
      const withInsights = await Promise.all(
        events.map(async (event) => {
          const insight = await api.insight(companyId, event.id);
          return { event, insight, tile: deriveTile(context, insight) } as FeedItem;
        }),
      );
      if (alive) { setCtx(context); setItems(withInsights); }
    })().catch((e) => alive && setError(e instanceof Error ? e.message : String(e)));
    return () => { alive = false; };
  }, [companyId, tick]);

  const visible = (items ?? []).filter((it) => matches(filter, it.tile.impact));

  return (
    <AppShell active="radar">
      <div className="page-head">
        <div>
          <div className="eyebrow">ПЕРСОНАЛЬНЫЙ РАДАР</div>
          <h1>Требует вашего внимания</h1>
          <p className="sub">Важное для компании — с объяснением, почему это важно именно ей.</p>
        </div>
        <div className="feed-updated">
          <span><ClockMIcon width={16} height={16} color="var(--color-light-graphic-tertiary)" /> Обновлено 12 минут назад</span>
          <Button view="secondary" size={40} leftAddons={<ArrowsCwMIcon width={18} height={18} />} onClick={() => setTick((t) => t + 1)}>
            Обновить
          </Button>
        </div>
      </div>

      <div className="feed-filters" role="group" aria-label="Фильтр событий">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            className={`feed-filter${filter === f.id ? ' feed-filter--active' : ''}`}
            aria-pressed={filter === f.id}
            onClick={() => setFilter(f.id)}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="feed-layout">
        <section className="feed-list" aria-label="Персональные инсайты">
          <LoadErr loading={items === null && !error} error={error} onRetry={() => setTick((t) => t + 1)}>
            {visible.length === 0 ? (
              <div className="empty">В этом разрезе значимых событий нет.<br />Попробуйте другой фильтр или другую компанию.</div>
            ) : (
              visible.map((it) => (
                <InsightTile key={it.event.id} event={it.event} tile={it.tile} isNew={it.event.id.startsWith('evt-mat-')} onClick={() => navigate(`/insight/${it.event.id}`)} />
              ))
            )}
          </LoadErr>
        </section>

        <aside className="feed-aside">
          {ctx && <CompanyProfileCard ctx={ctx} onOpenProfile={() => navigate('/companies')} />}
          <section className="card card--pad note-card">
            <span className="note-card__icon"><LinkMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" /></span>
            <h3>Одна новость — разное влияние</h3>
            <p>
              Радар учитывает отрасль, продукты банка и связи по платежам. Поэтому одно и то же событие
              даёт разные выводы для разных компаний — попробуйте переключить компанию в шапке.
            </p>
          </section>
        </aside>
      </div>
    </AppShell>
  );
}
