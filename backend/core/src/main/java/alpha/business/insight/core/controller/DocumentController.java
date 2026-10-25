package alpha.business.insight.core.controller;

import alpha.business.insight.core.dto.DocumentMetadata;
import alpha.business.insight.core.service.DocumentService;
import alpha.business.insight.core.service.DocumentStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API загрузки аналитических PDF банка и чтения оригиналов из общей базы знаний.
 */
@RestController
@Tag(name = "База знаний", description = "Загрузка PDF аналитики банка и доступ к первоисточникам")
public class DocumentController {
    /**
     * Сервис загрузки и получения метаданных завершённых документов.
     */
    private final DocumentService service;

    /**
     * Файловое хранилище оригиналов, доступных для скачивания.
     */
    private final DocumentStorage storage;

    /**
     * Подключает обработчик загрузки и чтение оригинальных PDF.
     */
    public DocumentController(DocumentService service, DocumentStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    /**
     * Принимает PDF от системного администратора и возвращает 201 после успешной индексации.
     *
     * @param file исходный PDF
     * @param title необязательное название материала
     * @param publicationDate необязательная дата публикации
     * @return метаданные документа и Location для их чтения
     */
    @PostMapping(value = "/admin/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить PDF аналитики банка в базу знаний",
            description = "Только SYSTEM_ADMIN. Максимум 50 МБ. Возвращает результат после записи в PostgreSQL "
                    + "и Qdrant. По умолчанию включена ML-заглушка: текст синтетический, PDF не анализируется.")
    @ApiResponse(responseCode = "201", description = "Документ сохранён и проиндексирован")
    @ApiResponse(responseCode = "400", description = "Пустой файл или некорректные параметры")
    @ApiResponse(responseCode = "413", description = "Файл превышает 50 МБ")
    @ApiResponse(responseCode = "415", description = "Файл не имеет сигнатуры PDF")
    @ApiResponse(responseCode = "422", description = "ML не смог извлечь текст из PDF")
    @ApiResponse(responseCode = "502", description = "Некорректный ответ ML")
    @ApiResponse(responseCode = "503", description = "ML или Qdrant недоступен; запрос можно повторить")
    public ResponseEntity<DocumentMetadata> upload(
            @Parameter(description = "Оригинальный PDF размером до 50 МБ", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Название материала; по умолчанию имя файла")
            @RequestParam(required = false) String title,
            @Parameter(description = "Дата публикации в формате yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publicationDate) {
        DocumentMetadata metadata = service.upload(file, title, publicationDate);
        return ResponseEntity.created(URI.create("/api/documents/" + metadata.documentId())).body(metadata);
    }

    /**
     * Возвращает метаданные завершённой загрузки пользователю приложения.
     *
     * @param id идентификатор документа
     * @return метаданные без серверного пути к файлу
     */
    @GetMapping("/documents/{id}")
    @Operation(summary = "Метаданные PDF из базы знаний")
    @ApiResponse(responseCode = "404", description = "Документ не найден")
    public DocumentMetadata get(@Parameter(description = "Идентификатор документа базы знаний", required = true) @PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Отдаёт исходные байты PDF с исходным именем для скачивания.
     *
     * @param id идентификатор документа
     * @return оригинальный PDF с заголовками Content-Type и Content-Disposition
     */
    @GetMapping(value = "/documents/{id}/file", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Скачать оригинал PDF из базы знаний")
    @ApiResponse(responseCode = "404", description = "Документ не найден")
    public ResponseEntity<Resource> download(@Parameter(description = "Идентификатор документа базы знаний", required = true) @PathVariable UUID id) {
        DocumentMetadata metadata = service.get(id);
        Resource file = storage.read(id);
        if (!file.isReadable()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Оригинал PDF недоступен");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(metadata.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(metadata.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file);
    }
}
