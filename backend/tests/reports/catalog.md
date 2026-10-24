# Каталог тестов и проверок

Каталог содержит независимые заголовки и ожидаемые результаты из
`resources/test-plans.properties`; названия совпадают с Allure. В Allure description
и вложении шаги берутся из реально исполненного дерева test body и нумеруются с
учётом вложенности. При падении отображаются только шаги, успевшие исполниться;
ожидаемый результат остаётся из каталога. Параметризованные проверки создают
несколько запусков одного сценария.

## Smoke

### TestSmokeCompanyContextCalculations

Контекст компании — HTTP контекст отражает профиль, группу и платежи PostgreSQL.

`alpha.business.insight.core.qa.company.CompanyContextSmokeTest#TestSmokeCompanyContextCalculations`

Шаги:

1. Создать изолированные записи компании, участника группы и встречных платежей
2. Получить настоящий токен Keycloak и запросить API

**Ожидаемый результат:** Ожидаются профиль, две компании в группе, оборот и доля контрагента равные 500 и 1.

### TestSmokePackagedCsvProfile

Импорт CSV — Профиль из комплектного CSV доступен через реальный API.

`alpha.business.insight.core.qa.dataimport.CsvPackagedSmokeTest#TestSmokePackagedCsvProfile`

Шаги:

1. Получить токен директора Keycloak и временно выдать доступ к U_67
2. Запросить контекст компании по HTTP

**Ожидаемый результат:** Ожидаются status 200, профиль AVAILABLE с ОКВЭД 46.51 и региональными данными из CSV.

### TestSmokePdfUploadRoundTrip

Документы — Реальная загрузка сохраняет PDF, метаданные и stub-чанк.

`alpha.business.insight.core.qa.documents.DocumentUploadSmokeTest#TestSmokePdfUploadRoundTrip`

Шаги:

1. Получить реальные JWT администратора и директора, затем проверить запреты и загрузить валидный PDF администратором
2. Получить метаданные и оригинал через API, сверить байты и прочитать payload/vector в Qdrant

**Ожидаемый результат:** Ожидается сохранённый документ и один чанк; stub=true, stub-v1 и [ML STUB] явно показывают синтетический результат без анализа содержимого PDF.

### TestSmokeKeycloakRealmRoles

Безопасность — Реальные токены Keycloak проходят аутентификацию и содержат роли.

`alpha.business.insight.core.qa.security.BackendAuthorizationSmokeTest#TestSmokeKeycloakRealmRoles`

Шаги:

1. Запросить /auth/me без токена
2. Получить токены директора и администратора через тестовый realm и проверить ответы

**Ожидаемый результат:** Ожидается 401 без токена и роли COMPANY_DIRECTOR и SYSTEM_ADMIN в реальных JWT.

## Unit

### TestUnitCalculatesCounterpartyShares

Контекст компании — Доли контрагентов рассчитываются от полного оборота.

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitCalculatesCounterpartyShares`

Шаги:

1. Подготовить три контрагента и ограничить выдачу двумя записями
2. Получить контекст компании и проверить доли, концентрацию и вызов репозитория с лимитом

**Ожидаемый результат:** Ожидается две записи, доли 0.6 и 0.3 от общего оборота, top1 0.6 и top3 1.0.

### TestUnitRejectsInvalidLimit

Контекст компании — Неположительный лимит отклоняется.

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitRejectsInvalidLimit`

Шаги:

1. Создать сервис с лимитом контрагентов равным нулю
2. Проверить тип исключения

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestUnitReportsMissingPayments

Контекст компании — Пустая группа и отсутствие платежей отражаются в контексте.

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitReportsMissingPayments`

Шаги:

1. Создать компанию без профиля группы и платежных связей
2. Получить контекст и проверить группы, контрагентов, концентрацию и признаки отсутствующих данных

**Ожидаемый результат:** Ожидаются пустые списки, нулевой оборот, неизвестные доли и маркеры profile.missing, payments и paymentShares.

### TestUnitReturnsNotFound

Контекст компании — Неизвестная компания возвращает 404.

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitReturnsNotFound`

Шаги:

1. Настроить отсутствие компании в репозитории
2. Запросить контекст по неизвестному идентификатору

**Ожидаемый результат:** Ожидается ResponseStatusException со статусом NOT_FOUND.

### TestUnitReadsBomQuotedFields

Импорт CSV — BOM и разделитель внутри кавычек сохраняют значение CSV.

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitReadsBomQuotedFields`

Шаги:

1. Передать UTF-8 BOM и значение ORG_TYPE с точкой с запятой в кавычках
2. Проверить принятый профиль и сериализованную строку

**Ожидаемый результат:** Ожидается одна принятая строка с исходным текстом value;part.

### TestUnitRejectsInvalidPaymentFields

Импорт CSV — Некорректные платежные поля отклоняются с указанием причины.

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitRejectsInvalidPaymentFields`

