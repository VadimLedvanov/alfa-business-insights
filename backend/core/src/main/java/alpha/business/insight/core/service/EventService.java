package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.EventCreateDto;
import alpha.business.insight.core.dto.EventListItem;
import alpha.business.insight.core.dto.EventReadDto;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import alpha.business.insight.core.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Сохраняет исходные материалы событий и предоставляет их для чтения.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {
    private final EventRepository repository;

    /**
     * Проверяет обязательные поля и сохраняет событие до обработки материалов.
     */
    @Transactional
    public EventReadDto create(EventCreateDto request) {
        if (request.getTitle() == null || request.getTitle().isBlank()
                || request.getSourceType() == null
                || request.getSourceRef() == null || request.getSourceRef().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Необходимо указать title, sourceType и непустой sourceRef");
        }
        Event event = Event.builder()
                .title(request.getTitle())
                .sourceType(request.getSourceType())
                .sourceRef(request.getSourceRef())
                .build();
        return EventReadDto.from(repository.save(event));
    }

    /**
     * Возвращает страницу кратких сведений с учётом непустых фильтров.
     */
    public Page<EventListItem> getEvents(String title, String status, String sourceType, Pageable pageable) {
        String normalizedTitle = normalizeFilter(title);
        if (normalizedTitle != null) {
            normalizedTitle = normalizedTitle.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
        return repository.findEvents(normalizedTitle, parseFilter(status, EventStatus.class),
                parseFilter(sourceType, EventSourceType.class), pageable);
    }

    private static String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <E extends Enum<E>> E parseFilter(String value, Class<E> type) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестное значение фильтра: " + value);
        }
    }

    /**
     * Возвращает событие либо HTTP 404, если идентификатор неизвестен.
     */
    public EventReadDto get(UUID id) {
        return repository.findById(id).map(EventReadDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
    }
}
