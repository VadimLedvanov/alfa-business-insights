# alfa-business-insights
Персонализированная новостная аналитика для крупного бизнеса — прототип команды Альфа Хак.

Для запуска нужны Docker и Docker Compose. Из корня проекта:

```bash
docker compose -f infrastructure/docker-compose.yaml up -d --build
```

Бэкенд доступен по адресу [http://localhost:8080](http://localhost:8080). Страницы входа и пользовательского интерфейса пока нет: API без JWT-токена возвращает `401 Unauthorized`.

## OpenAPI и Swagger UI

[Swagger UI](http://localhost:8080/swagger-ui.html)

Документация доступна без авторизации и автоматически строится по контроллерам.
Каждый авторизованный вызов API должен сопровождаться JWT-токеном, полученным непосредственно от Keycloak. Токен передаётся в заголовке `Authorization: Bearer <JWT>`.
В Swagger UI нажмите **Authorize**, затем **Authorize** в открывшемся окне и войдите на странице Keycloak под тестовой учётной записью из таблицы ниже. Swagger автоматически получит токен и будет подставлять его в запросы.
Для смены учётной записи нажмите **Authorize → Logout → Authorize**. Keycloak снова покажет форму входа. Если указан предыдущий пользователь, нажмите **Restart login** (кнопка рядом с логином), затем введите логин и пароль другой учётной записи.

## Keycloak

Админка Keycloak: [http://localhost:8081/admin/](http://localhost:8081/admin/).

- Логин: `admin`
- Пароль: `admin-dev`

Тестовые учётные записи приложения:

| Роль | Логин | Пароль |
| --- | --- | --- |
| Системный администратор (`SYSTEM_ADMIN`) | `sysadmin` | `sysadmin-dev` |
| Директор компании (`COMPANY_DIRECTOR`) | `director` | `director-dev` |

Тестовые учётные записи доступны для входа через Swagger UI.
