package dev.skillmanager.model;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a skill from a directory containing:
 * <ul>
 *   <li>{@code SKILL.md} — standard spec; only {@code name}, {@code description}
 *       (and optionally {@code version}) are read from frontmatter.</li>
 *   <li>{@code skill-manager.toml} — tooling config (cli deps, skill refs, mcp
 *       deps). Invisible to the agent, structured for skill-manager.</li>
 * </ul>
 */
public final class SkillParser {

    public static final String SKILL_FILENAME = "SKILL.md";
    public static final String TOML_FILENAME = "skill-manager.toml";

    private SkillParser() {}

    public static Skill load(Path skillDir) throws IOException {
        Path md = skillDir.resolve(SKILL_FILENAME);
        if (!Files.isRegularFile(md)) {
            throw new IOException("Missing " + SKILL_FILENAME + " in " + skillDir);
        }
        String content = Files.readString(md);
        Parsed parsed = splitFrontmatter(content);

        String name = asString(parsed.frontmatter.get("name"), skillDir.getFileName().toString());
        String description = asString(parsed.frontmatter.get("description"), "");

        TomlParseResult toml = loadToml(skillDir.resolve(TOML_FILENAME));

        String version = tomlString(toml, "skill.version",
                asString(parsed.frontmatter.get("version"), null));
        if (toml != null) {
            String tomlName = tomlString(toml, "skill.name", null);
            if (tomlName != null) name = tomlName;
            String tomlDesc = tomlString(toml, "skill.description", null);
            if (tomlDesc != null) description = tomlDesc;
        }

        List<CliDependency> cli = toml == null ? List.of() : parseCliDependencies(toml);
        List<UnitReference> refs = toml == null ? List.of() : parseSkillReferences(toml);
        List<McpDependency> mcp = toml == null ? List.of() : parseMcpDependencies(toml);

        return new Skill(name, description, version, cli, refs, mcp, parsed.frontmatter, parsed.body, skillDir.toAbsolutePath());
    }

    // ---------------------------------------------------------------- SKILL.md

    private record Parsed(Map<String, Object> frontmatter, String body) {}

