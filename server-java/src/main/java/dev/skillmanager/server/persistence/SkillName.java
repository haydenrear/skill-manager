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
 *
 * <p>{@code unitKind} sticks with the name (ticket 18). A name publishes
 * either as a plugin or as a skill, never both — later publishes of the
 * same name with a different kind get rejected at the publish service.
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

    /**
     * Unit kind: {@code "skill"} or {@code "plugin"}. Set at first publish,
     * immutable thereafter. Existing rows in pre-ticket-18 dev databases
     * pick up the {@code DEFAULT 'skill'} on the column add — every legacy
     * row was a skill by definition.
     */
    @Column(name = "unit_kind", length = 16, nullable = false,
            columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'skill'")
    private String unitKind;

    public SkillName() {}

    public SkillName(String name, String ownerUsername) {
        this(name, ownerUsername, "skill");
    }

    public SkillName(String name, String ownerUsername, String unitKind) {
        this.name = name;
        this.ownerUsername = ownerUsername;
        this.unitKind = (unitKind == null || unitKind.isBlank()) ? "skill" : unitKind;
        this.createdAt = Instant.now();
    }

    public String getName() { return name; }
    public String getOwnerUsername() { return ownerUsername; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUnitKind() { return unitKind; }
}
