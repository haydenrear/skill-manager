package dev.skillmanager.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-name ownership record. The first user who publishes {@code name@*}
 * owns the name; subsequent publishes of different versions under the same
 * name must come from the same owner (403 otherwise). Deletes follow the
 * same rule.
 *
 * <p>Decoupled from {@link SkillVersionRow} so that ownership survives
 * deleting every version of a skill — someone who publishes, tombstones,
 * and publishes again still owns the slot.
 */
@Entity
@Table(name = "skill_names")
public class SkillName {

    @Id
    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "owner_username", length = 64, nullable = false)
    private String ownerUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SkillName() {}

    public SkillName(String name, String ownerUsername) {
        this.name = name;
        this.ownerUsername = ownerUsername;
        this.createdAt = Instant.now();
    }

    public String getName() { return name; }
    public String getOwnerUsername() { return ownerUsername; }
    public Instant getCreatedAt() { return createdAt; }
}
