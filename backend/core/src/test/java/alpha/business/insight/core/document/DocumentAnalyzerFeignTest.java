package alpha.business.insight.core.document;

import alpha.business.insight.core.config.MlClientConfig;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAnalyzerFeignTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
            .withUserConfiguration(MlClientConfig.class, StubDocumentAnalyzer.class);

    @Test
    void defaultsToLocalStubWithoutHttpClient() {
        context.run(application -> {
            assertThat(application).hasSingleBean(DocumentAnalyzer.class);
            assertThat(application.getBean(DocumentAnalyzer.class)).isInstanceOf(StubDocumentAnalyzer.class);
        });
    }

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
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(application -> {
                        assertThat(application).hasSingleBean(DocumentAnalyzer.class);
                        assertThat(application.getBean(DocumentAnalyzer.class).analyze(documentId, pdf)).isEqualTo(expected);
                    });
            assertThat(receivedBody.get()).isEqualTo(pdf);
            assertThat(receivedType.get()).isEqualTo("application/pdf");
            assertThat(receivedPath.get()).isEqualTo("/documents/" + documentId + "/analyze");
        } finally {
            server.stop(0);
        }
    }

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
            context.withPropertyValues("ml.stub=false", "ml.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(application -> assertThatThrownBy(() -> application.getBean(DocumentAnalyzer.class)
                            .analyze(UUID.randomUUID(), new byte[]{1})).isInstanceOf(FeignException.class));
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
