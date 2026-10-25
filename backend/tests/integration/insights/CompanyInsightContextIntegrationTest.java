package alpha.business.insight.core.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.qameta.allure.Allure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/**
 * Проверяет передачу контекста выбранной компании в генерацию инсайта и изоляцию кэша.
 * LLM и ML используют production-заглушки, поэтому тест не заявляет о семантике персонализации.
 */
@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
            "spring.ai.model.chat=none",
            "ml.stub=true",
            "spring.datasource.hikari.maximum-pool-size=4"
        })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@Tag("insights")
class CompanyInsightContextIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Container
    static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:v1.19.0")
                    .withExposedPorts(6333, 6334)
                    .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    private static final String COMPANY_A =
            "insight_ctx_a_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String COMPANY_B =
            "insight_ctx_b_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String COMPANY_C =
            "insight_ctx_c_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP =
            "insight_ctx_group_" + UUID.randomUUID().toString().substring(0, 8);
    private static final List<String> COMPANIES = List.of(COMPANY_A, COMPANY_B, COMPANY_C);

    /** Подключает тестовые PostgreSQL и Qdrant к приложению. */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
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

    private UUID event;
    private final List<CapturedContext> capturedCompanyContexts = new ArrayList<>();

    /** Создаёт три компании с разными профилями, общую группу и платежи с известными долями. */
    @BeforeEach
    void prepareData() {
        jdbc.update(
                "INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name, "
                        + "region, group_id, has_loan, has_foreign_trade) "
                        + "VALUES (?, 'AVAILABLE', '01.1', 'Растениеводство', 'Москва', ?, true, false), "
                        + "(?, 'AVAILABLE', '46.2', 'Оптовая торговля', 'Казань', ?, false, true), "
                        + "(?, 'AVAILABLE', '62', 'Разработка ПО', 'Томск', ?, false, false)",
                COMPANY_A,
                GROUP,
                COMPANY_B,
                GROUP,
                COMPANY_C,
                GROUP);
        jdbc.update(
                "INSERT INTO company_payment_summaries "
                        + "(payer_company_id, recipient_company_id, total_payment_amount, payment_count) "
                        + "VALUES (?, ?, 100, 1), (?, ?, 300, 1), (?, ?, 100, 1)",
                COMPANY_A,
                COMPANY_B,
                COMPANY_C,
                COMPANY_A,
                COMPANY_B,
                COMPANY_C);
        event = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO events (id, title, source_type, source_ref, status) "
                        + "VALUES (?, 'Изменение ключевой ставки', 'TEXT', 'Ставка снижена на 1 п.п.', 'READY')",
                event);
        COMPANIES.forEach(
                company -> {
                    jdbc.update(
                            "INSERT INTO user_company_access (user_id, company_id) VALUES ('director', ?)",
                            company);
                });
        doAnswer(
                        invocation -> {
                            ObjectNode input = invocation.getArgument(1);
                            String stage = invocation.getArgument(0, String.class);
                            capturedCompanyContexts.add(
                                    new CapturedContext(
                                            stage,
                                            mapper.readTree(
                                                    mapper.writeValueAsBytes(
                                                            input.path("external_data")
                                                                    .path("company"))),
                                            input.path("external_data")
                                                    .path("event")
                                                    .path("id")
                                                    .asString()));
                            return invocation.callRealMethod();
                        })
                .when(llm)
                .complete(any(String.class), any(ObjectNode.class));
    }

    /** Удаляет только связанные с этим тестом строки после генерации. */
    @AfterEach
    void removeFixture() {
        jdbc.update(
                "DELETE FROM insights WHERE event_id=? AND company_id IN (?, ?, ?)",
                event,
                COMPANY_A,
                COMPANY_B,
                COMPANY_C);
        jdbc.update(
                "DELETE FROM user_company_access WHERE company_id IN (?, ?, ?)",
                COMPANY_A,
                COMPANY_B,
                COMPANY_C);
        jdbc.update(
                "DELETE FROM company_payment_summaries WHERE payer_company_id IN (?, ?, ?) "
                        + "OR recipient_company_id IN (?, ?, ?)",
                COMPANY_A,
                COMPANY_B,
                COMPANY_C,
                COMPANY_A,
                COMPANY_B,
                COMPANY_C);
        jdbc.update("DELETE FROM events WHERE id=?", event);
        jdbc.update(
                "DELETE FROM companies WHERE company_id IN (?, ?, ?)",
                COMPANY_A,
                COMPANY_B,
                COMPANY_C);
    }

    /** Передаёт разные профили и общий платёжный контекст, сохраняя независимый кэш каждой компании. */
    @Test
    @DisplayName("TestIntegrationInsightReceivesSelectedCompanyContextAndIsolatesCache")
    void TestIntegrationInsightReceivesSelectedCompanyContextAndIsolatesCache() throws Exception {
        // Действие: генерирует инсайты для трёх компаний на одном событии через реальный pipeline.
        List<JsonNode> insights = new ArrayList<>();
        for (String company : COMPANIES) {
            JsonNode result =
                    Allure.step(
                            "Сгенерировать инсайт для компании " + company,
                            () ->
                                    mapper.readTree(
                                            request()
                                                    .postProcessors(director())
                                                    .when()
                                                    .post(insightPath(company))
                                                    .then()
                                                    .statusCode(200)
                                                    .extract()
                                                    .response()
                                                    .asString()));
            insights.add(result);
        }

        // Проверки: фактический ввод LLM содержит профиль, группу и платежи выбранной компании.
        Allure.step(
                "Сверить контекст трёх компаний и сохранённые записи",
                () -> {
                    assertThat(capturedCompanyContexts)
                            .as("вызовы всех стадий LLM с переданным контекстом")
                            .hasSize(12);
                    assertCapturedCompanyStages(
                            COMPANY_A, "01.1", true, false, COMPANY_C, COMPANY_B);
                    assertCapturedCompanyStages(
                            COMPANY_B, "46.2", false, true, COMPANY_A, COMPANY_C);
                    assertCapturedCompanyStages(
                            COMPANY_C, "62", false, false, COMPANY_A, COMPANY_B);
                    assertThat(insights)
                            .as("у каждой компании свой ID инсайта")
                            .extracting(node -> node.path("id").asString())
                            .doesNotHaveDuplicates();
                    assertThat(insights)
                            .as("каждая генерация получила собственный trace_id")
                            .extracting(node -> node.path("trace_id").asString())
                            .doesNotHaveDuplicates();
                    for (JsonNode insight : insights) {
                        assertThat(UUID.fromString(insight.path("id").asString()))
                                .as("ID инсайта является UUID; response=%s", insight)
                                .isNotNull();
                        assertThat(UUID.fromString(insight.path("trace_id").asString()))
                                .as("trace_id является UUID; response=%s", insight)
                                .isNotNull();
                    }
                    List<java.util.Map<String, Object>> storedRows =
                            jdbc.queryForList(
                                    "SELECT company_id, event_id FROM insights WHERE event_id=? ORDER BY company_id",
                                    event);
                    assertThat(storedRows)
                            .extracting(row -> row.get("company_id"))
                            .containsExactlyElementsOf(COMPANIES.stream().sorted().toList());
                    assertThat(storedRows)
                            .extracting(row -> row.get("event_id"))
                            .containsOnly(event);
                    for (int index = 0; index < COMPANIES.size(); index++) {
                        String company = COMPANIES.get(index);
                        JsonNode generated = insights.get(index);
                        UUID insightId = UUID.fromString(generated.path("id").asString());
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM insights WHERE id=? AND company_id=? "
                                                        + "AND event_id=? AND materials_version=?",
                                                Integer.class,
                                                insightId,
                                                company,
                                                event,
                                                generated.path("materials_version").asLong()))
                                .as(
                                        "FK и версия сохранённого инсайта company=%s event=%s id=%s",
                                        company, event, insightId)
                                .isEqualTo(1);
                        JsonNode cached =
                                mapper.readTree(
                                        request()
                                                .postProcessors(director())
                                                .when()
                                                .get(insightPath(company))
                                                .then()
                                                .statusCode(200)
                                                .extract()
                                                .response()
                                                .asString());
                        assertThat(cached.path("id").asString())
                                .as(
                                        "GET-кэш для company=%s event=%s; ответ=%s",
                                        company, event, cached)
                                .isEqualTo(generated.path("id").asString());
                    }
                });
    }

    /** Проверяет переданный в pipeline профиль, состав группы и направления платежей. */
    private void assertCapturedCompanyStages(
            String company,
            String industry,
            boolean hasLoan,
            boolean hasForeignTrade,
            String firstCounterparty,
            String secondCounterparty) {
        List<CapturedContext> companyCaptures =
                capturedCompanyContexts.stream()
                        .filter(
                                capture ->
                                        capture.context()
                                                .at("/profile/companyId")
                                                .asString()
                                                .equals(company))
                        .toList();
        assertThat(companyCaptures)
                .as("контекст company=%s на каждой стадии LLM", company)
                .hasSize(4);
        assertThat(companyCaptures)
                .extracting(CapturedContext::stage)
                .as("стадии LLM для company=%s", company)
                .containsExactlyInAnyOrder("plan", "draft", "critic", "revision");
        companyCaptures.forEach(
                capture -> {
                    assertThat(capture.eventId())
                            .as("event ID на стадии %s для company=%s", capture.stage(), company)
                            .isEqualTo(event.toString());
                    assertCompanyContext(
                            capture.context(),
                            company,
                            industry,
                            hasLoan,
                            hasForeignTrade,
                            firstCounterparty,
                            secondCounterparty,
                            capture.stage());
                });
    }

    /** Проверяет переданный в pipeline профиль, состав группы и направления платежей. */
    private void assertCompanyContext(
            JsonNode context,
            String company,
            String industry,
            boolean hasLoan,
            boolean hasForeignTrade,
            String firstCounterparty,
            String secondCounterparty,
            String stage) {
        assertThat(context.at("/profile/companyId").asString())
                .as("profile.companyId, stage=%s; context=%s", stage, context)
                .isEqualTo(company);
        assertThat(context.at("/profile/mainIndustryCode").asString())
                .as("profile.mainIndustryCode, stage=%s; context=%s", stage, context)
                .isEqualTo(industry);
        assertThat(context.at("/profile/hasLoan").isBoolean())
                .as("profile.hasLoan is a boolean, stage=%s; context=%s", stage, context)
                .isTrue();
        assertThat(context.at("/profile/hasLoan").asBoolean())
                .as("profile.hasLoan, stage=%s; context=%s", stage, context)
                .isEqualTo(hasLoan);
        assertThat(context.at("/profile/hasForeignTrade").isBoolean())
                .as("profile.hasForeignTrade is a boolean, stage=%s; context=%s", stage, context)
                .isTrue();
        assertThat(context.at("/profile/hasForeignTrade").asBoolean())
                .as("profile.hasForeignTrade, stage=%s; context=%s", stage, context)
                .isEqualTo(hasForeignTrade);
        assertThat(context.at("/profile/groupId").asString())
                .as("profile.groupId, stage=%s; context=%s", stage, context)
                .isEqualTo(GROUP);
        assertThat(context.path("group").size())
                .as("group size, stage=%s; context=%s", stage, context)
                .isEqualTo(3);
        assertThat(context.path("group").findValuesAsString("companyId"))
                .as("group company IDs, stage=%s; context=%s", stage, context)
                .containsExactlyInAnyOrderElementsOf(COMPANIES);
        assertThat(context.at("/counterparties/0/companyId").asString())
                .as("largest counterparty, stage=%s; context=%s", stage, context)
                .isEqualTo(firstCounterparty);
        assertThat(context.at("/counterparties/1/companyId").asString())
                .as("second counterparty, stage=%s; context=%s", stage, context)
                .isEqualTo(secondCounterparty);
        BigDecimal expectedFirstShare =
                company.equals(COMPANY_B) ? new BigDecimal("0.5") : new BigDecimal("0.75");
        assertThat(context.at("/counterparties/0/paymentShare").decimalValue())
                .as("largest counterparty share, stage=%s; context=%s", stage, context)
                .isEqualByComparingTo(expectedFirstShare);
        assertThat(context.at("/counterparties/1/paymentShare").decimalValue())
                .as("second counterparty share, stage=%s; context=%s", stage, context)
                .isEqualByComparingTo(
                        company.equals(COMPANY_B) ? new BigDecimal("0.5") : new BigDecimal("0.25"));
    }

    /** Хранит копию контекста и события, переданных на одной стадии генерации. */
    private record CapturedContext(String stage, JsonNode context, String eventId) {}

    /** Создаёт request-local MockMvc спецификацию. */
    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Формирует адрес инсайта для указанной компании и общего события. */
    private String insightPath(String company) {
        return "/api/companies/" + company + "/events/" + event + "/insight";
    }

    /** Создаёт JWT директора с доступом к тестовым компаниям. */
    private RequestPostProcessor director() {
        return jwt().jwt(token -> token.subject("director"))
                .authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"));
    }
}
