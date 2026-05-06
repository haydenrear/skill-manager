package dev.skillmanager.shared.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lightweight, dependency-free parsers for skill/plugin bundle metadata.
 *
 * <p>The CLI's {@code SkillParser} / {@code PluginParser} are the canonical
 * loaders, but they pull in tomlj + snakeyaml. The server doesn't carry
 * those deps — and the few fields the registry needs at publish time
 * (description, skill_references, unitKind) are simple enough that a
 * line-based parser is the right tradeoff.
 *
 * <p>Used by {@code SkillStorage} (when inspecting an uploaded tarball)
 * and {@code GitHubFetcher} (when pulling a couple of files out of a
 * github tarball). Keeping the logic in one place avoids subtle drift —
 * e.g. one path treating {@code 'plugin'} as a skill because its detector
 * never grew the {@code .claude-plugin/plugin.json} probe.
 */
public final class BundleMetadata {

    public static final String PLUGIN_MANIFEST_DIR = ".claude-plugin";
    public static final String PLUGIN_MANIFEST_FILE = "plugin.json";
    public static final String SKILL_FILENAME = "SKILL.md";
    public static final String SKILL_TOML_FILENAME = "skill-manager.toml";

    public static final String UNIT_KIND_SKILL = "skill";
    public static final String UNIT_KIND_PLUGIN = "plugin";

    private BundleMetadata() {}

    /**
     * Classify an unpacked bundle as {@code "plugin"} or {@code "skill"}.
     * Plugin marker is {@code .claude-plugin/plugin.json} anywhere in the
     * tree (extractors may add an owner-repo-sha prefix dir, so walk to
     * depth 4 to cover the typical github tarball layout).
     */
    public static String detectUnitKind(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return UNIT_KIND_SKILL;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            boolean hasPluginManifest = s.anyMatch(BundleMetadata::isPluginManifest);
            return hasPluginManifest ? UNIT_KIND_PLUGIN : UNIT_KIND_SKILL;
        }
    }

    private static boolean isPluginManifest(Path p) {
        if (p.getFileName() == null) return false;
        if (!PLUGIN_MANIFEST_FILE.equals(p.getFileName().toString())) return false;
        Path parent = p.getParent();
        return parent != null
                && PLUGIN_MANIFEST_DIR.equals(parent.getFileName() == null ? "" : parent.getFileName().toString())
                && Files.isRegularFile(p);
    }

    /**
     * Pull {@code description: "..."} out of SKILL.md frontmatter. Returns
     * {@code ""} if no frontmatter or no description key.
     */
    public static String parseSkillDescription(String skillMd) {
        if (skillMd == null || !skillMd.startsWith("---")) return "";
        int end = skillMd.indexOf("\n---", 3);
        if (end < 0) return "";
        String frontmatter = skillMd.substring(4, end);
        for (String line : frontmatter.split("\n")) {
            String s = line.strip();
            if (s.startsWith("description:")) {
                return unquote(s.substring("description:".length()).strip());
            }
        }
        return "";
    }

    /**
     * Read a {@code skill_references = [...]} array out of a
     * {@code skill-manager.toml}. Top-level only — matches the form the
     * server has indexed since v0.1.
     */
    public static List<String> parseSkillReferences(String tomlContent) {
        if (tomlContent == null) return List.of();
        for (String raw : tomlContent.split("\n")) {
            String s = raw.strip();
            if (!s.startsWith("skill_references")) continue;
            if (!s.contains("=") || !s.contains("[")) continue;
            int start = s.indexOf('[') + 1;
            int end = s.lastIndexOf(']');
            if (end <= start) return List.of();
            List<String> out = new ArrayList<>();
            for (String part : s.substring(start, end).split(",")) {
                String p = unquote(part.strip());
                if (!p.isEmpty()) out.add(p);
            }
            return out;
        }
        return List.of();
    }

    /**
     * Extract a {@code [section].key = "value"} from a TOML string. Used
     * by GitHubFetcher to pull {@code [skill].name} / {@code [skill].version}
     * without paying for tomlj. Returns {@code null} if not present.
     */
    public static String parseTomlString(String toml, String section, String key) {
        if (toml == null) return null;
        boolean inSection = section == null;
        String header = section == null ? null : "[" + section + "]";
        for (String raw : toml.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                inSection = header != null && line.equalsIgnoreCase(header);
                continue;
            }
            if (!inSection) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            if (!k.equalsIgnoreCase(key)) continue;
            String v = line.substring(eq + 1).trim();
            int hash = v.indexOf('#');
            if (hash >= 0) v = v.substring(0, hash).trim();
            return unquote(v);
        }
        return null;
    }

    /** Find {@code SKILL.md} anywhere in the tree (depth 4). */
    public static java.util.Optional<Path> findSkillMd(Path dir) throws IOException {
        return findFile(dir, SKILL_FILENAME);
    }

    /** Find {@code skill-manager.toml} anywhere in the tree (depth 4). */
    public static java.util.Optional<Path> findSkillToml(Path dir) throws IOException {
        return findFile(dir, SKILL_TOML_FILENAME);
    }

    private static java.util.Optional<Path> findFile(Path dir, String filename) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return java.util.Optional.empty();
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return s.filter(p -> p.getFileName() != null
                            && filename.equals(p.getFileName().toString())
                            && Files.isRegularFile(p))
                    .findFirst();
        }
    }

    private static String unquote(String v) {
        if (v == null) return "";
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