Шаги:

1. Для каждого варианта передать отсутствующего получателя, отрицательную сумму, дробный или переполненный счётчик
2. Проверить результат импорта и сообщение ошибки

**Ожидаемый результат:** Все варианты отклоняются, принятых строк нет, причина указывает соответствующее обязательное поле или тип значения.

### TestUnitReportsRejectedPaymentRows

Импорт CSV — Импорт отчётливо разделяет принятые и ошибочные платежи.

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitReportsRejectedPaymentRows`

Шаги:

1. Передать строку с неверной суммой и корректную строку с пробелами
2. Проверить отчёт и фактически скопированные поля

**Ожидаемый результат:** Ожидаются одна принятая и одна отклонённая строка, понятная причина ошибки и нормализованные значения.

### TestUnitAcceptValidChunksAcrossPages

Документы — Контракт принимает корректные чанки и векторы.

`alpha.business.insight.core.qa.documents.unit.DocumentAnalysisContractTest#TestUnitAcceptValidChunksAcrossPages`

Шаги:

1. Создать две страницы текста с последовательными ordinal и конечными векторами
2. Проверить результат validate

**Ожидаемый результат:** Ожидается отсутствие исключения.

### TestUnitRejectInvalidAnalysisContract

Документы — Контракт анализа отклоняет некорректные значения.

`alpha.business.insight.core.qa.documents.unit.DocumentAnalysisContractTest#TestUnitRejectInvalidAnalysisContract`

Шаги:

1. Проверить чужой documentId, неизвестную версию, неверные размерности, страницы и ordinal
2. Проверить пустые и null-значения, повтор ordinal и невалидные векторы

**Ожидаемый результат:** Для каждого сценария ожидается IllegalArgumentException.

### TestUnitCleanupAfterAnalyzerFailure

Документы — Ошибка анализатора очищает PDF и становится 502.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitCleanupAfterAnalyzerFailure`

Шаги:

1. Смоделировать исключение анализатора и загрузить PDF
2. Проверить статус ошибки и отсутствие артефактов

**Ожидаемый результат:** Ожидается BAD_GATEWAY, удалённый оригинал и отсутствие записи и вызова удаления Qdrant.

### TestUnitCleanupAfterVectorFailure

Документы — Ошибка векторного индекса удаляет внешние артефакты.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitCleanupAfterVectorFailure`

Шаги:

1. Смоделировать отказ индексации после анализа PDF
2. Проверить cleanup и отсутствие сохранения в репозитории

**Ожидаемый результат:** Ожидается удаление оригинала и запрос на удаление точек документа.

### TestUnitRejectControlFilenameBeforeWrites

Документы — Управляющий символ в имени отклоняется до побочных эффектов.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectControlFilenameBeforeWrites`

Шаги:

1. Загрузить файл с управляющим символом в имени
2. Проверить статус, файловое хранилище, анализатор и репозиторий

**Ожидаемый результат:** Ожидается BAD_REQUEST, файл и запись не создаются, анализатор не вызывается.

### TestUnitRejectForeignAnalysisDocumentId

Документы — Некорректный ответ анализа очищает исходный файл.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectForeignAnalysisDocumentId`

Шаги:

1. Передать ответ ML с чужим documentId
2. Проверить отклонение ответа и очистку хранилищ

**Ожидаемый результат:** Ожидается BAD_GATEWAY, удалённый файл, отсутствие индексации и сохранённых метаданных.

### TestUnitRejectUnreadableUpload

Документы — Ошибка чтения загружаемого файла возвращает 400.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectUnreadableUpload`

Шаги:

1. Смоделировать IOException при чтении MultipartFile
2. Проверить статус и файловое хранилище

**Ожидаемый результат:** Ожидается BAD_REQUEST и отсутствие файлов.

### TestUnitReturnNotFoundForUnknownDocument

Документы — Отсутствующий документ возвращает 404.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitReturnNotFoundForUnknownDocument`

Шаги:

1. Настроить пустой ответ репозитория для UUID
2. Запросить метаданные документа

**Ожидаемый результат:** Ожидается ResponseStatusException со статусом NOT_FOUND.

### TestUnitUploadPdfAndPersistMetadata

