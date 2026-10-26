import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './theme.css';
import './components/ui.css';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { CompanyProvider } from './app/company';
import { RouterProvider } from './app/router';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <CompanyProvider>
        <RouterProvider>
          <App />
        </RouterProvider>
      </CompanyProvider>
    </AuthProvider>
  </StrictMode>,
);
