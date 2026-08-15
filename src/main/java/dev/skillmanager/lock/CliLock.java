package dev.skillmanager.lock;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TOML-backed record of every CLI tool installed by skill-manager. Keyed by
 * {@code backend.tool} so a skill wanting a second version of the same tool
 * is surfaced as a conflict before any install runs.
 *
 * <p>File layout (pretty-printed example):
 * <pre>
 * ["pip"."ruff"]
 * version = "0.6.0"
 * spec = "pip:ruff==0.6.0"
 * requested_by = ["skill-a"]
 * installed_at = "2026-04-21T14:00:00Z"
 *
 * ["npm"."typescript"]
 * version = "5.4.5"
 * spec = "npm:typescript@5.4.5"
 * requested_by = ["skill-b"]
 * </pre>
 */
public final class CliLock {

    public static final String FILENAME = "cli-lock.toml";

    /**
     * The basis recorded for a digest that predates the basis being recorded.
     * Old rows carry {@code install_fingerprint} and nothing that says what it
     * covered; "I do not know" is the true answer and is written down as one.
     */
    static final String LEGACY_BASIS = "unrecorded — written before the basis was";

    /**
     * One locked CLI dep.
     *
     * <p>{@code binary} and {@code fingerprint} are ARTI-04 additions, and both
     * are optional: the field set only grows, no key is renamed, and no value
     * written before this change means anything different after it. That is the
     * whole of this change's migration story and the reason there is not one.
     *
     * @param binary the artifact this row produced, as the declaring unit named
     *        it in {@code on_path}. The row is keyed by PACKAGE
     *        ({@code ["brew"."opentofu"]}) while the shim is named for the
     *        BINARY ({@code bin/cli/tofu}), and the mapping between them used to
     *        exist only in the declaring unit's manifest — so uninstalling that
     *        unit made the artifact unlocatable, which three rows in the project
     *        home already are. A fingerprint on a row that cannot name its own
     *        output describes a claim rather than an artifact, so the two are
     *        recorded together or not at all.
     * @param fingerprint what the backend can say about this row's declared
     *        INPUTS, or the reason it can say nothing. See {@link Fingerprint}.
     */
    public record Entry(
            String backend,
            String tool,
            String version,
            String spec,
            String sha256,
            List<String> requestedBy,
            String installedAt,
            String binary,
            Fingerprint fingerprint
    ) {
        public Entry {
            requestedBy = requestedBy == null ? List.of() : List.copyOf(requestedBy);
        }

        // Backwards-compat constructors — older callers (tests, in-tree
        // construction without a fingerprint) keep working untouched.
        public Entry(String backend, String tool, String version, String spec,
                     String sha256, List<String> requestedBy, String installedAt) {
            this(backend, tool, version, spec, sha256, requestedBy, installedAt, null, null);
        }

        public Entry(String backend, String tool, String version, String spec,
                     String sha256, List<String> requestedBy, String installedAt,
                     String installFingerprint) {
            this(backend, tool, version, spec, sha256, requestedBy, installedAt, null,
                    installFingerprint == null ? null
                            : Fingerprint.over(installFingerprint, LEGACY_BASIS));
        }

        /** The digest alone, for readers that only compare values. */
        public String installFingerprint() {
            return fingerprint == null ? null : fingerprint.value();
        }

        public Entry withRequester(String skillName) {
            if (requestedBy.contains(skillName)) return this;
            List<String> merged = new ArrayList<>(requestedBy);
            merged.add(skillName);
            return new Entry(backend, tool, version, spec, sha256, merged, installedAt,
                    binary, fingerprint);
        }

        public Entry withoutRequester(String skillName) {
            if (!requestedBy.contains(skillName)) return this;
            List<String> remaining = new ArrayList<>(requestedBy);
            remaining.remove(skillName);
            return new Entry(backend, tool, version, spec, sha256, remaining, installedAt,
                    binary, fingerprint);
        }

        public Entry withFingerprint(Fingerprint next) {
            return new Entry(backend, tool, version, spec, sha256, requestedBy, installedAt,
                    binary, next);
        }

        /** Keeps the binary already recorded when {@code next} says nothing. */
        public Entry withBinary(String next) {
            return new Entry(backend, tool, version, spec, sha256, requestedBy, installedAt,
                    next == null || next.isBlank() ? binary : next, fingerprint);
        }
    }

