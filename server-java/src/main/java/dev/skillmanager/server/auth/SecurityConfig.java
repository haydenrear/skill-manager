package dev.skillmanager.server.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chains in priority order (highest first):
 *
 * <ol>
 *   <li>{@link AuthorizationServerConfig#authServerFilterChain} (Order 1) —
 *       Spring Authorization Server's own matchers: {@code /oauth2/**},
 *       {@code /.well-known/**}, {@code /userinfo}, etc. Unauthenticated
 *       requests to {@code /oauth2/authorize} fall through to the login
 *       chain via the shared HTTP session.</li>
 *   <li>{@link #browserLoginChain} (Order 2) — form login at {@code /login}
 *       plus anonymous {@code POST /auth/register}. This is the side of
 *       the app a human browser sees; {@link UserAccountDetailsService}
 *       backs the authentication manager.</li>
 *   <li>{@link #resourceServerChain} (Order 3) — stateless JWT bearer for
 *       the rest of the API surface. Mutating endpoints require auth;
 *       read-only lookups stay public.</li>
 * </ol>
 *
 * <p>The split matters because the resource-server chain is stateless and
 * the browser-login chain is session-based; collapsing them would either
 * force CSRF + sessions on API clients or lose form-login entirely.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain browserLoginChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/login", "/logout", "/error", "/auth/register", "/auth/password-reset/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers("/auth/register", "/auth/password-reset/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers("/auth/password-reset/**").permitAll()
                        .requestMatchers("/login", "/logout", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain resourceServerChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
