package dev.skillmanager.project;

import dev.skillmanager.agent.ClaudeAgent;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Projects skills into Claude's tree. Plugins now flow through the
 * skill-manager-owned plugin marketplace (see
 * {@link PluginMarketplace}) and the {@code claude plugin} CLI rather
 * than a per-plugin symlink, so this projector is skills-only — the
 * {@link UnitKind#PLUGIN} arm returns an empty projection list.
 *
 * <ul>
 *   <li>{@link UnitKind#SKILL} → {@code <claudeHome>/.claude/skills/<name>}</li>
 *   <li>{@link UnitKind#PLUGIN} → no projection (handled by
 *       {@link dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins})</li>
 * </ul>
 *
 * <p>The skill arm produces a single projection per unit; the projection
 * is always "make target resolve to source", which means {@code apply}
 * and {@code remove} are about the link itself — never the source bytes
 * in the store.
 */
public final class ClaudeProjector implements Projector {

    private final Path skillsDir;
    private final Path pluginsDir;

    public ClaudeProjector(Path skillsDir, Path pluginsDir) {
        this.skillsDir = skillsDir;
        this.pluginsDir = pluginsDir;
    }

    /** Production constructor — wires from {@link ClaudeAgent} (respects {@code CLAUDE_HOME}). */
    public static ClaudeProjector forDefaultAgent() {
        ClaudeAgent agent = new ClaudeAgent();
        return new ClaudeProjector(agent.skillsDir(), agent.pluginsDir());
    }

    @Override public String agentId() { return "claude"; }
    @Override public Path skillsDir() { return skillsDir; }
    @Override public Path pluginsDir() { return pluginsDir; }

    @Override
    public List<Projection> planProjection(AgentUnit unit, SkillStore store) {
        if (unit.kind() != UnitKind.SKILL) return List.of();
        Path source = store.unitDir(unit.name(), unit.kind());
        Path target = skillsDir.resolve(unit.name());
        return List.of(new Projection(agentId(), source, target, unit.kind()));
    }

    @Override
    public void apply(Projection p) throws IOException {
        Fs.ensureDir(p.target().getParent());
        if (Files.exists(p.target(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p.target())) {
            Fs.deleteRecursive(p.target());
        }
        try {
            Files.createSymbolicLink(p.target(), p.source());
        } catch (UnsupportedOperationException | IOException fallback) {
            // Filesystems that don't support symlinks (rare on macOS/Linux,
            // common on some Windows configs) fall through to a recursive
            // copy — bytes match, semantics roughly the same for read.
            Fs.copyRecursive(p.source(), p.target());
        }
    }

    @Override
    public void remove(Projection p) throws IOException {
        if (Files.exists(p.target(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p.target())) {
            Fs.deleteRecursive(p.target());
        }
    }
}
