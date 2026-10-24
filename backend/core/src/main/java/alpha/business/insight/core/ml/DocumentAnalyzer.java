package alpha.business.insight.core.ml;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * Синхронный HTTP-клиент ML для подготовки PDF и векторизации поисковых запросов.
 */
@FeignClient(name = "document-analyzer", url = "${ml.url:http://localhost:8000}")
public interface DocumentAnalyzer {
    /** Получает вектор запроса и проверяет совместимость с существующей коллекцией Qdrant. */
    default List<Float> embed(String query) {
        Embedding result = embedding(Map.of("text", query));
        if (result == null || !result.stub() || !"stub-v1".equals(result.embeddingModel())
                || result.embeddingDimension() != 8 || result.embedding() == null || result.embedding().size() != 8
                || result.embedding().stream().anyMatch(value -> value == null || !Float.isFinite(value))
                || result.embedding().stream().allMatch(value -> value == 0f)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "ML вернул некорректный или несовместимый поисковый вектор: требуется stub-v1 размерности 8");
        }
        return result.embedding();
    }

    @PostMapping(value = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    Embedding embedding(@RequestBody Map<String, String> request);

    record Embedding(String embeddingModel, int embeddingDimension, boolean stub, List<Float> embedding) {}

    /**
     * Передаёт PDF в теле запроса; записи в PostgreSQL и Qdrant остаются ответственностью Java.
     *
     * @param documentId идентификатор исходного документа в URL запроса
     * @param pdf оригинальные байты PDF с Content-Type application/pdf
     * @return чанки с текстом, страницами и embeddings для записи в Qdrant
     */
    @PostMapping(value = "/documents/{documentId}/analyze", consumes = MediaType.APPLICATION_PDF_VALUE)
    DocumentAnalysis analyze(@PathVariable("documentId") UUID documentId, @RequestBody byte[] pdf);
}
