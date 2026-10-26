package alpha.business.insight.core.ml;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Результат подготовки PDF: чанки с текстом, страницами и векторами для Qdrant.
 *
 * @param documentId Идентификатор исходного документа; должен совпадать с запросом Java.
 * @param schemaVersion Версия контракта; поддерживается значение 1.
 * @param stub Признак синтетического ответа заглушки ML.
 * @param embeddingModel Версия модели, общей для векторов фрагментов и будущих поисковых запросов.
 * @param embeddingDimension Размерность всех векторов в chunks.
 * @param chunks Непустой список чанков с уникальными ordinal.
 */
@Schema(description = "Результат подготовки PDF: чанки с текстом, страницами и векторами для Qdrant.")
public record DocumentAnalysis(
        @Schema(description = "Идентификатор исходного документа; должен совпадать с запросом Java.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID documentId,
        @Schema(description = "Версия контракта; поддерживается значение 1.", allowableValues = {"1"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        int schemaVersion,
        @Schema(description = "Признак синтетического ответа заглушки ML.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean stub,
        @Schema(description = "Версия модели, общей для векторов фрагментов и будущих поисковых запросов.", example = "stub-v1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String embeddingModel,
        @Schema(description = "Размерность всех векторов в chunks.", minimum = "1", example = "8",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int embeddingDimension,
        @Schema(description = "Непустой список чанков: текст, страница и embedding каждого чанка.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<DocumentChunk> chunks
) {
    /**
     * Проверяет принадлежность документа, версию контракта, номера чанков и пригодность векторов.
     *
     * @param expectedId идентификатор документа, переданного анализатору
     * @throws IllegalArgumentException если ответ неполон, номера чанков повторяются или векторы некорректны
     */
    public void validate(UUID expectedId) {
        if (!expectedId.equals(documentId) || schemaVersion != 1 || embeddingModel == null
                || embeddingModel.isBlank() || embeddingDimension <= 0
                || chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Некорректный контракт ответа ML");
        }
        Set<Integer> ordinals = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.ordinal() < 0 || !ordinals.add(chunk.ordinal()) || chunk.page() < 1
                    || chunk.text() == null || chunk.text().isBlank() || chunk.embedding() == null
                    || chunk.embedding().size() != embeddingDimension
                    || chunk.embedding().stream().anyMatch(v -> v == null || !Float.isFinite(v))
                    || chunk.embedding().stream().noneMatch(v -> v != 0)) {
                throw new IllegalArgumentException("Некорректный фрагмент или вектор ML");
            }
        }
    }
}
