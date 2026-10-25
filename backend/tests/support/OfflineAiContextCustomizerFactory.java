package alpha.business.insight.core.qa.support;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** Отключает удалённый чат во всех Spring Test-контекстах. */
public final class OfflineAiContextCustomizerFactory implements ContextCustomizerFactory {
    private static final ContextCustomizer OFFLINE_AI = OfflineAiContextCustomizer.INSTANCE;

    /** Применяет общую безопасную конфигурацию к каждому Spring Test-контексту. */
    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return OFFLINE_AI;
    }

    /** Добавляет свойства до создания bean-ов и стабилен для кэша тестовых контекстов. */
    private enum OfflineAiContextCustomizer implements ContextCustomizer {
        INSTANCE;

        private static final Map<String, Object> PROPERTIES =
                Map.of(
                        "spring.ai.model.chat", "none",
                        "spring.ai.openai.base-url", "http://127.0.0.1:1",
                        "spring.ai.openai.api-key", "local-test-only");

        /** Ставит test-only свойства первыми в цепочке разрешения конфигурации. */
        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("qaOfflineAiProperties", PROPERTIES));
        }
    }
}
