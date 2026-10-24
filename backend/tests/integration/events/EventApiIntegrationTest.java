package alpha.business.insight.core.event;

import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;

import org.junit.jupiter.api.Test;
import io.qameta.allure.Step;
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
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проверяет создание, чтение и права доступа к событиям через API. */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
        "spring.ai.model.chat=none"
})
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@org.junit.jupiter.api.Tag("integration")
@org.junit.jupiter.api.Tag("events")
class EventApiIntegrationTest {
    private static final String REQUEST = """
            {"title":"Снижение ставки","sourceType":"TEXT","sourceRef":"Ставка снижена"}
            """;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    /** Подключает контейнер PostgreSQL к Spring-контексту. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entities;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    JwtDecoder decoder;
    /** Создаёт событие и проверяет сохранение. */

    @ParameterizedTest
    @CsvSource({"TEXT,Ставка снижена", "URL,https://example.org/news", "PDF,/api/documents/report/file"})
    @Step("Создаёт событие и проверяет сохранение")
    void givenAdmin_whenCreateEvent_thenPersistAndReturnReadableEvent(String sourceType, String sourceRef)
            throws Exception {
        Instant before = Instant.now();
        String request = mapper.writeValueAsString(java.util.Map.of(
                "title", "Снижение ставки", "sourceType", sourceType, "sourceRef", sourceRef));
        var result = request().postProcessors(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/events").then()
                .assertThat(status().isCreated())
                .assertThat(jsonPath("$.title").value("Снижение ставки"))
                .assertThat(jsonPath("$.sourceType").value(sourceType))
                .assertThat(jsonPath("$.sourceRef").value(sourceRef))
                .assertThat(jsonPath("$.status").value("PROCESSING"))
                .assertThat(jsonPath("$.materialsVersion").value(1))
                .extract().response();
        JsonNode response = mapper.readTree(result.asString());
        UUID id = UUID.fromString(response.get("id").asString());
        Instant createdAt = Instant.parse(response.get("createdAt").asString());
        assertThat(createdAt).isBetween(before, Instant.now());
        String location = "/api/events/" + id;
        assertThat(result.header("Location")).isEqualTo(location);

        entities.flush();
        entities.clear();
        assertThat(jdbc.queryForMap("SELECT title, source_type, source_ref, status, materials_version FROM events WHERE id = ?", id))
                .containsEntry("title", "Снижение ставки")
                .containsEntry("source_type", sourceType)
                .containsEntry("source_ref", sourceRef)
                .containsEntry("status", "PROCESSING")
                .containsEntry("materials_version", 1L);
        request().postProcessors(role("COMPANY_DIRECTOR")).when().get(location).then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$.id").value(id.toString()))
                .assertThat(jsonPath("$.title").value("Снижение ставки"))
                .assertThat(jsonPath("$.sourceType").value(sourceType))
                .assertThat(jsonPath("$.sourceRef").value(sourceRef))
                .assertThat(jsonPath("$.status").value("PROCESSING"))
                .assertThat(jsonPath("$.materialsVersion").value(1));
    }
    /** Игнорирует клиентские системные поля. */

    @Test
    @Step("Игнорирует клиентские системные поля")
    void givenClientSuppliedMetadata_whenCreate_thenUseServerMetadata() throws Exception {
        request().postProcessors(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).body("""
                                {"title":"Событие","sourceType":"TEXT","sourceRef":"Текст",
                                 "id":"00000000-0000-0000-0000-000000000001","status":"READY",
                                 "createdAt":"2000-01-01T00:00:00Z","materialsVersion":99}
                                """).when().post("/api/events").then()
                .assertThat(status().isCreated())
                .assertThat(jsonPath("$.id").value(org.hamcrest.Matchers.not("00000000-0000-0000-0000-000000000001")))
                .assertThat(jsonPath("$.status").value("PROCESSING"))
                .assertThat(jsonPath("$.createdAt").value(org.hamcrest.Matchers.not("2000-01-01T00:00:00Z")))
                .assertThat(jsonPath("$.materialsVersion").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{", "[]",
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
    void givenInvalidBody_whenCreateEvent_thenBadRequestWithoutSaving(String request) throws Exception {
        request().postProcessors(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/events").then()
                .assertThat(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
    }
    /** Отклоняет запрос без тела. */

    @Test
    @Step("Отклоняет запрос без тела")
    void givenNoBody_whenCreate_thenBadRequest() throws Exception {
        request().postProcessors(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).when().post("/api/events").then()
                .assertThat(status().isBadRequest());
    }
    /** Запрещает создание неадминистратору. */

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_DIRECTOR", "OTHER_ROLE"})
    @Step("Запрещает создание неадминистратору")
    void givenNonAdmin_whenCreateEvent_thenForbiddenWithoutSaving(String role) throws Exception {
        request().postProcessors(role(role))
                        .contentType(MediaType.APPLICATION_JSON).body(REQUEST).when().post("/api/events").then()
                .assertThat(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
    }
    /** Требует авторизацию для событий. */

    @Test
    @Step("Требует авторизацию для событий")
    void givenNoToken_whenAccessEvents_thenUnauthorized() throws Exception {
        request()
                        .contentType(MediaType.APPLICATION_JSON).body(REQUEST).when().post("/api/events").then()
                .assertThat(status().isUnauthorized());
        request().when().get("/api/events").then().assertThat(status().isUnauthorized());
        request().when().get("/api/events/00000000-0000-0000-0000-000000000001").then()
                .assertThat(status().isUnauthorized());
    }
    /** Возвращает события в заданном порядке. */

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    @Step("Возвращает события в заданном порядке")
    void givenEvents_whenListAndRead_thenReturnNewestFirstWithStableOrder(String role) throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        jdbc.update("""
                INSERT INTO events (id, title, source_type, source_ref, status, created_at)
                VALUES (?, 'Старое', 'TEXT', 'Текст', 'FAILED', '2026-10-23T10:00:00Z'),
                       (?, 'Новое', 'URL', 'https://example.org', 'READY', '2026-10-24T10:00:00Z'),
                       (?, 'Новое PDF', 'PDF', 'report.pdf', 'PROCESSING', '2026-10-24T10:00:00Z')
                """, first, second, third);
        request().postProcessors(role(role)).when().get("/api/events").then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$", hasSize(3)))
                .assertThat(jsonPath("$[0].id").value(third.toString()))
                .assertThat(jsonPath("$[1].id").value(second.toString()))
                .assertThat(jsonPath("$[2].id").value(first.toString()))
                .assertThat(jsonPath("$[2].status").value("FAILED"));
        request().postProcessors(role(role)).when().get("/api/events/" + second).then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$.title").value("Новое"))
                .assertThat(jsonPath("$.status").value("READY"));
    }
    /** Возвращает пустой список событий. */

    @Test
    @Step("Возвращает пустой список событий")
    void givenNoEvents_whenList_thenReturnEmptyArray() throws Exception {
        request().postProcessors(role("COMPANY_DIRECTOR")).when().get("/api/events").then()
                .assertThat(status().isOk())
                .assertThat(content().json("[]"));
    }
    /** Возвращает 404 для неизвестного события. */

    @Test
    @Step("Возвращает 404 для неизвестного события")
    void givenUnknownEvent_whenGet_thenNotFound() throws Exception {
        request().postProcessors(role("SYSTEM_ADMIN"))
                        .when().get("/api/events/00000000-0000-0000-0000-000000000001").then()
                .assertThat(status().isNotFound());
    }
    /** Отклоняет некорректный UUID события. */

    @Test
    @Step("Отклоняет некорректный UUID события")
    void givenInvalidId_whenGet_thenBadRequest() throws Exception {
        request().postProcessors(role("SYSTEM_ADMIN")).when().get("/api/events/invalid").then()
                .assertThat(status().isBadRequest());
    }
    /** Запрещает чтение посторонней роли. */

    @Test
    @Step("Запрещает чтение посторонней роли")
    void givenUnrelatedRole_whenRead_thenForbidden() throws Exception {
        request().postProcessors(role("OTHER_ROLE")).when().get("/api/events").then()
                .assertThat(status().isForbidden());
        request().postProcessors(role("OTHER_ROLE"))
                        .when().get("/api/events/00000000-0000-0000-0000-000000000001").then()
                .assertThat(status().isForbidden());
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
