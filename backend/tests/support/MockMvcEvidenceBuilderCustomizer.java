package alpha.business.insight.core.qa.support;

import alpha.business.insight.core.qa.connectors.MockMvcEvidenceResultHandler;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;

/** Добавляет сбор результатов к экземплярам MockMvc из тестовой автоконфигурации Spring Boot. */
public final class MockMvcEvidenceBuilderCustomizer implements MockMvcBuilderCustomizer {
    /** Добавляет обработчик фактического результата к экземпляру MockMvc. */
    @Override
    public void customize(org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder<?> builder) {
        builder.alwaysDo(new MockMvcEvidenceResultHandler());
    }
}
