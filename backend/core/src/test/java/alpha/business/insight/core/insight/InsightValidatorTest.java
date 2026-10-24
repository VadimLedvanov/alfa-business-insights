package alpha.business.insight.core.insight;

import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.service.InsightValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InsightValidatorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID fragmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void removesUnretrievedFactsAndBankViewFromNonBankSources() {
        ObjectNode input = empty();
        input.withArray("facts").addObject().put("text", "Подтверждено").put("fragment_id", fragmentId.toString());
        input.withArray("facts").addObject().put("text", "Выдуманный факт").put("fragment_id", UUID.randomUUID().toString());
        input.withArray("facts").addObject().put("text", "Без ссылки");
        input.withArray("bank_view").addObject().put("text", "Приписанная позиция").put("fragment_id", fragmentId.toString());
        input.withArray("sources").addObject().put("url", "https://invented.example");
        InsightFragment fragment = new InsightFragment(fragmentId, null, 1, "Событие", "EVENT_TEXT", false);
        ObjectNode result = new InsightValidator(mapper).validate(input, List.of(fragment), List.of(), UUID.randomUUID(), BigDecimal.ZERO);
        assertThat(result.path("facts").size()).isEqualTo(1);
        assertThat(result.path("facts").get(0).path("fragment_id").asString()).isEqualTo(fragmentId.toString());
        assertThat(result.path("bank_view").isEmpty()).isTrue();
        assertThat(result.path("missing_data").toString()).contains("банк");
        assertThat(result.path("sources").toString()).doesNotContain("invented.example");
    }

    @Test
    void acceptsBankReferencesOnlyFromRealBankMaterial() {
        ObjectNode input = empty();
        input.withArray("bank_view").addObject().put("text", "Оценка банка").put("fragment_id", fragmentId.toString());
        InsightFragment real = new InsightFragment(fragmentId, documentId, 3, "Материал банка", "BANK_ANALYTICS", false);
        InsightValidator validator = new InsightValidator(mapper);
        ObjectNode accepted = validator.validate(input.deepCopy(), List.of(real), List.of(), UUID.randomUUID(), BigDecimal.ZERO);
        assertThat(accepted.path("bank_view").size()).isEqualTo(1);
        assertThat(accepted.path("sources").get(0).path("page").asInt()).isEqualTo(3);
        assertThat(accepted.path("sources").get(0).path("fragment_id").asString()).isEqualTo(fragmentId.toString());
        assertThat(accepted.path("sources").get(0).path("text").isMissingNode()).isTrue();
        InsightFragment synthetic = new InsightFragment(fragmentId, documentId, 3, "[ML STUB]", "BANK_ANALYTICS", true);
        ObjectNode rejected = validator.validate(input.deepCopy(), List.of(synthetic), List.of(), UUID.randomUUID(), BigDecimal.ZERO);
        assertThat(rejected.path("bank_view").isEmpty()).isTrue();
        assertThat(rejected.path("missing_data").toString()).contains("банк");
    }

    @Test
    void removesSyntheticFactsAndNormalizesInferenceGrounding() {
        ObjectNode input = empty();
        input.withArray("facts").addObject().put("text", "Синтетический факт").put("fragment_id", fragmentId.toString());
        input.withArray("ai_inference").addObject().put("text", "Предположение").put("confidence", "high")
                .putArray("based_on").add("invented");
        InsightFragment synthetic = new InsightFragment(fragmentId, documentId, 1, "[ML STUB]", "BANK_ANALYTICS", true);
        ObjectNode result = new InsightValidator(mapper).validate(input, List.of(synthetic), List.of("profile.region"),
                UUID.randomUUID(), new BigDecimal("1.5"));
        assertThat(result.path("facts").isEmpty()).isTrue();
        assertThat(result.path("ai_inference").get(0).path("based_on").isEmpty()).isTrue();
        assertThat(result.path("ai_inference").get(0).path("confidence").asString()).isEqualTo("low");
        assertThat(result.path("missing_data").toString()).contains("profile.region", "STUB");
        assertThat(result.path("cost_rub").decimalValue()).isEqualByComparingTo("1.5");
    }

    @Test
    void discardsNestedSourceCopiesFromModelOutput() {
        String fragmentText = "RAW_FRAGMENT_BODY_DO_NOT_PERSIST";
        ObjectNode input = empty();
        input.withArray("facts").addObject().put("text", "Факт").put("fragment_id", fragmentId.toString())
                .putObject("source_fragment").put("text", fragmentText);
        input.withArray("ai_inference").addObject().put("text", "Вывод").put("confidence", "low")
                .putObject("source_fragment").put("text", fragmentText);
        for (String field : List.of("hypotheses", "risks", "opportunities", "actions")) {
            input.withArray(field).addObject().put("text", "Результат анализа")
                    .putArray("sources").addObject().put("text", fragmentText);
        }
        input.withArray("features_used").add("profile.region").addObject().put("text", fragmentText);
        InsightFragment fragment = new InsightFragment(fragmentId, documentId, 1, fragmentText, "BANK_ANALYTICS", false);
        ObjectNode result = new InsightValidator(mapper).validate(input, List.of(fragment), List.of(),
                UUID.randomUUID(), BigDecimal.ZERO);
        assertThat(result.toString()).doesNotContain(fragmentText);
        assertThat(result.path("facts").get(0).path("text").asString()).isEqualTo("Факт");
        assertThat(result.path("features_used").size()).isEqualTo(1);
    }

    private ObjectNode empty() {
        ObjectNode result = mapper.createObjectNode();
        for (String field : List.of("facts", "bank_view", "ai_inference", "hypotheses", "features_used", "risks",
                "opportunities", "actions", "missing_data", "sources")) { result.putArray(field); }
        return result;
    }
}
