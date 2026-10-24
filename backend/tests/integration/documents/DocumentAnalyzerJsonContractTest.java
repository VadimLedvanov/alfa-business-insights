package alpha.business.insight.core.qa.documents.integration;

import alpha.business.insight.core.config.MlClientConfig;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.qa.helpers.SyntheticPdf;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import com.sun.net.httpserver.HttpServer;
import feign.codec.DecodeException;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Проверяет реальное JSON-декодирование ответа Feign на локальном HTTP-сервере. */
@Epic("Backend")
@Feature("Документы")
@Story("Строгая проверка JSON-контракта ML")
@Tag("integration")
@Tag("documents")
class DocumentAnalyzerJsonContractTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
            .withPropertyValues("spring.cloud.openfeign.client.config.default.capabilities=alpha.business.insight.core.qa.connectors.AllureFeignCapability")
            .withUserConfiguration(MlClientConfig.class, StubDocumentAnalyzer.class);

    /** Проверяет отказ декодера, если координата embedding приходит JSON-строкой. */
    @Test
    @Tag("known-defect")
    @DisplayName("TestIntegrationRejectStringEmbedding")
    void TestIntegrationRejectStringEmbedding() throws Exception {
        // Готовит корректный ответ анализа и заменяет одну координату embedding строкой.
        UUID documentId = UUID.randomUUID();
        byte[] pdf = SyntheticPdf.validDocument();
        DocumentAnalysis validAnalysis = new StubDocumentAnalyzer().analyze(documentId, pdf);
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode responseJson = (ObjectNode) json.readTree(json.writeValueAsBytes(validAnalysis));
        ArrayNode chunks = (ArrayNode) responseJson.get("chunks");
        ObjectNode firstChunk = (ObjectNode) chunks.get(0);
        ArrayNode embedding = (ArrayNode) firstChunk.get("embedding");
        embedding.set(0, json.getNodeFactory().textNode("1.0"));
        byte[] response = json.writeValueAsBytes(responseJson);
        var receivedPath = new AtomicReference<String>();
        var receivedPdf = new AtomicReference<byte[]>();
        var decodeFailure = new AtomicReference<Throwable>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedPdf.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            // Вызывает настоящий Feign-декодер через локальный HTTP-сервер.
            String path = "/documents/" + documentId + "/analyze";

            Allure.step("Проверить отклонение ответа ML с embedding неверного JSON-типа", () -> {
                context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:"
                                + server.getAddress().getPort())
                        .run(application -> {
                            DocumentAnalyzer analyzer = application.getBean(DocumentAnalyzer.class);
                            decodeFailure.set(catchThrowable(() -> analyzer.analyze(documentId, pdf)));
                        });

                // Проверяет фактический запрос и отказ для неверного JSON-типа.
                assertThat(receivedPath.get()).isEqualTo(path);
                assertThat(receivedPdf.get()).containsExactly(pdf);
                Throwable actualDecodeOutcome = decodeFailure.get();
                if (actualDecodeOutcome == null) {
                    TestEvidence.output("Фактический результат декодирования", "exception: none");
                } else {
                    TestEvidence.exception(actualDecodeOutcome);
                }
                assertThat(decodeFailure.get())
                        .as("string_embedding_contract: JSON-строка вместо числовой координаты")
                        .isInstanceOf(DecodeException.class);
            });
        } finally {
            server.stop(0);
        }
    }
}
