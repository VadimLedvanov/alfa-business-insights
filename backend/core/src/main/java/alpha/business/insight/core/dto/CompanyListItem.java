package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.CompanyProfileStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Краткая информация о компании в списке.")
public record CompanyListItem(
        String companyId,
        CompanyProfileStatus profileStatus,
        String mainIndustryCode,
        String mainIndustryName,
        String city,
        String region
) {
}
