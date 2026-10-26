import { useEffect, useState } from 'react';
import { AppShell } from '../components/AppShell';
import { LoadErr } from '../components/common';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { api } from '../api/client';
import { ChevronRightMIcon } from '../ui/icons';
import type { TopEvent } from '../api/types';
import './HomePage.css';

function eventsWord(n: number): string {
  const last = n % 10, tens = n % 100;
  if (last === 1 && tens !== 11) return 'событие';
  if (last >= 2 && last <= 4 && (tens < 12 || tens > 14)) return 'события';
  return 'событий';
}

/** Главный экран после выбора компании: виджет «Радар» с переходом в ленту (FE-1). */
export function HomePage() {
  const { companyId } = useCompany();
  const { navigate } = useRouter();
  const [events, setEvents] = useState<TopEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!companyId) return;
    let alive = true; setEvents(null); setError(null);
    api.topEvents(companyId)
      .then((value) => alive && setEvents(value))
      .catch((e) => alive && setError(e instanceof Error ? e.message : String(e)));
    return () => { alive = false; };
  }, [companyId, tick]);

  return <AppShell active="radar">
    <div className="page-head"><div><h1>Главная</h1></div></div>
    <LoadErr loading={events === null && !error} error={error} onRetry={() => setTick((n) => n + 1)}>
      {events && <button type="button" className="card card--pad home-widget" onClick={() => navigate('/feed')}>
        <span className="eyebrow">РАДАР</span>
        {events.length === 0
          ? <span className="home-widget__count">Событий пока нет</span>
          : <>
            <span className="home-widget__count">{events.length} {eventsWord(events.length)} для вашей компании</span>
            <span className="home-widget__list">{events.slice(0, 2).map((event) => <span key={event.id} className="home-widget__title">{event.title}</span>)}</span>
          </>}
        <span className="home-widget__action">Открыть ленту <ChevronRightMIcon width={20} height={20} /></span>
      </button>}
    </LoadErr>
  </AppShell>;
}
