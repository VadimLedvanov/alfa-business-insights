package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Метаданные PDF базы знаний. Запись становится доступной после успешной индексации в Qdrant.
 */
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeDocument {

    /**
     * Идентификатор документа, общий с payload его векторов в Qdrant.
     */
    @Id
    private UUID id;

    /**
     * Название аналитического материала.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String title;

    /**
     * Исходное имя файла для скачивания.
     */
    @Column(name = "file_name", nullable = false, columnDefinition = "text")
    private String fileName;

    /**
     * Относительный путь к оригиналу PDF в файловом хранилище.
     */
    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    private String filePath;

    /**
     * Размер оригинального файла в байтах.
     */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * SHA-256 оригинала для проверки его целостности.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String sha256;

    /**
     * Время подготовки документа к сохранению.
     */
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /**
     * Дата публикации; отсутствует, если её не указал загрузивший пользователь.
     */
    @Column(name = "publication_date")
    private LocalDate publicationDate;

    /**
     * Количество чанков документа, записанных в Qdrant.
     */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /**
     * Версия контракта ответа анализатора.
     */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /**
     * Версия модели, вычислившей векторы фрагментов.
     */
    @Column(name = "embedding_model", nullable = false, columnDefinition = "text")
    private String embeddingModel;

    /**
     * Число координат каждого вектора.
     */
    @Column(name = "embedding_dimension", nullable = false)
    private int embeddingDimension;

    /**
     * Признак синтетического результата локальной заглушки ML.
     */
    @Column(nullable = false)
    private boolean stub;

    /**
     * Создаёт метаданные проверенного PDF и результата ML без сохранения бинарного содержимого в БД.
     */
    public KnowledgeDocument(UUID id, String title, String fileName, String filePath, long sizeBytes,
                             String sha256, Instant uploadedAt, LocalDate publicationDate, int chunkCount, int schemaVersion,
                             String embeddingModel, int embeddingDimension, boolean stub) {
        this.id = id;
        this.title = title;
        this.fileName = fileName;
        this.filePath = filePath;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedAt = uploadedAt;
        this.publicationDate = publicationDate;
        this.chunkCount = chunkCount;
        this.schemaVersion = schemaVersion;
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.stub = stub;
    }
}
