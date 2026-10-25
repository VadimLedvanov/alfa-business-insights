import { useEffect, useRef, useState, type ReactNode } from 'react';
import './AppShell.css';
import { AlfaLogo } from '../ui/Logo';
import {
  RadarMIcon, PlusCircleMIcon, PlusMIcon, ArrowsRightLeftMIcon, DocumentArrowRightMIcon, DocumentLinesMIcon,
  WalletMIcon, UsersMIcon, CalculatorMIcon, BanknotesMIcon, CardMIcon, DocumentsLinesMIcon,
  ShoppingCartMIcon, AScoresCircleMIcon, MagnifierMIcon, CalendarMIcon, MailMIcon, BellMIcon,
  BriefcaseGearMIcon, SquareRectangleRightCompactMIcon, BubbleLinesMIcon, ChevronDownMIcon,
  CheckmarkCircleMIcon, LogOutMIcon,
} from '../ui/icons';
import { useAuth } from '../auth/AuthContext';
import { useCompany } from '../app/company';
import { useRouter } from '../app/router';
import { useCompanies } from '../lib/useData';
import { companyIdLabel } from '../lib/format';

type NavKey = 'radar' | 'sources' | null;

const BANK_MENU: { label: string; Icon: typeof RadarMIcon }[] = [
  { label: 'Новый платёж', Icon: PlusCircleMIcon },
  { label: 'Платежи в работе', Icon: ArrowsRightLeftMIcon },
  { label: 'Импорт платежей', Icon: DocumentArrowRightMIcon },
  { label: 'Выписка', Icon: DocumentLinesMIcon },
  { label: 'Счета', Icon: WalletMIcon },
  { label: 'Контрагенты', Icon: UsersMIcon },
];
const SERVICES: { label: string; Icon: typeof RadarMIcon }[] = [
  { label: 'Эквайринг и касса', Icon: CalculatorMIcon },
  { label: 'Зарплатный проект', Icon: BanknotesMIcon },
  { label: 'Карты', Icon: CardMIcon },
  { label: 'Документооборот', Icon: DocumentsLinesMIcon },
];

export function AppShell({ active, children }: { active: NavKey; children: ReactNode }) {
  const { me, isAdmin, signOut } = useAuth();
  const { companyId, setCompany } = useCompany();
  const { navigate } = useRouter();

  return (
    <div className="shell">
      <aside className="shell__side">
        <button className="nav__item" style={{ height: 40, marginBottom: 20 }} onClick={() => navigate('/home')} aria-label="Альфа-Бизнес, на главную">
          <AlfaLogo size={40} />
          <span style={{ fontSize: 17, lineHeight: '22px', fontWeight: 700, letterSpacing: '-0.2px' }}>Альфа-Бизнес</span>
        </button>

        <nav className="nav" aria-label="Основное меню">
          {BANK_MENU.map(({ label, Icon }, i) => (
            <button key={label} className={`nav__item${i === 0 ? ' nav__item--active' : ''}`} onClick={() => {}}>
              <Icon width={24} height={24} color="var(--color-light-graphic-primary)" />
              <span className="nav__label">{label}</span>
            </button>
          ))}
        </nav>

        <div className="nav__caps">
          <span>МОИ СЕРВИСЫ</span>
          <button className="ib" style={{ width: 32, height: 32 }} aria-label="Добавить сервис">
            <PlusMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" />
          </button>
        </div>

        <nav className="nav" aria-label="Мои сервисы">
          <button className={`nav__item${active === 'radar' ? ' nav__item--active' : ''}`} onClick={() => navigate('/home')}>
            <RadarMIcon width={24} height={24} color="var(--color-light-graphic-primary)" />
            <span className="nav__label">Радар</span>
            <span className="count count--red">3</span>
          </button>
          {isAdmin && (
            <button className={`nav__item${active === 'sources' ? ' nav__item--active' : ''}`} onClick={() => navigate('/sources')}>
              <DocumentsLinesMIcon width={24} height={24} color="var(--color-light-graphic-primary)" />
              <span className="nav__label">Источники</span>
            </button>
          )}
          {SERVICES.map(({ label, Icon }) => (
            <button key={label} className="nav__item" onClick={() => {}}>
              <Icon width={24} height={24} color="var(--color-light-graphic-primary)" />
              <span className="nav__label">{label}</span>
            </button>
          ))}
        </nav>

        <div style={{ flex: '1 1 auto', minHeight: 24 }} />

        <div className="shell__bottom">
          <a href="#/home" onClick={(e) => e.preventDefault()}>
            <ShoppingCartMIcon width={24} height={24} color="var(--color-light-graphic-link)" />
            <span style={{ fontSize: 13, lineHeight: '16px', fontWeight: 500 }}>Все сервисы и продукты</span>
          </a>
          <a href="#/home" onClick={(e) => e.preventDefault()}>
            <AScoresCircleMIcon width={24} height={24} color="var(--radar-prov-ai-text)" />
            <span style={{ fontSize: 13, lineHeight: '16px', fontWeight: 500 }}>Кэшбэк и привилегии</span>
          </a>
        </div>
      </aside>

      <div className="shell__main">
        <header className="topbar">
          <span className="pill"><span className="pill__dot" />Тестовый стенд</span>
          <span style={{ flex: '1 1 auto' }} />
          <button className="ib" style={{ width: 'auto', padding: '0 12px', gap: 8, color: 'var(--color-light-text-secondary)', fontSize: 14, fontWeight: 500 }}>
            <MagnifierMIcon width={20} height={20} color="var(--color-light-graphic-primary)" />Поиск
          </button>
          <button className="ib" aria-label="Календарь"><CalendarMIcon width={24} height={24} /></button>
          <button className="ib" aria-label="Письма"><MailMIcon width={24} height={24} /></button>
          <button className="ib" aria-label="Уведомления"><BellMIcon width={24} height={24} /><span className="ib__dot" /></button>
          <span style={{ width: 1, height: 24, background: 'var(--color-light-border-primary)', margin: '0 8px' }} />
          <CompanySwitcher companyId={companyId} onSelect={setCompany} />
          <button className="ib" aria-label="Настройки компании"><BriefcaseGearMIcon width={24} height={24} /></button>
          <UserMenu username={me?.username ?? ''} onSignOut={() => { signOut(); navigate('/login'); }} />
          <button className="ib" aria-label="Боковая панель"><SquareRectangleRightCompactMIcon width={24} height={24} /></button>
        </header>

        <main className="content">{children}</main>
      </div>

      <button className="chat-fab" aria-label="Чат с банком"><BubbleLinesMIcon width={28} height={28} /></button>
    </div>
  );
}

