package alpha.business.insight.core.ml;

import alpha.business.insight.core.dto.CompanyContext;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP-клиент ML для персонального ранжирования событий по контексту компании.
 */
@FeignClient(name = "event-ranker", url = "${ml.url:http://localhost:8000}")
public interface EventRanker {
    /**
     * Возвращает отобранные события по убыванию значимости для компании с объяснением.
     *
     * @param request контекст компании, кандидаты и размер топа
     * @return события с оценкой, эффектом (risk, opportunity, mixed, unknown) и причинами
     */
    @PostMapping(value = "/rank", consumes = MediaType.APPLICATION_JSON_VALUE)
    RankResponse rank(@RequestBody RankRequest request);

    record RankRequest(CompanyContext company, List<Candidate> candidates, int topK, String role) {}

    record Candidate(UUID id, String title, String text, Instant publishedAt) {}

    record RankResponse(String modelVersion, List<RankedItem> items) {}

    record RankedItem(UUID id, double score, String effect, List<String> why) {}
}