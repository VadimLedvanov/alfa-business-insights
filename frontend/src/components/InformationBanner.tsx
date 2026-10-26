import type { ReactNode } from 'react';
import { InformationCircleMIcon } from '@alfalab/icons-glyph/InformationCircleMIcon';

/** Нейтральная информационная полоса (подсказка на экране). */
export function InformationBanner({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        display: 'flex', gap: 12, alignItems: 'flex-start',
        padding: '16px 18px', borderRadius: 12,
        background: 'var(--color-light-neutral-translucent-100)',
        fontSize: 14, lineHeight: '20px', color: 'var(--color-light-text-secondary)',
      }}
    >
      <InformationCircleMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" style={{ flex: 'none', marginTop: 1 }} />
      <span>{children}</span>
    </div>
  );
}
