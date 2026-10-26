package alpha.business.insight.core.config;

import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.EventRanker;
import alpha.business.insight.core.ml.NewsCollector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Включает OpenFeign-клиент ML только при ml.stub=false, исключая одновременное подключение заглушки.
 */
@Configuration
@ConditionalOnProperty(name = "ml.stub", havingValue = "false")
@EnableFeignClients(clients = {DocumentAnalyzer.class, NewsCollector.class, EventRanker.class})
public class MlClientConfig {}