package alpha.business.insight.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import alpha.business.insight.core.CoreApplication;
import alpha.business.insight.core.model.CsvImportSource;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.DirectoryResourceAccessor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Проверяет однократную начальную загрузку CSV при запуске приложения. */
@Testcontainers
@DisplayName("Начальная миграция CSV")
@Tag("integration")
@Tag("data-import")
class CsvStartupMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    @TempDir Path resources;

    /** Сценарии загрузки данных во время запуска приложения. */
    @Nested
    @DisplayName("Запуск приложения")
    class ApplicationStartup {

        /** Проверяет перенос packaged CSV и сохранение изменений после перезапуска. */
        @Test
        @DisplayName("Однократная загрузка CSV с сохранением изменений после перезапуска")
        void givenPackagedCsv_whenStartAndRestartApplication_thenImportOnceAndPreserveChanges() {
            // Подготовка изолированной схемы PostgreSQL.
            String url = createSchema("startup_import");
            JdbcTemplate jdbc = jdbc(url);

            // Действие и проверки первой стартовой миграции.
            try (var context = startApplication(url)) {
                Map<String, Object> migrationSnapshot =
                        new LinkedHashMap<>(
                                jdbc.queryForMap(
                                        """
                        SELECT (SELECT COUNT(*) FROM companies) AS companies,
                               (SELECT COUNT(*) FROM person_company_links) AS person_company_links,
                               (SELECT COUNT(*) FROM company_payment_summaries) AS payment_summaries,
                               (SELECT SUM(total_payment_amount) FROM company_payment_summaries) AS payment_amount,
                               (SELECT SUM(payment_count) FROM company_payment_summaries) AS payment_count
                        """));
                migrationSnapshot.put(
                        "conflictingCompanyRows",
                        jdbc.queryForList(
                                "SELECT profile_status FROM companies WHERE company_id = 'U_428697' LIMIT 1"));
                TestEvidence.output("Фактические результаты начальной миграции", migrationSnapshot);

                assertAll(
                        () -> assertThat(context.isActive()).isTrue(),
                        () -> assertThat(count(jdbc, "companies")).isEqualTo(204708),
                        () -> assertThat(count(jdbc, "person_company_links")).isEqualTo(12514),
                        () ->
                                assertThat(count(jdbc, "company_payment_summaries"))
                                        .isEqualTo(1380568),
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT SUM(total_payment_amount) FROM company_payment_summaries",
                                                        BigDecimal.class))
                                        .isEqualByComparingTo("55991235586000.0"),
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT SUM(payment_count) FROM company_payment_summaries",
                                                        Long.class))
                                        .isEqualTo(24515786),
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT profile_status FROM companies WHERE company_id = 'U_428697'",
                                                        String.class))
                                        .isEqualTo("CONFLICTING"));
            }

            // Подготовка контрольных изменений перед повторным запуском.
            var checkpoint =
                    jdbc.queryForMap("SELECT * FROM databasechangelog WHERE id = '20261022-04'");
            assertThat(checkpoint.get("filename")).isEqualTo("db/changelog/changelog-1.xml");
            jdbc.update(
                    "UPDATE companies SET city = 'Проверка однократной загрузки' WHERE company_id = 'U_67'");
            jdbc.update(
                    """
                    UPDATE company_payment_summaries SET total_payment_amount = total_payment_amount + 1
                    WHERE id = (SELECT id FROM company_payment_summaries ORDER BY id LIMIT 1)
                    """);

            // Действие и проверки того, что повторный запуск не перезаписывает данные.
            try (var context = startApplication(url)) {
                Map<String, Object> restartSnapshot = new LinkedHashMap<>();
                Map<String, Object> checkpointAfterRestart =
                        jdbc.queryForMap(
                                "SELECT * FROM databasechangelog WHERE id = '20261022-04'");
                String cityAfterRestart =
                        jdbc.queryForObject(
                                "SELECT city FROM companies WHERE company_id = 'U_67'",
                                String.class);
                BigDecimal paymentAmountAfterRestart =
                        jdbc.queryForObject(
                                "SELECT SUM(total_payment_amount) FROM company_payment_summaries",
                                BigDecimal.class);
                long paymentSummaryCountAfterRestart = count(jdbc, "company_payment_summaries");
                restartSnapshot.put("changelogCheckpoint", checkpointAfterRestart);
                restartSnapshot.put("companyCity", cityAfterRestart);
                restartSnapshot.put("paymentAmount", paymentAmountAfterRestart);
                restartSnapshot.put("paymentSummaryCount", paymentSummaryCountAfterRestart);
                TestEvidence.output(
                        "Фактические результаты после повторного запуска", restartSnapshot);

                assertAll(
                        () -> assertThat(context.isActive()).isTrue(),
                        () -> assertThat(checkpointAfterRestart).isEqualTo(checkpoint),
                        () ->
                                assertThat(cityAfterRestart)
                                        .isEqualTo("Проверка однократной загрузки"),
                        () ->
                                assertThat(paymentAmountAfterRestart)
                                        .isEqualByComparingTo("55991235586001.0"),
                        () -> assertThat(paymentSummaryCountAfterRestart).isEqualTo(1380568));
            }
        }
    }

    /** Сценарии выполнения changeset после неудачной и повторной миграции. */
    @Nested
    @DisplayName("Выполнение changeset")
    class MigrationExecution {

        /** Проверяет откат частичного импорта и повтор миграции после исправления CSV. */
        @Test
        @DisplayName("Откат отклонённых записей и повтор миграции после исправления CSV")
        void givenRejectedCsvRows_whenMigrate_thenRollbackAndAllowRetry() throws Exception {
            // Подготовка изолированной схемы и копий ресурсов миграции.
            String url = createSchema("failed_import");
            JdbcTemplate jdbc = jdbc(url);
            for (String name : List.of("master.xml", "changelog-1.xml")) {
                Path target = resources.resolve("db/changelog/" + name);
                Files.createDirectories(target.getParent());
                try (var input = getClass().getResourceAsStream("/db/changelog/" + name)) {
                    Files.copy(input, target);
                }
            }
            for (CsvImportSource source : CsvImportSource.values()) {
                Path target = resources.resolve("data/csv/" + source.getFileName());
                Files.createDirectories(target.getParent());
                try (var input =
                        getClass().getResourceAsStream("/csv-import/" + source.getFileName())) {
                    Files.copy(input, target);
                }
                TestEvidence.input(
                        "CSV перед первой миграцией: " + source.getFileName(),
                        Files.readString(target, StandardCharsets.UTF_8));
            }

            // Действие: первый запуск должен отклонить некорректные исходные строки.
            LiquibaseException exception =
                    assertThrows(LiquibaseException.class, () -> migrate(url));

            TestEvidence.exception(exception);
            Map<String, Object> rollbackSnapshot = new LinkedHashMap<>();
            rollbackSnapshot.put("companies", count(jdbc, "companies"));
            rollbackSnapshot.put("personCompanyLinks", count(jdbc, "person_company_links"));
            rollbackSnapshot.put("paymentSummaries", count(jdbc, "company_payment_summaries"));
            rollbackSnapshot.put(
                    "changeSetCount",
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM databasechangelog WHERE id = '20261022-04'",
                            Long.class));
            TestEvidence.output("Фактическое состояние после отката миграции", rollbackSnapshot);

            // Проверки полного отката данных и changeset.
            assertAll(
                    () ->
                            assertThat(exception)
                                    .hasStackTraceContaining(
                                            "Начальные CSV содержат ошибочные строки"),
                    () -> assertThat(count(jdbc, "companies")).isZero(),
                    () -> assertThat(count(jdbc, "person_company_links")).isZero(),
                    () -> assertThat(count(jdbc, "company_payment_summaries")).isZero(),
                    () ->
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM databasechangelog WHERE id = '20261022-04'",
                                                    Long.class))
                                    .isZero());

            // Подготовка корректных минимальных CSV для повторной миграции.
            for (CsvImportSource source : CsvImportSource.values()) {
                try (CSVPrinter printer =
                        new CSVPrinter(
                                Files.newBufferedWriter(
                                        resources.resolve("data/csv/" + source.getFileName()),
                                        StandardCharsets.UTF_8),
                                CSVFormat.DEFAULT
                                        .builder()
                                        .setDelimiter(';')
                                        .setHeader(
                                                source.getColumns().stream()
                                                        .map(CsvImportSource.Column::header)
                                                        .toArray(String[]::new))
                                        .get())) {
                    if (source == CsvImportSource.COMPANIES) {
                        List<String> company =
                                new ArrayList<>(
                                        Collections.nCopies(source.getColumns().size(), ""));
                        company.set(0, "U_1");
                        printer.printRecord(company);
                    } else if (source == CsvImportSource.PERSON_LINKS) {
                        printer.printRecord("F_1", "", "", "U_2");
                    } else {
                        printer.printRecord("U_2", "U_1", "12.30", "4");
                    }
                }
                TestEvidence.input(
                        "Исправленный CSV перед повторной миграцией: " + source.getFileName(),
                        Files.readString(
                                resources.resolve("data/csv/" + source.getFileName()),
                                StandardCharsets.UTF_8));
            }

            // Действие: повторно запускает миграцию после исправления файлов.
            migrate(url);

            Map<String, Object> retrySnapshot = new LinkedHashMap<>();
            retrySnapshot.put("companies", count(jdbc, "companies"));
            retrySnapshot.put("personCompanyLinks", count(jdbc, "person_company_links"));
            retrySnapshot.put("paymentSummaries", count(jdbc, "company_payment_summaries"));
            retrySnapshot.put(
                    "changeSetCount",
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM databasechangelog WHERE id = '20261022-04'",
                            Long.class));
            TestEvidence.output("Фактическое состояние после повторной миграции", retrySnapshot);

            // Проверки сохранённых строк и отметки выполненного changeset.
            assertAll(
                    () -> assertThat(count(jdbc, "companies")).isEqualTo(2),
                    () -> assertThat(count(jdbc, "person_company_links")).isEqualTo(1),
                    () -> assertThat(count(jdbc, "company_payment_summaries")).isEqualTo(1),
                    () ->
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM databasechangelog WHERE id = '20261022-04'",
                                                    Long.class))
                                    .isEqualTo(1));
        }
    }

    /** Создаёт отдельную схему PostgreSQL для изолированного запуска миграции. */
    private String createSchema(String schema) {
        jdbc(POSTGRES.getJdbcUrl()).execute("CREATE SCHEMA " + schema);
        return POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
    }

    /** Создаёт JDBC-шаблон для указанной схемы тестового PostgreSQL. */
    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** Запускает приложение без web-сервера на указанной схеме PostgreSQL. */
    private ConfigurableApplicationContext startApplication(String url) {
        return new SpringApplicationBuilder(CoreApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    /** Выполняет Liquibase-миграции из временной копии ресурсов. */
    private void migrate(String url) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var accessor = new DirectoryResourceAccessor(resources);
                var liquibase =
                        new Liquibase(
                                "db/changelog/master.xml",
                                accessor,
                                new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    /** Возвращает число строк таблицы через подключённую тестовую базу. */
    private long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
