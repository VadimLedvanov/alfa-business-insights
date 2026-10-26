package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.KnowledgeDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Метаданные PDF, доступные после успешного сохранения документа и его векторов.
 *
 * @param documentId Идентификатор документа, общий с метаданными его векторов.
 * @param title Название аналитического материала.
 * @param fileName Исходное имя PDF, используемое при скачивании.
 * @param sizeBytes Размер исходного PDF в байтах.
 * @param sha256 SHA-256 исходного файла в шестнадцатеричном представлении.
 * @param uploadedAt Время подготовки документа к сохранению.
 * @param publicationDate Дата публикации; отсутствует, если её не указали при загрузке.
 * @param chunkCount Количество чанков с текстом и векторами в Qdrant.
 * @param schemaVersion Версия контракта ответа ML.
 * @param embeddingModel Идентификатор версии модели векторов.
 * @param embeddingDimension Число координат вектора каждого фрагмента.
 * @param stub Признак синтетических данных заглушки; PDF в этом режиме не анализировался.
 */
@Schema(description = "Метаданные PDF, доступные после успешного сохранения документа и его векторов.")
public record DocumentMetadata(
        @Schema(description = "Идентификатор документа, общий с метаданными его векторов.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID documentId,
        @Schema(description = "Название аналитического материала.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
        @Schema(description = "Исходное имя PDF, используемое при скачивании.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String fileName,
        @Schema(description = "Размер исходного PDF в байтах.", minimum = "1", maximum = "52428800",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long sizeBytes,
        @Schema(description = "SHA-256 исходного файла в шестнадцатеричном представлении.", minLength = 64, maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String sha256,
        @Schema(description = "Время подготовки документа к сохранению.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant uploadedAt,
        @Schema(description = "Дата публикации; отсутствует, если её не указали при загрузке.", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDate publicationDate,
        @Schema(description = "Количество чанков документа в Qdrant.", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int chunkCount,
        @Schema(description = "Версия контракта ответа ML.", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int schemaVersion,
        @Schema(description = "Идентификатор версии модели векторов.", example = "stub-v1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String embeddingModel,
        @Schema(description = "Число координат вектора каждого фрагмента.", example = "8", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int embeddingDimension,
        @Schema(description = "Признак синтетических данных заглушки; PDF в этом режиме не анализировался.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean stub
) {
    /**
     * Копирует метаданные JPA-сущности, не раскрывая путь к файлу на сервере.
     *
     * @param document метаданные успешно загруженного PDF
     * @return представление документа для API
     */
    public static DocumentMetadata from(KnowledgeDocument document) {
        return new DocumentMetadata(document.getId(), document.getTitle(), document.getFileName(),
                document.getSizeBytes(), document.getSha256(), document.getUploadedAt(), document.getPublicationDate(),
                document.getChunkCount(), document.getSchemaVersion(), document.getEmbeddingModel(),
                document.getEmbeddingDimension(), document.isStub());
    }
}
