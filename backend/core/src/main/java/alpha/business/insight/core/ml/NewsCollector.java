package alpha.business.insight.core.ml;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * HTTP-клиент ML для сбора новостей из RSS; новые материалы сохраняет Java.
 */
@FeignClient(name = "news-collector", url = "${ml.url:http://localhost:8000}")
public interface NewsCollector {
    /**
     * Запускает сбор новостей в ML и возвращает материалы, собранные за последние часы.
     *
     * @param hours окно в часах, за которое ML возвращает собранные материалы
     * @return статистика сбора и материалы; повторы отсекает Java по externalId
     */
    @PostMapping("/news/collect")
    NewsBatch collect(@RequestParam("hours") int hours);

    record NewsBatch(Map<String, Object> stats, List<NewsMaterial> materials) {}

    record NewsMaterial(String externalId, String source, Integer sourceTier, String url, String title,
                        String text, OffsetDateTime publishedAt) {}
}