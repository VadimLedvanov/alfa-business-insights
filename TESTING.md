# Тесты backend

Тесты находятся в [`backend/tests/`](backend/tests/README.md).
Требуются Java 21, Maven и Docker; unit-тестам Docker не нужен.
Python ML проверяется через pytest и настоящий PDF-парсер; embeddings локальные `stub-v1`.
Frontend пока не покрыт. Внешняя нейросеть не вызывается.

```bash
# Backend: два параллельных JVM-воркера; покрытие строк не ниже 70%
mvn -B -ntp -f backend/tests/pom.xml clean verify -Plocal-report

# Отдельные наборы
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=UnitSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=IntegrationSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=E2eSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=SmokeSuite

# Фокусная проверка инсайтов по issue #25; HTML и Allure results — в backend/tests/target
mvn -B -ntp -f backend/tests/pom.xml test -Plocal-report -Dtest=InsightValidatorTest,CompanyInsightContextIntegrationTest,ConsumerInsightHttpSmokeTest

# Python ML: Python 3.11+ и отдельное виртуальное окружение
python3 -m venv .venv
# macOS/Linux: source .venv/bin/activate; Windows: .venv\Scripts\activate
python -m pip install -r ml/requirements.txt -r ml/tests/requirements.txt
python -m pytest -c ml/pytest.ini ml/tests --alluredir=backend/tests/target/allure-results

# Открыть общий отчёт Java + Python в браузере
mvn -B -ntp -f backend/tests/pom.xml allure:serve
```

`allure:serve` запускает локальный сервер, печатает ссылку
`http://127.0.0.1:8767/` и открывает отчёт в браузере. Сервер работает, пока
команда запущена; для остановки нажми `Ctrl+C`. После упавшего прогона команду
открытия отчёта выполняй отдельно. Если порт занят, добавь к `allure:serve`
параметр `-Dallure.serve.port=8768`.


Backend запускает независимые классы в двух JVM, pytest — в двух воркерах.
Методы с общей БД внутри класса последовательны. Явный запуск JUnit `*Suite`
работает в одной JVM; для параллельного полного прогона используй команду без `-Dtest`.
Java и pytest можно запускать одновременно после завершения Maven `clean test-compile`.
После добавления Python-результатов не запускай `clean` перед открытием общего отчёта.
Первое скачивание зависимостей и образов в бюджет повторного прогона не входит.

Профиль `local-report` автоматически формирует HTML после выполнения тестов
и возвращает ненулевой код при падениях или пропусках тестов. Если ошибка возникла до запуска
тестов (например, не компилируется код), сначала исправь причину из вывода Maven.

В текущей локальной настройке VS Code JUnit-кнопки запуска используют связанное
представление из `.vscode/java-qa`, а `.vscode/local-qa.code-workspace` открывает
его вместе с полным корнем репозитория. Эти workspace и JUnit-настройки принадлежат
пользователю и отсутствуют в чистом клоне. На другой машине открой корень репозитория
обычным способом и запускай тесты переносимыми Maven-командами выше. Прямые JUnit
кнопки относятся только к локальному отображению тестов и не заменяют Maven-команду.

| Набор | Назначение |
|---|---|
| `unit/` | Расчёты, валидация, обработка ошибок с изолированными зависимостями |
| `integration/` | БД, миграции, Spring MVC, файлы и контракт Feign |
| `e2e/` | Пользовательские сценарии через настоящий HTTP backend |
| `smoke/` | Авторизация, контекст компании, импорт и загрузка PDF |

- [Структура, теги и раннеры](backend/tests/README.md).
- [Тест-планы: шаги и ожидаемые результаты](backend/tests/reports/catalog.md).
- [Матрица проверок](backend/tests/reports/plan.md).
- [Известные дефекты](backend/tests/reports/findings.md): регрессии остаются включёнными.
