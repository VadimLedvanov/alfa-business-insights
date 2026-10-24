package alpha.business.insight.core.insight;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.service.InsightValidator;
import io.qameta.allure.Step;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Проверяет очистку и нормализацию ответа модели перед сохранением. */
@Tag("unit")
@Tag("insights")
class InsightValidatorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID fragmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Удаляет неподтверждённые факты и позицию банка. */
    @Test
    @Step("Удаляет неподтверждённые факты и позицию банка")
    void removesUnretrievedFactsAndBankViewFromNonBankSources() {
        // Подготовка: включает подтверждённый факт, неподтверждённые данные и обычный источник.
        ObjectNode input = empty();
        input.withArray("facts")
                .addObject()
                .put("text", "Подтверждено")
                .put("fragment_id", fragmentId.toString());
        input.withArray("facts")
                .addObject()
                .put("text", "Выдуманный факт")
                .put("fragment_id", UUID.randomUUID().toString());
        input.withArray("facts").addObject().put("text", "Без ссылки");
        input.withArray("bank_view")
                .addObject()
                .put("text", "Приписанная позиция")
                .put("fragment_id", fragmentId.toString());
        input.withArray("sources").addObject().put("url", "https://invented.example");
        InsightFragment fragment =
                new InsightFragment(fragmentId, null, 1, "Событие", "EVENT_TEXT", false);

        // Действие
        ObjectNode result =
                new InsightValidator(mapper)
                        .validate(
                                input,
                                List.of(fragment),
                                List.of(),
                                UUID.randomUUID(),
                                BigDecimal.ZERO);

        // Проверки
        assertThat(result.path("facts").size())
                .as("оставшийся факт с реальной ссылкой")
                .isEqualTo(1);
        assertThat(result.path("facts").get(0).path("fragment_id").asString())
                .as("идентификатор подтверждающего фрагмента")
                .isEqualTo(fragmentId.toString());
        assertThat(result.path("bank_view").isEmpty())
                .as("позиция банка из обычного источника")
                .isTrue();
        assertThat(result.path("missing_data").toString())
                .as("причина отсутствия позиции банка")
                .contains("банк");
        assertThat(result.path("sources").toString())
                .as("список проверенных источников")
                .doesNotContain("invented.example");
    }

    /** Принимает позицию банка только по реальному материалу. */
    @Test
    @Step("Принимает позицию банка только по реальному материалу")
    void acceptsBankReferencesOnlyFromRealBankMaterial() {
        ObjectNode input = empty();
        input.withArray("bank_view")
                .addObject()
                .put("text", "Оценка банка")
                .put("fragment_id", fragmentId.toString());
        InsightFragment real =
                new InsightFragment(
                        fragmentId, documentId, 3, "Материал банка", "BANK_ANALYTICS", false);
        InsightValidator validator = new InsightValidator(mapper);
        ObjectNode accepted =
                validator.validate(
                        input.deepCopy(),
                        List.of(real),
                        List.of(),
                        UUID.randomUUID(),
                        BigDecimal.ZERO);
        assertThat(accepted.path("bank_view").size())
                .as("позиция со ссылкой на реальный материал банка")
                .isEqualTo(1);
        assertThat(accepted.path("sources").get(0).path("page").asInt())
                .as("страница источника")
                .isEqualTo(3);
        assertThat(accepted.path("sources").get(0).path("fragment_id").asString())
                .as("идентификатор источника")
                .isEqualTo(fragmentId.toString());
        assertThat(accepted.path("sources").get(0).path("text").isMissingNode())
                .as("полный текст исходного фрагмента не копируется")
                .isTrue();
        InsightFragment synthetic =
                new InsightFragment(fragmentId, documentId, 3, "[ML STUB]", "BANK_ANALYTICS", true);
        ObjectNode rejected =
                validator.validate(
                        input.deepCopy(),
                        List.of(synthetic),
                        List.of(),
                        UUID.randomUUID(),
                        BigDecimal.ZERO);
        assertThat(rejected.path("bank_view").isEmpty())
                .as("синтетический источник не подтверждает позицию банка")
                .isTrue();
        assertThat(rejected.path("missing_data").toString())
                .as("маркер отсутствующего материала банка")
                .contains("банк");
    }

    /** Фильтрует синтетические факты и нормализует обоснование вывода. */
    @Test
    @Step("Фильтрует синтетические факты и нормализует обоснование вывода")
    void removesSyntheticFactsAndNormalizesInferenceGrounding() {
        ObjectNode input = empty();
        input.withArray("facts")
                .addObject()
                .put("text", "Синтетический факт")
                .put("fragment_id", fragmentId.toString());
        input.withArray("ai_inference")
                .addObject()
                .put("text", "Предположение")
                .put("confidence", "high")
                .putArray("based_on")
                .add("invented");
        InsightFragment synthetic =
                new InsightFragment(fragmentId, documentId, 1, "[ML STUB]", "BANK_ANALYTICS", true);
        ObjectNode result =
                new InsightValidator(mapper)
                        .validate(
                                input,
                                List.of(synthetic),
                                List.of("profile.region"),
                                UUID.randomUUID(),
                                new BigDecimal("1.5"));
        assertThat(result.path("facts").isEmpty()).as("факты из синтетического источника").isTrue();
        assertThat(result.path("ai_inference").get(0).path("based_on").isEmpty())
                .as("неподтверждённая ссылка в обосновании")
                .isTrue();
        assertThat(result.path("ai_inference").get(0).path("confidence").asString())
                .as("уверенность без подтверждающих оснований")
                .isEqualTo("low");
        assertThat(result.path("missing_data").toString())
                .as("нехватка профиля и синтетический источник")
                .contains("profile.region", "STUB");
        assertThat(result.path("cost_rub").decimalValue())
                .as("стоимость анализа")
                .isEqualByComparingTo("1.5");
    }

    /** Удаляет копии исходных фрагментов из ответа. */
    @Test
    @Step("Удаляет копии исходных фрагментов из ответа")
    void discardsNestedSourceCopiesFromModelOutput() {
        String fragmentText = "RAW_FRAGMENT_BODY_DO_NOT_PERSIST";
        ObjectNode input = empty();
        input.withArray("facts")
                .addObject()
                .put("text", "Факт")
                .put("fragment_id", fragmentId.toString())
                .putObject("source_fragment")
                .put("text", fragmentText);
        input.withArray("ai_inference")
                .addObject()
                .put("text", "Вывод")
                .put("confidence", "low")
                .putObject("source_fragment")
                .put("text", fragmentText);
        for (String field : List.of("hypotheses", "risks", "opportunities", "actions")) {
            input.withArray(field)
                    .addObject()
                    .put("text", "Результат анализа")
                    .putArray("sources")
                    .addObject()
                    .put("text", fragmentText);
        }
        input.withArray("features_used")
                .add("profile.region")
                .addObject()
                .put("text", fragmentText);
        InsightFragment fragment =
                new InsightFragment(
                        fragmentId, documentId, 1, fragmentText, "BANK_ANALYTICS", false);
        ObjectNode result =
                new InsightValidator(mapper)
                        .validate(
                                input,
                                List.of(fragment),
                                List.of(),
                                UUID.randomUUID(),
                                BigDecimal.ZERO);
        assertThat(result.toString())
                .as("ответ после удаления копий исходного текста")
                .doesNotContain(fragmentText);
        assertThat(result.path("facts").get(0).path("text").asString())
                .as("текст факта модели")
                .isEqualTo("Факт");
        assertThat(result.path("features_used").size())
                .as("список использованных признаков")
                .isEqualTo(1);
    }

    /** Создаёт пустой JSON-ответ со всеми обязательными массивами контракта. */
    private ObjectNode empty() {
        ObjectNode result = mapper.createObjectNode();
        for (String field :
                List.of(
                        "facts",
                        "bank_view",
                        "ai_inference",
                        "hypotheses",
                        "features_used",
                        "risks",
                        "opportunities",
                        "actions",
                        "missing_data",
                        "sources")) {
            result.putArray(field);
        }
        return result;
    }
}
