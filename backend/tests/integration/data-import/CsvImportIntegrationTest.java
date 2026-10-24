package alpha.business.insight.core.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import alpha.business.insight.core.dto.CsvImportReport;
import alpha.business.insight.core.dto.FileReport;
import alpha.business.insight.core.model.Company;
import alpha.business.insight.core.model.CompanyPaymentSummary;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.model.PersonCompanyLink;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.service.CsvImportService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
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

/** Проверяет импорт реальных CSV-файлов в PostgreSQL и сохранение результатов. */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
@DisplayName("Импорт CSV")
@Tag("integration")
@Tag("data-import")
class CsvImportIntegrationTest {
    private static final List<String> FILES =
            List.of("df_Uid_data_hack.csv", "df_Fid_Uid_link_hack.csv", "df_Uid_payment_hack.csv");

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    /** Подключает Spring-контекст к PostgreSQL-контейнеру теста. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private CsvImportService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @TempDir Path directory;

    /** Очищает импортированные таблицы и копирует исходные CSV в каталог теста. */
    @BeforeEach
    void prepareData() throws IOException {
        jdbc.execute("TRUNCATE company_payment_summaries, person_company_links, companies CASCADE");
        for (String name : FILES) {
            try (var stream = getClass().getResourceAsStream("/csv-import/" + name)) {
                Files.copy(stream, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Сценарии первичной загрузки исходных CSV-файлов. */
    @Nested
    @DisplayName("Первичная загрузка")
    class InitialImport {

        /** Проверяет сохранение профилей, связей, платежей и качества отчёта импорта. */
        @Test
        @DisplayName("Сохранение профилей, связей, платёжных агрегатов и отчёта")
        void givenProfilesLinksAndPayments_whenImportFiles_thenSaveDataAndReportQuality()
                throws IOException {
            // Действие
            CsvImportReport report = importFiles();
            TestEvidence.output("Фактический отчёт импорта CSV", report);

            // Подготовка фактических сущностей для сверки с отчётом.
            Company company = entities.find(Company.class, "U_1");
            UUID paymentId =
                    jdbc.queryForObject(
                            """
                    SELECT id FROM company_payment_summaries WHERE payer_company_id = 'U_1' AND recipient_company_id = 'U_3'
                    """,
                            UUID.class);
            CompanyPaymentSummary payment = entities.find(CompanyPaymentSummary.class, paymentId);
            UUID linkId =
                    jdbc.queryForObject(
                            "SELECT id FROM person_company_links WHERE company_id = 'U_1'",
                            UUID.class);
            PersonCompanyLink link = entities.find(PersonCompanyLink.class, linkId);

            // Проверки отчёта и сохранённых данных.
            assertAll(
                    () ->
                            assertThat(report.files())
                                    .extracting(FileReport::read)
                                    .containsExactly(5L, 4L, 8L),
                    () ->
                            assertThat(report.files())
                                    .extracting(FileReport::accepted)
                                    .containsExactly(4L, 3L, 4L),
                    () ->
                            assertThat(report.files())
                                    .extracting(FileReport::rejected)
                                    .containsExactly(1L, 1L, 4L),
                    () ->
                            assertThat(report.files())
                                    .extracting(FileReport::repeatedKeys)
                                    .containsExactly(2L, 1L, 1L),
                    () -> assertThat(report.files().getLast().errorSamples()).hasSize(4),
                    () -> assertThat(report.conflictingCompanyProfiles()).isEqualTo(1),
                    () -> assertThat(report.conflictingCompanyIdSamples()).containsExactly("U_2"),
                    () -> assertThat(report.companiesWithoutProfile()).isEqualTo(2),
                    () -> assertThat(count("companies")).isEqualTo(4),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3),
                    () ->
                            assertThat(company.getProfileStatus())
                                    .isEqualTo(CompanyProfileStatus.AVAILABLE),
                    () -> assertThat(company.getMainIndustryCode()).isEqualTo("01.01"),
                    () ->
                            assertThat(company.getMainIndustryName())
                                    .isEqualTo("Название; с разделителем\nи переносом"),
                    () ->
                            assertThat(company.getRevenue())
                                    .isEqualByComparingTo("12345678901234567890.12"),
                    () -> assertThat(company.getHasLoan()).isTrue(),
                    () -> assertThat(company.getHasLeasing()).isFalse(),
                    () -> assertThat(company.getEmployeeCountRangeSpark()).isNull(),
                    () ->
                            assertThat(entities.find(Company.class, "U_2").getProfileStatus())
                                    .isEqualTo(CompanyProfileStatus.CONFLICTING),
                    () ->
                            assertThat(entities.find(Company.class, "U_2").getMainIndustryCode())
                                    .isNull(),
                    () ->
                            assertThat(entities.find(Company.class, "U_3").getProfileStatus())
                                    .isEqualTo(CompanyProfileStatus.MISSING),
                    () -> assertThat(payment.getTotalPaymentAmount()).isEqualByComparingTo("12.30"),
                    () -> assertThat(payment.getPaymentCount()).isEqualTo(4),
                    () -> assertThat(payment.getPayerCompany().getId()).isEqualTo("U_1"),
                    () -> assertThat(link.getRegionalCenterName()).isEqualTo("Обновлённый"),
                    () ->
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT city_name FROM person_company_links WHERE company_id = 'U_3'",
                                                    String.class))
                                    .isNull());
        }
    }

    /** Сценарии повторной загрузки и обогащения уже сохранённых данных. */
    @Nested
    @DisplayName("Повторная загрузка")
    class RepeatedImport {

        /** Проверяет повторный импорт без изменения существующих идентификаторов и агрегатов. */
        @Test
        @DisplayName("Сохранение идентификаторов и обновление сумм платежей")
        void givenExistingData_whenImportFilesAgain_thenPreserveIdsAndRefreshAmounts()
                throws IOException {
            // Подготовка первоначального состояния и снимков идентификаторов.
            CsvImportReport initialReport = importFiles();
            TestEvidence.output("Отчёт исходного импорта CSV", initialReport);
            var paymentsBefore =
                    jdbc.queryForList("SELECT * FROM company_payment_summaries ORDER BY id");
            var linksBefore = jdbc.queryForList("SELECT * FROM person_company_links ORDER BY id");

            // Действие: повторный импорт не меняет сохранённые строки.
            CsvImportReport report = importFiles();
            TestEvidence.output("Фактический отчёт повторного импорта CSV", report);

            // Проверки сохранённых значений и состава отчёта.
            assertAll(
                    () ->
                            assertThat(
                                            jdbc.queryForList(
                                                    "SELECT * FROM company_payment_summaries ORDER BY id"))
                                    .isEqualTo(paymentsBefore),
                    () ->
                            assertThat(
                                            jdbc.queryForList(
                                                    "SELECT * FROM person_company_links ORDER BY id"))
                                    .isEqualTo(linksBefore),
                    () -> assertThat(count("companies")).isEqualTo(4),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(report.files().getLast().accepted()).isEqualTo(4));

            // Изменяет сумму в CSV и проверяет обновление агрегата.
            Path payments = directory.resolve(FILES.getLast());
            Files.writeString(payments, Files.readString(payments).replace("10.10", "20.10"));

            CsvImportReport changedPaymentReport = importFiles();
            TestEvidence.output(
                    "Фактический отчёт после изменения платёжного CSV", changedPaymentReport);

            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT total_payment_amount FROM company_payment_summaries
                    WHERE payer_company_id = 'U_1' AND recipient_company_id = 'U_3'
                    """,
                                    BigDecimal.class))
                    .isEqualByComparingTo("22.30");
        }

        /** Проверяет заполнение отсутствующего профиля с сохранением существующих связей. */
        @Test
        @DisplayName("Заполнение отсутствующего профиля с сохранением связей")
        void givenCompanyWithoutProfile_whenImportProfile_thenEnrichCompanyAndPreserveLinks()
                throws IOException {
            // Подготовка: загружает данные и заменяет компанию в файле профиля.
            CsvImportReport updatedReport = importFiles();
            TestEvidence.output("Отчёт повторного импорта профиля", updatedReport);
            Path companies = directory.resolve(FILES.getFirst());
            Files.writeString(companies, Files.readString(companies).replace("U_1", "U_3"));

            // Действие
            CsvImportReport enrichedReport = importFiles();
            TestEvidence.output("Фактический отчёт обогащения профиля", enrichedReport);

            // Проверки нового профиля и прежних связей.
            assertAll(
                    () ->
                            assertThat(entities.find(Company.class, "U_3").getProfileStatus())
                                    .isEqualTo(CompanyProfileStatus.AVAILABLE),
                    () -> assertThat(count("person_company_links")).isEqualTo(2),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3));
        }
    }

    /** Сценарии отказа импорта и проверки транзакционного отката. */
    @Nested
    @DisplayName("Ошибки загрузки")
    class ImportFailures {

        /** Проверяет отказ разбора CSV до изменения базы и успешный повтор после исправления файла. */
        @Test
        @DisplayName("Повреждённый CSV отклоняется до записи и допускает повтор")
        void givenMalformedCsv_whenImportFiles_thenThrowUncheckedIOExceptionAndAllowRetry()
                throws IOException {
            // Подготовка корректной базы и повреждённого платёжного файла.
            CsvImportReport initialReport = importFiles();
            TestEvidence.output("Отчёт до загрузки повреждённого CSV", initialReport);
            var companiesBefore = jdbc.queryForList("SELECT * FROM companies ORDER BY company_id");
            TestEvidence.output("Компании до отказа", companiesBefore);
            Path payments = directory.resolve(FILES.getLast());
            Files.writeString(payments, "BENEFICIARY;PAYER;PAY_AMT;PAY_CNT\n\"unterminated");
            TestEvidence.input(
                    "Повреждённый CSV перед импортом",
                    Map.of(
                            "file",
                            payments.getFileName().toString(),
                            "content",
                            Files.readString(payments)));

            // Действие: ошибка разбора должна отклонить весь импорт.
            assertThrows(
                    UncheckedIOException.class, () -> TestEvidence.capture(() -> importFiles()));
            attachDatabaseState("База данных после отказа импорта");

            // Проверки: база не изменилась.
            assertAll(
                    () ->
                            assertThat(
                                            jdbc.queryForList(
                                                    "SELECT * FROM companies ORDER BY company_id"))
                                    .isEqualTo(companiesBefore),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3));

            // Действие: восстанавливает исходный файл и повторяет импорт.
            try (var stream = getClass().getResourceAsStream("/csv-import/" + FILES.getLast())) {
                Files.copy(stream, payments, StandardCopyOption.REPLACE_EXISTING);
            }
            TestEvidence.input(
                    "Восстановленный CSV перед повторным импортом",
                    Map.of(
                            "file",
                            payments.getFileName().toString(),
                            "content",
                            Files.readString(payments)));

            CsvImportReport retryReport = importFiles();
            TestEvidence.output("Фактический отчёт после исправления CSV", retryReport);

            // Проверки успешного повтора.
            assertThat(count("company_payment_summaries")).isEqualTo(3);
        }

        /** Проверяет отказ для неверных заголовков без изменения базы. */
        @Test
        @DisplayName("Отклонение неверных заголовков CSV")
        void givenWrongHeaders_whenImportFiles_thenThrowIOExceptionAndPreserveDatabase()
                throws IOException {
            Files.writeString(directory.resolve(FILES.getLast()), "WRONG;HEADER\n1;2\n");
            attachDatabaseState("База данных перед отказом заголовков");

            // Проверяет отказ при несовпадающих заголовках.
            IOException exception =
                    assertThrows(
                            IOException.class, () -> TestEvidence.capture(() -> importFiles()));
            attachDatabaseState("База данных после отказа заголовков");

            assertAll(
                    () -> assertThat(exception).hasMessageContaining("expected headers"),
                    () -> assertThat(count("companies")).isZero());
        }

        /** Проверяет откат импорта при переполнении счётчика платежей. */
        @Test
        @DisplayName("Откат изменений при переполнении количества платежей")
        void
                givenPaymentCountOverflow_whenImportFiles_thenThrowDataIntegrityViolationExceptionAndRollback()
                        throws IOException {
            CsvImportReport initialReport = importFiles();
            TestEvidence.output("Отчёт исходного импорта перед переполнением", initialReport);
            attachDatabaseState("База данных до попытки переполнения");
            Path companies = directory.resolve(FILES.getFirst());
            Files.writeString(
                    companies, Files.readString(companies).replace("group_1", "changed_group"));
            Files.writeString(
                    directory.resolve(FILES.getLast()),
                    """
                    BENEFICIARY;PAYER;PAY_AMT;PAY_CNT
                    U_3;U_1;1;9223372036854775807
                    U_3;U_1;1;1
                    """);

            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> TestEvidence.capture(() -> importFiles()));
            attachDatabaseState("База данных после отката переполнения");

            assertAll(
                    () ->
                            assertThat(entities.find(Company.class, "U_1").getGroupId())
                                    .isEqualTo("group_1"),
                    () -> assertThat(count("company_payment_summaries")).isEqualTo(3));
        }
    }

    /** Импортирует три CSV-файла из текущего каталога тестовых данных. */
    private CsvImportReport importFiles() throws IOException {
        try (var companies = Files.newInputStream(directory.resolve(FILES.getFirst()));
                var personLinks = Files.newInputStream(directory.resolve(FILES.get(1)));
                var payments = Files.newInputStream(directory.resolve(FILES.getLast()))) {
            return service.importFiles(companies, personLinks, payments);
        }
    }

    /** Возвращает число строк указанной таблицы тестовой базы. */
    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    /** Прикладывает ограниченные выборки фактического состояния таблиц импорта. */
    private void attachDatabaseState(String attachmentName) {
        TestEvidence.output(
                attachmentName,
                Map.of(
                        "companies",
                                jdbc.queryForList(
                                        "SELECT * FROM companies ORDER BY company_id LIMIT 20"),
                        "personCompanyLinks",
                                jdbc.queryForList(
                                        "SELECT * FROM person_company_links ORDER BY id LIMIT 20"),
                        "paymentSummaries",
                                jdbc.queryForList(
                                        "SELECT * FROM company_payment_summaries ORDER BY id LIMIT 20")));
    }
}
