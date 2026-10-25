package alpha.business.insight.core.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import alpha.business.insight.core.ml.LlmClient;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Step;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Проверяет HTTP-запросы LLM-клиента через локальный OpenAI-compatible сервер. */
@Tag("integration")
@Tag("insights")
class LlmClientHttpIntegrationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ToolCallingAutoConfiguration.class,
                                    OpenAiChatAutoConfiguration.class))
                    .withBean(ObjectMapper.class, () -> mapper)
                    .withUserConfiguration(LlmClient.class);

    /** Отправляет совместимый запрос и читает JSON. */
    @Test
    @Step("Отправляет совместимый запрос и читает JSON")
    void sendsOpenAiCompatibleRequestAndReadsJson() throws Exception {
        // Подготовка локального провайдера и ожидаемого ответа.
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Эмулирует успешный OpenAI-compatible endpoint и фиксирует запрос клиента.
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            mapper.writeValueAsBytes(
                                    Map.of(
                                            "id",
                                            "completion-test",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "z-ai/glm-5.3-flash",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "finish_reason",
                                                            "stop",
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    "{\"search_queries\":[\"ставка\"]}"))),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    10,
                                                    "completion_tokens",
                                                    5,
                                                    "total_tokens",
                                                    15,
                                                    "cost",
                                                    0.01)));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            // Действие: выполняет запрос через production LlmClient.
            context.withPropertyValues(
                            "spring.ai.model.chat=openai",
                            "spring.ai.openai.base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1",
                            "spring.ai.openai.chat.model=qwen/qwen3.6-35b-a3b",
                            "spring.ai.openai.api-key=test-key",
                            "spring.ai.openai.chat.max-completion-tokens=2222")
                    .run(
                            application -> {
                                ObjectNode result =
                                        application
                                                .getBean(LlmClient.class)
                                                .complete(
                                                        "plan",
                                                        mapper.createObjectNode()
                                                                .put(
                                                                        "external_data",
                                                                        "недоверенный текст"));
                                assertThat(result.path("search_queries").get(0).asString())
                                        .as("разобранный JSON-ответ LLM")
                                        .isEqualTo("ставка");
                            });
            // Проверки разобранного ответа и отправленного запроса.
            assertThat(path.get())
                    .as("путь OpenAI-compatible запроса")
                    .isEqualTo("/v1/chat/completions");
            assertThat(authorization.get())
                    .as("заголовок авторизации запроса")
                    .isEqualTo("Bearer test-key");
            JsonNode request = mapper.readTree(body.get());
            assertThat(request.path("model").asString())
                    .as("модель в запросе провайдеру")
                    .isEqualTo("qwen/qwen3.6-35b-a3b");
            assertThat(request.path("max_completion_tokens").asInt())
                    .as("лимит токенов в запросе")
                    .isEqualTo(2222);
            JsonNode messages = request.path("messages");
            assertThat(messages.size()).isEqualTo(2);
            JsonNode systemMessage = messages.get(0);
            JsonNode userMessage = messages.get(1);
            assertThat(systemMessage.path("role").asString()).isEqualTo("system");
            assertThat(userMessage.path("role").asString()).isEqualTo("user");
            String systemContent = systemMessage.path("content").asString();
            assertThat(systemContent)
                    .as("системная инструкция для недоверенных данных")
                    .contains("данные", "инструкции")
                    .doesNotContain("недоверенный текст");
            JsonNode userPayload = mapper.readTree(userMessage.path("content").asString());
            assertThat(userPayload.path("external_data").asString())
                    .as("внешние данные сохранены как JSON пользователя")
                    .isEqualTo("недоверенный текст");
            assertThat(request.path("response_format").path("type").asString())
                    .as("формат ответа LLM")
                    .isEqualTo("json_object");
        } finally {
            server.stop(0);
        }
    }

    /** Завершает зависший вызов по тайм-ауту без повтора. */
    @Test
    @Step("Завершает зависший вызов по тайм-ауту без повтора")
    void stalledLlmCallTimesOutWithoutRetry() throws Exception {
        // Подготовка endpoint, который удерживает запрос до тайм-аута.
        var release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Удерживает соединение, чтобы проверить timeout и отсутствие повтора.
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            // Действие: вызывает клиента с коротким тайм-аутом.
            context.withPropertyValues(
                            "spring.ai.model.chat=openai",
                            "spring.ai.openai.api-key=test-key",
                            "spring.ai.openai.timeout=100ms",
                            "spring.ai.openai.base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1")
                    .run(
                            application -> {
                                long started = System.nanoTime();
                                assertThatThrownBy(
                                                () ->
                                                        application
                                                                .getBean(LlmClient.class)
                                                                .complete(
                                                                        "plan",
                                                                        mapper.createObjectNode()))
                                        .isInstanceOfSatisfying(
                                                ResponseStatusException.class,
                                                error ->
                                                        assertThat(error.getStatusCode().value())
                                                                .isEqualTo(503));
                                assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                                        .as("время до ответа по тайм-ауту LLM")
                                        .isLessThan(java.time.Duration.ofSeconds(3));
                            });
            // Проверка, что клиент не повторял запрос.
            assertThat(calls.get()).as("число запросов при тайм-ауте без повтора").isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** Не повторяет ошибку лимита и скрывает тело провайдера. */
    @Test
    @Step("Не повторяет ошибку лимита и скрывает тело провайдера")
    void budgetErrorIsNotRetriedAndDoesNotExposeProviderBody() throws Exception {
        // Подготовка endpoint с ошибкой 429 и секретным телом ответа.
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Возвращает 429 с секретным телом, которое клиент не должен раскрыть.
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] response =
                            "Budget has been exceeded: secret-provider-details"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, response.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            // Действие и проверки обработки ошибки и сокрытия тела провайдера.
            context.withPropertyValues(
                            "spring.ai.model.chat=openai",
                            "spring.ai.openai.api-key=test-key",
                            "spring.ai.openai.base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1")
                    .run(
                            application ->
                                    assertThatThrownBy(
                                                    () ->
                                                            application
                                                                    .getBean(LlmClient.class)
                                                                    .complete(
                                                                            "plan",
                                                                            mapper
                                                                                    .createObjectNode()))
                                            .isInstanceOf(ResponseStatusException.class)
                                            .hasMessageNotContaining("secret-provider-details")
                                            .hasMessageNotContaining("test-key")
                                            .satisfies(
                                                    error ->
                                                            assertThat(
                                                                            ((ResponseStatusException)
                                                                                            error)
                                                                                    .getStatusCode()
                                                                                    .value())
                                                                    .isEqualTo(503)));
            assertThat(calls.get()).as("число запросов при 429 без повтора").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