Документы — Успешная загрузка сохраняет PDF и метаданные.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitUploadPdfAndPersistMetadata`

Шаги:

1. Загрузить PDF через локальную ML-заглушку с названием и датой публикации
2. Сверить оригинальные байты, SHA-256, имя, дату и индексирование

**Ожидаемый результат:** Ожидаются нормализованное название, безопасное имя rates.pdf, один чанк и неизменённый файл.

### TestUnitUploadWithoutFilenameUsesDefault

Документы — Пустые имена получают безопасное значение по умолчанию.

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitUploadWithoutFilenameUsesDefault`

Шаги:

1. Загрузить PDF без имени и с пустым названием
2. Проверить метаданные документа

**Ожидаемый результат:** Ожидается имя файла и название document.pdf.

### TestUnitDeleteMissingPdfIdempotently

Документы — Повторная очистка отсутствующего файла безопасна.

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitDeleteMissingPdfIdempotently`

Шаги:

1. Удалить несуществующий путь дважды
2. Проверить каталог хранилища

**Ожидаемый результат:** Ожидается пустое хранилище без исключения.

### TestUnitPreventOverwriteOfStoredPdf

Документы — Повторная запись UUID не заменяет оригинал.

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitPreventOverwriteOfStoredPdf`

Шаги:

1. Записать файл и повторить запись с тем же UUID
2. Проверить исключение и сохранённое содержимое

**Ожидаемый результат:** Ожидается FileAlreadyExistsException и прежние байты.

### TestUnitReadLegacyFlatPdf

Документы — Чтение поддерживает прежнее плоское расположение PDF.

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitReadLegacyFlatPdf`

Шаги:

1. Создать файл по старому пути в корне хранилища
2. Прочитать документ по UUID

**Ожидаемый результат:** Ожидаются исходные байты файла.

### TestUnitRejectStoragePathTraversal

Документы — Очистка пути за корнем хранилища запрещена.

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitRejectStoragePathTraversal`

Шаги:

1. Передать путь с переходом к родительскому каталогу
2. Проверить исключение

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestUnitStoreAndReadPdfBytes

Документы — Новое PDF хранится по UUID и читается исходными байтами.

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitStoreAndReadPdfBytes`

Шаги:

1. Записать PDF в пустое временное хранилище
2. Проверить вложенный путь и байты при чтении

**Ожидаемый результат:** Ожидаются каталоги из первых шести символов UUID и побайтно совпадающий оригинал.

### TestUnitPreserveSafeHttpEvidence

HTTP evidence — Безопасный HTTP evidence сохраняется.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitPreserveSafeHttpEvidence`

Шаги:

1. Подготовить HTTP evidence со статусом, URL, именем пользователя и типом содержимого
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить, что все безопасные диагностические поля доступны без изменений

### TestUnitRedactAuthorizationCredentials

HTTP evidence — Учётные данные Authorization скрываются.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactAuthorizationCredentials`

Шаги:

1. Подготовить заголовки Authorization с Bearer и Basic значениями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить, что оба значения секрета отсутствуют в результате

### TestUnitRedactCookieHeaders

HTTP evidence — Значения cookie скрываются в HTTP evidence.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactCookieHeaders`

Шаги:

1. Подготовить заголовки Cookie и Set-Cookie с секретными значениями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие значений cookie и сохранение безопасных атрибутов

### TestUnitRedactJsonSecretsAndPreserveContext

HTTP evidence — Секреты JSON скрываются при сохранении диагностического контекста.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactJsonSecretsAndPreserveContext`

Шаги:

1. Подготовить HTTP evidence с токенами, паролем, client secret и безопасными полями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие всех секретов и сохранность статуса, URL, типа содержимого и имени пользователя

### TestUnitRedactMultipartSecrets

HTTP evidence — Все значения секретного multipart-поля скрываются целиком.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactMultipartSecrets`

Шаги:

1. Подготовить несколько значений `PASSWORD` с запятыми, кавычками и переносом строки
2. Отформатировать секретное поле, безопасное название и описание со встроенным паролем
3. Сверить маскирование всех секретов и сохранность безопасных значений

**Ожидаемый результат:** Значение `PASSWORD` целиком заменено на `[REDACTED]`,
встроенный пароль скрыт, названия файлов сохранены. Секретные части отсутствуют в результате.

### TestUnitRedactUrlEncodedPassword

HTTP evidence — Пароль формы скрывается в URL-encoded теле.

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactUrlEncodedPassword`

Шаги:

1. Подготовить form-urlencoded evidence с закодированным паролем и безопасными параметрами
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие исходного и закодированного секрета при сохранении логина и контекста запроса

## Integration

### TestIntegrationReportsIncompleteProfiles

Контекст компании — Конфликтный профиль и неполная группа отмечаются как недостающие.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenConflictingProfileAndPartialGroup_whenGetContext_thenReportMissingFeatures`

