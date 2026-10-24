package alpha.business.insight.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

/** Найденный в текущем прогоне источник; текст и страница получены из Qdrant. */
public record InsightFragment(@JsonProperty("fragment_id") UUID fragmentId,
                              @JsonProperty("document_id") UUID documentId,
                              int page, String text,
                              @JsonProperty("material_type") String materialType,
                              boolean stub) {
    public boolean bankMaterial() {
        return "BANK_ANALYTICS".equals(materialType) && !stub;
    }

    /** Ссылка для результата и журнала; исходный текст остаётся только в поисковом контексте. */
    public Map<String, Object> reference() {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("fragment_id", fragmentId);
        reference.put("document_id", documentId);
        reference.put("page", page);
        if (documentId != null) {
            reference.put("url", "/api/documents/" + documentId + "/file#page=" + page);
        }
        return reference;
    }
}
