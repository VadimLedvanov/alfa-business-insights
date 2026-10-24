package alpha.business.insight.core.insight;

import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;

import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.qameta.allure.Step;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проверяет генерацию, хранение и доступ к инсайтам через API. */
@org.junit.jupiter.api.Tag("integration")
@org.junit.jupiter.api.Tag("insights")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
        "spring.ai.model.chat=none", "ml.stub=true",
        "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
@Testcontainers
class InsightApiIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.19.0")
            .withExposedPorts(6333, 6334).waitingFor(Wait.forHttp("/readyz").forPort(6333));

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
    @MockitoSpyBean StubDocumentAnalyzer analyzer;
    @Autowired DocumentRepository documents;
    String company;
    UUID event;

    /** Создаёт компанию, событие и грант перед каждым тестом. */
    @BeforeEach
    void prepare() {
        company = "insight_" + UUID.randomUUID();
        event = UUID.randomUUID();
        jdbc.update("INSERT INTO companies (company_id, profile_status) VALUES (?, 'MISSING')", company);
        jdbc.update("INSERT INTO events (id, title, source_type, source_ref, status) "
                + "VALUES (?, 'Снижение ставки', 'TEXT', 'Ставка снижена', 'READY')", event);
        jdbc.update("INSERT INTO user_company_access (user_id, company_id) VALUES ('director', ?)", company);
    }
    /** Повторно читает сохранённый инсайт без новых вызовов LLM. */

    @Test
    @Step("Повторно читает сохранённый инсайт без новых вызовов LLM")
    void repeatedPostAndGetReturnSameInsightWithoutNewLlmCalls() throws Exception {
        JsonNode first = generate();
        assertThat(first.path("trace_id").asString()).isNotBlank();
        assertThat(first.path("bank_view").isArray()).isTrue();
        assertThat(first.path("bank_view").size()).isZero();
        assertThat(first.path("missing_data").toString()).contains("банк");
        assertThat(first.path("cost_rub").decimalValue()).isZero();
        for (String field : new String[]{"facts", "ai_inference", "hypotheses", "features_used", "risks",
                "opportunities", "actions", "missing_data", "sources"}) {
            assertThat(first.path(field).isArray()).as(field).isTrue();
        }
        assertThat(generate()).isEqualTo(first);
        JsonNode read = mapper.readTree(request().postProcessors(director()).when().get(path()).then()
                .assertThat(status().isOk()).extract().response().asString());
        assertThat(read).isEqualTo(first);
        verify(llm, times(4)).complete(anyString(), any());
    }
    /** Создаёт инсайт для новой версии материалов. */

    @Test
    @Step("Создаёт инсайт для новой версии материалов")
    void newMaterialsVersionRequiresNewGeneration() throws Exception {
        JsonNode first = generate();
        jdbc.update("UPDATE events SET materials_version=2 WHERE id=?", event);
        request().postProcessors(director()).when().get(path()).then().assertThat(status().isNotFound());
        JsonNode second = generate();
        assertThat(second.path("id")).isNotEqualTo(first.path("id"));
        assertThat(second.path("trace_id")).isNotEqualTo(first.path("trace_id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Integer.class, company))
                .isEqualTo(2);
    }
    /** Объединяет параллельную генерацию инсайта. */

    @Test
    @Step("Объединяет параллельную генерацию инсайта")
    void concurrentPostsShareOneGeneration() throws Exception {
        CountDownLatch requestsReady = new CountDownLatch(2);
        CountDownLatch startRequests = new CountDownLatch(1);
        AtomicBoolean firstPlan = new AtomicBoolean(true);
        String testCaseUuid = Allure.getLifecycle().getCurrentTestCase().orElseThrow();
        doAnswer(invocation -> {
            if (firstPlan.compareAndSet(true, false)) {
                awaitConcurrentTransaction();
            }
            return invocation.callRealMethod();
        }).when(llm).complete(eq("plan"), any());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<JsonNode> first = executor.submit(() -> {
                requestsReady.countDown();
                startRequests.await();
                return generateWithAllureContext(testCaseUuid);
            });
            Future<JsonNode> second = executor.submit(() -> {
                requestsReady.countDown();
                startRequests.await();
                return generateWithAllureContext(testCaseUuid);
            });
            boolean bothReady = requestsReady.await(10, TimeUnit.SECONDS);
            startRequests.countDown();
            assertThat(bothReady).isTrue();
            JsonNode firstResult = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(firstResult);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Integer.class, company))
                .isEqualTo(1);
        verify(llm, times(4)).complete(anyString(), any());
    }

    /** Дожидается второй транзакции, заблокированной на строке события. */
    private void awaitConcurrentTransaction() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int lockWaiters = 0;
        while (System.nanoTime() < deadline) {
            lockWaiters = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (lockWaiters > 0) return;
            Thread.sleep(25);
        }
        assertThat(lockWaiters).as("concurrent request waiting for the event row lock").isGreaterThan(0);
    }

    /** Привязывает шаги параллельного HTTP-запроса к текущему тесту Allure. */
    private JsonNode generateWithAllureContext(String testCaseUuid) throws Exception {
        Allure.getLifecycle().setCurrentTestCase(testCaseUuid);
        // Each request uses its own virtual thread, so the thread-local context ends with that task.
        return generate();
    }
    /** Возвращает 404 для отсутствующего инсайта. */

    @Test
    @Step("Возвращает 404 для отсутствующего инсайта")
    void missingSavedInsightReturns404() throws Exception {
        request().postProcessors(director()).when().get(path()).then().assertThat(status().isNotFound());
    }
    /** Проверяет авторизацию компании. */

    @Test
    @Step("Проверяет авторизацию компании")
    void rejectsAnonymousAndDirectorWithoutCompanyAccess() throws Exception {
        request().when().post(path()).then().assertThat(status().isUnauthorized());
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        request().postProcessors(director()).when().post(path()).then().assertThat(status().isForbidden());
        request().postProcessors(director()).when().get(path()).then().assertThat(status().isForbidden());
    }
    /** Разрешает администратору генерацию без гранта компании. */

    @Test
    @Step("Разрешает администратору генерацию без гранта компании")
    void administratorCanGenerateWithoutCompanyGrant() throws Exception {
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        request().postProcessors(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))).when().post(path()).then().assertThat(status().isOk());
    }
    /** Возвращает 404 для неизвестной компании или события. */

    @Test
    @Step("Возвращает 404 для неизвестной компании или события")
    void unknownCompanyOrEventReturns404() throws Exception {
        request().postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when().post("/api/companies/unknown/events/" + event + "/insight").then()
                .assertThat(status().isNotFound());
        event = UUID.randomUUID();
        request().postProcessors(director()).when().post(path()).then().assertThat(status().isNotFound());
    }
    /** Проверяет источники и удаление неподтверждённой позиции банка. */

    @Test
    @Step("Проверяет источники и удаление неподтверждённой позиции банка")
    void qdrantFragmentsReachPromptButSyntheticBankViewIsRemoved() throws Exception {
        UUID documentId = UUID.randomUUID();
        DocumentAnalysis analysis = new StubDocumentAnalyzer().analyze(documentId, new byte[0]);
        vectors.index(analysis, null, System.nanoTime() + Duration.ofSeconds(10).toNanos());
        documents.saveAndFlush(new KnowledgeDocument(documentId, "Банковский PDF", "test.pdf", "test.pdf",
                1, "0".repeat(64), Instant.now(), null, 1, 1, "stub-v1", 8, true));
        String fragmentId = DocumentVectorStore.chunkId(documentId, 0).toString();
        String fragmentText = analysis.chunks().getFirst().text();
        // Подменяет ответ модели, чтобы проверить очистку ссылок и сырого текста.
        doAnswer(invocation -> {
            ObjectNode input = invocation.getArgument(1);
            assertThat(input.path("external_data").path("fragments").toString()).contains(fragmentId, fragmentText);
            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
            completion.withArray("bank_view").addObject().put("text", "Выдуманная позиция банка")
                    .put("fragment_id", fragmentId);
            completion.withArray("facts").addObject().put("text", "Несуществующий фрагмент")
                    .put("fragment_id", UUID.randomUUID().toString());
            completion.withArray("sources").addObject().put("fragment_id", fragmentId)
                    .put("text", fragmentText);
            completion.putArray("fragments").addObject().put("text", fragmentText);
            return completion;
        }).when(llm).complete(eq("revision"), any());
        JsonNode result = generate();
        assertThat(result.path("bank_view").isEmpty()).isTrue();
        assertThat(result.path("facts").isEmpty()).isTrue();
        assertThat(result.path("sources").toString()).contains(fragmentId, "#page=1").doesNotContain(fragmentText);
        assertThat(result.path("sources").findValues("text")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM insights WHERE company_id=?", String.class, company))
                .doesNotContain(fragmentText, "Ставка снижена");
        assertThat(result.path("missing_data").toString()).contains("банк", "STUB");
    }
    /** Не сохраняет инсайт при ошибке LLM. */

    @Test
    @Step("Не сохраняет инсайт при ошибке LLM")
    void failedLlmDoesNotCacheIncompleteInsight() throws Exception {
        // Имитирует неполный критический ответ перед отказом revision-вызова.
        doAnswer(invocation -> {
            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
            completion.withArray("issues").addObject().put("source_fragment", "Ставка снижена");
            return completion;
        }).when(llm).complete(eq("critic"), any());
        doThrow(new ComponentUnavailableException("LLM")).when(llm).complete(eq("revision"), any());
        request().postProcessors(director()).when().post(path()).then()
                .assertThat(status().isServiceUnavailable())
                .assertThat(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .assertThat(jsonPath("$.retryable").value(true));
        request().postProcessors(director()).when().get(path()).then().assertThat(status().isNotFound());
        doCallRealMethod().when(llm).complete(eq("revision"), any());
        generate();
    }
    /** Повторяет генерацию после ошибки ML. */

    @Test
    @Step("Повторяет генерацию после ошибки ML")
    void failedMlDoesNotSaveInsightAndCanBeRetried() throws Exception {
        doThrow(new IllegalStateException("private ML details")).when(analyzer).embed(anyString());
        request().postProcessors(director()).when().post(path()).then()
                .assertThat(status().isServiceUnavailable())
                .assertThat(jsonPath("$.code").value("ML_UNAVAILABLE"))
                .assertThat(jsonPath("$.retryable").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Long.class, company)).isZero();
        doCallRealMethod().when(analyzer).embed(anyString());
        generate();
    }
    /** Повторяет генерацию после ошибки Qdrant. */

    @Test
    @Step("Повторяет генерацию после ошибки Qdrant")
    void failedQdrantDoesNotSaveInsightAndCanBeRetried() throws Exception {
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(QDRANT.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            request().postProcessors(director()).when().post(path()).then()
                    .assertThat(status().isServiceUnavailable())
                    .assertThat(jsonPath("$.code").value("QDRANT_UNAVAILABLE"))
                    .assertThat(jsonPath("$.retryable").value(true));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Long.class, company)).isZero();
        } finally {
            docker.unpauseContainerCmd(QDRANT.getContainerId()).exec();
        }
        generate();
    }

    private JsonNode generate() throws Exception {
        return mapper.readTree(request().postProcessors(director()).when().post(path()).then()
                .assertThat(status().isOk()).assertThat(jsonPath("$.id").isNotEmpty())
                .extract().response().asString());
    }

    /** Создаёт request-local спецификацию с общим API context path. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Возвращает endpoint инсайта для текущих тестовых данных. */
    private String path() { return "/api/companies/" + company + "/events/" + event + "/insight"; }
    /** Создаёт JWT директора с subject, используемым в таблице грантов. */
    private RequestPostProcessor director() {
        return jwt().jwt(token -> token.subject("director"))
                .authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"));
    }
}
