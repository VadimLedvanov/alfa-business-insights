package alpha.business.insight.core.qa.company;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет авторизацию директора для компании без явной связи доступа. */
@Epic("Backend")
@Feature("Контекст компании")
@Tag("e2e")
@Tag("company-context")
class CompanyContextHttpE2eTest extends RealStackE2eTest {
    private final String companyId = "e2e_" + UUID.randomUUID().toString().replace("-", "");

    /** Проверяет, что директор без разрешения не читает чужую компанию. */
    @Test
    @Tag("known-defect")
    @Story("Ограничить доступ к компании связью пользователя")
    @DisplayName("TestE2ECompanyAccessRequiresGrant")
    void TestE2ECompanyAccessRequiresGrant() throws Exception {
        Allure.step("Подготовить компанию без профиля и связи доступа", () ->
                jdbc.update("INSERT INTO companies (company_id, profile_status) VALUES (?, 'MISSING')", companyId));
        Allure.step("Проверить запрет доступа директора без связи с компанией", () -> {
            // Подготовить директора без связи доступа к компании.
            String directorToken = keycloak.accessToken("director", "director-dev");
            String userId = json(backend.get("/auth/me", directorToken)).path("id").asText();
            // Оставить для этой компании пустую таблицу user_company_access.
            // Действие
            var response = backend.get("/companies/" + companyId, directorToken);

            // Проверка
            assertThat(response.statusCode())
                    .as("director %s has no user_company_access row for %s (auth subject %s); the vision requires company-scoped authorization",
                            "director", companyId, userId)
                    .isEqualTo(403);
        });
    }

    /** Удаляет синтетические строки компании и связи доступа после теста. */
    @AfterEach
    void removeSyntheticRows() {
        jdbc.update("DELETE FROM user_company_access WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM companies WHERE company_id = ?", companyId);
    }
}
