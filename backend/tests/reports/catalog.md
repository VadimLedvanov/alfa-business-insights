# Каталог тестов и проверок

Названия, шаги и ожидаемые результаты соответствуют планам Allure. Параметризованный сценарий создаёт несколько запусков. Фактически исполненные шаги и HTTP-вложения доступны в отчёте.

## Unit

### TestUnitCalculatesCounterpartyShares

Доли контрагентов рассчитываются от полного оборота

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitCalculatesCounterpartyShares`

1. Подготовить три контрагента и ограничить выдачу двумя записями
2. Получить контекст компании и проверить доли, концентрацию и вызов репозитория с лимитом

**Ожидаемый результат:** Ожидается две записи, доли 0.6 и 0.3 от общего оборота, top1 0.6 и top3 1.0.

### TestUnitRejectsInvalidLimit

Неположительный лимит отклоняется

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitRejectsInvalidLimit`

1. Создать сервис с лимитом контрагентов равным нулю
2. Проверить тип исключения

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestUnitReportsMissingPayments

Пустая группа и отсутствие платежей отражаются в контексте

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitReportsMissingPayments`

1. Создать компанию без профиля группы и платежных связей
2. Получить контекст и проверить группы, контрагентов, концентрацию и признаки отсутствующих данных

**Ожидаемый результат:** Ожидаются пустые списки, нулевой оборот, неизвестные доли и маркеры profile.missing, payments и paymentShares.

### TestUnitReturnsNotFound

Неизвестная компания возвращает 404

`alpha.business.insight.core.qa.company.CompanyContextServiceTest#TestUnitReturnsNotFound`

1. Настроить отсутствие компании в репозитории
2. Запросить контекст по неизвестному идентификатору

**Ожидаемый результат:** Ожидается ResponseStatusException со статусом NOT_FOUND.

### TestUnitReadsBomQuotedFields

BOM и разделитель внутри кавычек сохраняют значение CSV

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitReadsBomQuotedFields`

1. Передать UTF-8 BOM и значение ORG_TYPE с точкой с запятой в кавычках
2. Проверить принятый профиль и сериализованную строку

**Ожидаемый результат:** Ожидается одна принятая строка с исходным текстом value;part.

### TestUnitRejectsInvalidPaymentFields

Некорректные платежные поля отклоняются с указанием причины

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitRejectsInvalidPaymentFields`

1. Для каждого варианта передать отсутствующего получателя, отрицательную сумму, дробный или переполненный счётчик
2. Проверить результат импорта и сообщение ошибки

**Ожидаемый результат:** Все варианты отклоняются, принятых строк нет, причина указывает соответствующее обязательное поле или тип значения.

### TestUnitReportsRejectedPaymentRows

Импорт отчётливо разделяет принятые и ошибочные платежи

`alpha.business.insight.core.qa.dataimport.CsvImportServiceTest#TestUnitReportsRejectedPaymentRows`

1. Передать строку с неверной суммой и корректную строку с пробелами
2. Проверить отчёт и фактически скопированные поля

**Ожидаемый результат:** Ожидаются одна принятая и одна отклонённая строка, понятная причина ошибки и нормализованные значения.

### TestUnitAcceptValidChunksAcrossPages

Контракт принимает корректные чанки и векторы

`alpha.business.insight.core.qa.documents.unit.DocumentAnalysisContractTest#TestUnitAcceptValidChunksAcrossPages`

1. Создать две страницы текста с последовательными ordinal и конечными векторами
2. Проверить результат validate

**Ожидаемый результат:** Ожидается отсутствие исключения.

### TestUnitRejectInvalidAnalysisContract

Контракт анализа отклоняет некорректные значения

`alpha.business.insight.core.qa.documents.unit.DocumentAnalysisContractTest#TestUnitRejectInvalidAnalysisContract`

1. Проверить чужой documentId, неизвестную версию, неверные размерности, страницы и ordinal
2. Проверить пустые и null-значения, повтор ordinal и невалидные векторы

**Ожидаемый результат:** Для каждого сценария ожидается IllegalArgumentException.

### TestUnitCleanupAfterAnalyzerFailure

Ошибка анализатора очищает PDF и становится 502

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitCleanupAfterAnalyzerFailure`

1. Смоделировать исключение анализатора и загрузить PDF
2. Проверить статус ошибки и отсутствие артефактов

**Ожидаемый результат:** Ожидается BAD_GATEWAY, удалённый оригинал и отсутствие записи и вызова удаления Qdrant.

### TestUnitCleanupAfterVectorFailure

Ошибка векторного индекса удаляет внешние артефакты

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitCleanupAfterVectorFailure`

1. Смоделировать отказ индексации после анализа PDF
2. Проверить cleanup и отсутствие сохранения в репозитории

**Ожидаемый результат:** Ожидается удаление оригинала и запрос на удаление точек документа.

### TestUnitRejectControlFilenameBeforeWrites

Управляющий символ в имени отклоняется до побочных эффектов

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectControlFilenameBeforeWrites`

1. Загрузить файл с управляющим символом в имени
2. Проверить статус, файловое хранилище, анализатор и репозиторий

**Ожидаемый результат:** Ожидается BAD_REQUEST, файл и запись не создаются, анализатор не вызывается.

### TestUnitRejectForeignAnalysisDocumentId

Некорректный ответ анализа очищает исходный файл

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectForeignAnalysisDocumentId`

1. Передать ответ ML с чужим documentId
2. Проверить отклонение ответа и очистку хранилищ

**Ожидаемый результат:** Ожидается BAD_GATEWAY, удалённый файл, отсутствие индексации и сохранённых метаданных.

### TestUnitRejectUnreadableUpload

Ошибка чтения загружаемого файла возвращает 400

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitRejectUnreadableUpload`

1. Смоделировать IOException при чтении MultipartFile
2. Проверить статус и файловое хранилище

**Ожидаемый результат:** Ожидается BAD_REQUEST и отсутствие файлов.

### TestUnitReturnNotFoundForUnknownDocument

Отсутствующий документ возвращает 404

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitReturnNotFoundForUnknownDocument`

1. Настроить пустой ответ репозитория для UUID
2. Запросить метаданные документа

**Ожидаемый результат:** Ожидается ResponseStatusException со статусом NOT_FOUND.

### TestUnitUploadPdfAndPersistMetadata

Успешная загрузка сохраняет PDF и метаданные

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitUploadPdfAndPersistMetadata`

1. Загрузить PDF через локальную ML-заглушку с названием и датой публикации
2. Сверить оригинальные байты, SHA-256, имя, дату и индексирование

**Ожидаемый результат:** Ожидаются нормализованное название, безопасное имя rates.pdf, один чанк и неизменённый файл.

### TestUnitUploadWithoutFilenameUsesDefault

Пустые имена получают безопасное значение по умолчанию

`alpha.business.insight.core.qa.documents.unit.DocumentServiceTest#TestUnitUploadWithoutFilenameUsesDefault`

