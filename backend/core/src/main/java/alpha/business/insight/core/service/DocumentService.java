package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.DocumentMetadata;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.model.KnowledgeDocument;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Сохраняет оригинал PDF, чанки в Qdrant и метаданные документа в PostgreSQL.
 */
@Service
public class DocumentService {
    /**
     * Максимальный размер оригинала PDF: 50 МБ.
     */
    private static final int MAX_BYTES = 50 * 1024 * 1024;

    /**
     * JPA-хранилище метаданных успешно загруженных документов.
     */
    private final DocumentRepository repository;

    /**
     * Файловое хранилище оригиналов PDF.
     */
    private final DocumentStorage storage;

    /**
     * Синхронный Feign-клиент ML либо локальная заглушка.
     */
    private final DocumentAnalyzer analyzer;

    /**
     * Хранилище текстов чанков, векторов и ссылок на страницы PDF.
     */
    private final DocumentVectorStore vectors;

    /**
     * Журнал ошибок удаления внешних данных после неудавшейся загрузки.
     */
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * Подключает хранилища и синхронный анализатор PDF.
     */
    public DocumentService(DocumentRepository repository, DocumentStorage storage, DocumentAnalyzer analyzer,
                           DocumentVectorStore vectors) {
        this.repository = repository;
        this.storage = storage;
        this.analyzer = analyzer;
        this.vectors = vectors;
    }

    /**
     * Сохраняет PDF и чанки, затем завершает транзакцию записи метаданных через JPA-репозиторий.
     * При обычной ошибке пытается удалить файл и уже записанные точки Qdrant.
     *
     * @param file исходный PDF размером до 50 МБ
     * @param title название материала; при отсутствии используется имя файла
     * @param publicationDate дата публикации либо null
     * @return метаданные успешно сохранённого и проиндексированного документа
     * @throws ResponseStatusException при неподходящем файле, ошибке ML, файлового хранилища или Qdrant
     */
    public DocumentMetadata upload(MultipartFile file, String title, LocalDate publicationDate) {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        byte[] pdf = readPdf(file);
        String originalName = file.getOriginalFilename();
        String name = originalName == null ? "document.pdf" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank()) {
            name = "document.pdf";
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое имя файла");
        }
        String documentTitle = title == null || title.isBlank() ? name : title.strip();
        UUID documentId = UUID.randomUUID();
        boolean indexingStarted = false;
        DocumentAnalysis analysis = null;
        try {
            storage.write(documentId, pdf);
            analysis = analyze(documentId, pdf);
            indexingStarted = true;
            vectors.index(analysis, publicationDate, deadline);
            KnowledgeDocument document = new KnowledgeDocument(documentId, documentTitle, name, storage.relativePath(documentId),
                    pdf.length, sha256(pdf), Instant.now(), publicationDate, analysis.chunks().size(),
                    analysis.schemaVersion(), analysis.embeddingModel(), analysis.embeddingDimension(), analysis.stub());
            return DocumentMetadata.from(repository.saveAndFlush(document));
        } catch (IOException exception) {
            cleanup(documentId, indexingStarted, analysis);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить PDF", exception);
        } catch (RuntimeException exception) {
            cleanup(documentId, indexingStarted, analysis);
            throw exception;
        }
    }

    /**
     * Возвращает метаданные завершённой загрузки.
     *
     * @param documentId идентификатор документа базы знаний
     * @return метаданные с количеством чанков в Qdrant
     * @throws ResponseStatusException со статусом 404, если документа нет
     */
    public DocumentMetadata get(UUID documentId) {
        return repository.findById(documentId).map(DocumentMetadata::from).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден"));
    }

    /** Возвращает страницу загруженных PDF без раскрытия внутренних путей хранения. */
    public Page<DocumentMetadata> list(Pageable pageable) {
        return repository.findAll(pageable).map(DocumentMetadata::from);
    }

    /** Удаляет векторы, оригинальный PDF и метаданные документа. */
    public void delete(UUID documentId) {
        KnowledgeDocument document = repository.findById(documentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден"));
        vectors.delete(documentId, document.getEmbeddingModel(), document.getEmbeddingDimension(), document.isStub());
        try {
            storage.delete(document.getFilePath());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось удалить оригинал PDF", exception);
        }
        repository.delete(document);
    }

    /**
     * Отказ ML обработать PDF даёт HTTP 422, недоступность — HTTP 503, некорректный ответ — HTTP 502.
     */
    private DocumentAnalysis analyze(UUID id, byte[] pdf) {
        DocumentAnalysis result;
        try {
            result = analyzer.analyze(id, pdf);
        } catch (RuntimeException exception) {
            if (exception instanceof FeignException failure) {
                log.warn("Ошибка обработки PDF {} в ML: HTTP {}", id, failure.status());
                if (failure.status() == 422) {
                    throw new ResponseStatusException(HttpStatus.valueOf(422),
                            "Не удалось извлечь текст из PDF. Проверьте, что файл не повреждён и содержит "
                                    + "текстовый слой. Для сканов требуется распознавание текста (OCR).", exception);
                }
            } else {
                log.warn("Ошибка вызова ML для PDF {}", id, exception);
            }
            throw new ComponentUnavailableException("ML");
        }
        try {
            if (result == null) {
                throw new IllegalArgumentException("ML вернул пустой ответ");
            }
            result.validate(id);
            return result;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML не вернул корректный результат", exception);
        }
    }

    /**
     * Независимо пытается удалить файл и точки Qdrant; сохраняет исходную ошибку загрузки.
     * Автоматических повторов после сбоя процесса нет.
     */
    private void cleanup(UUID documentId, boolean indexingStarted, DocumentAnalysis analysis) {
        try {
            storage.delete(storage.relativePath(documentId));
        } catch (IOException exception) {
            log.error("Не удалось удалить PDF неудавшейся загрузки {}", documentId, exception);
        }
        if (indexingStarted) {
            try {
                if (analysis != null) vectors.delete(documentId, analysis.embeddingModel(), analysis.embeddingDimension(), analysis.stub());
            } catch (RuntimeException exception) {
                log.error("Не удалось удалить чанки неудавшейся загрузки {}", documentId, exception);
            }
        }
    }

    /**
     * Проверяет размер и сигнатуру PDF до создания файла.
     */
    private static byte[] readPdf(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер PDF превышает 50 МБ");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] pdf = input.readNBytes(MAX_BYTES + 1);
            if (pdf.length > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер PDF превышает 50 МБ");
            }
            if (pdf.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF не должен быть пустым");
            }
            if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-') {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ожидается PDF-файл");
            }
            return pdf;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось прочитать файл", exception);
        }
    }

    /**
     * Вычисляет SHA-256 оригинальных байтов PDF для метаданных документа.
     */
    private static String sha256(byte[] pdf) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
