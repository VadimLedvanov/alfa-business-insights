package alpha.business.insight.core.model;

import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Описание источников CSV для импорта.
 */
@Getter
public enum CsvImportSource {
    /**
     * Профили компаний.
     */
    COMPANIES("df_Uid_data_hack.csv", "companies", "company_id",
            new Column("SYNTHETIC_U", "company_id", ValueType.COMPANY_ID),
            new Column("ORG_TYPE", "organization_type", ValueType.TEXT),
            new Column("OKFS_NAME", "ownership_type", ValueType.TEXT),
            new Column("COMPANY_AGE_YEARS_GRADATION", "age_range", ValueType.TEXT),
            new Column("ECO_MARK_LABEL", "business_lifecycle_stage", ValueType.TEXT),
            new Column("MAINOKVED_CODE", "main_industry_code", ValueType.TEXT),
            new Column("MAINOKVED_NAME", "main_industry_name", ValueType.TEXT),
            new Column("NEGATIVEMARK", "negative_mark", ValueType.BOOLEAN),
            new Column("AMOUNT_WORKERS_FNS_GRADATION", "employee_count_range_fns", ValueType.TEXT),
            new Column("AMOUNT_WORKERS_SPARK_GRADATION", "employee_count_range_spark", ValueType.TEXT),
            new Column("CL_TYPE", "client_type", ValueType.TEXT),
            new Column("CL_PRODUCT_RKO_MARK", "has_settlement_account", ValueType.BOOLEAN),
            new Column("CL_PRODUCT_GAR_MARK", "has_bank_guarantee", ValueType.BOOLEAN),
            new Column("CL_PRODUCT_LOA_MARK", "has_loan", ValueType.BOOLEAN),
            new Column("CL_PRODUCT_LEA_MARK", "has_leasing", ValueType.BOOLEAN),
            new Column("CL_PRODUCT_DEP_MARK", "has_deposit", ValueType.BOOLEAN),
            new Column("CL_PRODUCT_ZPP_MARK", "has_payroll_project", ValueType.BOOLEAN),
            new Column("FTS_FLAG", "has_foreign_trade", ValueType.BOOLEAN),
            new Column("FTS_COUNTRY_NAME", "foreign_trade_countries", ValueType.TEXT),
            new Column("FACTORING_MARK", "has_factoring", ValueType.BOOLEAN),
            new Column("REVENUE_COMPANY", "revenue", ValueType.DECIMAL),
            new Column("GROUP_ID", "group_id", ValueType.TEXT),
            new Column("CITY", "city", ValueType.TEXT),
            new Column("REGION", "region", ValueType.TEXT)),
    /**
     * Связи физических лиц с компаниями.
     */
    PERSON_LINKS("df_Fid_Uid_link_hack.csv", "person_company_links", "person_id, company_id",
            new Column("SYNTHETIC_F", "person_id", ValueType.PERSON_ID),
            new Column("CITY_NAME", "city_name", ValueType.TEXT),
            new Column("RC_NAME", "regional_center_name", ValueType.TEXT),
            new Column("SYNTHETIC_U", "company_id", ValueType.COMPANY_ID)),
    /**
     * Платёжные агрегаты между компаниями.
     */
    PAYMENTS("df_Uid_payment_hack.csv", "company_payment_summaries", "payer_company_id, recipient_company_id",
            new Column("BENEFICIARY", "recipient_company_id", ValueType.COMPANY_ID),
            new Column("PAYER", "payer_company_id", ValueType.COMPANY_ID),
            new Column("PAY_AMT", "total_payment_amount", ValueType.DECIMAL),
            new Column("PAY_CNT", "payment_count", ValueType.COUNT));

    /**
     * Имя файла CSV в ресурсах приложения.
     */
    private final String fileName;
    /**
     * Имя целевой таблицы.
     */
    private final String table;
    /**
     * Колонки ключа для определения повторов.
     */
    private final String keys;
    /**
     * Описание колонок CSV в порядке загрузки.
     */
    private final List<Column> columns;

    CsvImportSource(String fileName, String table, String keys, Column... columns) {
        this.fileName = fileName;
        this.table = table;
        this.keys = keys;
        this.columns = List.of(columns);
    }

    /**
     * Возвращает имя временной таблицы.
     */
    public String stagingTable() {
        return "csv_" + table;
    }

    /**
     * Возвращает имена колонок таблицы в порядке загрузки.
     */
    public String columnNames() {
        return columns.stream().map(Column::name).collect(Collectors.joining(", "));
    }

    /**
     * Тип значения колонки CSV.
     */
    public enum ValueType {
        /**
         * Текстовое значение.
         */
        TEXT,
        /**
         * Идентификатор компании.
         */
        COMPANY_ID,
        /**
         * Идентификатор физического лица.
         */
        PERSON_ID,
        /**
         * Логический признак.
         */
        BOOLEAN,
        /**
         * Неотрицательное десятичное число.
         */
        DECIMAL,
        /**
         * Неотрицательное целое количество.
         */
        COUNT
    }

    /**
     * Описание колонки CSV.
     */
    public record Column(
            /**
             * Имя колонки CSV.
             */
            String header,
            /**
             * Имя колонки таблицы.
             */
            String name,
            /**
             * Тип значения колонки.
             */
            ValueType type) { }
}
