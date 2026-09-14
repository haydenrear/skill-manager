package dev.skillmanager.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What a home's agent configs say about plugin marketplaces</b>, read from the
 * files each agent loads, and the one way an entry in them is removed.
 *
 * <p>OHV-6 (#352). A home's marketplace has one identity on disk
 * ({@link PluginMarketplace#name()}, derived from the store path) and several in
 * the agents that load it. Nothing read the agent side, so a registration under
 * a stale name, a copied identity, or an enablement of another home's
 * marketplace surfaced only as an {@code AGENT_SYNC_FAILED} line in a sync while
 * {@code home verify} was clean.
 *
 * <h2>Which files, and which not</h2>
 *
 * <p>For a home whose agent directories are {@code <homeRoot>/.claude} and
 * {@code <homeRoot>/.codex} (structurally derived from the store, never from the
 * environment — the {@code HomeRepair} rule):
 *
 * <table>
 *   <caption>read</caption>
 *   <tr><th>file</th><th>entries</th></tr>
 *   <tr><td>{@code .claude/plugins/known_marketplaces.json}</td><td>registrations: name → {@code source.path}</td></tr>
 *   <tr><td>{@code .claude/settings.json}</td><td>registrations: {@code extraKnownMarketplaces} name → {@code source.path};
 *       enablements: {@code enabledPlugins} keys {@code <plugin>@<marketplace>}</td></tr>
 *   <tr><td>{@code .codex/config.toml}</td><td>registrations: {@code [marketplaces.<name>] source};
 *       enablements: {@code [plugins."<plugin>@<marketplace>"]}</td></tr>
 * </table>
 *
 * <p>For a project home {@code <homeRoot>} is the checkout, so these are the
 * checkout's {@code .claude/settings.json} and {@code .codex/config.toml} — the
 * files {@code CLAUDE_CONFIG_DIR}/{@code CODEX_HOME} name when skill-manager
 * drives the CLIs for that home, and Claude's project scope for a session opened
 * there. For the ROOT home {@code <homeRoot>} is {@code $HOME}, so they are the
 * user-level {@code ~/.claude/settings.json} and {@code ~/.codex/config.toml}.
 *
 * <p><b>Deliberately NOT read:</b> {@code settings.local.json} (per-user local
 * overrides no skill-manager verb writes); {@code installed_plugins.json} and
 * {@code plugins/cache/} (the CLIs' install cache, rewritten by
 * {@code plugin install}/{@code uninstall} — not a registration); any directory
 * {@code CLAUDE_CONFIG_DIR}/{@code CODEX_HOME} redirect to (the environment is
 * not the home); harness-instance agent dirs; Gemini (no marketplace concept);
 * and every other checkout's configs — a home reports only its own.
 */
public final class MarketplaceRegistrations {

    private MarketplaceRegistrations() {}

    /** Which agent's file an entry came from. */
    public enum Agent { CLAUDE, CODEX }

    /** Which record inside the file. */
    public enum Section {
        /** {@code known_marketplaces.json} top-level key. */
        KNOWN_MARKETPLACES,
        /** {@code settings.json} {@code extraKnownMarketplaces} key. */
        EXTRA_KNOWN_MARKETPLACES,
        /** {@code settings.json} {@code enabledPlugins} key. */
        ENABLED_PLUGINS,
        /** {@code config.toml} {@code [marketplaces.<name>]} table. */
        CODEX_MARKETPLACES,
        /** {@code config.toml} {@code [plugins."<p>@<m>"]} table. */
        CODEX_PLUGINS
    }

    /**
     * One entry.
     *
     * @param key         the entry's key in its section: a marketplace name, or
     *                    {@code <plugin>@<marketplace>}
     * @param marketplace the marketplace it names
     * @param source      a registration's source path; null for an enablement
     */
    public record Entry(Agent agent, Path file, Section section, String key,
                        String marketplace, String source) {

        public boolean registration() {
            return section != Section.ENABLED_PLUGINS && section != Section.CODEX_PLUGINS;
        }

        /** The spelling a finding uses: {@code <file relative to base>:<section>.<key>}. */
        public String subject(Path base) {
            Path abs = file.toAbsolutePath().normalize();
            String rel;
            try {
                rel = base.toAbsolutePath().normalize().relativize(abs).toString().replace('\\', '/');
                if (rel.startsWith("..")) rel = abs.toString();
            } catch (IllegalArgumentException notUnder) {
                rel = abs.toString();
            }
            return rel + ":" + sectionName() + "." + key;
        }

        String sectionName() {
            return switch (section) {
                case KNOWN_MARKETPLACES -> "known_marketplaces";
                case EXTRA_KNOWN_MARKETPLACES -> "extraKnownMarketplaces";
                case ENABLED_PLUGINS -> "enabledPlugins";
                case CODEX_MARKETPLACES -> "marketplaces";
                case CODEX_PLUGINS -> "plugins";
            };
        }
    }

    // --------------------------------------------------------------- files

    public static Path knownMarketplaces(Path claudeDir) {
        return claudeDir.resolve("plugins").resolve("known_marketplaces.json");
    }

    public static Path claudeSettings(Path claudeDir) {
        return claudeDir.resolve("settings.json");
    }

    public static Path codexConfig(Path codexDir) {
        return codexDir.resolve("config.toml");
    }

    // ------------------------------------------------------------- reading

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every entry in {@code claudeDir}'s two files and {@code codexDir}'s one. Unreadable files contribute nothing. */
    public static List<Entry> read(Path claudeDir, Path codexDir) {
        List<Entry> out = new ArrayList<>();
        if (claudeDir != null) {
            readClaudeRegistrations(knownMarketplaces(claudeDir), Section.KNOWN_MARKETPLACES, out);
            readClaudeSettings(claudeSettings(claudeDir), out);
        }
        if (codexDir != null) readCodex(codexConfig(codexDir), out);
        return out;
    }

    private static Optional<JsonNode> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static void readClaudeRegistrations(Path file, Section section, List<Entry> out) {
        readJson(file).ifPresent(root -> addRegistrations(file, section, root, out));
    }

    private static void addRegistrations(Path file, Section section, JsonNode object, List<Entry> out) {
        Iterator<Map.Entry<String, JsonNode>> it = object.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode src = e.getValue().path("source");
            String path = src.path("path").isTextual() ? src.path("path").asText() : null;
            out.add(new Entry(Agent.CLAUDE, file, section, e.getKey(), e.getKey(), path));
        }
    }

    private static void readClaudeSettings(Path file, List<Entry> out) {
        readJson(file).ifPresent(root -> {
            JsonNode extra = root.path("extraKnownMarketplaces");
            if (extra.isObject()) addRegistrations(file, Section.EXTRA_KNOWN_MARKETPLACES, extra, out);
            JsonNode enabled = root.path("enabledPlugins");
            if (enabled.isObject()) {
                Iterator<String> names = enabled.fieldNames();
                while (names.hasNext()) {
                    String key = names.next();
                    int at = key.lastIndexOf('@');
                    if (at <= 0 || at == key.length() - 1) continue;
                    out.add(new Entry(Agent.CLAUDE, file, Section.ENABLED_PLUGINS, key,
                            key.substring(at + 1), null));
                }
            }
        });
    }

    /** The entries of one Codex {@code config.toml}, wherever it lives. */
    public static List<Entry> readCodexFile(Path file) {
        List<Entry> out = new ArrayList<>();
        readCodex(file, out);
        return out;
    }

    private static void readCodex(Path file, List<Entry> out) {
        if (!Files.isRegularFile(file)) return;
        TomlParseResult parsed;
        try {
            parsed = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        TomlTable marketplaces = parsed.getTable("marketplaces");
        if (marketplaces != null) {
            for (String name : marketplaces.keySet()) {
                TomlTable t = marketplaces.getTable(List.of(name));
                String source = t == null ? null : t.getString("source");
                out.add(new Entry(Agent.CODEX, file, Section.CODEX_MARKETPLACES, name, name, source));
            }
        }
        TomlTable plugins = parsed.getTable("plugins");
        if (plugins != null) {
            for (String key : plugins.keySet()) {
                int at = key.lastIndexOf('@');
                if (at <= 0 || at == key.length() - 1) continue;
                out.add(new Entry(Agent.CODEX, file, Section.CODEX_PLUGINS, key, key.substring(at + 1), null));
            }
        }
    }

    // ------------------------------------------------------------ judging

    /** A skill-manager-generated marketplace: by its name, or by the directory it registers. */
    public static boolean skillManagerMarketplace(String name, String source) {
        if (name != null && (name.equals(PluginMarketplace.NAME)
                || name.startsWith(PluginMarketplace.NAME + "-"))) {
            return true;
        }
        if (source == null) return false;
        Path p;
        try {
            p = Path.of(source).normalize();
        } catch (RuntimeException notAPath) {
            return false;
        }
        return p.getFileName() != null && p.getFileName().toString().equals("plugin-marketplace");
    }

    /**
     * Whether two spellings name one directory: equal once normalized, or equal
     * once resolved when both exist. {@code /var} vs {@code /private/var} resolves
     * equal; a registration whose directory is gone compares by its spelling.
     */
    public static boolean samePath(String a, Path b) {
        if (a == null || b == null) return false;
        try {
            Path pa = Path.of(a).toAbsolutePath().normalize();
            Path pb = b.toAbsolutePath().normalize();
            if (pa.equals(pb)) return true;
            if (Files.exists(pa) && Files.exists(pb)) {
                return pa.toRealPath().equals(pb.toRealPath());
            }
            return realOrSelf(pa).equals(realOrSelf(pb));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path realOrSelf(Path p) {
        // The macOS top-level aliases, for a path that no longer exists.
        String s = p.toString();
        for (String alias : List.of("/var/", "/tmp/", "/etc/")) {
            if (s.startsWith(alias)) return Path.of("/private" + s);
        }
        return p;
    }

    /** How one entry disagrees with the home's identity — #352's shapes 1, 2 and 4. */
    public enum Disagreement {
        /** Shape 1: this home's marketplace directory registered, or enabled, under a name that is not its identity. */
        UNDER_ANOTHER_NAME,
        /** Shape 2's trace: a plugin enabled under this home's identity, which that config does not register at this home's directory. */
        IDENTITY_UNREGISTERED,
        /** Shape 4: a registration or enablement of ANOTHER home's marketplace. */
        FOREIGN
    }

    /** One disagreement, with the sentence that names what was expected and what was found. */
    public record Judged(Disagreement kind, Entry entry, String detail) {}

    /**
     * Every entry that disagrees with a home whose marketplace is {@code identity}
     * at {@code marketplaceRoot}. Each agent is judged against its own files only:
     * a Claude enablement is resolved against Claude's registrations, never Codex's.
     *
     * <p><b>One deliberate exemption:</b> a Claude {@code known_marketplaces.json}
     * entry for another home's marketplace that nothing in the same config ENABLES
     * is not reported. It loads nothing, and in a user-level config it is how
     * Claude records a marketplace a checkout declared; removing it would be a
     * repair of something that is not damage. Declared ({@code extraKnownMarketplaces})
     * and Codex registrations are always reported: they are this home's statements.
     */
    public static List<Judged> judge(List<Entry> entries, String identity, Path marketplaceRoot) {
        List<Judged> out = new ArrayList<>();
        for (Agent agent : Agent.values()) {
            List<Entry> mine = entries.stream().filter(e -> e.agent() == agent).toList();
            List<Entry> regs = mine.stream().filter(Entry::registration).toList();
            java.util.Set<String> enabledFrom = new java.util.HashSet<>();
            for (Entry e : mine) if (!e.registration()) enabledFrom.add(e.marketplace());
            for (Entry r : regs) {
                if (!skillManagerMarketplace(r.key(), r.source())) continue;
                boolean here = samePath(r.source(), marketplaceRoot);
                if (here && r.key().equals(identity)) continue;
                if (here) {
                    out.add(new Judged(Disagreement.UNDER_ANOTHER_NAME, r,
                            "registers this home's marketplace " + marketplaceRoot + " under "
                                    + r.key() + ", but its identity is " + identity + " — expected "
                                    + identity + " at " + marketplaceRoot + ", found " + r.key()
                                    + " there, so every sync updates and installs from a name "
                                    + "this agent does not know"));
                    continue;
                }
                if (r.key().equals(identity)) {
                    out.add(new Judged(Disagreement.FOREIGN, r,
                            "registers this home's identity " + identity + " at " + r.source()
                                    + " — expected it at " + marketplaceRoot));
                    continue;
                }
                if (r.section() == Section.KNOWN_MARKETPLACES && !enabledFrom.contains(r.key())) continue;
                out.add(new Judged(Disagreement.FOREIGN, r,
                        "registers " + r.key() + " at " + r.source() + ", another home's marketplace "
                                + "— this home's is " + identity + " at " + marketplaceRoot
                                + ", and a plugin enabled from both loads twice"));
            }
            for (Entry e : mine) {
                if (e.registration()) continue;
                String m = e.marketplace();
                List<Entry> named = regs.stream().filter(r -> r.key().equals(m)).toList();
                boolean smName = skillManagerMarketplace(m, null)
                        || named.stream().anyMatch(r -> skillManagerMarketplace(null, r.source()));
                if (!smName) continue;
                if (m.equals(identity)) {
                    if (named.stream().anyMatch(r -> samePath(r.source(), marketplaceRoot))) continue;
                    List<String> registered = regs.stream()
                            .filter(r -> skillManagerMarketplace(r.key(), r.source()))
                            .map(r -> r.key() + " at " + r.source()).distinct().toList();
                    out.add(new Judged(Disagreement.IDENTITY_UNREGISTERED, e,
                            "enables " + e.key() + " from this home's own marketplace, but this "
                                    + "config registers no " + identity + " at " + marketplaceRoot
                                    + " — found " + (registered.isEmpty() ? "no skill-manager marketplace"
                                            : String.join(", ", registered))
                                    + "; a registered name that merely contains " + identity
                                    + " is not " + identity));
                    continue;
                }
                if (named.stream().anyMatch(r -> samePath(r.source(), marketplaceRoot))) {
                    out.add(new Judged(Disagreement.UNDER_ANOTHER_NAME, e,
                            "enables " + e.key() + " from " + m + ", which this config registers at "
                                    + "this home's marketplace " + marketplaceRoot + " — expected "
                                    + e.key().substring(0, e.key().lastIndexOf('@')) + "@" + identity));
                    continue;
                }
                String where = named.isEmpty() ? "not registered in this config at all"
                        : "registered at " + named.get(0).source();
                out.add(new Judged(Disagreement.FOREIGN, e,
                        "enables " + e.key() + " from " + m + ", " + where + " — expected only "
                                + identity + " at " + marketplaceRoot + " in this home"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ writing

    /**
     * Shape 1's repair: every entry of {@code entry}'s agent that registers this
     * home's directory under the stale name, and every enablement from that name,
     * re-pointed at {@code identity}. The enablements MIGRATE rather than go: a
     * {@code sync <plugin>} reinstalls only the plugins it names, so a removed
     * enablement is a plugin silently dropped from the agent.
     *
     * <p>Where an entry under {@code identity} already exists, the stale one is
     * removed instead of renamed onto it.
     *
     * @return the files written
     */
    public static List<Path> migrateToIdentity(Entry entry, List<Entry> all, String identity,
                                               Path marketplaceRoot) throws IOException {
        String stale = entry.registration() ? entry.key() : entry.marketplace();
        if (stale.equals(identity)) return List.of();
        List<Entry> group = all.stream()
                .filter(e -> e.agent() == entry.agent())
                .filter(e -> e.registration()
                        ? e.key().equals(stale) && samePath(e.source(), marketplaceRoot)
                        : e.marketplace().equals(stale))
                .toList();
        java.util.LinkedHashSet<Path> written = new java.util.LinkedHashSet<>();
        for (Entry e : group) {
            String renamed = e.registration() ? identity
                    : e.key().substring(0, e.key().lastIndexOf('@') + 1) + identity;
            if (rename(e, renamed)) written.add(e.file());
        }
        return List.copyOf(written);
    }

    /** Re-key one entry in place, or drop it when {@code newKey} is already present. */
    public static boolean rename(Entry entry, String newKey) throws IOException {
        if (entry.agent() == Agent.CODEX) {
            Path file = entry.file();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String table = entry.section() == Section.CODEX_MARKETPLACES ? "marketplaces" : "plugins";
            String edited = hasTable(text, table, newKey)
                    ? cutTable(text, table, entry.key())
                    : renameTable(text, table, entry.key(), newKey);
            if (edited.equals(text)) return false;
            writeAtomically(file, edited);
            return true;
        }
        JsonNode root = readJson(entry.file()).orElse(null);
        if (!(root instanceof ObjectNode obj)) return false;
        ObjectNode container = containerOf(obj, entry.section());
        if (container == null || !container.has(entry.key())) return false;
        if (container.has(newKey)) {
            container.remove(entry.key());
        } else {
            // In place: rebuild so the key keeps its position.
            java.util.LinkedHashMap<String, JsonNode> copy = new java.util.LinkedHashMap<>();
            container.fields().forEachRemaining(f -> copy.put(
                    f.getKey().equals(entry.key()) ? newKey : f.getKey(), f.getValue()));
            container.removeAll();
            copy.forEach(container::set);
        }
        writeJsonFile(entry.file(), obj);
        return true;
    }

    /**
     * Shape 2's repair: register {@code identity} at {@code marketplaceRoot} in
     * {@code entry}'s agent config, in the form that agent's own {@code marketplace
     * add} writes (measured against Claude Code 2.1 and codex-cli 0.154).
     *
     * @return the files written
     */
    public static List<Path> registerIdentity(Entry entry, String identity, Path marketplaceRoot)
            throws IOException {
        String source = marketplaceRoot.toAbsolutePath().normalize().toString();
        if (entry.agent() == Agent.CODEX) {
            Path file = entry.file();
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (hasTable(text, "marketplaces", identity)) return List.of();
            String sep = text.isEmpty() || text.endsWith("\n\n") ? "" : text.endsWith("\n") ? "\n" : "\n\n";
            writeAtomically(file, text + sep + "[marketplaces." + identity + "]\nsource_type = \"local\"\n"
                    + "source = " + JSON.writeValueAsString(source) + "\n");
            return List.of(file);
        }
        Path claudeDir = entry.section() == Section.KNOWN_MARKETPLACES
                ? entry.file().getParent().getParent() : entry.file().getParent();
        List<Path> written = new ArrayList<>();
        Path known = knownMarketplaces(claudeDir);
        ObjectNode knownRoot = readJson(known).orElse(null) instanceof ObjectNode o ? o : JSON.createObjectNode();
        if (!knownRoot.has(identity)) {
            ObjectNode reg = knownRoot.putObject(identity);
            reg.putObject("source").put("source", "directory").put("path", source);
            reg.put("installLocation", source);
            reg.put("lastUpdated", java.time.Instant.now().toString());
            Files.createDirectories(known.getParent());
            writeJsonFile(known, knownRoot);
            written.add(known);
        }
        Path settings = claudeSettings(claudeDir);
        if (readJson(settings).orElse(null) instanceof ObjectNode s) {
            ObjectNode extra = s.path("extraKnownMarketplaces") instanceof ObjectNode x
                    ? x : s.putObject("extraKnownMarketplaces");
            if (!extra.has(identity)) {
                extra.putObject(identity).putObject("source").put("source", "directory").put("path", source);
                writeJsonFile(settings, s);
                written.add(settings);
            }
        }
        return written;
    }

    private static ObjectNode containerOf(ObjectNode root, Section section) {
        return switch (section) {
            case KNOWN_MARKETPLACES -> root;
            case EXTRA_KNOWN_MARKETPLACES -> root.path("extraKnownMarketplaces") instanceof ObjectNode o ? o : null;
            case ENABLED_PLUGINS -> root.path("enabledPlugins") instanceof ObjectNode o ? o : null;
            default -> null;
        };
    }

    static boolean hasTable(String text, String table, String key) {
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("[") && namesTable(t, table, key)) return true;
        }
        return false;
    }

    /** {@code text} with the {@code [<table>.<old>]} header (and its sub-table headers) re-keyed. */
    static String renameTable(String text, String table, String oldKey, String newKey) {
        String spelledNew = newKey.matches("[A-Za-z0-9_-]+") ? newKey : "\"" + newKey + "\"";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            if (t.startsWith("[") && !t.startsWith("[[") && namesTable(t, table, oldKey)) {
                String inner = t.replaceFirst("^\\[", "").replaceFirst("\\]\\s*(#.*)?$", "").strip();
                String rest = "";
                for (String spelled : List.of("\"" + oldKey + "\"", oldKey)) {
                    String exact = table + "." + spelled;
                    if (inner.equals(exact) || inner.startsWith(exact + ".")) {
                        rest = inner.substring(exact.length());
                        break;
                    }
                }
                line = "[" + table + "." + spelledNew + rest + "]";
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------ removing

    /**
     * Remove exactly {@code entry} from its file, and nothing else.
     *
     * <p>JSON files are re-serialized in the layout the Claude CLI writes (two
     * spaces, {@code "key": value}); TOML is edited as TEXT — the one table and
     * its sub-tables are cut, every other byte is kept, because Codex's config
     * carries the operator's own settings and comments no parser round-trips.
     *
     * @return true when the entry was there and is gone
     */
    public static boolean remove(Entry entry) throws IOException {
        return switch (entry.agent()) {
            case CLAUDE -> removeJson(entry);
            case CODEX -> removeToml(entry);
        };
    }

    private static boolean removeJson(Entry entry) throws IOException {
        Path file = entry.file();
        JsonNode root = readJson(file).orElse(null);
        if (!(root instanceof ObjectNode obj)) return false;
        ObjectNode container = containerOf(obj, entry.section());
        if (container == null || !container.has(entry.key())) return false;
        container.remove(entry.key());
        writeJsonFile(file, obj);
        return true;
    }

    /**
     * Re-serialize {@code root} into {@code file}, ending the way the file ended:
     * the Claude CLI writes no trailing newline, a hand-edited settings file
     * usually has one, and a repair must not add or remove it.
     */
    private static void writeJsonFile(Path file, JsonNode root) throws IOException {
        boolean newline = Files.isRegularFile(file)
                && Files.readString(file, StandardCharsets.UTF_8).endsWith("\n");
        StringBuilder sb = new StringBuilder();
        writeJson(root, 0, sb);
        if (newline) sb.append('\n');
        writeAtomically(file, sb.toString());
    }

    /** {@code JSON.stringify(value, null, 2)}'s layout. */
    static void writeJson(JsonNode node, int indent, StringBuilder sb) throws IOException {
        String pad = "  ".repeat(indent + 1);
        String close = "  ".repeat(indent);
        if (node.isObject()) {
            if (node.size() == 0) { sb.append("{}"); return; }
            sb.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sb.append(pad).append(JSON.writeValueAsString(e.getKey())).append(": ");
                writeJson(e.getValue(), indent + 1, sb);
                sb.append(it.hasNext() ? ",\n" : "\n");
            }
            sb.append(close).append('}');
        } else if (node.isArray()) {
            if (node.size() == 0) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                sb.append(pad);
                writeJson(node.get(i), indent + 1, sb);
                sb.append(i < node.size() - 1 ? ",\n" : "\n");
            }
            sb.append(close).append(']');
        } else {
            sb.append(JSON.writeValueAsString(node));
        }
    }

    private static boolean removeToml(Entry entry) throws IOException {
        Path file = entry.file();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String table = entry.section() == Section.CODEX_MARKETPLACES ? "marketplaces" : "plugins";
        String edited = cutTable(text, table, entry.key());
        if (edited.equals(text)) return false;
        writeAtomically(file, edited);
        return true;
    }

    /**
     * {@code text} without the {@code [<table>.<key>]} table (bare or quoted key)
     * and its {@code [<table>.<key>.*]} sub-tables, each through the line before
     * the next table header. The blank lines separating the cut table from the
     * next one go with it, so the file keeps one blank line between tables.
     */
    static String cutTable(String text, String table, String key) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean cutting = false;
        boolean cutAny = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) {
                cutting = namesTable(trimmed, table, key);
                if (cutting) cutAny = true;
            }
            if (cutting) continue;
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        if (!cutAny) return text;
        // A cut at the end of the file can leave the previous table's blank
        // separator dangling; collapse runs of blank lines the cut created.
        String result = out.toString().replaceAll("\n{3,}", "\n\n");
        // A cut that took the file's last tables leaves their separator behind
        // as a trailing blank line; the file ends the way it did before.
        if (text.endsWith("\n")) {
            result = result.replaceAll("\n+$", "") + "\n";
        }
        return result;
    }

    private static boolean namesTable(String header, String table, String key) {
        String inner = header.replaceFirst("^\\[+", "").replaceFirst("\\]+\\s*(#.*)?$", "").strip();
        for (String spelled : List.of(key, "\"" + key + "\"")) {
            String exact = table + "." + spelled;
            if (inner.equals(exact) || inner.startsWith(exact + ".")) return true;
        }
        return false;
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".skill-manager-tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
