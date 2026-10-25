import type { ReactNode } from 'react';
import type { CompanyContext } from '../api/types';
import { bankProducts, companyIdLabel, foreignTradeLabel, revenueLabel } from '../lib/format';
import { ProvenanceLabel, type ProvKind } from '../ui/Provenance';
import { ChevronRightMIcon } from '../ui/icons';

const nf = new Intl.NumberFormat('ru-RU');

/** Сумма: целая часть основным цветом, дробь и «₽» — третичным (как в Альфа-Бизнесе). */
export function Money({ value, size = 28, line = 32, frac = ',00' }: { value: number; size?: number; line?: number; frac?: string }) {
  return (
    <span className="money" style={{ fontSize: size, lineHeight: `${line}px`, fontWeight: 700 }}>
      {nf.format(Math.round(value)).replace(/ /g, ' ')}
      <span className="money__frac">{frac}&nbsp;₽</span>
    </span>
  );
}

export function LoadErr({ loading, error, onRetry, children }: { loading: boolean; error: string | null; onRetry?: () => void; children: ReactNode }) {
  if (loading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="skeleton" style={{ height: 120 }} />
        <div className="skeleton" style={{ height: 120 }} />
      </div>
    );
  }
  if (error) {
    return (
      <div className="empty">
        Не удалось загрузить данные: {error}
        {onRetry && (
          <div style={{ marginTop: 12 }}>
            <button className="tag" style={{ cursor: 'pointer', border: 0 }} onClick={onRetry}>Повторить</button>
          </div>
        )}
      </div>
    );
  }
  return <>{children}</>;
}

/** Карточка профиля компании для правой колонки ленты и разбора. */
export function CompanyProfileCard({ ctx, onOpenProfile }: { ctx: CompanyContext; onOpenProfile?: () => void }) {
  const p = ctx.profile;
  const products = bankProducts(p);
  const ved = foreignTradeLabel(p);
  const links = ctx.counterparties.length;
  const groupSize = ctx.group.length;
  return (
    <section className="card card--pad" aria-label="Профиль компании">
      <div className="eyebrow">КОНТЕКСТ ВАШЕГО БИЗНЕСА</div>
      <div className="profile__row">
        <div className="profile__mono">{initials(p.companyId)}</div>
        <div>
          <div className="profile__name">{companyIdLabel(p.companyId)}</div>
          <div className="profile__industry">{p.mainIndustryName ?? 'Отрасль не указана'}</div>
        </div>
      </div>
      <dl className="profile__list">
        <div className="profile__line"><dt>Регион</dt><dd>{p.region ?? 'нет данных'}</dd></div>
        <div className="profile__line"><dt>Выручка</dt><dd>{revenueLabel(p.revenue)}</dd></div>
        {ved && <div className="profile__line"><dt>Внешняя торговля</dt><dd>{ved.replace('ВЭД, страна: ', '')}</dd></div>}
        <div className="profile__line"><dt>Группа компаний</dt><dd>{groupSize > 1 ? `${groupSize} компании` : p.groupId ? 'есть' : 'нет'}</dd></div>
        <div className="profile__line"><dt>Связей по платежам</dt><dd>{links}</dd></div>
      </dl>
      {products.length > 0 && (
        <div className="chips" style={{ marginTop: 16 }}>
          {products.map((t) => <span key={t} className="tag">{t}</span>)}
        </div>
      )}
      {onOpenProfile && (
        <button className="tag" style={{ cursor: 'pointer', border: 0, marginTop: 16, gap: 6 }} onClick={onOpenProfile}>
          Профиль компании <ChevronRightMIcon width={16} height={16} />
        </button>
      )}
    </section>
  );
}

export function initials(companyId: string): string {
  const digits = companyId.replace(/\D/g, '');
  return digits.slice(0, 2) || 'U';
}

export function provKinds(labels: ProvKind[]): JSX.Element {
  return (
    <>
      {labels.map((k) => <ProvenanceLabel key={k} kind={k} />)}
    </>
  );
}
