package alpha.business.insight.core.security;

import alpha.business.insight.core.config.SecurityConfig;
import alpha.business.insight.core.config.OpenApiConfig;
import alpha.business.insight.core.controller.AuthController;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, SecurityConfigTest.AdminController.class}, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@Import({SecurityConfig.class, OpenApiConfig.class, SecurityConfigTest.AdminController.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocSecurityConfiguration.class, SpringDocWebMvcConfiguration.class, SwaggerConfig.class,
        SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
class SecurityConfigTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    JwtDecoder decoder;

    @Test
    void givenNoToken_whenGetOpenApi_thenReturnApiWithKeycloakLogin() throws Exception {
        mvc.perform(apiGet("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Alfa Business Insights API"))
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost/api"))
                .andExpect(jsonPath("$.paths['/auth/me'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.type").value("oauth2"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl")
                        .value("http://localhost/test-realm/protocol/openid-connect/auth"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl")
                        .value("http://localhost/test-realm/protocol/openid-connect/token"))
                .andExpect(jsonPath("$.security[0].keycloak[0]").value("openid"));
    }

    @Test
    void givenNoToken_whenGetSwaggerUi_thenReturnUiAndConfiguration() throws Exception {
        mvc.perform(apiGet("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api/swagger-ui/index.html"));
        mvc.perform(apiGet("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(apiGet("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"clientId\":\"alfa-backend\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"usePkceWithAuthorizationCodeGrant\":true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"additionalQueryStringParams\":{\"prompt\":\"login\"}")));
        mvc.perform(apiGet("/swagger-ui/oauth2-redirect.html")).andExpect(status().isOk());
        mvc.perform(apiGet("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/v3/api-docs"));
    }

    @Test
    void givenNoToken_whenGetOpenApiYaml_thenReturnSpecification() throws Exception {
        mvc.perform(apiGet("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/auth/me:")));
    }

    @Test
    void givenNoToken_whenRequest_thenUnauthorized() throws Exception {
        mvc.perform(apiGet("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(apiPost("/admin/check")).andExpect(status().isUnauthorized());
    }

    @Test
    void givenInvalidToken_whenRequest_thenUnauthorized() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(apiGet("/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenApplicationRole_whenGetMe_thenReturnIdentity(String role) throws Exception {
        token(role);
        mvc.perform(apiGet("/auth/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-id"))
                .andExpect(jsonPath("$.username").value("demo"))
                .andExpect(jsonPath("$.roles[0]").value(role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER_ROLE"})
    void givenNoApplicationRole_whenGetMe_thenForbidden(String role) throws Exception {
        token(role);
        mvc.perform(apiGet("/auth/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenDirector_whenAdminRequest_thenForbidden() throws Exception {
        token("COMPANY_DIRECTOR");
        mvc.perform(apiGet("/admin/check").header("Authorization", "Bearer valid"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAdmin_whenAdminRequest_thenAllowed() throws Exception {
        token("SYSTEM_ADMIN");
        mvc.perform(apiGet("/admin/check").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder apiGet(String path) {
        return get("/api" + path).contextPath("/api");
    }

    private static MockHttpServletRequestBuilder apiPost(String path) {
        return post("/api" + path).contextPath("/api");
    }

    private void token(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("valid").header("alg", "RS256")
                .subject("user-id").claim("preferred_username", "demo");
        if (!role.isEmpty()) {
            builder.claim("roles", List.of(role));
        }
        when(decoder.decode("valid")).thenReturn(builder.build());
    }

    @RestController
    static class AdminController {
        @GetMapping("/admin/check")
        public String check() {
            return "ok";
        }
    }
}