Шаги:

1. Обновить профиль компании и участника группы на конфликтные и неполные данные
2. Получить оба контекста и проверить missingData

**Ожидаемый результат:** Ожидаются маркеры конфликтного профиля, отрасли и пустого списка стран ВЭД.

### TestIntegrationBuildsCompanyContext

Контекст компании — Профиль компании, группа и двунаправленные платежи возвращаются единым контекстом.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenGroupAndBidirectionalPayments_whenGetContext_thenReturnProfileRankingsAndMissingData`

Шаги:

1. Подготовить компанию, участника группы и платежи обеих сторон
2. Запросить API с ролями SYSTEM_ADMIN и COMPANY_DIRECTOR

**Ожидаемый результат:** Ожидаются профиль, две компании в группе, пять контрагентов в порядке оборота, правильные входящие и исходящие суммы, доли и missingData.

### TestIntegrationRejectsUnauthorizedRoles

Контекст компании — Для контекста компании требуется разрешённая роль.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenMissingOrInsufficientRole_whenGetContext_thenRejectRequest`

Шаги:

1. Выполнить запрос без JWT и с ролью OTHER
2. Проверить ответы фильтра безопасности

**Ожидаемый результат:** Ожидаются 401 без токена и 403 для неподдерживаемой роли.

### TestIntegrationReportsMissingPayments

Контекст компании — Контекст без группы и платежей отмечает пробелы данных.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenNoGroupOrPayments_whenGetContext_thenReturnEmptyListsAndUnknownShares`

Шаги:

1. Подготовить компанию без группы и платежей
2. Запросить контекст директором

**Ожидаемый результат:** Ожидаются пустые группы и контрагенты, нулевой оборот, неизвестные доли и перечень отсутствующих признаков.

### TestIntegrationPreservesIncomingPaymentDirection

Контекст компании — Один контрагент даёт концентрацию 100 процентов.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenOneCounterparty_whenGetContext_thenConcentrationIsOneAndIncomingDirectionIsPreserved`

Шаги:

1. Использовать компанию с одной входящей связью
2. Получить контекст и проверить направление и концентрацию

**Ожидаемый результат:** Ожидаются одна запись, входящая сумма 300, исходящая 0 и top1/top3 равные 1.

### TestIntegrationDocumentsOpenApiContract

Контекст компании — OpenAPI описывает API контекста компании.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenPublicSpecification_whenGetOpenApi_thenDescribeCompanyContextUnderCommonPrefix`

Шаги:

1. Получить публичную OpenAPI спецификацию
2. Проверить общий префикс, маршрут, ответ 404 и схему полей

**Ожидаемый результат:** Ожидается описание /companies/{id} под /api и документированные profile и missingData.

### TestIntegrationUsesConfiguredCounterpartyLimit

Контекст компании — Концентрация считается по полному обороту при лимите выдачи.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenSmallConfiguredLimit_whenGetContext_thenCalculateConcentrationAcrossThreeCounterparties`

Шаги:

1. Получить контекст сервиса с лимитом выдачи два
2. Проверить число контрагентов и показатели оборота

**Ожидаемый результат:** Ожидаются две записи, полный оборот 1000, top3Share 0.85; лимит ноль отклоняется.

### TestIntegrationReturnsCompanyNotFound

Контекст компании — Запрос неизвестной компании возвращает 404.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenUnknownCompany_whenGetContext_thenNotFound`

Шаги:

1. Запросить отсутствующий идентификатор с системной ролью
2. Проверить HTTP статус

**Ожидаемый результат:** Ожидается NOT_FOUND.

### TestIntegrationOrdersZeroPaymentTies

Контекст компании — Нулевые обороты и равные суммы обрабатываются устойчиво.

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenZeroAmountsAndTies_whenGetContext_thenReturnStableOrderWithoutDividingByZero`

Шаги:

1. Обнулить платежи компании
2. Получить контекст и проверить порядок и доли

**Ожидаемый результат:** Ожидается стабильная сортировка, null для долей и признак paymentShares без деления на ноль.

### TestIntegrationRollsBackMalformedCsvAndRetries

Импорт CSV — Ошибка разбора CSV откатывается и допускает повтор.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenMalformedCsv_whenImportFiles_thenThrowUncheckedIOExceptionAndAllowRetry`

Шаги:

1. Импортировать базовые данные и сохранить состояние таблиц
2. Передать незакрытую кавычку, проверить откат, восстановить файл и повторить импорт

**Ожидаемый результат:** Ожидается UncheckedIOException без частичных изменений; исправленный повтор завершается с исходным количеством платежей.

### TestIntegrationRollsBackPaymentCountOverflow

Импорт CSV — Переполнение счётчика платежей откатывает весь импорт.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenPaymentCountOverflow_whenImportFiles_thenThrowDataIntegrityViolationExceptionAndRollback`

