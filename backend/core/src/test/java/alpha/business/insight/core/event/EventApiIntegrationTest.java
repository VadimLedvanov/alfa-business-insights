package alpha.business.insight.core.event;

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

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class EventApiIntegrationTest {
    private static final String REQUEST = """
            {"title":"Снижение ставки","sourceType":"TEXT","sourceRef":"Ставка снижена"}
            """;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

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

    @ParameterizedTest
    @CsvSource({"TEXT,Ставка снижена", "URL,https://example.org/news", "PDF,/api/documents/report/file"})
    void givenAdmin_whenCreateEvent_thenPersistAndReturnReadableEvent(String sourceType, String sourceRef)
            throws Exception {
        Instant before = Instant.now();
        String request = mapper.writeValueAsString(java.util.Map.of(
                "title", "Снижение ставки", "sourceType", sourceType, "sourceRef", sourceRef));
        var result = mvc.perform(post("/api/events").contextPath("/api").with(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Снижение ставки"))
                .andExpect(jsonPath("$.sourceType").value(sourceType))
                .andExpect(jsonPath("$.sourceRef").value(sourceRef))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.materialsVersion").value(1))
                .andReturn();
        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(response.get("id").asString());
        Instant createdAt = Instant.parse(response.get("createdAt").asString());
        assertThat(createdAt).isBetween(before, Instant.now());
        String location = "/api/events/" + id;
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(location);

        entities.flush();
        entities.clear();
        assertThat(jdbc.queryForMap("SELECT title, source_type, source_ref, status, materials_version FROM events WHERE id = ?", id))
                .containsEntry("title", "Снижение ставки")
                .containsEntry("source_type", sourceType)
                .containsEntry("source_ref", sourceRef)
                .containsEntry("status", "PROCESSING")
                .containsEntry("materials_version", 1L);
        mvc.perform(get(location).contextPath("/api").with(role("COMPANY_DIRECTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Снижение ставки"))
                .andExpect(jsonPath("$.sourceType").value(sourceType))
                .andExpect(jsonPath("$.sourceRef").value(sourceRef))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.materialsVersion").value(1));
    }

    @Test
    void givenClientSuppliedMetadata_whenCreate_thenUseServerMetadata() throws Exception {
        mvc.perform(post("/api/events").contextPath("/api").with(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Событие","sourceType":"TEXT","sourceRef":"Текст",
                                 "id":"00000000-0000-0000-0000-000000000001","status":"READY",
                                 "createdAt":"2000-01-01T00:00:00Z","materialsVersion":99}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not("00000000-0000-0000-0000-000000000001")))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.not("2000-01-01T00:00:00Z")))
                .andExpect(jsonPath("$.materialsVersion").value(1));
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
    void givenInvalidBody_whenCreateEvent_thenBadRequestWithoutSaving(String request) throws Exception {
        mvc.perform(post("/api/events").contextPath("/api").with(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
    }

    @Test
    void givenNoBody_whenCreate_thenBadRequest() throws Exception {
        mvc.perform(post("/api/events").contextPath("/api").with(role("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_DIRECTOR", "OTHER_ROLE"})
    void givenNonAdmin_whenCreateEvent_thenForbiddenWithoutSaving(String role) throws Exception {
        mvc.perform(post("/api/events").contextPath("/api").with(role(role))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
    }

    @Test
    void givenNoToken_whenAccessEvents_thenUnauthorized() throws Exception {
        mvc.perform(post("/api/events").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/00000000-0000-0000-0000-000000000001").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
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
        mvc.perform(get("/api/events").contextPath("/api").with(role(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(third.toString()))
                .andExpect(jsonPath("$[1].id").value(second.toString()))
                .andExpect(jsonPath("$[2].id").value(first.toString()))
                .andExpect(jsonPath("$[2].status").value("FAILED"));
        mvc.perform(get("/api/events/" + second).contextPath("/api").with(role(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новое"))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void givenNoEvents_whenList_thenReturnEmptyArray() throws Exception {
        mvc.perform(get("/api/events").contextPath("/api").with(role("COMPANY_DIRECTOR")))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void givenUnknownEvent_whenGet_thenNotFound() throws Exception {
        mvc.perform(get("/api/events/00000000-0000-0000-0000-000000000001")
                        .contextPath("/api").with(role("SYSTEM_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenInvalidId_whenGet_thenBadRequest() throws Exception {
        mvc.perform(get("/api/events/invalid").contextPath("/api").with(role("SYSTEM_ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenUnrelatedRole_whenRead_thenForbidden() throws Exception {
        mvc.perform(get("/api/events").contextPath("/api").with(role("OTHER_ROLE")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/00000000-0000-0000-0000-000000000001")
                        .contextPath("/api").with(role("OTHER_ROLE")))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
