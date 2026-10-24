import { AppShell } from '../components/AppShell';
import { InformationBanner } from '../components/InformationBanner';
import { DocumentsLinesMIcon } from '../ui/icons';

/** Заглушка раздела администратора: полная загрузка материалов и смена ленты — в следующем PR. */
export function SourcesPage() {
  return (
    <AppShell active="sources">
      <div className="page-head">
        <div>
          <div className="eyebrow">АДМИНИСТРИРОВАНИЕ</div>
          <h1>Источники и материалы</h1>
          <p className="sub">Подготовка данных перед появлением инсайтов.</p>
        </div>
      </div>
      <InformationBanner>
        Загрузка новостей и PDF, статусы обработки и обновление ленты компании готовятся в следующем куске фронта.
        Сейчас раздел виден только роли администратора.
      </InformationBanner>
      <section className="card card--pad" style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <span style={{ width: 44, height: 44, borderRadius: 12, background: 'var(--color-light-neutral-200)', display: 'grid', placeItems: 'center', flex: 'none' }}>
          <DocumentsLinesMIcon width={24} height={24} color="var(--color-light-graphic-secondary)" />
        </span>
        <div style={{ fontSize: 14, color: 'var(--color-light-text-secondary)' }}>
          Раздел в разработке. Перейдите в Радар, чтобы посмотреть ленту и разбор событий.
        </div>
      </section>
    </AppShell>
  );
}
