package alpha.business.insight.core.security;

import alpha.business.insight.core.config.SecurityConfig;
import alpha.business.insight.core.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, SecurityConfigTest.AdminController.class})
@Import({SecurityConfig.class, SecurityConfigTest.AdminController.class})
class SecurityConfigTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    JwtDecoder decoder;

    @Test
    void givenNoToken_whenRequest_thenUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/check")).andExpect(status().isUnauthorized());
    }

    @Test
    void givenInvalidToken_whenRequest_thenUnauthorized() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_DIRECTOR"})
    void givenApplicationRole_whenGetMe_thenReturnIdentity(String role) throws Exception {
        token(role);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-id"))
                .andExpect(jsonPath("$.username").value("demo"))
                .andExpect(jsonPath("$.roles[0]").value(role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER_ROLE"})
    void givenNoApplicationRole_whenGetMe_thenForbidden(String role) throws Exception {
        token(role);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenDirector_whenAdminRequest_thenForbidden() throws Exception {
        token("COMPANY_DIRECTOR");
        mvc.perform(get("/api/admin/check").header("Authorization", "Bearer valid"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAdmin_whenAdminRequest_thenAllowed() throws Exception {
        token("SYSTEM_ADMIN");
        mvc.perform(get("/api/admin/check").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk());
    }

    private void token(String role) {
        var builder = Jwt.withTokenValue("valid").header("alg", "RS256")
                .subject("user-id").claim("preferred_username", "demo");
        if (!role.isEmpty()) {
            builder.claim("roles", List.of(role));
        }
        when(decoder.decode("valid")).thenReturn(builder.build());
    }

    @RestController
    static class AdminController {
        @GetMapping("/api/admin/check")
        public String check() {
            return "ok";
        }
    }
}
