package dev.skillmanager.server.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
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
 *       This is what the test graph (and any headless CI) uses for automated
 *       publishing / campaign CRUD.</li>
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
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${skill-registry.client.ci.secret:dev-ci-secret-change-me}") String ciSecret) {
        RegisteredClient ci = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("skill-manager-ci")
                .clientSecret("{noop}" + ciSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("skill:publish")
                .scope("ad:manage")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(ci);
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
