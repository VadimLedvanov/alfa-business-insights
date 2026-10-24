import { useEffect } from 'react';
import { useAuth } from './auth/AuthContext';
import { useCompany } from './app/company';
import { useRouter } from './app/router';
import { LoginPage } from './pages/LoginPage';
import { CompanySelectPage } from './pages/CompanySelectPage';
import { HomePage } from './pages/HomePage';
import { FeedPage } from './pages/FeedPage';
import { InsightPage } from './pages/InsightPage';
import { SourcesPage } from './pages/SourcesPage';

export function App() {
  const { me } = useAuth();
  const { companyId } = useCompany();
  const { path, navigate } = useRouter();

  const seg = path.split('/').filter(Boolean);
  const route = seg[0] ?? '';

  // Защита маршрутов: без входа — на вход; без выбранной компании — на выбор компании.
  useEffect(() => {
    if (!me && route !== 'login') {
      navigate('/login');
      return;
    }
    if (me && (route === '' || route === 'login')) {
      navigate(companyId ? '/home' : '/companies');
      return;
    }
    if (me && !companyId && (route === 'home' || route === 'feed' || route === 'insight')) {
      navigate('/companies');
    }
  }, [me, companyId, route, navigate]);

  if (!me) return <LoginPage />;

  switch (route) {
    case 'companies':
      return <CompanySelectPage />;
    case 'home':
      return companyId ? <HomePage /> : <CompanySelectPage />;
    case 'feed':
      return companyId ? <FeedPage /> : <CompanySelectPage />;
    case 'insight':
      return companyId && seg[1] ? <InsightPage eventId={seg[1]} /> : <CompanySelectPage />;
    case 'sources':
      return <SourcesPage />;
    default:
      return <CompanySelectPage />;
  }
}
