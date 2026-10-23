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
