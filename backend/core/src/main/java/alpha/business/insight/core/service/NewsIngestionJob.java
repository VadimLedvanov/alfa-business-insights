package alpha.business.insight.core.service;

import alpha.business.insight.core.ml.NewsCollector;
import alpha.business.insight.core.ml.NewsCollector.NewsMaterial;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import alpha.business.insight.core.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Периодически забирает новости из ML и сохраняет новые как события READY для ленты и инсайтов.
 */
@Component
@ConditionalOnProperty(name = "news.collect.enabled", havingValue = "true")
public class NewsIngestionJob {
    private static final Logger log = LoggerFactory.getLogger(NewsIngestionJob.class);
    private final ObjectProvider<NewsCollector> collector;
    private final EventRepository events;
    private final int hours;

    public NewsIngestionJob(ObjectProvider<NewsCollector> collector, EventRepository events,
                            @Value("${news.collect.hours:24}") int hours) {
        this.collector = collector;
        this.events = events;
        this.hours = hours;
    }

    /** fixedDelay: следующий сбор начинается после окончания предыдущего, запуски не накладываются. */
    @Scheduled(fixedDelayString = "${news.collect.interval:PT15M}",
            initialDelayString = "${news.collect.initial-delay:PT1M}")
    public void collect() {
        NewsCollector client = collector.getIfAvailable();
        if (client == null) {
            log.warn("News collection skipped: ML client disabled (ml.stub=true)");
            return;
        }
        long startedAt = System.nanoTime();
        NewsCollector.NewsBatch batch;
        try {
            batch = client.collect(hours);
        } catch (RuntimeException exception) {
            log.error("News collection failed errorType={} message={}", exception.getClass().getSimpleName(),
                    exception.getMessage());
            return;
        }
        List<NewsMaterial> materials = batch == null || batch.materials() == null ? List.of() : batch.materials();
        int saved = saveNew(materials);
        log.info("News collection completed received={} saved={} durationMs={} stats={}", materials.size(), saved,
                (System.nanoTime() - startedAt) / 1_000_000, batch == null ? null : batch.stats());
    }

    int saveNew(List<NewsMaterial> materials) {
        List<NewsMaterial> valid = materials.stream()
                .filter(m -> notBlank(m.externalId()) && notBlank(m.title()) && notBlank(m.text()))
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }
        Set<String> existing = events.findExistingExternalIds(valid.stream().map(NewsMaterial::externalId).toList());
        Map<String, Event> fresh = new LinkedHashMap<>();
        for (NewsMaterial m : valid) {
            if (existing.contains(m.externalId())) {
                continue;
            }
            fresh.putIfAbsent(m.externalId(), Event.builder()
                    .title(m.title().strip())
                    .sourceType(EventSourceType.TEXT)
                    .sourceRef(m.title().strip() + "\n\n" + m.text().strip()
                            + (notBlank(m.url()) ? "\n\nИсточник: " + m.url() : ""))
                    .status(EventStatus.READY)
                    .externalId(m.externalId())
                    .sourceUrl(m.url())
                    .sourceName(m.source())
                    .publishedAt(m.publishedAt() == null ? null : m.publishedAt().toInstant())
                    .build());
        }
        events.saveAll(fresh.values());
        return fresh.size();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}