// Знак «А» из @alfalab/icons-logotype (AlfaBankLogoMIcon), встроен как путь,
// чтобы не тянуть отдельный пакет. Используется в логотипе оболочки и на метке «Позиция банка».

const PATH =
  'M11.983 5.012L10.14 10.49h3.652l-1.74-5.48h-.07zM12.119 2c1.48 0 1.912.883 2.286 2l3.875 11.56h-2.867l-.87-2.748H9.355l-.939 2.748H5.72L9.786 4c.394-1.12.854-2 2.333-2zM5.5 19h13v3h-13v-3z';

/** Одноцветный знак «А» (currentColor). */
export function AlfaGlyph({ size = 16, color }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={color ?? 'currentColor'} aria-hidden="true" style={{ display: 'block', flex: 'none' }}>
      <path d={PATH} fillRule="evenodd" clipRule="evenodd" />
    </svg>
  );
}

/** Логотип Альфа-Бизнеса: красный квадрат со скруглением и белый знак «А». */
export function AlfaLogo({ size = 40 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 40 40" aria-hidden="true" style={{ display: 'block', flex: 'none' }}>
      <rect width="40" height="40" rx="10" fill="var(--color-light-accent-primary)" />
      <g transform="translate(8 8)" fill="#ffffff">
        <path d={PATH} fillRule="evenodd" clipRule="evenodd" />
      </g>
    </svg>
  );
}
