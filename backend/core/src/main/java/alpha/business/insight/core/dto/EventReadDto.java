package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Представление события для создания и чтения через API.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventReadDto {
    private UUID id;
    private String title;
    private EventSourceType sourceType;
    private String sourceRef;
    private EventStatus status;
    private Instant createdAt;
    private Long materialsVersion;

    /**
     * Преобразует сохранённое событие в ответ API.
     */
    public static EventReadDto from(Event event) {
        return EventReadDto.builder()
                .id(event.getId())
                .title(event.getTitle())
                .sourceType(event.getSourceType())
                .sourceRef(event.getSourceRef())
                .status(event.getStatus())
                .createdAt(event.getCreatedAt())
                .materialsVersion(event.getMaterialsVersion())
                .build();
    }
}
