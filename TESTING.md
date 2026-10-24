# Тесты backend

Тесты находятся в [`backend/tests/`](backend/tests/README.md).
Требуются Java 21, Maven и Docker; unit-тестам Docker не нужен.
Frontend и ML-сервис пока не покрыты. Внешняя нейросеть не вызывается.

```bash
# Все тесты и проверка общего покрытия строк не ниже 70%
mvn -B -ntp -f backend/tests/pom.xml clean verify -Plocal-report

# Отдельные наборы
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=UnitSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=IntegrationSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=E2eSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=SmokeSuite

# Открыть автоматически сформированный отчёт в браузере
mvn -B -ntp -f backend/tests/pom.xml allure:serve
```

`allure:serve` запускает локальный сервер, печатает ссылку
`http://127.0.0.1:8767/` и открывает отчёт в браузере. Сервер работает, пока
команда запущена; для остановки нажми `Ctrl+C`. После упавшего прогона команду
открытия отчёта выполняй отдельно. Если порт занят, добавь к `allure:serve`
параметр `-Dallure.serve.port=8768`.


Профиль `local-report` автоматически формирует HTML после выполнения тестов
и возвращает ненулевой код при падениях или пропусках тестов. Если ошибка возникла до запуска
тестов (например, не компилируется код), сначала исправь причину из вывода Maven.

Для запуска кнопкой в IDE импортируй `backend/tests/pom.xml` и выбери класс
в `backend/tests/suites/`. Рабочая директория — `backend/tests`, JDK — 21.

| Набор | Назначение |
|---|---|
| `unit/` | Расчёты, валидация, обработка ошибок с изолированными зависимостями |
| `integration/` | БД, миграции, Spring MVC, файлы и контракт Feign |
| `e2e/` | Пользовательские сценарии через настоящий HTTP backend |
| `smoke/` | Авторизация, контекст компании, импорт и загрузка PDF |

- [Структура, теги и раннеры](backend/tests/README.md).
- [Тест-планы: шаги и ожидаемые результаты](backend/tests/reports/catalog.md).
- [Матрица проверок](backend/tests/reports/plan.md).
- [Известные дефекты](backend/tests/reports/findings.md): две регрессии оставлены включёнными.
