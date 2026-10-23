# alfa-business-insights
Персонализированная новостная аналитика для крупного бизнеса — прототип команды Альфа Хак.

Для запуска нужны Docker и Docker Compose. Из корня проекта:

```bash
docker compose -f infrastructure/docker-compose.yaml up -d --build
```

После запуска API можно проверить через Swagger UI.

## OpenAPI и Swagger UI

[Swagger UI](http://localhost:8080/api/swagger-ui.html)

| Роль | Логин | Пароль |
| --- | --- | --- |
| Системный администратор | `sysadmin` | `sysadmin-dev` |
| Директор компании | `director` | `director-dev` |

## Админка Keycloak

[Админка Keycloak](http://localhost:8081/admin/)

- Логин: `admin`
- Пароль: `admin-dev`

## Qdrant

[Панель Qdrant](http://localhost:6333/dashboard)
