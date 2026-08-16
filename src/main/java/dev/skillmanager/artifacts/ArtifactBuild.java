package dev.skillmanager.artifacts;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What {@code skill-manager build} would do, decided before anything runs.
 *
 * <p>{@link ArtifactFreshness} answers "is this artifact still current". This
 * answers the next question and only the next question: <b>is there something
 * in this home that can rebuild it, and is that thing the right granularity?</b>
 * Those are different questions and the second one is where the epic's two
 * standing warnings live.
 *
 * <h2>Selection</h2>
 *
 * <ul>
 *   <li><b>no argument / {@code --stale}</b> — every artifact whose verdict is
 *       {@code stale}. Since ARTI-06 that set includes the declared-but-absent
 *       artifacts a clone ships, which is the set this verb was written for.</li>
 *   <li><b>{@code <id>…}</b> — those artifacts and <b>their stale
 *       prerequisites, transitively, and nothing else</b>. Prerequisites are
 *       {@link ArtifactGraph#dependsOn} — an artifact is built from them, so
 *       rebuilding it on top of a stale one just re-derives the stale
 *       thing.</li>
 *   <li><b>{@code --all}</b> — every artifact this command has a producer for,
 *       stale or not. Deliberately NOT "every artifact": most of a home's
 *       artifacts have no per-artifact producer, so {@code --all} over all of
 *       them would be a list of refusals with a handful of builds in it.</li>
 * </ul>
 *
 * <h2>What is buildable, and the two attributions this refuses to act on</h2>
 *
 * <p>Exactly one kind is buildable here: {@link ArtifactKind#CLI_SHIM}, through
 * the lock row's declaring unit and dependency — the same
 * {@link ArtifactFreshness#cliProducers join} that decided it was stale. Every
 * other kind is REPORTED with the command that does rebuild it and is never
 * claimed to have been built. Two of those refusals are load-bearing rather
 * than "not implemented yet":
 *
 * <ol>
 *   <li><b>{@link ArtifactKind#PROVISIONED_TREE} is never a build target.</b>
 *       No record in a home says which install wrote a directory;
 *       {@link ArtifactBackfill#provisionedTrees} credits a tree to the shim
 *       that demonstrably runs out of it, which is CONTAINMENT and not a
 *       recorded fact. ARTI-05's review measured all three ways that breaks:
 *       the live {@code provisioned-tree:cache/uv-tools owner=skill-dev-skill}
 *       is the SHARED uv root, so a "rebuild" of it would mark the root done
 *       while the install wrote only {@code cache/uv-tools/skill-dev}; a second
 *       uv tool makes the root ambiguous and DELETES the attribution, so the
 *       plan shrinks when a dependency is added; and the inference is persisted
 *       into {@code artifacts.lock.toml} and resurrected by
 *       {@code ArtifactIndex.reconcile} after the evidence is deleted. So the
 *       rule is the review's: <b>a {@code PROVISIONED_TREE} verdict whose owner
 *       was derived by containment is not a build trigger.</b> Since containment
 *       is the only way a tree gets an owner today, no tree is a target — and
 *       none needs to be. A missing tree makes the shims that run out of it
 *       stale through {@link ArtifactGraph}'s edges, those shims ARE buildable,
 *       and their install is what rewrites the tree. The sound action is reached
 *       without acting on the unsound attribution.</li>
 *   <li><b>An artifact whose producer records nothing is rebuilt
 *       unconditionally, and says so afterwards.</b> 0 of 16 {@code brew}/
 *       {@code npm}/{@code pip}/{@code tar} rows carry an install fingerprint
 *       (#120). Building one of those cannot be skipped on evidence and cannot
 *       be verified afterwards, so {@link Step#unverifiableAfterBuild()} is set
 *       at plan time and the run reports the post-build verdict it MEASURED
 *       rather than asserting freshness. Reporting them as fresh because the
 *       build returned 0 is the presence proxy wearing a new verb.</li>
 * </ol>
 *
 * <p>Nothing here executes. The plan is data; {@code BuildCommand} turns it into
 * effects.
 */
public final class ArtifactBuild {

    /** What this command intends to do about one artifact. */
    public enum Action {
        /** There is a producer, and the artifact needs it. */
        REBUILD,
        /** There is a producer and nothing to do — only reachable for a named id. */
        ALREADY_CURRENT,
        /** Nothing in this command can rebuild it; {@link Step#reason} says what can. */
        NOT_BUILDABLE;

        public String token() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /**
     * One artifact and this command's decision about it.
     *
     * @param dep      the dependency to hand a backend, or null when not buildable
     * @param unitName the unit that declares {@code dep}, or null
     * @param unverifiableAfterBuild whether a successful rebuild will still
     *        leave this artifact undecidable, known BEFORE the build runs
     */
    public record Step(String id, ArtifactKind kind, String owner, Action action,
                       ArtifactFreshness.Freshness before,
                       Artifact.Materialization materialization,
                       String producer, String reason,
                       CliDependency dep, String unitName,
                       boolean unverifiableAfterBuild) {

        public boolean rebuilds() { return action == Action.REBUILD; }
    }

    /** The whole decision, in dependency order. */
    public record Plan(List<Step> steps) {

        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public List<Step> rebuilds() {
            List<Step> out = new ArrayList<>();
            for (Step step : steps) if (step.rebuilds()) out.add(step);
            return out;
        }

        public List<Step> notBuildable() {
            List<Step> out = new ArrayList<>();
            for (Step step : steps) if (step.action() == Action.NOT_BUILDABLE) out.add(step);
            return out;
        }

        public boolean isEmpty() { return steps.isEmpty(); }
    }

    /** How the caller chose the artifacts. */
    public enum Scope { STALE, ALL, NAMED }

    private ArtifactBuild() {}

    /**
     * Decide what to build.
     *
     * @param named  explicit artifact ids, only read when {@code scope} is
     *               {@link Scope#NAMED}
     * @param force  rebuild a named artifact even when it is current. Does not
     *               widen the SET: {@code --force} is the artifact-granularity
     *               spelling of {@code --force-scripts}, not a second selector.
     */
    public static Plan of(ArtifactIndex index, ArtifactFreshness freshness, SkillStore store,
                          Scope scope, List<String> named, boolean force) {
        Map<String, ArtifactFreshness.CliProducer> producers =
                ArtifactFreshness.cliProducers(store);
        ArtifactGraph graph = freshness.graph();

        Set<String> selected = select(index, freshness, graph, scope, named, producers);

        List<Step> steps = new ArrayList<>();
        // Producers before consumers, so a shim is rebuilt after whatever it is
        // built from — the same order ArtifactFreshness decides verdicts in.
        for (String id : graph.topological()) {
            if (!selected.contains(id)) continue;
            Artifact artifact = graph.byId(id);
            if (artifact == null) continue;
            steps.add(step(artifact, freshness, producers, scope, named, force));
        }
        return new Plan(steps);
    }

    /**
     * The selected ids.
     *
     * <p>For named artifacts this walks {@code dependsOn} and adds only the
     * prerequisites that are STALE. A current prerequisite is not rebuilt: the
     * ticket's rule is "its stale prerequisites and nothing else", and a verb
     * that rebuilt the clean ones too would be {@code sync --force-scripts}
     * with extra steps.
     */
    private static Set<String> select(ArtifactIndex index, ArtifactFreshness freshness,
                                      ArtifactGraph graph, Scope scope, List<String> named,
                                      Map<String, ArtifactFreshness.CliProducer> producers) {
        Set<String> selected = new LinkedHashSet<>();
        switch (scope) {
            case STALE -> {
                for (ArtifactFreshness.Verdict verdict
                        : freshness.withFreshness(ArtifactFreshness.Freshness.STALE)) {
                    selected.add(verdict.id());
                }
            }
            case ALL -> {
                // Every artifact this command HAS a producer for. See the class
                // javadoc: the alternative reads as a page of refusals.
                for (Artifact artifact : index.artifacts()) {
                    if (cliProducerOf(artifact, producers) != null) selected.add(artifact.id());
                }
            }
            case NAMED -> {
                for (String id : named) {
                    selected.add(id);
                    for (String prerequisite : prerequisitesOf(id, graph)) {
                        ArtifactFreshness.Verdict verdict = freshness.of(prerequisite);
                        if (verdict != null
                                && verdict.freshness() == ArtifactFreshness.Freshness.STALE) {
                            selected.add(prerequisite);
                        }
                    }
                }
            }
        }
        return selected;
    }

    /** Everything {@code id} is transitively built from, itself excluded. */
    private static Set<String> prerequisitesOf(String id, ArtifactGraph graph) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(graph.dependsOn(id));
        while (!queue.isEmpty()) {
            String next = queue.remove(queue.size() - 1);
            if (!seen.add(next)) continue;
            queue.addAll(graph.dependsOn(next));
        }
        seen.remove(id);
        return seen;
    }

    // ------------------------------------------------------------ one artifact

    private static Step step(Artifact artifact, ArtifactFreshness freshness,
                             Map<String, ArtifactFreshness.CliProducer> producers,
                             Scope scope, List<String> named, boolean force) {
        ArtifactFreshness.Verdict verdict = freshness.of(artifact.id());
        ArtifactFreshness.Freshness before = verdict == null
                ? ArtifactFreshness.Freshness.UNVERIFIABLE : verdict.freshness();
        Artifact.Materialization materialization = artifact.materialization();

        ArtifactFreshness.CliProducer producer = cliProducerOf(artifact, producers);
        if (producer == null) {
            return new Step(artifact.id(), artifact.kind(), artifact.owner(),
                    Action.NOT_BUILDABLE, before, materialization,
                    null, whyNotBuildable(artifact), null, null, false);
        }

        boolean recordsNothing = artifact.recorded().get("install_fingerprint") == null;
        boolean explicitlyNamed = scope == Scope.NAMED && named.contains(artifact.id());
        boolean needsIt = before != ArtifactFreshness.Freshness.CURRENT;
        String producerLabel = producer.dep().backend() + ":" + producer.dep().name()
                + " (declared by " + producer.unitName() + ")";

        if (!needsIt && !force && !(scope == Scope.ALL)) {
            return new Step(artifact.id(), artifact.kind(), artifact.owner(),
                    Action.ALREADY_CURRENT, before, materialization, producerLabel,
                    explicitlyNamed
                            ? "already current — pass --force to rebuild it anyway"
                            : "already current",
                    producer.dep(), producer.unitName(), false);
        }
        return new Step(artifact.id(), artifact.kind(), artifact.owner(),
                Action.REBUILD, before, materialization, producerLabel,
                rebuildReason(before, materialization, recordsNothing, force),
                producer.dep(), producer.unitName(), recordsNothing);
    }

    private static String rebuildReason(ArtifactFreshness.Freshness before,
                                        Artifact.Materialization materialization,
                                        boolean recordsNothing, boolean force) {
        if (force) return "--force: rerun the install whatever the fingerprint says";
        String head = switch (before) {
            case STALE -> materialization == Artifact.Materialization.MATERIALIZED
                    ? "stale — its declared inputs moved"
                    : "stale — it is declared and not usable on disk";
            case UNVERIFIABLE -> "undecided — nothing in this home could tell whether it moved";
            case CURRENT -> "selected by --all";
        };
        // Said at PLAN time, not discovered afterwards.
        return recordsNothing
                ? head + "; its backend records no install fingerprint (#120), so this rebuild "
                        + "is unconditional and cannot be verified afterwards"
                : head;
    }

    /**
     * The artifact-to-install join, and the ONE place a kind becomes buildable.
     *
     * <p>{@link ArtifactKind#PROVISIONED_TREE} is excluded here and not by a
     * missing lookup: a tree DOES carry a backend and tool in
     * {@link Artifact#recorded()} — it inherited them from the claiming shim —
     * so a lookup alone would happily resolve one and act on the containment
     * inference. The class javadoc has the three measurements.
     */
    private static ArtifactFreshness.CliProducer cliProducerOf(
            Artifact artifact, Map<String, ArtifactFreshness.CliProducer> producers) {
        if (artifact.kind() != ArtifactKind.CLI_SHIM) return null;
        String backend = artifact.recorded().get("backend");
        String tool = artifact.recorded().get("tool");
        if (backend == null || tool == null) return null;
        return producers.get(ArtifactFreshness.lockKey(backend, tool));
    }

    /** Why this command will not touch an artifact, and what will. */
    private static String whyNotBuildable(Artifact artifact) {
        return switch (artifact.kind()) {
            case CLI_SHIM -> "no installed unit declares "
                    + artifact.recorded().getOrDefault("backend", "?") + ":"
                    + artifact.recorded().getOrDefault("tool", "?")
                    + " any more, so there is no install to rerun — `skill-manager install <unit>` "
                    + "brings the declaration back, `skill-manager sync` prunes the row";
            case PROVISIONED_TREE ->
                    "no install records that it wrote this tree; the owner shown for it was "
                    + "inferred from the shim that runs out of it, and an inference at the wrong "
                    + "granularity would mark a shared root rebuilt (ARTI-05 review). Build the "
                    + "artifact that runs out of it — its install is what rewrites this tree";
            case UNIT_STORE -> "a unit's store bytes come from its source, not from a local "
                    + "producer — `skill-manager sync " + nameOr(artifact, "<unit>") + "`";
            case PROJECTION -> "a projection is re-derived by its binding — "
                    + "`skill-manager sync` (or `skill-manager rebind "
                    + nameOr(artifact, "<unit>") + "`)";
            case MARKETPLACE_ENTRY -> "the marketplace is regenerated whole, not per entry, so "
                    + "rebuilding one row here would report work over rows it did not check — "
                    + "`skill-manager sync`";
            case HARNESS_INSTANCE -> "a harness instance is instantiated, not rebuilt — "
                    + "`skill-manager harness instantiate` / `skill-manager sync harness:<name>`";
            case MCP_REGISTRATION -> "a gateway registration is written by the gateway, and "
                    + "nothing records what was posted (#120), so a rebuild here could not be "
                    + "told from a no-op — `skill-manager sync --include-mcp`";
            case DOC_IMPORT -> "a doc unit's imports follow its store copy — `skill-manager sync "
                    + nameOr(artifact, "<unit>") + "`";
            case UNIT_DIGEST -> "the drift digest is recomputed whole — "
                    + "`skill-manager home drift --record`";
        };
    }

    private static String nameOr(Artifact artifact, String fallback) {
        return artifact.owner() == null ? fallback : artifact.owner();
    }

    /**
     * Ids that are not artifacts in this home, with near-misses for each.
     * Returned rather than printed so the command owns its own output.
     */
    public static Map<String, List<String>> unknownIds(ArtifactIndex index, List<String> named) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String id : named) {
            if (index.byId(id).isPresent()) continue;
            out.put(id, index.idsMatching(id));
        }
        return out;
    }
}
