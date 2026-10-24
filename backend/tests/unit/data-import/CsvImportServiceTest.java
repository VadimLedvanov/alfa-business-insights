package alpha.business.insight.core.qa.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.model.CsvImportSource;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.repository.CsvImportRepository;
import alpha.business.insight.core.service.CsvImportService;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Проверяет разбор CSV на изолированном репозитории с перехватом COPY-строк. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Разбор CSV")
@Tag("unit")
@Epic("Backend")
@Feature("Импорт CSV")
@Tag("data-import")
class CsvImportServiceTest {
    @Mock private CsvImportRepository repository;

    /** Проверяет отчёт об отклонённых платежах и строки, переданные в COPY. */
    @Test
    @DisplayName("TestUnitReportsRejectedPaymentRows")
    void TestUnitReportsRejectedPaymentRows() throws Exception {
        // Подготавливает CSV с одной корректной и одной ошибочной строкой платежа.
        String companies = csv(CsvImportSource.COMPANIES, row(CsvImportSource.COMPANIES, "U_1"));
        String persons =
                csv(CsvImportSource.PERSON_LINKS, row(CsvImportSource.PERSON_LINKS, "F_1"));
        String payments =
                String.join(
                                ";",
                                CsvImportSource.PAYMENTS.getColumns().stream()
                                        .map(CsvImportSource.Column::header)
                                        .toList())
                        + "\nU_2;U_1;not-a-number;1\nU_3;U_1; 12.50 ;2\n";

        Map<CsvImportSource, ByteArrayOutputStream> copied = captureCopiedRows();
        Allure.step(
                "Импортировать CSV платежей и сохранить только корректные строки",
                () -> {
                    TestEvidence.input("CSV компаний", companies);
                    TestEvidence.input("CSV связей", persons);
                    TestEvidence.input("CSV платежей", payments);
                    TestEvidence.input(
                            "Окружение", "unit: repository mocked; COPY rows captured in memory");
                    stubReportQueries();
                    // Импортирует подготовленные CSV-файлы.
                    var report =
                            TestEvidence.capture(
                                    () ->
                                            new CsvImportService(repository)
                                                    .importFiles(
                                                            stream(companies),
                                                            stream(persons),
                                                            stream(payments)));
                    TestEvidence.output("Отчёт об импорте", report);
                    var copiedRows = parseRows(copied, CsvImportSource.PAYMENTS);
                    TestEvidence.output(
                            "Валидные строки, переданные в COPY",
                            copiedRows.stream().map(record -> record.toList()).toList());

                    // Проверяет отчёт, пример ошибки и строки, переданные в COPY.
                    var paymentReport = report.files().get(2);
                    assertThat(paymentReport.read())
                            .as("число прочитанных платёжных строк")
                            .isEqualTo(2);
                    assertThat(paymentReport.accepted())
                            .as("число принятых платёжных строк")
                            .isEqualTo(1);
                    assertThat(paymentReport.rejected())
                            .as("число отклонённых платёжных строк")
                            .isEqualTo(1);
                    assertThat(paymentReport.errorSamples())
                            .as("пример ошибки PAY_AMT")
                            .singleElement()
                            .satisfies(
                                    error -> {
                                        assertThat(error.recordNumber()).isEqualTo(1);
                                        assertThat(error.reason())
                                                .isEqualTo("PAY_AMT: invalid DECIMAL value");
                                    });
                    assertThat(copiedRows)
                            .as("валидная платёжная строка, переданная в COPY")
                            .singleElement()
                            .satisfies(
                                    record -> {
                                        assertThat(record.get(0))
                                                .as("BENEFICIARY после нормализации")
                                                .isEqualTo("U_3");
                                        assertThat(record.get(1)).as("PAYER").isEqualTo("U_1");
                                        assertThat(record.get(2))
                                                .as("PAY_AMT после trim")
                                                .isEqualTo("12.50");
                                        assertThat(record.get(3)).as("PAY_CNT").isEqualTo("2");
                                        assertThat(record.get(4))
                                                .as("порядковый идентификатор строки COPY")
                                                .isEqualTo("2");
                                    });
                });
    }

