package alpha.business.insight.core.qa.events;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.TestUsers;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Проверяет персональное ранжирование через Java API, PostgreSQL, Keycloak и настоящий Python ML. */
@Epic("Backend")
@Feature("Персональная лента событий")
@Story("Реальное ранжирование событий для компании")
@Tag("e2e")
@Tag("events")
@Tag("ml")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ml.stub=false", "spring.ai.model.chat=none", "news.collect.enabled=false"})
class RankingHttpE2eTest extends RealStackE2eTest {
    private static final String PREFIX = "SYNTHETIC-RANK-" + UUID.randomUUID();
    private static final Path ROOT = projectRoot();
    private static final GenericContainer<?> PYTHON_ML =
            new GenericContainer<>(
                            new ImageFromDockerfile("alfa-business-insights-rank-qa", false)
                                    .withFileFromPath(".", ROOT.resolve("ml")))
                    .withEnv("ML_EMBEDDER", "stub")
                    .withEnv("ML_STUB", "false")
                    .withExposedPorts(8000)
                    .waitingFor(Wait.forHttp("/health").forPort(8000).forStatusCode(200));

    @Autowired private ApplicationContext context;

    private final List<String> companyIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final String suffix = UUID.randomUUID().toString().replace("-", "");

    static {
        PYTHON_ML.start();
    }

