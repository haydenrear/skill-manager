package dev.skillmanager.project;

import dev.skillmanager.agent.CodexAgent;
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
 * Projects skills into Codex's tree. v1 doesn't project plugins — Codex
 * doesn't yet have a plugin loader. The interface call returns an empty
 * projection list for {@link UnitKind#PLUGIN} so the per-projector loop
 * just skips Codex on plugin installs without special-casing the caller.
 *
 * <ul>
 *   <li>{@link UnitKind#SKILL} → {@code <codexHome>/.codex/skills/<name>}</li>
 *   <li>{@link UnitKind#PLUGIN} → no projection (returns empty list)</li>
 * </ul>
 *
 * <p>Plugin support lands when Codex's plugin story stabilizes — interface
 * accepts the change without further refactoring.
 */
public final class CodexProjector implements Projector {

    private final Path skillsDir;
    private final Path pluginsDir;

    public CodexProjector(Path skillsDir, Path pluginsDir) {
        this.skillsDir = skillsDir;
        this.pluginsDir = pluginsDir;
    }

    public static CodexProjector forDefaultAgent() {
        CodexAgent agent = new CodexAgent();
        return new CodexProjector(agent.skillsDir(), agent.pluginsDir());
    }

    @Override public String agentId() { return "codex"; }
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