1. Загрузить PDF без имени и с пустым названием
2. Проверить метаданные документа

**Ожидаемый результат:** Ожидается имя файла и название document.pdf.

### TestUnitDeleteMissingPdfIdempotently

Повторная очистка отсутствующего файла безопасна

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitDeleteMissingPdfIdempotently`

1. Удалить несуществующий путь дважды
2. Проверить каталог хранилища

**Ожидаемый результат:** Ожидается пустое хранилище без исключения.

### TestUnitPreventOverwriteOfStoredPdf

Повторная запись UUID не заменяет оригинал

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitPreventOverwriteOfStoredPdf`

1. Записать файл и повторить запись с тем же UUID
2. Проверить исключение и сохранённое содержимое

**Ожидаемый результат:** Ожидается FileAlreadyExistsException и прежние байты.

### TestUnitReadLegacyFlatPdf

Чтение поддерживает прежнее плоское расположение PDF

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitReadLegacyFlatPdf`

1. Создать файл по старому пути в корне хранилища
2. Прочитать документ по UUID

**Ожидаемый результат:** Ожидаются исходные байты файла.

### TestUnitRejectStoragePathTraversal

Очистка пути за корнем хранилища запрещена

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitRejectStoragePathTraversal`

1. Передать путь с переходом к родительскому каталогу
2. Проверить исключение

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestUnitStoreAndReadPdfBytes

Новое PDF хранится по UUID и читается исходными байтами

`alpha.business.insight.core.qa.documents.unit.DocumentStorageTest#TestUnitStoreAndReadPdfBytes`

1. Записать PDF в пустое временное хранилище
2. Проверить вложенный путь и байты при чтении

**Ожидаемый результат:** Ожидаются каталоги из первых шести символов UUID и побайтно совпадающий оригинал.

### TestUnitPreserveSafeHttpEvidence

Безопасный HTTP evidence сохраняется

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitPreserveSafeHttpEvidence`

1. Подготовить HTTP evidence со статусом, URL, именем пользователя и типом содержимого
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить, что все безопасные диагностические поля доступны без изменений

### TestUnitRedactAuthorizationCredentials

Учётные данные Authorization скрываются

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactAuthorizationCredentials`

1. Подготовить заголовки Authorization с Bearer и Basic значениями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить, что оба значения секрета отсутствуют в результате

### TestUnitRedactCookieHeaders

Значения cookie скрываются в HTTP evidence

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactCookieHeaders`

1. Подготовить заголовки Cookie и Set-Cookie с секретными значениями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие значений cookie и сохранение безопасных атрибутов

### TestUnitRedactJsonSecretsAndPreserveContext

Секреты JSON скрываются при сохранении диагностического контекста

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactJsonSecretsAndPreserveContext`

1. Подготовить HTTP evidence с токенами, паролем, client secret и безопасными полями
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие всех секретов и сохранность статуса, URL, типа содержимого и имени пользователя

### TestUnitRedactUrlEncodedPassword

Пароль формы скрывается в URL-encoded теле

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactUrlEncodedPassword`

1. Подготовить form-urlencoded evidence с закодированным паролем и безопасными параметрами
2. Применить редактирование evidence

**Ожидаемый результат:** Проверить отсутствие исходного и закодированного секрета при сохранении логина и контекста запроса

### TestUnitRedactMultipartSecrets

Секреты multipart-полей скрываются полностью

`alpha.business.insight.core.qa.connectors.HttpEvidenceTest#TestUnitRedactMultipartSecrets`

1. Подготовить значения password с запятой, кавычками и переносом строки, безопасное название файла и текстовое поле с вложенным паролем
2. Сформатировать каждое поле через HttpEvidence.formField

**Ожидаемый результат:** Проверить отсутствие секретов, сохранение безопасного названия и маскирование вложенного пароля

### TestUnitFilterUnsupportedFactsAndBankView

Проверка привязки фактов и позиции банка

`alpha.business.insight.core.insight.InsightValidatorTest#removesUnretrievedFactsAndBankViewFromNonBankSources`

1. Передать подтверждённый и неподтверждённые факты и позицию банка по не банковскому источнику.
2. Проверить оставшиеся записи, ссылки и missing_data.

**Ожидаемый результат:** Остаётся только подтверждённый факт; позиция банка удалена, выдуманный источник исключён, причина удаления указана.

### TestUnitRequireRealBankMaterial

Позиция банка требует реального материала

`alpha.business.insight.core.insight.InsightValidatorTest#acceptsBankReferencesOnlyFromRealBankMaterial`

1. Проверить позицию со ссылкой на реальный BANK_ANALYTICS и на stub-фрагмент.
2. Проверить страницы и источники.

**Ожидаемый результат:** Реальный банковский материал допустим и представлен без текста фрагмента; stub-позиция удалена.

### TestUnitNormalizeInferenceGrounding

Фильтрация stub-фактов и обоснования выводов

`alpha.business.insight.core.insight.InsightValidatorTest#removesSyntheticFactsAndNormalizesInferenceGrounding`

1. Передать факт со stub-источником и вывод с выдуманной ссылкой.
2. Проверить факты, based_on, confidence, missing_data и cost.

**Ожидаемый результат:** Stub-факт удалён; неподтверждённое обоснование очищено, confidence понижен до low; missing_data и cost сохранены.

### TestUnitRemoveNestedSourceText

Удаление вложенных копий исходного текста

`alpha.business.insight.core.insight.InsightValidatorTest#discardsNestedSourceCopiesFromModelOutput`

1. Поместить текст фрагмента во вложенные поля ответа модели.
2. Проверить нормализованные поля результата.

**Ожидаемый результат:** Сырой текст фрагмента отсутствует в выходном JSON; допустимые утверждения и features_used сохранены.

### TestUnitSelectLlmStubWhenChatDisabled

Заглушка модели при отключённом чате

`alpha.business.insight.core.insight.LlmClientTest#disabledChatUsesStubWithoutCreatingRemoteModel`

1. Запустить ApplicationContext с spring.ai.model.chat=none.
2. Проверить отсутствие OpenAiChatModel и ответ stub-клиента.

**Ожидаемый результат:** Удалённая модель не создаётся; клиент возвращает stub-ответ.

### TestUnitSendOpenAiCompatibleJsonRequest

Совместимый OpenAI запрос и JSON ответ

`alpha.business.insight.core.insight.LlmClientTest#sendsOpenAiCompatibleRequestAndReadsJson`

1. Поднять локальный HTTP-сервер и вызвать клиент с JSON-контекстом.
2. Проверить путь, авторизацию, модель, лимит токенов, формат JSON и распарсенный ответ.

