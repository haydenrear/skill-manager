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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserAccount() {}

    public UserAccount(String username, String displayName, String email) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
}
