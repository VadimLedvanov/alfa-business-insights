package alpha.business.insight.core.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.dto.Counterparty;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.service.CompanyContextService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Проверяет контекст компании через полный Spring-контекст и PostgreSQL. */
@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
        })
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@Tag("integration")
@Tag("company-context")
class CompanyContextIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    /** Подключает тестовую базу PostgreSQL к Spring-контексту. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired EntityManager entities;
    @Autowired CompanyRepository companies;
    @Autowired CompanyPaymentSummaryRepository payments;
    @MockitoBean JwtDecoder decoder;

    /** Заполняет базу профилями компаний и платежами. */
    @BeforeEach
    void prepareData() {
        execute(
                """
                INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name,
                    region, revenue, group_id, has_foreign_trade, foreign_trade_countries,
                    has_settlement_account, has_bank_guarantee, has_loan, has_leasing, has_deposit,
                    has_payroll_project, has_factoring)
                VALUES ('ctx_main', 'AVAILABLE', '01.1', 'Растениеводство', 'Москва', 15000000000.12,
                    'ctx_group', true, 'Китай', true, false, true, false, false, true, false),
                    ('ctx_group_member', 'AVAILABLE', '62', 'ИТ', NULL, NULL, 'ctx_group',
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        execute(
                """
                INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name)
                VALUES ('ctx_a', 'MISSING', NULL, NULL), ('ctx_b', 'AVAILABLE', '10', 'Пищевые продукты'),
                    ('ctx_c', 'AVAILABLE', '20', 'Химия'), ('ctx_d', 'AVAILABLE', '30', 'Машиностроение'),
                    ('ctx_e', 'AVAILABLE', '40', 'Энергетика'), ('ctx_f', 'AVAILABLE', '50', 'Транспорт'),
                    ('ctx_empty', 'MISSING', NULL, NULL), ('ctx_conflict', 'CONFLICTING', NULL, NULL)
                """);
        execute(
                """
                INSERT INTO user_company_access (user_id, company_id)
                VALUES ('context-director', 'ctx_main'), ('context-director', 'ctx_empty'),
                    ('context-director', 'ctx_conflict')
                """);
        execute(
                """
                INSERT INTO company_payment_summaries
                    (payer_company_id, recipient_company_id, total_payment_amount, payment_count)
                VALUES ('ctx_a', 'ctx_main', 250, 1), ('ctx_main', 'ctx_a', 150, 1),
                    ('ctx_main', 'ctx_b', 300, 2), ('ctx_c', 'ctx_main', 150, 1),
                    ('ctx_main', 'ctx_d', 100, 1), ('ctx_e', 'ctx_main', 30, 1),
                    ('ctx_main', 'ctx_f', 20, 1), ('ctx_main', 'ctx_main', 10000, 1)
                """);
    }

    /** Проверяет профиль, группу, направления платежей и ранжирование контрагентов. */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenGroupAndBidirectionalPayments_whenGetContext_thenReturnProfileRankingsAndMissingData(
            String role) throws Exception {
        // Действие
        request()
                .postProcessors(director("ROLE_" + role))
                .when()
                .get("/api/companies/ctx_main")
                .then()
                .statusCode(200)
                .body("profile.companyId", equalTo("ctx_main"))
                .body("profile.profileStatus", equalTo("AVAILABLE"))
                .body("profile.mainIndustryCode", equalTo("01.1"))
                .body("profile.region", equalTo("Москва"))
                .body("profile.revenue.doubleValue()", equalTo(15000000000.12))
                .body("profile.hasLoan", equalTo(true))
                .body("profile.hasLeasing", equalTo(false))
                .body("profile.hasForeignTrade", equalTo(true))
                .body("profile.foreignTradeCountries", equalTo("Китай"))
                .body("group", hasSize(2))
                .body("group[0].companyId", equalTo("ctx_group_member"))
                .body("group[0].mainIndustryCode", equalTo("62"))
                .body("group[1].companyId", equalTo("ctx_main"))
                .body("counterparties", hasSize(5))
                .body(
                        "counterparties*.companyId",
                        contains("ctx_a", "ctx_b", "ctx_c", "ctx_d", "ctx_e"))
                .body("counterparties[0].profileStatus", equalTo("MISSING"))
                .body("counterparties[0]", hasKey("mainIndustryCode"))
                .body("counterparties[0].mainIndustryCode", nullValue())
                .body("counterparties[0].incomingPaymentAmount.doubleValue()", equalTo(250.0))
                .body("counterparties[0].outgoingPaymentAmount.doubleValue()", equalTo(150.0))
                .body("counterparties[0].totalPaymentAmount.doubleValue()", equalTo(400.0))
                .body("counterparties[0].paymentShare.doubleValue()", equalTo(0.4))
                .body("totalPaymentAmount.doubleValue()", equalTo(1000.0))
                .body("concentration.top1Share.doubleValue()", equalTo(0.4))
                .body("concentration.top3Share.doubleValue()", equalTo(0.85))
                .body("missingData", contains("counterparties.ctx_a.mainIndustryCode"));
    }

    /** Проверяет пустые списки и неизвестные доли без платежей. */
    @Test
    void givenNoGroupOrPayments_whenGetContext_thenReturnEmptyListsAndUnknownShares()
            throws Exception {
        // Действие
        request()
                .postProcessors(director("ROLE_COMPANY_DIRECTOR"))
                .when()
                .get("/api/companies/ctx_empty")
                .then()
                .statusCode(200)
                .body("group", empty())
                .body("counterparties", empty())
                .body("totalPaymentAmount.doubleValue()", equalTo(0.0))
                .body("concentration", hasKey("top1Share"))
                .body("concentration.top1Share", nullValue())
                .body("concentration", hasKey("top3Share"))
                .body("concentration.top3Share", nullValue())
                .body("missingData", hasItem("profile.missing"))
                .body("missingData", hasItem("profile.mainIndustryCode"))
                .body("missingData", hasItem("profile.hasLoan"))
                .body("missingData", hasItem("payments"))
                .body("missingData", hasItem("paymentShares"));
    }

    /** Проверяет концентрацию платежей при ограниченном списке контрагентов. */
    @Test
    void
            givenSmallConfiguredLimit_whenGetContext_thenCalculateConcentrationAcrossThreeCounterparties() {
        // Подготовка
        CompanyContextService service = new CompanyContextService(companies, payments, 2);

        TestEvidence.step(
                "Calculate context with a limited counterparty list",
                List.of(
                        new TestEvidence.Input("companyId", "ctx_main"),
                        new TestEvidence.Input("resultLimit", 2),
                        new TestEvidence.Input(
                                "seededCompanyIds",
                                List.of(
                                        "ctx_main",
                                        "ctx_group_member",
                                        "ctx_a",
                                        "ctx_b",
                                        "ctx_c",
                                        "ctx_d",
                                        "ctx_e",
                                        "ctx_f"))),
                () -> service.getContext("ctx_main"),
                context -> {
                    assertThat(context.counterparties()).hasSize(2);
                    assertThat(context.totalPaymentAmount()).isEqualByComparingTo("1000");
                    assertThat(context.concentration().top3Share()).isEqualByComparingTo("0.85");
                });

        TestEvidence.stepThrows(
                "Reject a non-positive counterparty limit",
                List.of(new TestEvidence.Input("resultLimit", 0)),
                () -> new CompanyContextService(companies, payments, 0),
                failure -> assertThat(failure).isInstanceOf(IllegalArgumentException.class));
    }

    /** Проверяет стабильный порядок и нулевые доли при нулевых суммах. */
    @Test
    void givenZeroAmountsAndTies_whenGetContext_thenReturnStableOrderWithoutDividingByZero() {
        // Подготовка
        execute(
                """
                UPDATE company_payment_summaries SET total_payment_amount = 0
                WHERE payer_company_id = 'ctx_main' OR recipient_company_id = 'ctx_main'
                """);
        CompanyContextService service = new CompanyContextService(companies, payments, 5);

        TestEvidence.step(
                "Calculate shares for zero-value payments",
                List.of(
                        new TestEvidence.Input("companyId", "ctx_main"),
                        new TestEvidence.Input("resultLimit", 5),
                        new TestEvidence.Input(
                                "seededPaymentCompanyIds",
                                List.of(
                                        "ctx_a",
                                        "ctx_main",
                                        "ctx_b",
                                        "ctx_c",
                                        "ctx_d",
                                        "ctx_e",
                                        "ctx_f"))),
                () -> service.getContext("ctx_main"),
                context -> {
                    assertThat(context.counterparties())
                            .extracting(Counterparty::companyId)
                            .containsExactly("ctx_a", "ctx_b", "ctx_c", "ctx_d", "ctx_e");
                    assertThat(context.counterparties())
                            .allSatisfy(c -> assertThat(c.paymentShare()).isNull());
                    assertThat(context.concentration().top3Share()).isNull();
                    assertThat(context.missingData())
                            .contains("paymentShares")
                            .doesNotContain("payments");
                });
    }

    /** Проверяет описание конфликтных и неполных данных компании. */
    @Test
    void givenConflictingProfileAndPartialGroup_whenGetContext_thenReportMissingFeatures() {
        // Подготовка
        execute(
                "UPDATE companies SET group_id = 'ctx_group', main_industry_code = ' ' "
                        + "WHERE company_id = 'ctx_conflict'");
        execute("UPDATE companies SET foreign_trade_countries = ' ' WHERE company_id = 'ctx_main'");
        CompanyContextService service = new CompanyContextService(companies, payments, 5);

        TestEvidence.step(
                "Report missing data for a conflicting company profile",
                List.of(new TestEvidence.Input("companyId", "ctx_conflict")),
                () -> service.getContext("ctx_conflict"),
                context ->
                        assertThat(context.missingData())
                                .contains("profile.conflicting", "profile.mainIndustryCode"));
        TestEvidence.step(
                "Report partial group and foreign trade data",
                List.of(
                        new TestEvidence.Input("companyId", "ctx_main"),
                        new TestEvidence.Input("relatedCompanyId", "ctx_conflict")),
                () -> service.getContext("ctx_main"),
                context ->
                        assertThat(context.missingData())
                                .contains(
                                        "profile.foreignTradeCountries",
                                        "group.ctx_conflict.mainIndustryCode"));
    }

    /** Проверяет направление единственного платежа и его стопроцентную долю. */
    @Test
    void
            givenOneCounterparty_whenGetContext_thenConcentrationIsOneAndIncomingDirectionIsPreserved() {
        // Подготовка
        CompanyContextService service = new CompanyContextService(companies, payments, 5);

        TestEvidence.step(
                "Calculate direction and concentration for one counterparty",
                List.of(
                        new TestEvidence.Input("companyId", "ctx_b"),
                        new TestEvidence.Input("resultLimit", 5),
                        new TestEvidence.Input(
                                "seededPayment",
                                Map.of(
                                        "payerCompanyId",
                                        "ctx_main",
                                        "recipientCompanyId",
                                        "ctx_b",
                                        "amount",
                                        300))),
                () -> service.getContext("ctx_b"),
                context -> {
                    assertThat(context.counterparties()).hasSize(1);
                    assertThat(context.counterparties().getFirst().incomingPaymentAmount())
                            .isEqualByComparingTo("300");
                    assertThat(context.counterparties().getFirst().outgoingPaymentAmount())
                            .isZero();
                    assertThat(context.concentration().top1Share()).isEqualByComparingTo("1");
                    assertThat(context.concentration().top3Share()).isEqualByComparingTo("1");
                });
    }

    /** Проверяет ответ 404 для неизвестной компании. */
    @Test
    void givenUnknownCompany_whenGetContext_thenNotFound() throws Exception {
        // Действие и проверки
        request()
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .get("/api/companies/ctx_unknown")
                .then()
                .statusCode(404);
    }

    /** Проверяет описание API контекста компании в OpenAPI. */
    @Test
    void givenPublicSpecification_whenGetOpenApi_thenDescribeCompanyContextUnderCommonPrefix()
            throws Exception {
        // Действие и проверки
        request()
                .when()
                .get("/api/v3/api-docs")
                .then()
                .statusCode(200)
                .body("servers[0].url", equalTo("http://localhost/api"))
                .body("paths.'/companies/{id}'.get", notNullValue())
                .body("paths.'/companies/{id}'.get.responses.'404'", notNullValue())
                .body("components.schemas.CompanyContext.properties.missingData", notNullValue())
                .body("components.schemas.CompanyContext.properties.profile", notNullValue());
    }

    /** Проверяет отказ без токена и при недостаточной роли. */
    @Test
    void givenMissingOrInsufficientRole_whenGetContext_thenRejectRequest() throws Exception {
        // Действие и проверки
        request().when().get("/api/companies/ctx_main").then().statusCode(401);
        request()
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER")))
                .when()
                .get("/api/companies/ctx_main")
                .then()
                .statusCode(403);
    }

    /** Создаёт request-local спецификацию для существующего Spring MockMvc. */
    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Создаёт JWT директора с subject, совпадающим с подготовленными грантами. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor director(
            String role) {
        return jwt().jwt(token -> token.subject("context-director"))
                .authorities(new SimpleGrantedAuthority(role));
    }

    /** Выполняет SQL-команду подготовки данных через EntityManager. */
    private void execute(String sql) {
        entities.createNativeQuery(sql).executeUpdate();
    }
}
