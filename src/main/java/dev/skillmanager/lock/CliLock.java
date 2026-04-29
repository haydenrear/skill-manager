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

    public record Entry(
            String backend,
            String tool,
            String version,
            String spec,
            String sha256,
            List<String> requestedBy,
            String installedAt
    ) {
        public Entry {
            requestedBy = requestedBy == null ? List.of() : List.copyOf(requestedBy);
        }

        public Entry withRequester(String skillName) {
            if (requestedBy.contains(skillName)) return this;
            List<String> merged = new ArrayList<>(requestedBy);
            merged.add(skillName);
            return new Entry(backend, tool, version, spec, sha256, merged, installedAt);
        }

        public Entry withoutRequester(String skillName) {
            if (!requestedBy.contains(skillName)) return this;
            List<String> remaining = new ArrayList<>(requestedBy);
            remaining.remove(skillName);
            return new Entry(backend, tool, version, spec, sha256, remaining, installedAt);
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
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) {
            throw new IOException(FILENAME + " has errors: " + toml.errors());
        }
        for (String backendKey : toml.keySet()) {
            TomlTable backendTable = toml.getTable(backendKey);
            if (backendTable == null) continue;
            Map<String, Entry> perTool = new TreeMap<>();
            for (String toolKey : backendTable.keySet()) {
                TomlTable t = backendTable.getTable(toolKey);
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
                        t.getString("installed_at")
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
        Entry existing = get(backend, tool);
        Entry updated;
        if (existing != null && java.util.Objects.equals(existing.version(), version)) {
            updated = existing.withRequester(requester);
        } else {
            List<String> reqBy = new ArrayList<>();
            if (requester != null) reqBy.add(requester);
            updated = new Entry(backend, tool, version, spec, sha256, reqBy, Instant.now().toString());
        }
        put(updated);
        return updated;
    }

    // ------------------------------------------------------------------ utils

    private static String tomlKey(String k) { return "\"" + k.replace("\"", "\\\"") + "\""; }

    private static String tomlString(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

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
