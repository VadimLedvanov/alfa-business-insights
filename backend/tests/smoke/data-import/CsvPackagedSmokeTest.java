package alpha.business.insight.core.qa.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Проверяет чтение профиля компании из упакованного CSV через HTTP API. */
@Epic("Backend")
@Feature("Импорт CSV")
@Story("Отдать профиль компании из тестового CSV через API")
@Tag("e2e")
@Tag("smoke")
@Tag("data-import")
class CsvPackagedSmokeTest extends RealStackE2eTest {
    private static final String COMPANY_ID = "U_67";
    private String directorId;
    private boolean ownsTemporaryAccess;

    /** Получает реальный токен директора и проверяет загруженный профиль компании. */
    @Test
    @DisplayName("TestSmokePackagedCsvProfile")
    void TestSmokePackagedCsvProfile() throws Exception {
        String directorToken =
                Allure.step(
                        "Подготовить доступ пользователя к CSV-компании",
                        () -> {
                            String token = keycloak.accessToken("director", "director-dev");
                            directorId = json(backend.get("/auth/me", token)).path("id").asText();
                            ownsTemporaryAccess =
                                    jdbc.update(
                                                    "INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                                                    directorId,
                                                    COMPANY_ID)
                                            == 1;
                            return token;
                        });

        Allure.step(
                "Прочитать и проверить профиль компании из CSV",
                () -> {
                    // Запросить профиль компании.
                    var response = backend.get("/companies/" + COMPANY_ID, directorToken);
                    assertThat(response.statusCode()).isEqualTo(200);
                    var context = json(response);

                    // Сверить поля, загруженные из CSV.
                    assertThat(context.at("/profile/companyId").asText()).isEqualTo(COMPANY_ID);
                    assertThat(context.at("/profile/profileStatus").asText())
                            .isEqualTo("AVAILABLE");
                    assertThat(context.at("/profile/mainIndustryCode").asText()).isEqualTo("46.51");
                    assertThat(context.at("/profile/region").asText())
                            .isEqualTo("Новосибирская область");
                    assertThat(context.at("/group").isArray()).isTrue();
                });
    }

    /** Удаляет временную связь доступа, созданную только этим тестом. */
    @AfterEach
    void removeTemporaryAccess() {
        if (directorId != null && ownsTemporaryAccess) {
            jdbc.update(
                    "DELETE FROM user_company_access WHERE user_id = ? AND company_id = ?",
                    directorId,
                    COMPANY_ID);
        }
    }
}