Шаги:

1. Импортировать валидные данные и затем две строки, сумма счётчиков которых переполняется
2. Проверить исключение, прежний профиль и агрегаты

**Ожидаемый результат:** Ожидается DataIntegrityViolationException и откат изменений.

### TestIntegrationRejectsWrongCsvHeaders

Импорт CSV — Неверные заголовки CSV отклоняются до сохранения строк.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenWrongHeaders_whenImportFiles_thenThrowIOExceptionAndPreserveDatabase`

Шаги:

1. Передать файл платежей с неправильными колонками
2. Проверить исключение и состояние таблиц

**Ожидаемый результат:** Ожидается IOException с пояснением expected headers и пустая таблица компаний.

### TestIntegrationImportsProfilesLinksAndPayments

Импорт CSV — Первичный импорт сохраняет профили, связи и агрегаты.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$InitialImport#givenProfilesLinksAndPayments_whenImportFiles_thenSaveDataAndReportQuality`

Шаги:

1. Импортировать три подготовленных CSV-файла
2. Загрузить записи и отчёт из PostgreSQL и проверить качество строк

**Ожидаемый результат:** Ожидаются согласованные количества принятия и ошибок, конфликтный профиль, компании, платежи и связь с обновлёнными данными.

### TestIntegrationEnrichesMissingProfiles

Импорт CSV — Импорт дополняет отсутствующий профиль.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$RepeatedImport#givenCompanyWithoutProfile_whenImportProfile_thenEnrichCompanyAndPreserveLinks`

Шаги:

1. Импортировать набор данных, затем направить профиль имеющейся компании на другую запись
2. Повторно импортировать и сверить связанные сущности

**Ожидаемый результат:** Ожидается доступный профиль компании, связи и платежи остаются целыми.

### TestIntegrationPreservesIdsAcrossRepeatedImports

Импорт CSV — Повторный импорт сохраняет ключи и обновляет суммы.

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$RepeatedImport#givenExistingData_whenImportFilesAgain_thenPreserveIdsAndRefreshAmounts`

Шаги:

1. Импортировать данные дважды и сравнить UUID агрегатов и связей
2. Изменить сумму платежа и импортировать снова

**Ожидаемый результат:** Ожидаются прежние идентификаторы и новая сумма 22.30 без дублирования строк.

### TestIntegrationSelectLocalMlStub

Документы — При стандартной настройке выбирается локальная заглушка.

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#defaultsToLocalStubWithoutHttpClient`

Шаги:

1. Создать контекст с конфигурацией по умолчанию
2. Проверить единственный bean DocumentAnalyzer

**Ожидаемый результат:** Ожидается StubDocumentAnalyzer без HTTP-клиента.

### TestIntegrationPropagateMlFailureWithoutRetry

Документы — Ошибка ML возвращается вызывающему коду без повтора.

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#feignPropagatesServiceErrorWithoutRetryingUpload`

Шаги:

1. Ответить 503 на запрос анализа
2. Вызвать анализ и посчитать число запросов

**Ожидаемый результат:** Ожидается FeignException и ровно один запрос.

### TestIntegrationSendPdfToMlAndDecode

Документы — Feign отправляет PDF и декодирует результат анализа.

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#feignSendsPdfAndDocumentIdAndDecodesAnalysis`

Шаги:

1. Запустить локальный HTTP-ответчик с результатом контракта
2. Вызвать DocumentAnalyzer и проверить полученные сервером заголовок, путь и байты

**Ожидаемый результат:** Ожидаются application/pdf, путь с documentId и исходный DocumentAnalysis.

### TestIntegrationRejectStoragePathTraversal

Документы — Очистка не выходит за пределы хранилища.

`alpha.business.insight.core.document.DocumentStorageTest#rejectsCleanupPathsOutsideStorage`

Шаги:

1. Передать относительный путь к соседнему файлу
2. Проверить тип результата

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestIntegrationReadLegacyFlatPdf

Документы — Старый плоский путь остаётся читаемым.

`alpha.business.insight.core.document.DocumentStorageTest#stillReadsPreviouslyUploadedFlatFiles`

Шаги:

1. Записать PDF в корень под именем UUID
2. Прочитать документ через DocumentStorage

**Ожидаемый результат:** Ожидаются исходные байты.

