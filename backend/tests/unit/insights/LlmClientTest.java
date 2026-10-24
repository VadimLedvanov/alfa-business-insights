package alpha.business.insight.core.insight;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.ml.LlmClient;
import io.qameta.allure.Step;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Проверяет конфигурацию и обработку ответов LLM-клиента. */
@Tag("unit")
@Tag("insights")
class LlmClientTest {
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

    /** Использует заглушку при отключённой модели. */
    @Test
    @Step("Использует заглушку при отключённой модели")
    void disabledChatUsesStubWithoutCreatingRemoteModel() {
        context.withPropertyValues("spring.ai.model.chat=none")
                .run(
                        application -> {
                            assertThat(application)
                                    .as("отключённая модель не создаёт OpenAI клиента")
                                    .doesNotHaveBean(OpenAiChatModel.class);
                            ObjectNode result =
                                    application
                                            .getBean(LlmClient.class)
                                            .complete("draft", mapper.createObjectNode());
                            assertThat(result.path("missing_data").toString()).contains("LLM STUB");
                        });
    }

    /** Не создаёт удалённую модель с пустым ключом. */
    @Test
    @Step("Не создаёт удалённую модель с пустым ключом")
    void disabledChatUsesStubEvenWithRemoteSettingsAndEmptyKey() {
        context.withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.openai.api-key=",
                        "spring.ai.openai.base-url=http://127.0.0.1:1/v1")
                .run(
                        application -> {
                            assertThat(application)
                                    .as("пустой ключ при отключённой модели")
                                    .doesNotHaveBean(OpenAiChatModel.class);
                            ObjectNode result =
                                    application
                                            .getBean(LlmClient.class)
                                            .complete("plan", mapper.createObjectNode());
                            assertThat(result.path("search_queries").isArray())
                                    .as("поисковые запросы в ответе заглушки")
                                    .isTrue();
                        });
    }
}
