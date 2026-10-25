package alpha.business.insight.core.qa.insights;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Проверяет пользовательский HTTP-путь от списка компании до чтения сохранённого инсайта. */
@Epic("Backend")
@Feature("Инсайты")
@Story("Прочитать инсайт по подготовленному событию")
@Tag("e2e")
@Tag("smoke")
@Tag("insights")
class ConsumerInsightHttpSmokeTest extends RealStackE2eTest {
    private final String companyId =
            "smoke_insight_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID eventId = UUID.randomUUID();
    private final String eventTitle = "Снижение ключевой ставки " + companyId;

    /** Удаляет запись инсайта, событие, грант и компанию, созданные тестом. */
    @AfterEach
    void removeFixture() {
        jdbc.update(
                "DELETE FROM dialog_messages WHERE insight_id IN "
                        + "(SELECT id FROM insights WHERE company_id=? AND event_id=?)",
                companyId,
                eventId);
        jdbc.update("DELETE FROM insights WHERE company_id=? AND event_id=?", companyId, eventId);
        jdbc.update(
                "DELETE FROM company_payment_summaries WHERE payer_company_id=? OR recipient_company_id=?",
                companyId,
                companyId);
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", companyId);
        jdbc.update("DELETE FROM events WHERE id=?", eventId);
        jdbc.update("DELETE FROM companies WHERE company_id=?", companyId);
    }

