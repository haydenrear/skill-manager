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
 * Loads a harness template (#47) from a directory containing a
 * {@code harness.toml} manifest:
 *
 * <pre>
 *   [harness]
 *   name = "code-reviewer-harness"
 *   version = "0.1.0"
 *   description = "..."
 *
 *   units = ["plugin:repo-intel@0.4.2", "skill:diff-narrative"]
 *   docs  = ["doc:org-prompts/review-stance"]
 *
 *   [[mcp_tools]]
 *   server = "shared-mcp"
 *   tools  = ["search", "get"]
 * </pre>
 *
 * <p>Identity precedence: {@code [harness] name} &gt; directory name.
 * {@code units} / {@code docs} entries are parsed through
 * {@link Coord#parse(String)} so the full coord grammar (kinded,
 * direct git, local, sub-element) applies. The resolver later walks
 * the transitive set during install.
 */
public final class HarnessParser {

    public static final String TOML_FILENAME = "harness.toml";

    private HarnessParser() {}

    /**
     * @return {@code true} if {@code dir} looks like a harness template
     *         (has {@code harness.toml} with a parseable {@code [harness]}
     *         table). Used by the resolver to detect kind from on-disk
     *         shape.
     */
    public static boolean looksLikeHarness(Path dir) {
        Path tomlPath = dir.resolve(TOML_FILENAME);
        if (!Files.isRegularFile(tomlPath)) return false;
        try {
            TomlParseResult toml = Toml.parse(tomlPath);
            return !toml.hasErrors() && toml.contains("harness");
        } catch (IOException e) {
            return false;
        }
    }

    public static HarnessUnit load(Path harnessDir) throws IOException {
        Path tomlPath = harnessDir.resolve(TOML_FILENAME);
        if (!Files.isRegularFile(tomlPath)) {
            throw new IOException("Missing " + TOML_FILENAME + " in " + harnessDir);
        }
        TomlParseResult toml = Toml.parse(tomlPath);
        if (toml.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(tomlPath).append(":\n");
            toml.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        if (!toml.contains("harness")) {
            throw new IOException("Not a harness template: missing [harness] table in " + tomlPath);
        }
        String name = firstNonBlank(
                toml.getString("harness.name"),
                harnessDir.getFileName().toString());
        String version = toml.getString("harness.version");
        String description = firstNonBlank(toml.getString("harness.description"), "");

        // TOML block-scoping: `units = [...]` after `[harness]` lives at
        // `harness.units`, but authors may also place the arrays at the
        // root before any table. Check both, root takes precedence (mirrors
        // SkillParser's findArray behavior).
        List<UnitReference> units = parseCoords(arrayUnder(toml, "units"), tomlPath, "units");
        List<UnitReference> docs = parseCoords(arrayUnder(toml, "docs"), tomlPath, "docs");
        List<HarnessMcpToolSelection> mcpTools = parseMcpTools(toml);

        return new HarnessUnit(name, version, description, harnessDir, units, docs, mcpTools);
    }

    private static TomlArray arrayUnder(TomlParseResult toml, String key) {
        TomlArray a = toml.getArray(key);
        if (a != null) return a;
        return toml.getArray("harness." + key);
    }

    private static List<UnitReference> parseCoords(TomlArray arr, Path tomlPath, String key) throws IOException {
        if (arr == null) return List.of();
        List<UnitReference> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            String raw = arr.getString(i);
            if (raw == null || raw.isBlank()) continue;
            try {
                out.add(UnitReference.parse(raw));
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed coord in harness " + key + "[" + i + "] of "
                        + tomlPath + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static List<HarnessMcpToolSelection> parseMcpTools(TomlParseResult toml) {
        TomlArray arr = toml.getArrayOrEmpty("mcp_tools");
        List<HarnessMcpToolSelection> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            TomlTable row = arr.getTable(i);
            if (row == null) continue;
            String server = row.getString("server");
            if (server == null || server.isBlank()) continue;
            List<String> tools = null;
            if (row.contains("tools")) {
                TomlArray ta = row.getArrayOrEmpty("tools");
                tools = new ArrayList<>(ta.size());
                for (int j = 0; j < ta.size(); j++) {
                    String s = ta.getString(j);
                    if (s != null && !s.isBlank()) tools.add(s);
                }
            }
            out.add(new HarnessMcpToolSelection(server, tools));
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
