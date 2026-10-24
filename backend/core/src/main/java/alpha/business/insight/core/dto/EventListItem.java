package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Краткая информация о событии в списке.")
public record EventListItem(
        UUID id,
        String title,
        EventSourceType sourceType,
        EventStatus status,
        Instant createdAt
) {
}