    /** Проверяет допустимые границы суммы и счётчика платежа на сохранённых COPY-строках. */
    @ParameterizedTest(name = "{displayName} [{index}]: {0}")
    @CsvSource({
        "zero amount and count,0,0",
        "smallest positive amount,0.01,1",
        "count below signed BIGINT max,1,9223372036854775806",
        "signed BIGINT max count,1,9223372036854775807"
    })
    @DisplayName("TestUnitAcceptPaymentBoundaryValues")
    void TestUnitAcceptPaymentBoundaryValues(String label, String amount, String count)
            throws Exception {
        String companies = csv(CsvImportSource.COMPANIES, row(CsvImportSource.COMPANIES, "U_1"));
        String persons =
                csv(CsvImportSource.PERSON_LINKS, row(CsvImportSource.PERSON_LINKS, "F_1"));
        String payments = csv(CsvImportSource.PAYMENTS, "U_2;U_1;" + amount + ";" + count);
        Map<CsvImportSource, ByteArrayOutputStream> copied = captureCopiedRows();

        Allure.step(
                "Принять граничные значения платежа: " + label,
                () -> {
                    TestEvidence.input("Сумма платежа", amount);
                    TestEvidence.input("Счётчик платежа", count);
                    TestEvidence.input("CSV компаний", companies);
                    TestEvidence.input("CSV связей", persons);
                    TestEvidence.input("CSV платежей", payments);
                    TestEvidence.input(
                            "Окружение", "unit: repository mocked; COPY rows captured in memory");
                    stubReportQueries();
                    var report =
                            TestEvidence.capture(
                                    () ->
                                            new CsvImportService(repository)
                                                    .importFiles(
                                                            stream(companies),
                                                            stream(persons),
                                                            stream(payments)));
                    var paymentReport = report.files().get(2);
                    var copiedRows = parseRows(copied, CsvImportSource.PAYMENTS);
                    TestEvidence.output("Отчёт об импорте", paymentReport);
                    TestEvidence.output(
                            "Платёжные строки, переданные в COPY",
                            copiedRows.stream().map(record -> record.toList()).toList());

                    assertThat(paymentReport.read())
                            .as("прочитанные строки для %s", label)
                            .isEqualTo(1);
                    assertThat(paymentReport.accepted())
                            .as("принятые строки для %s", label)
                            .isEqualTo(1);
                    assertThat(paymentReport.rejected())
                            .as("отклонённые строки для %s", label)
                            .isZero();
                    assertThat(copiedRows)
                            .as("строка платежа в COPY для %s", label)
                            .singleElement()
                            .satisfies(
                                    record -> {
                                        assertThat(record.get(0))
                                                .as("BENEFICIARY для %s", label)
                                                .isEqualTo("U_2");
                                        assertThat(record.get(1))
                                                .as("PAYER для %s", label)
                                                .isEqualTo("U_1");
                                        assertThat(new BigDecimal(record.get(2)))
                                                .as("PAY_AMT для %s", label)
                                                .isEqualByComparingTo(new BigDecimal(amount));
                                        assertThat(record.get(3))
                                                .as("PAY_CNT для %s", label)
                                                .isEqualTo(count);
                                    });
                });
    }

