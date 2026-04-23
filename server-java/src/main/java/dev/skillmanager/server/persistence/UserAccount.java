package dev.skillmanager.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A registered user of the skill registry. Created on first login against
 * the embedded authorization server (or, during bootstrap, the dev-token
 * endpoint).
 *
 * <p>Intentionally thin. We key on {@code username} — the identity the
 * authorization server puts in the JWT subject claim — because that's the
 * handle we'll hang every subsequent row off of (campaigns, publishes,
 * impressions).
 */
@Entity
@Table(name = "users")
public class UserAccount {

    /** Stable identifier we use in JWT subject claims and FK references. */
    @Id
    @Column(name = "username", length = 64, nullable = false)
    private String username;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "email", length = 320)
    private String email;

    /**
     * BCrypt hash of the user's login password. {@code null} for machine
     * accounts (rows created by {@code TokenCustomizer} on a
     * client_credentials issuance) — {@link UserAccountDetailsService}
     * refuses to treat those as login-capable.
     */
    @Column(name = "password_hash", length = 120)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserAccount() {}

    public UserAccount(String username, String displayName, String email) {
        this(username, displayName, email, null);
    }

    public UserAccount(String username, String displayName, String email, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
