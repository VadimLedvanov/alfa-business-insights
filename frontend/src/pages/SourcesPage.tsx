import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button } from '@alfalab/core-components/button';
import { AppShell } from '../components/AppShell';
import { InformationBanner } from '../components/InformationBanner';
import {
  DocumentMIcon, DocumentPlusMIcon, ArrowRightMIcon, ClockMIcon, ArrowsCwMIcon,
  CheckmarkCircleMIcon, ExclamationCircleMIcon,
} from '../ui/icons';
import { addMaterial, listMaterials, subscribe, type Material, type MaterialStatus } from '../api/sessionStore';
import { useRouter } from '../app/router';
import { useCompany } from '../app/company';
import './SourcesPage.css';

const EXAMPLE: { title: string; meta: string }[] = [
  { title: 'ALFA-Macro_Insights-Policy_Rate_11Sep.pdf', meta: 'PDF · материал ЦМИ · ключевая ставка' },
  { title: 'ALFA-consumption.pdf', meta: 'PDF · материал ЦМИ · потребление' },
  { title: 'Профили компаний и связи', meta: '3 CSV · загружаются при старте' },
];

export function SourcesPage() {
  const [, force] = useState(0);
  const { navigate } = useRouter();
  const { companyId } = useCompany();

  useEffect(() => subscribe(() => force((n) => n + 1)), []);

  const materials = listMaterials();
  const lastReady = materials.find((m) => m.status === 'ready');

  return (
    <AppShell active="sources">
      <div className="page-head">
        <div>
          <div className="eyebrow">АДМИНИСТРИРОВАНИЕ</div>
          <h1>Источники и материалы</h1>
          <p className="sub">Добавьте новость или аналитику — после обработки появится новый инсайт в ленте компаний.</p>
        </div>
      </div>

      <InformationBanner>
        Это демонстрация пайплайна: материал проходит статусы обработки, затем в ленте компании появляется
        новый разбор. Так видно, что выводы формируются из данных, а не заготовлены заранее.
      </InformationBanner>

      {lastReady && (
        <div className="sources__cta">
          <CheckmarkCircleMIcon width={24} height={24} color="var(--color-light-graphic-positive)" />
          <span>Материал «{lastReady.title}» обработан — в ленте появился новый инсайт.</span>
          <Button view="primary" size={40} rightAddons={<ArrowRightMIcon width={20} height={20} />} onClick={() => navigate(companyId ? '/feed' : '/companies')}>
            Открыть ленту
          </Button>
        </div>
      )}

      <div className="sources__layout">
        <AddMaterialForm />
        <MaterialsLibrary materials={materials} />
      </div>
    </AppShell>
  );
}

function AddMaterialForm() {
  const [title, setTitle] = useState('');
  const [url, setUrl] = useState('');
  const [text, setText] = useState('');
  const [fileName, setFileName] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const name = title.trim() || fileName || url.trim() || 'Новый материал';
    const kind = fileName ? 'pdf' : url.trim() ? 'link' : 'text';
    if (!fileName && !url.trim() && !text.trim()) return;
    addMaterial(name, kind);
    setTitle(''); setUrl(''); setText(''); setFileName('');
    if (fileRef.current) fileRef.current.value = '';
  };

  return (
    <section className="card card--pad">
      <h2 className="sources__h2">Добавить материал</h2>
      <p className="sources__hint">PDF-аналитика, ссылка на новость или текст. После обработки материал используется в разборе.</p>

      <form onSubmit={submit}>
        <label className="sources__drop">
          <DocumentPlusMIcon width={28} height={28} color="var(--color-light-graphic-secondary)" />
          <span className="sources__drop-text">{fileName || 'Выбрать PDF с аналитикой или новостью'}</span>
          <input
            ref={fileRef}
            type="file"
            accept=".pdf,.csv"
            hidden
            onChange={(e) => setFileName(e.target.files?.[0]?.name ?? '')}
          />
          <span className="sources__drop-btn">Выбрать файл</span>
        </label>

        <label className="field">
          <span>Название материала</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={150} placeholder="Например, обзор рынка за октябрь" />
        </label>
        <label className="field">
          <span>Ссылка на новость</span>
          <input value={url} onChange={(e) => setUrl(e.target.value)} type="url" placeholder="https://example.com/news" />
        </label>
        <label className="field">
          <span>Или текст новости</span>
          <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={10000} placeholder="Вставьте текст для анализа" />
        </label>

        <Button view="primary" size={48} rightAddons={<ArrowRightMIcon width={20} height={20} />} type="submit">
          Отправить на обработку
        </Button>
      </form>
    </section>
  );
}

const STATUS: Record<MaterialStatus, { label: string; cls: string; icon: JSX.Element }> = {
  queued: { label: 'В очереди', cls: 'st--queued', icon: <ClockMIcon width={16} height={16} /> },
  processing: { label: 'Обрабатывается', cls: 'st--processing', icon: <ArrowsCwMIcon width={16} height={16} /> },
  ready: { label: 'Готов', cls: 'st--ready', icon: <CheckmarkCircleMIcon width={16} height={16} /> },
  error: { label: 'Ошибка', cls: 'st--error', icon: <ExclamationCircleMIcon width={16} height={16} /> },
};

function MaterialsLibrary({ materials }: { materials: Material[] }) {
  return (
    <section className="card card--pad">
      <h2 className="sources__h2">Библиотека материалов</h2>
      <p className="sources__hint">Добавленные вами материалы и примеры источников банка.</p>

      <div className="mat-list">
        {materials.map((m) => {
          const s = STATUS[m.status];
          return (
            <div key={m.id} className="mat-row">
              <span className="mat-row__icon"><DocumentMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" /></span>
              <div className="mat-row__text">
                <strong>{m.title}</strong>
                <small>{m.kind === 'pdf' ? 'PDF' : m.kind === 'link' ? 'Ссылка' : 'Текст'}</small>
              </div>
              <span className={`mat-status ${s.cls}`}>{s.icon}{s.label}</span>
            </div>
          );
        })}
        {EXAMPLE.map((e) => (
          <div key={e.title} className="mat-row">
            <span className="mat-row__icon"><DocumentMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" /></span>
            <div className="mat-row__text">
              <strong>{e.title}</strong>
              <small>{e.meta}</small>
            </div>
            <span className="mat-status st--example">Пример</span>
          </div>
        ))}
      </div>
    </section>
  );
}
