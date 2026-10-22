package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Профиль компании.
 */
@Entity
@Table(name = "companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {
    /**
     * Обезличенный идентификатор компании.
     */
    @Id
    @Column(name = "company_id", nullable = false, columnDefinition = "text")
    private String id;

    /**
     * Статус профиля компании.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_status", nullable = false, columnDefinition = "text")
    private CompanyProfileStatus profileStatus;

    /**
     * Тип организации.
     */
    @Column(name = "organization_type", columnDefinition = "text")
    private String organizationType;

    /**
     * Форма собственности.
     */
    @Column(name = "ownership_type", columnDefinition = "text")
    private String ownershipType;

    /**
     * Диапазон возраста компании в годах.
     */
    @Column(name = "age_range", columnDefinition = "text")
    private String ageRange;

    /**
     * Этап развития компании.
     */
    @Column(name = "business_lifecycle_stage", columnDefinition = "text")
    private String businessLifecycleStage;

    /**
     * Код основного ОКВЭД.
     */
    @Column(name = "main_industry_code", columnDefinition = "text")
    private String mainIndustryCode;

    /**
     * Наименование основного вида деятельности.
     */
    @Column(name = "main_industry_name", columnDefinition = "text")
    private String mainIndustryName;

    /**
     * Признак негативной отметки.
     */
    @Column(name = "negative_mark", columnDefinition = "boolean")
    private Boolean negativeMark;

    /**
     * Диапазон численности работников по данным ФНС.
     */
    @Column(name = "employee_count_range_fns", columnDefinition = "text")
    private String employeeCountRangeFns;

    /**
     * Диапазон численности работников по данным СПАРК.
     */
    @Column(name = "employee_count_range_spark", columnDefinition = "text")
    private String employeeCountRangeSpark;

    /**
     * Тип клиента.
     */
    @Column(name = "client_type", columnDefinition = "text")
    private String clientType;

    /**
     * Признак расчётно-кассового обслуживания.
     */
    @Column(name = "has_settlement_account", columnDefinition = "boolean")
    private Boolean hasSettlementAccount;

    /**
     * Признак банковской гарантии.
     */
    @Column(name = "has_bank_guarantee", columnDefinition = "boolean")
    private Boolean hasBankGuarantee;

    /**
     * Признак кредитного продукта.
     */
    @Column(name = "has_loan", columnDefinition = "boolean")
    private Boolean hasLoan;

    /**
     * Признак лизинга.
     */
    @Column(name = "has_leasing", columnDefinition = "boolean")
    private Boolean hasLeasing;

    /**
     * Признак депозитного продукта.
     */
    @Column(name = "has_deposit", columnDefinition = "boolean")
    private Boolean hasDeposit;

    /**
     * Признак зарплатного проекта.
     */
    @Column(name = "has_payroll_project", columnDefinition = "boolean")
    private Boolean hasPayrollProject;

    /**
     * Признак внешнеторговой деятельности.
     */
    @Column(name = "has_foreign_trade", columnDefinition = "boolean")
    private Boolean hasForeignTrade;

    /**
     * Страны внешнеторговой деятельности.
     */
    @Column(name = "foreign_trade_countries", columnDefinition = "text")
    private String foreignTradeCountries;

    /**
     * Признак факторинга.
     */
    @Column(name = "has_factoring", columnDefinition = "boolean")
    private Boolean hasFactoring;

    /**
     * Выручка компании.
     */
    @Column(name = "revenue", columnDefinition = "numeric")
    private BigDecimal revenue;

    /**
     * Обезличенный идентификатор группы компаний.
     */
    @Column(name = "group_id", columnDefinition = "text")
    private String groupId;

    /**
     * Город компании.
     */
    @Column(name = "city", columnDefinition = "text")
    private String city;

    /**
     * Регион компании.
     */
    @Column(name = "region", columnDefinition = "text")
    private String region;
}
