import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { CompanyListItem, Page } from '../api/types';
import { Button } from '@alfalab/core-components/button';
import { AppShell } from '../components/AppShell';
import { LoadErr, MissingDataNote, initials } from '../components/common';
import { useCompanyContext } from '../lib/useData';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { bankProducts, companyIdLabel, foreignTradeLabel, revenueLabel } from '../lib/format';
import { InformationBanner } from '../components/InformationBanner';
import './CompanySelectPage.css';

export function CompanySelectPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<CompanyListItem> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const reload = () => setTick((n) => n + 1);
  useEffect(() => {
    let alive = true; setLoading(true); setError(null);
    api.companies(page).then((value) => { if (alive) setData(value); }).catch((e) => { if (alive) setError(e.message); }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [page, tick]);
  return (
    <AppShell active="companies">
      <div className="page-head">
        <div>
          <div className="eyebrow">BUSINESS INSIGHTS</div>
          <h1>Мои компании</h1>
          <p className="sub">Выберите, для какого бизнеса показать события и их влияние.</p>
        </div>
      </div>

      <InformationBanner>
        Данные обезличены, поэтому компании названы по ID и отрасли. Откройте одну и ту же новость для разных
        компаний — вывод будет разным: у кого-то ставка касается своего кредита, у кого-то — через связанного плательщика.
      </InformationBanner>

      <LoadErr loading={loading} error={error} onRetry={reload}>
        <div className="companies-grid">
          {(data?.content ?? []).map((c) => <CompanyCard key={c.companyId} id={c.companyId} />)}
        </div>
      {data?.totalElements === 0 && <div className="empty">Нет доступных компаний.</div>}
      </LoadErr>
      {data && data.totalPages > 1 && <div className="pagination"><button className="button" disabled={page === 0} onClick={() => setPage(page - 1)}>← Назад</button><span>{page + 1} / {data.totalPages}</span><button className="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Далее →</button></div>}
    </AppShell>
  );
}

function CompanyCard({ id }: { id: string }) {
  const { data: ctx, loading, error, reload } = useCompanyContext(id);
  const { companyId, setCompany } = useCompany();
  const { navigate } = useRouter();
  const selected = companyId === id;

  const open = () => {
    setCompany(id);
    navigate('/home');
  };

  if (error) return <LoadErr loading={false} error={error} onRetry={reload}>{null}</LoadErr>;
  if (loading || !ctx) {
    return <div className="card card--pad company-card company-card--loading" aria-busy="true"><div className="skeleton" /></div>;
  }
  const p = ctx.profile;
  const products = bankProducts(p);
  const ved = foreignTradeLabel(p);

  return (
    <section className={`card card--pad company-card${selected ? ' company-card--selected' : ''}`}>
      <div className="company-card__header">
        <div className="profile__mono">{initials(id)}</div>
        {selected && <span className="tag" style={{ background: 'var(--radar-impact-high-bg)', color: 'var(--radar-impact-high-text)' }}>Выбрана</span>}
      </div>
      <h2 className="company-card__name">{companyIdLabel(id)}</h2>
      <p className="company-card__industry">{p.mainIndustryName}</p>
      <dl className="profile__list company-card__details">
        <div className="profile__line"><dt>Регион</dt><dd>{p.region ?? 'нет данных'}</dd></div>
        <div className="profile__line"><dt>Выручка</dt><dd>{revenueLabel(p.revenue)}</dd></div>
        {ved && <div className="profile__line"><dt>ВЭД</dt><dd>{ved.replace('ВЭД, страна: ', '')}</dd></div>}
        <div className="profile__line"><dt>Связей по платежам</dt><dd>{ctx.counterparties.length}</dd></div>
      </dl>
      {products.length > 0 && (
        <div className="chips">{products.map((t) => <span key={t} className="tag">{t}</span>)}</div>
      )}
      <MissingDataNote items={ctx.missingData} />
      <div className="company-card__action">
        <Button view={selected ? 'primary' : 'accent'} size={48} block onClick={open}>
          Открыть Радар
        </Button>
      </div>
    </section>
  );
}
