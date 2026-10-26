package alpha.business.insight.core.qa.support;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** HTTP-контур событий с fail-closed защитой от внешней LLM и настоящего ML. */
public abstract class OfflineEventsE2eTest extends RealStackE2eTest {
    @Autowired private ApplicationContext applicationContext;
    @Autowired private Environment environment;

    /** Переопределяет настройки модели динамическими test-only свойствами. */
    @DynamicPropertySource
    static void offlineEventProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.ai.model.chat", () -> "none");
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:1");
        properties.add("spring.ai.openai.api-key", () -> "local-test-only");
        properties.add("ml.stub", () -> "true");
    }

    /** Проверяет до тестового HTTP-запроса, что используются только локальные заглушки. */
    @org.junit.jupiter.api.BeforeEach
    protected void requireOfflineModels() {
        assertThat(environment.getProperty("spring.ai.model.chat"))
                .as("чат должен быть отключён до любых HTTP-запросов")
                .isEqualTo("none");
        assertThat(environment.getProperty("ml.stub"))
                .as("ML должен работать в режиме заглушки")
                .isEqualTo("true");
        assertThat(applicationContext.getBeansOfType(ChatModel.class))
                .as("в контексте не должно быть внешней языковой модели")
                .isEmpty();
        assertThat(applicationContext.getBean(DocumentAnalyzer.class))
                .as("к HTTP-тестам подключена локальная ML-заглушка")
                .isInstanceOf(StubDocumentAnalyzer.class);
    }
}