**Ожидаемый результат:** Клиент отправляет совместимый запрос на /v1/chat/completions с заданными заголовками и параметрами и читает JSON-ответ.

### TestUnitDisableRemoteChatWithEmptyKey

Отключённый чат не создаёт удалённую модель

`alpha.business.insight.core.insight.LlmClientTest#disabledChatUsesStubEvenWithRemoteSettingsAndEmptyKey`

1. Задать chat=none, пустой ключ и удалённый URL.
2. Проверить отсутствие OpenAiChatModel и stub-ответ.

**Ожидаемый результат:** Клиент остаётся stub и не создаёт удалённую модель.

### TestUnitTimeoutLlmCallWithoutRetry

Тайм-аут вызова LLM без повтора

`alpha.business.insight.core.insight.LlmClientTest#stalledLlmCallTimesOutWithoutRetry`

1. Запустить локальный сервер, который удерживает запрос дольше установленного тайм-аута.
2. Проверить статус исключения, длительность и число запросов.

**Ожидаемый результат:** Вызов завершается 503 до трёх секунд и не повторяется.

### TestUnitHideProviderErrorAndAvoidRetry

Скрытие ошибки провайдера без повтора

`alpha.business.insight.core.insight.LlmClientTest#budgetErrorIsNotRetriedAndDoesNotExposeProviderBody`

1. Ответить локального сервера HTTP 429 с секретным телом.
2. Проверить статус, отсутствие секрета/ключа в исключении и число запросов.

**Ожидаемый результат:** Клиент возвращает 503, не раскрывает тело провайдера или API-ключ и выполняет ровно один запрос.

### TestUnitCreateQdrantClientWithoutNetworkWait

Создание клиента Qdrant без ожидания сети

`alpha.business.insight.core.document.QdrantConfigTest#clientCreationDoesNotBlockOnUnresponsiveQdrant`

1. Открыть локальный порт TCP без реализации Qdrant.
2. Создать Qdrant client и проверить тайм-аут операции создания.

**Ожидаемый результат:** Создание клиента завершается не дольше двух секунд и не инициирует блокирующий сетевой вызов.

## Integration

### TestIntegrationReportsIncompleteProfiles

Конфликтный профиль и неполная группа отмечаются как недостающие

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenConflictingProfileAndPartialGroup_whenGetContext_thenReportMissingFeatures`

1. Обновить профиль компании и участника группы на конфликтные и неполные данные
2. Получить оба контекста и проверить missingData

**Ожидаемый результат:** Ожидаются маркеры конфликтного профиля, отрасли и пустого списка стран ВЭД.

### TestIntegrationBuildsCompanyContext

Профиль компании, группа и двунаправленные платежи возвращаются единым контекстом

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenGroupAndBidirectionalPayments_whenGetContext_thenReturnProfileRankingsAndMissingData`

1. Подготовить компанию, участника группы и платежи обеих сторон
2. Запросить API с ролями SYSTEM_ADMIN и COMPANY_DIRECTOR

**Ожидаемый результат:** Ожидаются профиль, две компании в группе, пять контрагентов в порядке оборота, правильные входящие и исходящие суммы, доли и missingData.

### TestIntegrationRejectsUnauthorizedRoles

Для контекста компании требуется разрешённая роль

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenMissingOrInsufficientRole_whenGetContext_thenRejectRequest`

1. Выполнить запрос без JWT и с ролью OTHER
2. Проверить ответы фильтра безопасности

**Ожидаемый результат:** Ожидаются 401 без токена и 403 для неподдерживаемой роли.

### TestIntegrationReportsMissingPayments

Контекст без группы и платежей отмечает пробелы данных

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenNoGroupOrPayments_whenGetContext_thenReturnEmptyListsAndUnknownShares`

1. Подготовить компанию без группы и платежей
2. Запросить контекст директором

**Ожидаемый результат:** Ожидаются пустые группы и контрагенты, нулевой оборот, неизвестные доли и перечень отсутствующих признаков.

### TestIntegrationPreservesIncomingPaymentDirection

Один контрагент даёт концентрацию 100 процентов

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenOneCounterparty_whenGetContext_thenConcentrationIsOneAndIncomingDirectionIsPreserved`

1. Использовать компанию с одной входящей связью
2. Получить контекст и проверить направление и концентрацию

**Ожидаемый результат:** Ожидаются одна запись, входящая сумма 300, исходящая 0 и top1/top3 равные 1.

### TestIntegrationDocumentsOpenApiContract

OpenAPI описывает API контекста компании

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenPublicSpecification_whenGetOpenApi_thenDescribeCompanyContextUnderCommonPrefix`

1. Получить публичную OpenAPI спецификацию
2. Проверить общий префикс, маршрут, ответ 404 и схему полей

**Ожидаемый результат:** Ожидается описание /companies/{id} под /api и документированные profile и missingData.

### TestIntegrationUsesConfiguredCounterpartyLimit

Концентрация считается по полному обороту при лимите выдачи

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenSmallConfiguredLimit_whenGetContext_thenCalculateConcentrationAcrossThreeCounterparties`

1. Получить контекст сервиса с лимитом выдачи два
2. Проверить число контрагентов и показатели оборота

**Ожидаемый результат:** Ожидаются две записи, полный оборот 1000, top3Share 0.85; лимит ноль отклоняется.

### TestIntegrationReturnsCompanyNotFound

Запрос неизвестной компании возвращает 404

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenUnknownCompany_whenGetContext_thenNotFound`

1. Запросить отсутствующий идентификатор с системной ролью
2. Проверить HTTP статус

**Ожидаемый результат:** Ожидается NOT_FOUND.

### TestIntegrationOrdersZeroPaymentTies

Нулевые обороты и равные суммы обрабатываются устойчиво

`alpha.business.insight.core.company.CompanyContextIntegrationTest#givenZeroAmountsAndTies_whenGetContext_thenReturnStableOrderWithoutDividingByZero`

1. Обнулить платежи компании
2. Получить контекст и проверить порядок и доли

**Ожидаемый результат:** Ожидается стабильная сортировка, null для долей и признак paymentShares без деления на ноль.

### TestIntegrationRollsBackMalformedCsvAndRetries

Ошибка разбора CSV откатывается и допускает повтор

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenMalformedCsv_whenImportFiles_thenThrowUncheckedIOExceptionAndAllowRetry`

1. Импортировать базовые данные и сохранить состояние таблиц
2. Передать незакрытую кавычку, проверить откат, восстановить файл и повторить импорт

**Ожидаемый результат:** Ожидается UncheckedIOException без частичных изменений; исправленный повтор завершается с исходным количеством платежей.

### TestIntegrationRollsBackPaymentCountOverflow

Переполнение счётчика платежей откатывает весь импорт

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenPaymentCountOverflow_whenImportFiles_thenThrowDataIntegrityViolationExceptionAndRollback`

