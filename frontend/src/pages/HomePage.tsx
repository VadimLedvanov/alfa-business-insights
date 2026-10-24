import { Button } from '@alfalab/core-components/button';
import { AppShell } from '../components/AppShell';
import { Money, LoadErr } from '../components/common';
import { InsightTile } from '../components/InsightTile';
import { AlfaGlyph } from '../ui/Logo';
import {
  RadarMIcon, ArrowRightMIcon, ClockMIcon, PlusMIcon, ChevronDownMIcon, PencilMIcon, CopyMIcon,
  DocumentStarMIcon, DocumentStampMIcon, Document1CMIcon, DocumentPlusMIcon, ChartPieMIcon, WorldTransfersMIcon, AScoresCircleMIcon,
} from '../ui/icons';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { useCompanyContext, useEvents, useInsight } from '../lib/useData';
import { deriveTile } from '../lib/derive';
import { companyTitle } from '../lib/format';
import type { CompanyContext, EventRead } from '../api/types';
import './HomePage.css';

const QUICK = [
  { label: 'Платёж по шаблону', Icon: DocumentStarMIcon },
  { label: 'Сформировать выписку', Icon: DocumentStampMIcon },
  { label: 'Импорт платежей из 1С', Icon: Document1CMIcon },
  { label: 'Выставить счёт', Icon: DocumentPlusMIcon },
  { label: 'Предоставить сведения о ВО', Icon: ChartPieMIcon },
];
const STATUSES: [string, number][] = [
  ['Оплатить', 1], ['Подписать', 7], ['Отправить', 7], ['Доработать', 4], ['Отклонённые', 7], ['Черновики', 5],
];

export function HomePage() {
  const { companyId } = useCompany();
  const ctx = useCompanyContext(companyId);

  return (
    <AppShell active="radar">
      <section aria-label="Быстрые действия" className="home-qa">
        {QUICK.map(({ label, Icon }) => (
          <button key={label} className="qa-card">
            <span className="qa-card__icon"><Icon width={24} height={24} color="var(--color-light-graphic-secondary)" /></span>
            <span className="qa-card__label">{label}</span>
          </button>
        ))}
        <div className="qa-aside">
          <button className="qa-sq" style={{ border: '1px dashed var(--color-light-neutral-translucent-500)', background: 'transparent' }} aria-label="Добавить действие">
            <PlusMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" />
          </button>
          <button className="qa-sq" style={{ border: 0, background: 'var(--color-light-bg-primary)' }} aria-label="Ещё действия">
            <ChevronDownMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" />
          </button>
        </div>
      </section>

      <nav aria-label="Документы" className="status-strip">
        {STATUSES.map(([label, n], i) => (
          <a key={label} href="#/home" onClick={(e) => e.preventDefault()}>
            {label}
            <span className={`count ${i === 0 ? 'count--red' : 'count--gray'}`}>{n}</span>
          </a>
        ))}
        <span style={{ flex: '1 1 auto' }} />
        <a href="#/home" onClick={(e) => e.preventDefault()}>В работе у банка<span className="count count--gray">8</span></a>
      </nav>

      <LoadErr loading={ctx.loading} error={ctx.error} onRetry={ctx.reload}>
        {ctx.data && <RadarWidget ctx={ctx.data} />}
      </LoadErr>

      {ctx.data && <AccountRow ctx={ctx.data} />}
    </AppShell>
  );
}

