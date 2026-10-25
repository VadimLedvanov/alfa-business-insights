// Вход в Keycloak для тестового стенда (Resource Owner Password Credentials).
// Клиент alfa-backend публичный, прямой доступ включён в realm. Тестовые пользователи
// и их пароли — сид-значения из репозитория (infrastructure/keycloak/*-realm.json, README),
// поэтому кнопки «Андрей / Администратор» входят без ручного ввода пароля (как в ТЗ).
// Используется только в live-режиме; в stub-режиме Keycloak не нужен.

const TOKEN_URL = '/auth/realms/alfa-business-insights/protocol/openid-connect/token';
const CLIENT_ID = 'alfa-backend';

// Сид-пароли тестовых пользователей (dev-стенд). Соответствуют realm-экспорту.
const TEST_CREDENTIALS: Record<string, { username: string; password: string }> = {
  director: { username: 'director', password: 'director-dev' },
  sysadmin: { username: 'sysadmin', password: 'sysadmin-dev' },
};

const KEY = 'abi.kc.token';
let accessToken: string | null = read();

function read(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}
function store(token: string | null) {
  accessToken = token;
  try {
    if (token) sessionStorage.setItem(KEY, token);
    else sessionStorage.removeItem(KEY);
  } catch {
    /* приватный режим */
  }
}

/** Получить токен тестового пользователя по ключу роли (director | sysadmin). */
export async function kcLogin(userKey: string): Promise<void> {
  const cred = TEST_CREDENTIALS[userKey];
  if (!cred) throw new Error(`Неизвестный тестовый пользователь: ${userKey}`);
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: CLIENT_ID,
    username: cred.username,
    password: cred.password,
  });
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) throw new Error(`Keycloak вернул ${res.status}. Проверьте, что стенд запущен.`);
  const data = (await res.json()) as { access_token?: string };
  if (!data.access_token) throw new Error('Keycloak не вернул токен.');
  store(data.access_token);
}

export function kcLogout() {
  store(null);
}

/** Заголовок авторизации для запросов к /api (пусто, если токена нет). */
export function authHeader(): Record<string, string> {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}
