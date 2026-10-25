import { useEffect, useState } from 'react';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { ProvenanceLabel, ImpactBadge, ConfidenceWord } from '../ui/Provenance';
import { ChevronLeftMIcon } from '../ui/icons';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { useCompanyContext } from '../lib/useData';
import { api, ApiError } from '../api/client';
import { deriveTile } from '../lib/derive';
import type { EventRead, Insight, InsightMessage, Significance, SourceRef } from '../api/types';
import './InsightPage.css';

export function InsightPage({ eventId }: { eventId: string }) {
  const { companyId } = useCompany();
  const context = useCompanyContext(companyId);
  const { navigate } = useRouter();
  const [event, setEvent] = useState<EventRead | null>(null);
  const [insight, setInsight] = useState<Insight | null>(null);
  const [messages, setMessages] = useState<InsightMessage[]>([]);
  const [messagesError, setMessagesError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [preview, setPreview] = useState<{ name: string; url: string } | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true); setError(null); setInsight(null); setMessages([]); setMessagesError(null);
    api.event(eventId).then(async (value) => {
      if (!active) return;
      setEvent(value);
      if (companyId) {
        let result: Insight;
        try { result = await api.insight(companyId, eventId); }
        catch (e) {
          if (e instanceof ApiError && e.status === 404) return;
          throw e;
        }
        if (active) {
          setInsight(result);
          try {
            const history = await api.messages(companyId, eventId);
            if (active) setMessages(history);
          }
          catch (e) { if (active) setMessagesError(e instanceof Error ? e.message : 'История сообщений недоступна.'); }
        }
      }
    }).catch((e) => active && setError(e instanceof Error ? e.message : 'Не удалось загрузить событие.'))
      .finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [eventId, companyId, revision]);

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview.url); }, [preview]);

  const generate = async () => {
    if (!companyId || event?.sourceType !== 'TEXT') return;
    setBusy(true); setError(null);
    try {
      const value = await api.generateInsight(companyId, eventId);
      setInsight(value);
      setEvent((current) => current ? { ...current, status: 'READY' } : current);
      try { setMessages(await api.messages(companyId, eventId)); setMessagesError(null); }
      catch (e) { setMessagesError(e instanceof Error ? e.message : 'История сообщений недоступна.'); }
    } catch (e) { setError(e instanceof Error ? e.message : 'Не удалось сформировать разбор.'); }
    finally { setBusy(false); }
  };

  const openDocument = async (source: SourceRef) => {
    if (!source.document_id) return;
    try {
      const metadata = await api.document(source.document_id);
      const blob = await api.documentFile(source.document_id);
      setPreview({ name: metadata.fileName, url: URL.createObjectURL(blob) });
    } catch (e) { setError(e instanceof Error ? e.message : 'Не удалось открыть PDF-источник.'); }
  };

  const scrollTo = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  return <AppShell active="radar">
    <button className="back-link" onClick={() => navigate('/feed')}><ChevronLeftMIcon width={20} height={20} /> Вернуться в Радар</button>
    <LoadErr loading={loading || context.loading} error={error || context.error} onRetry={() => setRevision((n) => n + 1)}>
      {event && context.data && <>
        <div className="insight-head">
          {insight && <ImpactBadge level={deriveTile(context.data, insight).impact} />}
          <h1>{event.title}</h1>
          <div className="insight-head__meta"><span>{new Date(event.createdAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}</span><span>·</span><span>{event.sourceType}</span><span>·</span><span>{companyId}</span></div>
        </div>
        <nav className="event-tabs" aria-label="Разделы события">
          <button type="button" onClick={() => scrollTo('event-material')}>Материал события</button>
          <button type="button" onClick={() => scrollTo('event-analysis')}>{insight ? 'Инсайд для компании' : 'Сформировать инсайд'}</button>
        </nav>
        {insight ? <div className="insight-layout">
          <div className="insight-main">
            <EventMaterial event={event} />
            <div id="event-analysis" className="insight-main__section"><InsightContent insight={insight} onSource={openDocument} /><MessageHistory messages={messages} error={messagesError} /></div>
          </div>
          <aside className="insight-aside"><CompanyProfileCard ctx={context.data} onOpenProfile={() => navigate('/companies')} /></aside>
        </div> : <div className="insight-layout">
          <div className="insight-main">
            <EventMaterial event={event} />
            <section id="event-analysis" className="panel insight-status">
              <div className="eyebrow">РАЗБОР ДЛЯ КОМПАНИИ</div>
              <h2>{event.status === 'FAILED' ? 'Разбор не сформирован' : 'Инсайд по этому событию ещё не создан'}</h2>
              <p>{event.sourceType === 'TEXT' ? `Сопоставим событие с профилем компании ${companyId}, сохраним вывод и оценку значимости.` : 'Для URL и PDF извлечение текста пока не подключено, поэтому сформировать инсайд нельзя.'}</p>
              {event.sourceType === 'TEXT' && <button className="button button--primary insight-generate" disabled={busy} aria-busy={busy} onClick={() => void generate()}>{busy && <span className="insight-generate__spinner" aria-hidden="true" />}{busy ? 'Формируем инсайд…' : 'Сформировать инсайд'}</button>}
            </section>
          </div>
          <aside className="insight-aside"><CompanyProfileCard ctx={context.data} onOpenProfile={() => navigate('/companies')} /></aside>
        </div>}
      </>}
    </LoadErr>
    {preview && <div className="pdf-overlay" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreview(null); }}><section className="pdf-dialog" role="dialog" aria-modal="true" aria-label={`Просмотр ${preview.name}`}><header><strong>{preview.name}</strong><button className="button button--small" onClick={() => setPreview(null)}>Закрыть</button></header><iframe title={preview.name} src={preview.url} /></section></div>}
  </AppShell>;
}

