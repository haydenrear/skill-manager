package dev.skillmanager.model;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SkillProjectParser {

    public static final String PRIMARY_TOML_FILENAME = "skill-project.toml";
    public static final String LEGACY_TOML_FILENAME = "skill-manager-project.toml";

    private SkillProjectParser() {}

    public static boolean looksLikeProject(Path dir) {
        Path manifest = findManifest(dir);
        if (manifest == null) return false;
        try {
            TomlParseResult toml = Toml.parse(manifest);
            return !toml.hasErrors() && toml.contains("project");
        } catch (IOException e) {
            return false;
        }
    }

    public static Path requireManifest(Path projectDir) throws IOException {
        Path manifest = findManifest(projectDir);
        if (manifest == null) {
            throw new IOException("Missing " + PRIMARY_TOML_FILENAME + " or "
                    + LEGACY_TOML_FILENAME + " in " + projectDir);
        }
        return manifest;
    }

    public static Path findManifest(Path projectDir) {
        Path primary = projectDir.resolve(PRIMARY_TOML_FILENAME);
        if (Files.isRegularFile(primary)) return primary;
        Path legacy = projectDir.resolve(LEGACY_TOML_FILENAME);
        if (Files.isRegularFile(legacy)) return legacy;
        return null;
    }

    public static SkillProject load(Path projectDir) throws IOException {
        return loadManifest(requireManifest(projectDir), projectDir);
    }

    public static SkillProject loadManifest(Path manifestPath) throws IOException {
        return loadManifest(manifestPath, manifestPath.toAbsolutePath().normalize().getParent());
    }

    public static SkillProject loadManifest(Path manifestPath, Path projectRoot) throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Missing project manifest " + manifestPath);
        }
        TomlParseResult toml = Toml.parse(manifestPath);
        if (toml.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(manifestPath).append(":\n");
            toml.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        TomlTable project = toml.getTable("project");
        if (project == null) {
            throw new IOException("Not a skill project: missing [project] table in " + manifestPath);
        }
        String name = firstNonBlank(project.getString("name"), projectRoot.getFileName().toString());
        return new SkillProject(
                name,
                project.getString("version"),
                firstNonBlank(project.getString("description"), ""),
                projectRoot,
                manifestPath,
                parseUnitSection(toml, "skills", UnitKind.SKILL, manifestPath),
                parseUnitSection(toml, "plugins", UnitKind.PLUGIN, manifestPath),
                parseUnitSection(toml, "harnesses", UnitKind.HARNESS, manifestPath),
                parseUnitSection(toml, "docs", UnitKind.DOC, manifestPath),
                SkillParser.parseCliDependencies(toml, "project"),
                SkillParser.parseMcpDependencies(toml, "project"),
                parseEnvs(toml),
                parseLibs(toml, manifestPath)
        );
    }

    private static List<SkillProject.ProjectUnitRef> parseUnitSection(
            TomlParseResult toml,
            String section,
            UnitKind kind,
            Path manifestPath) throws IOException {
        List<SkillProject.ProjectUnitRef> out = new ArrayList<>();

        Object rawSection = toml.get(section);
        if (rawSection == null) {
            TomlTable project = toml.getTable("project");
            if (project != null) rawSection = project.get(section);
        }
        if (rawSection instanceof TomlArray array) {
            for (int i = 0; i < array.size(); i++) {
                String raw = array.getString(i);
                if (raw == null || raw.isBlank()) continue;
                UnitReference ref = parseReference(raw, section + "[" + i + "]", manifestPath);
                String alias = firstNonBlank(ref.name(), kind.name().toLowerCase() + "-" + i);
                out.add(new SkillProject.ProjectUnitRef(alias, kind, ref, null, true));
            }
        }

        if (!(rawSection instanceof TomlTable table)) return out;
        for (String alias : table.keySet()) {
            TomlTable dep = table.getTable(alias);
            if (dep == null) continue;
            String source = firstNonBlank(dep.getString("source"), dep.getString("coord"));
            if (source == null || source.isBlank()) {
                throw new IOException("Missing source for project " + section + "." + alias
                        + " in " + manifestPath);
            }
            UnitReference ref = parseReference(source, section + "." + alias + ".source", manifestPath);
            out.add(new SkillProject.ProjectUnitRef(
                    alias,
                    kind,
                    ref,
                    dep.getString("revision"),
                    !Boolean.FALSE.equals(dep.getBoolean("install"))));
        }
        return out;
    }

    private static UnitReference parseReference(String raw, String key, Path manifestPath) throws IOException {
        try {
            return UnitReference.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed coord in project " + key + " of "
                    + manifestPath + ": " + e.getMessage(), e);
        }
    }

    private static List<SkillProject.ProjectEnv> parseEnvs(TomlParseResult toml) {
        TomlTable envs = toml.getTable("envs");
        if (envs == null) return List.of();
        List<SkillProject.ProjectEnv> out = new ArrayList<>();
        for (String name : envs.keySet()) {
            TomlTable env = envs.getTable(name);
            if (env == null) continue;
            out.add(new SkillProject.ProjectEnv(
                    name,
                    env.getString("python"),
                    strings(env.getArray("dependencies")),
                    strings(env.getArray("skill_packages")),
                    strings(env.getArray("tools"))));
        }
        return out;
    }

    private static List<SkillProject.ProjectLib> parseLibs(TomlParseResult toml, Path manifestPath) throws IOException {
        TomlArray libs = toml.getArray("libs");
        if (libs == null) return List.of();
        List<SkillProject.ProjectLib> out = new ArrayList<>();
        for (int i = 0; i < libs.size(); i++) {
            TomlTable lib = libs.getTable(i);
            if (lib == null) continue;
            String name = lib.getString("name");
            String source = lib.getString("source");
            try {
                out.add(new SkillProject.ProjectLib(
                        name,
                        source,
                        lib.getString("ref"),
                        lib.getString("sha")));
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed project libs[" + i + "] in "
                        + manifestPath + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<String> strings(TomlArray arr) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getString(i);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