1. Импортировать валидные данные и затем две строки, сумма счётчиков которых переполняется
2. Проверить исключение, прежний профиль и агрегаты

**Ожидаемый результат:** Ожидается DataIntegrityViolationException и откат изменений.

### TestIntegrationRejectsWrongCsvHeaders

Неверные заголовки CSV отклоняются до сохранения строк

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$ImportFailures#givenWrongHeaders_whenImportFiles_thenThrowIOExceptionAndPreserveDatabase`

1. Передать файл платежей с неправильными колонками
2. Проверить исключение и состояние таблиц

**Ожидаемый результат:** Ожидается IOException с пояснением expected headers и пустая таблица компаний.

### TestIntegrationImportsProfilesLinksAndPayments

Первичный импорт сохраняет профили, связи и агрегаты

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$InitialImport#givenProfilesLinksAndPayments_whenImportFiles_thenSaveDataAndReportQuality`

1. Импортировать три подготовленных CSV-файла
2. Загрузить записи и отчёт из PostgreSQL и проверить качество строк

**Ожидаемый результат:** Ожидаются согласованные количества принятия и ошибок, конфликтный профиль, компании, платежи и связь с обновлёнными данными.

### TestIntegrationEnrichesMissingProfiles

Импорт дополняет отсутствующий профиль

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$RepeatedImport#givenCompanyWithoutProfile_whenImportProfile_thenEnrichCompanyAndPreserveLinks`

1. Импортировать набор данных, затем направить профиль имеющейся компании на другую запись
2. Повторно импортировать и сверить связанные сущности

**Ожидаемый результат:** Ожидается доступный профиль компании, связи и платежи остаются целыми.

### TestIntegrationPreservesIdsAcrossRepeatedImports

Повторный импорт сохраняет ключи и обновляет суммы

`alpha.business.insight.core.dataimport.CsvImportIntegrationTest$RepeatedImport#givenExistingData_whenImportFilesAgain_thenPreserveIdsAndRefreshAmounts`

1. Импортировать данные дважды и сравнить UUID агрегатов и связей
2. Изменить сумму платежа и импортировать снова

**Ожидаемый результат:** Ожидаются прежние идентификаторы и новая сумма 22.30 без дублирования строк.

### TestIntegrationSelectLocalMlStub

При стандартной настройке выбирается локальная заглушка

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#defaultsToLocalStubWithoutHttpClient`

1. Создать контекст с конфигурацией по умолчанию
2. Проверить единственный bean DocumentAnalyzer

**Ожидаемый результат:** Ожидается StubDocumentAnalyzer без HTTP-клиента.

### TestIntegrationPropagateMlFailureWithoutRetry

Ошибка ML возвращается вызывающему коду без повтора

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#feignPropagatesServiceErrorWithoutRetryingUpload`

1. Ответить 503 на запрос анализа
2. Вызвать анализ и посчитать число запросов

**Ожидаемый результат:** Ожидается FeignException и ровно один запрос.

### TestIntegrationSendPdfToMlAndDecode

Feign отправляет PDF и декодирует результат анализа

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#feignSendsPdfAndDocumentIdAndDecodesAnalysis`

1. Запустить локальный HTTP-ответчик с результатом контракта
2. Вызвать DocumentAnalyzer и проверить полученные сервером заголовок, путь и байты

**Ожидаемый результат:** Ожидаются application/pdf, путь с documentId и исходный DocumentAnalysis.

### TestIntegrationRejectStoragePathTraversal

Очистка не выходит за пределы хранилища

`alpha.business.insight.core.document.DocumentStorageTest#rejectsCleanupPathsOutsideStorage`

1. Передать относительный путь к соседнему файлу
2. Проверить тип результата

**Ожидаемый результат:** Ожидается IllegalArgumentException.

### TestIntegrationReadLegacyFlatPdf

Старый плоский путь остаётся читаемым

`alpha.business.insight.core.document.DocumentStorageTest#stillReadsPreviouslyUploadedFlatFiles`

1. Записать PDF в корень под именем UUID
2. Прочитать документ через DocumentStorage

**Ожидаемый результат:** Ожидаются исходные байты.

### TestIntegrationStorePdfByUuidPrefix

Хранилище распределяет PDF по первым шести символам UUID

`alpha.business.insight.core.document.DocumentStorageTest#storesPdfInThreeDirectoriesFromFirstSixUuidCharacters`

1. Записать PDF и прочитать его из вложенного каталога
2. Удалить файл по вычисляемому относительному пути

**Ожидаемый результат:** Ожидаются исходные байты и отсутствие файла после удаления.

### TestIntegrationConcurrentWritesKeepSingleWinner

Конкурентная запись одного UUID сохраняет единственный полный PDF

`alpha.business.insight.core.document.DocumentStorageConcurrencyTest#TestIntegrationConcurrentWritesKeepSingleWinner`

1. Одновременно записать два набора синтетических байтов по одному UUID
2. Проверить один успешный исход, отказ FileAlreadyExistsException, содержимое победителя и число файлов

**Ожидаемый результат:** Ожидается один полный файл без смешивания байтов; проверяется файловое production-хранилище без HTTP и AI.

### TestIntegrationConcurrentDocumentsKeepOwnBytes

Разные UUID с общим префиксом сохраняют независимые PDF

`alpha.business.insight.core.document.DocumentStorageConcurrencyTest#TestIntegrationConcurrentDocumentsKeepOwnBytes`

1. Одновременно записать синтетические PDF по разным UUID с общим префиксом
2. Проверить байты обоих файлов и число файлов

**Ожидаемый результат:** Ожидаются две независимые полные записи в общем каталоге; проверяется файловое production-хранилище без HTTP и AI.

### TestIntegrationIsolateConcurrentUploads

Параллельные загрузки получают независимые UUID документов и чанков

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#concurrentUploadsKeepDocumentAndChunkIdsIndependent`

1. Запустить четыре загрузки одновременно
2. Сверить название каждого документа и точки Qdrant

**Ожидаемый результат:** Ожидаются четыре сохранённых документа с отдельными точками.

### TestIntegrationRejectInvalidMlResponses

Некорректный ответ ML отменяет загрузку

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#invalidMlResponseCancelsUpload`

1. Для каждого fault-сценария вернуть неверный ID, ordinal, размерность, NaN, пустой список или исключение
2. Проверить HTTP ответ и побочные эффекты

**Ожидаемый результат:** Ожидаются 502, отсутствие метаданных и очищенный файловый оригинал.

### TestIntegrationCleanArtifactsAfterMetadataFailure

Ошибка фиксации метаданных компенсирует внешние записи

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#metadataCommitFailureDeletesAlreadyIndexedChunksAndOriginal`

1. Создать отложенный триггер, завершающий вставку документа ошибкой
2. Загрузить документ и проверить очистку всех хранилищ