    /** Проверяет отклонение некорректных полей платежа из набора граничных примеров. */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({
        "missing beneficiary,nan;U_1;1;1,BENEFICIARY: required value is missing",
        "negative amount,U_2;U_1;-0.01;1,PAY_AMT: invalid DECIMAL value",
        "negative count,U_2;U_1;1;-1,PAY_CNT: invalid COUNT value",
        "fractional count,U_2;U_1;1;1.5,PAY_CNT: invalid COUNT value",
        "overflowing count,U_2;U_1;1;9223372036854775808,PAY_CNT: invalid COUNT value"
    })
    @DisplayName("TestUnitRejectsInvalidPaymentFields")
    void TestUnitRejectsInvalidPaymentFields(String label, String row, String reason)
            throws Exception {
        // Подготавливает CSV с некорректным платёжным полем.
        String payments = headers(CsvImportSource.PAYMENTS) + "\n" + row + "\n";
        String companies = csv(CsvImportSource.COMPANIES, row(CsvImportSource.COMPANIES, "U_1"));
        String persons =
                csv(CsvImportSource.PERSON_LINKS, row(CsvImportSource.PERSON_LINKS, "F_1"));

        Map<CsvImportSource, ByteArrayOutputStream> copied = captureCopiedRows();
        Allure.step(
                "Отклонить платёж: " + label,
                () -> {
                    TestEvidence.input("CSV компаний", companies);
                    TestEvidence.input("CSV связей", persons);
                    TestEvidence.input("CSV платежей", payments);
                    TestEvidence.input(
                            "Окружение", "unit: repository mocked; COPY rows captured in memory");
                    stubReportQueries();
                    // Импортирует CSV-файлы с указанной ошибкой.
                    var report =
                            TestEvidence.capture(
                                    () ->
                                            new CsvImportService(repository)
                                                    .importFiles(
                                                            stream(companies),
                                                            stream(persons),
                                                            stream(payments)));
                    TestEvidence.output("Отчёт об импорте", report);
                    var copiedRows = parseRows(copied, CsvImportSource.PAYMENTS);
                    TestEvidence.output(
                            "Строки платежей, переданные в COPY",
                            copiedRows.stream().map(record -> record.toList()).toList());

                    // Проверяет отказ строки и пустую передачу платёжных данных в COPY.
                    assertThat(report.files().get(2).read())
                            .as("прочитанные строки для сценария %s", label)
                            .isEqualTo(1);
                    assertThat(report.files().get(2).accepted())
                            .as("принятые строки для сценария %s", label)
                            .isZero();
                    assertThat(report.files().get(2).rejected())
                            .as("отклонённые строки для сценария %s", label)
                            .isEqualTo(1);
                    assertThat(report.files().get(2).errorSamples())
                            .as("ошибка для сценария %s", label)
                            .singleElement()
                            .satisfies(
                                    error ->
                                            assertThat(error.reason())
                                                    .as("причина отказа %s", label)
                                                    .isEqualTo(reason));
                    assertThat(copiedRows)
                            .as("платёжные строки COPY для отклонённого сценария %s", label)
                            .isEmpty();
                });
    }

    /** Проверяет чтение BOM и корректное сохранение разделителя внутри CSV-поля. */
    @Test
    @DisplayName("TestUnitReadsBomQuotedFields")
    void TestUnitReadsBomQuotedFields() throws Exception {
        // Подготавливает CSV с BOM и разделителем внутри текстового поля.
        String companyHeaders = headers(CsvImportSource.COMPANIES);
        String companyRow = row(CsvImportSource.COMPANIES, "U_1", Map.of("ORG_TYPE", "value;part"));
        String companies = "\uFEFF" + companyHeaders + "\n" + companyRow + "\n";
        String persons =
                csv(CsvImportSource.PERSON_LINKS, row(CsvImportSource.PERSON_LINKS, "F_1"));
        String payments = csv(CsvImportSource.PAYMENTS, "U_2;U_1;1;1");

        Map<CsvImportSource, ByteArrayOutputStream> copied = captureCopiedRows();
        Allure.step(
                "Разобрать CSV с BOM и разделителем внутри поля",
                () -> {
                    TestEvidence.input("CSV компаний", companies);
                    TestEvidence.input("CSV связей", persons);
                    TestEvidence.input("CSV платежей", payments);
                    TestEvidence.input(
                            "Окружение", "unit: repository mocked; COPY rows captured in memory");
                    stubReportQueries();
                    // Импортирует CSV-файлы.
                    var report =
                            TestEvidence.capture(
                                    () ->
                                            new CsvImportService(repository)
                                                    .importFiles(
                                                            stream(companies),
                                                            stream(persons),
                                                            stream(payments)));
                    TestEvidence.output("Отчёт об импорте", report);
                    var copiedRows = parseRows(copied, CsvImportSource.COMPANIES);
                    TestEvidence.output(
                            "Строка компании, переданная в COPY",
                            copiedRows.stream().map(record -> record.toList()).toList());

                    // Проверяет принятую строку и сохранение значения поля.
                    int organizationTypeIndex =
                            CsvImportSource.COMPANIES.getColumns().stream()
                                    .map(CsvImportSource.Column::header)
                                    .toList()
                                    .indexOf("ORG_TYPE");
                    assertThat(report.files().getFirst().accepted())
                            .as("принятая строка компании с quoted-полем")
                            .isEqualTo(1);
                    assertThat(copiedRows)
                            .as("строка компании в COPY")
                            .singleElement()
                            .satisfies(
                                    record ->
                                            assertThat(record.get(organizationTypeIndex))
                                                    .as("ORG_TYPE с сохранённым разделителем")
                                                    .isEqualTo("value;part"));
                });
    }