### TestIntegrationStorePdfByUuidPrefix

Документы — Хранилище распределяет PDF по первым шести символам UUID.

`alpha.business.insight.core.document.DocumentStorageTest#storesPdfInThreeDirectoriesFromFirstSixUuidCharacters`

Шаги:

1. Записать PDF и прочитать его из вложенного каталога
2. Удалить файл по вычисляемому относительному пути

**Ожидаемый результат:** Ожидаются исходные байты и отсутствие файла после удаления.

### TestIntegrationConcurrentWritesKeepSingleWinner

Документы — Конкурентная запись одного UUID сохраняет единственный полный PDF.

`alpha.business.insight.core.document.DocumentStorageConcurrencyTest#TestIntegrationConcurrentWritesKeepSingleWinner`

Шаги:

1. Одновременно записать два набора синтетических байтов по одному UUID
2. Проверить один успешный исход, отказ FileAlreadyExistsException, содержимое победителя и число файлов

**Ожидаемый результат:** Ожидается один полный файл без смешивания байтов; проверяется файловое production-хранилище без HTTP и AI.

### TestIntegrationConcurrentDocumentsKeepOwnBytes

Документы — Разные UUID с общим префиксом сохраняют независимые PDF.

`alpha.business.insight.core.document.DocumentStorageConcurrencyTest#TestIntegrationConcurrentDocumentsKeepOwnBytes`

Шаги:

1. Одновременно записать синтетические PDF по разным UUID с общим префиксом
2. Проверить байты обоих файлов и число файлов

**Ожидаемый результат:** Ожидаются две независимые полные записи в общем каталоге; проверяется файловое production-хранилище без HTTP и AI.

### TestIntegrationIsolateConcurrentUploads

Документы — Параллельные загрузки получают независимые UUID документов и чанков.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#concurrentUploadsKeepDocumentAndChunkIdsIndependent`

Шаги:

1. Запустить четыре загрузки одновременно
2. Сверить название каждого документа и точки Qdrant

**Ожидаемый результат:** Ожидаются четыре сохранённых документа с отдельными точками.

### TestIntegrationRejectInvalidMlResponses

Документы — Некорректный ответ ML отменяет загрузку.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#invalidMlResponseCancelsUpload`

Шаги:

1. Для каждого fault-сценария вернуть неверный ID, ordinal, размерность, NaN, пустой список или исключение
2. Проверить HTTP ответ и побочные эффекты

**Ожидаемый результат:** Ожидаются 502, отсутствие метаданных и очищенный файловый оригинал.

### TestIntegrationCleanArtifactsAfterMetadataFailure

Документы — Ошибка фиксации метаданных компенсирует внешние записи.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#metadataCommitFailureDeletesAlreadyIndexedChunksAndOriginal`

Шаги:

1. Создать отложенный триггер, завершающий вставку документа ошибкой
2. Загрузить документ и проверить очистку всех хранилищ

**Ожидаемый результат:** Ожидается отсутствие записи, точки Qdrant и исходного файла.

### TestIntegrationPreserveChunkPagesAndIds

Документы — Несколько чанков сохраняют страницы и независимые идентификаторы.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#multipleChunksPreserveTheirTextsPagesAndIndependentIds`

Шаги:

1. Вернуть анализ с двумя страницами и загрузить PDF
2. Прочитать второй чанк по его ID

**Ожидаемый результат:** Ожидаются два чанка, страница 3 и текст соответствующего фрагмента.

### TestIntegrationDocumentOpenApiContract

Документы — OpenAPI публикует безопасную схему метаданных.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#openApiDescribesMetadataWithoutPostgresFragmentFields`

Шаги:

1. Получить OpenAPI через MockMvc
2. Проверить описания размера, SHA-256, количества чанков и отсутствие внутренних полей

**Ожидаемый результат:** Ожидаются публичные поля контракта и документированное название загрузки.

### TestIntegrationCleanPartialVectorWrites

Документы — Частичная запись Qdrant компенсируется.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#partialQdrantWriteDeletesChunksAndOriginalWithoutSavingMetadata`

Шаги:

1. Выполнить реальную запись точки и затем смоделировать отказ
2. Проверить документы, точки Qdrant и файл

**Ожидаемый результат:** Ожидается отсутствие метаданных, чанков и оригинала.

### TestIntegrationRejectInvalidPdfUploads

Документы — Пустой, не PDF и превышающий лимит файлы отклоняются.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#rejectsEmptyNonPdfAndOversizedFileBeforeCreatingArtifacts`

Шаги:

1. Загрузить файлы размером 0, 3 байта и свыше 20 МБ
2. Проверить статусы, записи и файлы

