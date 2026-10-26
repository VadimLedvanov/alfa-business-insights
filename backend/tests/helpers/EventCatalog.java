package alpha.business.insight.core.qa.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Читает неизменённый PR65 каталог и строит безопасные payload синтетических HTTP-фикстур. */
public final class EventCatalog {
    public static final String SYNTHETIC_MARKER = "[SYNTHETIC TEST FIXTURE]";
    private static final String FIXTURE = "backend/tests/fixtures/events/pr65-events.json";

    private EventCatalog() {}

    /** Загружает проверяемые исходные поля событий из скопированного PR65 JSON. */
    public static List<CatalogEvent> load() throws IOException {
        Path fixture = findFixture();
        JsonNode root = new ObjectMapper().readTree(Files.readString(fixture));
        if (!root.path("synthetic").isBoolean() || !root.path("synthetic").asBoolean()) {
            throw new IllegalStateException("PR65 event catalog must be explicitly synthetic");
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) {
            throw new IllegalStateException("PR65 event catalog has no events array");
        }
        List<CatalogEvent> result = new ArrayList<>();
        for (JsonNode item : events) {
            JsonNode event = item.path("event");
            result.add(
                    new CatalogEvent(
                            requiredText(item, "id"),
                            requiredText(event, "title"),
                            requiredText(event, "sourceType"),
                            requiredText(event, "sourceRef")));
        }
        return List.copyOf(result);
    }

    /** Добавляет синтетическую маркировку, сохраняя URL sourceRef для записей типа URL. */
    public static EventPayload payload(CatalogEvent event, String runPrefix) {
        String title = SYNTHETIC_MARKER + " " + runPrefix + " " + event.id() + " " + event.title();
        String sourceRef = event.sourceRef();
        if ("TEXT".equals(event.sourceType())) {
            sourceRef = SYNTHETIC_MARKER + " " + runPrefix + " " + event.id() + "\n" + sourceRef;
        }
        return new EventPayload(title, event.sourceType(), sourceRef);
    }

    /** Ищет каталог в репозитории при запуске Maven из любой его поддиректории. */
    private static Path findFixture() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(FIXTURE);
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Cannot find backend/tests/fixtures/events/pr65-events.json");
    }

    /** Возвращает непустое текстовое поле каталога или завершает тест с ошибкой фикстуры. */
    private static String requiredText(JsonNode node, String field) {
        JsonNode valueNode = node.path(field);
        if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
            throw new IllegalStateException("PR65 event catalog has a blank " + field);
        }
        return valueNode.asText();
    }

    public record CatalogEvent(String id, String title, String sourceType, String sourceRef) {}

    public record EventPayload(String title, String sourceType, String sourceRef) {}
}
