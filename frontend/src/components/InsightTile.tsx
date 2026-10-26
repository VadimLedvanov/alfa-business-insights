import type { EventRead } from '../api/types';
import type { TileData } from '../lib/derive';
import { ImpactBadge, ProvenanceLabel } from '../ui/Provenance';
import { ChevronRightMIcon } from '../ui/icons';

const dateFmt = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });

function category(ev: EventRead): string {
  if (ev.title.toLowerCase().includes('ставк')) return 'Ключевая ставка';
  if (ev.title.toLowerCase().includes('потреб')) return 'Потребительский спрос';
  return 'Макроэкономика';
}

export function InsightTile({ event, tile, onClick, isNew }: { event: EventRead; tile: TileData; onClick: () => void; isNew?: boolean }) {
  const whyText = tile.why.join(', ');
  return (
    <button className={`insight-tile${isNew ? ' insight-tile--new' : ''}`} onClick={onClick}>
      <span className="insight-tile__meta">
        {isNew && <span className="insight-tile__new">Новое</span>}
        <ImpactBadge level={tile.impact} />
        <span>{dateFmt.format(new Date(event.createdAt))} · {category(event)}</span>
      </span>
      <span className="insight-tile__title">{tile.conclusion}</span>
      {whyText && (
        <span className="insight-tile__why"><b>Почему вам:</b> {whyText}</span>
      )}
      <span className="insight-tile__labels">
        {tile.labels.map((k) => <ProvenanceLabel key={k} kind={k} />)}
        <span className="insight-tile__arrow"><ChevronRightMIcon width={20} height={20} color="var(--color-light-graphic-primary)" /></span>
      </span>
    </button>
  );
}
