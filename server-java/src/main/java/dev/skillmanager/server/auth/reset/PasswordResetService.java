package dev.skillmanager.server.auth.reset;

import dev.skillmanager.server.persistence.PasswordResetToken;
import dev.skillmanager.server.persistence.PasswordResetTokenRepository;
import dev.skillmanager.server.persistence.UserAccount;
import dev.skillmanager.server.persistence.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Lifecycle of a password-reset token:
 *
 * <ol>
 *   <li>{@link #request(String, String)} — if the email matches a known
 *       user, mints a cryptographically random token, persists it with a
 *       30-minute TTL, and emails the user a link containing it. Returns
 *       silently regardless so callers can't probe for valid emails.</li>
 *   <li>{@link #lookup(String)} — verifies a token is live (not expired,
 *       not used). Used by the GET form to show a useful error before
 *       the user types a new password.</li>
 *   <li>{@link #consume(String, String)} — re-verifies, updates the
 *       user's password hash, stamps {@code used_at} so the token is
 *       single-use.</li>
 * </ol>
 *
 * <p>Min password length mirrors {@code RegisterController} so resets
 * can't downgrade the account below signup strength.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserAccountRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final SecureRandom rng = new SecureRandom();

    public PasswordResetService(UserAccountRepository users,
                                PasswordResetTokenRepository tokens,
                                PasswordEncoder encoder,
                                MailService mail) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mail = mail;
    }

    @Transactional
    public void request(String email, String confirmBaseUrl) {
        if (email == null || email.isBlank()) return;
        Optional<UserAccount> match = users.findAll().stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .findFirst();
        if (match.isEmpty()) {
            log.info("password-reset requested for unknown email={} — dropping silently", email);
            return;
        }
        UserAccount user = match.get();
        String raw = randomUrlsafe(32);
        tokens.save(new PasswordResetToken(raw, user.getUsername(),
                Instant.now().plus(TTL)));

        String link = confirmBaseUrl + "/auth/password-reset/confirm?token=" + raw;
        String body = "Hi " + (user.getDisplayName() == null ? user.getUsername() : user.getDisplayName()) + ",\n\n"
                + "Someone (hopefully you) requested a password reset for your skill-manager account.\n\n"
                + "Click the link below within the next 30 minutes to pick a new password:\n\n"
                + "  " + link + "\n\n"
                + "If you didn't request this, ignore this email — the link expires on its own.\n";
        mail.send(user.getEmail(), "Reset your skill-manager password", body);
    }

    public Optional<PasswordResetToken> lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return tokens.findById(rawToken)
                .filter(t -> t.getUsedAt() == null && Instant.now().isBefore(t.getExpiresAt()));
    }

    @Transactional
    public boolean consume(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        PasswordResetToken token = lookup(rawToken).orElse(null);
        if (token == null) return false;

        UserAccount user = users.findById(token.getUsername()).orElse(null);
        if (user == null) return false;

        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);
        token.markUsed(Instant.now());
        tokens.save(token);
        return true;
    }

    private String randomUrlsafe(int bytes) {
        byte[] buf = new byte[bytes];
        rng.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
