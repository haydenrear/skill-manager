package dev.skillmanager.model;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@value #KEY} manifest field, #261: tracked paths a unit keeps out of what
 * an install puts on disk.
 *
 * <pre>
 * [skill]                # or [plugin], [doc-repo], [harness]
 * excluded_from_install = ["specs/.history"]
 * </pre>
 *
 * <p>Paths are literal and relative to the repository root, so only the manifest
 * at the root of the repository is read; a contained skill's own manifest does
 * not declare exclusions, its plugin does. An excluded path is absent from the
 * working tree and stays in every commit: nothing about the history changes.
 *
 * <p>A path that could not mean what it says is refused rather than dropped, so
 * a typo cannot quietly install the whole archive, and a path that would remove
 * the manifest itself cannot make a unit uninstallable.
 */
public final class InstallExclusion {

    public static final String KEY = "excluded_from_install";

    /** The first of these a repository root carries is the unit's manifest. */
    private static final List<ManifestSlot> SLOTS = List.of(
            new ManifestSlot(PluginParser.TOML_FILENAME, "plugin"),
            new ManifestSlot(SkillParser.TOML_FILENAME, "skill"),
            new ManifestSlot(DocRepoParser.TOML_FILENAME, "doc-repo"),
            new ManifestSlot(HarnessParser.TOML_FILENAME, "harness"));

    /** Root entries a unit cannot be installed or identified without. */
    private static final Set<String> REQUIRED = Set.of(
            ".git", ".claude-plugin", SkillParser.SKILL_FILENAME,
            PluginParser.TOML_FILENAME, SkillParser.TOML_FILENAME, HarnessParser.TOML_FILENAME);

    private record ManifestSlot(String file, String table) {}

    /** Reads a root-relative file, or returns null when it is absent. */
    @FunctionalInterface
    public interface FileReader {
        String read(String rootRelative) throws IOException;
    }

    private InstallExclusion() {}

    /** The paths declared by the manifest at {@code root}; empty when none are. */
    public static List<String> declaredIn(Path root) throws IOException {
        return declared(rel -> {
            Path file = root.resolve(rel);
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        });
    }

    /**
     * The declared paths, read through {@code reader} so a caller can consult a
     * commit before anything is checked out.
     *
     * @throws IOException when a declaration is present but unusable
     */
    public static List<String> declared(FileReader reader) throws IOException {
        for (ManifestSlot slot : SLOTS) {
            String text = reader.read(slot.file());
            if (text == null) continue;
            TomlParseResult toml = Toml.parse(text);
            // A manifest that does not parse is the real parser's to report.
            if (toml.hasErrors()) continue;
            TomlTable table = toml.getTable(slot.table());
            if (table == null) continue;
            return parse(table.get(KEY), slot.file());
        }
        return List.of();
    }

    private static List<String> parse(Object value, String file) throws IOException {
        if (value == null) return List.of();
        if (!(value instanceof TomlArray array)) {
            throw new IOException(file + ": " + KEY + " must be an array of paths");
        }
        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.get(i) instanceof String entry)) {
                throw new IOException(file + ": " + KEY + " must be an array of paths");
            }
            paths.add(normalized(entry, file));
        }
        return new ArrayList<>(paths);
    }

    private static String normalized(String declared, String file) throws IOException {
        String path = declared.strip();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.matches(".*[*?\\[\\]!#\\n].*")) {
            throw new IOException(file + ": " + KEY + " entry '" + declared
                    + "' must be a literal path relative to the repository root");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException(file + ": " + KEY + " entry '" + declared
                        + "' must not contain empty, '.' or '..' segments");
            }
        }
        if (REQUIRED.contains(path.split("/")[0])) {
            throw new IOException(file + ": " + KEY + " entry '" + declared
                    + "' would remove what identifies the unit");
        }
        return path;
    }
}
