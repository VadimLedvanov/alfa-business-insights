package alpha.business.insight.core.security;

import alpha.business.insight.core.config.OpenApiConfig;
import alpha.business.insight.core.config.SecurityConfig;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSecurityConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проверяет правила доступа и публичные страницы документации API. */
@WebMvcTest(controllers = {AuthController.class, SecurityConfigTest.AdminController.class}, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@Import({SecurityConfig.class, OpenApiConfig.class, SecurityConfigTest.AdminController.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocSecurityConfiguration.class, SpringDocWebMvcConfiguration.class, SwaggerConfig.class,
        SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
@Tag("integration")
@Tag("security")
class SecurityConfigTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    JwtDecoder decoder;

    /** Проверяет доступность OpenAPI и настройки входа через Keycloak. */
    @Test
    void givenNoToken_whenGetOpenApi_thenReturnApiWithKeycloakLogin() throws Exception {
        request().when().get("/api/v3/api-docs").then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$.info.title").value("Alfa Business Insights API"))
                .assertThat(jsonPath("$.servers[0].url").value("http://localhost/api"))
                .assertThat(jsonPath("$.paths['/auth/me'].get").exists())
                .assertThat(jsonPath("$.components.securitySchemes.keycloak.type").value("oauth2"))
                .assertThat(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl")
                        .value("http://localhost/test-realm/protocol/openid-connect/auth"))
                .assertThat(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl")
                        .value("http://localhost/test-realm/protocol/openid-connect/token"))
                .assertThat(jsonPath("$.security[0].keycloak[0]").value("openid"));
    }

    /** Проверяет публичные страницы Swagger UI и его настройки. */
    @Test
    void givenNoToken_whenGetSwaggerUi_thenReturnUiAndConfiguration() throws Exception {
        // Действие и проверки публичных ресурсов Swagger UI.
        request().when().get("/api/swagger-ui.html").then()
                .assertThat(status().is3xxRedirection())
                .assertThat(redirectedUrl("/api/swagger-ui/index.html"));
        request().when().get("/api/swagger-ui/index.html").then()
                .assertThat(status().isOk())
                .assertThat(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        request().when().get("/api/swagger-ui/swagger-initializer.js").then()
                .assertThat(status().isOk())
                .assertThat(content().string(org.hamcrest.Matchers.containsString("\"clientId\":\"alfa-backend\"")))
                .assertThat(content().string(org.hamcrest.Matchers.containsString("\"usePkceWithAuthorizationCodeGrant\":true")))
                .assertThat(content().string(org.hamcrest.Matchers.containsString("\"additionalQueryStringParams\":{\"prompt\":\"login\"}")));
        request().when().get("/api/swagger-ui/oauth2-redirect.html").then().assertThat(status().isOk());
        request().when().get("/api/v3/api-docs/swagger-config").then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$.url").value("/api/v3/api-docs"));
    }

    /** Проверяет доступность OpenAPI в формате YAML. */
    @Test
    void givenNoToken_whenGetOpenApiYaml_thenReturnSpecification() throws Exception {
        request().when().get("/api/v3/api-docs.yaml").then()
                .assertThat(status().isOk())
                .assertThat(content().string(org.hamcrest.Matchers.containsString("/auth/me:")));
    }

    /** Проверяет отказ в доступе к закрытым точкам без токена. */
    @Test
    void givenNoToken_whenRequest_thenUnauthorized() throws Exception {
        request().when().get("/api/auth/me").then().assertThat(status().isUnauthorized());
        request().when().post("/api/admin/check").then().assertThat(status().isUnauthorized());
    }

    /** Проверяет отказ в доступе при недействительном токене. */
    @Test
    void givenInvalidToken_whenRequest_thenUnauthorized() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        request().header("Authorization", "Bearer invalid").when().get("/api/auth/me").then()
                .assertThat(status().isUnauthorized());
    }

    /** Проверяет идентификатор и роль пользователя для разрешённых ролей. */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenApplicationRole_whenGetMe_thenReturnIdentity(String role) throws Exception {
        // Подготовка
        token(role);

        // Действие и проверки
        request().header("Authorization", "Bearer valid").when().get("/api/auth/me").then()
                .assertThat(status().isOk())
                .assertThat(jsonPath("$.id").value("user-id"))
                .assertThat(jsonPath("$.username").value("demo"))
                .assertThat(jsonPath("$.roles[0]").value(role));
    }

    /** Проверяет отказ, если в токене нет разрешённой роли. */
    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER_ROLE"})
    void givenNoApplicationRole_whenGetMe_thenForbidden(String role) throws Exception {
        // Подготовка
        token(role);

        // Действие и проверки
        request().header("Authorization", "Bearer valid").when().get("/api/auth/me").then()
                .assertThat(status().isForbidden());
    }

    /** Проверяет отказ директору при обращении к административной точке. */
    @Test
    void givenDirector_whenAdminRequest_thenForbidden() throws Exception {
        // Подготовка
        token("COMPANY_DIRECTOR");

        // Действие и проверки
        request().header("Authorization", "Bearer valid").when().get("/api/admin/check").then()
                .assertThat(status().isForbidden());
    }

    /** Проверяет доступ администратора к административной точке. */
    @Test
    void givenAdmin_whenAdminRequest_thenAllowed() throws Exception {
        // Подготовка
        token("SYSTEM_ADMIN");

        // Действие и проверки
        request().header("Authorization", "Bearer valid").when().get("/api/admin/check").then()
                .assertThat(status().isOk());
    }

    /** Создаёт request-local спецификацию для Spring MockMvc. */
    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Настраивает JWT-заглушку с указанной прикладной ролью. */
    private void token(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("valid").header("alg", "RS256")
                .subject("user-id").claim("preferred_username", "demo");
        if (!role.isEmpty()) {
            builder.claim("roles", List.of(role));
        }
        when(decoder.decode("valid")).thenReturn(builder.build());
    }

    /** Добавляет закрытую административную точку для проверки правил безопасности. */
    @RestController
    static class AdminController {
        /** Возвращает короткий ответ после успешной авторизации администратора. */
        @GetMapping("/admin/check")
        public String check() {
            return "ok";
        }
    }
}
