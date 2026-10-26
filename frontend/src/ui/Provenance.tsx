import type { CSSProperties } from 'react';
import type { Confidence, ImpactLevel } from '../api/types';
import { impactLabel } from '../lib/format';
import { AlfaGlyph } from './Logo';
import { DocumentMIcon, MagicWandMIcon, BulbMIcon, CircleCrossedOutMIcon, LinkMIcon, ArrowRightUpMIcon } from './icons';

const chipBase: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4, height: 24, padding: '0 8px',
  borderRadius: 6, fontSize: 13, lineHeight: '16px', fontWeight: 500, whiteSpace: 'nowrap', boxSizing: 'border-box',
};

export type ProvKind = 'fact' | 'bank' | 'ai' | 'hypothesis' | 'nodata';

const PROV: Record<ProvKind, { label: string; style: CSSProperties; icon: JSX.Element }> = {
  fact: {
    label: 'Факт',
    style: { background: 'var(--radar-prov-fact-bg)', color: 'var(--radar-prov-fact-text)' },
    icon: <DocumentMIcon width={16} height={16} />,
  },
  bank: {
    label: 'Позиция банка',
    style: { background: 'var(--radar-prov-bank-bg)', color: 'var(--radar-prov-bank-text)' },
    icon: <AlfaGlyph size={16} color="#ffffff" />,
  },
  ai: {
    label: 'Вывод AI',
    style: { background: 'var(--radar-prov-ai-bg)', color: 'var(--radar-prov-ai-text)' },
    icon: <MagicWandMIcon width={16} height={16} />,
  },
  hypothesis: {
    label: 'Гипотеза',
    style: { background: 'transparent', color: 'var(--radar-prov-hypothesis-text)', border: '1px dashed var(--radar-prov-hypothesis-border)' },
    icon: <BulbMIcon width={16} height={16} />,
  },
  nodata: {
    label: 'Нет данных',
    style: { background: 'transparent', color: 'var(--radar-prov-nodata-text)', border: '1px dashed var(--radar-prov-nodata-border)' },
    icon: <CircleCrossedOutMIcon width={16} height={16} />,
  },
};

/** Метка происхождения утверждения (критерий кейса 03). Одинакова в ленте, разборе и чате. */
export function ProvenanceLabel({ kind }: { kind: ProvKind }) {
  const p = PROV[kind];
  return (
    <span style={{ ...chipBase, ...p.style }}>
      {p.icon}
      {p.label}
    </span>
  );
}

const IMPACT: Record<ImpactLevel, { style: CSSProperties; mark: JSX.Element }> = {
  high: { style: { background: 'var(--radar-impact-high-bg)', color: 'var(--radar-impact-high-text)' }, mark: <Dot color="var(--color-light-status-negative)" /> },
  medium: { style: { background: 'var(--radar-impact-medium-bg)', color: 'var(--radar-impact-medium-text)' }, mark: <Dot color="var(--color-light-status-attention)" /> },
  indirect: { style: { background: 'var(--radar-impact-indirect-bg)', color: 'var(--radar-impact-indirect-text)' }, mark: <LinkMIcon width={16} height={16} /> },
  opportunity: { style: { background: 'var(--radar-opportunity-bg)', color: 'var(--radar-opportunity-text)' }, mark: <ArrowRightUpMIcon width={16} height={16} /> },
};

function Dot({ color }: { color: string }) {
  return <span style={{ width: 6, height: 6, borderRadius: '50%', background: color, flex: 'none' }} />;
}

/** Бейдж значимости события для компании. */
export function ImpactBadge({ level }: { level: ImpactLevel }) {
  const i = IMPACT[level];
  return (
    <span style={{ ...chipBase, gap: 6, ...i.style }}>
      {i.mark}
      {impactLabel(level)}
    </span>
  );
}

const CONF: Record<Confidence, string> = { high: 'высокая', medium: 'средняя', low: 'низкая' };

/** Уверенность вывода словами (решение продакта): без шкал и процентов. */
export function ConfidenceWord({ value }: { value: Confidence }) {
  return (
    <span style={{ fontSize: 13, lineHeight: '16px', color: 'var(--color-light-text-secondary)' }}>
      Уверенность: <b style={{ fontWeight: 600, color: 'var(--radar-prov-ai-text)' }}>{CONF[value]}</b>
    </span>
  );
}