    private static Parsed splitFrontmatter(String content) {
        if (!content.startsWith("---")) return new Parsed(Map.of(), content);
        int firstNl = content.indexOf('\n');
        if (firstNl < 0) return new Parsed(Map.of(), content);
        int end = content.indexOf("\n---", firstNl);
        if (end < 0) return new Parsed(Map.of(), content);
        String yaml = content.substring(firstNl + 1, end);
        int bodyStart = end + 4;
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\n') bodyStart++;
        String body = content.substring(Math.min(bodyStart, content.length()));
        Object loaded = new Yaml().load(yaml);
        Map<String, Object> fm = loaded instanceof Map<?, ?> m ? copyMap(m) : Map.of();
        return new Parsed(fm, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    // ------------------------------------------------------ skill-manager.toml

    static TomlParseResult loadToml(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(path).append(":\n");
            result.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        return result;
    }

    /**
     * TOML's block-scoping means keys written after {@code [skill]} belong to
     * that table. We read list-keys from whichever table has them (root or
     * {@code [skill]}), so users don't have to worry about section ordering.
     * Plugins use {@link #findArrayUnder(TomlTable, String, String)} with
     * fallback table {@code "plugin"}.
     */
    private static TomlArray findArray(TomlTable root, String key) {
        return findArrayUnder(root, key, "skill");
    }

    /** Generalized version of {@link #findArray} taking the fallback table name. */
    static TomlArray findArrayUnder(TomlTable root, String key, String fallbackTable) {
        TomlArray arr = root.getArray(key);
        if (arr != null) return arr;
        TomlTable nested = root.getTable(fallbackTable);
        return nested == null ? null : nested.getArray(key);
    }

    private static Object findValue(TomlTable root, String key) {
        Object v = root.get(key);
        if (v != null) return v;
        TomlTable skill = root.getTable("skill");
        return skill == null ? null : skill.get(key);
    }

    static List<CliDependency> parseCliDependencies(TomlTable root) {
        return parseCliDependencies(root, "skill");
    }

    /** Generalized version that takes the fallback table name (e.g. {@code "plugin"}). */
    static List<CliDependency> parseCliDependencies(TomlTable root, String fallbackTable) {
        List<CliDependency> out = new ArrayList<>();
        TomlArray arr = findArrayUnder(root, "cli_dependencies", fallbackTable);
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            TomlTable t = arr.getTable(i);
            if (t == null) continue;
            String spec = t.getString("spec");
            if (spec == null) continue;

            String name = t.getString("name");
            if (name == null) {
                name = deriveCliName(spec);
            }

            Map<String, CliDependency.InstallTarget> targets = new LinkedHashMap<>();
            TomlTable installTable = t.getTable("install");
            if (installTable != null) {
                for (String key : installTable.keySet()) {
                    TomlTable target = installTable.getTable(key);
                    if (target == null) continue;
                    targets.put(key, new CliDependency.InstallTarget(
                            target.getString("url"),
                            target.getString("archive"),
                            target.getString("binary"),
                            asStringList(target.getArray("extract")),
                            target.getString("sha256"),
                            target.getString("script"),
                            asStringList(target.getArray("args"))
                    ));
                }
            }

            out.add(new CliDependency(
                    name,
                    spec,
                    t.getString("min_version"),
                    t.getString("version_check"),
                    t.getString("on_path"),
                    Boolean.TRUE.equals(t.getBoolean("platform_independent")),
                    targets
            ));
        }
        return out;
    }

    /**
     * Parse a bare-string reference coordinate. Thin shim over
     * {@link Coord#parse(String)} that wraps the result in a
     * {@link UnitReference} with the kind filter derived from the
     * coord shape. The {@code skill:} / {@code plugin:} / {@code github:} /
     * {@code git+} / {@code file://} / {@code ./} / {@code /} forms
     * are all handled by {@link Coord#parse(String)}; this method
     * exists for backwards compatibility with callers that already
     * expect a {@link UnitReference}.
     *
     * <p>Returns {@code null} for blank / null input.
     */
    public static UnitReference parseCoord(String coord) {
        if (coord == null || coord.isBlank()) return null;
        return UnitReference.of(Coord.parse(coord));
    }

    /**
     * Read references from the {@code skill_references} key (root or
     * under {@code [skill]}). Used by {@link SkillParser}; plugins
     * use {@link #parseReferences(TomlTable, String, String)} with
     * key {@code "references"} and table {@code "plugin"}.
     */
    static List<UnitReference> parseSkillReferences(TomlTable root) {
        return parseReferences(root, "skill_references", "skill");
    }

    /**
     * Generalized reference parsing: looks for {@code key} at the root
     * or, if not found, under {@code tableName}. Inline-table entries
     * (e.g. {@code [[skill_references]] name = "x" path = "./y"}) are
     * built via {@link UnitReference#legacy(String, String, String)}.
     * Bare-string entries route through {@link Coord#parse(String)}.
     */
    static List<UnitReference> parseReferences(TomlTable root, String key, String tableName) {
        List<UnitReference> out = new ArrayList<>();
        Object raw = root.get(key);
        if (raw == null) {
            TomlTable nested = root.getTable(tableName);
            if (nested != null) raw = nested.get(key);
        }
        if (!(raw instanceof TomlArray arr)) return out;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (item instanceof TomlTable t) {
                String path = t.getString("path");
                String name = t.getString("name");
                String version = t.getString("version");
                UnitReference ref = UnitReference.legacy(name, version, path);
                if (ref != null) out.add(ref);
            } else if (item instanceof String coord) {
                UnitReference ref = parseCoord(coord);
                if (ref != null) out.add(ref);
            }
        }
        return out;
    }

    static List<McpDependency> parseMcpDependencies(TomlTable root) {
        return parseMcpDependencies(root, "skill");
    }

