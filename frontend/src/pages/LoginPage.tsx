import { useState } from 'react';
import { Button } from '@alfalab/core-components/button';
import { AlfaLogo } from '../ui/Logo';
import { UsersMIcon, BriefcaseGearMIcon, ArrowRightMIcon } from '../ui/icons';
import { useAuth } from '../auth/AuthContext';
import { useRouter } from '../app/router';
import './LoginPage.css';

const ROLES = [
  {
    key: 'director',
    title: 'Андрей, руководитель казначейства',
    sub: 'Видит Радар, читает разбор событий, задаёт уточняющие вопросы.',
    Icon: UsersMIcon,
  },
  {
    key: 'sysadmin',
    title: 'Администратор материалов',
    sub: 'Добавляет новости и аналитику, следит за обработкой источников.',
    Icon: BriefcaseGearMIcon,
  },
];

export function LoginPage() {
  const { signIn } = useAuth();
  const { navigate } = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const pick = async (key: string) => {
    setBusy(key);
    setError(null);
    try {
      await signIn(key);
      navigate(key === 'sysadmin' ? '/sources' : '/companies');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось войти. Попробуйте ещё раз.');
      setBusy(null);
    }
  };

  return (
    <div className="login">
      <div className="login__card">
        <div className="login__head">
          <AlfaLogo size={44} />
          <div>
            <div className="login__brand">Альфа-Бизнес</div>
            <div className="login__product">Alfa Business Insights · Радар</div>
          </div>
        </div>

        <span className="login__stand"><span className="login__stand-dot" />Тестовый стенд</span>

        <h1 className="login__title">Выберите тестового пользователя</h1>
        <p className="login__lead">
          Это демонстрационный стенд для хакатона: данные обезличены, вход без логина и пароля.
          Пользователи заведены в Keycloak, в бою вход идёт через него.
        </p>

        {error && <div className="login__error" role="alert">{error}</div>}

        <div className="login__roles">
          {ROLES.map(({ key, title, sub, Icon }) => (
            <button key={key} className="role" onClick={() => pick(key)} disabled={!!busy} aria-busy={busy === key}>
              <span className="role__icon"><Icon width={24} height={24} color="var(--color-light-graphic-primary)" /></span>
              <span className="role__text">
                <span className="role__title">{title}</span>
                <span className="role__sub">{busy === key ? 'Входим…' : sub}</span>
              </span>
              <ArrowRightMIcon width={20} height={20} color="var(--color-light-graphic-secondary)" />
            </button>
          ))}
        </div>

        <div className="login__foot">
          <Button view="text" size={40} onClick={() => pick('director')} loading={busy === 'director'} disabled={!!busy}>
            Войти как Андрей
          </Button>
        </div>
      </div>
    </div>
  );
}