**Ожидаемый результат:** Ожидается отсутствие записи, точки Qdrant и исходного файла.

### TestIntegrationPreserveChunkPagesAndIds

Несколько чанков сохраняют страницы и независимые идентификаторы

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#multipleChunksPreserveTheirTextsPagesAndIndependentIds`

1. Вернуть анализ с двумя страницами и загрузить PDF
2. Прочитать второй чанк по его ID

**Ожидаемый результат:** Ожидаются два чанка, страница 3 и текст соответствующего фрагмента.

### TestIntegrationDocumentOpenApiContract

OpenAPI публикует безопасную схему метаданных

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#openApiDescribesMetadataWithoutPostgresFragmentFields`

1. Получить OpenAPI через MockMvc
2. Проверить описания размера, SHA-256, количества чанков и отсутствие внутренних полей

**Ожидаемый результат:** Ожидаются публичные поля контракта и документированное название загрузки.

### TestIntegrationCleanPartialVectorWrites

Частичная запись Qdrant компенсируется

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#partialQdrantWriteDeletesChunksAndOriginalWithoutSavingMetadata`

1. Выполнить реальную запись точки и затем смоделировать отказ
2. Проверить документы, точки Qdrant и файл

**Ожидаемый результат:** Ожидается отсутствие метаданных, чанков и оригинала.

### TestIntegrationRejectInvalidPdfUploads

Пустой, не PDF и превышающий лимит файлы отклоняются

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#rejectsEmptyNonPdfAndOversizedFileBeforeCreatingArtifacts`

1. Загрузить файлы размером 0, 3 байта и свыше 20 МБ
2. Проверить статусы, записи и файлы

**Ожидаемый результат:** Ожидаются 400, 415 и 413 без созданных артефактов.

### TestIntegrationRequireAdminAndHandleMissingDocument

Загрузка требует администратора, неизвестный документ возвращает 404

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#uploadRequiresAdminAndUnknownDocumentReturns404`

1. Выполнить загрузку без токена и с ролью директора
2. Запросить неизвестный UUID с токеном директора

**Ожидаемый результат:** Ожидаются 401, 403 и 404 без новых документов.

### TestIntegrationPersistMetadataAndIndexChunks

PDF, метаданные и vector payload распределяются по хранилищам

`alpha.business.insight.core.document.DocumentUploadIntegrationTest#uploadStoresOnlyMetadataInPostgresAndTextAlongsideVectorInQdrant`

1. Загрузить PDF как системный администратор
2. Проверить PostgreSQL, оригинал, Qdrant payload/vector и ответы на чтение для обеих ролей

**Ожидаемый результат:** Ожидаются метаданные в PostgreSQL, оригинал в файловом хранилище и помеченный ML STUB чанк в Qdrant.

### TestIntegrationPersistAnalysisGraphAndReload

Сущности события и анализа сохраняют данные и связи

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#givenAnalysisGraph_whenPersistAndReload_thenPreserveDataAndRelations`

1. Сохранить event, insight, step, диалог, feedback и доступ пользователя к компании
2. Очистить persistence context и загрузить сущности повторно

**Ожидаемый результат:** Ожидаются исходные JSONB, enum, связи, суммы и текстовые поля.

### TestIntegrationEnforceInsightCacheVersion

Ключ кэша допускает новую версию материала

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#givenCachedInsight_whenInsertVersion_thenEnforceCacheKey`

1. Создать событие и инсайты для версий 1 и 2
2. Повторно вставить версию 1

**Ожидаемый результат:** Ожидаются две записи и ограничение уникальности для повторной версии.

### TestIntegrationKeepChunksOutOfPostgres

Чанки и очередь очистки не хранятся в PostgreSQL

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#whenInspectSchema_thenChunksAreNotStoredInPostgres`

1. Проверить прикладную схему таблиц
2. Найти fragments, facts и document_upload_cleanup

**Ожидаемый результат:** Ожидается отсутствие всех трёх таблиц.

### TestIntegrationDocumentDatabaseSchemaComments

Таблицы и колонки прикладной схемы документированы

`alpha.business.insight.core.migration.AnalysisSchemaIntegrationTest#whenInspectSchema_thenEveryTableAndColumnHasComment`

1. Проверить комментарии таблиц и колонок в системном каталоге PostgreSQL
2. Исключить только системные таблицы Liquibase

**Ожидаемый результат:** Ожидаются комментарии у каждой прикладной таблицы и колонки.

### TestIntegrationImportsPackagedCsvOnce

Комплектные CSV мигрируют ровно один раз

`alpha.business.insight.core.migration.CsvStartupMigrationIntegrationTest$ApplicationStartup#givenPackagedCsv_whenStartAndRestartApplication_thenImportOnceAndPreserveChanges`

1. Запустить приложение на чистой схеме и проверить контрольные объёмы данных
2. Изменить импортированные значения и перезапустить приложение

**Ожидаемый результат:** Ожидается неизменный changeset, сохранённые ручные правки и прежнее число платежей.

### TestIntegrationRollsBackRejectedCsvRows

Неудачная миграция CSV откатывается и повторяется после исправления

`alpha.business.insight.core.migration.CsvStartupMigrationIntegrationTest$MigrationExecution#givenRejectedCsvRows_whenMigrate_thenRollbackAndAllowRetry`

1. Запустить changeset с ошибочными строками и проверить отсутствие частичных таблиц
2. Исправить тестовые файлы и выполнить миграцию повторно

**Ожидаемый результат:** Ожидается первая ошибка без отметки changeset и успешный повтор с двумя компаниями, связью и платежом.

### TestIntegrationAllowSystemAdminRoute

Системный администратор открывает административный маршрут

`alpha.business.insight.core.security.SecurityConfigTest#givenAdmin_whenAdminRequest_thenAllowed`

1. Выдать JWT SYSTEM_ADMIN
2. Запросить /admin/check

**Ожидаемый результат:** Ожидается HTTP 200.

### TestIntegrationRecognizeApplicationRoles

Поддерживаемые роли видят идентификатор и имя пользователя

`alpha.business.insight.core.security.SecurityConfigTest#givenApplicationRole_whenGetMe_thenReturnIdentity`

1. Для SYSTEM_ADMIN и COMPANY_DIRECTOR выдать тестовый JWT
2. Запросить auth/me

**Ожидаемый результат:** Ожидаются HTTP 200, subject, имя demo и переданная роль.

### TestIntegrationDenyDirectorAdminRoute

Роль директора не открывает административный маршрут

`alpha.business.insight.core.security.SecurityConfigTest#givenDirector_whenAdminRequest_thenForbidden`

1. Выдать JWT COMPANY_DIRECTOR
2. Запросить /admin/check

**Ожидаемый результат:** Ожидается 403.

### TestIntegrationRejectInvalidJwt

