package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.CompanyProfileStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Контрагент с отраслью, суммами платежей и долей в полном обороте компании. Направление платежей указано относительно компании, для которой запрошен контекст.")
public record Counterparty(
        @Schema(description = "Обезличенный идентификатор контрагента.")
        String companyId,
        @Schema(description = "Качество профиля контрагента.")
        CompanyProfileStatus profileStatus,
        @Schema(description = "Код основного вида деятельности по ОКВЭД; null, если неизвестен.")
        String mainIndustryCode,
        @Schema(description = "Название основного вида деятельности.")
        String mainIndustryName,
        @Schema(description = "Платежи от контрагента в пользу компании.")
        BigDecimal incomingPaymentAmount,
        @Schema(description = "Платежи компании в пользу контрагента.")
        BigDecimal outgoingPaymentAmount,
        @Schema(description = "Сумма входящих и исходящих платежей с этим контрагентом.")
        BigDecimal totalPaymentAmount,
        @Schema(description = "Доля в полном входящем и исходящем обороте, от 0 до 1; null при нулевом обороте")
        BigDecimal paymentShare
) {
}
