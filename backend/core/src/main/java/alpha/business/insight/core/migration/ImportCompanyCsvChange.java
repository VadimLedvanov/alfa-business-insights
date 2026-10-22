package alpha.business.insight.core.migration;

import alpha.business.insight.core.dto.CsvImportReport;
import alpha.business.insight.core.model.CsvImportSource;
import alpha.business.insight.core.repository.CsvImportRepository;
import alpha.business.insight.core.service.CsvImportService;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.InputStream;

public class ImportCompanyCsvChange implements CustomTaskChange {
    private ResourceAccessor resources;
    private CsvImportReport report;

    @Override
    public void execute(Database database) throws CustomChangeException {
        try (InputStream companies = openCsv(CsvImportSource.COMPANIES);
             InputStream personLinks = openCsv(CsvImportSource.PERSON_LINKS);
             InputStream payments = openCsv(CsvImportSource.PAYMENTS)) {
            JdbcConnection connection = (JdbcConnection) database.getConnection();
            // Соединение и транзакция принадлежат Liquibase; JdbcTemplate не должен закрывать соединение.
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection.getUnderlyingConnection(), true);
            // Миграции выполняются до инициализации JPA, поэтому Spring-бины здесь не используются.
            CsvImportService service = new CsvImportService(new CsvImportRepository(new JdbcTemplate(dataSource)));
            report = service.importFiles(companies, personLinks, payments);
            if (report.files().stream().anyMatch(file -> file.rejected() > 0)) {
                throw new CustomChangeException("Начальные CSV содержат ошибочные строки: " + report.files());
            }
        } catch (CustomChangeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CustomChangeException("Не удалось загрузить начальные CSV из ресурсов", exception);
        }
    }

    private InputStream openCsv(CsvImportSource source) throws java.io.IOException {
        return resources.getExisting("data/csv/" + source.getFileName()).openInputStream();
    }

    @Override
    public String getConfirmationMessage() {
        return "Начальные CSV загружены: " + report;
    }

    @Override
    public void setUp() {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        this.resources = resourceAccessor;
    }

    @Override
    public ValidationErrors validate(Database database) {
        ValidationErrors errors = new ValidationErrors();
        if (!(database instanceof PostgresDatabase)) {
            errors.addError("Начальный импорт CSV поддерживает только PostgreSQL");
        }
        return errors;
    }
}