    // backend -> tool -> entry
    private final Map<String, Map<String, Entry>> entries;

    private CliLock(Map<String, Map<String, Entry>> entries) {
        this.entries = entries;
    }

    public static CliLock load(SkillStore store) throws IOException {
        Path file = store.root().resolve(FILENAME);
        Map<String, Map<String, Entry>> entries = new TreeMap<>();
        if (!Files.isRegularFile(file)) return new CliLock(entries);
        String text = Files.readString(file);
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            String repaired = repairLegacyBareTableKeys(text);
            if (!repaired.equals(text)) {
                TomlParseResult retry = Toml.parse(repaired);
                if (!retry.hasErrors()) toml = retry;
            }
        }
        if (toml.hasErrors()) {
            throw new IOException(FILENAME + " has errors: " + toml.errors());
        }
        for (String backendKey : toml.keySet()) {
            TomlTable backendTable = toml.getTable(List.of(backendKey));
            if (backendTable == null) continue;
            Map<String, Entry> perTool = new TreeMap<>();
            for (String toolKey : backendTable.keySet()) {
                TomlTable t = backendTable.getTable(List.of(toolKey));
                if (t == null) continue;
                List<String> reqBy = new ArrayList<>();
                TomlArray arr = t.getArray("requested_by");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        Object v = arr.get(i);
                        if (v != null) reqBy.add(v.toString());
                    }
                }
                perTool.put(toolKey, new Entry(
                        backendKey,
                        toolKey,
                        t.getString("version"),
                        t.getString("spec"),
                        t.getString("sha256"),
                        reqBy,
                        t.getString("installed_at"),
                        t.getString("binary"),
                        readFingerprint(t)
                ));
            }
            if (!perTool.isEmpty()) entries.put(backendKey, perTool);
        }
        return new CliLock(entries);
    }

    public void save(SkillStore store) throws IOException {
        Fs.ensureDir(store.root());
        Path file = store.root().resolve(FILENAME);
        StringBuilder sb = new StringBuilder();
        sb.append("# skill-manager CLI lock. Auto-managed — hand edits survive but are replaced on `install`.\n");
        sb.append("# Keyed by [package-manager.tool].\n\n");
        for (var backendEntry : entries.entrySet()) {
            for (var toolEntry : backendEntry.getValue().entrySet()) {
                Entry e = toolEntry.getValue();
                sb.append("[").append(tomlKey(backendEntry.getKey())).append(".").append(tomlKey(e.tool())).append("]\n");
                if (e.version() != null) sb.append("version = ").append(tomlString(e.version())).append('\n');
                if (e.spec() != null) sb.append("spec = ").append(tomlString(e.spec())).append('\n');
                if (e.sha256() != null) sb.append("sha256 = ").append(tomlString(e.sha256())).append('\n');
                sb.append("requested_by = ").append(tomlStringArray(e.requestedBy())).append('\n');
                if (e.installedAt() != null) sb.append("installed_at = ").append(tomlString(e.installedAt())).append('\n');
                if (e.binary() != null) sb.append("binary = ").append(tomlString(e.binary())).append('\n');
                Fingerprint fp = e.fingerprint();
                if (fp != null && fp.present()) {
                    sb.append("install_fingerprint = ").append(tomlString(fp.value())).append('\n');
                    sb.append("install_fingerprint_basis = ").append(tomlString(fp.basis())).append('\n');
                } else if (fp != null) {
                    // Written, not dropped. A row with no fingerprint and no
                    // reason is indistinguishable from a row nobody asked, which
                    // is the state this ticket removed.
                    sb.append("install_fingerprint_gap = ").append(tomlString(fp.gap())).append('\n');
                }
                sb.append('\n');
            }
        }
        Files.writeString(file, sb.toString());
    }

    public Entry get(String backend, String tool) {
        Map<String, Entry> inner = entries.get(backend);
        return inner == null ? null : inner.get(tool);
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (var inner : entries.values()) out.addAll(inner.values());
        return out;
    }

    public void put(Entry entry) {
        entries.computeIfAbsent(entry.backend(), k -> new TreeMap<>()).put(entry.tool(), entry);
    }

    public void remove(String backend, String tool) {
        Map<String, Entry> inner = entries.get(backend);
        if (inner != null) {
            inner.remove(tool);
            if (inner.isEmpty()) entries.remove(backend);
        }
    }

    public Entry recordInstall(String backend, String tool, String version, String spec,
                               String sha256, String requester) {
        return recordInstall(backend, tool, version, spec, sha256, requester, (String) null);
    }

    /**
     * Record a successful install with everything ARTI-04 asks a backend for:
     * the {@link Fingerprint} over its declared inputs and the {@code binary}
     * the row produced.
     */
    public Entry recordInstall(String backend, String tool, String version, String spec,
                               String sha256, String requester,
                               Fingerprint fingerprint, String binary) {
        Entry existing = get(backend, tool);
        Entry updated;
        if (existing != null && java.util.Objects.equals(existing.version(), version)) {
            updated = existing.withRequester(requester);
        } else {
            List<String> reqBy = new ArrayList<>();
            if (requester != null) reqBy.add(requester);
            updated = new Entry(backend, tool, version, spec, sha256,
                    reqBy, Instant.now().toString(), null, null);
        }
        updated = updated.withBinary(binary);
        // Overwrite unconditionally, gap included: a row whose backend can no
        // longer describe its inputs must stop claiming the digest it carried
        // when it could, or the next comparison reads a fact about a state the
        // home has left.
        if (fingerprint != null) updated = updated.withFingerprint(fingerprint);
        put(updated);
        return updated;
    }

    /**
     * Record a successful install AND stamp a bare digest whose basis is not
     * known — the shape callers written before ARTI-04 use. The fingerprint is
     * opaque to this class; backends populate it however they want, and the
     * next install/sync/upgrade pass compares the stored value against a freshly
     * computed one.
     */
    public Entry recordInstall(String backend, String tool, String version, String spec,
                               String sha256, String requester, String fingerprint) {
        return recordInstall(backend, tool, version, spec, sha256, requester,
                fingerprint == null ? null : Fingerprint.over(fingerprint, LEGACY_BASIS),
                null);
    }

    // ------------------------------------------------------------------ utils

    /**
     * Read a row's fingerprint. A row with a digest and no recorded basis is a
     * row written before the basis was recorded, and says so rather than
     * inventing one; a row with only a gap keeps the gap, because the gap is
     * the record.
     */
    private static Fingerprint readFingerprint(TomlTable t) {
        String value = t.getString("install_fingerprint");
        if (value != null && !value.isBlank()) {
            String basis = t.getString("install_fingerprint_basis");
            return Fingerprint.over(value, basis == null || basis.isBlank() ? LEGACY_BASIS : basis);
        }
        String gap = t.getString("install_fingerprint_gap");
        return gap == null || gap.isBlank() ? null : Fingerprint.gap(gap);
    }

    private static String tomlKey(String k) { return "\"" + k.replace("\"", "\\\"") + "\""; }

    private static String tomlString(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private static String repairLegacyBareTableKeys(String text) {
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(repairLegacyBareTableKeyLine(lines[i]));
        }
        return out.toString();
    }

    private static String repairLegacyBareTableKeyLine(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        if (!trimmed.startsWith("[") || trimmed.startsWith("[[") || trimmed.startsWith("[\"")) {
            return line;
        }
        int close = trimmed.indexOf(']');
        if (close < 0) return line;
        String key = trimmed.substring(1, close);
        int dot = key.indexOf('.');
        if (dot <= 0) return line;
        String backend = key.substring(0, dot);
        String tool = key.substring(dot + 1);
        if (!isKnownBackend(backend) || tool.isBlank() || tool.startsWith("\"")) return line;
        return indent + "[" + tomlKey(backend) + "." + tomlKey(tool) + "]" + trimmed.substring(close + 1);
    }

    private static boolean isKnownBackend(String backend) {
        return switch (backend) {
            case "pip", "npm", "brew", "tar", "skill-script" -> true;
            default -> false;
        };
    }

    private static String tomlStringArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(tomlString(list.get(i)));
        }
        return sb.append("]").toString();
    }

    /** Pretty-print for {@code cli list}. */
    public Map<String, Map<String, Entry>> asNestedMap() {
        Map<String, Map<String, Entry>> copy = new LinkedHashMap<>();
        for (var e : entries.entrySet()) copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        return copy;
    }
}
