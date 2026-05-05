package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A skill living inside a plugin's {@code skills/} directory. Carries
 * everything the parser needs to (a) compute the plugin's effective
 * dependency union and (b) re-walk on uninstall to find what to
 * tear down.
 *
 * <p>This is <em>not</em> an {@link AgentUnit}. Contained skills are
 * bundle-internal: they are never separately installable, never
 * resolution targets, never depend-on-able. The harness still sees
 * them on disk via the plugin's projected directory; skill-manager
 * exposes them only through their parent {@link PluginUnit}.
 *
 * <p>If a contained skill needs to be reusable across plugins, the
 * answer is to publish it as a bare skill, not to expose it from a
 * plugin.
 */
public record ContainedSkill(Skill skill) {

    public ContainedSkill {
        if (skill == null) throw new IllegalArgumentException("skill must not be null");
    }

    public String name() { return skill.name(); }
    public Path sourcePath() { return skill.sourcePath(); }
    public List<CliDependency> cliDependencies() { return skill.cliDependencies(); }
    public List<McpDependency> mcpDependencies() { return skill.mcpDependencies(); }
    public List<UnitReference> references() { return skill.skillReferences(); }
}
