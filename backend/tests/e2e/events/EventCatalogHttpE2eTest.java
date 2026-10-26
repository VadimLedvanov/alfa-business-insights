package alpha.business.insight.core.qa.events;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.EventCatalog;
import alpha.business.insight.core.qa.helpers.TestUsers;
import alpha.business.insight.core.qa.support.OfflineEventsE2eTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** Проверяет сквозное создание и чтение синтетического каталога событий через HTTP. */
@Epic("Backend")
@Feature("Каталог событий")
@Tag("e2e")
@Tag("events")
class EventCatalogHttpE2eTest extends OfflineEventsE2eTest {
    private final String runPrefix = "PR65-" + UUID.randomUUID();
    private final String titlePrefix = EventCatalog.SYNTHETIC_MARKER + " " + runPrefix + " ";
    private final List<UUID> createdEventIds = new ArrayList<>();

    @Autowired private ObjectMapper mapper;

    /** Удаляет только события с выданными этим сценарием UUID или уникальным title prefix. */
    @AfterEach
    void removeCreatedEvents() {
        Set<UUID> cleanupIds = new HashSet<>(createdEventIds);
        cleanupIds.addAll(
                jdbc.query(
                        "SELECT id FROM events WHERE title LIKE ?",
                        (result, row) -> result.getObject(1, UUID.class),
                        titlePrefix + "%"));
        for (UUID eventId : cleanupIds) {
            jdbc.update("DELETE FROM events WHERE id = ?", eventId);
        }
        createdEventIds.clear();
    }

