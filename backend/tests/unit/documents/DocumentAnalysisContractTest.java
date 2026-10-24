package alpha.business.insight.core.qa.documents.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentChunk;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Проверяет обязательные поля и ограничения результата анализа документа. */
@Epic("Backend")
@Feature("Документы")
@Story("Проверка контракта анализа PDF")
@Tag("unit")
@Tag("documents")
class DocumentAnalysisContractTest {
    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FOREIGN_DOCUMENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Проверяет принятие корректных фрагментов с разных страниц документа. */
    @Test
    @DisplayName("TestUnitAcceptValidChunksAcrossPages")
    void TestUnitAcceptValidChunksAcrossPages() {
        DocumentAnalysis validAnalysis =
                analysis(
                        DOCUMENT_ID,
                        1,
                        "fixture-model",
                        2,
                        List.of(
                                new DocumentChunk(0, 1, "Ставка 16%", List.of(0.5f, -0.25f)),
                                new DocumentChunk(1, 3, "Прогноз на 2027 год", List.of(0f, 1f))));

        Allure.step(
                "Принять анализ с последовательными ordinal на нескольких страницах",
                () -> {
                    TestEvidence.input("Ожидаемый documentId", DOCUMENT_ID);
                    TestEvidence.input("Ответ анализа", validAnalysis);

                    // Проверяет результат публичной валидации контракта.
                    assertThatCode(() -> validAnalysis.validate(DOCUMENT_ID))
                            .doesNotThrowAnyException();
                    TestEvidence.output(
                            "Наблюдение validate", "validate завершился без исключения");
                });
    }

    /** Проверяет отказ для каждого сценария, нарушающего контракт анализа. */
    @ParameterizedTest(name = "{displayName} [{index}]: {0}")
    @MethodSource("invalidContracts")
    @DisplayName("TestUnitRejectInvalidAnalysisContract")
    void TestUnitRejectInvalidAnalysisContract(
            String scenario, DocumentAnalysis invalid, UUID expectedDocumentId) {
        TestEvidence.stepThrows(
                "Отклонить нарушенный контракт анализа: " + scenario,
                List.of(
                        new TestEvidence.Input("Сценарий нарушения", scenario),
                        new TestEvidence.Input("Ожидаемый documentId", expectedDocumentId),
                        new TestEvidence.Input("Ответ анализа", invalid)),
                () -> invalid.validate(expectedDocumentId),
                error ->
                        assertThat(error)
                                .as(scenario)
                                .isInstanceOf(IllegalArgumentException.class));
    }

    /** Возвращает примеры результатов анализа с отдельными нарушениями контракта. */
    private static Stream<Arguments> invalidContracts() {
        UUID id = DOCUMENT_ID;
        var validChunk = chunk(0, 1, "текст", 1f);
        return Stream.of(
                Arguments.of(
                        "чужой documentId",
                        analysis(FOREIGN_DOCUMENT_ID, 1, "fixture-model", 1, List.of(validChunk)),
                        id),
                Arguments.of(
                        "неподдерживаемая schemaVersion",
                        analysis(id, 2, "fixture-model", 1, List.of(validChunk)),
                        id),
                Arguments.of(
                        "пустая embeddingModel", analysis(id, 1, " ", 1, List.of(validChunk)), id),
                Arguments.of(
                        "нулевая embeddingDimension",
                        analysis(id, 1, "fixture-model", 0, List.of(validChunk)),
                        id),
                Arguments.of(
                        "отрицательная embeddingDimension",
                        analysis(id, 1, "fixture-model", -1, List.of(validChunk)),
                        id),
                Arguments.of(
                        "пустой список чанков", analysis(id, 1, "fixture-model", 1, List.of()), id),
                Arguments.of(
                        "null вместо списка чанков", analysis(id, 1, "fixture-model", 1, null), id),
                Arguments.of(
                        "повторный ordinal",
                        analysis(
                                id,
                                1,
                                "fixture-model",
                                1,
                                List.of(validChunk, chunk(0, 2, "второй", 1f))),
                        id),
                Arguments.of(
                        "отрицательный ordinal",
                        analysis(id, 1, "fixture-model", 1, List.of(chunk(-1, 1, "текст", 1f))),
                        id),
                Arguments.of(
                        "страница меньше единицы",
                        analysis(id, 1, "fixture-model", 1, List.of(chunk(0, 0, "текст", 1f))),
                        id),
                Arguments.of(
                        "пустой текст",
                        analysis(id, 1, "fixture-model", 1, List.of(chunk(0, 1, "  ", 1f))),
                        id),
                Arguments.of(
                        "длина вектора отличается от embeddingDimension",
                        analysis(
                                id,
                                1,
                                "fixture-model",
                                2,
                                List.of(new DocumentChunk(0, 1, "текст", List.of(1f)))),
                        id),
                Arguments.of(
                        "вектор длиннее embeddingDimension",
                        analysis(
                                id,
                                1,
                                "fixture-model",
                                2,
                                List.of(new DocumentChunk(0, 1, "текст", List.of(1f, 0f, 0f)))),
                        id),
                Arguments.of("NaN в embedding", vectorCase(id, List.of(Float.NaN, 0f)), id),
                Arguments.of(
                        "Infinity в embedding",
                        vectorCase(id, List.of(Float.POSITIVE_INFINITY, 0f)),
                        id),
                Arguments.of(
                        "null-координата в embedding", vectorCase(id, Arrays.asList(null, 1f)), id),
                Arguments.of("нулевой embedding", vectorCase(id, List.of(0f, 0f)), id),
                Arguments.of(
                        "null вместо чанка",
                        analysis(id, 1, "fixture-model", 1, Collections.singletonList(null)),
                        id),
                Arguments.of(
                        "null вместо embedding",
                        analysis(
                                id,
                                1,
                                "fixture-model",
                                1,
                                List.of(new DocumentChunk(0, 1, "текст", null))),
                        id));
    }

    /** Собирает результат с вектором, содержащим проверяемое некорректное значение. */
    private static DocumentAnalysis vectorCase(UUID id, List<Float> embedding) {
        return new DocumentAnalysis(
                id,
                1,
                false,
                "fixture-model",
                2,
                List.of(new DocumentChunk(0, 1, "текст", embedding)));
    }

    /** Создаёт результат анализа с переданными метаданными и фрагментами. */
    private static DocumentAnalysis analysis(
            UUID id, int version, String model, int dimension, List<DocumentChunk> chunks) {
        return new DocumentAnalysis(id, version, false, model, dimension, chunks);
    }

    /** Создаёт тестовый фрагмент с однокомпонентным вектором. */
    private static DocumentChunk chunk(int ordinal, int page, String text, float firstValue) {
        return new DocumentChunk(ordinal, page, text, List.of(firstValue));
    }
}
