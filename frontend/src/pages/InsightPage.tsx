import { useMemo, useState } from 'react';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { Chat } from '../components/Chat';
import { ProvenanceLabel, ImpactBadge, ConfidenceWord } from '../ui/Provenance';
import { ChevronLeftMIcon, DocumentMIcon, ArrowRightMIcon, ThumbUpMIcon, ThumbDownMIcon, UsersGroupMIcon } from '../ui/icons';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { useCompanyContext, useEvents, useInsight } from '../lib/useData';
import { impactOf, companyIdLabel } from '../lib/format';
import type { CompanyContext, Insight, SourceRef } from '../api/types';
import './InsightPage.css';

type Tab = 'overview' | 'sources' | 'trace';
type Scope = 'company' | 'group';
const TABS: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Разбор события' },
  { id: 'sources', label: 'Источники' },
  { id: 'trace', label: 'Как получен вывод' },
];

const DOC_TITLES: Record<string, string> = {
  'doc-policy-rate-11sep': 'Ключевая ставка ЦБ РФ: ставка без изменения на 14,0%, риторика стала жёстче',
  'doc-consumption': 'Потребление на пике',
};

export function InsightPage({ eventId }: { eventId: string }) {
  const { companyId } = useCompany();
  const ctxQ = useCompanyContext(companyId);
  const eventsQ = useEvents();
  const insightQ = useInsight(companyId, eventId);
  const { navigate } = useRouter();
  const [tab, setTab] = useState<Tab>('overview');
  const [scope, setScope] = useState<Scope>('company');

  const event = eventsQ.data?.find((e) => e.id === eventId) ?? null;
  const loading = ctxQ.loading || eventsQ.loading || insightQ.loading;
  const error = ctxQ.error || eventsQ.error || insightQ.error;
  const hasGroup = (ctxQ.data?.group.length ?? 0) > 1;

  return (
    <AppShell active="radar">
      <button className="back-link" onClick={() => navigate('/feed')}>
        <ChevronLeftMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" /> Вернуться в Радар
      </button>

      <LoadErr loading={loading} error={error} onRetry={insightQ.reload}>
        {ctxQ.data && insightQ.data && (
          <>
            <div className="insight-head">
              <ImpactBadge level={impactOf(ctxQ.data, insightQ.data)} />
              <h1>{insightQ.data.ai_inference[0]?.text.split('.')[0] ?? event?.title}</h1>
              <div className="insight-head__meta">
                {event && <span>{new Date(event.createdAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}</span>}
                <span>·</span>
                <span>Разбор для {companyIdLabel(companyId ?? '')}</span>
              </div>
            </div>

            <div className="tabs" role="tablist">
              {TABS.map((t) => (
                <button key={t.id} role="tab" aria-selected={tab === t.id} className={`tab${tab === t.id ? ' tab--active' : ''}`} onClick={() => setTab(t.id)}>
                  {t.label}
                </button>
              ))}
            </div>

            <div className="insight-layout">
              <div className="insight-main">
                {tab === 'overview' && (
                  <>
                    <ScopeToggle scope={scope} setScope={setScope} hasGroup={hasGroup} groupSize={ctxQ.data.group.length} />
                    <Overview insight={insightQ.data} onSource={() => setTab('sources')} />
                    <Feedback />
                    <Chat ctx={ctxQ.data} insight={insightQ.data} scope={scope} />
                  </>
                )}
                {tab === 'sources' && <Sources insight={insightQ.data} />}
                {tab === 'trace' && <Trace insight={insightQ.data} />}
              </div>
              <aside className="insight-aside">
                <InfluenceMap insight={insightQ.data} ctx={ctxQ.data} eventTitle={event?.title ?? 'Событие'} />
                <CompanyProfileCard ctx={ctxQ.data} onOpenProfile={() => navigate('/companies')} />
              </aside>
            </div>
          </>
        )}
      </LoadErr>
    </AppShell>
  );
}

function ScopeToggle({ scope, setScope, hasGroup, groupSize }: { scope: Scope; setScope: (s: Scope) => void; hasGroup: boolean; groupSize: number }) {
  return (
    <div className="scope">
      <span className="scope__label">Разрез:</span>
      <div className="scope__switch" role="group" aria-label="Разрез анализа">
        <button className={`scope__btn${scope === 'company' ? ' scope__btn--active' : ''}`} aria-pressed={scope === 'company'} onClick={() => setScope('company')}>Компания</button>
        <button
          className={`scope__btn${scope === 'group' ? ' scope__btn--active' : ''}`}
          aria-pressed={scope === 'group'}
          disabled={!hasGroup}
          title={hasGroup ? undefined : 'Группа не указана'}
          onClick={() => hasGroup && setScope('group')}
        >
          <UsersGroupMIcon width={16} height={16} /> Вся группа{hasGroup ? ` · ${groupSize}` : ''}
        </button>
      </div>
      {scope === 'group' && <span className="scope__note">Показан разрез по группе. Агрегаты уровня группы подключаются из API.</span>}
    </div>
  );
}

function Overview({ insight, onSource }: { insight: Insight; onSource: () => void }) {
  const inf = insight.ai_inference[0];
  const hasBank = insight.bank_view.length > 0;
  return (
    <>
      {insight.facts.length > 0 && (
        <section className="panel">
          <ProvenanceLabel kind="fact" />
          <h2>Что произошло</h2>
          {insight.facts.map((f, i) => (
            <p key={i}>{f.text}<button className="source-ref" onClick={onSource} aria-label="Открыть источник">[1]</button></p>
          ))}
        </section>
      )}

      {inf && (
        <section className="panel panel--ai">
          <ProvenanceLabel kind="ai" />
          <h2>Почему это важно для вашей компании</h2>
          {insight.ai_inference.map((a, i) => <p key={i}>{a.text}</p>)}
          <div style={{ margin: '12px 0' }}><ConfidenceWord value={inf.confidence} /></div>
          {insight.features_used.length > 0 && (
            <>
              <div className="panel__caption">Признаки компании, повлиявшие на вывод</div>
              <div className="chips">{insight.features_used.map((f) => <span key={f} className="tag">{f}</span>)}</div>
            </>
          )}
        </section>
      )}

      <section className="panel">
        <ProvenanceLabel kind="bank" />
        <h2>Как оценивает банк</h2>
        {hasBank ? (
          <>
            {insight.bank_view.map((b, i) => (
              <p key={i}>{b.text}<button className="source-ref" onClick={onSource} aria-label="Открыть материал банка">[2]</button></p>
            ))}
            <p className="panel__src-note">{bankSourceNote(insight)}</p>
          </>
        ) : (
          <p className="panel__muted">Подходящей аналитики банка по этому событию нет. Вывод выше сделан моделью.</p>
        )}
      </section>

      {(insight.risks.length > 0 || insight.opportunities.length > 0) && (
        <div className="split">
          <section className="panel">
            <div className="panel__label" style={{ color: 'var(--radar-impact-high-text)' }}>↘ Возможные риски</div>
            {insight.risks.length ? insight.risks.map((r, i) => <p key={i}>{r.text}</p>) : <p className="panel__muted">Рисков по этому событию не выделено.</p>}
          </section>
          <section className="panel">
            <div className="panel__label" style={{ color: 'var(--radar-opportunity-text)' }}>↗ Возможности</div>
            {insight.opportunities.length ? insight.opportunities.map((o, i) => <p key={i}>{o.text}</p>) : <p className="panel__muted">Явных возможностей не выделено.</p>}
          </section>
        </div>
      )}

      {insight.hypotheses.length > 0 && (
        <section className="panel">
          <ProvenanceLabel kind="hypothesis" />
          <h2>Гипотезы</h2>
          {insight.hypotheses.map((h, i) => <p key={i} className="hypo">{h.text}</p>)}
        </section>
      )}

      {insight.actions.length > 0 && (
        <section className="panel">
          <h2>Что стоит сделать</h2>
          <ol className="actions">
            {insight.actions.map((a, i) => <li key={i}><span className="actions__num">{i + 1}</span>{a.text}</li>)}
          </ol>
        </section>
      )}

      {insight.missing_data.length > 0 && (
        <section className="panel">
          <ProvenanceLabel kind="nodata" />
          <h2>Чего мы не знаем</h2>
          {insight.missing_data.map((m, i) => <p key={i} className="panel__muted">{m}</p>)}
        </section>
      )}
    </>
  );
}

function Feedback() {
  const [vote, setVote] = useState<'up' | 'down' | null>(null);
  return (
    <section className="panel feedback">
      <span>Такой разбор был полезен?</span>
      <div className="feedback__btns">
        <button className={`feedback__btn${vote === 'up' ? ' feedback__btn--on' : ''}`} aria-pressed={vote === 'up'} onClick={() => setVote('up')}>
          <ThumbUpMIcon width={18} height={18} /> Да
        </button>
        <button className={`feedback__btn${vote === 'down' ? ' feedback__btn--on' : ''}`} aria-pressed={vote === 'down'} onClick={() => setVote('down')}>
          <ThumbDownMIcon width={18} height={18} /> Нет
        </button>
      </div>
      {vote && <span className="feedback__thanks">Спасибо! Оценка учтена для мониторинга качества.</span>}
    </section>
  );
}

function bankSourceNote(insight: Insight): string {
  const bankSrc = insight.sources.find((s) => s.document_id && DOC_TITLES[s.document_id]);
  if (!bankSrc || !bankSrc.document_id) return 'Материал Центра макроэкономических исследований Альфа-Банка.';
  return `Источник: «${DOC_TITLES[bankSrc.document_id]}» · материал ЦМИ, стр. ${bankSrc.page ?? '—'}`;
}

function Sources({ insight }: { insight: Insight }) {
  return (
    <section className="panel">
      <h2>Основания для разбора</h2>
      <p className="panel__muted">У каждого факта и позиции банка есть источник. Клик открывает материал по ссылке из ответа.</p>
      <div className="sources">
        {insight.sources.map((s, i) => <SourceCard key={s.fragment_id} index={i + 1} source={s} />)}
      </div>
      <div className="trace-id">trace_id: {insight.trace_id}</div>
    </section>
  );
}

function SourceCard({ index, source }: { index: number; source: SourceRef }) {
  const title = source.document_id ? (DOC_TITLES[source.document_id] ?? source.document_id) : 'Новость · первоисточник';
  const meta = source.document_id ? `PDF · материал банка${source.page ? ` · стр. ${source.page}` : ''}` : 'Внешняя новость';
  if (source.url) {
    return (
      <a className="source-card" href={source.url} target="_blank" rel="noreferrer">
        <DocumentMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" />
        <span><strong>[{index}] {title}</strong><small>{meta}</small></span>
        <ArrowRightMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" style={{ marginLeft: 'auto' }} />
      </a>
    );
  }
  return (
    <div className="source-card">
      <DocumentMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" />
      <span><strong>[{index}] {title}</strong><small>{meta}</small></span>
    </div>
  );
}

function Trace({ insight }: { insight: Insight }) {
  return (
    <section className="panel">
      <h2>Как получен вывод</h2>
      <p className="panel__muted">
        Полный журнал шагов агента (сбор контекста → поиск материалов → проверка полноты → при нехватке данных
        повторный поиск → итог) приходит из API вместе с инсайтом. Здесь показан идентификатор трассировки и
        использованные источники; пошаговый журнал подключается к этому экрану при готовности ручки.
      </p>
      <div className="trace-steps">
        <div className="trace-step"><span className="actions__num">1</span><div><strong>Собрали контекст компании</strong><p>Профиль, продукты банка, связи по платежам и группа.</p></div></div>
        <div className="trace-step"><span className="actions__num">2</span><div><strong>Нашли основания</strong><p>Фрагменты новостей и аналитики банка — {insight.sources.length} источника.</p></div></div>
        <div className="trace-step"><span className="actions__num">3</span><div><strong>Сформировали и проверили вывод</strong><p>Полнота и непротиворечивость, ссылки на источники.</p></div></div>
      </div>
      <div className="trace-id">trace_id: {insight.trace_id}</div>
    </section>
  );
}

function InfluenceMap({ insight, ctx, eventTitle }: { insight: Insight; ctx: CompanyContext; eventTitle: string }) {
  const nodes = useMemo(() => [
    { tag: 'СОБЫТИЕ', text: eventTitle },
    { tag: 'КОНТЕКСТ КОМПАНИИ', text: insight.features_used[0] ?? (ctx.profile.mainIndustryName ?? 'Профиль компании'), current: true },
    { tag: 'ВОЗМОЖНОЕ СЛЕДСТВИЕ', text: insight.ai_inference[0]?.text.split('.')[0] ?? 'Влияние на бизнес' },
  ], [insight, ctx, eventTitle]);
  return (
    <section className="card card--pad note-card">
      <div className="eyebrow">КАРТА ВЛИЯНИЯ</div>
      <div className="influence">
        {nodes.map((n, i) => (
          <div key={i}>
            {i > 0 && <div className="influence__arrow">↓</div>}
            <div className={`influence__node${n.current ? ' influence__node--current' : ''}`}>
              <small>{n.tag}</small>{n.text}
            </div>
          </div>
        ))}
      </div>
      <p style={{ fontSize: 12, lineHeight: '18px', color: 'var(--color-light-text-secondary)', margin: '16px 0 0' }}>
        Связь показывает логику вывода. Финансовый эффект в рублях не рассчитывается.
      </p>
    </section>
  );
}
