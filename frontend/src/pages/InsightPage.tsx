import { useEffect, useRef, useState } from 'react';
import type { PDFDocumentLoadingTask, PDFPageProxy } from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { AppShell } from '../components/AppShell';
import { CompanyProfileCard, LoadErr } from '../components/common';
import { ProvenanceLabel, ImpactBadge, ConfidenceWord } from '../ui/Provenance';
import { ChevronLeftMIcon } from '../ui/icons';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { useCompanyContext } from '../lib/useData';
import { api, ApiError } from '../api/client';
import { deriveTile } from '../lib/derive';
import type { CompanyContext, EventRead, Insight, InsightMessage, Significance, SourceRef } from '../api/types';
import { money, revenueLabel, sharePct } from '../lib/format';
import './InsightPage.css';

const GENERATION_STEPS = ['Читаем событие', 'Сопоставляем с профилем компании', 'Ищем позицию банка в аналитике', 'Формируем вывод'];

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
  const [genError, setGenError] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const [revision, setRevision] = useState(0);
  const [preview, setPreview] = useState<{ name: string; url: string; blob: Blob; page: number } | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true); setError(null); setGenError(null); setInsight(null); setMessages([]); setMessagesError(null);
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

  useEffect(() => {
    if (!busy) return;
    setStep(0);
    const timer = window.setInterval(() => setStep((n) => Math.min(n + 1, GENERATION_STEPS.length - 1)), 4000);
    return () => window.clearInterval(timer);
  }, [busy]);

  const generate = async () => {
    if (!companyId || event?.sourceType !== 'TEXT') return;
    setBusy(true); setGenError(null);
    try {
      const value = await api.generateInsight(companyId, eventId);
      setInsight(value);
      setEvent((current) => current ? { ...current, status: 'READY' } : current);
      try { setMessages(await api.messages(companyId, eventId)); setMessagesError(null); }
      catch (e) { setMessagesError(e instanceof Error ? e.message : 'История сообщений недоступна.'); }
    } catch (e) { setGenError(e instanceof Error ? e.message : 'Не удалось сформировать разбор.'); }
    finally { setBusy(false); }
  };

  const openDocument = async (source: SourceRef) => {
    if (!source.document_id) return;
    try {
      const metadata = await api.document(source.document_id);
      const file = await api.documentFile(source.document_id);
      setPreview({ name: metadata.fileName, blob: file, url: URL.createObjectURL(file), page: source.page || 1 });
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
            <EventFactPreview event={event} ctx={context.data} />
            <section id="event-analysis" className="panel insight-status">
              <div className="eyebrow">РАЗБОР ДЛЯ КОМПАНИИ</div>
              <h2>{event.status === 'FAILED' ? 'Разбор не сформирован' : 'Инсайд по этому событию ещё не создан'}</h2>
              <p>{event.sourceType === 'TEXT' ? `Сопоставим событие с профилем компании ${companyId}, сохраним вывод и оценку значимости.` : 'Для URL и PDF извлечение текста пока не подключено, поэтому сформировать инсайд нельзя.'}</p>
              {genError && !busy && <p className="insight-generate__error" role="alert">Не удалось сформировать инсайд: {genError}</p>}
              {event.sourceType === 'TEXT' && !busy && <button className="button button--primary insight-generate" onClick={() => void generate()}>{genError ? 'Повторить' : 'Сгенерировать инсайт'}</button>}
              {busy && <ol className="gen-steps" aria-live="polite" aria-busy="true">{GENERATION_STEPS.map((label, i) => <li key={label} className={`gen-steps__item${i < step ? ' gen-steps__item--done' : i === step ? ' gen-steps__item--active' : ''}`}>{i === step && <span className="insight-generate__spinner insight-generate__spinner--dark" aria-hidden="true" />}{label}</li>)}</ol>}
            </section>
          </div>
          <aside className="insight-aside"><CompanyProfileCard ctx={context.data} onOpenProfile={() => navigate('/companies')} /></aside>
        </div>}
      </>}
    </LoadErr>
    {preview && <div className="pdf-overlay" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreview(null); }}><section className="pdf-dialog" role="dialog" aria-modal="true" aria-label={`Просмотр ${preview.name}`}><header><strong>{preview.name}</strong><div><a className="button button--small" href={preview.url} download={preview.name}>Скачать PDF</a><button className="button button--small" onClick={() => setPreview(null)}>Закрыть</button></div></header><PdfViewer blob={preview.blob} initialPage={preview.page} /></section></div>}
  </AppShell>;
}

