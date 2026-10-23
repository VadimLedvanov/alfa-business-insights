package alpha.business.insight.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import alpha.business.insight.core.model.CompanyProfileStatus;

@Schema(description = "Участник группы компаний со статусом профиля, кодом ОКВЭД и названием отрасли.")
public record GroupCompany(
        @Schema(description = "Обезличенный идентификатор участника группы.")
        String companyId,
        @Schema(description = "Качество профиля участника группы.")
        CompanyProfileStatus profileStatus,
        @Schema(description = "Код основного вида деятельности по ОКВЭД; null, если неизвестен.")
        String mainIndustryCode,
        @Schema(description = "Название основного вида деятельности.")
        String mainIndustryName
) {
}
