package alpha.business.insight.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Концентрация платежей - зависимость оборота компании от крупнейших контрагентов. Содержит доли одного и трёх крупнейших контрагентов от полного оборота, от 0 до 1. При нулевом обороте доли неизвестны и возвращаются как null.")
public record Concentration(
        @Schema(description = "Доля крупнейшего контрагента; null при нулевом обороте") BigDecimal top1Share,
        @Schema(description = "Доля трёх крупнейших контрагентов; null при нулевом обороте") BigDecimal top3Share
) {
}
