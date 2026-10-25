import type { ReactNode } from 'react';
import { AlfaLogo } from '../ui/Logo';
import { RadarMIcon, UsersMIcon, DocumentMIcon, LogOutMIcon } from '../ui/icons';
import { useAuth } from '../auth/AuthContext';
import { useRouter } from '../app/router';
import { useCompany } from '../app/company';
import { companyIdLabel } from '../lib/format';
import './AppShell.css';

export function AppShell({ active, children }: { active: 'radar' | 'companies' | 'sources' | null; children: ReactNode }) {
  const { me, isAdmin, signOut } = useAuth();
  const { navigate } = useRouter();
  const { companyId, setCompany } = useCompany();
  const items = [
    { key: 'radar', title: 'Радар', path: companyId ? '/feed' : '/companies', Icon: RadarMIcon },
    { key: 'companies', title: 'Мои компании', path: '/companies', Icon: UsersMIcon },
    ...(isAdmin ? [{ key: 'sources', title: 'Источники', path: '/sources', Icon: DocumentMIcon }] : []),
  ];
  return <div className="shell"><aside className="shell__side"><button className="shell__brand" onClick={() => navigate(companyId ? '/feed' : '/companies')}><AlfaLogo size={42} /><span>Альфа<span>Бизнес</span></span></button><div className="shell__label">БИЗНЕС-АНАЛИТИКА</div><nav className="shell__nav" aria-label="Основное меню">{items.map(({ key, title, path, Icon }) => <button key={key} className={`shell__nav-item${active === key ? ' shell__nav-item--active' : ''}`} aria-current={active === key ? 'page' : undefined} onClick={() => navigate(path)}><Icon width={22} height={22} /><span>{title}</span></button>)}</nav><div className="shell__note"><span>✦</span><strong>События обретают смысл</strong><p>Когда знаешь, как они связаны с твоим бизнесом.</p></div><div className="shell__user"><span className="shell__avatar">{me?.username.slice(0, 2).toUpperCase()}</span><div><strong>{me?.username}</strong><small>{isAdmin ? 'Администратор' : 'Руководитель компании'}</small></div><button className="shell__logout" aria-label="Выйти" onClick={() => { setCompany(null); signOut(); }}><LogOutMIcon width={20} height={20} /></button></div></aside><div className="shell__body"><header className="shell__top"><span className="shell__breadcrumb">Бизнес-аналитика <span>/</span> <b>{active === 'sources' ? 'Источники' : active === 'companies' ? 'Мои компании' : 'Радар'}</b></span>{companyId && <button className="shell__company" onClick={() => navigate('/companies')}>{companyIdLabel(companyId)} <span>⌄</span></button>}</header><main className="shell__main">{children}</main><footer className="shell__footer"><span>Альфа Бизнес · Радар событий</span><span>Новости и аналитика в контексте вашей компании</span></footer></div></div>;
}