**Ожидаемый результат:** Ожидаются 400, 415 и 413 без созданных артефактов.

### TestIntegrationRequireAdminAndHandleMissingDocument

Документы — Загрузка требует администратора, неизвестный документ возвращает 404.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#uploadRequiresAdminAndUnknownDocumentReturns404`

Шаги:

1. Выполнить загрузку без токена и с ролью директора
2. Запросить неизвестный UUID с токеном директора

**Ожидаемый результат:** Ожидаются 401, 403 и 404 без новых документов.

### TestIntegrationPersistMetadataAndIndexChunks

Документы — PDF, метаданные и vector payload распределяются по хранилищам.

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#uploadStoresOnlyMetadataInPostgresAndTextAlongsideVectorInQdrant`

Шаги:

1. Загрузить PDF как системный администратор
2. Проверить PostgreSQL, оригинал, Qdrant payload/vector и ответы на чтение для обеих ролей

**Ожидаемый результат:** Ожидаются метаданные в PostgreSQL, оригинал в файловом хранилище и помеченный ML STUB чанк в Qdrant.

### TestIntegrationPersistAnalysisGraphAndReload

Схема данных — Сущности события и анализа сохраняют данные и связи.

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#givenAnalysisGraph_whenPersistAndReload_thenPreserveDataAndRelations`

Шаги:

1. Сохранить event, insight, step, диалог, feedback и доступ пользователя к компании
2. Очистить persistence context и загрузить сущности повторно

**Ожидаемый результат:** Ожидаются исходные JSONB, enum, связи, суммы и текстовые поля.

### TestIntegrationEnforceInsightCacheVersion

Схема данных — Ключ кэша допускает новую версию материала.

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#givenCachedInsight_whenInsertVersion_thenEnforceCacheKey`

Шаги:

1. Создать событие и инсайты для версий 1 и 2
2. Повторно вставить версию 1

**Ожидаемый результат:** Ожидаются две записи и ограничение уникальности для повторной версии.

### TestIntegrationKeepChunksOutOfPostgres

Схема данных — Чанки и очередь очистки не хранятся в PostgreSQL.

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#whenInspectSchema_thenChunksAreNotStoredInPostgres`

Шаги:

1. Проверить прикладную схему таблиц
2. Найти fragments, facts и document_upload_cleanup

**Ожидаемый результат:** Ожидается отсутствие всех трёх таблиц.

### TestIntegrationDocumentDatabaseSchemaComments

Схема данных — Таблицы и колонки прикладной схемы документированы.

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#whenInspectSchema_thenEveryTableAndColumnHasComment`

Шаги:

1. Проверить комментарии таблиц и колонок в системном каталоге PostgreSQL
2. Исключить только системные таблицы Liquibase

**Ожидаемый результат:** Ожидаются комментарии у каждой прикладной таблицы и колонки.

### TestIntegrationImportsPackagedCsvOnce

Импорт CSV — Комплектные CSV мигрируют ровно один раз.

`alpha.business.insight.core.migration.CsvStartupMigrationIntegrationTest$ApplicationStartup#givenPackagedCsv_whenStartAndRestartApplication_thenImportOnceAndPreserveChanges`

Шаги:

1. Запустить приложение на чистой схеме и проверить контрольные объёмы данных
2. Изменить импортированные значения и перезапустить приложение

**Ожидаемый результат:** Ожидается неизменный changeset, сохранённые ручные правки и прежнее число платежей.

### TestIntegrationRollsBackRejectedCsvRows

Импорт CSV — Неудачная миграция откатывается и повторяется после исправления.

`alpha.business.insight.core.migration.CsvStartupMigrationIntegrationTest$MigrationExecution#givenRejectedCsvRows_whenMigrate_thenRollbackAndAllowRetry`

Шаги:

1. Запустить changeset с ошибочными строками и проверить отсутствие частичных таблиц
2. Исправить тестовые файлы и выполнить миграцию повторно

**Ожидаемый результат:** Ожидается первая ошибка без отметки changeset и успешный повтор с двумя компаниями, связью и платежом.

### TestIntegrationAllowSystemAdminRoute

Безопасность — Системный администратор открывает административный маршрут.

`alpha.business.insight.core.security.SecurityConfigTest#givenAdmin_whenAdminRequest_thenAllowed`

Шаги:

1. Выдать JWT SYSTEM_ADMIN
2. Запросить /admin/check

**Ожидаемый результат:** Ожидается HTTP 200.

### TestIntegrationRecognizeApplicationRoles

Безопасность — Поддерживаемые роли видят идентификатор и имя пользователя.

