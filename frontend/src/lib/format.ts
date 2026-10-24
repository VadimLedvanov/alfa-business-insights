// Форматирование по правилам кейса и терминам из созвона.

import type { CompanyContext, CompanyProfile, ImpactLevel, Insight } from '../api/types';

const nf = new Intl.NumberFormat('ru-RU');

/** ID компании с разделителем тысяч: «U-104233» → «U-104 233». */
export function companyIdLabel(id: string): string {
  const m = id.match(/^([A-Za-zА-Яа-я-]+)(\d+)$/);
  if (!m) return id;
  // Разбиваем цифры по три справа, сохраняя ведущие нули (U-087512 → U-087 512).
  const grouped = m[2].replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${m[1]}${grouped}`;
}

/** «U-104 233 · Грузоперевозки». */
export function companyTitle(c: CompanyContext): string {
  const ind = c.profile.mainIndustryName;
  return ind ? `${companyIdLabel(c.profile.companyId)} · ${ind}` : companyIdLabel(c.profile.companyId);
}

/** Крупная сумма в рублях. */
export function money(v: number): string {
  return `${nf.format(Math.round(v))} ₽`.replace(/ /g, ' ');
}

/** Выручка: 1000 — техническая заглушка, показываем «нет данных». */
export function revenueLabel(revenue: number | null): string {
  if (revenue == null || revenue === 1000) return 'нет данных';
  if (revenue >= 1_000_000_000) {
    const bln = revenue / 1_000_000_000;
    return `${nf.format(Math.round(bln * 10) / 10)} млрд ₽`.replace(/ /g, ' ');
  }
  return money(revenue);
}

/** Доля 0..1 → «21%». */
export function sharePct(v: number | null): string {
  if (v == null) return 'нет данных';
  return `${Math.round(v * 100)}%`;
}

/** Продукты банка у компании — человекочитаемый список. */
export function bankProducts(p: CompanyProfile): string[] {
  const out: string[] = [];
  if (p.hasLoan) out.push('Кредит');
  if (p.hasLeasing) out.push('Лизинг');
  if (p.hasBankGuarantee) out.push('Гарантии');
  if (p.hasDeposit) out.push('Депозит');
  if (p.hasFactoring) out.push('Факторинг');
  if (p.hasPayrollProject) out.push('Зарплатный проект');
  if (p.hasSettlementAccount) out.push('РКО');
  return out;
}

/** ВЭД со страной, без направления (в данных ФТС направления нет). */
export function foreignTradeLabel(p: CompanyProfile): string | null {
  if (!p.hasForeignTrade) return null;
  return p.foreignTradeCountries ? `ВЭД, страна: ${p.foreignTradeCountries}` : 'ВЭД';
}

const IMPACT_LABEL: Record<ImpactLevel, string> = {
  high: 'Высокое влияние',
  medium: 'Среднее влияние',
  indirect: 'Косвенное влияние',
  opportunity: 'Возможность',
};
export const impactLabel = (l: ImpactLevel) => IMPACT_LABEL[l];

/**
 * Значимость события для компании. Вычисляется фронтом, пока бэк не отдаёт ранжирование:
 * прямой продукт под риском → высокое, косвенная связь → косвенное, иначе среднее.
 */
export function impactOf(ctx: CompanyContext, insight: Insight | null): ImpactLevel {
  const p = ctx.profile;
  const text = insight ? insight.ai_inference.map((a) => a.text).join(' ').toLowerCase() : '';
  const hasOpportunity = !!insight && insight.opportunities.length > 0;
  const positive = /возможност|рост спроса|поддерживает спрос|увеличить объ/.test(text);
  const indirect = /косвен|через связ|связанн/.test(text);

  if (indirect) return 'indirect';
  if (hasOpportunity && positive) return 'opportunity';
  if ((p.hasLoan || p.hasLeasing) && insight && insight.ai_inference.some((a) => a.confidence !== 'low')) return 'high';
  return 'medium';
}
