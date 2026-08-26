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
            // Three states, and they had one sentence between them.
            // A tree WITH a (containment-inferred) owner is reached by building
            // the artifact that runs out of it. A bundled package-manager root
            // has no owner but DOES record what it was derived from (ARTI-20),
            // so its remedy is a real command rather than a dead end. A tree
            // with neither is reached by nothing, and telling its operator to
            // "build the artifact that runs out of it" is an instruction with no
            // referent — the #142 class this epic keeps closing.
            case PROVISIONED_TREE -> artifact.owner() == null
                    ? (artifact.recorded().get("pinned_version") != null
                    // Review of #122: this used to tell the operator to DELETE
                    // pm/<tool> by hand. That was wrong and needlessly
                    // destructive — `pm install` calls PackageManagerRuntime
                    // .install directly, which bypasses ensureBundled's
                    // short-circuit and moves the `current` pointer, so it
                    // re-pins in place and leaves the old version beside it.
                    // A remedy that says "delete this" when a non-destructive
                    // one exists is the #142 class in the other direction.
                    ? "it is a bundled package manager, not a unit's install: `build` has no "
                    + "per-tree producer for it. Re-pin it with `skill-manager pm install "
                    + artifact.recorded().getOrDefault("tool", "<tool>")
                    + "`, which downloads the pinned version and repoints `current` without "
                    + "removing what is there. Its record does name what it was derived from, "
                    + "so a stale one is REPORTED rather than silent (#122)"
                    : "nothing in this home claims to have produced it: no record says which "
                    + "installer wrote it and no artifact names it as an input, so there is no "
                    + "install to rerun and `build` has nothing to offer. A tree under a unit's "
                    + "own name is usually re-created by reinstalling that unit")
                    : "no install records that it wrote this tree; the owner shown for it was "
                    + "inferred from the shim that runs out of it, and an inference at the wrong "
                    + "granularity would mark a shared root rebuilt (ARTI-05 review). Build the "
                    + "artifact that runs out of it — its install is what rewrites this tree";
            case UNIT_STORE -> "a unit's store bytes come from its source, not from a local "
                    + "producer — `skill-manager sync " + nameOr(artifact, "<unit>") + "`";
            // ARTI-18 review: the default remedy is DANGEROUS on one shape, so
            // that shape is named before the remedy is offered. A home copied
            // without re-anchoring holds bindings whose destPath is absolute
            // into the ORIGINAL home's checkout, so `sync` and `rebind` would
            // rewrite that checkout's agent links from a home that does not own
            // them. The condition is one fact about the home
            // (ArtifactBackfill's `foreign-home` link state) and the repair is
            // to re-create the home, not to re-derive N bindings.
            case PROJECTION -> "foreign-home".equals(artifact.actual().get("link_state"))
                    ? "this home is a copy of "
                    + artifact.actual().getOrDefault("copied_from_home", "another home")
                    + " that was never re-anchored, so its bindings' destinations are in THAT "
                    + "home's checkout — `sync` or `rebind` here would rewrite another "
                    + "checkout's agent links. Re-create this home with `skill-manager home "
                    + "clone`; nothing about this projection alone needs rebuilding"
                    : "a projection is re-derived by its binding — "
                    + "`skill-manager sync` (or `skill-manager rebind "
                    + nameOr(artifact, "<unit>") + "`)";
            case MARKETPLACE_ENTRY -> "the marketplace is regenerated whole, not per entry, so "
                    + "rebuilding one row here would report work over rows it did not check — "
                    + "`skill-manager sync`";
            case HARNESS_INSTANCE -> "a harness instance is instantiated, not rebuilt — "
                    + "`skill-manager harness instantiate` / `skill-manager sync harness:<name>`";
            // The clause that stood here — "and nothing records what was posted
            // (#120), so a rebuild here could not be told from a no-op" — was
            // true when ARTI-06 wrote it and stopped being true when ARTI-17
            // landed on this branch: mcp-lock.json records the payload digest,
            // and a registration IS now decidable against its declaration. What
            // did not change is whose write it is, which is the real reason
            // `build` declines it.
            case MCP_REGISTRATION -> "a gateway registration is a write to the GATEWAY, not to "
                    + "this home, so `build` is not the verb that makes one — "
                    + "`skill-manager sync --include-mcp`";
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
     * The artifacts {@code build} can rebuild to clear a set of unresolved
     * references — the join that lets {@code home verify}'s remedy be about the
     * diagnosis it was printed under.
     *
     * <h2>Why the remedy is scoped, and not {@code --stale}</h2>
     *
     * <p>{@code home verify} names N specific references. Printing
     * {@code build --stale} beneath them prescribes a WHOLE-HOME action for a
     * per-instance finding, which is the exact asymmetry this ticket exists to
     * remove, restated one verb later. It is also measurably wrong as a remedy:
     * on the operator's project home {@code build --stale} selects 55 artifacts
     * and plans 18 rebuilds, of which 11 are lock rows recording a
     * {@code binary} their install never produced — so the command exits 1 over
     * artifacts that have nothing to do with the reference it was printed for.
     * {@code HomeFixpointLaw} parses that printed string and runs it, and its
     * law ("the remedy it printed must clear it, first try") is a good law. The
     * fix is to print a remedy that answers its own question.
     *
     * <p>Resolution is by OUTPUT PATH, the way {@link ArtifactGraph} resolves
     * everything else: an unresolved entry is {@code <home-relative path> ->
     * <what it names>} and the left side IS the artifact's output. Never by
     * parsing a shim name back into a lock key — {@code bin/cli/tofu} comes
     * from {@code brew:opentofu}, and a rule that works for one kind and fails
     * silently for another is the failure {@link ArtifactGraph}'s javadoc
     * already records.
     *
     * <p>Only artifacts with a producer this command can invoke are returned.
     * Naming one {@code build} would decline gives a remedy that runs, exits 0
     * and repairs nothing, which is worse than a general one.
     */
    public static List<String> buildableFor(SkillStore store, List<String> unresolvedReferences) {
        if (unresolvedReferences == null || unresolvedReferences.isEmpty()) return List.of();
        ArtifactIndex index;
        try {
            index = ArtifactIndex.of(store);
        } catch (RuntimeException | java.io.IOException e) {
            // Never worth failing the check over. The caller falls back to the
            // general command, which says nothing untrue either.
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String reference : unresolvedReferences) {
            if (reference == null) continue;
            int arrow = reference.indexOf(" -> ");
            String path = (arrow < 0 ? reference : reference.substring(0, arrow))
                    .trim().replace('\\', '/');
            if (!path.isEmpty()) wanted.add(path);
        }
        Map<String, ArtifactFreshness.CliProducer> producers =
                ArtifactFreshness.cliProducers(store);
        List<String> out = new ArrayList<>();
        for (Artifact artifact : index.artifacts()) {
            if (cliProducerOf(artifact, producers) == null) continue;
            for (Artifact.Output output : artifact.outputs()) {
                if (output.scope() != Artifact.Scope.HOME) continue;
                if (!wanted.contains(output.path())) continue;
                if (!out.contains(artifact.id())) out.add(artifact.id());
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * An artifact id as a shell word.
     *
     * <p>{@code cli-shim:pip/jinja2-cli[yaml]} is a real id in a real home and
     * {@code [yaml]} is a glob. The printed remedy is EXECUTED — by an operator
     * pasting it and by {@code HomeFixpointLaw}, which runs it through
     * {@code sh -c} — so an id that reaches a shell unquoted is a remedy that
     * silently addresses a different artifact, or none.
     */
    public static String shellWord(String id) {
        return "'" + id.replace("'", "'\\''") + "'";
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