function EventFactPreview({ event, ctx }: { event: EventRead; ctx: CompanyContext }) {
  const { profile } = ctx;
  const facts: string[] = [];
  if (event.sourceRef?.trim()) facts.push(event.sourceRef.trim());

  const industry = [profile.mainIndustryCode, profile.mainIndustryName].filter(Boolean).join(' ');
  const location = profile.region || profile.city;
  const companyDetails = [
    industry && `работает в отрасли ${industry}`,
    location && `зарегистрирована в ${location}`,
    profile.revenue != null && profile.revenue !== 1000 && `выручка около ${revenueLabel(profile.revenue)}`,
  ].filter(Boolean);
  const employees = [
    profile.employeeCountRangeFns && `ФНС: ${profile.employeeCountRangeFns}`,
    profile.employeeCountRangeSpark && `СПАРК: ${profile.employeeCountRangeSpark}`,
  ].filter(Boolean);
  if (employees.length) companyDetails.push(`штат ${employees.join(', ')}`);
  if (companyDetails.length) facts.push(`Компания ${companyDetails.join(', ')}.`);

  const counterparty = ctx.counterparties[0];
  if (counterparty) {
    const counterpartyName = [counterparty.mainIndustryCode, counterparty.mainIndustryName].filter(Boolean).join(' ');
    const paymentDetails = [
      counterparty.outgoingPaymentAmount != null && `исходящие платежи ${money(counterparty.outgoingPaymentAmount)}`,
      counterparty.paymentShare != null && `доля ${sharePct(counterparty.paymentShare)} от оборота`,
    ].filter(Boolean);
    if (counterpartyName || paymentDetails.length) {
      facts.push(`Крупнейший контрагент${counterpartyName ? ` — ${counterpartyName}` : ''}${paymentDetails.length ? `: ${paymentDetails.join(', ')}` : ''}.`);
    }
  }

  if (!facts.length) return null;
  return <section className="panel event-fact-preview">
    <div className="eyebrow">ФАКТ · КОНТЕКСТ КОМПАНИИ</div>
    <h2>Что произошло</h2>
    <div className="event-fact-preview__facts">{facts.map((fact, index) => <p key={index}>{fact}</p>)}</div>
  </section>;
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

function PdfViewer({ blob, initialPage }: { blob: Blob; initialPage: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [pageNumber, setPageNumber] = useState(initialPage);
  const [pageCount, setPageCount] = useState(0);
  const [status, setStatus] = useState('Загружаем PDF…');

  useEffect(() => {
    let active = true;
    let renderTask: ReturnType<PDFPageProxy['render']> | null = null;
    let loadingTask: PDFDocumentLoadingTask | null = null;
    setStatus('Загружаем PDF…');
    setPageCount(0);
    void import('pdfjs-dist').then((pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = `${pdfWorkerUrl}?v=2`;
      return blob.arrayBuffer().then((data) => {
      if (!active) return;
      loadingTask = pdfjs.getDocument({ data: new Uint8Array(data) });
      return loadingTask.promise.then(async (document) => {
        if (!active) return;
        const page = Math.min(Math.max(pageNumber, 1), document.numPages);
        setPageNumber(page);
        setPageCount(document.numPages);
        const pdfPage = await document.getPage(page);
        const base = pdfPage.getViewport({ scale: 1 });
        const scale = Math.min((canvas.current?.parentElement?.clientWidth ?? base.width) / base.width, 1.5);
        const viewport = pdfPage.getViewport({ scale });
        const element = canvas.current;
        const context = element?.getContext('2d');
        if (!active || !element || !context) return;
        element.width = Math.ceil(viewport.width);
        element.height = Math.ceil(viewport.height);
        renderTask = pdfPage.render({ canvas: element, canvasContext: context, viewport });
        await renderTask.promise;
        if (active) setStatus('');
      });
      });
    }).catch((error: unknown) => {
      if (!active) return;
      console.error('PDF preview failed', error);
      setStatus(`Не удалось отобразить PDF: ${error instanceof Error ? error.message : String(error)}`);
    });

    return () => {
      active = false;
      renderTask?.cancel();
      if (loadingTask) void loadingTask.destroy();
    };
  }, [blob, pageNumber]);

  return <div className="pdf-viewer">
    <div className="pdf-viewer__toolbar">
      <button className="button button--small" disabled={pageNumber <= 1} onClick={() => setPageNumber((page) => page - 1)}>← Предыдущая</button>
      <span>{pageCount ? `${Math.min(pageNumber, pageCount)} / ${pageCount}` : status}</span>
      <button className="button button--small" disabled={!pageCount || pageNumber >= pageCount} onClick={() => setPageNumber((page) => page + 1)}>Следующая →</button>
    </div>
    {status && pageCount > 0 && <p className="pdf-viewer__status">{status}</p>}
    <canvas ref={canvas} className="pdf-viewer__canvas" aria-label={`Страница PDF ${pageNumber}`} />
  </div>;
}

function InsightContent({ insight, onSource }: { insight: Insight; onSource: (source: SourceRef) => void }) {
  const confidence = insight.ai_inference[0]?.confidence;
  const [openFragment, setOpenFragment] = useState<string | null>(null);
  const sourceOf = (fragmentId: string) => insight.sources.find((x) => x.fragment_id === fragmentId);
  const showFragment = (fragmentId: string) => {
    setOpenFragment(fragmentId);
    window.setTimeout(() => document.getElementById(`src-${fragmentId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 0);
  };
  return <>
    <SignificancePanel status={insight.significance ?? 'UNKNOWN'} reason={insight.significanceReason ?? null} />
    {insight.facts.length > 0 && <section className="panel"><ProvenanceLabel kind="fact"/><h2>Что произошло</h2>{insight.facts.map((f) => <div className="cited" key={f.fragment_id}><p>{f.text}</p><button type="button" className="cited__link" onClick={() => showFragment(f.fragment_id)}>Фрагмент {f.fragment_id}</button></div>)}</section>}
    <section className="panel"><ProvenanceLabel kind="bank"/><h2>Позиция банка</h2>{insight.bank_view.length > 0 ? insight.bank_view.map((f) => { const src = sourceOf(f.fragment_id); return <div className="cited" key={f.fragment_id}><p>{f.text}</p>{src?.document_id ? <button type="button" className="cited__link" onClick={() => onSource(src)}>{sourceLabel(src)}</button> : <button type="button" className="cited__link" onClick={() => showFragment(f.fragment_id)}>Фрагмент {f.fragment_id}</button>}</div>; }) : <p className="panel__muted">Аналитики по этому событию нет.</p>}</section>
    {insight.ai_inference.length > 0 && <section className="panel panel--ai"><ProvenanceLabel kind="ai"/><h2>Почему это важно для вашей компании</h2>{insight.ai_inference.map((f, i) => <p key={i}>{f.text}</p>)}{confidence && <ConfidenceWord value={confidence} />}{insight.features_used.length > 0 && <div className="chips">{insight.features_used.map((feature) => <span className="tag" key={feature}>{feature}</span>)}</div>}</section>}
    {insight.risks.length > 0 && <TextList title="Возможные риски" items={insight.risks.map((x) => x.text)}/>}
    {insight.opportunities.length > 0 && <TextList title="Возможности" items={insight.opportunities.map((x) => x.text)}/>}
    {insight.hypotheses.length > 0 && <TextList title="Гипотезы" items={insight.hypotheses.map((x) => x.text)}/>}
    {insight.actions.length > 0 && <TextList title="Что стоит сделать" items={insight.actions.map((x) => x.text)}/>}
    {insight.missing_data.length > 0 && <TextList title="Чего мы не знаем" items={insight.missing_data}/>}
    {insight.sources.length > 0 && <section className="panel"><h2>Источники</h2>{insight.sources.map((source) => <Source key={source.fragment_id} source={source} open={openFragment === source.fragment_id} onToggle={() => setOpenFragment(openFragment === source.fragment_id ? null : source.fragment_id)} fragments={[...insight.facts, ...insight.bank_view].filter((f) => f.fragment_id === source.fragment_id).map((f) => f.text)} onOpen={() => onSource(source)}/>)}</section>}
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

function sourceLabel(source: SourceRef): string {
  return source.document_id ? `PDF · ${source.document_id}${source.page ? ` · стр. ${source.page}` : ''}` : source.url || 'Текстовый источник';
}

function Source({ source, fragments, open, onToggle, onOpen }: { source: SourceRef; fragments: string[]; open: boolean; onToggle: () => void; onOpen: () => void }) {
  const label = sourceLabel(source);
  return <div id={`src-${source.fragment_id}`} className={`source-card source-card--expandable${open ? ' source-card--open' : ''}`}>
    <div className="source-card__row">
      <button type="button" className="source-card__toggle" aria-expanded={open} onClick={onToggle}><span><strong>{label}</strong><small>{source.fragment_id}</small></span><span className="source-card__chevron" aria-hidden="true">{open ? '▴' : '▾'}</span></button>
      {source.document_id ? <button className="button button--small" onClick={onOpen}>Просмотр PDF</button> : source.url && <a className="button button--small" href={source.url} target="_blank" rel="noreferrer">Открыть</a>}
    </div>
    {open && <div className="source-card__text">{fragments.length > 0 ? fragments.map((text, i) => <p key={i}>{text}</p>) : <p className="panel__muted">Текст фрагмента недоступен.</p>}</div>}
  </div>;
}
