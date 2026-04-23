package dev.skillmanager.server.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Duration;
import java.util.UUID;

/**
 * Embedded Spring Authorization Server.
 *
 * <p>We sign JWTs with an RSA keypair loaded from {@link KeyStoreProvider}; the
 * matching public key is published at {@code /oauth2/jwks}, and the same
 * {@link JWKSource} backs this app's resource-server {@link JwtDecoder}, so
 * tokens minted at {@code /oauth2/token} validate on the same pass through
 * the resource server's filter chain.
 *
 * <p>Registered clients:
 * <ul>
 *   <li>{@code skill-manager-ci} — confidential, {@code client_credentials}.
 *       Internal-only handle for test graphs and headless CI. Secret lives
 *       in {@code SKILL_REGISTRY_CI_SECRET}; never documented or exposed in
 *       end-user tooling.</li>
 *   <li>{@code skill-manager-cli} — public (no secret), PKCE-required,
 *       {@code authorization_code} + {@code refresh_token}. This is what
 *       {@code skill-manager login} uses: opens the browser against
 *       {@code /oauth2/authorize}, receives the code on a loopback redirect,
 *       exchanges it at {@code /oauth2/token}.</li>
 * </ul>
 *
 * <p>By acting as our own IdP we keep the dev loop self-contained — no
 * external provider, no outbound network during tests, no leaked prod
 * tokens from exploratory work.
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        // Unauthenticated HTML requests to /oauth2/authorize need to land on
        // /login so the user can sign in; API clients (JSON) still get 401.
        http.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            PasswordEncoder passwordEncoder,
            @Value("${skill-registry.client.ci.secret:dev-ci-secret-change-me}") String ciSecret,
            @Value("${skill-registry.client.cli.redirect-uri:http://127.0.0.1:8765/callback}")
                    String cliRedirectUri) {
        RegisteredClient ci = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("skill-manager-ci")
                .clientSecret(passwordEncoder.encode(ciSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("skill:publish")
                .scope("ad:manage")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();

        RegisteredClient cli = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("skill-manager-cli")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(cliRedirectUri)
                .scope("skill:publish")
                .scope("ad:manage")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(ci, cli);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(KeyStoreProvider keys) {
        return new ImmutableJWKSet<>(new JWKSet(keys.rsaKey()));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwks) {
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    public JwtDecoder jwtDecoder(KeyStoreProvider keys) throws java.security.NoSuchAlgorithmException,
            java.security.spec.InvalidKeySpecException {
        try {
            return NimbusJwtDecoder.withPublicKey(keys.rsaKey().toRSAPublicKey()).build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to expose RSA public key for JWT verification", e);
        }
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }
}
