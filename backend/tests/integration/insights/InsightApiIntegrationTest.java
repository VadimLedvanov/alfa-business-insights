package alpha.business.insight.core.insight;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.DocumentChunk;
import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.qameta.allure.Allure;
import io.qameta.allure.Step;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Проверяет генерацию, хранение и доступ к инсайтам через API. */
@Tag("integration")
@Tag("insights")
@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
            "spring.ai.model.chat=none",
            "ml.stub=true",
            "spring.datasource.hikari.maximum-pool-size=4",
            "spring.datasource.hikari.connection-timeout=1000"
        })
@AutoConfigureMockMvc
@Testcontainers
class InsightApiIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Container
    static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:v1.19.0")
                    .withExposedPorts(6333, 6334)
                    .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    /** Настраивает адреса PostgreSQL и Qdrant из контейнеров. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qdrant.host", QDRANT::getHost);
        registry.add("qdrant.grpc-port", () -> QDRANT.getMappedPort(6334));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean LlmClient llm;
    @Autowired DocumentVectorStore vectors;
    @Autowired QdrantClient qdrant;
    @Autowired ApplicationContext applicationContext;
    @MockitoSpyBean StubDocumentAnalyzer analyzer;
    @Autowired DocumentRepository documents;
    String company;
    UUID event;

    /** Создаёт компанию, событие и грант перед каждым тестом. */
    @BeforeEach
    void prepare() {
        company = "insight_" + UUID.randomUUID();
        event = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO companies (company_id, profile_status) VALUES (?, 'MISSING')",
                company);
        jdbc.update(
                "INSERT INTO events (id, title, source_type, source_ref, status) "
                        + "VALUES (?, 'Снижение ставки', 'TEXT', 'Ставка снижена', 'READY')",
                event);
        jdbc.update(
                "INSERT INTO user_company_access (user_id, company_id) VALUES ('director', ?)",
                company);
    }

    /** Повторно читает сохранённый инсайт без новых вызовов LLM. */
    @Test
    @Step("Повторно читает сохранённый инсайт без новых вызовов LLM")
    void repeatedPostAndGetReturnSameInsightWithoutNewLlmCalls() throws Exception {
        // Действие: создаёт инсайт и фиксирует число вызовов LLM после первой генерации.
        JsonNode first = generate();
        long llmCallsAfterInitialGeneration = llmCallCount();

        // Проверки: повторные POST и GET возвращают сохранённый результат без новых вызовов LLM.
        assertThat(first.path("trace_id").asString()).isNotBlank();
        assertThat(first.path("bank_view").isArray()).isTrue();
        assertThat(first.path("bank_view").size()).isZero();
        assertThat(first.path("missing_data").toString()).contains("банк");
        assertThat(first.path("cost_rub").decimalValue()).isZero();
        for (String field :
                new String[] {
                    "facts",
                    "ai_inference",
                    "hypotheses",
                    "features_used",
                    "risks",
                    "opportunities",
                    "actions",
                    "missing_data",
                    "sources"
                }) {
            assertThat(first.path(field).isArray()).as(field).isTrue();
        }
        assertThat(generate()).isEqualTo(first);
        JsonNode read =
                mapper.readTree(
                        request()
                                .postProcessors(director())
                                .when()
                                .get(path())
                                .then()
                                .statusCode(200)
                                .extract()
                                .response()
                                .asString());
        assertThat(read).isEqualTo(first);
        assertThat(llmCallCount())
                .as("число вызовов LLM после повторных POST и GET")
                .isEqualTo(llmCallsAfterInitialGeneration);
    }

    /** Создаёт инсайт для новой версии материалов. */
    @Test
    @Step("Создаёт инсайт для новой версии материалов")
    void newMaterialsVersionRequiresNewGeneration() throws Exception {
        // Подготовка: сохраняет инсайт для текущей версии материалов.
        JsonNode first = generate();

        // Действие: меняет версию материалов и запрашивает инсайт до повторной генерации.
        jdbc.update("UPDATE events SET materials_version=2 WHERE id=?", event);
        request().postProcessors(director()).when().get(path()).then().statusCode(404);

        // Проверки: новая версия создаёт отдельный инсайт.
        JsonNode second = generate();
        assertThat(second.path("id")).isNotEqualTo(first.path("id"));
        assertThat(second.path("trace_id")).isNotEqualTo(first.path("trace_id"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM insights WHERE company_id=?",
                                Integer.class,
                                company))
                .isEqualTo(2);
    }

    /** Объединяет параллельную генерацию инсайта. */
    @Test
    @Step("Объединяет параллельную генерацию инсайта")
    void concurrentPostsShareOneGeneration() throws Exception {
        // Подготовка: синхронизирует два запроса и удерживает первый вызов до блокировки второй
        // транзакции.
        CountDownLatch requestsReady = new CountDownLatch(2);
        CountDownLatch startRequests = new CountDownLatch(1);
        AtomicBoolean firstPlan = new AtomicBoolean(true);
        String testCaseUuid = Allure.getLifecycle().getCurrentTestCase().orElseThrow();
        doAnswer(
                        invocation -> {
                            if (firstPlan.compareAndSet(true, false)) {
                                int holderPid =
                                        jdbc.queryForObject(
                                                "SELECT pg_backend_pid()", Integer.class);
                                awaitConcurrentTransaction(holderPid);
                            }
                            return invocation.callRealMethod();
                        })
                .when(llm)
                .complete(eq("plan"), any());

        // Действие: запускает оба запроса одновременно.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<JsonNode> first =
                    executor.submit(
                            () -> {
                                requestsReady.countDown();
                                if (!startRequests.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError(
                                            "Параллельный старт запросов не открыт");
                                }
                                return generateWithAllureContext(testCaseUuid);
                            });
            Future<JsonNode> second =
                    executor.submit(
                            () -> {
                                requestsReady.countDown();
                                if (!startRequests.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError(
                                            "Параллельный старт запросов не открыт");
                                }
                                return generateWithAllureContext(testCaseUuid);
                            });
            boolean bothReady = requestsReady.await(10, TimeUnit.SECONDS);
            startRequests.countDown();
            assertThat(bothReady).isTrue();
            JsonNode firstResult = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(firstResult);
        } finally {
            startRequests.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("завершение параллельных запросов")
                    .isTrue();
        }
        // Проверки: оба ответа совпадают, а инсайт и вызов планирования созданы один раз.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM insights WHERE company_id=?",
                                Integer.class,
                                company))
                .isEqualTo(1);
        verify(llm, times(1)).complete(eq("plan"), any());
    }

    /** Дожидается второй транзакции, заблокированной на строке события. */
    private void awaitConcurrentTransaction(int holderPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int lockWaiters = 0;
        while (System.nanoTime() < deadline) {
            lockWaiters =
                    jdbc.queryForObject(
                            """
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> ?
                      AND wait_event_type = 'Lock'
                      AND ? = ANY(pg_blocking_pids(pid))
                    """,
                            Integer.class,
                            holderPid,
                            holderPid);
            if (lockWaiters > 0) return;
            Thread.sleep(25);
        }
        assertThat(lockWaiters)
                .as("concurrent request waiting for the event row lock")
                .isGreaterThan(0);
    }

    /** Привязывает шаги параллельного HTTP-запроса к текущему тесту Allure. */
    private JsonNode generateWithAllureContext(String testCaseUuid) throws Exception {
        Allure.getLifecycle().setCurrentTestCase(testCaseUuid);
        return Allure.step("Выполнить параллельную генерацию инсайта", this::generate);
    }

    /** Подсчитывает вызовы метода complete у текущего spy клиента LLM. */
    private long llmCallCount() {
        return org.mockito.Mockito.mockingDetails(llm).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("complete"))
                .count();
    }

    /** Возвращает 404 для отсутствующего инсайта. */
    @Test
    @Step("Возвращает 404 для отсутствующего инсайта")
    void missingSavedInsightReturns404() throws Exception {
        request().postProcessors(director()).when().get(path()).then().statusCode(404);
    }

    /** Проверяет авторизацию компании. */
    @Test
    @Step("Проверяет авторизацию компании")
    void rejectsAnonymousAndDirectorWithoutCompanyAccess() throws Exception {
        // Действие и проверка: анонимный запрос отклоняется.
        request().when().post(path()).then().statusCode(401);

        // Действие: удаляет грант директора.
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);

        // Проверки: директор без гранта не может создавать или читать инсайт.
        request().postProcessors(director()).when().post(path()).then().statusCode(403);
        request().postProcessors(director()).when().get(path()).then().statusCode(403);
        request()
                .postProcessors(director())
                .when()
                .get("/api/companies/" + company + "/events/top")
                .then()
                .statusCode(403);
        request()
                .postProcessors(director())
                .when()
                .get("/api/companies/" + company + "/events/" + event + "/messages")
                .then()
                .statusCode(403);
    }

    /** Сохранённый разбор и его история остаются недоступны другому директору. */
    @Test
    void savedInsightDoesNotBypassCompanyAccess() throws Exception {
        request().postProcessors(director()).when().post(path()).then().statusCode(200);
        var otherDirector =
                jwt().jwt(token -> token.subject("other-director"))
                        .authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"));

        request().postProcessors(otherDirector).when().get(path()).then().statusCode(403);
        request().postProcessors(otherDirector).when().post(path()).then().statusCode(403);
        request()
                .postProcessors(otherDirector)
                .when()
                .get("/api/companies/" + company + "/events/" + event + "/messages")
                .then()
                .statusCode(403);

        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        request().postProcessors(director()).when().get(path()).then().statusCode(403);
        request().postProcessors(director()).when().post(path()).then().statusCode(403);
        request()
                .postProcessors(director())
                .when()
                .get("/api/companies/" + company + "/events/" + event + "/messages")
                .then()
                .statusCode(403);
    }

    /** Разрешает администратору генерацию без гранта компании. */
    @Test
    @Step("Разрешает администратору генерацию без гранта компании")
    void administratorCanGenerateWithoutCompanyGrant() throws Exception {
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        request()
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post(path())
                .then()
                .statusCode(200);
    }

    /** Возвращает 404 для неизвестной компании или события. */
    @Test
    @Step("Возвращает 404 для неизвестной компании или события")
    void unknownCompanyOrEventReturns404() throws Exception {
        request()
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/companies/unknown/events/" + event + "/insight")
                .then()
                .statusCode(404);
        event = UUID.randomUUID();
        request().postProcessors(director()).when().post(path()).then().statusCode(404);
    }

    /** Проверяет источники и удаление неподтверждённой позиции банка. */
    @Test
    @Step("Проверяет источники и удаление неподтверждённой позиции банка")
    void qdrantFragmentsReachPromptButSyntheticBankViewIsRemoved() throws Exception {
        // Подготовка: индексирует PDF и подмешивает в ответ модели неподтверждённые данные.
        UUID documentId = UUID.randomUUID();
        DocumentAnalysis analysis = new StubDocumentAnalyzer().analyze(documentId, new byte[0]);
        vectors.index(analysis, null, System.nanoTime() + Duration.ofSeconds(10).toNanos());
        documents.saveAndFlush(
                new KnowledgeDocument(
                        documentId,
                        "Банковский PDF",
                        "test.pdf",
                        "test.pdf",
                        1,
                        "0".repeat(64),
                        Instant.now(),
                        null,
                        1,
                        1,
                        "stub-v1",
                        8,
                        true));
        String fragmentId = DocumentVectorStore.chunkId(documentId, 0).toString();
        String fragmentText = analysis.chunks().getFirst().text();
        // Подменяет ответ модели, чтобы проверить очистку ссылок и сырого текста.
        doAnswer(
                        invocation -> {
                            ObjectNode input = invocation.getArgument(1);
                            assertThat(input.path("external_data").path("fragments").toString())
                                    .contains(fragmentId, fragmentText);
                            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
                            completion
                                    .withArray("bank_view")
                                    .addObject()
                                    .put("text", "Выдуманная позиция банка")
                                    .put("fragment_id", fragmentId);
                            completion
                                    .withArray("facts")
                                    .addObject()
                                    .put("text", "Несуществующий фрагмент")
                                    .put("fragment_id", UUID.randomUUID().toString());
                            completion
                                    .withArray("sources")
                                    .addObject()
                                    .put("fragment_id", fragmentId)
                                    .put("text", fragmentText);
                            completion.putArray("fragments").addObject().put("text", fragmentText);
                            return completion;
                        })
                .when(llm)
                .complete(eq("revision"), any());
        // Действие
        JsonNode result = generate();

        // Проверки: неподтверждённые данные удалены, а без цитат список источников пуст.
        assertThat(result.path("bank_view").isEmpty()).isTrue();
        assertThat(result.path("facts").isEmpty()).isTrue();
        assertThat(result.path("sources").isArray())
                .as("sources должен присутствовать как JSON-массив: %s", result.path("sources"))
                .isTrue();
        assertThat(result.path("sources").isEmpty())
                .as("нет источников, поскольку ни одно утверждение не прошло проверку")
                .isTrue();
        assertThat(result.path("sources").findValues("text")).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM insights WHERE company_id=?",
                                String.class,
                                company))
                .doesNotContain(fragmentText, "Ставка снижена");
        assertThat(result.path("missing_data").toString()).contains("банк", "STUB");
    }

    /** Передаёт LLM только Qdrant-фрагменты с оценкой не ниже настроенного порога. */
    @Test
    @Step("Фильтрует фрагменты Qdrant по минимальной оценке сходства")
    void TestIntegrationQdrantScoreThreshold() throws Exception {
        // Подготовка: три контролируемых вектора дают оценки 0.65, 0.66 и 0.64 для запроса [1, 0].
        UUID documentId = UUID.randomUUID();
        float boundaryX = 0.65f;
        float boundaryY = (float) Math.sqrt(1 - boundaryX * boundaryX);
        float aboveX = 0.66f;
        float belowX = 0.64f;
        List<Float> query = List.of(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        UUID boundaryId = DocumentVectorStore.chunkId(documentId, 0);
        UUID aboveId = DocumentVectorStore.chunkId(documentId, 1);
        UUID belowId = DocumentVectorStore.chunkId(documentId, 2);
        List<DocumentChunk> chunks =
                List.of(
                        new DocumentChunk(
                                0,
                                1,
                                "Граничный фрагмент",
                                List.of(boundaryX, boundaryY, 0f, 0f, 0f, 0f, 0f, 0f)),
                        new DocumentChunk(
                                1,
                                2,
                                "Фрагмент выше порога",
                                List.of(
                                        aboveX,
                                        (float) Math.sqrt(1 - aboveX * aboveX),
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f)),
                        new DocumentChunk(
                                2,
                                3,
                                "Фрагмент ниже порога",
                                List.of(
                                        belowX,
                                        (float) Math.sqrt(1 - belowX * belowX),
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f)));
        DocumentAnalysis analysis = new DocumentAnalysis(documentId, 1, true, "stub-v1", 8, chunks);
        vectors.index(analysis, null, System.nanoTime() + Duration.ofSeconds(10).toNanos());
        documents.saveAndFlush(
                new KnowledgeDocument(
                        documentId,
                        "Контролируемые векторы",
                        "controlled.pdf",
                        "controlled.pdf",
                        1,
                        "0".repeat(64),
                        Instant.now(),
                        null,
                        chunks.size(),
                        1,
                        "stub-v1",
                        8,
                        true));

        // Действие: проверяет фактическую оценку границы Qdrant и затем запускает генерацию.
        Allure.step(
                "1. Сверить вход Qdrant и точную оценку граничного фрагмента",
                () -> {
                    assertThat(applicationContext.getBeanProvider(ChatModel.class).getIfAvailable())
                            .as("модель чата отключена: тест не должен вызывать внешнюю LLM")
                            .isNull();
                    List<io.qdrant.client.grpc.Points.ScoredPoint> scored =
                            qdrant.searchAsync(
                                            SearchPoints.newBuilder()
                                                    .setCollectionName(
                                                            DocumentVectorStore.STUB_COLLECTION)
                                                    .addAllVector(query)
                                                    .setFilter(
                                                            Filter.newBuilder()
                                                                    .addMust(
                                                                            matchKeyword(
                                                                                    "documentId",
                                                                                    documentId
                                                                                            .toString()))
                                                                    .build())
                                                    .setLimit(10)
                                                    .setWithPayload(
                                                            WithPayloadSelector.newBuilder()
                                                                    .setEnable(true))
                                                    .build())
                                    .get(5, TimeUnit.SECONDS);
                    Allure.addAttachment(
                            "Вход и оценки Qdrant",
                            "text/plain",
                            "query="
                                    + query
                                    + "\n"
                                    + scored.stream()
                                            .map(
                                                    point ->
                                                            point.getId().getUuid()
                                                                    + "="
                                                                    + point.getScore())
                                            .toList());
                    for (var expected :
                            java.util.Map.of(boundaryId, 0.65f, aboveId, 0.66f, belowId, 0.64f)
                                    .entrySet()) {
                        assertThat(
                                        scored.stream()
                                                .filter(
                                                        point ->
                                                                point.getId()
                                                                        .getUuid()
                                                                        .equals(
                                                                                expected.getKey()
                                                                                        .toString()))
                                                .findFirst()
                                                .orElseThrow()
                                                .getScore())
                                .as(
                                        "фактическая оценка фрагмента %s, ожидалось %s",
                                        expected.getKey(), expected.getValue())
                                .isEqualTo(expected.getValue());
                    }
                });
        doReturn(new DocumentAnalyzer.Embedding("stub-v1", 8, true, query))
                .when(analyzer)
                .embedding(any());
        java.util.Map<String, List<String>> promptFragments =
                new java.util.concurrent.ConcurrentHashMap<>();
        doAnswer(
                        invocation -> {
                            String stage = invocation.getArgument(0);
                            ObjectNode input = invocation.getArgument(1);
                            promptFragments.put(
                                    stage,
                                    input.path("external_data")
                                            .path("fragments")
                                            .findValuesAsString("fragment_id"));
                            return invocation.callRealMethod();
                        })
                .when(llm)
                .complete(anyString(), any());
        Allure.step(
                "2. Проверить фрагменты во всех промптах LLM",
                () -> {
                    Allure.addAttachment(
                            "Ожидаемые fragment_id",
                            "text/plain",
                            "граница=" + boundaryId + "\nвыше=" + aboveId + "\nниже=" + belowId);
                    generate();
                    Allure.addAttachment(
                            "Фрагменты в контексте стадий LLM",
                            "text/plain",
                            promptFragments.toString());
                    org.junit.jupiter.api.Assertions.assertAll(
                            () ->
                                    verifyThresholdContext(
                                            "draft",
                                            promptFragments.get("draft"),
                                            belowId,
                                            boundaryId,
                                            aboveId),
                            () ->
                                    verifyThresholdContext(
                                            "critic",
                                            promptFragments.get("critic"),
                                            belowId,
                                            boundaryId,
                                            aboveId),
                            () ->
                                    verifyThresholdContext(
                                            "revision",
                                            promptFragments.get("revision"),
                                            belowId,
                                            boundaryId,
                                            aboveId));
                });
    }

    /** Проверяет строгий порог в конкретной стадии, сохраняя независимые ошибки всех стадий. */
    @Step("Проверить контекст стадии {stage}: 0.64 и 0.65 исключены, 0.66 передан")
    private void verifyThresholdContext(
            String stage, List<String> fragments, UUID below, UUID boundary, UUID above) {
        org.junit.jupiter.api.Assertions.assertAll(
                () ->
                        assertThat(fragments)
                                .as("%s: оценки 0.64 и 0.65 исключены", stage)
                                .doesNotContain(below.toString(), boundary.toString()),
                () ->
                        assertThat(fragments)
                                .as("%s: оценка 0.66 передана", stage)
                                .contains(above.toString()));
    }

    /** Не сохраняет инсайт при ошибке LLM. */
    @Test
    @Step("Не сохраняет инсайт при ошибке LLM")
    void failedLlmDoesNotCacheIncompleteInsight() throws Exception {
        // Подготовка: задаёт неполный критический ответ и отказ вызова revision.
        doAnswer(
                        invocation -> {
                            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
                            completion
                                    .withArray("issues")
                                    .addObject()
                                    .put("source_fragment", "Ставка снижена");
                            return completion;
                        })
                .when(llm)
                .complete(eq("critic"), any());
        doThrow(new ComponentUnavailableException("LLM")).when(llm).complete(eq("revision"), any());
        // Действие и проверки: ошибка API не сохраняет незавершённый инсайт.
        request()
                .postProcessors(director())
                .when()
                .post(path())
                .then()
                .statusCode(503)
                .body("code", equalTo("LLM_UNAVAILABLE"))
                .body("retryable", equalTo(true));
        request().postProcessors(director()).when().get(path()).then().statusCode(404);
        doCallRealMethod().when(llm).complete(eq("revision"), any());
        generate();
    }

    /** Повторяет генерацию после ошибки ML. */
    @Test
    @Step("Повторяет генерацию после ошибки ML")
    void failedMlDoesNotSaveInsightAndCanBeRetried() throws Exception {
        // Подготовка: имитирует отказ ML-анализатора.
        doThrow(new IllegalStateException("private ML details")).when(analyzer).embed(anyString());

        // Действие и проверки: API возвращает ошибку, не сохраняя инсайт.
        request()
                .postProcessors(director())
                .when()
                .post(path())
                .then()
                .statusCode(503)
                .body("code", equalTo("ML_UNAVAILABLE"))
                .body("retryable", equalTo(true));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM insights WHERE company_id=?",
                                Long.class,
                                company))
                .isZero();
        doCallRealMethod().when(analyzer).embed(anyString());
        generate();
    }

    /** Повторяет генерацию после ошибки Qdrant. */
    @Test
    @Step("Повторяет генерацию после ошибки Qdrant")
    void failedQdrantDoesNotSaveInsightAndCanBeRetried() throws Exception {
        // Подготовка: приостанавливает контейнер Qdrant.
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(QDRANT.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            // Действие и проверки: API быстро возвращает временную ошибку без сохранения.
            request()
                    .postProcessors(director())
                    .when()
                    .post(path())
                    .then()
                    .statusCode(503)
                    .body("code", equalTo("QDRANT_UNAVAILABLE"))
                    .body("retryable", equalTo(true));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(8));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM insights WHERE company_id=?",
                                    Long.class,
                                    company))
                    .isZero();
        } finally {
            docker.unpauseContainerCmd(QDRANT.getContainerId()).exec();
        }
        generate();
    }

    private JsonNode generate() throws Exception {
        return mapper.readTree(
                request()
                        .postProcessors(director())
                        .when()
                        .post(path())
                        .then()
                        .statusCode(200)
                        .body("id", notNullValue())
                        .body("id", not(isEmptyString()))
                        .extract()
                        .response()
                        .asString());
    }

    /** Создаёт request-local спецификацию с общим API context path. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Возвращает endpoint инсайта для текущих тестовых данных. */
    private String path() {
        return "/api/companies/" + company + "/events/" + event + "/insight";
    }

    /** Создаёт JWT директора с subject, используемым в таблице грантов. */
    private RequestPostProcessor director() {
        return jwt().jwt(token -> token.subject("director"))
                .authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"));
    }
}
