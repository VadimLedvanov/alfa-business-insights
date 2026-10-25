import { useState, type FormEvent } from 'react';
import { AlfaLogo } from '../ui/Logo';
import { useAuth } from '../auth/AuthContext';
import './LoginPage.css';

export function LoginPage() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError(null);
    try { await signIn(username.trim(), password); setPassword(''); }
    catch (e) { setError(e instanceof Error ? e.message : 'Не удалось войти.'); }
    finally { setBusy(false); }
  };
  return <div className="login"><section className="login__story"><AlfaLogo size={48} /><span className="eyebrow">ALFA BUSINESS INSIGHTS</span><h1>События обретают смысл.</h1><p>Когда знаешь, как они связаны с твоим бизнесом.</p><div className="login__radar" aria-hidden="true"><span /><span /><span /></div></section><section className="login__card"><div className="eyebrow">ВАШ БИЗНЕС В КОНТЕКСТЕ</div><h2>Войти в Радар</h2><p className="login__lead">Новости, аналитика и решения для вашей компании.</p><form onSubmit={submit} aria-busy={busy}>{error && <div className="error-message" role="alert">{error}</div>}<label className="field"><span>Логин</span><input autoComplete="username" name="username" required autoFocus value={username} onChange={(e) => setUsername(e.target.value)} disabled={busy} /></label><label className="field"><span>Пароль</span><input autoComplete="current-password" name="password" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} disabled={busy} /></label><button className="button button--primary login__submit" disabled={busy} type="submit">{busy ? 'Входим…' : 'Войти →'}</button></form><p className="login__foot">Используйте учётную запись вашей организации.</p></section></div>;
}
