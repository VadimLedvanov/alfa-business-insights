package alpha.business.insight.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Результат агрегации платежей в репозитории: оборот контрагента по направлениям и полный оборот компании до отбора крупнейших контрагентов.")
public record CounterpartyPaymentTotals(
        @Schema(description = "Обезличенный идентификатор контрагента.")
        String companyId,
        @Schema(description = "Статус профиля из БД до преобразования в перечисление.")
        String profileStatus,
        @Schema(description = "Код основного вида деятельности по ОКВЭД.")
        String mainIndustryCode,
        @Schema(description = "Название основного вида деятельности.")
        String mainIndustryName,
        @Schema(description = "Платежи от контрагента в пользу компании.")
        BigDecimal incomingPaymentAmount,
        @Schema(description = "Платежи компании в пользу контрагента.")
        BigDecimal outgoingPaymentAmount,
        @Schema(description = "Сумма платежей с этим контрагентом в обе стороны.")
        BigDecimal totalPaymentAmount,
        @Schema(description = "Полный оборот компании по всем контрагентам до применения лимита.")
        BigDecimal allPaymentAmount
) {
}
