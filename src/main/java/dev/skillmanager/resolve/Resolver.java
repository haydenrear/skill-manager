package dev.skillmanager.resolve;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.Fetcher;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** A top-level skill source to resolve, plus an optional pinned version / git ref. */
    public record Coord(String source, String version) {}

    /**
     * Per-coord resolve failure surfaced by {@link #resolveAllSafely(List)}.
     * Carries enough attribution for the caller to (a) record a
     * persistent error against {@code requestedBy} when it's already
     * in the store (transitive-of-installed-unit case), and (b) emit
     * a {@link dev.skillmanager.effects.ContextFact.TransitiveFailed}
     * so the renderer reports every problem in one batch.
     *
     * @param source      the coord we tried to resolve
     * @param requestedBy unit that declared this ref via skill_references —
     *                    null for top-level coords the caller passed directly
     * @param cause       underlying exception (a GitFetcherException, an
     *                    IOException from a file:/ probe, a RegistryUnavailableException, etc.)
     */
    public record ResolveFailure(String source, String requestedBy, Throwable cause) {
        /** One-line summary for renderer / banner — first line of the cause's message, trimmed. */
        public String reason() {
            String m = cause == null ? null : cause.getMessage();
            if (m == null || m.isBlank()) return "resolve failed";
            int newline = m.indexOf('\n');
            String first = newline < 0 ? m : m.substring(0, newline);
            return first.length() > 240 ? first.substring(0, 240) + "…" : first.trim();
        }
    }

    /**
     * Outcome of {@link #resolveAllSafely(List)} — the partial graph of
     * everything that DID resolve, plus a list of per-coord failures.
     * Lets callers iterate every coord (instead of fail-fast on the
     * first IOException) so a batch can report N failures in one run
     * AND install whatever pieces succeeded.
     */
    /** A recoverable hard-reference cycle, reported in traversal order. */
    public record ResolveCycle(List<String> path) {
        public ResolveCycle {
            path = path == null ? List.of() : List.copyOf(path);
        }

        public String description() { return String.join(" -> ", path); }
    }

    public record ResolveOutcome(
            ResolvedGraph graph,
            List<ResolveFailure> failures,
            List<ResolveCycle> cycles
    ) {
        public ResolveOutcome {
            failures = failures == null ? List.of() : List.copyOf(failures);
            cycles = cycles == null ? List.of() : List.copyOf(cycles);
        }
        public boolean hasFailures() { return !failures.isEmpty(); }
        public boolean hasCycles() { return !cycles.isEmpty(); }
    }

    /**
     * Resolve a batch of top-level sources + their transitive deps into a
     * single shared {@link ResolvedGraph}, accompanied by the per-coord
     * failures that occurred along the way. Lets {@code onboard} (or any
     * future bulk install) build one graph + one install plan + one
     * {@link dev.skillmanager.app.InstallUseCase} program over every
     * skill, instead of running the whole install pipeline once per
     * top-level — which would re-resolve, re-fetch, re-commit per skill
     * and re-run the post-update tail repeatedly.
     *
     * <p><b>Never throws on per-coord failure.</b> Each
     * {@link Fetcher#fetch} exception is captured into the
     * {@link ResolveOutcome#failures()} list — including its attribution
     * to {@code requestedBy} — and the BFS continues with the next
     * pending coord. Callers MUST inspect
     * {@link ResolveOutcome#hasFailures()} and decide what the
     * appropriate behavior is for their flow (sync emits a
     * {@link dev.skillmanager.effects.ContextFact.TransitiveFailed}
     * per failure plus records {@code TRANSITIVE_RESOLVE_FAILED} on
     * the parent in the store; install/onboard render the failures
     * and exit non-zero — see {@code TransitiveFailures} helpers).
     *
     * <p>Filesystem errors from creating the staging temp dir still
     * propagate as {@link IOException} — those are bugs in the cache
     * dir setup, not per-coord failures, and a top-level stack
     * trace is the right diagnostic.
     */
    public ResolveOutcome resolveAll(List<Coord> coords) throws IOException {
        return resolveAll(coords, Map.of());
    }

    public ResolveOutcome resolveAll(List<Coord> coords, Map<String, Coord> aliases) throws IOException {
        ResolvedGraph graph = new ResolvedGraph();
        List<ResolveFailure> failures = new ArrayList<>();
        List<ResolveCycle> cycles = new ArrayList<>();
        Set<String> reportedCycles = new LinkedHashSet<>();
        Set<CoordKey> visited = new HashSet<>();
        Map<CoordKey, String> resolvedNames = new HashMap<>();
        Deque<Pending> queue = new ArrayDeque<>();
        for (Coord c : coords) {
            Coord resolved = resolveAlias(c.source(), c.version(), aliases);
            queue.push(new Pending(resolved.source(), resolved.version(), null, List.of()));
        }

        while (!queue.isEmpty()) {
            Pending p = queue.pop();
            String coord = p.source;
            CoordKey key = coordKey(coord, p.version);

            int cycleStart = ancestorIndex(p.ancestors, key);
            if (cycleStart >= 0) {
                List<String> path = new ArrayList<>();
                for (int i = cycleStart; i < p.ancestors.size(); i++) {
                    path.add(p.ancestors.get(i).unitName());
                }
                path.add(resolvedNames.getOrDefault(key, guessName(coord)));
                String signature = String.join("\u0000", path);
                if (reportedCycles.add(signature)) {
                    ResolveCycle cycle = new ResolveCycle(path);
                    cycles.add(cycle);
                    Log.warn("reference cycle: %s", cycle.description());
                }
                mergeRequester(graph, resolvedNames.get(key), p.requestedBy);
                continue;
            }

            if (!visited.add(key)) {
                mergeRequester(graph, resolvedNames.get(key), p.requestedBy);
                continue;
            }

            Path staging = Files.createTempDirectory(store.cacheDir(), "stage-");
            Fetcher.FetchResult fetched;
            try {
                fetched = Fetcher.fetch(coord, p.version, staging, store);
            } catch (IOException | dev.skillmanager.store.GitFetcherException e) {
                // Accumulate instead of bailing — the rest of the
                // queue may succeed, and callers want to report every
                // failure in one run rather than fail-fast on the
                // first one (which masks the others).
                Fs.deleteRecursive(staging);
                failures.add(new ResolveFailure(coord, p.requestedBy, e));
                continue;
            }
            // Kind-aware parse, in precedence order:
            //   1. plugin — .claude-plugin/plugin.json at root
            //   2. harness template — harness.toml with [harness] table (#47)
            //   3. doc-repo — skill-manager.toml with [doc-repo] table (#48)
            //   4. bare skill — SKILL.md at root (fallback)
            // The plugin probe must come first so plugins with contained
            // skills under skills/<name>/ don't get descended into and
            // installed as standalone skill units (ticket-15 fix).
            dev.skillmanager.model.AgentUnit unit;
            if (dev.skillmanager.model.PluginParser.looksLikePlugin(fetched.dir())) {
                unit = dev.skillmanager.model.PluginParser.load(fetched.dir());
            } else if (dev.skillmanager.model.HarnessParser.looksLikeHarness(fetched.dir())) {
                unit = dev.skillmanager.model.HarnessParser.load(fetched.dir());
            } else if (dev.skillmanager.model.DocRepoParser.looksLikeDocRepo(fetched.dir())) {
                unit = dev.skillmanager.model.DocRepoParser.load(fetched.dir());
            } else {
                unit = SkillParser.load(fetched.dir()).asUnit();
            }

            boolean reused = store.contains(unit.name());
            resolvedNames.put(key, unit.name());
            if (graph.contains(unit.name())) {
                graph.addRequester(unit.name(), p.requestedBy);
                Fs.deleteRecursive(staging);
                continue;
            }
            graph.add(new ResolvedGraph.Resolved(
                    unit.name(),
                    unit.version(),
                    coord,
                    fetched.kind(),
                    staging,
                    fetched.bytesDownloaded(),
                    fetched.sha256(),
                    unit,
                    reused,
                    p.requestedBy == null ? List.of() : List.of(p.requestedBy)
            ));

            Path originDir = fetched.dir();
            List<Ancestor> childAncestors = new ArrayList<>(p.ancestors);
            childAncestors.add(new Ancestor(key, unit.name()));
            for (UnitReference ref : unit.references()) {
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
                    Log.warn("skipping reference with no name or path in %s", unit.name());
                    continue;
                }
                Coord alias = resolveAlias(childSource, childVersion, aliases);
                childSource = alias.source();
                childVersion = alias.version();
                queue.push(new Pending(
                        childSource, childVersion, unit.name(), List.copyOf(childAncestors)));
            }
        }
        return new ResolveOutcome(graph, failures, cycles);
    }

    private static Coord resolveAlias(String source, String version, Map<String, Coord> aliases) {
        if (aliases == null || aliases.isEmpty()) return new Coord(source, version);
        Coord direct = aliases.get(source);
        if (direct != null) return direct;
        String name = registryName(source);
        if (name == null) return new Coord(source, version);
        Coord byName = aliases.get(name);
        return byName == null ? new Coord(source, version) : byName;
    }

    private static String registryName(String source) {
        if (source == null || source.isBlank()) return null;
        String s = source.trim();
        if (s.startsWith("skill:")) s = s.substring("skill:".length());
        else if (s.startsWith("plugin:") || s.startsWith("doc:") || s.startsWith("harness:")) return null;
        else if (s.startsWith("github:") || s.startsWith("git+") || s.startsWith("file:")
                || s.startsWith("./") || s.startsWith("../") || s.startsWith("/")
                || s.endsWith(".git") || s.startsWith("ssh://") || s.startsWith("git@")) {
            return null;
        }
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        return s.isBlank() ? null : s;
    }

    public void commit(ResolvedGraph graph) throws IOException {
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            Path dst = store.skillDir(r.name());
            if (Files.exists(dst)) Fs.deleteRecursive(dst);
            Fs.ensureDir(dst.getParent());
            // r.stagedDir() is the fetch workspace; the actual skill root is under it.
            // Re-locate the skill root (mirrors Fetcher logic).
            Path skillRoot = r.unit().sourcePath();
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

    private static void mergeRequester(ResolvedGraph graph, String name, String requestedBy) {
        if (name != null) graph.addRequester(name, requestedBy);
    }

    private static int ancestorIndex(List<Ancestor> ancestors, CoordKey key) {
        for (int i = 0; i < ancestors.size(); i++) {
            if (ancestors.get(i).key().equals(key)) return i;
        }
        return -1;
    }

    private static CoordKey coordKey(String source, String version) {
        String normalizedSource = source == null ? "" : source.trim();
        String normalizedVersion = version == null || version.isBlank() ? null : version.trim();

        String registryName = registryName(normalizedSource);
        if (registryName != null) {
            int at = normalizedSource.indexOf('@');
            if (at >= 0 && normalizedVersion == null) {
                normalizedVersion = normalizedSource.substring(at + 1).trim();
            }
            normalizedSource = registryName;
        } else {
            normalizedSource = canonicalDirectSource(normalizedSource);
        }
        return new CoordKey(normalizedSource, normalizedVersion);
    }

    private static String canonicalDirectSource(String source) {
        String value = source;
        if (value.startsWith("file:")) value = value.substring("file:".length());
        if (value.startsWith("./") || value.startsWith("../") || value.startsWith("/")) {
            return Path.of(value).toAbsolutePath().normalize().toString();
        }

        if (value.startsWith("github:")) {
            return canonicalGithubPath(value.substring("github:".length()));
        }
        if (value.startsWith("git+")) value = value.substring("git+".length());

        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getHost().equalsIgnoreCase("github.com")) {
                return canonicalGithubPath(uri.getPath());
            }
        } catch (IllegalArgumentException ignored) {
            // Non-URI direct sources retain their trimmed spelling.
        }
        return stripGitSuffix(value);
    }

    private static String canonicalGithubPath(String path) {
        String normalized = path;
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        normalized = stripGitSuffix(normalized);
        return "https://github.com/" + normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String stripGitSuffix(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        return normalized;
    }

    private record CoordKey(String source, String version) {}
    private record Ancestor(CoordKey key, String unitName) {}
    private record Pending(
            String source,
            String version,
            String requestedBy,
            List<Ancestor> ancestors
    ) {}
}