`alpha.business.insight.core.security.SecurityConfigTest#givenApplicationRole_whenGetMe_thenReturnIdentity`

Шаги:

1. Для SYSTEM_ADMIN и COMPANY_DIRECTOR выдать тестовый JWT
2. Запросить auth/me

**Ожидаемый результат:** Ожидаются HTTP 200, subject, имя demo и переданная роль.

### TestIntegrationDenyDirectorAdminRoute

Безопасность — Роль директора не открывает административный маршрут.

`alpha.business.insight.core.security.SecurityConfigTest#givenDirector_whenAdminRequest_thenForbidden`

Шаги:

1. Выдать JWT COMPANY_DIRECTOR
2. Запросить /admin/check

**Ожидаемый результат:** Ожидается 403.

### TestIntegrationRejectInvalidJwt

Безопасность — JWT с ошибкой декодирования отклоняется.

`alpha.business.insight.core.security.SecurityConfigTest#givenInvalidToken_whenRequest_thenUnauthorized`

Шаги:

1. Настроить декодер на BadJwtException
2. Выполнить запрос с bearer invalid

**Ожидаемый результат:** Ожидается 401.

### TestIntegrationRejectUnknownRoles

Безопасность — JWT без разрешённой роли запрещён.

`alpha.business.insight.core.security.SecurityConfigTest#givenNoApplicationRole_whenGetMe_thenForbidden`

Шаги:

1. Проверить JWT без ролей и с OTHER_ROLE
2. Запросить auth/me

**Ожидаемый результат:** Ожидается 403 для обоих вариантов.

### TestIntegrationPublicOpenApiYaml

Безопасность — OpenAPI YAML доступен анонимно.

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetOpenApiYaml_thenReturnSpecification`

Шаги:

1. Запросить YAML endpoint
2. Проверить наличие маршрута auth/me

**Ожидаемый результат:** Ожидается HTTP 200 и спецификация API.

### TestIntegrationPublicOpenApi

Безопасность — OpenAPI доступен без авторизации и описывает вход Keycloak.

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetOpenApi_thenReturnApiWithKeycloakLogin`

Шаги:

1. Запросить публичную OpenAPI спецификацию
2. Проверить серверный префикс, маршруты, OAuth URLs и scope

**Ожидаемый результат:** Ожидается схема OAuth2 с authorization code и scope openid.

### TestIntegrationPublicSwaggerUi

Безопасность — Swagger UI и его конфигурация доступны без токена.

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetSwaggerUi_thenReturnUiAndConfiguration`

Шаги:

1. Получить страницу, redirect, initializer, OAuth redirect и swagger config
2. Проверить адрес API и настройки клиента с PKCE

**Ожидаемый результат:** Ожидаются открытые страницы и clientId alfa-backend с prompt login.

### TestIntegrationRequireJwt

Безопасность — Защищённые маршруты требуют JWT.

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenRequest_thenUnauthorized`

Шаги:

1. Запросить auth/me и административный маршрут без токена
2. Проверить статусы

**Ожидаемый результат:** Ожидаются 401 для обоих маршрутов.

### TestIntegrationRejectStringEmbedding

Документы — Feign отклоняет строковую координату embedding.

`alpha.business.insight.core.qa.documents.integration.DocumentAnalyzerJsonContractTest#TestIntegrationRejectStringEmbedding`

Шаги:

1. Запустить локальный HTTP endpoint на случайном loopback-порту и подготовить валидный stub-v1 JSON с одной строковой координатой
2. Передать синтетический PDF через production Feign DocumentAnalyzer

**Ожидаемый результат:** Ожидается DecodeException; JSON с числовой строкой не принимается согласно ML-контракту

## E2E

### TestE2ECompanyAccessRequiresGrant

Контекст компании — Чтение компании требует связи пользователя с компанией.

`alpha.business.insight.core.qa.company.CompanyContextHttpE2eTest#TestE2ECompanyAccessRequiresGrant`

Шаги:

1. Создать компанию без строки user_company_access и получить токен директора
2. Запросить контекст и сравнить статус с требованием видения, раздел 2.4

**Ожидаемый результат:** Ожидается 403; текущий код возвращает 200 и тест отмечен known-defect до исправления.

### TestE2EDocumentAccessRules

Документы — Запись доступна только администратору, чтение требует JWT.

`alpha.business.insight.core.qa.documents.DocumentUploadHttpE2eTest#TestE2EDocumentAccessRules`

Шаги:

1. Отправить загрузку с JWT директора и запросить документ без токена
2. Проверить статусы ответов

**Ожидаемый результат:** Ожидаются 403 для директора и 401 для анонимного запроса.
