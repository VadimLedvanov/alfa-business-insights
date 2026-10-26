import { useEffect, useRef, useState, type FormEvent } from 'react';
import { AppShell } from '../components/AppShell';
import { api } from '../api/client';
import type { DocumentMetadata, Page } from '../api/types';
import { DocumentMIcon } from '../ui/icons';
import './SourcesPage.css';

export function SourcesPage() {
  const [documents, setDocuments] = useState<Page<DocumentMetadata> | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [preview, setPreview] = useState<{ name: string; url: string } | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    api.documents(page).then((result) => {
      if (active) setDocuments(result);
    }).catch((e) => {
      if (active) setError(e instanceof Error ? e.message : 'Не удалось загрузить документы.');
    }).finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [page, revision]);

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url);
  }, [preview]);

  const openPreview = async (doc: DocumentMetadata) => {
    setBusyId(doc.documentId);
    setError(null);
    try {
      const blob = await api.documentFile(doc.documentId);
      setPreview({ name: doc.fileName, url: URL.createObjectURL(blob) });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось открыть PDF.');
    } finally {
      setBusyId(null);
    }
  };

  const download = async (doc: DocumentMetadata) => {
    setBusyId(doc.documentId);
    setError(null);
    try {
      const blob = await api.documentFile(doc.documentId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = doc.fileName;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось скачать PDF.');
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (doc: DocumentMetadata) => {
    if (!window.confirm(`Удалить документ «${doc.fileName}» и все связанные данные?`)) return;
    setBusyId(doc.documentId);
    setError(null);
    setNotice(null);
    try {
      await api.deleteDocument(doc.documentId);
      setNotice(`Документ «${doc.fileName}» удалён.`);
      if (page > 0 && documents?.content.length === 1) setPage((current) => current - 1);
      else setRevision((current) => current + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось удалить документ.');
    } finally {
      setBusyId(null);
    }
  };

  return <AppShell active="sources">
    <div className="page-head"><div><div className="eyebrow">АДМИНИСТРИРОВАНИЕ</div><h1>Источники и материалы</h1><p className="sub">Загружайте аналитику банка и работайте с оригиналами PDF.</p></div></div>
    <div className="sources__layout">
      <UploadDocument onUploaded={(name) => { setNotice(`Файл «${name}» загружен и добавлен в базу знаний.`); setPage(0); setRevision((n) => n + 1); }} />
      <section className="card card--pad">
        <div className="sources__library-head"><div><h2 className="sources__h2">Библиотека PDF</h2><p className="sources__hint">{documents ? `${documents.totalElements} файлов` : 'Загруженные документы'}</p></div><button className="button" onClick={() => setRevision((n) => n + 1)} disabled={loading}>Обновить</button></div>
        {notice && <p className="sources__notice" role="status">{notice}</p>}
        {error && <p className="sources__error" role="alert">{error}</p>}
        {loading ? <p className="sources__hint">Загружаем документы…</p> : documents?.content.length ? <div className="mat-list">
          {documents.content.map((doc) => <article className="mat-row" key={doc.documentId}>
            <span className="mat-row__icon"><DocumentMIcon width={20} height={20} /></span>
            <div className="mat-row__text"><strong>{doc.title || doc.fileName}</strong><small>{doc.fileName} · {formatBytes(doc.sizeBytes)} · {new Date(doc.uploadedAt).toLocaleDateString('ru-RU')}</small></div>
            <div className="sources__actions"><button className="button button--small" disabled={busyId === doc.documentId} onClick={() => void openPreview(doc)}>Просмотр</button><button className="button button--small" disabled={busyId === doc.documentId} onClick={() => void download(doc)}>Скачать</button><button className="button button--small button--danger" disabled={busyId === doc.documentId} onClick={() => void remove(doc)}>{busyId === doc.documentId ? 'Удаляем…' : 'Удалить'}</button></div>
          </article>)}
        </div> : <div className="empty">Загруженных PDF пока нет.</div>}
        {documents && documents.totalPages > 1 && <div className="pagination"><button className="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Назад</button><span>{page + 1} / {documents.totalPages}</span><button className="button" disabled={page + 1 >= documents.totalPages} onClick={() => setPage((p) => p + 1)}>Далее →</button></div>}
      </section>
    </div>
    {preview && <div className="pdf-overlay" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreview(null); }}><section className="pdf-dialog" role="dialog" aria-modal="true" aria-label={`Просмотр ${preview.name}`}><header><strong>{preview.name}</strong><button className="button button--small" onClick={() => setPreview(null)}>Закрыть</button></header><iframe title={preview.name} src={preview.url} /></section></div>}
  </AppShell>;
}

function UploadDocument({ onUploaded }: { onUploaded: (name: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) { setError('Выберите PDF-файл.'); return; }
    if (file.size > 50 * 1024 * 1024) { setError('Размер PDF не должен превышать 50 МБ.'); return; }
    setBusy(true); setError(null);
    try {
      const result = await api.uploadDocument(file);
      onUploaded(result.stub ? `${result.fileName} (сохранён; ML-заглушка не анализировала PDF)` : result.fileName);
      setFile(null); setInputKey((key) => key + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить PDF.');
    } finally { setBusy(false); }
  };

  return <section className="card card--pad"><h2 className="sources__h2">Добавить PDF</h2><p className="sources__hint">Файл до 50 МБ будет сохранён и проиндексирован в базе знаний.</p>
    <form onSubmit={(e) => void submit(e)} aria-busy={busy}>
      <button type="button" className="sources__drop" disabled={busy} onClick={() => input.current?.click()}><DocumentMIcon width={28} height={28} /><span className="sources__drop-text">{file?.name ?? 'Выберите PDF с аналитикой банка'}</span><span className="sources__drop-btn">Выбрать файл</span></button>
      <input key={inputKey} ref={input} type="file" accept="application/pdf,.pdf" hidden disabled={busy} onChange={(e) => { setFile(e.target.files?.[0] ?? null); setError(null); }} />
      {error && <p className="sources__error" role="alert">{error}</p>}
      <button className="button button--primary" disabled={busy}>{busy && <span className="sources__spinner" aria-hidden="true" />}{busy ? 'Загружаем…' : 'Загрузить'}</button>
      {busy && <div className="sources__uploading" role="status" aria-live="polite"><span>Файл загружается и индексируется</span><span className="sources__progress"><span /></span></div>}
    </form>
  </section>;
}

function formatBytes(size: number): string {
  return size < 1024 * 1024 ? `${Math.max(1, Math.round(size / 1024))} КБ` : `${(size / (1024 * 1024)).toFixed(1)} МБ`;
}
