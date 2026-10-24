package alpha.business.insight.core.security;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.config.OpenApiConfig;
import alpha.business.insight.core.config.SecurityConfig;
import alpha.business.insight.core.controller.AuthController;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSecurityConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
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

/** Проверяет правила доступа и публичные страницы документации API. */
@WebMvcTest(
        controllers = {AuthController.class, SecurityConfigTest.AdminController.class},
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
        })
@Import({SecurityConfig.class, OpenApiConfig.class, SecurityConfigTest.AdminController.class})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocSecurityConfiguration.class,
    SpringDocWebMvcConfiguration.class,
    SwaggerConfig.class,
    SwaggerUiConfigProperties.class,
    SwaggerUiOAuthProperties.class
})
@Tag("integration")
@Tag("security")
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    /** Проверяет доступность OpenAPI и настройки входа через Keycloak. */
    @Test
    void givenNoToken_whenGetOpenApi_thenReturnApiWithKeycloakLogin() throws Exception {
        request()
                .when()
                .get("/api/v3/api-docs")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Alfa Business Insights API"))
                .body("servers[0].url", equalTo("http://localhost/api"))
                .body("paths.'/auth/me'.get", notNullValue())
                .body("components.securitySchemes.keycloak.type", equalTo("oauth2"))
                .body(
                        "components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl",
                        equalTo("http://localhost/test-realm/protocol/openid-connect/auth"))
                .body(
                        "components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl",
                        equalTo("http://localhost/test-realm/protocol/openid-connect/token"))
                .body("security[0].keycloak[0]", equalTo("openid"));
    }

    /** Проверяет публичные страницы Swagger UI и его настройки. */
    @Test
    void givenNoToken_whenGetSwaggerUi_thenReturnUiAndConfiguration() throws Exception {
        // Действие и проверки публичных ресурсов Swagger UI.
        // Проверяет redirect без автоматического перехода по Location.
        request()
                .when()
                .get("/api/swagger-ui.html")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(300), lessThan(400)))
                .header("Location", equalTo("/api/swagger-ui/index.html"));
        request()
                .when()
                .get("/api/swagger-ui/index.html")
                .then()
                .statusCode(200)
                .body(containsString("Swagger UI"));
        request()
                .when()
                .get("/api/swagger-ui/swagger-initializer.js")
                .then()
                .statusCode(200)
                .body(containsString("\"clientId\":\"alfa-backend\""))
                .body(containsString("\"usePkceWithAuthorizationCodeGrant\":true"))
                .body(containsString("\"additionalQueryStringParams\":{\"prompt\":\"login\"}"));
        request().when().get("/api/swagger-ui/oauth2-redirect.html").then().statusCode(200);
        request()
                .when()
                .get("/api/v3/api-docs/swagger-config")
                .then()
                .statusCode(200)
                .body("url", equalTo("/api/v3/api-docs"));
    }

    /** Проверяет доступность OpenAPI в формате YAML. */
    @Test
    void givenNoToken_whenGetOpenApiYaml_thenReturnSpecification() throws Exception {
        request()
                .when()
                .get("/api/v3/api-docs.yaml")
                .then()
                .statusCode(200)
                .body(containsString("/auth/me:"));
    }

    /** Проверяет отказ в доступе к закрытым точкам без токена. */
    @Test
    void givenNoToken_whenRequest_thenUnauthorized() throws Exception {
        request()
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(401)
                .body("code", equalTo("UNAUTHORIZED"))
                .body("message", equalTo("Требуется авторизация"))
                .body("retryable", equalTo(false));
        request().when().post("/api/admin/check").then().statusCode(401);
    }

    /** Проверяет отказ в доступе при недействительном токене. */
    @Test
    void givenInvalidToken_whenRequest_thenUnauthorized() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        request()
                .header("Authorization", "Bearer invalid")
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(401);
    }

    /** Проверяет идентификатор и роль пользователя для разрешённых ролей. */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenApplicationRole_whenGetMe_thenReturnIdentity(String role) throws Exception {
        // Подготовка
        token(role);

        // Действие и проверки
        request()
                .header("Authorization", "Bearer valid")
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("id", equalTo("user-id"))
                .body("username", equalTo("demo"))
                .body("roles[0]", equalTo(role));
    }

    /** Проверяет отказ, если в токене нет разрешённой роли. */
    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER_ROLE"})
    void givenNoApplicationRole_whenGetMe_thenForbidden(String role) throws Exception {
        // Подготовка
        token(role);

        // Действие и проверки
        request()
                .header("Authorization", "Bearer valid")
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"))
                .body("retryable", equalTo(false));
    }

    /** Проверяет отказ директору при обращении к административной точке. */
    @Test
    void givenDirector_whenAdminRequest_thenForbidden() throws Exception {
        // Подготовка
        token("COMPANY_DIRECTOR");

        // Действие и проверки
        request()
                .header("Authorization", "Bearer valid")
                .when()
                .get("/api/admin/check")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"))
                .body("retryable", equalTo(false));
    }

    /** Проверяет доступ администратора к административной точке. */
    @Test
    void givenAdmin_whenAdminRequest_thenAllowed() throws Exception {
        // Подготовка
        token("SYSTEM_ADMIN");

        // Действие и проверки
        request()
                .header("Authorization", "Bearer valid")
                .when()
                .get("/api/admin/check")
                .then()
                .statusCode(200);
    }

    /** Создаёт request-local спецификацию для Spring MockMvc. */
    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Настраивает JWT-заглушку с указанной прикладной ролью. */
    private void token(String role) {
        Jwt.Builder builder =
                Jwt.withTokenValue("valid")
                        .header("alg", "RS256")
                        .subject("user-id")
                        .claim("preferred_username", "demo");
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
