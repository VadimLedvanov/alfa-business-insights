package alpha.business.insight.core.ml;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Синхронная граница подготовки PDF: Feign-клиент передаёт оригинал ML-сервису по HTTP.
 */
@FeignClient(name = "document-analyzer", url = "${ml.url:http://localhost:8000}")
public interface DocumentAnalyzer {
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
