# alfa-business-insights
Персонализированная новостная аналитика для крупного бизнеса — прототип команды Альфа Хак.

Для запуска нужны Docker и Docker Compose. Из корня проекта:

```bash
docker compose -f infrastructure/docker-compose.yaml up -d --build
```

После запуска открыть [http://localhost:8080](http://localhost:8080) в браузере.
Если появилась страница входа Spring Security - бэкенд работает.
