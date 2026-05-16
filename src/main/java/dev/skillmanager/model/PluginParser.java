package dev.skillmanager.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads a plugin from a directory containing:
 * <ul>
 *   <li>{@code .claude-plugin/plugin.json} — the agent-facing manifest. Required.
 *       Skill-manager reads {@code name}, {@code version}, {@code description},
 *       and {@code mcpServers} (the last only for the double-registration
 *       warning emitted at plan time).</li>
 *   <li>{@code skill-manager-plugin.toml} — optional sidecar carrying
 *       skill-manager-specific config: plugin-level CLI deps, MCP deps,
 *       references, and identity overrides.</li>
 *   <li>{@code skills/<name>/} — zero or more contained skills, each parsed
 *       via {@link SkillParser#load(Path)}. Their dep lists and references
 *       are unioned into the plugin's effective dep set.</li>
 * </ul>
 *
 * <p>Identity precedence: {@code skill-manager-plugin.toml} >
 * {@code plugin.json} > directory name. Drift between the toml and the
 * json on a shared field is a {@code WARNING}, not an error — the toml
 * value wins, the warning lands on the {@link PluginUnit}.
 *
 * <p>A plugin with no {@code skill-manager-plugin.toml} is valid: the
 * resulting {@link PluginUnit} has the union of contained-skill deps
 * but no plugin-level entries.
 */
public final class PluginParser {

    public static final String PLUGIN_JSON_PATH = ".claude-plugin/plugin.json";
    public static final String TOML_FILENAME = "skill-manager-plugin.toml";
    public static final String SKILLS_SUBDIR = "skills";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PluginParser() {}

    /**
     * @return {@code true} if {@code dir} looks like a plugin (has
     *         {@code .claude-plugin/plugin.json}). Used by the resolver
     *         (ticket 04) to detect kind from on-disk shape.
     */
    public static boolean looksLikePlugin(Path dir) {
        return Files.isRegularFile(dir.resolve(PLUGIN_JSON_PATH));
    }

    public static PluginUnit load(Path pluginDir) throws IOException {
        Path pluginJsonPath = pluginDir.resolve(PLUGIN_JSON_PATH);
        if (!Files.isRegularFile(pluginJsonPath)) {
            throw new IOException("Missing " + PLUGIN_JSON_PATH + " in " + pluginDir);
        }

        Map<String, Object> pluginJson = readPluginJson(pluginJsonPath);
        TomlParseResult toml = SkillParser.loadToml(pluginDir.resolve(TOML_FILENAME));

        // ---- identity (toml > plugin.json > dirname) + drift warnings ----
        List<String> warnings = new ArrayList<>();

        String jsonName = stringOrNull(pluginJson.get("name"));
        String jsonVersion = stringOrNull(pluginJson.get("version"));
        String jsonDescription = stringOrNull(pluginJson.get("description"));

        String tomlName = tomlString(toml, "plugin.name");
        String tomlVersion = tomlString(toml, "plugin.version");
        String tomlDescription = tomlString(toml, "plugin.description");

        String name = firstNonBlank(tomlName, jsonName, pluginDir.getFileName().toString());
        String version = firstNonBlank(tomlVersion, jsonVersion, null);
        String description = firstNonBlank(tomlDescription, jsonDescription, "");

        if (tomlName != null && jsonName != null && !Objects.equals(tomlName, jsonName)) {
            warnings.add(driftWarning("name", tomlName, jsonName));
        }
        if (tomlVersion != null && jsonVersion != null && !Objects.equals(tomlVersion, jsonVersion)) {
            warnings.add(driftWarning("version", tomlVersion, jsonVersion));
        }
        if (tomlDescription != null && jsonDescription != null
                && !Objects.equals(tomlDescription, jsonDescription)) {
            warnings.add(driftWarning("description", tomlDescription, jsonDescription));
        }

        // ---- plugin-level deps + references from the sidecar toml ----
        List<CliDependency> pluginCli = toml == null
                ? List.of()
                : SkillParser.parseCliDependencies(toml, "plugin");
        List<McpDependency> pluginMcp = toml == null
                ? List.of()
                : SkillParser.parseMcpDependencies(toml, "plugin");
        List<UnitReference> pluginRefs = toml == null
                ? List.of()
                : SkillParser.parseReferences(toml, "references", "plugin");

        // ---- contained skills under skills/ ----
        List<ContainedSkill> contained = loadContainedSkills(pluginDir.resolve(SKILLS_SUBDIR));

        // ---- effective dep set = plugin-level ∪ contained-skill-level ----
        List<CliDependency> effectiveCli = PluginUnit.unionCli(pluginCli, contained);
        List<McpDependency> effectiveMcp = PluginUnit.unionMcp(pluginMcp, contained);
        List<UnitReference> effectiveRefs = PluginUnit.unionRefs(pluginRefs, contained);

        // ---- harness-facing mcpServers map (kept for ticket 09's warning) ----
        Map<String, Object> declaredMcpServers = mapValue(pluginJson.get("mcpServers"));

        return new PluginUnit(
                name,
                version,
                description,
                effectiveCli,
                effectiveMcp,
                effectiveRefs,
                contained,
                declaredMcpServers,
                warnings,
                pluginDir.toAbsolutePath()
        );
    }

    // -------------------------------------------------------------------- json

    private static Map<String, Object> readPluginJson(Path path) throws IOException {
        try {
            Map<String, Object> parsed = JSON.readValue(path.toFile(), new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            throw new IOException("Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return Map.of();
    }

    // ---------------------------------------------------------- contained skills

    private static List<ContainedSkill> loadContainedSkills(Path skillsDir) throws IOException {
        if (!Files.isDirectory(skillsDir)) return List.of();
        List<ContainedSkill> out = new ArrayList<>();
        try (var stream = Files.list(skillsDir)) {
            // sorted iteration so the union order is deterministic
            for (Path child : stream.sorted().toList()) {
                if (!Files.isDirectory(child)) continue;
                if (!Files.isRegularFile(child.resolve(SkillParser.SKILL_FILENAME))) continue;
                try {
                    Skill s = SkillParser.load(child);
                    out.add(new ContainedSkill(s));
                } catch (IOException e) {
                    throw new IOException("Failed to parse contained skill at "
                            + child + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String tomlString(TomlParseResult toml, String dottedKey) {
        if (toml == null) return null;
        return toml.getString(dottedKey);
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) if (s != null && !s.isBlank()) return s;
        return null;
    }

    private static String driftWarning(String field, String tomlValue, String jsonValue) {
        return "skill-manager-plugin.toml.[plugin]." + field + " (" + tomlValue
                + ") differs from .claude-plugin/plugin.json." + field + " (" + jsonValue
                + "); using the toml value.";
    }
}