    /** Подменяет COPY репозитория записью переданных строк в память для проверки. */
    private Map<CsvImportSource, ByteArrayOutputStream> captureCopiedRows() {
        Map<CsvImportSource, ByteArrayOutputStream> copied = new EnumMap<>(CsvImportSource.class);
        doAnswer(
                        invocation -> {
                            CsvImportSource source = invocation.getArgument(0);
                            CsvImportRepository.CsvRowWriter writer = invocation.getArgument(1);
                            ByteArrayOutputStream output =
                                    copied.computeIfAbsent(
                                            source, ignored -> new ByteArrayOutputStream());
                            try (CSVPrinter printer =
                                    new CSVPrinter(
                                            new OutputStreamWriter(output, StandardCharsets.UTF_8),
                                            CSVFormat.DEFAULT)) {
                                writer.writeTo(printer);
                            }
                            return null;
                        })
                .when(repository)
                .copyRows(any(), any());
        return copied;
    }

    /** Настраивает пустые ответы репозитория для формирования итогового отчёта импорта. */
    private void stubReportQueries() {
        for (CsvImportSource source : CsvImportSource.values()) {
            when(repository.countRepeatedKeys(source)).thenReturn(0L);
        }
        when(repository.findConflictingCompanyIdSamples()).thenReturn(List.of());
    }

    /** Читает из памяти CSV-записи, переданные для указанного источника импорта. */
    private static List<CSVRecord> parseRows(
            Map<CsvImportSource, ByteArrayOutputStream> copied, CsvImportSource source)
            throws Exception {
        return CSVFormat.DEFAULT
                .parse(new StringReader(copied.get(source).toString(StandardCharsets.UTF_8)))
                .getRecords();
    }

    /** Собирает заголовки CSV из описания колонок источника. */
    private static String headers(CsvImportSource source) {
        return String.join(
                ";", source.getColumns().stream().map(CsvImportSource.Column::header).toList());
    }

    /** Формирует CSV-текст из заголовков и одной строки. */
    private static String csv(CsvImportSource source, String row) {
        return headers(source) + "\n" + row + "\n";
    }

    /** Формирует строку CSV с заданным первым значением и значениями по умолчанию. */
    private static String row(CsvImportSource source, String first) {
        return row(source, first, Map.of());
    }

    /** Формирует строку CSV с переопределёнными текстовыми колонками. */
    private static String row(CsvImportSource source, String first, Map<String, String> overrides) {
        return source.getColumns().stream()
                .map(
                        column ->
                                escapeCsvField(
                                        column == source.getColumns().getFirst()
                                                ? first
                                                : switch (column.type()) {
                                                    case COMPANY_ID -> "U_9";
                                                    case PERSON_ID -> "F_9";
                                                    case BOOLEAN -> "1";
                                                    case DECIMAL, COUNT -> "1";
                                                    case TEXT ->
                                                            overrides.getOrDefault(
                                                                    column.header(), "value");
                                                }))
                .collect(Collectors.joining(";"));
    }

    /** Экранирует значение CSV, если оно содержит разделитель, кавычку или перевод строки. */
    private static String escapeCsvField(String value) {
        if (value.contains(";")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Кодирует CSV-текст в UTF-8 поток для вызова сервиса импорта. */
    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
