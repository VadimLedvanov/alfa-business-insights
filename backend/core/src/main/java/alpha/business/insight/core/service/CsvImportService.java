package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.CsvImportReport;
import alpha.business.insight.core.dto.FileReport;
import alpha.business.insight.core.dto.RowError;
import alpha.business.insight.core.model.CsvImportSource;
import alpha.business.insight.core.model.CsvImportSource.Column;
import alpha.business.insight.core.model.CsvImportSource.ValueType;
import alpha.business.insight.core.repository.CsvImportRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class CsvImportService {
    private static final int ERROR_SAMPLE_LIMIT = 20;
    private static final CSVFormat INPUT_FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';').setHeader().setSkipHeaderRecord(true).get();
    private final CsvImportRepository repository;

    public CsvImportService(CsvImportRepository repository) {
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public CsvImportReport importFiles(InputStream companies, InputStream personLinks, InputStream payments) throws IOException {
        Map<CsvImportSource, InputStream> inputs = Map.of(CsvImportSource.COMPANIES, companies,
                CsvImportSource.PERSON_LINKS, personLinks, CsvImportSource.PAYMENTS, payments);
        repository.lockImport();
        List<FileReport> files = new ArrayList<>();
        for (CsvImportSource source : CsvImportSource.values()) {
            repository.createStagingTable(source);
            files.add(loadFile(inputs.get(source), source));
        }
        repository.saveReferencedCompanies();
        repository.saveCompanyProfiles();
        repository.savePersonCompanyLinks();
        repository.savePaymentSummaries();
        return new CsvImportReport(List.copyOf(files), repository.countConflictingProfiles(),
                repository.findConflictingCompanyIdSamples(), repository.countCompaniesWithoutProfile());
    }

    private FileReport loadFile(InputStream input, CsvImportSource source) throws IOException {
        long[] counts = new long[3];
        List<RowError> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            try (CSVParser parser = INPUT_FORMAT.parse(reader)) {
                List<String> expected = source.getColumns().stream().map(Column::header).toList();
                if (parser.getHeaderNames().size() != expected.size()
                        || !new HashSet<>(parser.getHeaderNames()).equals(new HashSet<>(expected))) {
                    throw new IOException(source.getFileName() + ": expected headers " + expected
                            + ", received " + parser.getHeaderNames());
                }
                repository.copyRows(source, printer -> {
                    for (CSVRecord record : parser) {
                        counts[0]++;
                        List<Object> values = new ArrayList<>();
                        try {
                            if (!record.isConsistent()) {
                                throw new IllegalArgumentException("Column count does not match the header");
                            }
                            for (Column column : source.getColumns()) {
                                values.add(parseColumn(column, record.get(column.header())));
                            }
                        } catch (IllegalArgumentException invalid) {
                            counts[2]++;
                            if (errors.size() < ERROR_SAMPLE_LIMIT) {
                                errors.add(new RowError(record.getRecordNumber(), invalid.getMessage()));
                            }
                            continue;
                        }
                        values.add(record.getRecordNumber());
                        printer.printRecord(values);
                        counts[1]++;
                    }
                });
            }
        }
        long duplicates = repository.countRepeatedKeys(source);
        return new FileReport(source.getFileName(), counts[0], counts[1], counts[2], duplicates, List.copyOf(errors));
    }

    private Object parseColumn(Column column, String raw) {
        ValueType type = column.type();
        String header = column.header();
        String value = raw.strip();
        boolean required = type == ValueType.COMPANY_ID || type == ValueType.PERSON_ID
                || header.equals("PAY_AMT") || type == ValueType.COUNT;
        if (value.isEmpty() || value.equalsIgnoreCase("nan") || value.equalsIgnoreCase("null")) {
            if (required) {
                throw new IllegalArgumentException(header + ": required value is missing");
            }
            return null;
        }
        try {
            return switch (type) {
                case TEXT -> value;
                case COMPANY_ID, PERSON_ID -> {
                    String prefix = type == ValueType.COMPANY_ID ? "U_" : "F_";
                    if (!value.matches(prefix + "[0-9]+")) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                case BOOLEAN -> {
                    BigDecimal number = new BigDecimal(value);
                    if (number.compareTo(BigDecimal.ZERO) != 0 && number.compareTo(BigDecimal.ONE) != 0) {
                        throw new IllegalArgumentException();
                    }
                    yield number.compareTo(BigDecimal.ONE) == 0;
                }
                case DECIMAL, COUNT -> {
                    BigDecimal number = new BigDecimal(value);
                    if (number.signum() < 0) {
                        throw new IllegalArgumentException();
                    }
                    yield type == ValueType.COUNT ? number.longValueExact() : number;
                }
            };
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException(header + ": invalid " + type + " value");
        }
    }
}
