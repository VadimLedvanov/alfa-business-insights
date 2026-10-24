package alpha.business.insight.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                                "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/admin/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/events").hasRole("SYSTEM_ADMIN")
                        .anyRequest().hasAnyRole("SYSTEM_ADMIN", "COMPANY_DIRECTOR"))
                .oauth2ResourceServer(server ->
                        server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