function RadarWidget({ ctx }: { ctx: CompanyContext }) {
  const events = useEvents();
  const { navigate } = useRouter();
  return (
    <section aria-label="Радар" className="radar-widget">
      <div className="radar-widget__head">
        <span className="radar-widget__badge"><RadarMIcon width={28} height={28} color="#ffffff" /></span>
        <div style={{ flex: '1 1 320px', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <h2 className="radar-widget__title">Радар</h2>
            <span className="count count--red">3</span>
            <span style={{ fontSize: 13, color: 'var(--color-light-text-secondary)' }}>новых события</span>
          </div>
          <p className="radar-widget__sub">Выводы для {companyTitle(ctx).split(' · ')[0]} из новостей и аналитики банка</p>
        </div>
        <span className="radar-widget__updated"><ClockMIcon width={16} height={16} color="var(--color-light-graphic-tertiary)" />Обновлено 12 минут назад</span>
        <Button view="accent" size={40} rightAddons={<ArrowRightMIcon width={20} height={20} />} onClick={() => navigate('/feed')}>
          Открыть Радар
        </Button>
      </div>
      <LoadErr loading={events.loading} error={events.error} onRetry={events.reload}>
        <div className="radar-widget__tiles">
          {(events.data ?? []).slice(0, 2).map((ev) => (
            <WidgetTile key={ev.id} ctx={ctx} event={ev} />
          ))}
        </div>
      </LoadErr>
    </section>
  );
}

function WidgetTile({ ctx, event }: { ctx: CompanyContext; event: EventRead }) {
  const { navigate } = useRouter();
  const ins = useInsight(ctx.profile.companyId, event.id);
  if (!ins.data) return <div className="skeleton" style={{ height: 150, borderRadius: 12 }} />;
  const tile = deriveTile(ctx, ins.data);
  return <InsightTile event={event} tile={tile} onClick={() => navigate(`/insight/${event.id}`)} />;
}

function AccountRow({ ctx }: { ctx: CompanyContext }) {
  void ctx;
  return (
    <div className="account-row">
      <section aria-label="Счёт" className="account">
        <div style={{ display: 'flex', gap: 32, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: '1 1 260px', minWidth: 0 }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 14, color: 'var(--color-light-text-secondary)' }}>
              Рублёвый счёт<PencilMIcon width={16} height={16} color="var(--color-light-graphic-tertiary)" />
            </span>
            <Money value={182_495_810} />
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 14, color: 'var(--color-light-text-secondary)', fontVariantNumeric: 'tabular-nums' }}>
              40702 810 9 0000 0104233<CopyMIcon width={16} height={16} color="var(--color-light-graphic-tertiary)" />
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: '0 0 auto' }}>
            <span style={{ width: 64, height: 42, borderRadius: 6, background: 'var(--color-light-accent-primary)', position: 'relative', flex: 'none', boxShadow: 'var(--shadow-xs)' }}>
              <span style={{ position: 'absolute', left: 8, top: 7 }}><AlfaGlyph size={14} color="#fff" /></span>
            </span>
            <span style={{ display: 'flex', flexDirection: 'column' }}>
              <span style={{ fontSize: 14, fontWeight: 500 }}>Бизнес-карта</span>
              <span style={{ fontSize: 13, color: 'var(--color-light-text-secondary)', fontVariantNumeric: 'tabular-nums' }}>··4233</span>
            </span>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
          <Button view="secondary" size={40}>Скопировать реквизиты</Button>
          <span style={{ fontSize: 14 }}>Тариф «Корпоративный»</span>
        </div>
      </section>

      <aside className="promos" aria-label="Предложения">
        <a href="#/home" onClick={(e) => e.preventDefault()} className="promo" style={{ background: 'var(--color-light-decorative-muted-alt-blue)' }}>
          <span style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
            <span className="promo__title">Валютный контроль онлайн</span>
            <WorldTransfersMIcon width={28} height={28} color="var(--color-light-text-link)" />
          </span>
          <span className="promo__sub">Без визита в банк</span>
        </a>
        <a href="#/home" onClick={(e) => e.preventDefault()} className="promo" style={{ background: 'var(--color-light-decorative-muted-alt-orange)' }}>
          <span style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
            <span className="promo__title">Кэшбэк и привилегии</span>
            <AScoresCircleMIcon width={28} height={28} color="var(--radar-prov-ai-text)" />
          </span>
          <span className="promo__sub">Баллы за привычные операции</span>
        </a>
      </aside>
    </div>
  );
}
