package alpha.business.insight.core.ml;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Локальная замена ML по умолчанию; возвращает синтетические данные без чтения содержимого PDF.
 */
@Component
@ConditionalOnProperty(name = "ml.stub", havingValue = "true", matchIfMissing = true)
public class StubDocumentAnalyzer implements DocumentAnalyzer {
    @Override
    public Embedding embedding(java.util.Map<String, String> request) {
        return new Embedding("stub-v1", 8, true, embed(request.get("text")));
    }

    /** Возвращает тот же синтетический вектор stub-v1, что используется при индексации. */
    @Override
    public List<Float> embed(String query) {
        return List.of(1f, 0.5f, 0.25f, 0.125f, 0f, 0f, 0f, 0f);
    }

    /**
     * Возвращает один помеченный тестовый фрагмент, ненулевой вектор и текст чанка.
     *
     * @param documentId идентификатор документа, возвращаемый без изменений
     * @param pdf оригинал PDF; в режиме заглушки не анализируется
     * @return синтетический результат stub-v1 размерности 8
     */
    @Override
    public DocumentAnalysis analyze(UUID documentId, byte[] pdf) {
        return new DocumentAnalysis(documentId, 1, true, "stub-v1", 8,
                List.of(new DocumentChunk(0, 1,
                        "[ML STUB] Синтетический фрагмент для проверки интеграции. PDF не анализировался.",
                        List.of(1f, 0.5f, 0.25f, 0.125f, 0f, 0f, 0f, 0f))));
    }
}
