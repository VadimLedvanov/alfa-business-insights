package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.CsvImportSource;
import alpha.business.insight.core.model.CsvImportSource.Column;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class CsvImportRepository {
    private final JdbcTemplate jdbc;

    public CsvImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockImport() {
        jdbc.execute("SELECT pg_advisory_xact_lock(47510625)");
    }

    public void createStagingTable(CsvImportSource source) {
        jdbc.execute("CREATE TEMP TABLE " + source.stagingTable() + " ON COMMIT DROP AS SELECT "
                + source.columnNames() + ", 0::bigint AS source_record FROM " + source.getTable() + " WITH NO DATA");
    }

    public void copyRows(CsvImportSource source, CsvRowWriter rows) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String copy = "COPY " + source.stagingTable() + " (" + source.columnNames()
                    + ", source_record) FROM STDIN WITH (FORMAT CSV, NULL '')";
            try (PGCopyOutputStream output = new PGCopyOutputStream(connection.unwrap(PGConnection.class), copy);
                 CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8),
                         CSVFormat.DEFAULT.builder().setNullString("").get())) {
                rows.writeTo(printer);
            } catch (IOException exception) {
                throw new UncheckedIOException("Не удалось загрузить " + source.getFileName(), exception);
            }
            return null;
        });
    }

    public void saveReferencedCompanies() {
        jdbc.execute("""
                INSERT INTO companies (company_id)
                SELECT company_id FROM csv_companies
                UNION SELECT company_id FROM csv_person_company_links
                UNION SELECT payer_company_id FROM csv_company_payment_summaries
                UNION SELECT recipient_company_id FROM csv_company_payment_summaries
                ON CONFLICT (company_id) DO NOTHING
                """);
    }

    public void saveCompanyProfiles() {
        String columns = CsvImportSource.COMPANIES.columnNames();
        jdbc.execute("CREATE TEMP TABLE csv_distinct_company_profiles ON COMMIT DROP AS SELECT DISTINCT "
                + columns + " FROM csv_companies");
        jdbc.execute("""
                CREATE TEMP TABLE csv_company_profile_counts ON COMMIT DROP AS
                SELECT company_id, COUNT(*) AS variants FROM csv_distinct_company_profiles GROUP BY company_id
                """);
        List<Column> attributes = CsvImportSource.COMPANIES.getColumns()
                .subList(1, CsvImportSource.COMPANIES.getColumns().size());
        String updates = attributes.stream().map(c -> c.name() + " = EXCLUDED." + c.name())
                .collect(Collectors.joining(", "));
        jdbc.execute("INSERT INTO companies (" + columns + ", profile_status) SELECT " + columns
                + ", 'AVAILABLE' FROM csv_distinct_company_profiles WHERE company_id IN "
                + "(SELECT company_id FROM csv_company_profile_counts WHERE variants = 1) "
                + "ON CONFLICT (company_id) DO UPDATE SET " + updates + ", profile_status = 'AVAILABLE'");
        String clearAttributes = attributes.stream().map(c -> c.name() + " = NULL").collect(Collectors.joining(", "));
        jdbc.execute("UPDATE companies SET " + clearAttributes + ", profile_status = 'CONFLICTING' "
                + "WHERE company_id IN (SELECT company_id FROM csv_company_profile_counts WHERE variants > 1)");
    }

    public void savePersonCompanyLinks() {
        jdbc.execute("""
                INSERT INTO person_company_links (person_id, company_id, city_name, regional_center_name)
                SELECT DISTINCT ON (person_id, company_id) person_id, company_id, city_name, regional_center_name
                FROM csv_person_company_links ORDER BY person_id, company_id, source_record DESC
                ON CONFLICT (person_id, company_id) DO UPDATE
                SET city_name = EXCLUDED.city_name, regional_center_name = EXCLUDED.regional_center_name
                """);
    }

    public void savePaymentSummaries() {
        jdbc.execute("""
                INSERT INTO company_payment_summaries
                    (payer_company_id, recipient_company_id, total_payment_amount, payment_count)
                SELECT payer_company_id, recipient_company_id, SUM(total_payment_amount), SUM(payment_count)
                FROM csv_company_payment_summaries GROUP BY payer_company_id, recipient_company_id
                ON CONFLICT (payer_company_id, recipient_company_id) DO UPDATE
                SET total_payment_amount = EXCLUDED.total_payment_amount, payment_count = EXCLUDED.payment_count
                """);
    }

    public long countRepeatedKeys(CsvImportSource source) {
        return jdbc.queryForObject("SELECT COUNT(*) - COUNT(DISTINCT (" + source.getKeys() + ")) FROM "
                + source.stagingTable(), Long.class);
    }

    public long countConflictingProfiles() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM csv_company_profile_counts WHERE variants > 1", Long.class);
    }

    public List<String> findConflictingCompanyIdSamples() {
        return jdbc.queryForList("""
                SELECT company_id FROM csv_company_profile_counts WHERE variants > 1
                ORDER BY company_id LIMIT 20
                """, String.class);
    }

    public long countCompaniesWithoutProfile() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE profile_status = 'MISSING'", Long.class);
    }

    @FunctionalInterface
    public interface CsvRowWriter {
        void writeTo(CSVPrinter printer) throws IOException;
    }
}
