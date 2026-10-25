package alpha.business.insight.core.qa.support;

import java.util.List;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** Регистрирует тестовый обработчик MockMvc без правок тестовых классов. */
public final class MockMvcEvidenceContextCustomizerFactory implements ContextCustomizerFactory {
    /** Включает настройку для тестовых классов с AutoConfigureMockMvc. */
    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass,
            List<org.springframework.test.context.ContextConfigurationAttributes>
                    configAttributes) {
        return AnnotatedElementUtils.hasAnnotation(testClass, AutoConfigureMockMvc.class)
                ? new MockMvcEvidenceContextCustomizer()
                : null;
    }

    /** Добавляет test-only сборщик результатов в Spring-контекст. */
    private record MockMvcEvidenceContextCustomizer() implements ContextCustomizer {
        /** Регистрирует сборщик evidence в контексте теста. */
        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            String beanName = "qaMockMvcEvidenceBuilderCustomizer";
            if (!context.getBeanFactory().containsSingleton(beanName)
                    && !context.getBeanFactory().containsBeanDefinition(beanName)) {
                context.getBeanFactory()
                        .registerSingleton(beanName, new MockMvcEvidenceBuilderCustomizer());
            }
        }
    }
}
