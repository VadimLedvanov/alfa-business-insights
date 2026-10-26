package alpha.business.insight.core.qa.company;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.EventCatalog;
import alpha.business.insight.core.qa.helpers.TestUsers;
import alpha.business.insight.core.qa.support.OfflineEventsE2eTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Проверяет отдельные профили и права реальных тестовых пользователей через HTTP. */
@Epic("Backend")
@Feature("Контекст компании")
@Tag("e2e")
@Tag("company-context")
class CompanyUsersHttpE2eTest extends OfflineEventsE2eTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String creditCompany = "e2e_credit_" + suffix;
    private final String tradeCompany = "e2e_trade_" + suffix;
    private final String unassignedCompany = "e2e_unassigned_" + suffix;
    private ActorSession creditDirector;
    private ActorSession tradeDirector;
    private ActorSession unassignedDirector;
    private UUID processingEventId;
    private String processingEventTitle;
    private String processingEventSourceRef;

    /** Создаёт разные профили и выдаёт grants только двум соответствующим пользователям. */
    @BeforeEach
    void prepareCompaniesAndUsers() throws Exception {
        insertCompany(creditCompany, "64.19", "Денежное посредничество", true, false, null);
        insertCompany(
                tradeCompany,
                "46.90",
                "Неспециализированная оптовая торговля",
                false,
                true,
                "Китай");
        insertCompany(
                unassignedCompany, "01.11", "Выращивание зерновых культур", false, false, null);

        Allure.step(
                "1. Получить JWT трёх директоров и проверить идентичность",
                () -> {
                    creditDirector = authenticate(TestUsers.Actor.CREDIT_DIRECTOR);
                    tradeDirector = authenticate(TestUsers.Actor.TRADE_DIRECTOR);
                    unassignedDirector = authenticate(TestUsers.Actor.UNASSIGNED_DIRECTOR);
                    assertThat(
                                    Set.of(
                                            creditDirector.subject(),
                                            tradeDirector.subject(),
                                            unassignedDirector.subject()))
                            .as("subject трёх отдельных пользователей Keycloak")
                            .hasSize(3);
                });

        jdbc.update(
                "INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?), (?, ?)",
                creditDirector.subject(),
                creditCompany,
                tradeDirector.subject(),
                tradeCompany);
    }

    /** Удаляет grants и только компании, созданные текущим тестом. */
    @AfterEach
    void removeSyntheticRows() {
        if (processingEventId != null) {
            jdbc.update("DELETE FROM events WHERE id = ?", processingEventId);
        }
        if (processingEventTitle != null && processingEventSourceRef != null) {
            jdbc.update(
                    "DELETE FROM events WHERE title = ? AND source_ref = ?",
                    processingEventTitle,
                    processingEventSourceRef);
        }
        jdbc.update(
                "DELETE FROM user_company_access WHERE company_id IN (?, ?, ?)",
                creditCompany,
                tradeCompany,
                unassignedCompany);
        jdbc.update(
                "DELETE FROM companies WHERE company_id IN (?, ?, ?)",
                creditCompany,
                tradeCompany,
                unassignedCompany);
    }

    /** Проверяет, что директор с grant видит собственный кредитный профиль. */
    @Test
    @Story("Изолировать контекст компании по grant пользователя")
    @DisplayName("TestE2ECreditDirectorReadsGrantedCompanyProfile")
    void TestE2ECreditDirectorReadsGrantedCompanyProfile() throws Exception {
        Allure.step(
                "2. Прочитать профиль компании кредитного директора",
                () -> {
                    Response response =
                            backend.get("/companies/" + creditCompany, creditDirector.token());
                    assertThat(response.statusCode())
                            .as("GET профиля кредитной компании; response=%s", response.asString())
                            .isEqualTo(200);
                    JsonNode profile = json(response).path("profile");
                    assertThat(profile.path("companyId").asText()).isEqualTo(creditCompany);
                    assertThat(profile.path("hasLoan").isBoolean()).isTrue();
                    assertThat(profile.path("hasLoan").asBoolean()).isTrue();
                    assertThat(profile.path("hasForeignTrade").isBoolean()).isTrue();
                    assertThat(profile.path("hasForeignTrade").asBoolean()).isFalse();
                });
        Allure.step(
                "3. Проверить доступ к HTTP-ленте компании с выданным grant",
                () -> {
                    Response response =
                            backend.get(
                                    "/companies/" + creditCompany + "/events/top",
                                    creditDirector.token());
                    assertThat(response.statusCode())
                            .as("GET ленты кредитной компании; response=%s", response.asString())
                            .isEqualTo(200);
                    assertThat(json(response).isArray()).isTrue();
                });
    }

    /** Проверяет, что директор с grant видит собственный профиль ВЭД. */
    @Test
    @Story("Изолировать контекст компании по grant пользователя")
    @DisplayName("TestE2ETradeDirectorReadsGrantedCompanyProfile")
    void TestE2ETradeDirectorReadsGrantedCompanyProfile() throws Exception {
        Allure.step(
                "2. Прочитать профиль компании директора по внешней торговле",
                () -> {
                    Response response =
                            backend.get("/companies/" + tradeCompany, tradeDirector.token());
                    assertThat(response.statusCode())
                            .as("GET профиля торговой компании; response=%s", response.asString())
                            .isEqualTo(200);
                    JsonNode profile = json(response).path("profile");
                    assertThat(profile.path("companyId").asText()).isEqualTo(tradeCompany);
                    assertThat(profile.path("hasLoan").isBoolean()).isTrue();
                    assertThat(profile.path("hasLoan").asBoolean()).isFalse();
                    assertThat(profile.path("hasForeignTrade").isBoolean()).isTrue();
                    assertThat(profile.path("hasForeignTrade").asBoolean()).isTrue();
                    assertThat(profile.path("foreignTradeCountries").asText()).isEqualTo("Китай");
                });
        Allure.step(
                "3. Проверить доступ к HTTP-ленте компании с выданным grant",
                () -> {
                    Response response =
                            backend.get(
                                    "/companies/" + tradeCompany + "/events/top",
                                    tradeDirector.token());
                    assertThat(response.statusCode())
                            .as("GET ленты торговой компании; response=%s", response.asString())
                            .isEqualTo(200);
                    assertThat(json(response).isArray()).isTrue();
                });
    }

    /** Отдельно проверяет отказ в профиле и HTTP-топе для каждой пары пользователя и компании. */
    @ParameterizedTest
    @CsvSource({
        "CREDIT_DIRECTOR, trade, profile",
        "CREDIT_DIRECTOR, trade, top",
        "TRADE_DIRECTOR, credit, profile",
        "TRADE_DIRECTOR, credit, top",
        "UNASSIGNED_DIRECTOR, unassigned, profile",
        "UNASSIGNED_DIRECTOR, unassigned, top"
    })
    @Story("Ограничить доступ к компании выданным grant")
    @DisplayName("TestE2EDirectorCannotReadCompanyWithoutGrant")
    void TestE2EDirectorCannotReadCompanyWithoutGrant(
            String actorName, String companyName, String resource) {
        Allure.step(
                "2. Запросить профиль или ленту компании без grant",
                () -> {
                    ActorSession actor = actorSession(actorName);
                    String company = companyId(companyName);
                    String path = "/companies/" + company;
                    if (resource.equals("top")) {
                        path += "/events/top";
                    }
                    Response response = backend.get(path, actor.token());
                    assertThat(response.statusCode())
                            .as(
                                    "%s не имеет grant на %s (%s); response=%s",
                                    actorName, company, resource, response.asString())
                            .isEqualTo(403);
                });
    }

    /** Проверяет, что новое событие в состоянии PROCESSING не входит в ленту готовых событий. */
    @Test
    @Story("Показывать только готовые события компании")
    @DisplayName("TestE2EProcessingEventIsExcludedFromCompanyTop")
    void TestE2EProcessingEventIsExcludedFromCompanyTop() throws Exception {
        Allure.step(
                "2. Создать событие через HTTP от имени администратора",
                () -> {
                    processingEventTitle =
                            EventCatalog.SYNTHETIC_MARKER + " PROCESSING_E2E_" + suffix;
                    processingEventSourceRef =
                            EventCatalog.SYNTHETIC_MARKER
                                    + " PROCESSING_E2E_"
                                    + suffix
                                    + "\nКредитные условия и денежное посредничество";
                    String body = jsonString(processingEventTitle, processingEventSourceRef);
                    Response response =
                            backend.postJson(
                                    "/events",
                                    keycloak.accessToken(
                                            TestUsers.Actor.SYSTEM_ADMIN.username(),
                                            TestUsers.Actor.SYSTEM_ADMIN.password()),
                                    body);
                    assertThat(response.statusCode())
                            .as("создание PROCESSING события; response=%s", response.asString())
                            .isEqualTo(201);
                    JsonNode created = json(response);
                    processingEventId = UUID.fromString(created.path("id").asText());
                    assertThat(created.path("status").asText())
                            .as("новое событие должно оставаться в состоянии обработки")
                            .isEqualTo("PROCESSING");
                });
        Allure.step(
                "3. Проверить HTTP-топ компании директора по кредитам",
                () -> {
                    Response response =
                            backend.get(
                                    "/companies/" + creditCompany + "/events/top",
                                    creditDirector.token());
                    assertThat(response.statusCode())
                            .as("GET топа компании; response=%s", response.asString())
                            .isEqualTo(200);
                    JsonNode candidates = json(response);
                    assertThat(candidates.isArray())
                            .as("GET топа должен вернуть массив; response=%s", response.asString())
                            .isTrue();
                    assertThat(candidates.size())
                            .as("компактный топ ограничен тремя событиями")
                            .isLessThanOrEqualTo(3);
                    assertThat(candidates.findValuesAsText("id"))
                            .as("идентификатор PROCESSING-события в HTTP-топе")
                            .doesNotContain(processingEventId.toString());
                });
    }

    /** Получает реальный токен и проверяет subject, имя и роль через HTTP /auth/me. */
    private ActorSession authenticate(TestUsers.Actor actor) throws Exception {
        String token = keycloak.accessToken(actor.username(), actor.password());
        Response response = backend.get("/auth/me", token);
        assertThat(response.statusCode())
                .as("GET /auth/me для %s; response=%s", actor.username(), response.asString())
                .isEqualTo(200);
        JsonNode identity = json(response);
        assertThat(identity.path("username").asText()).isEqualTo(actor.username());
        JsonNode rolesNode = identity.path("roles");
        assertThat(rolesNode.isArray()).as("/auth/me.roles должен быть массивом").isTrue();
        List<String> roles = new ArrayList<>();
        rolesNode.forEach(role -> roles.add(role.asText()));
        assertThat(roles).contains("COMPANY_DIRECTOR").doesNotContain("SYSTEM_ADMIN");
        String subject = identity.path("id").asText();
        assertThat(subject).as("JWT subject для %s", actor.username()).isNotBlank();
        return new ActorSession(token, subject);
    }

    private ActorSession actorSession(String name) {
        return switch (name) {
            case "CREDIT_DIRECTOR" -> creditDirector;
            case "TRADE_DIRECTOR" -> tradeDirector;
            case "UNASSIGNED_DIRECTOR" -> unassignedDirector;
            default -> throw new IllegalArgumentException("Неизвестный тестовый actor: " + name);
        };
    }

    private String companyId(String name) {
        return switch (name) {
            case "credit" -> creditCompany;
            case "trade" -> tradeCompany;
            case "unassigned" -> unassignedCompany;
            default -> throw new IllegalArgumentException("Неизвестный тестовый профиль: " + name);
        };
    }

    private String jsonString(String title, String sourceRef) throws Exception {
        return JSON.writeValueAsString(
                java.util.Map.of(
                        "title", title,
                        "sourceType", "TEXT",
                        "sourceRef", sourceRef));
    }

    /** Создаёт изолированный профиль с указанными продуктовыми признаками. */
    private void insertCompany(
            String companyId,
            String industryCode,
            String industryName,
            boolean hasLoan,
            boolean hasForeignTrade,
            String foreignTradeCountries) {
        jdbc.update(
                """
                INSERT INTO companies (
                    company_id, profile_status, main_industry_code, main_industry_name,
                    has_loan, has_foreign_trade, foreign_trade_countries, region
                ) VALUES (?, 'AVAILABLE', ?, ?, ?, ?, ?, 'Москва')
                """,
                companyId,
                industryCode,
                industryName,
                hasLoan,
                hasForeignTrade,
                foreignTradeCountries);
    }

    private record ActorSession(String token, String subject) {}
}
