package alpha.business.insight.core.migration;

import alpha.business.insight.core.model.Company;
import alpha.business.insight.core.model.DialogMessage;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import alpha.business.insight.core.model.Feedback;
import alpha.business.insight.core.model.Insight;
import alpha.business.insight.core.model.UserCompanyAccess;
import alpha.business.insight.core.model.UserCompanyAccessId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@Testcontainers
@Transactional
@DisplayName("Схема хранения материалов и персонального анализа")
class AnalysisSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    EntityManager entities;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void company() {
        jdbc.update("INSERT INTO companies (company_id) VALUES ('schema_company')");
    }

    @Test
    @DisplayName("Сохранение событий, разборов, JSONB и составного ключа")
    void givenAnalysisGraph_whenPersistAndReload_thenPreserveDataAndRelations() throws Exception {
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        Company company = entities.getReference(Company.class, "schema_company");
        Event event = persist(Event.class, Map.of("title", "Снижение ставки", "sourceType", EventSourceType.PDF,
                "sourceRef", "reports/rates.pdf", "status", EventStatus.READY, "createdAt", now,
                "materialsVersion", 1L));
        UUID fragmentId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("summary", "Кредиты станут дешевле", "sources", java.util.List.of(fragmentId.toString()));
        Insight insight = persist(Insight.class, Map.of("company", company, "event", event,
                "materialsVersion", 1L, "payload", payload, "costRub", new BigDecimal("1.25"), "createdAt", now));
        DialogMessage message = persist(DialogMessage.class, Map.of("insight", insight, "role", "USER",
                "text", "Что это значит для нас?", "createdAt", now));
        Feedback feedback = persist(Feedback.class, Map.of("insight", insight, "userId", "keycloak-sub",
                "value", (short) 1, "createdAt", now));
        UserCompanyAccessId accessId = new UserCompanyAccessId("keycloak-sub", company.getId());
        persist(UserCompanyAccess.class, Map.of("id", accessId, "company", company));
        entities.flush();
        entities.clear();

        assertThat(entities.find(Event.class, event.getId()).getSourceType()).isEqualTo(EventSourceType.PDF);
        assertThat(entities.find(Event.class, event.getId()).getCreatedAt()).isEqualTo(now);
        Insight loadedInsight = entities.find(Insight.class, insight.getId());
        assertThat(loadedInsight.getPayload()).isEqualTo(payload);
        assertThat(loadedInsight.getCostRub()).isEqualByComparingTo("1.25");
        assertThat(loadedInsight.getCompany().getId()).isEqualTo(company.getId());
        assertThat(entities.find(DialogMessage.class, message.getId()).getText()).isEqualTo("Что это значит для нас?");
        assertThat(entities.find(Feedback.class, feedback.getId()).getValue()).isEqualTo((short) 1);
        assertThat(entities.find(UserCompanyAccess.class, accessId).getCompany().getId()).isEqualTo(company.getId());
    }

    @Test
    @DisplayName("Новая версия материалов допускает новый инсайт, повтор той же версии запрещён")
    void givenCachedInsight_whenInsertVersion_thenEnforceCacheKey() {
        UUID event = insertEvent();
        insertInsight(event, 1);
        insertInsight(event, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM insights WHERE event_id = ?", Long.class, event)).isEqualTo(2);
        assertThatThrownBy(() -> insertInsight(event, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Таблиц чанков, фактов и очереди очистки в PostgreSQL нет")
    void whenInspectSchema_thenChunksAreNotStoredInPostgres() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema()
                    AND table_name IN ('fragments', 'facts', 'document_upload_cleanup')
                """, String.class)).isEmpty();
    }

    @Test
    @DisplayName("Все прикладные таблицы и колонки имеют комментарии в PostgreSQL")
    void whenInspectSchema_thenEveryTableAndColumnHasComment() {
        assertThat(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind = 'r'
                    AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')
                    AND obj_description(c.oid, 'pg_class') IS NULL
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT c.relname || '.' || a.attname
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = current_schema() AND c.relkind = 'r'
                    AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')
                    AND a.attnum > 0 AND NOT a.attisdropped AND col_description(c.oid, a.attnum) IS NULL
                """, String.class)).isEmpty();
    }

    private UUID insertEvent() {
        return jdbc.queryForObject("""
                INSERT INTO events (title, source_type, source_ref) VALUES ('Событие', 'TEXT', 'Текст') RETURNING id
                """, UUID.class);
    }

    private void insertInsight(UUID event, long version) {
        jdbc.update("""
                INSERT INTO insights (company_id, event_id, materials_version, payload)
                VALUES ('schema_company', ?, ?, '{"summary":"Разбор"}')
                """, event, version);
    }

    private <T> T persist(Class<T> type, Map<String, Object> fields) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        T entity = constructor.newInstance();
        fields.forEach((name, value) -> ReflectionTestUtils.setField(entity, name, value));
        entities.persist(entity);
        return entity;
    }
}