    /** Generalized version that takes the fallback table name (e.g. {@code "plugin"}). */
    static List<McpDependency> parseMcpDependencies(TomlTable root, String fallbackTable) {
        List<McpDependency> out = new ArrayList<>();
        TomlArray arr = findArrayUnder(root, "mcp_dependencies", fallbackTable);
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            TomlTable t = arr.getTable(i);
            if (t == null) continue;
            String name = t.getString("name");
            if (name == null) continue;

            TomlTable loadTable = t.getTable("load");
            McpDependency.LoadSpec load = parseLoadSpec(loadTable);
            if (load == null) continue;

            List<McpDependency.InitField> schema = new ArrayList<>();
            TomlArray schemaArr = t.getArray("init_schema");
            if (schemaArr != null) {
                for (int j = 0; j < schemaArr.size(); j++) {
                    TomlTable sm = schemaArr.getTable(j);
                    if (sm == null) continue;
                    schema.add(new McpDependency.InitField(
                            sm.getString("name"),
                            stringOr(sm.getString("type"), "string"),
                            stringOr(sm.getString("description"), ""),
                            Boolean.TRUE.equals(sm.getBoolean("required")),
                            Boolean.TRUE.equals(sm.getBoolean("secret")),
                            sm.get("default"),
                            asStringList(sm.getArray("enum"))
                    ));
                }
            }

            TomlTable initParams = t.getTable("initialization");
            Map<String, Object> init = initParams == null ? Map.of() : tableToMap(initParams);
            Long idle = t.getLong("idle_timeout_seconds");
            String defaultScope = t.getString("default_scope");

            out.add(new McpDependency(
                    name,
                    stringOr(t.getString("display_name"), name),
                    stringOr(t.getString("description"), ""),
                    load,
                    schema,
                    init,
                    asStringList(t.getArray("required_tools")),
                    idle == null ? null : idle.intValue(),
                    defaultScope
            ));
        }
        return out;
    }

    private static McpDependency.LoadSpec parseLoadSpec(TomlTable t) {
        if (t == null) return null;
        String type = t.getString("type");
        if (type == null) return null;
        Map<String, String> env = stringMap(t.getTable("env"));
        String transport = stringOr(t.getString("transport"), "stdio");
        String url = t.getString("url");
        if ("docker".equalsIgnoreCase(type)) {
            return new McpDependency.DockerLoad(
                    t.getString("image"),
                    t.getBoolean("pull") == null ? true : t.getBoolean("pull"),
                    t.getString("platform"),
                    asStringList(t.getArray("command")),
                    asStringList(t.getArray("args")),
                    env,
                    asStringList(t.getArray("volumes")),
                    transport,
                    url
            );
        }
        if ("npm".equalsIgnoreCase(type)) {
            String pkg = t.getString("package");
            if (pkg == null || pkg.isBlank()) {
                throw new IllegalArgumentException(
                        "npm load spec requires `package` (e.g. \"@runpod/mcp-server\")");
            }
            return new McpDependency.NpmLoad(
                    pkg,
                    stringOr(t.getString("version"), "latest"),
                    asStringList(t.getArray("args")),
                    env,
                    transport,
                    url
            );
        }
        if ("uv".equalsIgnoreCase(type)) {
            String pkg = t.getString("package");
            if (pkg == null || pkg.isBlank()) {
                throw new IllegalArgumentException(
                        "uv load spec requires `package` (e.g. \"tb-query-mcp\")");
            }
            return new McpDependency.UvLoad(
                    pkg,
                    t.getString("version"),     // null = latest, no `--from` pin
                    t.getString("entry_point"), // null = use package's default script
                    asStringList(t.getArray("args")),
                    env,
                    transport,
                    url
            );
        }
        if ("shell".equalsIgnoreCase(type)) {
            java.util.List<String> command = asStringList(t.getArray("command"));
            if (command.isEmpty()) {
                throw new IllegalArgumentException(
                        "shell load spec requires `command` (e.g. command = [\"my-script.sh\", \"--mcp\"])");
            }
            return new McpDependency.ShellLoad(
                    command,
                    env,
                    transport,
                    url
            );
        }
        if ("binary".equalsIgnoreCase(type)) {
            Map<String, McpDependency.InstallTarget> targets = new LinkedHashMap<>();
            TomlTable installTable = t.getTable("install");
            if (installTable != null) {
                for (String key : installTable.keySet()) {
                    TomlTable target = installTable.getTable(key);
                    if (target == null) continue;
                    targets.put(key, new McpDependency.InstallTarget(
                            target.getString("url"),
                            target.getString("archive"),
                            target.getString("binary"),
                            target.getString("sha256")
                    ));
                }
            }
            return new McpDependency.BinaryLoad(
                    targets,
                    t.getString("init_script"),
                    t.getString("bin_path"),
                    asStringList(t.getArray("args")),
                    env,
                    transport,
                    url
            );
        }
        return null;
    }

    // ------------------------------------------------------------------ utils

    private static String tomlString(TomlParseResult toml, String key, String fallback) {
        if (toml == null) return fallback;
        String s = toml.getString(key);
        return s == null ? fallback : s;
    }

    private static String stringOr(String s, String fallback) {
        return s == null ? fallback : s;
    }

    private static String asString(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static List<String> asStringList(TomlArray arr) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object v = arr.get(i);
            if (v != null) out.add(v.toString());
        }
        return out;
    }

    private static Map<String, String> stringMap(TomlTable t) {
        if (t == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : t.keySet()) {
            Object v = t.get(key);
            if (v != null) out.put(key, String.valueOf(v));
        }
        return out;
    }

    private static Map<String, Object> tableToMap(TomlTable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : t.keySet()) out.put(key, t.get(key));
        return out;
    }

    private static String deriveCliName(String spec) {
        int colon = spec.indexOf(':');
        String backend = colon < 0 ? "" : spec.substring(0, colon);
        String body = colon < 0 ? spec : spec.substring(colon + 1);
        if ("npm".equals(backend) && body.startsWith("@")) {
            int versionSep = body.lastIndexOf('@');
            return versionSep > 0 ? body.substring(0, versionSep) : body;
        }
        int versionSep = indexOfAny(body, "=@");
        return versionSep < 0 ? body : body.substring(0, versionSep);
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }
}
