package alpha.business.insight.core.company;

import alpha.business.insight.core.dto.CompanyContext;
import alpha.business.insight.core.dto.Counterparty;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import alpha.business.insight.core.service.CompanyContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class CompanyContextIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    EntityManager entities;
    @Autowired
    CompanyRepository companies;
    @Autowired
    CompanyPaymentSummaryRepository payments;
    @MockitoBean
    JwtDecoder decoder;

    @BeforeEach
    void prepareData() {
        execute("""
                INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name,
                    region, revenue, group_id, has_foreign_trade, foreign_trade_countries,
                    has_settlement_account, has_bank_guarantee, has_loan, has_leasing, has_deposit,
                    has_payroll_project, has_factoring)
                VALUES ('ctx_main', 'AVAILABLE', '01.1', 'Растениеводство', 'Москва', 15000000000.12,
                    'ctx_group', true, 'Китай', true, false, true, false, false, true, false),
                    ('ctx_group_member', 'AVAILABLE', '62', 'ИТ', NULL, NULL, 'ctx_group',
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        execute("""
                INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name)
                VALUES ('ctx_a', 'MISSING', NULL, NULL), ('ctx_b', 'AVAILABLE', '10', 'Пищевые продукты'),
                    ('ctx_c', 'AVAILABLE', '20', 'Химия'), ('ctx_d', 'AVAILABLE', '30', 'Машиностроение'),
                    ('ctx_e', 'AVAILABLE', '40', 'Энергетика'), ('ctx_f', 'AVAILABLE', '50', 'Транспорт'),
                    ('ctx_empty', 'MISSING', NULL, NULL), ('ctx_conflict', 'CONFLICTING', NULL, NULL)
                """);
        execute("""
                INSERT INTO company_payment_summaries
                    (payer_company_id, recipient_company_id, total_payment_amount, payment_count)
                VALUES ('ctx_a', 'ctx_main', 250, 1), ('ctx_main', 'ctx_a', 150, 1),
                    ('ctx_main', 'ctx_b', 300, 2), ('ctx_c', 'ctx_main', 150, 1),
                    ('ctx_main', 'ctx_d', 100, 1), ('ctx_e', 'ctx_main', 30, 1),
                    ('ctx_main', 'ctx_f', 20, 1), ('ctx_main', 'ctx_main', 10000, 1)
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenGroupAndBidirectionalPayments_whenGetContext_thenReturnProfileRankingsAndMissingData(String role)
            throws Exception {
        mvc.perform(get("/api/companies/ctx_main").contextPath("/api")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.companyId").value("ctx_main"))
                .andExpect(jsonPath("$.profile.profileStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.profile.mainIndustryCode").value("01.1"))
                .andExpect(jsonPath("$.profile.region").value("Москва"))
                .andExpect(jsonPath("$.profile.revenue").value(15000000000.12))
                .andExpect(jsonPath("$.profile.hasLoan").value(true))
                .andExpect(jsonPath("$.profile.hasLeasing").value(false))
                .andExpect(jsonPath("$.profile.hasForeignTrade").value(true))
                .andExpect(jsonPath("$.profile.foreignTradeCountries").value("Китай"))
                .andExpect(jsonPath("$.group.length()").value(2))
                .andExpect(jsonPath("$.group[0].companyId").value("ctx_group_member"))
                .andExpect(jsonPath("$.group[0].mainIndustryCode").value("62"))
                .andExpect(jsonPath("$.group[1].companyId").value("ctx_main"))
                .andExpect(jsonPath("$.counterparties.length()").value(5))
                .andExpect(jsonPath("$.counterparties[*].companyId")
                        .value(org.hamcrest.Matchers.contains("ctx_a", "ctx_b", "ctx_c", "ctx_d", "ctx_e")))
                .andExpect(jsonPath("$.counterparties[0].profileStatus").value("MISSING"))
                .andExpect(jsonPath("$.counterparties[0].mainIndustryCode").isEmpty())
                .andExpect(jsonPath("$.counterparties[0].incomingPaymentAmount").value(250))
                .andExpect(jsonPath("$.counterparties[0].outgoingPaymentAmount").value(150))
                .andExpect(jsonPath("$.counterparties[0].totalPaymentAmount").value(400))
                .andExpect(jsonPath("$.counterparties[0].paymentShare").value(0.4))
                .andExpect(jsonPath("$.totalPaymentAmount").value(1000))
                .andExpect(jsonPath("$.concentration.top1Share").value(0.4))
                .andExpect(jsonPath("$.concentration.top3Share").value(0.85))
                .andExpect(jsonPath("$.missingData").value(org.hamcrest.Matchers.contains(
                        "counterparties.ctx_a.mainIndustryCode")));
    }

    @Test
    void givenNoGroupOrPayments_whenGetContext_thenReturnEmptyListsAndUnknownShares() throws Exception {
        mvc.perform(get("/api/companies/ctx_empty").contextPath("/api").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group").isEmpty())
                .andExpect(jsonPath("$.counterparties").isEmpty())
                .andExpect(jsonPath("$.totalPaymentAmount").value(0))
                .andExpect(jsonPath("$.concentration.top1Share").isEmpty())
                .andExpect(jsonPath("$.concentration.top3Share").isEmpty())
                .andExpect(jsonPath("$.missingData", hasItem("profile.missing")))
                .andExpect(jsonPath("$.missingData", hasItem("profile.mainIndustryCode")))
                .andExpect(jsonPath("$.missingData", hasItem("profile.hasLoan")))
                .andExpect(jsonPath("$.missingData", hasItem("payments")))
                .andExpect(jsonPath("$.missingData", hasItem("paymentShares")));
    }

    @Test
    void givenSmallConfiguredLimit_whenGetContext_thenCalculateConcentrationAcrossThreeCounterparties() {
        CompanyContext context = new CompanyContextService(companies, payments, 2).getContext("ctx_main");
        assertThat(context.counterparties()).hasSize(2);
        assertThat(context.totalPaymentAmount()).isEqualByComparingTo("1000");
        assertThat(context.concentration().top3Share()).isEqualByComparingTo("0.85");
        assertThatThrownBy(() -> new CompanyContextService(companies, payments, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void givenZeroAmountsAndTies_whenGetContext_thenReturnStableOrderWithoutDividingByZero() {
        execute("""
                UPDATE company_payment_summaries SET total_payment_amount = 0
                WHERE payer_company_id = 'ctx_main' OR recipient_company_id = 'ctx_main'
                """);
        CompanyContext context = new CompanyContextService(companies, payments, 5).getContext("ctx_main");
        assertThat(context.counterparties()).extracting(Counterparty::companyId)
                .containsExactly("ctx_a", "ctx_b", "ctx_c", "ctx_d", "ctx_e");
        assertThat(context.counterparties()).allSatisfy(c -> assertThat(c.paymentShare()).isNull());
        assertThat(context.concentration().top3Share()).isNull();
        assertThat(context.missingData()).contains("paymentShares").doesNotContain("payments");
    }

    @Test
    void givenConflictingProfileAndPartialGroup_whenGetContext_thenReportMissingFeatures() {
        execute("UPDATE companies SET group_id = 'ctx_group', main_industry_code = ' ' "
                + "WHERE company_id = 'ctx_conflict'");
        execute("UPDATE companies SET foreign_trade_countries = ' ' WHERE company_id = 'ctx_main'");
        CompanyContextService service = new CompanyContextService(companies, payments, 5);
        assertThat(service.getContext("ctx_conflict").missingData())
                .contains("profile.conflicting", "profile.mainIndustryCode");
        assertThat(service.getContext("ctx_main").missingData())
                .contains("profile.foreignTradeCountries", "group.ctx_conflict.mainIndustryCode");
    }

    @Test
    void givenOneCounterparty_whenGetContext_thenConcentrationIsOneAndIncomingDirectionIsPreserved() {
        CompanyContext context = new CompanyContextService(companies, payments, 5).getContext("ctx_b");
        assertThat(context.counterparties()).hasSize(1);
        assertThat(context.counterparties().getFirst().incomingPaymentAmount()).isEqualByComparingTo("300");
        assertThat(context.counterparties().getFirst().outgoingPaymentAmount()).isZero();
        assertThat(context.concentration().top1Share()).isEqualByComparingTo("1");
        assertThat(context.concentration().top3Share()).isEqualByComparingTo("1");
    }

    @Test
    void givenUnknownCompany_whenGetContext_thenNotFound() throws Exception {
        mvc.perform(get("/api/companies/ctx_unknown").contextPath("/api").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenPublicSpecification_whenGetOpenApi_thenDescribeCompanyContextUnderCommonPrefix() throws Exception {
        mvc.perform(get("/api/v3/api-docs").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost/api"))
                .andExpect(jsonPath("$.paths['/companies/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/companies/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.components.schemas.CompanyContext.properties.missingData").exists())
                .andExpect(jsonPath("$.components.schemas.CompanyContext.properties.profile").exists());
    }

    @Test
    void givenMissingOrInsufficientRole_whenGetContext_thenRejectRequest() throws Exception {
        mvc.perform(get("/api/companies/ctx_main").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/companies/ctx_main").contextPath("/api").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }
    private void execute(String sql) {
        entities.createNativeQuery(sql).executeUpdate();
    }
}
