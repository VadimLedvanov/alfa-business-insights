import { Button } from '@alfalab/core-components/button';
import { AppShell } from '../components/AppShell';
import { LoadErr, initials } from '../components/common';
import { useCompanies, useCompanyContext } from '../lib/useData';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { bankProducts, companyIdLabel, foreignTradeLabel, revenueLabel } from '../lib/format';
import { InformationBanner } from '../components/InformationBanner';
import './CompanySelectPage.css';

export function CompanySelectPage() {
  const { data, loading, error, reload } = useCompanies();
  return (
    <AppShell active="radar">
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
          {(data ?? []).map((c) => <CompanyCard key={c.id} id={c.id} />)}
        </div>
      </LoadErr>
    </AppShell>
  );
}

function CompanyCard({ id }: { id: string }) {
  const { data: ctx } = useCompanyContext(id);
  const { companyId, setCompany } = useCompany();
  const { navigate } = useRouter();
  const selected = companyId === id;

  const open = () => {
    setCompany(id);
    navigate('/home');
  };

  if (!ctx) {
    return <div className="card card--pad company-card"><div className="skeleton" style={{ height: 180 }} /></div>;
  }
  const p = ctx.profile;
  const products = bankProducts(p);
  const ved = foreignTradeLabel(p);

  return (
    <section className={`card card--pad company-card${selected ? ' company-card--selected' : ''}`}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div className="profile__mono">{initials(id)}</div>
        {selected && <span className="tag" style={{ background: 'var(--radar-impact-high-bg)', color: 'var(--radar-impact-high-text)' }}>Выбрана</span>}
      </div>
      <h2 className="company-card__name">{companyIdLabel(id)}</h2>
      <p className="company-card__industry">{p.mainIndustryName}</p>
      <dl className="profile__list" style={{ margin: '18px 0', paddingTop: 16, borderTop: '1px solid var(--color-light-border-secondary)' }}>
        <div className="profile__line"><dt>Регион</dt><dd>{p.region ?? 'нет данных'}</dd></div>
        <div className="profile__line"><dt>Выручка</dt><dd>{revenueLabel(p.revenue)}</dd></div>
        {ved && <div className="profile__line"><dt>ВЭД</dt><dd>{ved.replace('ВЭД, страна: ', '')}</dd></div>}
        <div className="profile__line"><dt>Связей по платежам</dt><dd>{ctx.counterparties.length}</dd></div>
      </dl>
      {products.length > 0 && (
        <div className="chips">{products.map((t) => <span key={t} className="tag">{t}</span>)}</div>
      )}
      <div style={{ marginTop: 20 }}>
        <Button view={selected ? 'primary' : 'accent'} size={48} block onClick={open}>
          Открыть Радар
        </Button>
      </div>
    </section>
  );
}