JWT с ошибкой декодирования отклоняется

`alpha.business.insight.core.security.SecurityConfigTest#givenInvalidToken_whenRequest_thenUnauthorized`

1. Настроить декодер на BadJwtException
2. Выполнить запрос с bearer invalid

**Ожидаемый результат:** Ожидается 401.

### TestIntegrationRejectUnknownRoles

JWT без разрешённой роли запрещён

`alpha.business.insight.core.security.SecurityConfigTest#givenNoApplicationRole_whenGetMe_thenForbidden`

1. Проверить JWT без ролей и с OTHER_ROLE
2. Запросить auth/me

**Ожидаемый результат:** Ожидается 403 для обоих вариантов.

### TestIntegrationPublicOpenApiYaml

OpenAPI YAML доступен анонимно

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetOpenApiYaml_thenReturnSpecification`

1. Запросить YAML endpoint
2. Проверить наличие маршрута auth/me

**Ожидаемый результат:** Ожидается HTTP 200 и спецификация API.

### TestIntegrationPublicOpenApi

OpenAPI доступен без авторизации и описывает вход Keycloak

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetOpenApi_thenReturnApiWithKeycloakLogin`

1. Запросить публичную OpenAPI спецификацию
2. Проверить серверный префикс, маршруты, OAuth URLs и scope

**Ожидаемый результат:** Ожидается схема OAuth2 с authorization code и scope openid.

### TestIntegrationPublicSwaggerUi

Swagger UI и его конфигурация доступны без токена

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenGetSwaggerUi_thenReturnUiAndConfiguration`

1. Получить страницу, redirect, initializer, OAuth redirect и swagger config
2. Проверить адрес API и настройки клиента с PKCE

**Ожидаемый результат:** Ожидаются открытые страницы и clientId alfa-backend с prompt login.

### TestIntegrationRequireJwt

Защищённые маршруты требуют JWT

`alpha.business.insight.core.security.SecurityConfigTest#givenNoToken_whenRequest_thenUnauthorized`

1. Запросить auth/me и административный маршрут без токена
2. Проверить статусы

**Ожидаемый результат:** Ожидаются 401 для обоих маршрутов.

### TestIntegrationRejectStringEmbedding

Feign отклоняет строковую координату embedding

`alpha.business.insight.core.qa.documents.integration.DocumentAnalyzerJsonContractTest#TestIntegrationRejectStringEmbedding`

1. Запустить локальный HTTP endpoint на случайном loopback-порту и подготовить валидный stub-v1 JSON с одной строковой координатой
2. Передать синтетический PDF через production Feign DocumentAnalyzer

**Ожидаемый результат:** Ожидается DecodeException; JSON с числовой строкой не принимается согласно ML-контракту

### TestIntegrationCreateAndReadEvent

Создание события с разными типами источников

`alpha.business.insight.core.event.EventApiIntegrationTest#givenAdmin_whenCreateEvent_thenPersistAndReturnReadableEvent`

1. Отправить запрос от системного администратора для TEXT, URL и PDF.
2. Проверить поля ответа, начальный статус и версию материалов.
3. Сверить запись PostgreSQL, Location и чтение события директором.

**Ожидаемый результат:** Событие сохранено с PROCESSING и версией 1; ответ и запись БД согласованы, Location ведёт на созданное событие.

### TestIntegrationIgnoreClientEventMetadata

Системные поля события задаёт сервер

`alpha.business.insight.core.event.EventApiIntegrationTest#givenClientSuppliedMetadata_whenCreate_thenUseServerMetadata`

1. Отправить тело с клиентскими id, status, createdAt и materialsVersion.
2. Проверить выданные сервером значения.

**Ожидаемый результат:** Сервер игнорирует клиентские системные поля и создаёт событие со своим id, временем, статусом PROCESSING и версией 1.

### TestIntegrationRejectInvalidEventBodies

Отклонение некорректных тел события

`alpha.business.insight.core.event.EventApiIntegrationTest#givenInvalidBody_whenCreateEvent_thenBadRequestWithoutSaving`

1. Отправить отсутствующие, пустые, неверно типизированные и недопустимые поля события.
2. Проверить HTTP 400 и отсутствие записи в БД.

**Ожидаемый результат:** Каждое некорректное тело получает 400; событие не сохраняется.

### TestIntegrationRejectEmptyEventBody

Отклонение запроса без тела

`alpha.business.insight.core.event.EventApiIntegrationTest#givenNoBody_whenCreate_thenBadRequest`

1. Отправить POST создания события без тела.

**Ожидаемый результат:** Ответ 400, событие не создаётся.

### TestIntegrationRequireAdminForEventCreation

Создание события доступно администратору

`alpha.business.insight.core.event.EventApiIntegrationTest#givenNonAdmin_whenCreateEvent_thenForbiddenWithoutSaving`

1. Отправить запросы директора и роли без прав.
2. Проверить 403 и число сохранённых событий.

**Ожидаемый результат:** Неадминистратор получает 403; событие не сохраняется.

### TestIntegrationRequireAuthenticationForEvents

Авторизация для API событий

`alpha.business.insight.core.event.EventApiIntegrationTest#givenNoToken_whenAccessEvents_thenUnauthorized`

1. Без JWT вызвать создание, список и чтение события.

**Ожидаемый результат:** Все закрытые endpoints возвращают 401.

### TestIntegrationListEventsNewestFirst

Сортировка событий от новых к старым

`alpha.business.insight.core.event.EventApiIntegrationTest#givenEvents_whenListAndRead_thenReturnNewestFirstWithStableOrder`

1. Создать набор событий с разными датами и одинаковой датой.
2. Запросить список и прочитать конкретное событие от имени администратора и директора.

**Ожидаемый результат:** Список упорядочен по времени создания по убыванию, при равенстве даты порядок стабилен по id; чтение возвращает сохранённые поля.

### TestIntegrationReturnEmptyEventList

Пустой список событий

`alpha.business.insight.core.event.EventApiIntegrationTest#givenNoEvents_whenList_thenReturnEmptyArray`

1. Запросить список от имени директора при отсутствии событий.

**Ожидаемый результат:** API возвращает HTTP 200 и пустой JSON-массив.

### TestIntegrationReturnEventNotFound

Чтение неизвестного события

`alpha.business.insight.core.event.EventApiIntegrationTest#givenUnknownEvent_whenGet_thenNotFound`

1. Запросить отсутствующий UUID от имени администратора.

**Ожидаемый результат:** API возвращает 404.

### TestIntegrationRejectInvalidEventId

Некорректный UUID события

`alpha.business.insight.core.event.EventApiIntegrationTest#givenInvalidId_whenGet_thenBadRequest`

1. Запросить событие с невалидным идентификатором.

**Ожидаемый результат:** API возвращает 400.

### TestIntegrationRejectUnauthorizedEventReader