    /** Передаёт backend адрес production FastAPI с локальным stub embedder. */
    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry properties) {
        properties.add(
                "ml.url",
                () -> "http://" + PYTHON_ML.getHost() + ":" + PYTHON_ML.getMappedPort(8000));
    }

    @BeforeEach
    void requireLocalModels() {
        assertThat(context.getBeansOfType(ChatModel.class))
                .as("внешняя языковая модель отключена")
                .isEmpty();
    }

    /** Проверяет, что один подъём ставки даёт разные персональные эффекты для кредита и депозита. */
    @Test
    void TestE2ERateEventRanksDifferentlyForLoanAndDepositCompanies() throws Exception {
        String borrower =
                insertCompany("borrower", "01.11", "Выращивание зерновых культур", true, false);
        String depositor =
                insertCompany("depositor", "01.11", "Выращивание зерновых культур", false, true);
        UUID rateEvent =
                insertEvent(
                        "Банк России повысил ключевую ставку до 18 процентов " + suffix,
                        "Банк России повысил ключевую ставку до 18 процентов. "
                                + "Стоимость кредитования и заимствований для бизнеса выросла. "
                                + "Источник: https://cbr.ru/press/key-rate",
                        "READY",
                        "TEXT");
        insertEvent(
                "Открылась выставка современного искусства " + suffix,
                "В Москве открылась выставка современного искусства с работами молодых художников.",
                "READY",
                "TEXT");
        insertEvent(
                "Городской музей представил новую экспозицию " + suffix,
                "Музей представил новую экспозицию о развитии культуры и архитектуры города.",
                "READY",
                "TEXT");
        UUID processingRate =
                insertEvent(
                        "Обработка: Банк России повысил ключевую ставку " + suffix,
                        "Стоимость кредитования выросла.",
                        "PROCESSING",
                        "TEXT");
        UUID urlRate =
                insertEvent(
                        "Ссылка о повышении ключевой ставки " + suffix,
                        "Банк России повысил ключевую ставку. https://cbr.ru/press/key-rate",
                        "READY",
                        "URL");

        Actor borrowerDirector = authenticate(TestUsers.Actor.CREDIT_DIRECTOR);
        Actor depositDirector = authenticate(TestUsers.Actor.TRADE_DIRECTOR);
        grant(borrowerDirector.subject(), borrower);
        grant(depositDirector.subject(), depositor);

        Allure.step(
                "Запросить персональную ленту компаний через HTTP",
                () -> {
                    var borrowerResponse =
                            backend.get(
                                    "/companies/" + borrower + "/events/top",
                                    borrowerDirector.token());
                    assertThat(borrowerResponse.statusCode())
                            .as(borrowerResponse.asString())
                            .isEqualTo(200);
                    JsonNode borrowerItems = json(borrowerResponse);
                    assertTopContract(borrowerItems, rateEvent, "risk", "у компании кредит");
                    assertThat(borrowerItems.findValuesAsText("id"))
                            .doesNotContain(processingRate.toString(), urlRate.toString());

                    var depositResponse =
                            backend.get(
                                    "/companies/" + depositor + "/events/top",
                                    depositDirector.token());
                    assertThat(depositResponse.statusCode())
                            .as(depositResponse.asString())
                            .isEqualTo(200);
                    JsonNode depositItems = json(depositResponse);
                    assertTopContract(
                            depositItems, rateEvent, "opportunity", "у компании депозиты");
                });
    }

    /** Проверяет, что причина влияния ставки у компании приходит через платёжного контрагента. */
    @Test
    void TestE2ERateEventExplainsCounterpartyExposure() throws Exception {
        String company = insertCompany("counterparty", "99", "Прочие услуги", false, false);
        String counterparty =
                insertCompany("auto-supplier", "29.10", "Производство автомобилей", false, false);
        jdbc.update(
                "INSERT INTO company_payment_summaries (payer_company_id, recipient_company_id, total_payment_amount, payment_count) VALUES (?, ?, 900000, 10), (?, ?, 100000, 2)",
                company,
                counterparty,
                counterparty,
                company);
        UUID rateEvent =
                insertEvent(
                        "Банк России повысил ключевую ставку для компаний " + suffix,
                        "Банк России повысил ключевую ставку до 18 процентов. "
                                + "Стоимость кредитования и заимствований для бизнеса выросла. "
                                + "Источник: https://cbr.ru/press/key-rate",
                        "READY",
                        "TEXT");
        Actor director = authenticate(TestUsers.Actor.UNASSIGNED_DIRECTOR);
        grant(director.subject(), company);

        var response = backend.get("/companies/" + company + "/events/top", director.token());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        JsonNode items = json(response);
        JsonNode ranked =
                assertTopContract(items, rateEvent, "unknown", "контрагент (получатель платежей)");
        Allure.addAttachment(
                "Фактическая ранжированная причина", "application/json", ranked.toPrettyString());

        var companyContext = backend.get("/companies/" + company, director.token());
        assertThat(companyContext.statusCode()).as(companyContext.asString()).isEqualTo(200);
        assertThat(json(companyContext).path("counterparties").get(0).path("companyId").asText())
                .isEqualTo(counterparty);
    }

    /** Проверяет форму каждого кандидата и персональные поля выбранного события в HTTP-ответе. */
    private JsonNode assertTopContract(
            JsonNode items, UUID eventId, String effect, String whyFragment) {
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isBetween(1, 3);
        for (JsonNode item : items) {
            assertThat(item.path("id").asText()).isNotBlank();
            assertThat(item.path("title").asText()).isNotBlank();
            assertThat(item.path("sourceType").asText()).isEqualTo("TEXT");
            assertThat(item.path("status").asText()).isEqualTo("READY");
            assertThat(item.path("createdAt").asText()).isNotBlank();
            assertThat(item.path("relevanceScore").isNumber()).isTrue();
            assertThat(item.path("insightGenerated").isBoolean()).isTrue();
            assertThat(item.path("effect").asText()).isNotBlank();
            assertThat(item.path("why").isArray()).isTrue();
        }
        JsonNode match = null;
        for (JsonNode item : items) {
            if (eventId.toString().equals(item.path("id").asText())) {
                match = item;
                break;
            }
        }
        assertThat(match)
                .as(
                        "ранжированный ответ должен содержать созданное READY TEXT событие %s: %s",
                        eventId, items)
                .isNotNull();
        assertThat(match.path("effect").asText()).isEqualTo(effect);
        assertThat(match.path("insightGenerated").asBoolean()).isFalse();
        assertThat(match.path("relevanceScore").asDouble()).isPositive();
        List<String> why = new ArrayList<>();
        match.path("why").forEach(reason -> why.add(reason.asText().toLowerCase(Locale.ROOT)));
        assertThat(why).anyMatch(reason -> reason.contains(whyFragment));
        return match;
    }

    /** Создаёт отдельный профиль компании для сценария. */
    private String insertCompany(
            String label, String code, String industry, boolean hasLoan, boolean hasDeposit) {
        String id = "rank_" + label + "_" + suffix;
        jdbc.update(
                "INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name, has_loan, has_deposit, region) VALUES (?, 'AVAILABLE', ?, ?, ?, ?, 'Москва')",
                id,
                code,
                industry,
                hasLoan,
                hasDeposit);
        companyIds.add(id);
        return id;
    }

    /** Создаёт локальное событие с явно заданным статусом и источником. */
    private UUID insertEvent(String title, String text, String status, String sourceType) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO events (id, title, source_type, source_ref, status) VALUES (?, ?, ?, ?, ?)",
                id,
                PREFIX + " " + title,
                sourceType,
                text,
                status);
        eventIds.add(id);
        return id;
    }

    /** Проверяет JWT директора через реальный HTTP-профиль. */
    private Actor authenticate(TestUsers.Actor user) throws Exception {
        String token = keycloak.accessToken(user.username(), user.password());
        var response = backend.get("/auth/me", token);
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        JsonNode identity = json(response);
        assertThat(identity.path("roles").toString()).contains("COMPANY_DIRECTOR");
        return new Actor(token, identity.path("id").asText());
    }

    /** Выдаёт пользователю доступ только к выбранной тестовой компании. */
    private void grant(String subject, String companyId) {
        jdbc.update(
                "INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?)",
                subject,
                companyId);
    }

    /** Удаляет только выданные тестовые grants, компании, платежи и события. */
    @AfterEach
    void cleanupRankingFixtures() {
        for (UUID eventId : eventIds) jdbc.update("DELETE FROM events WHERE id = ?", eventId);
        for (String companyId : companyIds) {
            jdbc.update(
                    "DELETE FROM company_payment_summaries WHERE payer_company_id = ? OR recipient_company_id = ?",
                    companyId,
                    companyId);
            jdbc.update("DELETE FROM user_company_access WHERE company_id = ?", companyId);
            jdbc.update("DELETE FROM companies WHERE company_id = ?", companyId);
        }
    }

    /** Находит корень checkout для сборки настоящего ML-образа. */
    private static Path projectRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("ml/Dockerfile"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("Не найден корень проекта с ml/Dockerfile");
    }

    private record Actor(String token, String subject) {}
}
