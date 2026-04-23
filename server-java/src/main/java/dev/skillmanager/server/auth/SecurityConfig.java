package dev.skillmanager.server.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server filter chain (order 2; {@link AuthorizationServerConfig} owns
 * order 1 and claims {@code /oauth2/**} + {@code /.well-known/**}).
 *
 * <p>Public surface:
 *   GET /health, GET /skills(/...), GET /ads/campaigns(/...)
 *
 * <p>Authenticated surface (JWT bearer, RS256, verified against the JWKS
 * published by the embedded authorization server):
 *   POST /skills/{n}/{v}   publish
 *   DELETE /skills/...     removal
 *   POST /ads/campaigns    ad creation
 *   DELETE /ads/campaigns  ad removal
 *   GET  /auth/me          identity echo
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain http(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET,
                                "/health",
                                "/skills", "/skills/search",
                                "/skills/*", "/skills/*/*", "/skills/*/*/download",
                                "/ads/campaigns", "/ads/campaigns/*",
                                "/ads/campaigns/*/stats"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/skills/*/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/skills/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/ads/campaigns").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/ads/campaigns/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
