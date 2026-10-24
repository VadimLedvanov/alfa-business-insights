import type { CompanyContext, Insight, ImpactLevel } from '../api/types';
import type { ProvKind } from '../ui/Provenance';
import { impactOf } from './format';

export interface TileData {
  impact: ImpactLevel;
  conclusion: string; // вывод для компании (заголовок карточки), а не текст новости
  why: string[]; // признаки компании, которые сработали (features_used)
  labels: ProvKind[]; // метки происхождения, присутствующие в инсайте
}

/** Первое предложение вывода AI — используем как заголовок-вывод карточки. */
function firstSentence(text: string): string {
  const m = text.match(/^(.*?[.!?])(\s|$)/);
  return (m ? m[1] : text).trim();
}

export function deriveTile(ctx: CompanyContext, insight: Insight): TileData {
  const labels: ProvKind[] = [];
  if (insight.facts.length) labels.push('fact');
  if (insight.ai_inference.length) labels.push('ai');
  if (insight.bank_view.length) labels.push('bank');
  if (insight.hypotheses.length) labels.push('hypothesis');
  if (insight.missing_data.length) labels.push('nodata');

  const conclusion = insight.ai_inference[0] ? firstSentence(insight.ai_inference[0].text) : 'Событие может затронуть вашу компанию';

  // В ленте показываем до двух самых важных меток: вывод AI и позицию банка/гипотезу.
  const tileLabels = labels.filter((l) => l === 'ai' || l === 'bank' || l === 'hypothesis').slice(0, 2);

  return {
    impact: impactOf(ctx, insight),
    conclusion,
    why: insight.features_used.slice(0, 3),
    labels: tileLabels.length ? tileLabels : labels.slice(0, 2),
  };
}
