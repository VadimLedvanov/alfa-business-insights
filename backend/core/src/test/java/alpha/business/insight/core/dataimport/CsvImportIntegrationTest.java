package alpha.business.insight.core.dataimport;

import alpha.business.insight.core.dto.CsvImportReport;
import alpha.business.insight.core.dto.FileReport;
import alpha.business.insight.core.model.Company;
import alpha.business.insight.core.model.CompanyPaymentSummary;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.model.PersonCompanyLink;
import alpha.business.insight.core.service.CsvImportService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
@DisplayName("Импорт CSV")
class CsvImportIntegrationTest {
    private static final List<String> FILES = List.of("df_Uid_data_hack.csv", "df_Fid_Uid_link_hack.csv",
            "df_Uid_payment_hack.csv");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CsvImportService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entities;
    @TempDir
    Path directory;

    @BeforeEach
    void prepareData() throws IOException {
        jdbc.execute("TRUNCATE company_payment_summaries, person_company_links, companies CASCADE");
        for (String name : FILES) {
            try (var stream = getClass().getResourceAsStream("/csv-import/" + name)) {
                Files.copy(stream, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Nested
    @DisplayName("Первичная загрузка")
    class InitialImport {

        @Test
        @DisplayName("Сохранение профилей, связей, платёжных агрегатов и отчёта")
        void givenProfilesLinksAndPayments_whenImportFiles_thenSaveDataAndReportQuality() throws IOException {
            CsvImportReport report = importFiles();

            Company company = entities.find(Company.class, "U_1");
            UUID paymentId = jdbc.queryForObject("""
                    SELECT id FROM company_payment_summaries WHERE payer_company_id = 'U_1' AND recipient_company_id = 'U_3'
                    """, UUID.class);
            CompanyPaymentSummary payment = entities.find(CompanyPaymentSummary.class, paymentId);
            UUID linkId = jdbc.queryForObject("SELECT id FROM person_company_links WHERE company_id = 'U_1'", UUID.class);
            PersonCompanyLink link = entities.find(PersonCompanyLink.class, linkId);

            assertAll(
                    () -> assertThat(report.files()).extracting(FileReport::read)
                            .containsExactly(5L, 4L, 8L),
                    () -> assertThat(report.files()).extracting(FileReport::accepted)
                            .containsExactly(4L, 3L, 4L),
                    () -> assertThat(report.files()).extracting(FileReport::rejected)
                            .containsExactly(1L, 1L, 4L),
                    () -> assertThat(report.files()).extracting(FileReport::repeatedKeys)
                            .containsExactly(2L, 1L, 1L),
                    () -> assertThat(report.files().getLast().errorSamples()).hasSize(4),
                    () -> assertThat(report.conflictingCompanyProfiles()).isEqualTo(1),
                    () -> assertThat(report.conflictingCompanyIdSamples()).containsExactly("U_2"),
                    () -> assertThat(report.companiesWithoutProfile()).isEqualTo(2),
                    () -> assertThat(count("companies")).isEqualTo(4),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3),
                    () -> assertThat(company.getProfileStatus()).isEqualTo(CompanyProfileStatus.AVAILABLE),
                    () -> assertThat(company.getMainIndustryCode()).isEqualTo("01.01"),
                    () -> assertThat(company.getMainIndustryName()).isEqualTo("Название; с разделителем\nи переносом"),
                    () -> assertThat(company.getRevenue()).isEqualByComparingTo("12345678901234567890.12"),
                    () -> assertThat(company.getHasLoan()).isTrue(),
                    () -> assertThat(company.getHasLeasing()).isFalse(),
                    () -> assertThat(company.getEmployeeCountRangeSpark()).isNull(),
                    () -> assertThat(entities.find(Company.class, "U_2").getProfileStatus())
                            .isEqualTo(CompanyProfileStatus.CONFLICTING),
                    () -> assertThat(entities.find(Company.class, "U_2").getMainIndustryCode()).isNull(),
                    () -> assertThat(entities.find(Company.class, "U_3").getProfileStatus())
                            .isEqualTo(CompanyProfileStatus.MISSING),
                    () -> assertThat(payment.getTotalPaymentAmount()).isEqualByComparingTo("12.30"),
                    () -> assertThat(payment.getPaymentCount()).isEqualTo(4),
                    () -> assertThat(payment.getPayerCompany().getId()).isEqualTo("U_1"),
                    () -> assertThat(link.getRegionalCenterName()).isEqualTo("Обновлённый"),
                    () -> assertThat(jdbc.queryForObject("SELECT city_name FROM person_company_links WHERE company_id = 'U_3'",
                            String.class)).isNull()
            );
        }
    }

    @Nested
    @DisplayName("Повторная загрузка")
    class RepeatedImport {

        @Test
        @DisplayName("Сохранение идентификаторов и обновление сумм платежей")
        void givenExistingData_whenImportFilesAgain_thenPreserveIdsAndRefreshAmounts() throws IOException {
            importFiles();
            var paymentsBefore = jdbc.queryForList("SELECT * FROM company_payment_summaries ORDER BY id");
            var linksBefore = jdbc.queryForList("SELECT * FROM person_company_links ORDER BY id");

            CsvImportReport report = importFiles();

            assertAll(
                    () -> assertThat(jdbc.queryForList("SELECT * FROM company_payment_summaries ORDER BY id"))
                            .isEqualTo(paymentsBefore),
                    () -> assertThat(jdbc.queryForList("SELECT * FROM person_company_links ORDER BY id")).isEqualTo(linksBefore),
                    () -> assertThat(count("companies")).isEqualTo(4),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(report.files().getLast().accepted()).isEqualTo(4)
            );

            Path payments = directory.resolve(FILES.getLast());
            Files.writeString(payments, Files.readString(payments).replace("10.10", "20.10"));

            importFiles();

            assertThat(jdbc.queryForObject("""
                    SELECT total_payment_amount FROM company_payment_summaries
                    WHERE payer_company_id = 'U_1' AND recipient_company_id = 'U_3'
                    """, BigDecimal.class)).isEqualByComparingTo("22.30");
        }

        @Test
        @DisplayName("Заполнение отсутствующего профиля с сохранением связей")
        void givenCompanyWithoutProfile_whenImportProfile_thenEnrichCompanyAndPreserveLinks() throws IOException {
            importFiles();
            Path companies = directory.resolve(FILES.getFirst());
            Files.writeString(companies, Files.readString(companies).replace("U_1", "U_3"));

            importFiles();

            assertAll(
                    () -> assertThat(entities.find(Company.class, "U_3").getProfileStatus())
                            .isEqualTo(CompanyProfileStatus.AVAILABLE),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3)
            );
        }
    }

    @Nested
    @DisplayName("Ошибки загрузки")
    class ImportFailures {

        @Test
        @DisplayName("Откат повреждённого CSV и повтор загрузки после исправления")
        void givenMalformedCsv_whenImportFiles_thenThrowUncheckedIOExceptionAndAllowRetry() throws IOException {
            importFiles();
            var companiesBefore = jdbc.queryForList("SELECT * FROM companies ORDER BY company_id");
            Path payments = directory.resolve(FILES.getLast());
            Files.writeString(payments, "BENEFICIARY;PAYER;PAY_AMT;PAY_CNT\n\"unterminated");

            assertThrows(UncheckedIOException.class, () -> importFiles());

            assertAll(
                    () -> assertThat(jdbc.queryForList("SELECT * FROM companies ORDER BY company_id")).isEqualTo(companiesBefore),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3)
            );

            try (var stream = getClass().getResourceAsStream("/csv-import/" + FILES.getLast())) {
                Files.copy(stream, payments, StandardCopyOption.REPLACE_EXISTING);
            }

            importFiles();

            assertThat(count("company_payment_summaries")).isEqualTo(3);
        }

        @Test
        @DisplayName("Отклонение неверных заголовков CSV")
        void givenWrongHeaders_whenImportFiles_thenThrowIOExceptionAndPreserveDatabase() throws IOException {
            Files.writeString(directory.resolve(FILES.getLast()), "WRONG;HEADER\n1;2\n");

            IOException exception = assertThrows(IOException.class, () -> importFiles());

            assertAll(
                    () -> assertThat(exception).hasMessageContaining("expected headers"),
                    () -> assertThat(count("companies")).isZero()
            );
        }

        @Test
        @DisplayName("Откат изменений при переполнении количества платежей")
        void givenPaymentCountOverflow_whenImportFiles_thenThrowDataIntegrityViolationExceptionAndRollback() throws IOException {
            importFiles();
            Path companies = directory.resolve(FILES.getFirst());
            Files.writeString(companies, Files.readString(companies).replace("group_1", "changed_group"));
            Files.writeString(directory.resolve(FILES.getLast()), """
                    BENEFICIARY;PAYER;PAY_AMT;PAY_CNT
                    U_3;U_1;1;9223372036854775807
                    U_3;U_1;1;1
                    """);

            assertThrows(DataIntegrityViolationException.class, () -> importFiles());

            assertAll(
                    () -> assertThat(entities.find(Company.class, "U_1").getGroupId()).isEqualTo("group_1"),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3)
            );
        }
    }

    private CsvImportReport importFiles() throws IOException {
        try (var companies = Files.newInputStream(directory.resolve(FILES.getFirst()));
             var personLinks = Files.newInputStream(directory.resolve(FILES.get(1)));
             var payments = Files.newInputStream(directory.resolve(FILES.getLast()))) {
            return service.importFiles(companies, personLinks, payments);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
