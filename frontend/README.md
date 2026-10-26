# Frontend — Alfa Business Insights

Интерфейс «Радара» показывает события в контексте компании и их контрагентов. Администратор может загрузить PDF аналитики банка, просмотреть оригинал и скачать его.

## Стек

- React 18, TypeScript, Vite
- `@alfalab/core-components` и `@alfalab/icons-glyph`

## Запуск

```bash
npm ci
npm run dev       # Vite на :3000; /api проксируется в Spring :8080, /auth — через nginx :80 в Keycloak
npm run build
npm run preview
```

Docker собирает приложение в статические файлы и отдаёт их через nginx. Внешний nginx направляет `/api` в Spring, `/auth` в Keycloak.

## Вход и данные

Вход выполняется по логину и паролю через Keycloak. Access token хранится в `sessionStorage` и добавляется к запросам `/api` как Bearer. Данные загружаются только из Java Spring API.

Frontend использует `/auth/me`, `/companies`, `/companies/{id}`, `/events`, `/events/{id}`, `/companies/{id}/events/{eventId}/insight`, `/admin/documents`, `/documents/{id}` и `/documents/{id}/file`. Администратор загружает PDF через `POST /admin/documents`. Список PDF использует пагинацию; оригинал открывается и скачивается авторизованным запросом.

Java формирует разбор для текстовых событий. Для событий со ссылкой или PDF текст пока не извлекается, поэтому интерфейс сообщает об этом и не предлагает запуск анализа.

## Структура

- `src/api` — DTO и клиент Java API.
- `src/auth` — вход Keycloak и управление сессией.
- `src/components` — оболочка и общие карточки.
- `src/pages` — вход, выбор компании, лента событий, разбор и администрирование PDF.
- `src/lib` — форматирование данных и хуки загрузки.
