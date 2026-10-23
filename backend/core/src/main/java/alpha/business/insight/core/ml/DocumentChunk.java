package alpha.business.insight.core.ml;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Чанк результата ML: текст, страница первоисточника и вектор для индексации.
 *
 * @param ordinal Уникальный номер чанка внутри документа, начиная с нуля.
 * @param page Номер страницы исходного PDF, начиная с единицы.
 * @param text Текст чанка; в режиме заглушки явно помечен как синтетический.
 * @param embedding Ненулевой вектор из конечных чисел; длина равна embeddingDimension ответа ML.
 */
@Schema(description = "Чанк результата ML: текст, страница первоисточника и вектор для индексации.")
public record DocumentChunk(
        @Schema(description = "Уникальный номер чанка внутри документа, начиная с нуля.", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int ordinal,
        @Schema(description = "Номер страницы исходного PDF, начиная с единицы.", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(description = "Текст чанка; в режиме заглушки явно помечен как синтетический.", minLength = 1,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String text,
        @Schema(description = "Ненулевой вектор из конечных чисел; длина равна embeddingDimension ответа ML.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<Float> embedding
) {
}
