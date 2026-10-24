package alpha.business.insight.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.qameta.allure.Step;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
        // Действие
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

        // Проверки побочного эффекта
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
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
        request()
                .postProcessors(role(role))
                .contentType(MediaType.APPLICATION_JSON)
                .body(REQUEST)
                .when()
                .post("/api/events")
                .then()
                .statusCode(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
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
    @Step("Возвращает события в заданном порядке")
    void givenEvents_whenListAndRead_thenReturnNewestFirstWithStableOrder(String role)
            throws Exception {
        // Подготовка
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        jdbc.update(
                """
                INSERT INTO events (id, title, source_type, source_ref, status, created_at)
                VALUES (?, 'Старое', 'TEXT', 'Текст', 'FAILED', '2026-10-23T10:00:00Z'),
                       (?, 'Новое', 'URL', 'https://example.org', 'READY', '2026-10-24T10:00:00Z'),
                       (?, 'Новое PDF', 'PDF', 'report.pdf', 'PROCESSING', '2026-10-24T10:00:00Z')
                """,
                first,
                second,
                third);

        // Действие и проверки
        request()
                .postProcessors(role(role))
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("", hasSize(3))
                .body("[0].id", equalTo(third.toString()))
                .body("[1].id", equalTo(second.toString()))
                .body("[2].id", equalTo(first.toString()))
                .body("[2].status", equalTo("FAILED"));
        request()
                .postProcessors(role(role))
                .when()
                .get("/api/events/" + second)
                .then()
                .statusCode(200)
                .body("title", equalTo("Новое"))
                .body("status", equalTo("READY"));
    }

    /** Возвращает пустой список событий. */
    @Test
    @Step("Возвращает пустой список событий")
    void givenNoEvents_whenList_thenReturnEmptyArray() throws Exception {
        request()
                .postProcessors(role("COMPANY_DIRECTOR"))
                .when()
                .get("/api/events")
                .then()
                .statusCode(200)
                .body("", hasSize(0));
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
