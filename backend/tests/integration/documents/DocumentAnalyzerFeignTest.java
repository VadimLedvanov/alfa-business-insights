package alpha.business.insight.core.document;

import alpha.business.insight.core.config.MlClientConfig;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import io.qameta.allure.Step;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверяет Feign-клиент анализатора на локальном HTTP-сервере и конфигурацию заглушки. */
@Tag("integration")
@Tag("documents")
class DocumentAnalyzerFeignTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
            .withPropertyValues("spring.cloud.openfeign.client.config.default.capabilities=alpha.business.insight.core.qa.connectors.AllureFeignCapability")
            .withUserConfiguration(MlClientConfig.class, StubDocumentAnalyzer.class);

    /** Проверяет локальную заглушку по умолчанию без HTTP-клиента. */
    @Test
    void defaultsToLocalStubWithoutHttpClient() {
        context.run(application -> {
            DocumentAnalyzer analyzer = application.getBean(DocumentAnalyzer.class);
            TestEvidence.output("Фактический bean анализатора", Map.of(
                    "class", analyzer.getClass().getName(),
                    "beanNames", List.of(application.getBeanNamesForType(DocumentAnalyzer.class))));

            assertThat(application).hasSingleBean(DocumentAnalyzer.class);
            assertThat(analyzer).isInstanceOf(StubDocumentAnalyzer.class);
        });
    }

    /** Проверяет передачу PDF и идентификатора, а также декодирование ответа Feign. */
    @Test
    void feignSendsPdfAndDocumentIdAndDecodesAnalysis() throws Exception {
        UUID documentId = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4\nTest document".getBytes(StandardCharsets.UTF_8);
        DocumentAnalysis expected = new StubDocumentAnalyzer().analyze(documentId, pdf);
        byte[] response = JsonMapper.builder().build().writeValueAsBytes(expected);
        var receivedBody = new AtomicReference<byte[]>();
        var receivedType = new AtomicReference<String>();
        var receivedPath = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            // Передаёт PDF через настроенный Feign-клиент.
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(application -> {
                        assertThat(application).hasSingleBean(DocumentAnalyzer.class);
                        DocumentAnalysis actual = application.getBean(DocumentAnalyzer.class).analyze(documentId, pdf);
                        TestEvidence.output("Фактически декодированный ответ анализатора", actual);
                        assertThat(actual).isEqualTo(expected);
                    });
            // Проверяет декодированный результат и запрос, полученный локальным сервером.
            assertThat(receivedBody.get()).isEqualTo(pdf);
            assertThat(receivedType.get()).isEqualTo("application/pdf");
            assertThat(receivedPath.get()).isEqualTo("/documents/" + documentId + "/analyze");
        } finally {
            server.stop(0);
        }
    }

    /** Проверяет удалённый embedding и восстановление после ответа 503. */
    @Test
    @Step("Проверяет удалённое embedding и повтор после ошибки сервиса")
    void feignCallsRemoteEmbeddingAndValidatesModel() throws Exception {
        var received = new AtomicReference<String>();
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (unavailable.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] response = "{\"embeddingModel\":\"stub-v1\",\"embeddingDimension\":8,\"stub\":true,\"embedding\":[1,0.5,0.25,0.125,0,0,0,0]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(application -> {
                        DocumentAnalyzer analyzer = application.getBean(DocumentAnalyzer.class);
                        assertThat(analyzer.embed("ставка")).containsExactly(1f, 0.5f, 0.25f, 0.125f, 0f, 0f, 0f, 0f);
                        unavailable.set(true);
                        assertThatThrownBy(() -> analyzer.embed("ставка")).isInstanceOf(FeignException.class);
                        unavailable.set(false);
                        assertThat(analyzer.embed("ставка")).containsExactly(1f, 0.5f, 0.25f, 0.125f, 0f, 0f, 0f, 0f);
                    });
            assertThat(JsonMapper.builder().build().readTree(received.get()).path("text").asString()).isEqualTo("ставка");
        } finally { server.stop(0); }
    }

    @Test
    @Step("Проверяет тайм-аут ML без повторного запроса")
    void stalledMlCallTimesOutWithoutRetry() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "spring.cloud.openfeign.client.config.document-analyzer.readTimeout=100")
                    .run(application -> {
                        long started = System.nanoTime();
                        assertThatThrownBy(() -> application.getBean(DocumentAnalyzer.class).embed("ставка"))
                                .isInstanceOf(feign.RetryableException.class);
                        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                                .isLessThan(java.time.Duration.ofSeconds(3));
                    });
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** Проверяет передачу ошибки сервиса без повторного запроса. */
    @Test
    void feignPropagatesServiceErrorWithoutRetryingUpload() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            // Проверяет распространение ошибки сервиса без повтора запроса.
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(application -> assertThatThrownBy(() -> TestEvidence.capture(() ->
                            application.getBean(DocumentAnalyzer.class).analyze(UUID.randomUUID(), new byte[]{1})))
                            .isInstanceOf(FeignException.class));
            TestEvidence.output("Фактическое число запросов к локальному анализатору", requests.get());
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
