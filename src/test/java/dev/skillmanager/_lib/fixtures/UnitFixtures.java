package dev.skillmanager._lib.fixtures;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializes a {@link DepSpec} as a real on-disk unit and parses it
 * via the production parsers. Tests assert against the parsed
 * {@link AgentUnit}, so we exercise the same code path users will hit.
 *
 * <p>{@link #buildEquivalent(UnitKind, Path, String, DepSpec, ContainedSkillSpec...)}
 * is the spine of the substitutability claim: given a {@code DepSpec},
 * the same dep set materializes either as a bare skill or as a plugin
 * with one contained skill carrying those deps. Tests parameterized
 * over {@link UnitKind} call this helper and assert that the resulting
 * {@link AgentUnit#cliDependencies()} / {@code mcpDependencies()} /
 * {@code references()} match across kinds.
 */
public final class UnitFixtures {

    private UnitFixtures() {}

    public static AgentUnit buildEquivalent(
            UnitKind kind,
            Path tempRoot,
            String name,
            DepSpec deps,
            ContainedSkillSpec... extraContained
    ) {
        try {
            return switch (kind) {
                case SKILL -> scaffoldSkill(tempRoot, name, deps).asUnit();
                case PLUGIN -> {
                    // Equivalent shape: the plugin has one contained skill
                    // carrying the supplied deps, no plugin-level deps.
                    // After union, AgentUnit.cliDependencies() etc. match
                    // the bare-skill case.
                    ContainedSkillSpec primary = new ContainedSkillSpec(name + "-impl", deps);
                    ContainedSkillSpec[] all = new ContainedSkillSpec[1 + extraContained.length];
                    all[0] = primary;
                    System.arraycopy(extraContained, 0, all, 1, extraContained.length);
                    yield scaffoldPlugin(tempRoot, name, DepSpec.empty(), all);
                }
                case DOC, HARNESS -> throw new UnsupportedOperationException(
                        kind + " fixtures use scaffold* helpers — buildEquivalent's deps shape "
                                + "doesn't apply to content-only kinds");
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Scaffold a bare skill at {@code <tempRoot>/<name>} with the given
     * deps. Returns the parsed {@link Skill} record.
     */
    public static Skill scaffoldSkill(Path tempRoot, String name, DepSpec deps) throws IOException {
        Path dir = tempRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME), skillMd(name));
        Files.writeString(dir.resolve(SkillParser.TOML_FILENAME), skillToml(name, deps));
        return SkillParser.load(dir);
    }

    /**
     * Scaffold a plugin at {@code <tempRoot>/<name>} with the given
     * plugin-level deps and contained skills. Returns the parsed
     * {@link dev.skillmanager.model.PluginUnit}.
     */
    public static dev.skillmanager.model.PluginUnit scaffoldPlugin(
            Path tempRoot,
            String name,
            DepSpec pluginLevelDeps,
            ContainedSkillSpec... contained
    ) throws IOException {
        Path dir = tempRoot.resolve(name);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), pluginJson(name));
        Files.writeString(dir.resolve(PluginParser.TOML_FILENAME), pluginToml(name, pluginLevelDeps));
        if (contained != null && contained.length > 0) {
            Path skillsDir = dir.resolve(PluginParser.SKILLS_SUBDIR);
            Files.createDirectories(skillsDir);
            for (ContainedSkillSpec cs : contained) {
                Path csDir = skillsDir.resolve(cs.name);
                Files.createDirectories(csDir);
                Files.writeString(csDir.resolve(SkillParser.SKILL_FILENAME), skillMd(cs.name));
                Files.writeString(csDir.resolve(SkillParser.TOML_FILENAME), skillToml(cs.name, cs.deps));
            }
        }
        return PluginParser.load(dir);
    }

    // ------------------------------------------------------- file body builders

    private static String skillMd(String name) {
        return """
                ---
                name: %s
                description: %s — fixture
                ---
                Body of %s.
                """.formatted(name, name, name);
    }

    private static String skillToml(String name, DepSpec deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("[skill]\n");
        sb.append("name = \"").append(name).append("\"\n");
        sb.append("version = \"0.1.0\"\n");
        sb.append("description = \"").append(name).append(" fixture\"\n\n");

        if (!deps.references.isEmpty()) {
            sb.append("skill_references = [\n");
            for (String r : deps.references) sb.append("  \"").append(r).append("\",\n");
            sb.append("]\n\n");
        }
        for (String spec : deps.cliSpecs) {
            sb.append("[[cli_dependencies]]\n");
            sb.append("spec = \"").append(spec).append("\"\n\n");
        }
        for (String server : deps.mcpServers) {
            sb.append("[[mcp_dependencies]]\n");
            sb.append("name = \"").append(server).append("\"\n");
            sb.append("description = \"").append(server).append(" fixture\"\n");
            sb.append("[mcp_dependencies.load]\n");
            sb.append("type = \"docker\"\n");
            sb.append("image = \"ghcr.io/example/").append(server).append(":latest\"\n\n");
        }
        return sb.toString();
    }

    private static String pluginJson(String name) {
        return """
                {
                  "name": "%s",
                  "version": "0.1.0",
                  "description": "%s plugin fixture"
                }
                """.formatted(name, name);
    }

    private static String pluginToml(String name, DepSpec pluginLevelDeps) {
        StringBuilder sb = new StringBuilder();
        sb.append("[plugin]\n");
        sb.append("name = \"").append(name).append("\"\n");
        sb.append("version = \"0.1.0\"\n");
        sb.append("description = \"").append(name).append(" plugin fixture\"\n\n");

        if (!pluginLevelDeps.references.isEmpty()) {
            sb.append("references = [\n");
            for (String r : pluginLevelDeps.references) sb.append("  \"").append(r).append("\",\n");
            sb.append("]\n\n");
        }
        for (String spec : pluginLevelDeps.cliSpecs) {
            sb.append("[[cli_dependencies]]\n");
            sb.append("spec = \"").append(spec).append("\"\n\n");
        }
        for (String server : pluginLevelDeps.mcpServers) {
            sb.append("[[mcp_dependencies]]\n");
            sb.append("name = \"").append(server).append("\"\n");
            sb.append("description = \"").append(server).append(" fixture\"\n");
            sb.append("[mcp_dependencies.load]\n");
            sb.append("type = \"docker\"\n");
            sb.append("image = \"ghcr.io/example/").append(server).append(":latest\"\n\n");
        }
        return sb.toString();
    }
}