function useOutsideClose(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [onClose]);
  return ref;
}

function CompanySwitcher({ companyId, onSelect }: { companyId: string | null; onSelect: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const { data } = useCompanies();
  const { navigate } = useRouter();
  const ref = useOutsideClose(() => setOpen(false));
  const current = data?.find((c) => c.id === companyId);

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button className="company-switch" onClick={() => setOpen((v) => !v)} aria-haspopup="menu" aria-expanded={open} aria-label="Сменить компанию">
        <span style={{ display: 'flex', flexDirection: 'column' }}>
          <span className="company-switch__name">{current ? `${companyIdLabel(current.id)} · ${current.industry ?? ''}` : 'Выберите компанию'}</span>
          <span className="company-switch__sub">Компания для анализа</span>
        </span>
        <ChevronDownMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" />
      </button>
      {open && (
        <div className="menu" role="menu">
          {(data ?? []).map((c) => (
            <button
              key={c.id}
              className={`menu__item${c.id === companyId ? ' menu__item--active' : ''}`}
              role="menuitemradio"
              aria-checked={c.id === companyId}
              onClick={() => { onSelect(c.id); setOpen(false); }}
            >
              <span style={{ flex: '1 1 auto' }}>{companyIdLabel(c.id)} · {c.industry}</span>
              {c.id === companyId && <CheckmarkCircleMIcon width={18} height={18} color="var(--color-light-graphic-positive)" />}
            </button>
          ))}
          <button className="menu__item" onClick={() => { setOpen(false); navigate('/companies'); }} style={{ color: 'var(--color-light-text-link)' }}>
            Все мои компании →
          </button>
        </div>
      )}
    </div>
  );
}

function UserMenu({ username, onSignOut }: { username: string; onSignOut: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(() => setOpen(false));
  const initials = username.slice(0, 1).toUpperCase() || 'А';
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button className="ib" style={{ width: 36, height: 36 }} aria-label="Профиль" onClick={() => setOpen((v) => !v)}>
        <span style={{ width: 32, height: 32, borderRadius: '50%', background: 'var(--color-light-neutral-200)', display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600 }}>{initials}</span>
      </button>
      {open && (
        <div className="menu" role="menu" style={{ minWidth: 220 }}>
          <div style={{ padding: '8px 12px', fontSize: 13, color: 'var(--color-light-text-secondary)' }}>{username}</div>
          <button className="menu__item" onClick={onSignOut}>
            <LogOutMIcon width={18} height={18} color="var(--color-light-graphic-primary)" />
            Выйти
          </button>
        </div>
      )}
    </div>
  );
}
