const TOKEN_URL = '/auth/realms/alfa-business-insights/protocol/openid-connect/token';
const KEY = 'abi.kc.token';
let accessToken: string | null = null;
try { accessToken = sessionStorage.getItem(KEY); } catch { /* Storage may be disabled. */ }

function store(token: string | null) {
  accessToken = token;
  try {
    if (token) sessionStorage.setItem(KEY, token);
    else sessionStorage.removeItem(KEY);
  } catch { /* Keep in-memory authentication available. */ }
}

export async function kcLogin(username: string, password: string): Promise<void> {
  store(null);
  const response = await fetch(TOKEN_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'alfa-backend', username, password }),
  });
  if (!response.ok) throw new Error(response.status === 400 || response.status === 401
    ? 'Неверный логин или пароль.' : 'Сервис входа недоступен. Попробуйте позже.');
  const data = await response.json() as { access_token?: string };
  if (!data.access_token) throw new Error('Сервис входа не вернул токен.');
  store(data.access_token);
}

export function kcLogout() { store(null); }
export function hasToken() { return !!accessToken; }
export function authHeader(): Record<string, string> { return accessToken ? { Authorization: `Bearer ${accessToken}` } : {}; }
