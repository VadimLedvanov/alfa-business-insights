package alpha.business.insight.core.insight;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.service.InsightValidator;
import io.qameta.allure.Allure;
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
    private final UUID bankFragmentId = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** Удаляет неподтверждённые факты и позицию банка из неподходящего материала. */
    @Test
    @Step("Удаляет неподтверждённые факты и позицию банка из неподходящего материала")
    void removesUnretrievedFactsAndBankViewFromNonBankSources() {
        // Подготовка: утверждения ссылаются на обычный фрагмент и реальный материал банка.
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
                .put("text", "Позиция по обычному источнику")
                .put("fragment_id", fragmentId.toString());
        input.withArray("bank_view")
                .addObject()
                .put("text", "Позиция по синтетическому материалу")
                .put("fragment_id", bankFragmentId.toString());
        input.withArray("bank_view")
                .addObject()
                .put("text", "Подтверждённая позиция банка")
                .put("fragment_id", documentId.toString());
        input.withArray("sources").addObject().put("url", "https://invented.example");
        InsightFragment ordinaryFragment =
                new InsightFragment(fragmentId, null, 1, "Событие", "EVENT_TEXT", false);
        InsightFragment syntheticBankFragment =
                new InsightFragment(
                        bankFragmentId, documentId, 2, "[ML STUB]", "BANK_ANALYTICS", true);
        InsightFragment realBankFragment =
                new InsightFragment(
                        documentId, documentId, 3, "Материал банка", "BANK_ANALYTICS", false);
        List<InsightFragment> fragments =
                List.of(ordinaryFragment, syntheticBankFragment, realBankFragment);

        // Действие
        ObjectNode result =
                Allure.step(
                        "1. Проверить ответ модели",
                        () -> {
                            Allure.addAttachment(
                                    "input.json", "application/json", input.toPrettyString());
                            return new InsightValidator(mapper)
                                    .validate(
                                            input,
                                            fragments,
                                            List.of(),
                                            UUID.randomUUID(),
                                            BigDecimal.ZERO);
                        });
        Allure.addAttachment("output.json", "application/json", result.toPrettyString());

        // Проверки
        Allure.step(
                "2. Оставить только факт с проверенной ссылкой",
                () -> {
                    assertThat(result.path("facts").size())
                            .as(
                                    "число фактов после проверки: expected=1, actual=%s",
                                    result.path("facts").size())
                            .isEqualTo(1);
                    assertThat(result.path("facts").get(0).path("text").asString())
                            .as("текст факта с реальной ссылкой")
                            .isEqualTo("Подтверждено");
                    assertThat(result.path("facts").get(0).path("fragment_id").asString())
                            .as("идентификатор подтверждающего фрагмента")
                            .isEqualTo(fragmentId.toString());
                });
        Allure.step(
                "3. Оставить позицию банка только по реальному материалу банка",
                () -> {
                    assertThat(result.path("bank_view").size())
                            .as(
                                    "число допустимых позиций банка: expected=1, actual=%s",
                                    result.path("bank_view").size())
                            .isEqualTo(1);
                    assertThat(result.path("bank_view").get(0).path("text").asString())
                            .as("текст позиции из реального материала банка")
                            .isEqualTo("Подтверждённая позиция банка");
                    assertThat(result.path("bank_view").get(0).path("fragment_id").asString())
                            .as("ссылка позиции на реальный фрагмент банка")
                            .isEqualTo(documentId.toString());
                    assertThat(result.path("missing_data").toString())
                            .as(
                                    "причина отклонения позиций из обычного и синтетического материалов")
                            .contains("Удалены неподтверждённые утверждения из bank_view");
                });
        Allure.step(
                "4. Оставить в источниках только процитированные фрагменты",
                () -> {
                    assertThat(result.path("sources").isArray())
                            .as("sources должен быть JSON-массивом")
                            .isTrue();
                    assertThat(result.path("sources").size())
                            .as(
                                    "число процитированных фрагментов: expected=2, actual=%s",
                                    result.path("sources").size())
                            .isEqualTo(2);
                    assertThat(result.path("sources").findValuesAsString("fragment_id"))
                            .as(
                                    "идентификаторы фрагментов, процитированных подтверждёнными утверждениями")
                            .containsExactlyInAnyOrder(
                                    fragmentId.toString(), documentId.toString());
                    assertThat(result.path("sources").toString())
                            .as(
                                    "процитированные источники без неподтверждённых и непроцитированных фрагментов")
                            .doesNotContain("invented.example", bankFragmentId.toString());
                });
    }

    /** Переносит неподтверждённый факт в вывод с низкой уверенностью. */
    @Test
    @Step("Переносит неподтверждённый факт в вывод с низкой уверенностью")
    void TestUnitDemotesUnreferencedFactToLowConfidenceInference() {
        // Подготовка: неподтверждённый текст должен стать выводом, а подтверждённый факт —
        // сохраниться.
        String unsupportedText = "Рост спроса не подтверждён источником";
        String existingInferenceText = "Рынок может расшириться";
        ObjectNode input = empty();
        input.withArray("facts").addObject().put("text", unsupportedText);
        input.withArray("facts")
                .addObject()
                .put("text", "Подтверждённый факт")
                .put("fragment_id", fragmentId.toString());
        input.withArray("ai_inference")
                .addObject()
                .put("text", existingInferenceText)
                .put("confidence", "high")
                .putArray("based_on")
                .add(fragmentId.toString());
        InsightFragment fragment =
                new InsightFragment(fragmentId, documentId, 1, "Материал", "EVENT_TEXT", false);

        // Действие
        ObjectNode result =
                Allure.step(
                        "1. Проверить ответ с неподтверждённым фактом",
                        () -> {
                            Allure.addAttachment(
                                    "input.json", "application/json", input.toPrettyString());
                            return new InsightValidator(mapper)
                                    .validate(
                                            input,
                                            List.of(fragment),
                                            List.of(),
                                            UUID.randomUUID(),
                                            BigDecimal.ZERO);
                        });
        Allure.addAttachment("output.json", "application/json", result.toPrettyString());

        // Проверки
        Allure.step(
                "2. Сохранить подтверждённый факт и убрать неподтверждённый",
                () -> {
                    assertThat(result.path("facts").size())
                            .as(
                                    "число оставшихся фактов: expected=1, actual=%s",
                                    result.path("facts").size())
                            .isEqualTo(1);
                    assertThat(result.path("facts").get(0).path("text").asString())
                            .as("текст факта с реальной ссылкой")
                            .isEqualTo("Подтверждённый факт");
                    assertThat(result.path("facts").toString())
                            .as("факты после валидации не должны содержать неподтверждённый текст")
                            .doesNotContain(unsupportedText);
                });
        Allure.step(
                "3. Добавить неподтверждённый текст как вывод с низкой уверенностью",
                () -> {
                    assertThat(result.path("ai_inference").toString())
                            .as("неподтверждённый факт должен присутствовать среди выводов")
                            .contains(unsupportedText);
                    assertThat(result.path("ai_inference").findValuesAsString("text"))
                            .as("каждый текст вывода должен встречаться ровно один раз")
                            .containsOnlyOnce(unsupportedText)
                            .containsOnlyOnce(existingInferenceText);
                    assertThat(result.path("ai_inference").findValuesAsString("text"))
                            .as("существующий вывод должен сохраниться")
                            .contains(existingInferenceText);
                    var existingInference =
                            result.path("ai_inference").findParents("text").stream()
                                    .filter(
                                            inference ->
                                                    inference
                                                            .path("text")
                                                            .asString()
                                                            .equals(existingInferenceText))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(existingInference.path("confidence").asString())
                            .as("уверенность существующего вывода с подтверждённым основанием")
                            .isEqualTo("high");
                    assertThat(existingInference.path("based_on").get(0).asString())
                            .as("основание существующего вывода")
                            .isEqualTo(fragmentId.toString());
                    var demoted =
                            result.path("ai_inference").findParents("text").stream()
                                    .filter(
                                            inference ->
                                                    inference
                                                            .path("text")
                                                            .asString()
                                                            .equals(unsupportedText))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(demoted.path("confidence").asString())
                            .as("уверенность вывода без подтверждённых оснований")
                            .isEqualTo("low");
                    assertThat(demoted.path("based_on").isArray())
                            .as("обоснование вывода представлено массивом")
                            .isTrue();
                    assertThat(demoted.path("based_on").isEmpty())
                            .as("обоснование вывода без подтверждённых оснований")
                            .isTrue();
                });
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

    /** Создаёт валидный пустой ответ с обязательными массивами и полями значимости. */
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
        result.put("significance", "UNKNOWN");
        result.put("significanceReason", "Недостаточно данных для оценки значимости.");
        return result;
    }
}
