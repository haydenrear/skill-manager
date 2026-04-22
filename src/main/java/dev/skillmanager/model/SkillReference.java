package dev.skillmanager.model;

/**
 * A transitive skill this skill depends on.
 *
 * <p>Primary form is name-based and resolved via the skill registry:
 * {@code SkillReference("other-skill", "1.2.0", null)}.
 *
 * <p>Local development references carry a {@code path}; the registry lookup
 * is skipped for those.
 */
public record SkillReference(String name, String version, String path) {

    public boolean isLocal() { return path != null && !path.isBlank(); }

    public boolean isRegistry() { return !isLocal() && name != null && !name.isBlank(); }

    /** Parse {@code name} or {@code name@version} into a registry reference. */
    public static SkillReference registry(String coord) {
        if (coord == null || coord.isBlank()) return null;
        int at = coord.indexOf('@');
        if (at < 0) return new SkillReference(coord.trim(), null, null);
        return new SkillReference(coord.substring(0, at).trim(), coord.substring(at + 1).trim(), null);
    }
}
