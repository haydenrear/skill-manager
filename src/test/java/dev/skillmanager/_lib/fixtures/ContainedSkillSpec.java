package dev.skillmanager._lib.fixtures;

/**
 * Description of a skill that lives inside a plugin's {@code skills/}
 * tree. Pairs a name with its {@link DepSpec} so the parser can
 * union deps across the parent plugin's contained skills.
 *
 * <p>Like {@link DepSpec}, dependency-free for JBang reuse.
 */
public final class ContainedSkillSpec {

    public final String name;
    public final DepSpec deps;

    public ContainedSkillSpec(String name, DepSpec deps) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        this.name = name;
        this.deps = deps == null ? DepSpec.empty() : deps;
    }
}
