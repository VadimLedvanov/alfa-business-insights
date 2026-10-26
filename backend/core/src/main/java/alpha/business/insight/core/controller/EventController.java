package alpha.business.insight.core.controller;

import alpha.business.insight.core.dto.EventCreateDto;
import alpha.business.insight.core.dto.EventListItem;
import alpha.business.insight.core.dto.EventReadDto;
import alpha.business.insight.core.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * API создания событий администратором и чтения событий пользователями приложения.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "События", description = "Создание и получение событий для анализа")
public class EventController {
    private final EventService service;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Создать событие",
            description = "Только SYSTEM_ADMIN. Сохраняет текст, URL или ссылку на сохранённый PDF. "
                    + "Начальный статус PROCESSING, версия материалов 1. Обработка материалов не запускается.")
    @ApiResponse(responseCode = "201", description = "Событие сохранено; Location содержит адрес его получения")
    @ApiResponse(responseCode = "400", description = "Некорректное тело запроса или отсутствуют обязательные поля")
    @ApiResponse(responseCode = "401", description = "Требуется авторизация")
    @ApiResponse(responseCode = "403", description = "Создание разрешено только системному администратору")
    public ResponseEntity<EventReadDto> create(@RequestBody EventCreateDto request) {
        EventReadDto event = service.create(request);
        return ResponseEntity.created(URI.create("/api/events/" + event.getId())).body(event);
    }

    @GetMapping
    @Operation(summary = "Список событий с фильтрацией и пагинацией",
            description = "SYSTEM_ADMIN и COMPANY_DIRECTOR. Фильтры объединяются через И; пустые игнорируются. "
                    + "По умолчанию: 20 событий на странице, сортировка по createdAt и id по убыванию.")
    @ApiResponse(responseCode = "200", description = "Страница кратких сведений о событиях")
    @ApiResponse(responseCode = "400", description = "Неизвестный статус или тип источника")
    public Page<EventListItem> getEvents(
            @Parameter(description = "Часть заголовка без учёта регистра")
            @RequestParam(required = false) String title,
            @Parameter(description = "Статус обработки", schema = @Schema(allowableValues = {"PROCESSING", "READY", "FAILED"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "Тип источника", schema = @Schema(allowableValues = {"TEXT", "URL", "PDF"}))
            @RequestParam(required = false) String sourceType,
            @ParameterObject @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable) {
        return service.getEvents(title, status, sourceType, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить событие", description = "SYSTEM_ADMIN и COMPANY_DIRECTOR.")
    @ApiResponse(responseCode = "200", description = "Событие")
    @ApiResponse(responseCode = "400", description = "Некорректный UUID события")
    @ApiResponse(responseCode = "404", description = "Событие не найдено")
    public EventReadDto get(@PathVariable UUID id) {
        return service.get(id);
    }
}
