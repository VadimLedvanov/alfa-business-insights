# Тесты backend

JUnit Jupiter, REST Assured, Mockito, Allure и JaCoCo.
Проверяются импорт CSV, контекст компании, события, инсайты, доступ по ролям и PDF.

## Требования и запуск

Нужны Java 21, Maven и запущенный Docker engine. Testcontainers поднимает
изолированные PostgreSQL, Keycloak и Qdrant на свободных портах; рабочая БД не используется.
Первый запуск скачивает Maven-зависимости и контейнерные образы.
Только unit-набор работает без Docker.

Проверь окружение перед первым запуском:

```bash
java -version
mvn -version
docker info
```

В выводе Maven должна быть Java 21. Docker должен работать в режиме Linux-контейнеров.
Для unit-проверок достаточно первых двух команд. Системный Allure CLI устанавливать
не нужно: Maven загружает нужную версию в локальный игнорируемый каталог `.allure/`.
Не нужны настроенная рабочая БД, токены приложения или настоящий AI endpoint.

Из корня репозитория (одинаковые Maven-команды на Windows, macOS и Linux):

```bash
mvn -B -ntp -f backend/tests/pom.xml clean verify -Plocal-report
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=UnitSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=IntegrationSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=E2eSuite
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dtest=SmokeSuite
```

Выборка по тегам и классам:

```bash
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report -Dgroups=unit
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report '-Dgroups=integration & documents'
mvn -B -ntp -f backend/tests/pom.xml clean test -Plocal-report '-Dtest=Document*Test'
```

Пустая выборка считается ошибкой. `clean` удаляет результаты предыдущего запуска.
Полный `verify` проверяет общее покрытие строк production-кода не ниже 70%;
покрытие отдельного набора не заменяет полный прогон.
Версии production-зависимостей в этом POM следует обновлять вместе с `../core/pom.xml`.

## Запуск кнопкой в IDE

Импортируй `backend/tests/pom.xml` как Maven-проект, выбери JDK 21 и рабочую
директорию `backend/tests`. Нажми Run рядом с классом в `suites/` или отдельным
тестовым методом. Allure-вложения сохраняются и при запуске из IDE.

| Раннер | Тег / назначение |
|---|---|
| `AllSuite` | Все тесты |
| `UnitSuite` | `unit`: изолированная логика |
| `IntegrationSuite` | `integration`: БД, MVC, файлы, Feign |
| `E2eSuite` | `e2e`, кроме `smoke`: функциональные HTTP-сценарии |
| `SmokeSuite` | `smoke`: критические HTTP-пути |
| `CompanyContextSuite` | `company-context`: профиль, группа, платежи, доступ |
| `DocumentsSuite` | `documents`: загрузка, хранение, ML-контракт |
| `DataImportSuite` | `data-import`: CSV и повторный импорт |
| `SecuritySuite` | `security`: JWT и роли |
| `SchemaSuite` | `schema`: миграции и ограничения данных |
| `DiagnosticsSuite` | `diagnostics`: сокрытие секретов в отчётах |

Отдельный функциональный сьют создаётся только для самостоятельного контура кода.
Unit и Smoke — общие наборы; отдельные сценарии и проверки гонки добавляются
в существующий контур, для узкого запуска используются теги, например `concurrency`.

Классы `*Suite` выбираются явно; обычный Maven-прогон запускает `*Test`
напрямую, без повторного выполнения через раннеры.

## Структура и границы

```text
unit/<функциональность>/         Изолированная логика; Mockito для зависимостей
integration/<функциональность>/  БД, Spring MVC, файлы и loopback-контракт Feign
e2e/<функциональность>/          HTTP backend с PostgreSQL, Keycloak и Qdrant
smoke/<функциональность>/        Критические сценарии с настоящими сервисами
suites/                         JUnit-раннеры
connectors/                     REST Assured, Keycloak и диагностика запросов
helpers/                        Фикстуры и переиспользуемые проверки
support/                        Окружение и расширения отчётности
resources/                      Конфигурация, CSV, названия и планы тестов
reports/                        Каталог тест-планов, матрица и дефекты
```

Сетевые запросы выполняются через REST Assured, MVC-проверки — через REST Assured
MockMvc без сетевого соединения. Feign-контракт проверяет production-клиент
с локальным HTTP-ответчиком. Mockito применяется к зависимостям unit-тестов.

Основные PDF-сценарии используют реализованный `ml.stub=true`: `stub-v1`, размерность 8,
метка `[ML STUB]`. Они проверяют загрузку, сохранение и передачу данных,
а не качество анализа PDF. `RealMlDocumentIntegrationTest` собирает настоящий Python-сервис
и проверяет извлечение текста, физические страницы и сохранение оригинала, сохраняя
локальные embeddings `stub-v1`. Фикстуры ЦБ и ЕЦБ лежат в `ml/tests/fixtures/pdf`;
источники и хеши указаны рядом. Внешний AI endpoint не вызывается.

Полный Maven-прогон выполняет независимые классы в двух JVM (`-Dqa.forks=1` для
последовательной диагностики). Методы одного класса последовательны. Раннер `*Suite`
сам по себе не распределяет вложенные классы между JVM. JaCoCo объединяет покрытие обоих воркеров.

## Стиль тестов

