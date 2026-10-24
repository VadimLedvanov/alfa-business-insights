package alpha.business.insight.core.qa.security;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет реальную аутентификацию и роли пользователей через HTTP API. */
@Epic("Backend")
@Feature("Аутентификация и роли")
@Story("Проверить реальные токены Keycloak и роли realm")
@Tag("e2e")
@Tag("smoke")
@Tag("security")
class BackendAuthorizationSmokeTest extends RealStackE2eTest {
    /** Проверяет отказ анониму и роли директора и системного администратора. */
    @Test
    @DisplayName("TestSmokeKeycloakRealmRoles")
    void TestSmokeKeycloakRealmRoles() throws Exception {
        Allure.step("Проверить отказ анонимному пользователю", () ->
                assertThat(backend.get("/auth/me", null).statusCode()).isEqualTo(401));

        Allure.step("Проверить роль директора", () -> {
            // Действие
            var director = json(backend.get("/auth/me", keycloak.accessToken("director", "director-dev")));

            // Проверка
            assertThat(director.path("username").asText()).isEqualTo("director");
            assertThat(director.path("roles").toString()).contains("COMPANY_DIRECTOR");
        });

        Allure.step("Проверить роль системного администратора", () -> {
            // Действие
            var admin = json(backend.get("/auth/me", keycloak.accessToken("sysadmin", "sysadmin-dev")));
            assertThat(admin.path("roles").toString()).contains("SYSTEM_ADMIN");
        });
    }
}
