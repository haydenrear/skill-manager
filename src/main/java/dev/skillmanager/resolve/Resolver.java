package dev.skillmanager.resolve;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.SkillReference;
import dev.skillmanager.store.Fetcher;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Two-phase resolver:
 * <ol>
 *   <li>{@link #resolve(String, String)} — walks the full transitive dep graph,
 *       staging every skill in temp dirs without touching the store.</li>
 *   <li>{@link #commit(ResolvedGraph)} — moves staged skills into the store.
 *       Runs only after the user has consented to the plan.</li>
 * </ol>
 */
public final class Resolver {

    private final SkillStore store;

    public Resolver(SkillStore store) { this.store = store; }

    public ResolvedGraph resolve(String source, String version) throws IOException {
        ResolvedGraph graph = new ResolvedGraph();
        Deque<Pending> queue = new ArrayDeque<>();
        queue.push(new Pending(source, version, null));

        while (!queue.isEmpty()) {
            Pending p = queue.pop();
            String coord = p.source;

            String guessName = guessName(coord);
            if (guessName != null && graph.contains(guessName)) continue;

            Path staging = Files.createTempDirectory(store.cacheDir(), "stage-");
            Fetcher.FetchResult fetched;
            try {
                fetched = Fetcher.fetch(coord, p.version, staging, store);
            } catch (IOException e) {
                Fs.deleteRecursive(staging);
                throw e;
            }
            Skill skill = SkillParser.load(fetched.dir());

            boolean reused = store.contains(skill.name());
            graph.add(new ResolvedGraph.Resolved(
                    skill.name(),
                    skill.version(),
                    coord,
                    fetched.kind(),
                    staging,
                    fetched.bytesDownloaded(),
                    fetched.sha256(),
                    skill,
                    reused,
                    p.requestedBy == null ? List.of() : List.of(p.requestedBy)
            ));

            Path originDir = fetched.dir();
            for (SkillReference ref : skill.skillReferences()) {
                String childSource;
                String childVersion;
                if (ref.isLocal()) {
                    Path rel = Path.of(ref.path());
                    Path resolvedPath = rel.isAbsolute() ? rel : originDir.resolve(rel).normalize();
                    childSource = resolvedPath.toString();
                    childVersion = null;
                } else if (ref.isRegistry()) {
                    childSource = ref.name();
                    childVersion = ref.version();
                } else {
                    Log.warn("skipping reference with no name or path in %s", skill.name());
                    continue;
                }
                String childName = ref.name() != null ? ref.name() : guessName(childSource);
                if (childName != null && graph.contains(childName)) continue;
                queue.push(new Pending(childSource, childVersion, skill.name()));
            }
        }
        return graph;
    }

    public void commit(ResolvedGraph graph) throws IOException {
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            Path dst = store.skillDir(r.name());
            if (Files.exists(dst)) Fs.deleteRecursive(dst);
            Fs.ensureDir(dst.getParent());
            // r.stagedDir() is the fetch workspace; the actual skill root is under it.
            // Re-locate the skill root (mirrors Fetcher logic).
            Path skillRoot = r.skill().sourcePath();
            Fs.copyRecursive(skillRoot, dst);
            Log.ok("installed %s", r.name());
        }
    }

    private static String guessName(String coord) {
        if (coord == null) return null;
        String normalized = coord;
        int at = normalized.indexOf('@');
        if (at >= 0) normalized = normalized.substring(0, at);
        if (normalized.startsWith("file:")) normalized = normalized.substring(5);
        if (normalized.startsWith("github:")) {
            int slash = normalized.lastIndexOf('/');
            return slash >= 0 ? normalized.substring(slash + 1) : null;
        }
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        int slash = normalized.lastIndexOf('/');
        String tail = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return tail.isBlank() ? null : tail;
    }

    private record Pending(String source, String version, String requestedBy) {}
}