    /** Проходит клиентский путь: компания, контекст, событие, генерация и повторное чтение. */
    @Test
    @DisplayName("TestSmokeConsumerInsightHttpJourney")
    void TestSmokeConsumerInsightHttpJourney() throws Exception {
        // Подготовка: создаёт компанию и READY-событие SQL-фикстурой; API создания оставляет
        // событие PROCESSING.
        Allure.step(
                "Подготовить компанию и READY-событие SQL-фикстурой",
                () -> {
                    jdbc.update(
                            "INSERT INTO companies (company_id, profile_status, main_industry_code, "
                                    + "main_industry_name, region, has_loan, has_foreign_trade) "
                                    + "VALUES (?, 'AVAILABLE', '62', 'Разработка ПО', 'Москва', true, false)",
                            companyId);
                    jdbc.update(
                            "INSERT INTO events (id, title, source_type, source_ref, status) "
                                    + "VALUES (?, ?, 'TEXT', 'Ставка снижена на 1 п.п.', 'READY')",
                            eventId,
                            eventTitle);
                });
        // Действие: получает токен, проверяет identity и связывает директора с фикстурной
        // компанией.
        String directorToken =
                Allure.step(
                        "Получить токен директора, проверить identity и выдать грант компании",
                        () -> {
                            String token = keycloak.accessToken("director", "director-dev");
                            var meResponse = backend.get("/auth/me", token);
                            assertThat(meResponse.statusCode())
                                    .as("GET /auth/me status; response=%s", meResponse.asString())
                                    .isEqualTo(200);
                            String directorId = json(meResponse).path("id").asText();
                            assertThat(directorId)
                                    .as(
                                            "subject из GET /auth/me; response=%s",
                                            meResponse.asString())
                                    .isNotBlank();
                            jdbc.update(
                                    "INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?)",
                                    directorId,
                                    companyId);
                            return token;
                        });

        // Действие и проверки: находит компанию и читает её контекст через реальные HTTP endpoints.
        Allure.step(
                "Открыть список компаний и контекст выбранной компании",
                () -> {
                    var list = backend.get("/companies?companyId=" + companyId, directorToken);
                    assertThat(list.statusCode())
                            .as("GET /companies status; response=%s", list.asString())
                            .isEqualTo(200);
                    var page = json(list);
                    assertThat(page.path("content").findValuesAsText("companyId"))
                            .as("companyId in GET /companies content; response=%s", page)
                            .contains(companyId);

                    var contextResponse = backend.get("/companies/" + companyId, directorToken);
                    assertThat(contextResponse.statusCode())
                            .as(
                                    "GET company context status; response=%s",
                                    contextResponse.asString())
                            .isEqualTo(200);
                    var context = json(contextResponse);
                    assertThat(context.at("/profile/companyId").asText())
                            .as("profile.companyId; context=%s", context)
                            .isEqualTo(companyId);
                    assertThat(context.at("/profile/mainIndustryCode").asText())
                            .as("profile.mainIndustryCode; context=%s", context)
                            .isEqualTo("62");
                    assertThat(context.at("/profile/hasLoan").isBoolean())
                            .as("profile.hasLoan is a boolean; context=%s", context)
                            .isTrue();
                    assertThat(context.at("/profile/hasLoan").asBoolean())
                            .as("profile.hasLoan; context=%s", context)
                            .isTrue();
                    assertThat(context.path("group").isArray())
                            .as("group is an array; context=%s", context)
                            .isTrue();
                    assertThat(context.path("counterparties").isArray())
                            .as("counterparties is an array; context=%s", context)
                            .isTrue();
                });

        // Действие и проверки: находит READY-событие по заголовку и открывает его карточку.
        Allure.step(
                "Найти READY-событие и открыть его карточку",
                () -> {
                    String encodedTitle = URLEncoder.encode(eventTitle, StandardCharsets.UTF_8);
                    var eventsResponse =
                            backend.get(
                                    "/events?title=" + encodedTitle + "&status=READY",
                                    directorToken);
                    assertThat(eventsResponse.statusCode())
                            .as("GET /events status; response=%s", eventsResponse.asString())
                            .isEqualTo(200);
                    var events = json(eventsResponse).path("content");
                    assertThat(events.findValuesAsText("id"))
                            .as("event IDs in READY list; content=%s", events)
                            .contains(eventId.toString());

                    var eventResponse = backend.get("/events/" + eventId, directorToken);
                    assertThat(eventResponse.statusCode())
                            .as("GET event details status; response=%s", eventResponse.asString())
                            .isEqualTo(200);
                    var event = json(eventResponse);
                    assertThat(event.path("id").asText())
                            .as("event.id; response=%s", event)
                            .isEqualTo(eventId.toString());
                    assertThat(event.path("status").asText())
                            .as("event.status; response=%s", event)
                            .isEqualTo("READY");
                    assertThat(event.path("sourceType").asText())
                            .as("event.sourceType; response=%s", event)
                            .isEqualTo("TEXT");
                });

        String insightPath = "/companies/" + companyId + "/events/" + eventId + "/insight";
        // Действие и проверки: подтверждает отсутствие кэша до генерации.
        Allure.step(
                "Проверить отсутствие сохранённого инсайта до генерации",
                () -> {
                    var missing = backend.get(insightPath, directorToken);
                    assertThat(missing.statusCode())
                            .as("GET insight before generation; response=%s", missing.asString())
                            .isEqualTo(404);
                });

        // Действие и проверки: запускает анализ и обозначает границы тестовых заглушек.
        var generated =
                Allure.step(
                        "Сгенерировать инсайт для READY-события",
                        () -> {
                            var response = backend.postJson(insightPath, directorToken, "{}");
                            assertThat(response.statusCode())
                                    .as("POST insight status; response=%s", response.asString())
                                    .isEqualTo(200);
                            var insight = json(response);
                            assertValidStubInsight(insight);
                            return insight;
                        });

        // Действие и проверки: повторно читает сохранённый результат и сверяет связи в БД.
        Allure.step(
                "Прочитать сохранённый инсайт и подтвердить его ключи",
                () -> {
                    var response = backend.get(insightPath, directorToken);
                    assertThat(response.statusCode())
                            .as("GET cached insight status; response=%s", response.asString())
                            .isEqualTo(200);
                    var cached = json(response);
                    assertThat(cached)
                            .as("cached insight matches generated response; response=%s", cached)
                            .isEqualTo(generated);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM insights WHERE id=? AND company_id=? AND event_id=?",
                                            Integer.class,
                                            UUID.fromString(generated.path("id").asText()),
                                            companyId,
                                            eventId))
                            .as("stored insight FK matches returned ID, company and event")
                            .isEqualTo(1);
                });
    }

    /** Проверяет структуру ответа и явные признаки синтетических LLM/ML данных. */
    private void assertValidStubInsight(com.fasterxml.jackson.databind.JsonNode insight) {
        assertThat(insight.path("id").asText())
                .as("insight.id is a UUID; response=%s", insight)
                .isNotBlank();
        assertThat(insight.path("trace_id").asText())
                .as("insight.trace_id is a UUID; response=%s", insight)
                .isNotBlank();
        assertThat(UUID.fromString(insight.path("id").asText()))
                .as("insight.id parses as UUID; response=%s", insight)
                .isNotNull();
        assertThat(UUID.fromString(insight.path("trace_id").asText()))
                .as("insight.trace_id parses as UUID; response=%s", insight)
                .isNotNull();
        assertThat(insight.path("materials_version").asInt())
                .as("materials_version from READY SQL fixture; response=%s", insight)
                .isEqualTo(1);
        for (String field :
                List.of(
                        "facts",
                        "bank_view",
                        "ai_inference",
                        "hypotheses",
                        "features_used",
                        "risks",
                        "opportunities",
                        "actions",
                        "missing_data",
                        "sources")) {
            assertThat(insight.path(field).isArray())
                    .as("required insight field %s is an array; response=%s", field, insight)
                    .isTrue();
        }
        assertThat(insight.path("bank_view").isEmpty())
                .as("no bank materials are attached to this TEXT fixture; response=%s", insight)
                .isTrue();
        assertThat(insight.path("missing_data").toString())
                .as("stub and missing-bank disclosures; response=%s", insight)
                .contains("[LLM STUB]", "[ML STUB]")
                .contains("банк");
    }
}