    /** Создаёт все 20 каталоговых событий, сверяет round-trip и чтение директором. */
    @Test
    @DisplayName("TestE2ECatalogEventsCreateReadAndList")
    void TestE2ECatalogEventsCreateReadAndList() throws Exception {
        List<EventCatalog.CatalogEvent> catalog =
                Allure.step(
                        "1. Загрузить и проверить синтетический каталог PR65",
                        () -> {
                            List<EventCatalog.CatalogEvent> loaded = EventCatalog.load();
                            assertThat(loaded).hasSize(20);
                            assertThat(loaded)
                                    .extracting(EventCatalog.CatalogEvent::id)
                                    .doesNotHaveDuplicates();
                            assertThat(loaded)
                                    .extracting(EventCatalog.CatalogEvent::sourceType)
                                    .contains("URL", "TEXT");
                            assertThat(
                                            loaded.stream()
                                                    .filter(
                                                            event ->
                                                                    "URL"
                                                                            .equals(
                                                                                    event
                                                                                            .sourceType()))
                                                    .map(EventCatalog.CatalogEvent::id)
                                                    .toList())
                                    .containsExactly("E20");
                            return loaded;
                        });

        String adminToken =
                Allure.step(
                        "2. Получить токен системного администратора",
                        () ->
                                keycloak.accessToken(
                                        TestUsers.Actor.SYSTEM_ADMIN.username(),
                                        TestUsers.Actor.SYSTEM_ADMIN.password()));
        List<TestUsers.Actor> readers =
                List.of(
                        TestUsers.Actor.CREDIT_DIRECTOR,
                        TestUsers.Actor.TRADE_DIRECTOR,
                        TestUsers.Actor.UNASSIGNED_DIRECTOR);
        List<String> directorTokens =
                Allure.step(
                        "3. Получить JWT трёх директоров для чтения событий",
                        () -> {
                            List<String> tokens = new ArrayList<>();
                            for (TestUsers.Actor actor : readers) {
                                tokens.add(
                                        keycloak.accessToken(actor.username(), actor.password()));
                            }
                            return List.copyOf(tokens);
                        });

        List<CreatedEvent> created =
                Allure.step(
                        "4. Создать и прочитать каждое событие каталога через HTTP",
                        () -> {
                            List<CreatedEvent> result = new ArrayList<>();
                            for (int index = 0; index < catalog.size(); index++) {
                                EventCatalog.CatalogEvent source = catalog.get(index);
                                result.add(
                                        Allure.step(
                                                "4."
                                                        + (index + 1)
                                                        + ". Создать и прочитать "
                                                        + source.id(),
                                                () -> {
                                                    EventCatalog.EventPayload expected =
                                                            EventCatalog.payload(source, runPrefix);
                                                    String body =
                                                            mapper.writeValueAsString(
                                                                    Map.of(
                                                                            "title",
                                                                                    expected
                                                                                            .title(),
                                                                            "sourceType",
                                                                                    expected
                                                                                            .sourceType(),
                                                                            "sourceRef",
                                                                                    expected
                                                                                            .sourceRef()));
                                                    var post =
                                                            backend.postJson(
                                                                    "/events", adminToken, body);
                                                    assertThat(post.statusCode())
                                                            .as(
                                                                    "POST /events for %s: %s",
                                                                    source.id(), post.asString())
                                                            .isEqualTo(201);
                                                    JsonNode createdBody = json(post);
                                                    UUID id =
                                                            UUID.fromString(
                                                                    createdBody
                                                                            .path("id")
                                                                            .asText());
                                                    createdEventIds.add(id);
                                                    assertEventFields(
                                                            post.statusCode(),
                                                            201,
                                                            createdBody,
                                                            id,
                                                            expected);
                                                    assertThat(createdBody.path("status").asText())
                                                            .isEqualTo("PROCESSING");
                                                    assertThat(post.getHeader("Location"))
                                                            .as(
                                                                    "Location for event %s",
                                                                    source.id())
                                                            .endsWith("/api/events/" + id);

                                                    var read =
                                                            backend.get(
                                                                    "/events/" + id, adminToken);
                                                    assertEventFields(
                                                            read.statusCode(),
                                                            200,
                                                            json(read),
                                                            id,
                                                            expected);
                                                    assertThat(json(read).path("status").asText())
                                                            .isEqualTo("PROCESSING");
                                                    return new CreatedEvent(id, expected);
                                                }));
                            }
                            return List.copyOf(result);
                        });

        Allure.step(
                "5. Проверить пагинацию, фильтры и чтение событий директорами",
                () -> {
                    String encodedTitle = URLEncoder.encode(titlePrefix, StandardCharsets.UTF_8);
                    List<JsonNode> pages = new ArrayList<>();
                    for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
                        int currentPage = pageNumber;
                        JsonNode page =
                                Allure.step(
                                        "5."
                                                + (pageNumber + 1)
                                                + ". Прочитать страницу "
                                                + pageNumber,
                                        () -> {
                                            var response =
                                                    backend.get(
                                                            "/events?title="
                                                                    + encodedTitle
                                                                    + "&status=PROCESSING&page="
                                                                    + currentPage
                                                                    + "&size=7",
                                                            directorTokens.getFirst());
                                            assertThat(response.statusCode()).isEqualTo(200);
                                            JsonNode result = json(response);
                                            assertThat(result.path("totalElements").asInt())
                                                    .isEqualTo(catalog.size());
                                            assertThat(result.path("totalPages").asInt())
                                                    .isEqualTo(3);
                                            assertThat(result.path("number").asInt())
                                                    .isEqualTo(currentPage);
                                            assertThat(result.path("size").asInt()).isEqualTo(7);
                                            assertThat(result.path("content").isArray()).isTrue();
                                            assertThat(result.path("content").size())
                                                    .isEqualTo(currentPage < 2 ? 7 : 6);
                                            return result;
                                        });
                        pages.add(page);
                    }
                    assertThat(pages.stream().map(page -> page.path("content").size()).toList())
                            .containsExactly(7, 7, 6);
                    List<String> allListedIds =
                            pages.stream()
                                    .flatMap(
                                            page ->
                                                    page
                                                            .path("content")
                                                            .findValuesAsText("id")
                                                            .stream())
                                    .toList();
                    Set<String> listedIds = new HashSet<>(allListedIds);
                    assertThat(allListedIds).hasSize(catalog.size());
                    assertThat(listedIds).hasSize(catalog.size());
                    assertThat(listedIds)
                            .containsExactlyInAnyOrderElementsOf(
                                    created.stream().map(event -> event.id().toString()).toList());
                    for (JsonNode page : pages) {
                        assertThat(page.path("content").findValuesAsText("status"))
                                .containsOnly("PROCESSING");
                    }

                    Allure.step(
                            "5.4. Фильтр URL оставляет только запись E20",
                            () -> {
                                var urlOnly =
                                        backend.get(
                                                "/events?title="
                                                        + encodedTitle
                                                        + "&status=PROCESSING&sourceType=URL&page=0&size=100",
                                                directorTokens.getFirst());
                                assertThat(urlOnly.statusCode()).isEqualTo(200);
                                assertThat(json(urlOnly).path("content").isArray()).isTrue();
                                List<String> urlIds =
                                        json(urlOnly).path("content").findValuesAsText("id");
                                assertThat(urlIds)
                                        .containsExactly(
                                                created.stream()
                                                        .filter(
                                                                event ->
                                                                        "URL"
                                                                                .equals(
                                                                                        event.payload()
                                                                                                .sourceType()))
                                                        .map(event -> event.id().toString())
                                                        .findFirst()
                                                        .orElseThrow());
                            });

                    Allure.step(
                            "5.5. Фильтр READY не включает созданные PROCESSING-события",
                            () -> {
                                var notReady =
                                        backend.get(
                                                "/events?title="
                                                        + encodedTitle
                                                        + "&status=READY&page=0&size=100",
                                                directorTokens.getFirst());
                                assertThat(notReady.statusCode()).isEqualTo(200);
                                assertThat(json(notReady).path("content").isArray()).isTrue();
                                assertThat(json(notReady).path("content").size()).isZero();
                            });

                    for (int readerIndex = 1; readerIndex < readers.size(); readerIndex++) {
                        int currentReader = readerIndex;
                        Allure.step(
                                "5.6. Проверить полный список для "
                                        + readers.get(currentReader).username(),
                                () -> {
                                    var response =
                                            backend.get(
                                                    "/events?title="
                                                            + encodedTitle
                                                            + "&status=PROCESSING&page=0&size=100",
                                                    directorTokens.get(currentReader));
                                    assertThat(response.statusCode()).isEqualTo(200);
                                    JsonNode result = json(response);
                                    assertThat(result.path("totalElements").asInt())
                                            .isEqualTo(catalog.size());
                                    assertThat(result.path("content").isArray()).isTrue();
                                    assertThat(result.path("content").findValuesAsText("id"))
                                            .containsExactlyInAnyOrderElementsOf(
                                                    created.stream()
                                                            .map(event -> event.id().toString())
                                                            .toList());
                                });
                    }

                    for (int readerIndex = 0; readerIndex < readers.size(); readerIndex++) {
                        String directorToken = directorTokens.get(readerIndex);
                        for (int eventIndex = 0; eventIndex < created.size(); eventIndex++) {
                            CreatedEvent event = created.get(eventIndex);
                            Allure.step(
                                    "5.7. Прочитать "
                                            + catalog.get(eventIndex).id()
                                            + " от имени "
                                            + readers.get(readerIndex).username(),
                                    () -> {
                                        var read =
                                                backend.get("/events/" + event.id(), directorToken);
                                        assertEventFields(
                                                read.statusCode(),
                                                200,
                                                json(read),
                                                event.id(),
                                                event.payload());
                                        assertThat(json(read).path("status").asText())
                                                .isEqualTo("PROCESSING");
                                    });
                        }
                    }

                    Long savedRows =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM events WHERE title LIKE ?",
                                    Long.class,
                                    titlePrefix + "%");
                    assertThat(savedRows).isEqualTo(catalog.size());
                });
    }

    /** Проверяет настоящий JWT трёх директоров и отсутствие записей после запрета создания. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("companyDirectors")
    @DisplayName("TestE2ECompanyDirectorsCannotCreateCatalogEvents")
    void TestE2ECompanyDirectorsCannotCreateCatalogEvents(TestUsers.Actor actor) throws Exception {
        EventCatalog.CatalogEvent source = EventCatalog.load().getFirst();
        EventCatalog.EventPayload expected = EventCatalog.payload(source, runPrefix);
        String body =
                mapper.writeValueAsString(
                        Map.of(
                                "title", expected.title(),
                                "sourceType", expected.sourceType(),
                                "sourceRef", expected.sourceRef()));
        Allure.step(
                "1. Проверить запрет создания событий для директора " + actor.username(),
                () -> {
                    Long rowsBefore = scopedEventCount();
                    assertThat(rowsBefore).isZero();
                    String token =
                            Allure.step(
                                    "Получить JWT " + actor.username(),
                                    () -> keycloak.accessToken(actor.username(), actor.password()));
                    var identity = backend.get("/auth/me", token);
                    assertThat(identity.statusCode()).isEqualTo(200);
                    assertThat(json(identity).path("roles").toString())
                            .contains("COMPANY_DIRECTOR");

                    var response = backend.postJson("/events", token, body);
                    assertThat(response.statusCode())
                            .as("POST /events as %s: %s", actor.username(), response.asString())
                            .isEqualTo(403);
                    assertThat(scopedEventCount())
                            .as("no event row is created for %s", actor.username())
                            .isEqualTo(rowsBefore);
                });
    }

    /** Возвращает три тестовые роли директора, которым запрещено создавать события. */
    private static Stream<TestUsers.Actor> companyDirectors() {
        return Stream.of(
                TestUsers.Actor.CREDIT_DIRECTOR,
                TestUsers.Actor.TRADE_DIRECTOR,
                TestUsers.Actor.UNASSIGNED_DIRECTOR);
    }

    /** Сверяет точные поля ответа API при создании или чтении события. */
    private static void assertEventFields(
            int actualHttpStatus,
            int expectedHttpStatus,
            JsonNode actual,
            UUID id,
            EventCatalog.EventPayload expected) {
        assertThat(actualHttpStatus).isEqualTo(expectedHttpStatus);
        assertThat(actual.path("id").asText()).isEqualTo(id.toString());
        assertThat(actual.path("title").asText()).isEqualTo(expected.title());
        assertThat(actual.path("sourceType").asText()).isEqualTo(expected.sourceType());
        assertThat(actual.path("sourceRef").asText()).isEqualTo(expected.sourceRef());
        assertThat(actual.path("createdAt").asText()).isNotBlank();
        assertThat(actual.path("materialsVersion").asLong()).isEqualTo(1);
    }

    /** Считает только строки событий текущего запуска по уникальной синтетической метке. */
    private long scopedEventCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE title LIKE ?", Long.class, titlePrefix + "%");
    }

    private record CreatedEvent(UUID id, EventCatalog.EventPayload payload) {}
}
