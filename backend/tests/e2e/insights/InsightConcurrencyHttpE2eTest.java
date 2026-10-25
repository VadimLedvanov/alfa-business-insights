package alpha.business.insight.core.qa.insights;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.ConcurrentRequests;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Проверяет конкуренцию генерации инсайтов по живому HTTP API без внешней LLM. */
@Epic("Backend")
@Feature("Конкурентная генерация инсайтов")
@Tag("e2e")
@Tag("concurrency")
class InsightConcurrencyHttpE2eTest extends RealStackE2eTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private final List<String> companies = new ArrayList<>();
    @Autowired private ObjectMapper mapper;
    private UUID eventId;
    private String directorToken;
    private String directorUserId;

    /** Создаёт изолированное готовое событие, компании и явные разрешения директора. */
    @BeforeEach
    void createReadyEventAndCompanyGrants() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        companies.add("e2e_concurrent_a_" + suffix);
        companies.add("e2e_concurrent_b_" + suffix);
        companies.add("e2e_concurrent_c_" + suffix);
        companies.forEach(
                company ->
                        jdbc.update(
                                "INSERT INTO companies (company_id, profile_status) VALUES (?, 'MISSING')",
                                company));
        eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO events (id, title, source_type, source_ref, status) "
                        + "VALUES (?, 'Конкурентная проверка инсайта', 'TEXT', ?, 'READY')",
                eventId,
                "Синтетическое событие " + suffix);
        directorToken = keycloak.accessToken("director", "director-dev");
        directorUserId = json(backend.get("/auth/me", directorToken)).path("id").asText();
        if (directorUserId.isBlank())
            throw new IllegalStateException("Не найден subject директора");
        companies.forEach(
                company ->
                        jdbc.update(
                                "INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?)",
                                directorUserId,
                                company));
    }

    /** Удаляет строки только созданного события и его тестовых компаний. */
    @AfterEach
    void removeReadyEventAndCompanyGrants() {
        if (eventId == null) return;
        jdbc.update(
                "DELETE FROM dialog_messages WHERE insight_id IN "
                        + "(SELECT id FROM insights WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM insights WHERE event_id = ?", eventId);
        jdbc.update(
                "DELETE FROM user_company_access WHERE company_id IN (?, ?, ?)",
                companies.toArray());
        jdbc.update("DELETE FROM events WHERE id = ?", eventId);
        jdbc.update("DELETE FROM companies WHERE company_id IN (?, ?, ?)", companies.toArray());
    }

    /** Сериализует одинаковые HTTP POST по событию и возвращает обеим сторонам один кэш. */
    @Test
    @DisplayName("TestE2EConcurrentInsightPostsShareCachedResult")
    void TestE2EConcurrentInsightPostsShareCachedResult() throws Exception {
        String company = companies.getFirst();
        String path = insightPath(company);
        HeldEventLock lock =
                Allure.step(
                        "1. Удержать блокировку строки события в отдельной сессии",
                        this::lockEventRow);
        List<InsightResponse> responses;
        try {
            responses =
                    Allure.step(
                            "2. Отправить два POST и дождаться их сериализации по событию",
                            () -> {
                                String parentStepUuid =
                                        Allure.getLifecycle()
                                                .getCurrentTestCaseOrStep()
                                                .orElseThrow();
                                try (var requests =
                                        ConcurrentRequests.startTogether(
                                                2,
                                                parentStepUuid,
                                                REQUEST_TIMEOUT,
                                                ignored -> {
                                                    var response =
                                                            backend.postJson(
                                                                    path, directorToken, "{}");
                                                    return new InsightResponse(
                                                            response.statusCode(),
                                                            parseJson(response.asString()));
                                                })) {
                                    try {
                                        requests.awaitReady();
                                        requests.release();
                                        Allure.step(
                                                "2.1. Подтвердить ожидание запросов на нашей блокировке события",
                                                () -> awaitRequestsBlockedBy(lock.backendPid(), 2));
                                        lock.release();
                                        return requests.awaitResults();
                                    } finally {
                                        lock.close();
                                    }
                                }
                            });
        } finally {
            lock.close();
        }

        Allure.step(
                "4. Проверить одинаковый ответ, одну сохранённую генерацию и последующий GET",
                () -> {
                    assertThat(responses).hasSize(2);
                    assertThat(responses).extracting(InsightResponse::status).containsOnly(200);
                    JsonNode first = responses.getFirst().body();
                    JsonNode second = responses.get(1).body();
                    assertThat(second).isEqualTo(first);
                    assertThat(first.path("id").asString()).isNotBlank();
                    assertThat(first.path("trace_id").asString()).isNotBlank();
                    Integer rows =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM insights WHERE company_id = ? AND event_id = ? AND materials_version = 1",
                                    Integer.class,
                                    company,
                                    eventId);
                    assertThat(rows).isEqualTo(1);
                    String savedTrace =
                            jdbc.queryForObject(
                                    "SELECT payload ->> 'trace_id' FROM insights WHERE company_id = ? AND event_id = ? AND materials_version = 1",
                                    String.class,
                                    company,
                                    eventId);
                    assertThat(savedTrace).isEqualTo(first.path("trace_id").asString());
                    var cached = backend.get(path, directorToken);
                    assertThat(cached.statusCode()).isEqualTo(200);
                    assertThat(parseJson(cached.asString())).isEqualTo(first);
                });
    }

    /** Даёт трём разрешённым компаниям отдельные записи по одному READY-событию. */
    @Test
    @DisplayName("TestE2EConcurrentCompanyInsightsKeepSeparateCacheEntries")
    void TestE2EConcurrentCompanyInsightsKeepSeparateCacheEntries() throws Exception {
        List<InsightResponse> responses =
                Allure.step(
                        "1. Одновременно отправить POST для трёх разрешённых компаний",
                        () -> {
                            String parentStepUuid =
                                    Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow();
                            try (var requests =
                                    ConcurrentRequests.startTogether(
                                            companies.size(),
                                            parentStepUuid,
                                            REQUEST_TIMEOUT,
                                            index -> {
                                                var response =
                                                        backend.postJson(
                                                                insightPath(companies.get(index)),
                                                                directorToken,
                                                                "{}");
                                                return new InsightResponse(
                                                        response.statusCode(),
                                                        parseJson(response.asString()));
                                            })) {
                                requests.awaitReady();
                                requests.release();
                                return requests.awaitResults();
                            }
                        });

        Allure.step(
                "2. Проверить независимые ответы, связи PostgreSQL и кэш каждой компании",
                () -> {
                    assertThat(responses).hasSize(companies.size());
                    assertThat(responses).extracting(InsightResponse::status).containsOnly(200);
                    Set<String> responseIds = new HashSet<>();
                    Set<String> responseTraces = new HashSet<>();
                    for (int index = 0; index < companies.size(); index++) {
                        JsonNode body = responses.get(index).body();
                        String id = body.path("id").asString();
                        String trace = body.path("trace_id").asString();
                        assertThat(id).isNotBlank();
                        assertThat(trace).isNotBlank();
                        responseIds.add(id);
                        responseTraces.add(trace);

                        var saved =
                                jdbc.queryForMap(
                                        "SELECT id::text AS id, company_id, event_id::text AS event_id, "
                                                + "materials_version, payload ->> 'trace_id' AS trace_id "
                                                + "FROM insights WHERE company_id = ? AND event_id = ? AND materials_version = 1",
                                        companies.get(index),
                                        eventId);
                        assertThat(saved.get("id")).isEqualTo(id);
                        assertThat(saved.get("company_id")).isEqualTo(companies.get(index));
                        assertThat(saved.get("event_id")).isEqualTo(eventId.toString());
                        assertThat(saved.get("trace_id")).isEqualTo(trace);

                        var cached = backend.get(insightPath(companies.get(index)), directorToken);
                        assertThat(cached.statusCode()).isEqualTo(200);
                        assertThat(parseJson(cached.asString())).isEqualTo(body);
                    }
                    assertThat(responseIds).hasSize(companies.size());
                    assertThat(responseTraces).hasSize(companies.size());
                    Integer savedRows =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM insights WHERE event_id = ? AND company_id IN (?, ?, ?) AND materials_version = 1",
                                    Integer.class,
                                    eventId,
                                    companies.get(0),
                                    companies.get(1),
                                    companies.get(2));
                    assertThat(savedRows).isEqualTo(companies.size());
                });
    }

    /** Удерживает блокировку события собственной PostgreSQL-сессией. */
    private HeldEventLock lockEventRow() throws Exception {
        var dataSource = jdbc.getDataSource();
        if (dataSource == null)
            throw new IllegalStateException("Нет DataSource для блокировки события");
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            int pid;
            try (PreparedStatement statement =
                            connection.prepareStatement("SELECT pg_backend_pid()");
                    ResultSet result = statement.executeQuery()) {
                result.next();
                pid = result.getInt(1);
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT id FROM events WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, eventId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next())
                        throw new IllegalStateException("Тестовое событие не найдено");
                }
            }
            return new HeldEventLock(connection, pid);
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    /** Ждёт блокирующие сессии, чей PostgreSQL blocker действительно является нашим PID. */
    private void awaitRequestsBlockedBy(int blockerPid, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int blockedCount = 0;
        while (System.nanoTime() < deadline) {
            blockedCount =
                    jdbc.queryForObject(
                            "WITH RECURSIVE blockers(waiting_pid, blocker_pid, depth) AS ("
                                    + " SELECT activity.pid, blocker.pid, 1 FROM pg_stat_activity activity"
                                    + " CROSS JOIN LATERAL unnest(pg_blocking_pids(activity.pid)) AS blocker(pid)"
                                    + " WHERE activity.pid <> ? AND activity.datname = current_database()"
                                    + " AND activity.wait_event_type = 'Lock'"
                                    + " UNION ALL"
                                    + " SELECT blockers.waiting_pid, parent_blocker.pid, blockers.depth + 1"
                                    + " FROM blockers JOIN pg_stat_activity parent ON parent.pid = blockers.blocker_pid"
                                    + " CROSS JOIN LATERAL unnest(pg_blocking_pids(parent.pid)) AS parent_blocker(pid)"
                                    + " WHERE blockers.blocker_pid <> ? AND blockers.depth < 10"
                                    + ") SELECT count(DISTINCT waiting_pid) FROM blockers WHERE blocker_pid = ?",
                            Integer.class,
                            blockerPid,
                            blockerPid,
                            blockerPid);
            if (blockedCount >= expectedCount) return;
            Thread.sleep(25);
        }
        assertThat(blockedCount)
                .as("HTTP sessions blocked by the test's event-row lock PID %s", blockerPid)
                .isGreaterThanOrEqualTo(expectedCount);
    }

    /** Возвращает endpoint инсайта для выбранной компании и готового события. */
    private String insightPath(String company) {
        return "/companies/" + company + "/events/" + eventId + "/insight";
    }

    /** Разбирает JSON-ответ живого backend. */
    private JsonNode parseJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Ответ инсайта не является корректным JSON", failure);
        }
    }

    private record InsightResponse(int status, JsonNode body) {}

    private static final class HeldEventLock implements AutoCloseable {
        private final Connection connection;
        private final int backendPid;
        private boolean released;
        private boolean closed;

        private HeldEventLock(Connection connection, int backendPid) {
            this.connection = connection;
            this.backendPid = backendPid;
        }

        int backendPid() {
            return backendPid;
        }

        void release() throws Exception {
            if (!released) {
                connection.commit();
                released = true;
            }
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            try {
                if (!released) connection.rollback();
            } finally {
                closed = true;
                connection.close();
            }
        }
    }
}
