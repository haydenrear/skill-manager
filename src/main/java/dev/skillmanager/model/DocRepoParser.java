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

/**
 * Loads a doc-repo (#48) from a directory containing a
 * {@code skill-manager.toml} with a {@code [doc-repo]} block and
 * one or more {@code [[sources]]} entries.
 *
 * <p>Expected layout:
 * <pre>
 * my-team-prompts/
 *   skill-manager.toml
 *   claude-md/
 *     review-stance.md
 *     build-instructions.md
 * </pre>
 *
 * <p>Files in {@code claude-md/} not declared in {@code [[sources]]}
 * are <em>not bindable</em> — the manifest is the gate. This lets the
 * doc-repo author keep WIP files in the repo without exposing them
 * as bindable sub-elements.
 *
 * <p>Sub-element ids default to the file stem when the manifest
 * omits {@code id}; an {@code agents} list defaults to
 * {@link DocSource#DEFAULT_AGENTS}.
 */
public final class DocRepoParser {

    /** Same filename as skills; the {@code [doc-repo]} table is what discriminates. */
    public static final String TOML_FILENAME = "skill-manager.toml";

    private DocRepoParser() {}

    /**
     * @return {@code true} if {@code dir} looks like a doc-repo (has
     *         {@code skill-manager.toml} with a {@code [doc-repo]}
     *         table). Used by the resolver to detect kind from
     *         on-disk shape.
     */
    public static boolean looksLikeDocRepo(Path dir) {
        Path tomlPath = dir.resolve(TOML_FILENAME);
        if (!Files.isRegularFile(tomlPath)) return false;
        try {
            TomlParseResult toml = Toml.parse(tomlPath);
            return !toml.hasErrors() && toml.contains("doc-repo");
        } catch (IOException e) {
            return false;
        }
    }

    public static DocUnit load(Path docRepoDir) throws IOException {
        Path tomlPath = docRepoDir.resolve(TOML_FILENAME);
        if (!Files.isRegularFile(tomlPath)) {
            throw new IOException("Missing " + TOML_FILENAME + " in " + docRepoDir);
        }
        TomlParseResult toml = Toml.parse(tomlPath);
        if (toml.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(tomlPath).append(":\n");
            toml.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        if (!toml.contains("doc-repo")) {
            throw new IOException("Not a doc-repo: missing [doc-repo] table in " + tomlPath);
        }

        String name = firstNonBlank(
                toml.getString("doc-repo.name"),
                docRepoDir.getFileName().toString());
        String version = toml.getString("doc-repo.version");
        String description = firstNonBlank(toml.getString("doc-repo.description"), "");

        TomlArray sourcesArr = toml.getArray("sources");
        if (sourcesArr == null || sourcesArr.isEmpty()) {
            throw new IOException("Doc-repo " + name + " declares no [[sources]] in " + tomlPath);
        }
        List<DocSource> sources = new ArrayList<>();
        for (int i = 0; i < sourcesArr.size(); i++) {
            TomlTable row = sourcesArr.getTable(i);
            if (row == null) continue;
            String file = row.getString("file");
            if (file == null || file.isBlank()) {
                throw new IOException("Doc-repo " + name
                        + " has a [[sources]] entry without a `file` key in " + tomlPath);
            }
            // Verify the file actually exists in the repo — fail-fast at
            // parse time rather than at bind time with a more confusing
            // "source missing" surface.
            Path resolved = docRepoDir.resolve(file).normalize();
            if (!resolved.startsWith(docRepoDir)) {
                throw new IOException("Doc-repo " + name
                        + " source file escapes the repo root: " + file);
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IOException("Doc-repo " + name + " source missing on disk: " + file);
            }
            String id = row.getString("id");
            if (id == null || id.isBlank()) id = fileStem(file);
            List<String> agents = readAgentsList(row);
            sources.add(new DocSource(id, file, agents));
        }
        // Reject duplicate ids — the binder would race on the same coord.
        List<String> seen = new ArrayList<>();
        for (DocSource s : sources) {
            if (seen.contains(s.id())) {
                throw new IOException("Doc-repo " + name + " has duplicate source id: " + s.id());
            }
            seen.add(s.id());
        }
        return new DocUnit(name, version, description, docRepoDir, sources);
    }

    private static List<String> readAgentsList(TomlTable row) {
        TomlArray arr = row.getArray("agents");
        if (arr == null) return DocSource.DEFAULT_AGENTS;
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getString(i);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out.isEmpty() ? DocSource.DEFAULT_AGENTS : out;
    }

    private static String fileStem(String file) {
        String base = file;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
