package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.InsightFragment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Проверяет контракт и привязывает утверждения только к источникам текущего прогона. */
@Component
public class InsightValidator {
    private static final Logger log = LoggerFactory.getLogger(InsightValidator.class);
    private final ObjectMapper mapper;

    public InsightValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode validate(ObjectNode result, List<InsightFragment> fragments, List<String> missing,
                               UUID traceId, BigDecimal costRub) {
        ObjectNode validated = mapper.createObjectNode();
        List<String> normalizationNotes = new java.util.ArrayList<>();
        for (String field : List.of("facts", "bank_view", "ai_inference", "hypotheses", "features_used",
                "risks", "opportunities", "actions", "missing_data")) {
            JsonNode value = result.get(field);
            if ("ai_inference".equals(field) && (value == null || value.isNull())) {
                validated.putArray(field);
                normalizationNotes.add("LLM не вернула ai_inference; выводы отсутствуют");
                log.warn("LLM contract field normalized field={} actualType={}", field,
                        value == null ? "missing" : "null");
            } else if ("ai_inference".equals(field) && value.isObject()) {
                validated.putArray(field).add(value.deepCopy());
                log.warn("LLM contract field normalized field={} actualType=object", field);
            } else if (value == null || !value.isArray()) {
                log.error("LLM contract violation field={} actualType={}", field,
                        value == null ? "missing" : value.getNodeType());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM нарушила JSON-контракт: " + field);
            } else {
                validated.set(field, value.deepCopy());
            }
        }
        String significance = result.path("significance").asString();
        String significanceReason = result.path("significanceReason").asString();
        if (significance == null || !Set.of("SIGNIFICANT", "NOT_SIGNIFICANT", "UNKNOWN").contains(significance)
                || significanceReason == null || significanceReason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "LLM нарушила JSON-контракт: significance и significanceReason");
        }
        validated.put("significance", significance);
        validated.put("significanceReason", significanceReason);
        result = validated;
        Map<String, InsightFragment> byId = new LinkedHashMap<>();
        fragments.forEach(fragment -> byId.put(fragment.fragmentId().toString(), fragment));
        Set<String> missingData = new LinkedHashSet<>(missing);
        missingData.addAll(normalizationNotes);
        result.path("missing_data").forEach(item -> {
            if (item.isString() && !item.asString().isBlank()) { missingData.add(item.asString()); }
        });
        for (String field : List.of("facts", "bank_view")) {
            ArrayNode verified = mapper.createArrayNode();
            for (JsonNode item : result.path(field)) {
                InsightFragment source = byId.get(item.path("fragment_id").asString());
                if (item.isObject() && item.path("text").isString() && !item.path("text").asString().isBlank()
                        && source != null && !source.stub() && (!field.equals("bank_view") || source.bankMaterial())) {
                    verified.add(mapper.createObjectNode().put("text", item.path("text").asString())
                            .put("fragment_id", source.fragmentId().toString()));
                } else {
                    missingData.add("Удалены неподтверждённые утверждения из " + field);
                }
            }
            result.set(field, verified);
        }
        ArrayNode inferences = mapper.createArrayNode();
        for (JsonNode item : result.path("ai_inference")) {
            if (!item.isObject() || !item.path("text").isString()
                    || item.path("text").asString().isBlank()) { continue; }
            ObjectNode inference = mapper.createObjectNode().put("text", item.path("text").asString());
            ArrayNode basedOn = mapper.createArrayNode();
            boolean invalid = !item.path("based_on").isArray();
            for (JsonNode reference : item.path("based_on")) {
                InsightFragment source = byId.get(reference.asString());
                if (source != null && !source.stub()) { basedOn.add(reference.asString()); }
                else { invalid = true; }
            }
            String confidence = item.path("confidence").asString();
            inference.set("based_on", basedOn);
            if (invalid || basedOn.isEmpty() || !Set.of("low", "medium", "high").contains(confidence)) {
                confidence = "low";
            }
            inference.put("confidence", confidence);
            inferences.add(inference);
        }
        result.set("ai_inference", inferences);
        for (String field : List.of("hypotheses", "risks", "opportunities", "actions")) {
            ArrayNode items = mapper.createArrayNode();
            for (JsonNode item : result.path(field)) {
                String text = item.isString() ? item.asString() : item.path("text").asString();
                if (!text.isBlank()) {
                    items.add(mapper.createObjectNode().put("text", text));
                }
            }
            result.set(field, items);
        }
        ArrayNode features = mapper.createArrayNode();
        for (JsonNode feature : result.path("features_used")) {
            if (feature.isString() && !feature.asString().isBlank()) {
                features.add(feature.asString());
            }
        }
        result.set("features_used", features);
        if (fragments.stream().noneMatch(InsightFragment::bankMaterial)) {
            result.putArray("bank_view");
            missingData.add("Не найдены материалы банка: позиция банка отсутствует");
        }
        if (fragments.stream().anyMatch(InsightFragment::stub)) {
            missingData.add("[ML STUB] Поиск использует синтетические фрагменты; они не подтверждают факты или позицию банка");
        }
        ArrayNode sources = result.putArray("sources");
        for (InsightFragment fragment : fragments) {
            sources.add(mapper.valueToTree(fragment.reference()));
        }
        ArrayNode limitations = result.putArray("missing_data");
        missingData.forEach(limitations::add);
        result.put("trace_id", traceId.toString());
        result.put("cost_rub", costRub);
        return result;
    }
}