Во всех Java-наборах: отступ 4 пробела, явные импорты, короткий русский комментарий
над методом и разделение подготовки, действия и проверок пустыми строками.
Сложные сценарии отмечают эти этапы комментариями; бизнес-шаги Allure содержат
свои запросы и assertions. HTTP проверяется native REST Assured DSL
`given/when/then`, состояние БД и чистые значения — AssertJ.
Unit-проверки используют JUnit/Mockito для изолированной логики.

Spotless проверяет единый формат Java при Maven-запуске и затрагивает только
тестовый код. Применить формат перед запуском:

```bash
mvn -B -ntp -f backend/tests/pom.xml spotless:apply
mvn -B -ntp -f backend/tests/pom.xml spotless:check
```

## Allure и покрытие

Профиль `-Plocal-report` формирует HTML автоматически при `test` и `verify`,
включая прогоны с упавшими проверками. После генерации он проверяет JUnit XML
и завершает Maven с ошибкой, если есть failures/errors, пропущенные тесты или нет результатов.
Не используй `-Dmaven.test.failure.ignore=true` для получения зелёного статуса.
В Allure добавляются версия Java, ОС и архитектура без персональных путей.

Без этого профиля сохраняется обычное поведение Maven: ошибка теста немедленно
останавливает сборку, отчёт можно сформировать отдельной командой ниже.
Запуск кнопкой в IDE записывает исходные Allure-результаты; для HTML выполни
`allure:serve` после завершения тестов. Ошибка компиляции или запуска JVM может
прервать выполнение до генерации HTML — тогда начинай с журнала Maven.

```bash
# Генерация HTML, также после неуспешного прогона
mvn -B -ntp -f backend/tests/pom.xml jacoco:merge@coverage-merge jacoco:report allure:report
# Локальная ссылка выводится в терминале; отчёт открывается в браузере
mvn -B -ntp -f backend/tests/pom.xml allure:serve
# Отдельная проверка порога после полного упавшего прогона
mvn -B -ntp -f backend/tests/pom.xml jacoco:check@coverage-threshold
```

Allure: `target/site/allure-maven-plugin/`, исходные результаты и вложения:
`target/allure-results/`. JaCoCo: `target/site/jacoco/index.html`.
JUnit XML: `target/surefire-reports/`. Эти каталоги не включаются в Git.

`allure:serve` открывает `http://127.0.0.1:8767/`. Сервер доступен только
на локальной машине; для остановки нажми `Ctrl+C`. Если порт занят, добавь
`-Dallure.serve.port=8768`.

Allure группирует тесты по уровню и функциональности. У каждого сценария есть
название, описание, ожидаемый результат и план. Пронумерованные бизнес-шаги
содержат вложенные HTTP-запросы с параметрами и фактическими ответами;
токены, пароли и cookie скрываются. При падении описание содержит только
исполненные шаги, ожидаемый результат сохраняется из тест-плана.

[Каталог сценариев](reports/catalog.md) · [Матрица](reports/plan.md) ·
[Дефекты](reports/findings.md).

## Если прогон упал

| Где проблема | Что посмотреть / сделать |
|---|---|
| Java / компиляция | `mvn -version`: JDK 21; первая ошибка компилятора в выводе Maven |
| Maven-зависимости или контейнерные образы не скачиваются | Адрес и причина ошибки загрузки, сеть и настройки proxy своей машины |
| `Could not find a valid Docker environment` | `docker info`, запущен ли Docker engine и доступен ли его socket |
| Контейнер не готов, timeout | Первый `Caused by` и журнал контейнера в выводе Maven / `target/surefire-reports/` |
| Проверка вернула неожиданный ответ | Allure → упавший тест → пронумерованный шаг → запрос и фактический ответ |
| Непонятно, какой результат правильный | План и expected в Allure или [каталог](reports/catalog.md); сравни с фактическим ответом |
| `No tests ...` | Имя класса `-Dtest` или выражение `-Dgroups`; пустой набор считается ошибкой |

Для повтора укажи упавший класс: `-Dtest=DocumentStorageConcurrencyTest`;
для метода — `-Dtest=DocumentStorageConcurrencyTest#TestIntegrationConcurrentWritesKeepSingleWinner`.
Не включай автоматические повторы ради зелёного результата.
Известные красные регрессии перечислены в [findings.md](reports/findings.md).
Падение профиля после сообщения `Report generated successfully` означает,
что отчёт доступен, но результат тестов остался неуспешным.


## Размеры PDF

Границы загрузки проверяет `RealMlDocumentIntegrationTest`: 36 МиБ,
50 МиБ − 1 байт, ровно 50 МиБ, 50 МиБ + 1 байт и 51 МиБ.
Новый backend-контракт допускает 50 × 1 048 576 байт; multipart-запросу отведён
51 МиБ с учётом служебных полей. Принятый файл проверяется по размеру и SHA-256,
отказ — по 413, отсутствию побочных эффектов и доступности следующего запроса.

Файлы для ручной загрузки сохраняются в `fixtures/pdf/`; тестовый helper
автоматически воспроизводит их на другой машине из компактного исходника ЦБ.
Большие PDF игнорируются Git; генератор, исходник и контрольные суммы входят в проект.
В Allure бинарные тела больше 2 МиБ представлены размером и SHA-256,
а небольшие исходники приложены полностью. Проверки используют полные байты.

Узкий запуск размеров через настоящий HTTP:

```bash
mvn -B -ntp -f backend/tests/pom.xml test -Plocal-report '-Dtest=RealMlDocumentIntegrationTest#TestIntegrationPdfUploadSizeBoundaries'
```