function EventMaterial({ event }: { event: EventRead }) {
  return <section id="event-material" className="panel event-material">
    <div className="eyebrow">СОБЫТИЕ · ИСХОДНЫЙ МАТЕРИАЛ</div>
    <h2>Материал события</h2>
    {event.sourceType === 'URL' && event.sourceRef?.startsWith('https://')
      ? <a className="event-material__link" href={event.sourceRef} target="_blank" rel="noreferrer">{event.sourceRef}</a>
      : <p>{event.sourceRef || 'Исходный материал не указан.'}</p>}
  </section>;
}

function InsightContent({ insight, onSource }: { insight: Insight; onSource: (source: SourceRef) => void }) {
  const confidence = insight.ai_inference[0]?.confidence;
  return <>
    <SignificancePanel status={insight.significance ?? 'UNKNOWN'} reason={insight.significanceReason ?? null} />
    {insight.facts.length > 0 && <section className="panel"><ProvenanceLabel kind="fact"/><h2>Что произошло</h2>{insight.facts.map((f) => <p key={f.fragment_id}>{f.text}</p>)}</section>}
    {insight.bank_view.length > 0 && <section className="panel"><ProvenanceLabel kind="bank"/><h2>Позиция банка</h2>{insight.bank_view.map((f) => <p key={f.fragment_id}>{f.text}</p>)}</section>}
    {insight.ai_inference.length > 0 && <section className="panel panel--ai"><ProvenanceLabel kind="ai"/><h2>Почему это важно для вашей компании</h2>{insight.ai_inference.map((f, i) => <p key={i}>{f.text}</p>)}{confidence && <ConfidenceWord value={confidence} />}{insight.features_used.length > 0 && <div className="chips">{insight.features_used.map((feature) => <span className="tag" key={feature}>{feature}</span>)}</div>}</section>}
    {insight.risks.length > 0 && <TextList title="Возможные риски" items={insight.risks.map((x) => x.text)}/>}
    {insight.opportunities.length > 0 && <TextList title="Возможности" items={insight.opportunities.map((x) => x.text)}/>}
    {insight.hypotheses.length > 0 && <TextList title="Гипотезы" items={insight.hypotheses.map((x) => x.text)}/>}
    {insight.actions.length > 0 && <TextList title="Что стоит сделать" items={insight.actions.map((x) => x.text)}/>}
    {insight.missing_data.length > 0 && <TextList title="Чего мы не знаем" items={insight.missing_data}/>}
    {insight.sources.length > 0 && <section className="panel"><h2>Источники</h2>{insight.sources.map((source) => <Source key={source.fragment_id} source={source} onOpen={() => onSource(source)}/>)}</section>}
  </>;
}

function SignificancePanel({ status, reason }: { status: Significance; reason: string | null }) {
  const labels: Record<Significance, string> = {
    SIGNIFICANT: 'Событие значимо',
    NOT_SIGNIFICANT: 'Значимое влияние не выявлено',
    UNKNOWN: 'Значимость не оценена',
  };
  return <section className={`panel significance significance--${status.toLowerCase()}`}><div className="eyebrow">ОЦЕНКА ЗНАЧИМОСТИ</div><h2>{labels[status]}</h2>{reason && <p>{reason}</p>}</section>;
}

function MessageHistory({ messages, error }: { messages: InsightMessage[]; error: string | null }) {
  return <section className="panel message-history"><h2>История сообщений</h2>{error ? <p className="panel__muted">{error}</p> : messages.length === 0 ? <p className="panel__muted">Сохранённых сообщений нет.</p> : messages.map((message) => <article className="message-history__item" key={message.id}><div className="message-history__meta"><strong>{message.role.toLowerCase() === 'user' ? 'Вы' : 'Ассистент'}</strong><time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString('ru-RU')}</time></div><p>{message.text}</p></article>)}</section>;
}

function TextList({ title, items }: { title: string; items: string[] }) {
  return <section className="panel"><h2>{title}</h2>{items.map((item, i) => <p key={i}>{item}</p>)}</section>;
}

function Source({ source, onOpen }: { source: SourceRef; onOpen: () => void }) {
  const label = source.document_id ? `PDF · ${source.document_id}${source.page ? ` · стр. ${source.page}` : ''}` : source.url || 'Текстовый источник';
  return <div className="source-card"><span><strong>{label}</strong><small>{source.fragment_id}</small></span>{source.url ? <a className="button button--small" href={source.url} target="_blank" rel="noreferrer">Открыть</a> : source.document_id && <button className="button button--small" onClick={onOpen}>Просмотр PDF</button>}</div>;
}
