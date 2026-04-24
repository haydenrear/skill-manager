package dev.skillmanager.server.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
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
        // Only /oauth2/authorize should bounce unauthenticated browsers to
        // /login. Matching on media type caught form-encoded POSTs to
        // /oauth2/token (which default to Accept: */*) and redirected them
        // too — broke refresh_token grants.
        http.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new AntPathRequestMatcher("/oauth2/authorize")));
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            PasswordEncoder passwordEncoder,
            @Value("${skill-registry.client.ci.secret:dev-ci-secret-change-me}") String ciSecret,
            @Value("${skill-registry.client.cli.redirect-uri:http://127.0.0.1:8765/callback}")
                    String cliRedirectUri,
            @Value("${skill-registry.client.cli.secret:skill-manager-cli-public}")
                    String cliPublicSecret,
            @Value("${skill-registry.access-token-ttl-seconds:3600}") long accessTokenTtlSeconds,
            @Value("${skill-registry.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds) {
        Duration accessTtl = Duration.ofSeconds(accessTokenTtlSeconds);
        Duration refreshTtl = Duration.ofSeconds(refreshTokenTtlSeconds);

        RegisteredClient ci = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("skill-manager-ci")
                .clientSecret(passwordEncoder.encode(ciSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("skill:publish")
                .scope("ad:manage")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(accessTtl)
                        .build())
                .build();

        // SAS's client_auth=NONE rejects refresh_token grants (PublicClient
        // auth provider is scoped to auth_code-with-PKCE only), so the CLI
        // gets an openly-published "secret" for the auth pipeline. PKCE
        // is what actually binds the token exchange to the original
        // authorize request — the secret is cosmetic.
        RegisteredClient cli = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("skill-manager-cli")
                .clientSecret(passwordEncoder.encode(cliPublicSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(cliRedirectUri)
                .scope("skill:publish")
                .scope("ad:manage")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(accessTtl)
                        .refreshTokenTimeToLive(refreshTtl)
                        // Rotate on every refresh so a stolen token is
                        // usable for at most one subsequent request.
                        .reuseRefreshTokens(false)
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
    public JwtDecoder jwtDecoder(
            KeyStoreProvider keys,
            @Value("${skill-registry.jwt.clock-skew-seconds:60}") long clockSkewSeconds)
            throws java.security.NoSuchAlgorithmException, java.security.spec.InvalidKeySpecException {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.rsaKey().toRSAPublicKey()).build();
            // Spring's default is 60s, which masks short-TTL refresh tests.
            // Knob lets callers dial it to zero for time-based expiry assertions.
            decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    new org.springframework.security.oauth2.jwt.JwtTimestampValidator(
                            Duration.ofSeconds(clockSkewSeconds))));
            return decoder;
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to expose RSA public key for JWT verification", e);
        }
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * Replace SAS's default token generator so public clients (our CLI)
     * also receive refresh tokens on the authorization_code grant.
     *
     * <p>SAS declines refresh tokens to public clients by default (RFC
     * 6749 §10.4). We accept that tradeoff consciously: PKCE binds the
     * auth-code to the original verifier, tokens are cached under
     * {@code auth.token} with owner-only POSIX perms, and every refresh
     * rotates the refresh token. The gain — no hourly re-login for the
     * CLI user — beats the marginal confidentiality loss for this
     * deployment model.
     */
    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(
            JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext> jwtCustomizer) {
        JwtGenerator jwtGen = new JwtGenerator(jwtEncoder);
        jwtGen.setJwtCustomizer(jwtCustomizer);
        OAuth2AccessTokenGenerator accessGen = new OAuth2AccessTokenGenerator();
        OAuth2TokenGenerator<OAuth2RefreshToken> refreshGen = context -> {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
            byte[] buf = new byte[48];
            new SecureRandom().nextBytes(buf);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
            java.time.Instant now = java.time.Instant.now();
            java.time.Duration ttl = context.getRegisteredClient()
                    .getTokenSettings().getRefreshTokenTimeToLive();
            return new OAuth2RefreshToken(value, now, now.plus(ttl));
        };
        return new DelegatingOAuth2TokenGenerator(jwtGen, accessGen, refreshGen);
    }
}
