package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.EventSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Исходный материал события. Служебные поля задаются сервером.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCreateDto {
    @Schema(description = "Непустой заголовок события", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "Тип исходного материала", requiredMode = Schema.RequiredMode.REQUIRED)
    private EventSourceType sourceType;

    @Schema(description = "Непустой текст, URL или ссылка на сохранённый PDF",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceRef;
}
