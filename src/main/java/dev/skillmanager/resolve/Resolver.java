package dev.skillmanager.resolve;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.Fetcher;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
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
    public record ResolveOutcome(ResolvedGraph graph, List<ResolveFailure> failures) {
        public ResolveOutcome {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
        public boolean hasFailures() { return !failures.isEmpty(); }
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
        ResolvedGraph graph = new ResolvedGraph();
        List<ResolveFailure> failures = new ArrayList<>();
        Deque<Pending> queue = new ArrayDeque<>();
        for (Coord c : coords) queue.push(new Pending(c.source(), c.version(), null));

        while (!queue.isEmpty()) {
            Pending p = queue.pop();
            String coord = p.source;

            String guessName = guessName(coord);
            if (guessName != null && graph.contains(guessName)) continue;

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
            // Kind-aware parse: plugins land at the bundle root with a
            // .claude-plugin/plugin.json manifest; bare skills have a
            // SKILL.md at the root. Without the plugin probe we'd descend
            // into skills/<contained>/SKILL.md and install the contained
            // skill as a top-level unit (was: ticket-15 plugin-smoke
            // failure — install hello-plugin produced INSTALLED hello-impl).
            dev.skillmanager.model.AgentUnit unit;
            if (dev.skillmanager.model.PluginParser.looksLikePlugin(fetched.dir())) {
                unit = dev.skillmanager.model.PluginParser.load(fetched.dir());
            } else {
                unit = SkillParser.load(fetched.dir()).asUnit();
            }

            boolean reused = store.contains(unit.name());
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
                String childName = ref.name() != null ? ref.name() : guessName(childSource);
                if (childName != null && graph.contains(childName)) continue;
                queue.push(new Pending(childSource, childVersion, unit.name()));
            }
        }
        return new ResolveOutcome(graph, failures);
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

    private record Pending(String source, String version, String requestedBy) {}
}
