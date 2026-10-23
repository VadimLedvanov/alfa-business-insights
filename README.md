# alfa-business-insights
Персонализированная новостная аналитика для крупного бизнеса — прототип команды Альфа Хак.

Для запуска нужны Docker и Docker Compose. Из корня проекта:

```bash
docker compose -f infrastructure/docker-compose.yaml up -d --build
```

Бэкенд доступен по адресу [http://localhost:8080](http://localhost:8080). Страницы входа и пользовательского интерфейса пока нет: без JWT-токена бэкенд возвращает `401 Unauthorized`.

## Keycloak

Админка Keycloak: [http://localhost:8081/admin/](http://localhost:8081/admin/).

- Логин: `admin`
- Пароль: `admin-dev`

Тестовые учётные записи приложения:

| Роль | Логин | Пароль |
| --- | --- | --- |
| Системный администратор (`SYSTEM_ADMIN`) | `sysadmin` | `sysadmin-dev` |
| Директор компании (`COMPANY_DIRECTOR`) | `director` | `director-dev` |

Вход под тестовыми учётными записями пока недоступен: страницы входа и пользовательского контента приложения ещё нет.
