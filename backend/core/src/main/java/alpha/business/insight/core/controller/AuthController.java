package alpha.business.insight.core.controller;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
public class AuthController {

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(JwtAuthenticationToken authentication) {
        String username = authentication.getToken().getClaimAsString("preferred_username");
        return Map.of(
                "id", Objects.requireNonNull(authentication.getToken().getSubject()),
                "username", username != null ? username : authentication.getName(),
                "roles", authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(role -> Objects.requireNonNull(role).startsWith("ROLE_"))
                        .map(role -> role.substring(5))
                        .toList());
    }
}
