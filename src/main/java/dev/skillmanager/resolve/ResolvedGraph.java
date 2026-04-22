package dev.skillmanager.resolve;

import dev.skillmanager.model.Skill;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All transitively-resolved skills, staged in scratch directories, ready to be
 * committed into the store once the user consents. Supports clean rollback by
 * calling {@link #cleanup()} if consent is denied.
 */
public final class ResolvedGraph {

    public enum SourceKind { REGISTRY, LOCAL, GIT }

    public record Resolved(
            String name,
            String version,
            String source,
            SourceKind sourceKind,
            Path stagedDir,
            long bytesDownloaded,
            String sha256,
            Skill skill,
            boolean reusedFromStore,
            List<String> requestedBy
    ) {}

    private final Map<String, Resolved> byName = new LinkedHashMap<>();

    public void add(Resolved r) {
        byName.put(r.name(), r);
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    public Resolved get(String name) { return byName.get(name); }

    public List<Resolved> resolved() { return new ArrayList<>(byName.values()); }

    public List<Skill> skills() {
        List<Skill> out = new ArrayList<>();
        for (Resolved r : byName.values()) out.add(r.skill());
        return out;
    }

    public long totalBytes() {
        long total = 0;
        for (Resolved r : byName.values()) total += r.bytesDownloaded();
        return total;
    }

    public void cleanup() {
        for (Resolved r : byName.values()) {
            if (r.stagedDir() == null) continue;
            try { Fs.deleteRecursive(r.stagedDir()); } catch (IOException ignored) {}
        }
    }
}
