# Матрица проверок

Ожидаемые результаты основаны на `doc/visionandscope.md`,
`ml/ml-document-contract.md` и публичных API-контрактах.
Подробные шаги каждого сценария — в [каталоге](catalog.md) и Allure.

| Область | Уровни | Что проверяется |
|---|---|---|
| CSV | Unit, integration, smoke | Разбор трёх CSV, границы чисел, повторный импорт, откат при ошибке, доступность импортированного профиля |
| Контекст компании | Unit, integration, E2E, smoke | Профиль, группа, оборот, доли контрагентов, отсутствующие данные, право доступа |
| Авторизация | Integration, smoke | 401 без JWT, роли директора и администратора, запрет загрузки директору |
| PDF | Unit, integration, E2E, smoke | Валидация, загрузка, метаданные, байты оригинала, Qdrant, очистка при ошибке, конкурентная запись одного и разных UUID |
| Границы | Unit, integration | PDF: 36/50/51 МиБ и ±1 байт у лимита; счётчик платежей 0/1/BIGINT max/max+1; суммы 0/0.01/отрицательная; длина вектора и минимальная страница/ordinal |
| ML-контракт | Unit, integration | Типы и размерность embedding, некорректные ответы; локальный HTTP-ответчик |
| События и инсайты | Integration, unit | Создание событий, роли, версии материалов, кэш, конкуренция, проверка ссылок и ошибок LLM |
| Настоящий Python ML | Integration (Java и pytest) | Страницы PDF, числовые факты ЦБ/ЕЦБ, оригинал и Qdrant; пустые, повреждённые и смешанные PDF, UUID и лимит размера |
| Схема | Integration | Миграции, обязательные поля и ограничения целостности |
| Диагностика | Unit | Сокрытие секретов во вложениях запросов и ответов |

Извлечение PDF проверяется настоящим Python-парсером, embeddings остаются
синтетическими. Качество семантического поиска и генерации внешней LLM, а также frontend
в этот прогон не входят. Известные регрессии [QA-001 и QA-002](findings.md)
включены в полный запуск и не подавляются.

## Issue #25 — инсайты по контексту выбранной компании

| Приоритет | Проверка | Покрытие / статус |
|---|---|---|
| P0 | Контекст выбранной компании передаётся отдельно для каждой из трёх компаний одной группы; их записи и кэш изолированы | `TestIntegrationInsightReceivesSelectedCompanyContextAndIsolatesCache` — PASS. Проверяет доставку контекста и изоляцию, не семантическую правильность ранжирования. |
| P0 | Выдача отдельных рангов и причин для каждой компании | **Blocked:** в текущем backend отсутствуют matcher, DTO и API для ранжирования с причинами. Новый integration test не может подтвердить этот контракт. |
| P1 | Неподтверждённый факт преобразуется в вывод с `confidence=low` и пустым `based_on` | `TestUnitDemotesUnreferencedFactToLowConfidenceInference` — FAIL, воспроизведено: текст удалён, в `ai_inference` не добавлен; см. QA-004. |
| P1 | Полный путь от вопроса пользователя до живого ответа чата | **Blocked:** нет chat controller/service и frontend-вызова. `TestSmokeConsumerInsightHttpJourney` — PASS для существующего HTTP пути инсайта с синтетическими ответами; пользовательский чат не проверяется. |
| P1 | `bank_view` принимается только по реальному банковскому материалу | Усиленный `TestUnitFilterUnsupportedFactsAndBankView` — PASS: обычный EVENT_TEXT и BANK_ANALYTICS stub отклоняются, настоящий BANK_ANALYTICS сохраняется. |
| P2 | Повторный POST/GET использует кэш, новая версия материалов создаёт новую запись, параллельные POST разделяют генерацию | Уже покрыто `TestIntegrationReuseCachedInsight`, `TestIntegrationRegenerateInsightForNewMaterials`, `TestIntegrationShareConcurrentInsightGeneration`. |
| P2 | Сбой LLM, ML или Qdrant не сохраняет неполный инсайт и допускает повтор; права директора/администратора проверяются | Уже покрыто `TestIntegrationRetryInsightAfterLlmFailure`, `TestIntegrationRetryInsightAfterMlFailure`, `TestIntegrationRetryInsightAfterQdrantFailure`, `TestIntegrationEnforceCompanyInsightAccess`, `TestIntegrationAllowAdminInsightGeneration`. Чтение контекста компании отдельно имеет QA-001. |

Внешний chat provider и качество генерации этими тестами не оцениваются. Отсутствие chat API — отдельный продуктовый блокер, а не следствие отключённой LLM в тестовой конфигурации.

**Результат issue #25:** focused-запуск — 7 тестов, 6 passed, 1 failed (QA-004). В полном прогоне на `26ac72a` новые проверки контекста компании и HTTP smoke прошли; mixed-source `bank_view` прошёл.
