package alpha.business.insight.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import alpha.business.insight.core.model.Company;
import alpha.business.insight.core.model.CompanyProfileStatus;

import java.math.BigDecimal;

@Schema(description = "Признаки компании из загруженного профиля. Неизвестные значения сохраняются как null, чтобы не подменять отсутствие данных отрицательным признаком или нулевой выручкой.")
public record CompanyProfile(
        @Schema(description = "Обезличенный идентификатор компании.")
        String companyId,
        @Schema(description = "Качество профиля: доступен, отсутствует или содержит конфликтующие данные.")
        CompanyProfileStatus profileStatus,
        @Schema(description = "Тип организации.")
        String organizationType,
        @Schema(description = "Форма собственности.")
        String ownershipType,
        @Schema(description = "Диапазон возраста компании в годах.")
        String ageRange,
        @Schema(description = "Этап развития бизнеса.")
        String businessLifecycleStage,
        @Schema(description = "Код основного вида деятельности по ОКВЭД.")
        String mainIndustryCode,
        @Schema(description = "Название основного вида деятельности.")
        String mainIndustryName,
        @Schema(description = "Наличие негативной отметки в профиле.")
        Boolean negativeMark,
        @Schema(description = "Диапазон численности работников по данным ФНС.")
        String employeeCountRangeFns,
        @Schema(description = "Диапазон численности работников по данным СПАРК.")
        String employeeCountRangeSpark,
        @Schema(description = "Тип клиента банка.")
        String clientType,
        @Schema(description = "Наличие расчётно-кассового обслуживания.")
        Boolean hasSettlementAccount,
        @Schema(description = "Наличие банковской гарантии.")
        Boolean hasBankGuarantee,
        @Schema(description = "Наличие кредитного продукта.")
        Boolean hasLoan,
        @Schema(description = "Наличие лизинга.")
        Boolean hasLeasing,
        @Schema(description = "Наличие депозитного продукта.")
        Boolean hasDeposit,
        @Schema(description = "Наличие зарплатного проекта.")
        Boolean hasPayrollProject,
        @Schema(description = "Наличие внешнеторговой деятельности (ВЭД).")
        Boolean hasForeignTrade,
        @Schema(description = "Страны внешнеторговой деятельности.")
        String foreignTradeCountries,
        @Schema(description = "Наличие факторинга — финансирования под уступку денежных требований.")
        Boolean hasFactoring,
        @Schema(description = "Выручка компании из исходного профиля.")
        BigDecimal revenue,
        @Schema(description = "Обезличенный идентификатор группы компаний; null, если группа не указана.")
        String groupId,
        @Schema(description = "Город компании.")
        String city,
        @Schema(description = "Регион компании.")
        String region
) {
    /**
     * Копирует признаки сущности в DTO без связей JPA.
     */
    public static CompanyProfile from(Company company) {
        return new CompanyProfile(
                company.getId(),
                company.getProfileStatus(),
                company.getOrganizationType(),
                company.getOwnershipType(),
                company.getAgeRange(),
                company.getBusinessLifecycleStage(),
                company.getMainIndustryCode(),
                company.getMainIndustryName(),
                company.getNegativeMark(),
                company.getEmployeeCountRangeFns(),
                company.getEmployeeCountRangeSpark(),
                company.getClientType(),
                company.getHasSettlementAccount(),
                company.getHasBankGuarantee(),
                company.getHasLoan(),
                company.getHasLeasing(),
                company.getHasDeposit(),
                company.getHasPayrollProject(),
                company.getHasForeignTrade(),
                company.getForeignTradeCountries(),
                company.getHasFactoring(),
                company.getRevenue(),
                company.getGroupId(),
                company.getCity(),
                company.getRegion());
    }
}

