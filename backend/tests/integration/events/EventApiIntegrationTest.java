package alpha.business.insight.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.qameta.allure.Step;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Проверяет создание, чтение и права доступа к событиям через API. */
@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
            "spring.ai.model.chat=none"
        })
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@Tag("integration")
@Tag("events")
class EventApiIntegrationTest {
    private static final String REQUEST =
            """
            {"title":"Снижение ставки","sourceType":"TEXT","sourceRef":"Ставка снижена"}
            """;

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    /** Подключает контейнер PostgreSQL к Spring-контексту. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;

    /** Создаёт событие и проверяет сохранение. */
    @ParameterizedTest
    @CsvSource({
        "TEXT,Ставка снижена,Снижение ставки",
        "URL,https://example.org/news,Снижение ставки",
        "PDF,/api/documents/report/file,Снижение ставки",
        "TEXT,Текст,Я"
    })
    @Step("Создаёт событие и проверяет сохранение")
    void givenAdmin_whenCreateEvent_thenPersistAndReturnReadableEvent(
            String sourceType, String sourceRef, String title) throws Exception {
        // Подготовка
        Instant before = Instant.now();
        String request =
                mapper.writeValueAsString(
                        Map.of("title", title, "sourceType", sourceType, "sourceRef", sourceRef));
        // Действие
        var result =
                request()
                        .postProcessors(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .when()
                        .post("/api/events")
                        .then()
                        .statusCode(201)
                        .body("title", equalTo(title))
                        .body("sourceType", equalTo(sourceType))
                        .body("sourceRef", equalTo(sourceRef))
                        .body("status", equalTo("PROCESSING"))
                        .body("materialsVersion", equalTo(1))
                        .header("Location", matchesPattern("/api/events/[0-9a-fA-F-]{36}"))
                        .extract()
                        .response();
        JsonNode response = mapper.readTree(result.asString());
        UUID id = UUID.fromString(response.get("id").asString());
        Instant createdAt = Instant.parse(response.get("createdAt").asString());

        // Проверки
        assertThat(createdAt).isBetween(before, Instant.now());
        String location = "/api/events/" + id;
        result.then().header("Location", equalTo(location));

        entities.flush();
        entities.clear();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT title, source_type, source_ref, status, materials_version FROM events WHERE id = ?",
                                id))
                .containsEntry("title", title)
                .containsEntry("source_type", sourceType)
                .containsEntry("source_ref", sourceRef)
                .containsEntry("status", "PROCESSING")
                .containsEntry("materials_version", 1L);
        request()
                .postProcessors(role("COMPANY_DIRECTOR"))
                .when()
                .get(location)
                .then()
                .statusCode(200)
                .body("id", equalTo(id.toString()))
                .body("title", equalTo(title))
                .body("sourceType", equalTo(sourceType))
                .body("sourceRef", equalTo(sourceRef))
                .body("status", equalTo("PROCESSING"))
                .body("materialsVersion", equalTo(1));
    }

    /** Игнорирует клиентские системные поля. */
    @Test
    @Step("Игнорирует клиентские системные поля")
    void givenClientSuppliedMetadata_whenCreate_thenUseServerMetadata() throws Exception {
        // Действие и проверки ответа
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        """
                                {"title":"Событие","sourceType":"TEXT","sourceRef":"Текст",
                                 "id":"00000000-0000-0000-0000-000000000001","status":"READY",
                                 "createdAt":"2000-01-01T00:00:00Z","materialsVersion":99}
                                """)
                .when()
                .post("/api/events")
                .then()
                .statusCode(201)
                .body("id", not(equalTo("00000000-0000-0000-0000-000000000001")))
                .body("status", equalTo("PROCESSING"))
                .body("createdAt", not(equalTo("2000-01-01T00:00:00Z")))
                .body("materialsVersion", equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "{",
                "[]",
                "{\"sourceType\":\"TEXT\",\"sourceRef\":\"Текст\"}",
                "{\"title\":null,\"sourceType\":\"TEXT\",\"sourceRef\":\"Текст\"}",
                "{\"title\":\" \",\"sourceType\":\"TEXT\",\"sourceRef\":\"Текст\"}",
                "{\"title\":\"Событие\",\"sourceRef\":\"Текст\"}",
                "{\"title\":\"Событие\",\"sourceType\":null,\"sourceRef\":\"Текст\"}",
                "{\"title\":\"Событие\",\"sourceType\":\"OTHER\",\"sourceRef\":\"Текст\"}",
                "{\"title\":\"Событие\",\"sourceType\":\"TEXT\"}",
                "{\"title\":\"Событие\",\"sourceType\":\"TEXT\",\"sourceRef\":null}",
                "{\"title\":\"Событие\",\"sourceType\":\"TEXT\",\"sourceRef\":\"\"}",
                "{\"title\":\"Событие\",\"sourceType\":\"URL\",\"sourceRef\":\" \"}",
                "{\"title\":\"Событие\",\"sourceType\":\"PDF\",\"sourceRef\":\" \"}"
            })
    @Step("Отклоняет некорректное тело события")
    void givenInvalidBody_whenCreateEvent_thenBadRequestWithoutSaving(String request)
            throws Exception {
        List<Map<String, Object>> eventsBeforeRequest = eventRows();

        // Действие: невалидное тело отклоняется.
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .when()
                .post("/api/events")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"))
                .body("message", matchesPattern("(?s).*\\S.*"))
                .body("retryable", equalTo(false));

        // Проверка: отклонённый запрос не меняет сохранённые события.
        assertThat(eventRows())
                .as("события не меняются после отклонения некорректного тела")
                .isEqualTo(eventsBeforeRequest);
    }

    /** Отклоняет запрос без тела. */
    @Test
    @Step("Отклоняет запрос без тела")
    void givenNoBody_whenCreate_thenBadRequest() throws Exception {
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .when()
                .post("/api/events")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"))
                .body("message", matchesPattern("(?s).*\\S.*"))
                .body("retryable", equalTo(false));
    }

    /** Запрещает создание неадминистратору. */
    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_DIRECTOR", "OTHER_ROLE"})
    @Step("Запрещает создание неадминистратору")
    void givenNonAdmin_whenCreateEvent_thenForbiddenWithoutSaving(String role) throws Exception {
        List<Map<String, Object>> eventsBeforeRequest = eventRows();

        request()
                .postProcessors(role(role))
                .contentType(MediaType.APPLICATION_JSON)
                .body(REQUEST)
                .when()
                .post("/api/events")
                .then()
                .statusCode(403);
        assertThat(eventRows())
                .as("события не меняются после отказа в создании")
                .isEqualTo(eventsBeforeRequest);
    }

    /** Требует авторизацию для событий. */
    @Test
    @Step("Требует авторизацию для событий")
    void givenNoToken_whenAccessEvents_thenUnauthorized() throws Exception {
        request()
                .contentType(MediaType.APPLICATION_JSON)
                .body(REQUEST)
                .when()
                .post("/api/events")
                .then()
                .statusCode(401);
        request().when().get("/api/events").then().statusCode(401);
        request()
                .when()
                .get("/api/events/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(401);
    }

    /** Возвращает события в заданном порядке. */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    @Step("Возвращает страницу событий в заданном порядке")
    void givenEvents_whenListAndRead_thenReturnNewestFirstWithStableOrder(String role)
            throws Exception {
        // Подготовка
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        String titlePrefix = "Event API order " + UUID.randomUUID();
        String firstTitle = titlePrefix + " — Старое";
        String secondTitle = titlePrefix + " — Новое";
        String thirdTitle = titlePrefix + " — Новое PDF";
        jdbc.update(
                """
                INSERT INTO events (id, title, source_type, source_ref, status, created_at)
                VALUES (?, ?, 'TEXT', 'Текст', 'FAILED', '2026-10-23T10:00:00Z'),
                       (?, ?, 'URL', 'https://example.org', 'READY', '2026-10-24T10:00:00Z'),
                       (?, ?, 'PDF', 'report.pdf', 'PROCESSING', '2026-10-24T10:00:00Z')
                """,
                first,
                firstTitle,
                second,
                secondTitle,
                third,
                thirdTitle);

        // Действие и проверки
        request()
                .postProcessors(role(role))
                .queryParam("title", titlePrefix)
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("content", hasSize(3))
                .body("content[0].id", equalTo(third.toString()))
                .body("content[1].id", equalTo(second.toString()))
                .body("content[2].id", equalTo(first.toString()))
                .body("content[2].status", equalTo("FAILED"))
                .body("content[0].title", equalTo(thirdTitle))
                .body("content[0].sourceType", equalTo("PDF"))
                .body("content[0].createdAt", notNullValue())
                .body("content[0]", not(hasKey("sourceRef")))
                .body("content[0]", not(hasKey("materialsVersion")))
                .body("totalElements", equalTo(3));
        request()
                .postProcessors(role(role))
                .when()
                .get("/api/events/" + second)
                .then()
                .statusCode(200)
                .body("title", equalTo(secondTitle))
                .body("status", equalTo("READY"));
    }

    /** Возвращает пустую страницу для фильтра без совпадений. */
    @Test
    @Step("Возвращает пустую страницу для фильтра без совпадений")
    void givenNoEvents_whenList_thenReturnEmptyArray() throws Exception {
        // Подготовка: случайный заголовок не совпадает с существующими событиями.
        String unmatchedTitle = "no-event-" + UUID.randomUUID();

        request()
                .postProcessors(role("COMPANY_DIRECTOR"))
                .queryParam("title", unmatchedTitle)
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("content", hasSize(0))
                .body("number", equalTo(0))
                .body("size", equalTo(20))
                .body("totalElements", equalTo(0));
    }

    /** Объединяет фильтры и сохраняет число результатов за пределами страницы. */
    @Test
    @Step("Объединяет фильтры событий и сохраняет общее число результатов")
    void givenFiltersAndPage_whenList_thenCombineFiltersAndPreserveTotals() {
        // Подготовка: создаёт события с разными заголовками, статусами и типами источника.
        insertListEvents();

        // Действие и проверки: совмещает фильтры и получает вторую страницу результата.
        request()
                .postProcessors(role("COMPANY_DIRECTOR"))
                .queryParam("title", "  rate  ")
                .queryParam("status", " READY ")
                .queryParam("sourceType", " TEXT ")
                .queryParam("page", "1")
                .queryParam("size", "1")
                .queryParam("sort", "title,asc")
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("content", hasSize(1))
                .body("content[0].title", equalTo("Rate B"))
                .body("number", equalTo(1))
                .body("size", equalTo(1))
                .body("totalElements", equalTo(2))
                .body("totalPages", equalTo(2));

        // Проверки: страница за пределами результата пуста, но totalElements сохраняется.
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .queryParam("title", "rate")
                .queryParam("status", "READY")
                .queryParam("sourceType", "TEXT")
                .queryParam("page", "5")
                .queryParam("size", "1")
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("content", hasSize(0))
                .body("totalElements", equalTo(2));
    }

    /** Игнорирует пустые и пробельные значения фильтров событий. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    @Step("Игнорирует пустые фильтры событий")
    void givenBlankFilters_whenList_thenIgnoreFilters(String filter) throws Exception {
        // Подготовка
        insertListEvents();
        long persistedEventCount = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);

        // Действие: считывает список без фильтров и с пустыми значениями фильтров.
        JsonNode unfiltered =
                mapper.readTree(
                        request()
                                .postProcessors(role("SYSTEM_ADMIN"))
                                .when()
                                .get("/api/events")
                                .then()
                                .statusCode(200)
                                .extract()
                                .asString());
        JsonNode withBlankFilters =
                mapper.readTree(
                        request()
                                .postProcessors(role("SYSTEM_ADMIN"))
                                .queryParam("title", filter)
                                .queryParam("status", filter)
                                .queryParam("sourceType", filter)
                                .when()
                                .get("/api/events")
                                .then()
                                .statusCode(200)
                                .extract()
                                .asString());

        // Проверка: пустые фильтры дают тот же список, а totalElements отражает все строки.
        assertThat(withBlankFilters)
                .as("полный ответ при пустых фильтрах совпадает с ответом без фильтров: %s", filter)
                .isEqualTo(unfiltered);
        assertThat(withBlankFilters.path("totalElements").asLong())
                .as("число результатов совпадает с числом сохранённых событий")
                .isEqualTo(persistedEventCount);
    }

    /** Ищет специальные символы заголовка как литералы, а не SQL-шаблоны. */
    @Test
    @Step("Ищет специальные символы заголовка буквально")
    void givenWildcardCharacters_whenList_thenMatchLiteralTitle() {
        // Подготовка
        insertListEvents();
        jdbc.update(
                """
                INSERT INTO events (id, title, source_type, source_ref, status)
                VALUES (?, 'Rate!%_special', 'TEXT', 'Текст', 'READY')
                """,
                UUID.fromString("00000000-0000-0000-0000-000000000006"));

        // Действие и проверки
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .queryParam("title", "!%_")
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("content[0].title", equalTo("Rate!%_special"))
                .body("totalElements", equalTo(1));
    }

    /** Отклоняет неизвестные значения фильтров списка событий. */
    @ParameterizedTest
    @CsvSource({"status,UNKNOWN", "sourceType,OTHER"})
    @Step("Отклоняет неизвестные значения фильтров событий")
    void givenInvalidFilter_whenList_thenBadRequest(String parameter, String value) {
        // Действие и проверки
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .queryParam(parameter, value)
                .when()
                .get("/api/events")
                .then()
                .statusCode(400);
    }

    /** Считывает все поля событий в стабильном порядке для сравнения побочных эффектов. */
    private List<Map<String, Object>> eventRows() {
        return jdbc.queryForList("SELECT * FROM events ORDER BY id");
    }

    /** Создаёт фиксированный набор событий для проверки фильтрации и пагинации. */
    private void insertListEvents() {
        jdbc.update(
                """
                INSERT INTO events (id, title, source_type, source_ref, status)
                VALUES ('00000000-0000-0000-0000-000000000001', 'Rate A', 'TEXT', 'Текст', 'READY'),
                       ('00000000-0000-0000-0000-000000000002', 'Rate B', 'TEXT', 'Текст', 'READY'),
                       ('00000000-0000-0000-0000-000000000003', 'Rate C', 'TEXT', 'Текст', 'FAILED'),
                       ('00000000-0000-0000-0000-000000000004', 'Rate D', 'URL', 'url', 'READY'),
                       ('00000000-0000-0000-0000-000000000005', 'Other', 'TEXT', 'Текст', 'READY')
                """);
    }

    /** Возвращает 404 для неизвестного события. */
    @Test
    @Step("Возвращает 404 для неизвестного события")
    void givenUnknownEvent_whenGet_thenNotFound() throws Exception {
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .when()
                .get("/api/events/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404);
    }

    /** Отклоняет некорректный UUID события. */
    @Test
    @Step("Отклоняет некорректный UUID события")
    void givenInvalidId_whenGet_thenBadRequest() throws Exception {
        request()
                .postProcessors(role("SYSTEM_ADMIN"))
                .when()
                .get("/api/events/invalid")
                .then()
                .statusCode(400);
    }

    /** Запрещает чтение посторонней роли. */
    @Test
    @Step("Запрещает чтение посторонней роли")
    void givenUnrelatedRole_whenRead_thenForbidden() throws Exception {
        request()
                .postProcessors(role("OTHER_ROLE"))
                .when()
                .get("/api/events")
                .then()
                .statusCode(403);
        request()
                .postProcessors(role("OTHER_ROLE"))
                .when()
                .get("/api/events/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(403);
    }

    /** Создаёт request-local спецификацию с общим API context path. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Создаёт JWT-процессор с указанной прикладной ролью. */
    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