Чтение событий ограничено ролями

`alpha.business.insight.core.event.EventApiIntegrationTest#givenUnrelatedRole_whenRead_thenForbidden`

1. Запросить список и конкретное событие с посторонней ролью.

**Ожидаемый результат:** Оба запроса возвращают 403.

### TestIntegrationReuseCachedInsight

Кэширование инсайта и повторное чтение

`alpha.business.insight.core.insight.InsightApiIntegrationTest#repeatedPostAndGetReturnSameInsightWithoutNewLlmCalls`

1. Сгенерировать инсайт POST, повторить POST и прочитать GET.
2. Проверить JSON-поля, одинаковый результат и число обращений LLM.

**Ожидаемый результат:** Повторные запросы возвращают сохранённый результат без дополнительных вызовов LLM; обязательные поля контракта присутствуют.

### TestIntegrationRegenerateInsightForNewMaterials

Инвалидация инсайта при новой версии материалов

`alpha.business.insight.core.insight.InsightApiIntegrationTest#newMaterialsVersionRequiresNewGeneration`

1. Сгенерировать инсайт, увеличить materials_version события и вызвать GET, затем POST.
2. Сверить идентификаторы и количество записей.

**Ожидаемый результат:** Старый кэш для текущей версии недоступен; POST создаёт новую запись и trace_id.

### TestIntegrationShareConcurrentInsightGeneration

Единая генерация для параллельных запросов

`alpha.business.insight.core.insight.InsightApiIntegrationTest#concurrentPostsShareOneGeneration`

1. Параллельно отправить два POST для одной компании и события.
2. Сравнить ответы и число сохранённых записей.

**Ожидаемый результат:** Оба запроса возвращают один результат; сохраняется одна генерация.

### TestIntegrationReturnMissingInsight

Чтение отсутствующего сохранённого инсайта

`alpha.business.insight.core.insight.InsightApiIntegrationTest#missingSavedInsightReturns404`

1. Вызвать GET для компании и события без сохранённого инсайта.

**Ожидаемый результат:** API возвращает 404.

### TestIntegrationEnforceCompanyInsightAccess

Доступ директора ограничен правами компании

`alpha.business.insight.core.insight.InsightApiIntegrationTest#rejectsAnonymousAndDirectorWithoutCompanyAccess`

1. Без токена вызвать POST; затем удалить грант директора и вызвать POST и GET.

**Ожидаемый результат:** Аноним получает 401; директор без доступа получает 403 на чтение и генерацию.

### TestIntegrationAllowAdminInsightGeneration

Генерация инсайта системным администратором

`alpha.business.insight.core.insight.InsightApiIntegrationTest#administratorCanGenerateWithoutCompanyGrant`

1. Удалить грант пользователя на компанию и отправить POST от администратора.

**Ожидаемый результат:** Системный администратор получает успешный ответ без гранта компании.

### TestIntegrationReturnUnknownInsightTarget

Неизвестная компания или событие

`alpha.business.insight.core.insight.InsightApiIntegrationTest#unknownCompanyOrEventReturns404`

1. Запросить генерацию для неизвестной компании, затем для неизвестного события.

**Ожидаемый результат:** Оба запроса возвращают 404.

### TestIntegrationValidateInsightSources

Валидация источников инсайта

`alpha.business.insight.core.insight.InsightApiIntegrationTest#qdrantFragmentsReachPromptButSyntheticBankViewIsRemoved`

1. Индексировать синтетический банковский фрагмент и подменить ответ модели неподтверждённой позицией и фактом.
2. Проверить результат и сохранённую запись.

**Ожидаемый результат:** Синтетические/неподтверждённые факты и позиция банка удалены; ссылки на источники содержат метаданные, исходный текст фрагмента не попадает в сохранённый payload.

### TestIntegrationRetryInsightAfterLlmFailure

Повтор генерации после ошибки LLM

`alpha.business.insight.core.insight.InsightApiIntegrationTest#failedLlmDoesNotCacheIncompleteInsight`

1. Смоделировать ошибку LLM при revision.
2. Проверить ответ, отсутствие кэша и повторить запрос после восстановления клиента.

**Ожидаемый результат:** Возвращается 503 с LLM_UNAVAILABLE и retryable=true; неполный инсайт не сохраняется, повторная генерация успешна.

### TestIntegrationRetryInsightAfterMlFailure

Повтор генерации после ошибки ML

`alpha.business.insight.core.insight.InsightApiIntegrationTest#failedMlDoesNotSaveInsightAndCanBeRetried`

1. Смоделировать ошибку embedding, проверить ответ и отсутствие сохранения.
2. Восстановить ML-заглушку и повторить POST.

**Ожидаемый результат:** Возвращается 503 с ML_UNAVAILABLE и retryable=true; данные ошибки не сохраняются как инсайт, повтор успешен.

### TestIntegrationRetryInsightAfterQdrantFailure

Повтор генерации после ошибки Qdrant

`alpha.business.insight.core.insight.InsightApiIntegrationTest#failedQdrantDoesNotSaveInsightAndCanBeRetried`

1. Приостановить Qdrant, отправить POST и измерить задержку.
2. Проверить ответ и отсутствие записи; возобновить Qdrant и повторить.

**Ожидаемый результат:** Возвращается 503 с QDRANT_UNAVAILABLE и retryable=true в пределах таймаута; запись не сохраняется, повтор успешен.

### TestIntegrationFormatBusinessApiError

Формат бизнес-ошибки API

`alpha.business.insight.core.controller.ApiExceptionHandlerTest#businessErrorPreservesStatusAndRussianMessage`

1. Вызвать endpoint, выбрасывающий NOT_FOUND с русским сообщением.
2. Проверить код, сообщение, retryable и отсутствие detail.

**Ожидаемый результат:** Ответ сохраняет статус 404, код NOT_FOUND и сообщение; retryable=false, внутренний detail отсутствует.

### TestIntegrationHideUnexpectedErrorDetails

Сокрытие внутренних ошибок API

`alpha.business.insight.core.controller.ApiExceptionHandlerTest#unexpectedErrorDoesNotExposeDetails`

1. Вызвать endpoint с неожиданным исключением.
2. Проверить стабильный формат ответа и отсутствие исходной детали.

**Ожидаемый результат:** Ответ 500 содержит INTERNAL_ERROR и общее сообщение, не раскрывая secret.

### TestIntegrationFormatOversizedUploadError

Единый формат ошибки размера загрузки

`alpha.business.insight.core.controller.ApiExceptionHandlerTest#oversizedMultipartUsesSameFormat`

1. Вызвать обработчик ошибки загрузки свыше лимита.
2. Проверить статус, код, сообщение и retryable.

**Ожидаемый результат:** Ответ 413 содержит PAYLOAD_TOO_LARGE, русское сообщение о лимите 20 МБ и retryable=false.

