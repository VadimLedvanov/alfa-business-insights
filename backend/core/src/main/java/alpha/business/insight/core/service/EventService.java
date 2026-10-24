package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.EventCreateDto;
import alpha.business.insight.core.dto.EventReadDto;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
     * Возвращает события от новых к старым; идентификатор определяет порядок при равных датах.
     */
    public List<EventReadDto> getAll() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id")).stream()
                .map(EventReadDto::from)
                .toList();
    }

    /**
     * Возвращает событие либо HTTP 404, если идентификатор неизвестен.
     */
    public EventReadDto get(UUID id) {
        return repository.findById(id).map(EventReadDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
    }
}
