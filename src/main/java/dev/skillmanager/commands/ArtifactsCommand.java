package dev.skillmanager.commands;

import dev.skillmanager.artifacts.Artifact;
import dev.skillmanager.artifacts.ArtifactCycleException;
import dev.skillmanager.artifacts.ArtifactFreshness;
import dev.skillmanager.artifacts.ArtifactGraph;
import dev.skillmanager.artifacts.ArtifactIndex;
import dev.skillmanager.artifacts.ArtifactKind;
import dev.skillmanager.artifacts.ArtifactLedger;
import dev.skillmanager.artifacts.ArtifactPrune;
import dev.skillmanager.artifacts.ArtifactReport;
import dev.skillmanager.artifacts.StaleReport;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.NotAHomeException;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager artifacts} — name what this home derived.
 *
 * <p>A home derives nine classes of thing and, before this command, could not
 * answer "what did you build, and from what?" in one place. {@code list} and
 * {@code show} are the read surface; {@code record} is the only writer, and it
 * writes {@code artifacts.lock.toml} and nothing else.
 *
 * <p><b>This command installs nothing and rebuilds nothing.</b> A home that
 * predates it lists correctly with no migration, because
 * {@link dev.skillmanager.artifacts.ArtifactBackfill} reads the records that
 * were already there.
 *
 * <p>The sentence above used to read "it does not install, rebuild, prune or
 * repair", and ARTI-08 makes one word of that false: {@code prune} deletes.
 * It is the only subcommand that does, it deletes only paths the ledger
 * recorded, and {@link dev.skillmanager.artifacts.ArtifactPrune} carries the
 * argument for why a disposal decision in this program may never be made by
 * looking at a directory.
 */
@Command(name = "artifacts",
        description = "Name and inspect the artifacts this home derived.",
        subcommands = {
                ArtifactsCommand.ListArtifacts.class,
                ArtifactsCommand.ShowArtifact.class,
                ArtifactsCommand.StaleArtifacts.class,
                ArtifactsCommand.RecordLedger.class,
                ArtifactsCommand.PruneArtifacts.class
        })