### TestIntegrationFormatMalformedRequestError

Ошибки некорректного JSON и UUID

`alpha.business.insight.core.controller.ApiExceptionHandlerTest#malformedJsonAndInvalidUuidUseBadRequest`

1. Отправить синтаксически неверный JSON и невалидный UUID.
2. Проверить 400 и код BAD_REQUEST.

**Ожидаемый результат:** Обе ошибки возвращают 400 с BAD_REQUEST; retryable=false для JSON.

### TestIntegrationPreserveAllowHeader

Формат ошибки неподдерживаемого метода

`alpha.business.insight.core.controller.ApiExceptionHandlerTest#unsupportedMethodPreservesAllowHeader`

1. Вызвать POST-only endpoint методом DELETE.
2. Проверить 405, Allow и код ошибки.

**Ожидаемый результат:** Ответ 405 содержит Allow: POST и METHOD_NOT_ALLOWED.

### TestIntegrationCallRemoteEmbeddingAndValidateModel

Удалённый embedding и проверка модели

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#feignCallsRemoteEmbeddingAndValidatesModel`

1. Поднять локальный endpoint embeddings и запустить DocumentAnalyzer с ml.stub=false.
2. Проверить переданный текст, размер/значения вектора и восстановление после 503.

**Ожидаемый результат:** Клиент получает ожидаемый вектор и корректно пробрасывает FeignException при ошибке сервиса; следующий запрос снова успешен.

### TestIntegrationTimeoutMlCallWithoutRetry

Тайм-аут ML вызова без повтора

`alpha.business.insight.core.document.DocumentAnalyzerFeignTest#stalledMlCallTimesOutWithoutRetry`

1. Поднять локальный endpoint, удерживающий ответ.
2. Проверить исключение, максимальную задержку и число запросов.

**Ожидаемый результат:** Вызов заканчивается по timeout менее чем за три секунды и не повторяется.

### TestIntegrationUploadPdfThroughRealPython

Настоящий Python извлекает страницы PDF и backend сохраняет результат

`alpha.business.insight.core.qa.documents.integration.RealMlDocumentIntegrationTest#TestIntegrationUploadPdfThroughRealPython`

1. Получить JWT администратора и загрузить PDF через POST /api/admin/documents
2. Прочитать метаданные, чанки Qdrant и скачать оригинал через API

**Ожидаемый результат:** HTTP 201; реальный текст и страницы PDF в Qdrant, метаданные в PostgreSQL, скачанные байты совпадают с исходными; embeddings явно stub-v1.

### TestIntegrationRejectTextlessPdfWithoutArtifacts

Отказ анализа PDF без текста не сохраняет документ

`alpha.business.insight.core.qa.documents.integration.RealMlDocumentIntegrationTest#TestIntegrationRejectTextlessPdfWithoutArtifacts`

1. Загрузить физически корректный PDF без текстового слоя через реальный Python
2. Проверить отсутствие метаданных и временного оригинала и статус API

**Ожидаемый результат:** Нет сохранённого документа и оригинала; Java возвращает 502 по контракту ML, а не ложный успех. Расхождение 502/503 фиксируется отдельно.

### TestIntegrationIndexPublishedBankReport

Опубликованный PDF ЦБ индексируется с сохранением страниц и фактов

`alpha.business.insight.core.qa.documents.integration.RealMlDocumentIntegrationTest#TestIntegrationIndexPublishedBankReport`

1. Загрузить оригинальный PDF Банка России через backend и настоящий Python
2. Проверить PostgreSQL, SHA-256 и чанки Qdrant с числами на физической странице 2
3. Скачать оригинал через API

**Ожидаемый результат:** HTTP 201; исходный SHA-256 сохранён, значения 12,7% и 9,8% относятся к странице 2, скачанный PDF побайтно совпадает; embeddings явно stub-v1.

## E2E

### TestE2ECompanyAccessRequiresGrant

Чтение компании требует связи пользователя с компанией

`alpha.business.insight.core.qa.company.CompanyContextHttpE2eTest#TestE2ECompanyAccessRequiresGrant`

1. Создать компанию без строки user_company_access и получить токен директора
2. Запросить контекст и сравнить статус с требованием видения, раздел 2.4

**Ожидаемый результат:** Ожидается 403; текущий код возвращает 200 и тест отмечен known-defect до исправления.

### TestE2EDocumentAccessRules

Запись доступна только администратору, чтение требует JWT

`alpha.business.insight.core.qa.documents.DocumentUploadHttpE2eTest#TestE2EDocumentAccessRules`

1. Отправить загрузку с JWT директора и запросить документ без токена
2. Проверить статусы ответов

**Ожидаемый результат:** Ожидаются 403 для директора и 401 для анонимного запроса.

## Smoke

### TestSmokeCompanyContextCalculations

HTTP контекст отражает профиль, группу и платежи PostgreSQL

`alpha.business.insight.core.qa.company.CompanyContextSmokeTest#TestSmokeCompanyContextCalculations`

1. Создать изолированные записи компании, участника группы и встречных платежей
2. Получить настоящий токен Keycloak и запросить API

**Ожидаемый результат:** Ожидаются профиль, две компании в группе, оборот и доля контрагента равные 500 и 1.

### TestSmokePackagedCsvProfile

Профиль из комплектного CSV доступен через реальный API

`alpha.business.insight.core.qa.dataimport.CsvPackagedSmokeTest#TestSmokePackagedCsvProfile`

1. Получить токен директора Keycloak и временно выдать доступ к U_67
2. Запросить контекст компании по HTTP

**Ожидаемый результат:** Ожидаются status 200, профиль AVAILABLE с ОКВЭД 46.51 и региональными данными из CSV.

### TestSmokePdfUploadRoundTrip

Реальная загрузка сохраняет PDF, метаданные и stub-чанк

`alpha.business.insight.core.qa.documents.DocumentUploadSmokeTest#TestSmokePdfUploadRoundTrip`

1. Получить реальные JWT администратора и директора, затем проверить запреты и загрузить валидный PDF администратором
2. Получить метаданные и оригинал через API, сверить байты и прочитать payload/vector в Qdrant

**Ожидаемый результат:** Ожидается сохранённый документ и один чанк; stub=true, stub-v1 и [ML STUB] явно показывают синтетический результат без анализа содержимого PDF.

### TestSmokeKeycloakRealmRoles

Реальные токены Keycloak проходят аутентификацию и содержат роли

`alpha.business.insight.core.qa.security.BackendAuthorizationSmokeTest#TestSmokeKeycloakRealmRoles`

1. Запросить /auth/me без токена
2. Получить токены директора и администратора через тестовый realm и проверить ответы

**Ожидаемый результат:** Ожидается 401 без токена и роли COMPANY_DIRECTOR и SYSTEM_ADMIN в реальных JWT.
