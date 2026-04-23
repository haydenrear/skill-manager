package dev.skillmanager.server.auth;

import dev.skillmanager.server.persistence.UserAccount;
import dev.skillmanager.server.persistence.UserAccountRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Hooks into {@code /oauth2/token} issuance to upsert a {@link UserAccount}
 * keyed on the token subject and add a mirror {@code username} claim.
 *
 * <p>Works for every grant type the authorization server supports:
 * client_credentials tokens land with {@code sub = client_id}, so the CI
 * client gets its own users row; authorization_code tokens will later
 * land with the human's registered username.
 */
@Component
public class TokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final UserAccountRepository users;

    public TokenCustomizer(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public void customize(JwtEncodingContext ctx) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) return;
        String subject = ctx.getPrincipal().getName();
        if (subject == null || subject.isBlank()) return;
        users.findById(subject).orElseGet(() -> users.save(new UserAccount(subject, subject, null)));
        ctx.getClaims().claim("username", subject);
    }
}
