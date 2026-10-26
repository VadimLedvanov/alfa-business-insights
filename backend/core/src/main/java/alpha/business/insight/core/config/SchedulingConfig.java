package alpha.business.insight.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает фоновые задачи только при news.collect.enabled=true, чтобы тесты не запускали сбор новостей.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "news.collect.enabled", havingValue = "true")
public class SchedulingConfig {}