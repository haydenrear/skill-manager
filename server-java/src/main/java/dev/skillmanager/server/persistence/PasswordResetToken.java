package dev.skillmanager.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One-shot password-reset token. Raw cryptographically-random value is
 * the PK — the email link carries it verbatim and consumers look it up
 * on {@link #token} directly.
 *
 * <p>Invariants enforced at consumption:
 * <ul>
 *   <li>{@code usedAt == null} — single-use</li>
 *   <li>{@code Instant.now().isBefore(expiresAt)} — fresh</li>
 * </ul>
 */
@Entity
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_password_reset_username", columnList = "username")
})
public class PasswordResetToken {

    @Id
    @Column(name = "token", length = 86, nullable = false)
    private String token;

    @Column(name = "username", length = 64, nullable = false)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public PasswordResetToken() {}

    public PasswordResetToken(String token, String username, Instant expiresAt) {
        this.token = token;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    public String getToken() { return token; }
    public String getUsername() { return username; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }

    public void markUsed(Instant when) { this.usedAt = when; }
}
