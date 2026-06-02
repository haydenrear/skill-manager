package dev.skillmanager.project;

import dev.skillmanager.agent.GeminiAgent;
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
 * Projects skills into Gemini CLI's Agent Skills directory.
 *
 * <ul>
 *   <li>{@link UnitKind#SKILL} -> {@code <geminiHome>/skills/<name>}</li>
 *   <li>{@link UnitKind#PLUGIN} -> no projection until Gemini extension
 *       mapping is modeled and implemented</li>
 * </ul>
 */
public final class GeminiProjector implements Projector {

    private final Path skillsDir;
    private final Path pluginsDir;

    public GeminiProjector(Path skillsDir, Path pluginsDir) {
        this.skillsDir = skillsDir;
        this.pluginsDir = pluginsDir;
    }

    public static GeminiProjector forDefaultAgent() {
        GeminiAgent agent = new GeminiAgent();
        return new GeminiProjector(agent.skillsDir(), agent.pluginsDir());
    }

    @Override public String agentId() { return "gemini"; }
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