public final class ArtifactsCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    // ------------------------------------------------------------------ list

    /**
     * {@code skill-manager artifacts list} — every artifact, one line each.
     *
     * <p>Read-only in the strict sense the {@link dev.skillmanager.cli.CommandHomeAccess}
     * table means: run against a path that is not a home it produces a refusal
     * and no bytes.
     */
    @Command(name = "list",
            description = "List every derived artifact this home holds.")
    public static final class ListArtifacts implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--kind", paramLabel = "<kind>",
                description = "Only this kind, e.g. cli-shim, unit-store, projection.")
        String kind;

        @Option(names = "--owner", paramLabel = "<unit>",
                description = "Only artifacts owned by this unit.")
        String owner;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;

            ArtifactKind wanted = null;
            if (kind != null && !kind.isBlank()) {
                wanted = ArtifactKind.fromId(kind);
                if (wanted == null) {
                    Log.error("unknown artifact kind: %s (known: %s)", kind, knownKinds());
                    return 2;
                }
            }

            ArtifactIndex index = ArtifactIndex.of(store);
            List<Artifact> selected = new ArrayList<>();
            for (Artifact artifact : index.artifacts()) {
                if (wanted != null && artifact.kind() != wanted) continue;
                if (owner != null && !owner.isBlank() && !owner.equals(artifact.owner())) continue;
                selected.add(artifact);
            }

            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, selected)) ? 0 : 2;
            }
            render(index, selected);
            return 0;
        }

        private static void render(ArtifactIndex index, List<Artifact> selected) {
            System.out.println("artifacts — " + index.home());
            System.out.println(index.ledgerPresent()
                    ? "ledger: " + ArtifactLedger.FILENAME + " ("
                            + index.ledger().rows().size() + " row(s), recorded "
                            + index.ledger().recordedAt() + ")"
                    : "ledger: none — backfilled from this home's existing records");
            System.out.println();
            int width = 0;
            for (Artifact artifact : selected) width = Math.max(width, artifact.id().length());
            for (Artifact artifact : selected) {
                System.out.printf("%-" + Math.max(width, 8) + "s  %-14s %-9s %s%n",
                        artifact.id(),
                        lower(artifact.materialization().name()),
                        lower(artifact.agreement().name()),
                        artifact.owner() == null ? "" : artifact.owner());
            }
            System.out.println();
            System.out.printf("%d artifact(s) across %d kind(s)%n",
                    selected.size(),
                    (int) selected.stream().map(Artifact::kind).distinct().count());
        }
    }

    // ------------------------------------------------------------------ show

    /** {@code skill-manager artifacts show <id>} — one artifact, in full. */
    @Command(name = "show",
            description = "Show one artifact: its inputs, its outputs, and what is recorded about it.")
    public static final class ShowArtifact implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<id>",
                description = "Artifact id, e.g. cli-shim:brew/kubectl.")
        String id;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;

            ArtifactIndex index = ArtifactIndex.of(store);
            var found = index.byId(id);
            if (found.isEmpty()) {
                Log.error("no artifact with id %s in %s", id, index.home());
                List<String> near = index.idsMatching(id);
                if (!near.isEmpty()) {
                    System.err.println("  did you mean:");
                    Log.errorList("    ", near);
                }
                return 1;
            }
            Artifact artifact = found.get();
            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, List.of(artifact))) ? 0 : 2;
            }
            System.out.println("id:              " + artifact.id());
            System.out.println("kind:            " + artifact.kind().id());
            System.out.println("owner:           "
                    + (artifact.owner() == null ? "(none — nothing in this home owns it)"
                                                : artifact.owner()));
            System.out.println("materialization: " + lower(artifact.materialization().name()));
            System.out.println("agreement:       " + lower(artifact.agreement().name()));
            System.out.println("origin:          " + lower(artifact.origin().name()));
            System.out.println("source:          "
                    + (artifact.source() == null ? "(none — no record declares it)"
                                                 : artifact.source()));
            System.out.println("inputs:");
            if (artifact.inputs().isEmpty()) System.out.println("  (none declared)");
            for (String input : artifact.inputs()) System.out.println("  " + input);
            if (!artifact.observedInputs().isEmpty()) {
                // Printed apart from the declared ones, and labelled, because
                // these are re-read on every pass and never written down.
                System.out.println("observed inputs (read off disk, never recorded):");
                for (String input : artifact.observedInputs()) System.out.println("  " + input);
            }
            System.out.println("outputs:");
            if (artifact.outputs().isEmpty()) System.out.println("  (none — the source record is the artifact)");
            for (Artifact.Output output : artifact.outputs()) {
                System.out.printf("  %-10s %-8s %s%n",
                        lower(output.presence().name()), lower(output.scope().name()), output.path());
            }
            if (!artifact.recorded().isEmpty()) {
                System.out.println("recorded:");
                artifact.recorded().forEach((k, v) -> System.out.println("  " + k + " = " + v));
            }
            if (!artifact.actual().isEmpty()) {
                System.out.println("actual:");
                artifact.actual().forEach((k, v) -> System.out.println("  " + k + " = " + v));
            }
            return 0;
        }
    }

    // ----------------------------------------------------------------- stale

    /**
     * {@code skill-manager artifacts stale} — what a moved unit made stale.
     *
     * <p>The question the whole epic exists for, and the one a home could not
     * answer: not "does a file exist there" but "does what is on disk still
     * describe the inputs it was built from". A verdict is reached by
     * RE-DERIVING each artifact's input fingerprint now and comparing it with
     * what its producer recorded, then by propagating along
     * {@link ArtifactGraph}'s edges — so a unit moving names its shims, the
     * trees they run out of, and everything downstream of those.
     *
     * <p><b>Read-only, and it prescribes nothing.</b> No install, no rebuild,
     * no repair, and no ledger write. Turning a verdict into a rebuild is the
     * {@code build} verb's job.
     *
     * <p><b>Exit code 0 whatever it finds.</b> A read command that exits
     * non-zero on drift is a read command that gets wrapped in {@code || true}
     * by the first script that calls it, and the finding then goes to nobody.
     * The counts are in the summary and in the JSON.
     */
    @Command(name = "stale",
            description = "Name every artifact whose inputs no longer match what was recorded.")
    public static final class StaleArtifacts implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--kind", paramLabel = "<kind>",
                description = "Only this kind, e.g. cli-shim, provisioned-tree.")
        String kind;

        @Option(names = "--unverifiable",
                description = "Also print the artifacts that could not be decided, and why. "
                        + "Always present in --json.")
        boolean unverifiable;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;

            ArtifactKind wanted = null;
            if (kind != null && !kind.isBlank()) {
                wanted = ArtifactKind.fromId(kind);
                if (wanted == null) {
                    Log.error("unknown artifact kind: %s (known: %s)", kind, knownKinds());
                    return 2;
                }
            }

            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactFreshness freshness;
            try {
                freshness = ArtifactFreshness.of(index, store);
            } catch (ArtifactCycleException cycle) {
                // A plan error, not a stack overflow: the chain is named and
                // the command refuses rather than reporting a partial graph.
                Log.error("%s", cycle.getMessage());
                return 2;
            }

            // `wanted` reaches BOTH renderings. It used to reach only the human
            // one, so `--json --kind cli-shim` silently emitted every kind: a
            // filter that validates its argument, prints no error and is then
            // discarded is worse than one that does not exist.
            if (json) {
                return JsonOutput.print(
                        StaleReport.of(index.home().toString(), freshness, wanted)) ? 0 : 2;
            }
            render(index, freshness, wanted, unverifiable);
            return 0;
        }

        /**
         * Every line here is scoped by {@code wanted}, counts included.
         * Printing a filtered list above unfiltered totals produced an all-clear
         * ("nothing … disagrees with its inputs") directly above "49 stale" in
         * the same output.
         */
        private static void render(ArtifactIndex index, ArtifactFreshness freshness,
                                   ArtifactKind wanted, boolean withUnverifiable) {
            System.out.println("stale artifacts — " + index.home()
                    + (wanted == null ? "" : " (kind " + wanted.id() + ")"));
            System.out.println();
            List<ArtifactFreshness.Verdict> stale =
                    freshness.withFreshness(ArtifactFreshness.Freshness.STALE, wanted);
            printGroup(stale, freshness);
            if (stale.isEmpty()) {
                System.out.println("  nothing "
                        + (wanted == null ? "recorded in this home" : "of that kind")
                        + " disagrees with its inputs.");
            }
            List<ArtifactFreshness.Verdict> undecided =
                    freshness.withFreshness(ArtifactFreshness.Freshness.UNVERIFIABLE, wanted);
            if (withUnverifiable && !undecided.isEmpty()) {
                System.out.println();
                System.out.println("could not be decided:");
                printGroup(undecided, freshness);
            }
            System.out.println();
            System.out.printf("%d stale, %d unverifiable, %d current, of %d artifact(s)%n",
                    stale.size(),
                    undecided.size(),
                    freshness.count(ArtifactFreshness.Freshness.CURRENT, wanted),
                    freshness.total(wanted));
            if (!withUnverifiable && !undecided.isEmpty()) {
                System.out.println("  (--unverifiable names the ones nothing in this home could "
                        + "decide; an undecided artifact is not a current one)");
            }
        }

        private static void printGroup(List<ArtifactFreshness.Verdict> verdicts,
                                       ArtifactFreshness freshness) {
            for (ArtifactFreshness.Verdict verdict : verdicts) {
                System.out.printf("  %s%s%n", verdict.id(),
                        verdict.owner() == null ? "" : "  (" + verdict.owner() + ")");
                System.out.println("      " + verdict.reason());
                List<String> downstream = new ArrayList<>(
                        freshness.graph().downstreamOf(verdict.id()));
                if (!downstream.isEmpty()) {
                    System.out.println("      feeds: " + String.join(", ", downstream));
                }
            }
        }
    }

    // ---------------------------------------------------------------- record

    /**
     * {@code skill-manager artifacts record} — persist the ledger.
     *
     * <p>Its own verb rather than a flag on {@code list}, so that {@code list}
     * can stay in the READ_ONLY column of
     * {@link dev.skillmanager.cli.CommandHomeAccess} without an exception. The
     * shape is {@code home drift --record}'s: a read that reports, and a
     * separate gesture that writes a baseline.
     *
     * <p>It records the <b>merged</b> set, not the backfilled one. Recording in
     * a home that skipped {@code cache/} must not erase the declaration that
     * those trees exist — that would delete the only evidence a clone has of
     * what it is missing, which is the knowledge the whole ledger is for.
     */
    @Command(name = "record",
            description = "Write " + ArtifactLedger.FILENAME + " from what this home holds.")
    public static final class RecordLedger implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactLedger ledger = ArtifactLedger.of(index.artifacts());
            ledger.save(store);
            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, index.artifacts())) ? 0 : 2;
            }
            Log.ok("recorded %d artifact(s) to %s",
                    index.artifacts().size(), ArtifactLedger.file(store));
            return 0;
        }
    }

    // ----------------------------------------------------------------- prune

    /**
     * {@code skill-manager artifacts prune} — remove the artifacts whose owner
     * is gone.
     *
     * <p>For the orphans PAST removals already left behind, which the live
     * homes have accumulated: {@code PruneCliIfOrphan} removes a unit's lock
     * claim and its declared binary and nothing else, so the
     * {@code cache/skill-script-<unit>-<tool>/} tree and its venv outlive every
     * uninstall. Going forward the same walk runs as part of a removal; this
     * verb is how a home that predates that catches up.
     *
     * <p><b>Deletes only what the ledger recorded.</b> See
     * {@link ArtifactPrune} — the rule, and the three guards on top of it, are
     * that class's whole subject and are not restated here.
     *
     * <p>Exit 0 whether or not it finds anything, including under
     * {@code --dry-run}: "there are orphans" is a report, not a failure, and a
     * non-zero exit would make it unusable in the close-out of a script that
     * has just correctly removed a unit.
     */
    @Command(name = "prune",
            description = "Remove artifacts whose owning unit is no longer installed.")
    public static final class PruneArtifacts implements Callable<Integer> {

        @Option(names = "--dry-run", description = "Print what would be removed and change nothing.")
        boolean dryRun;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--owner", paramLabel = "UNIT",
                description = "Only artifacts owned by this unit. Repeatable.")
        List<String> owners = new java.util.ArrayList<>();

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;
            if (!dryRun) HomePolicy.requireLive(store, "artifacts prune");
            ArtifactPrune.Plan plan = ArtifactPrune.of(store, owners);
            List<String> pruned = dryRun ? List.of() : ArtifactPrune.apply(store, plan);
            if (json) return JsonOutput.print(PruneReport.of(plan, dryRun, pruned)) ? 0 : 2;
            return render(plan, pruned, store);
        }

        private int render(ArtifactPrune.Plan plan, List<String> pruned, SkillStore store) {
            if (!plan.ledgerPresent()) {
                // Named before anything else, because it changes what an empty
                // result MEANS: a home with no ledger cannot have orphans, it
                // can only have unrecorded ones.
                Log.warn("this home has no %s, so nothing can be pruned — everything this "
                                + "command would delete has to have been recorded first: "
                                + "`skill-manager artifacts record`",
                        ArtifactLedger.FILENAME);
            }
            List<ArtifactPrune.Step> targets = plan.prunes();
            if (targets.isEmpty() && plan.refusals().isEmpty()) {
                Log.ok("no orphaned artifacts in %s", plan.home());
                return 0;
            }
            for (ArtifactPrune.Step step : targets) {
                Log.info("%s %s  (%s)", dryRun ? "would remove" : "removed", step.id(),
                        step.reason());
                for (String path : step.paths()) Log.detail("    %s", path);
            }
            for (ArtifactPrune.Step step : plan.refusals()) {
                Log.warn("kept %s — %s", step.id(), step.reason());
            }
            if (dryRun) {
                Log.info("%d artifact(s) would be removed; re-run without --dry-run",
                        targets.size());
            } else {
                Log.ok("removed %d artifact(s) from %s", pruned.size(), store.root());
            }
            return 0;
        }
    }

    /** The {@code --json} shape for {@link PruneArtifacts}. */
    public record PruneReport(String home, boolean ledgerPresent, boolean dryRun,
                              List<Row> pruned, List<Row> kept) {

        public record Row(String id, String kind, String owner, String verdict,
                          List<String> paths, String reason) {}

        static PruneReport of(ArtifactPrune.Plan plan, boolean dryRun, List<String> applied) {
            List<Row> pruned = new java.util.ArrayList<>();
            List<Row> kept = new java.util.ArrayList<>();
            for (ArtifactPrune.Step step : plan.steps()) {
                Row row = new Row(step.id(), step.kind().id(), step.owner(),
                        step.verdict().token(), step.paths(), step.reason());
                if (step.prunes() && (dryRun || applied.contains(step.id()))) pruned.add(row);
                else kept.add(row);
            }
            return new PruneReport(plan.home(), plan.ledgerPresent(), dryRun, pruned, kept);
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * The ambient home, or null after printing the refusal.
     *
     * <p>A refusal rather than an empty listing, for the reason
     * {@link NotAHomeException} records: a report that says "0 artifacts" about
     * a path that is not a home reads exactly like a report about an empty one,
     * and the wrong path is usually the one already in the operator's hand.
     */
    static SkillStore requireHome() {
        SkillStore store = SkillStore.defaultStore();
        if (store.isHome()) return store;
        Log.error("%s is not a Skill Manager home", store.root());
        return null;
    }

    private static String knownKinds() {
        StringBuilder sb = new StringBuilder();
        for (ArtifactKind kind : ArtifactKind.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kind.id());
        }
        return sb.toString();
    }

    private static String lower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
