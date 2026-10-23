package alpha.business.insight.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Контекст для анализа влияния событий: профиль компании, группа, контрагенты и недостающие данные. Общий оборот охватывает всех контрагентов, даже если в ответ попали только крупнейшие.")
public record CompanyContext(
        @Schema(description = "Профиль компании и его качество.")
        CompanyProfile profile,
        @Schema(description = "Все участники группы с их отраслями; пустой список, если группа не указана.")
        List<GroupCompany> group,
        @Schema(description = "Крупнейшие контрагенты по сумме платежей в обе стороны.")
        List<Counterparty> counterparties,
        @Schema(description = "Полный входящий и исходящий оборот без собственных платежей компании.")
        BigDecimal totalPaymentAmount,
        @Schema(description = "Доли оборота, приходящиеся на одного и трёх крупнейших контрагентов.")
        Concentration concentration,
        @Schema(description = "Ключи отсутствующих признаков и причин, по которым данные нельзя рассчитать.")
        List<String> missingData
) {
}
