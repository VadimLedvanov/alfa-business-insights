package alpha.business.insight.core.insight;

import alpha.business.insight.core.ml.LlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
            .withBean(ObjectMapper.class, () -> mapper)
            .withUserConfiguration(LlmClient.class);

    @Test
    void disabledChatUsesStubWithoutCreatingRemoteModel() {
        context.withPropertyValues("spring.ai.model.chat=none").run(application -> {
            assertThat(application).doesNotHaveBean(OpenAiChatModel.class);
            ObjectNode result = application.getBean(LlmClient.class).complete("draft", mapper.createObjectNode());
            assertThat(result.path("missing_data").toString()).contains("LLM STUB");
        });
    }

    @Test
    void sendsOpenAiCompatibleRequestAndReadsJson() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = mapper.writeValueAsBytes(Map.of("id", "completion-test", "object", "chat.completion",
                    "created", 1, "model", "z-ai/glm-5.3-flash", "choices", List.of(Map.of("index", 0,
                            "finish_reason", "stop", "message", Map.of("role", "assistant",
                                    "content", "{\"search_queries\":[\"ставка\"]}"))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15, "cost", 0.01)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            context.withPropertyValues("spring.ai.model.chat=openai",
                    "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "spring.ai.openai.chat.model=qwen/qwen3.6-35b-a3b", "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.chat.max-tokens=2222")
                    .run(application -> {
                        ObjectNode result = application.getBean(LlmClient.class).complete("plan",
                                mapper.createObjectNode().put("external_data", "недоверенный текст"));
                        assertThat(result.path("search_queries").get(0).asString()).isEqualTo("ставка");
                    });
            assertThat(path.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            JsonNode request = mapper.readTree(body.get());
            assertThat(request.path("model").asString()).isEqualTo("qwen/qwen3.6-35b-a3b");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(2222);
            assertThat(request.path("messages").get(0).path("content").asString()).contains("данные", "инструкции");
            assertThat(request.path("response_format").path("type").asString()).isEqualTo("json_object");
        } finally { server.stop(0); }
    }

    @Test
    void disabledChatUsesStubEvenWithRemoteSettingsAndEmptyKey() {
        context.withPropertyValues("spring.ai.model.chat=none", "spring.ai.openai.api-key=",
                "spring.ai.openai.base-url=http://127.0.0.1:1/v1")
                .run(application -> {
                    assertThat(application).doesNotHaveBean(OpenAiChatModel.class);
                    ObjectNode result = application.getBean(LlmClient.class).complete("plan", mapper.createObjectNode());
                    assertThat(result.path("search_queries").isArray()).isTrue();
                });
    }

    @Test
    void stalledLlmCallTimesOutWithoutRetry() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
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
            context.withPropertyValues("spring.ai.model.chat=openai", "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.timeout=100ms",
                    "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(application -> {
                        long started = System.nanoTime();
                        assertThatThrownBy(() -> application.getBean(LlmClient.class)
                                .complete("plan", mapper.createObjectNode()))
                                .isInstanceOfSatisfying(ResponseStatusException.class,
                                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
                        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                                .isLessThan(java.time.Duration.ofSeconds(3));
                    });
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void budgetErrorIsNotRetriedAndDoesNotExposeProviderBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "Budget has been exceeded: secret-provider-details".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            context.withPropertyValues("spring.ai.model.chat=openai", "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(application -> assertThatThrownBy(() -> application.getBean(LlmClient.class)
                            .complete("plan", mapper.createObjectNode()))
                            .isInstanceOf(ResponseStatusException.class)
                            .hasMessageNotContaining("secret-provider-details").hasMessageNotContaining("test-key")
                            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                                    .isEqualTo(503)));
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
