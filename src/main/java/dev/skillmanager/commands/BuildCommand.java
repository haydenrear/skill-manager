package dev.skillmanager.commands;

import dev.skillmanager.artifacts.ArtifactBuild;
import dev.skillmanager.artifacts.ArtifactCycleException;
import dev.skillmanager.artifacts.ArtifactFreshness;
import dev.skillmanager.artifacts.ArtifactIndex;
import dev.skillmanager.artifacts.BuildReport;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.NotAHomeException;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager build} — repair ONE derived artifact instead of all of
 * them.
 *
 * <h2>The asymmetry this closes</h2>
 *
 * <p>{@code home verify} has always been able to diagnose a missing artifact
 * per instance and prescribe only {@code sync --force-scripts}, which reruns
 * every skill-script in the home — three {@code deploy-helm} venvs at ~530 MB
 * each — to repair one shim. That asymmetry is why provisioning is eager: if
 * the only repair is total, it has to happen up front, and the front door pays
 * for it (#97). This is the per-artifact repair the diagnosis always implied,
 * and it is now the remedy {@code home verify} prints.
 *
 * <h2>What it will not do, and why that is the design</h2>
 *
 * <p>{@link ArtifactBuild} decides what is buildable, and it refuses two things
 * on purpose: a {@code provisioned-tree} whose owner was inferred by
 * containment rather than recorded, and any claim of freshness for a backend
 * that records no fingerprint. Both refusals are measurements from ARTI-05's
 * review, not gaps — the class javadoc there carries them.
 *
 * <p>The consequence worth stating here: {@code build} names artifacts it
 * cannot rebuild, with the command that can, rather than either silently
 * dropping them or pretending. A verb whose report is a superset of its
 * capability is the only honest shape when the capability is partial.
 *
 * <h2>Freshness afterwards is MEASURED</h2>
 *
 * <p>After the effects run, the index and every verdict are re-derived from
 * disk and printed per artifact. A rebuild through a backend that records
 * nothing (0 of 16 {@code brew}/{@code npm}/{@code pip}/{@code tar} rows carry
 * an install fingerprint — #120) will still read {@code unverifiable}, and the
 * output says so. Reporting those as fresh because the command exited 0 would
 * be the presence proxy this epic exists to remove, wearing a new verb.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — nothing was asked for, or everything that ran succeeded.
 *       Includes the case where stale artifacts remain that this command cannot
 *       rebuild: they are named, loudly, with the command that does. Exiting
 *       non-zero for work that was never this verb's would make the printed
 *       remedy fail after doing its job correctly.</li>
 *   <li>{@code 1} — a rebuild failed, or a rebuilt artifact is still stale.</li>
 *   <li>{@code 2} — usage, or an artifact id this home does not hold.</li>
 *   <li>{@code 5} / {@code 6} — the {@code policy.install} gate; see
 *       {@link SkillEffect.CheckBuildPolicyGate}.</li>
 * </ul>
 */
@Command(name = "build",
        description = "Rebuild derived artifacts — one, some, or everything stale.",
        descriptionHeading = "%n",
        footer = {
                "",
                "What build does:",
                "  Re-derives ONE artifact through the backend that declared it, instead of",
                "  rerunning every install in the home. `skill-manager artifacts stale` names",
                "  what needs it; this rebuilds it.",
                "",
                "  With no argument it builds what is stale. Naming an artifact builds that",
                "  artifact and its STALE prerequisites, and nothing else.",
                "",
                "Only cli-shim artifacts have a per-artifact producer today. Everything else",
                "is reported with the command that rebuilds it (`sync`, `rebind`, `harness",
                "instantiate`) and is never claimed to have been built here.",
                "",
                "Examples:",
                "  skill-manager build                          # everything stale",
                "  skill-manager build --stale --dry-run --json # what it would do",
                "  skill-manager build cli-shim:skill-script/skt",
                "  skill-manager build cli-shim:pip/pytest --force"
        })
public final class BuildCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<artifact>",
            description = "Artifact ids to rebuild, e.g. cli-shim:skill-script/skt. "
                    + "With none, everything stale is built.")
    public List<String> artifacts = new ArrayList<>();

    @Option(names = "--stale",
            description = "Build every stale artifact. The default when no artifact is named.")
    public boolean stale;

    @Option(names = "--all",
            description = "Build every artifact this command has a producer for, stale or not.")
    public boolean all;

    @Option(names = "--force",
            description = "Rerun the install even when the recorded fingerprint still matches — "
                    + "`install --force-scripts` at artifact granularity. Reaches skill-script "
                    + "backends; the others decide for themselves whether a present artifact is "
                    + "reinstalled.")
    public boolean force;

    @Option(names = "--dry-run", description = "Print what would be built and change nothing.")
    public boolean dryRun;

    @Option(names = {"-y", "--yes"},
            description = "Skip interactive confirmation. Blocked by policy.install gates the "
                    + "same way `install --yes` is — this is not a bypass.")
    public boolean yes;

    @Option(names = "--json", description = "Emit machine-readable JSON.")
    public boolean json;

    /** Test seam, as on {@code SyncCommand}. */
    public SkillStore injectedStore;

    @Override
    public Integer call() throws Exception {
        SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
        if (!store.isHome()) {
            Log.error("%s is not a Skill Manager home", store.root());
            return NotAHomeException.EXIT_CODE;
        }
        if (all && !artifacts.isEmpty()) {
            Log.error("--all builds everything this command can build; naming an artifact as well "
                    + "asks for two different sets. Pick one.");
            return 2;
        }
        // A frozen home refuses a rebuild for the same reason it refuses a
        // sync: the artifact this would rewrite is part of what was frozen.
        try {
            dev.skillmanager.policy.HomePolicy.requireLive(store, "build");
        } catch (dev.skillmanager.policy.FrozenHomeException frozen) {
            Log.error("%s", frozen.getMessage());
            return dev.skillmanager.policy.FrozenHomeException.EXIT_CODE;
        }

        ArtifactIndex index;
        ArtifactFreshness freshness;
        try {
            index = ArtifactIndex.of(store);
            freshness = ArtifactFreshness.of(index, store);
        } catch (ArtifactCycleException cycle) {
            Log.error("%s", cycle.getMessage());
            return 2;
        }

        if (!artifacts.isEmpty()) {
            Map<String, List<String>> unknown = ArtifactBuild.unknownIds(index, artifacts);
            if (!unknown.isEmpty()) {
                for (Map.Entry<String, List<String>> miss : unknown.entrySet()) {
                    Log.error("no artifact with id %s in %s", miss.getKey(), index.home());
                    if (!miss.getValue().isEmpty()) {
                        System.err.println("  did you mean:");
                        Log.errorList("    ", miss.getValue());
                    }
                }
                return 2;
            }
        }

        ArtifactBuild.Scope scope = !artifacts.isEmpty() ? ArtifactBuild.Scope.NAMED
                : all ? ArtifactBuild.Scope.ALL
                      : ArtifactBuild.Scope.STALE;
        ArtifactBuild.Plan plan =
                ArtifactBuild.of(index, freshness, store, scope, artifacts, force);

        if (plan.isEmpty()) {
            if (json) {
                return JsonOutput.print(BuildReport.empty(index.home().toString(), dryRun)) ? 0 : 2;
            }
            Log.ok("nothing to build in %s%s", index.home(),
                    scope == ArtifactBuild.Scope.STALE ? " — no artifact is stale" : "");
            return 0;
        }

        List<ArtifactBuild.Step> rebuilds = plan.rebuilds();
        Map<String, String> outcomes = BuildReport.outcomes();

        // ---------------------------------------------------------- the program
        //
        // ONE program, as Program's contract requires: the gate, the per-artifact
        // rebuilds, and the audit line. Routing through the effect program is
        // what gives --dry-run, receipts and audit.log the same shape they have
        // for install and sync, rather than a second spelling of each.
        List<SkillEffect> effects = new ArrayList<>();
        List<CliDependency> gated = new ArrayList<>();
        for (ArtifactBuild.Step step : rebuilds) gated.add(step.dep());
        if (!dryRun) effects.add(new SkillEffect.CheckBuildPolicyGate(gated, yes));
        for (ArtifactBuild.Step step : rebuilds) {
            effects.add(new SkillEffect.RebuildCliArtifact(step.id(), step.unitName(), step.dep(),
                    force));
        }
        // Named targets, not a plan: `build` builds no InstallPlan, so a
        // plan-only audit would log nothing at all — the exact gap #45/T44 was
        // filed on, one verb later. One line per artifact, through the same
        // AuditLog.record every other verb writes through.
        List<String> auditTargets = new ArrayList<>();
        for (ArtifactBuild.Step step : rebuilds) {
            auditTargets.add("rebuild " + step.id() + " via " + step.producer()
                    + (force ? " (force)" : ""));
        }
        if (!auditTargets.isEmpty()) {
            effects.add(new SkillEffect.RecordAuditPlan("build", auditTargets));
        }

        Program<List<EffectReceipt>> program =
                new Program<>("build-" + plan.steps().size(), effects, receipts -> receipts);

        int exit = 0;
        // `--json` emits exactly ONE document, and it is the BuildReport.
        //
        // Both interpreters emit their own JSON document when constructed with
        // json=true, and both print human lines to STDOUT when constructed with
        // json=false — so either spelling puts two documents, or a document and
        // prose, on the same stream. They are run in human mode with stdout
        // pointed at stderr instead: nothing is lost (the progress lines are
        // still there, on the stream a --json caller is not parsing), and
        // stdout carries one parseable document. The receipts are not dropped
        // either — every one of them is joined back onto an artifact row below.
        java.io.PrintStream realOut = System.out;
        try {
            if (json) System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(
                    java.io.FileDescriptor.err), true));
            if (dryRun) {
                new DryRunInterpreter(store).run(program);
            } else if (!effects.isEmpty()) {
                GatewayConfig gw = GatewayConfig.resolve(store, null);
                Executor.Outcome<List<EffectReceipt>> outcome =
                        new Executor(store, gw).run(program);
                exit = record(outcome.result(), outcomes);
                if (!outcome.applied().isEmpty()) {
                    // Only said when compensations really ran.
                    // RebuildCliArtifact yields none by design, so this line is
                    // about something else having gone wrong and must not be
                    // printed on every failure.
                    Log.warn("build rolled back %d effect(s)", outcome.applied().size());
                }
            }
        } finally {
            System.setOut(realOut);
        }

        // ------------------------------------------------------------- measure
        //
        // Re-derived from disk, never inferred from the receipts. "The install
        // returned 0" and "the artifact now describes its inputs" are different
        // claims and this epic exists because they were the same one.
        ArtifactFreshness after = null;
        if (!dryRun && !rebuilds.isEmpty()) {
            try {
                after = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            } catch (ArtifactCycleException | IOException reread) {
                Log.warn("build: could not re-read this home to confirm what it built: %s",
                        reread.getMessage());
            }
        }

        BuildReport report = BuildReport.of(index.home().toString(), dryRun, plan, outcomes, after);
        if (json) {
            if (!JsonOutput.print(report)) return 2;
        } else {
            render(index.home().toString(), plan, report, after, dryRun);
        }
        if (report.summary().failed() > 0) exit = 1;
        // An artifact this command ATTEMPTED and did not repair is a failure of
        // this run, whether the producer errored, produced nothing, or produced
        // something that is still stale. All three are "I tried and the home
        // does not hold it".
        //
        // What is NOT exit 1: an artifact this command never attempted. A
        // `not-buildable` row is named, loudly, with the command that does
        // rebuild it, and it leaves the exit code alone — a verdict about work
        // that was never this verb's would make every printed remedy fail on
        // any home with a stale projection in it.
        for (BuildReport.StepView view : report.steps()) {
            boolean attempted = BuildReport.BUILT.equals(view.outcome())
                    || BuildReport.NO_OP.equals(view.outcome());
            if (attempted && "stale".equals(view.freshnessAfter())) exit = 1;
        }
        return exit;
    }

    /** Join receipts back onto artifact ids. */
    private static int record(List<EffectReceipt> receipts, Map<String, String> outcomes) {
        int exit = 0;
        for (EffectReceipt receipt : receipts) {
            if (receipt.effect() instanceof SkillEffect.RebuildCliArtifact rebuild) {
                // SKIPPED is the handler's typed signal that the backend ran
                // and wrote nothing — a status rather than a sniffed summary
                // string, which is exactly why the handler uses it.
                outcomes.put(rebuild.artifactId(),
                        switch (receipt.status()) {
                            case FAILED -> BuildReport.FAILED;
                            case SKIPPED -> BuildReport.NO_OP;
                            default -> BuildReport.BUILT;
                        });
            }
            for (ContextFact fact : receipt.facts()) {
                if (fact instanceof ContextFact.HaltWithExitCode halt) {
                    if (exit == 0) exit = halt.code();
                }
            }
        }
        return exit;
    }

    // ------------------------------------------------------------------ output

    private static void render(String home, ArtifactBuild.Plan plan, BuildReport report,
                               ArtifactFreshness after, boolean dryRun) {
        System.out.println((dryRun ? "build (dry run) — " : "build — ") + home);
        System.out.println();

        Map<String, BuildReport.StepView> views = new LinkedHashMap<>();
        for (BuildReport.StepView view : report.steps()) views.put(view.id(), view);

        for (ArtifactBuild.Step step : plan.steps()) {
            if (step.action() != ArtifactBuild.Action.REBUILD) continue;
            BuildReport.StepView view = views.get(step.id());
            System.out.printf("  %s %s%n", dryRun ? "would build" : outcomeMark(view), step.id());
            System.out.println("      " + step.reason());
            System.out.println("      producer: " + step.producer());
            if (view != null && view.freshnessAfter() != null) {
                // The measured half. Spelled out for the unverifiable case
                // rather than left as a bare token, because "unverifiable"
                // after a successful build reads like a failure and is not one.
                System.out.println("      now: " + view.freshnessAfter()
                        + describeAfter(after, step.id(), view));
                boolean attempted = BuildReport.BUILT.equals(view.outcome())
                        || BuildReport.NO_OP.equals(view.outcome());
                if (attempted && "stale".equals(view.freshnessAfter())) {
                    // An attempt that left the artifact absent. Said outright,
                    // and WITHOUT a cause invented for it beyond what the
                    // backend itself reported — guessing would be a third claim
                    // about an artifact two claims already disagree about.
                    Log.error("      the producer ran and this home still does not hold the "
                            + "artifact%s. This is not a repair.",
                            BuildReport.NO_OP.equals(view.outcome())
                                    ? " — it reported the dependency already satisfied from "
                                            + "outside this home and wrote nothing"
                                    : " — see the run log");
                }
            }
        }
        List<ArtifactBuild.Step> current = new ArrayList<>();
        for (ArtifactBuild.Step step : plan.steps()) {
            if (step.action() == ArtifactBuild.Action.ALREADY_CURRENT) current.add(step);
        }
        for (ArtifactBuild.Step step : current) {
            System.out.printf("  skipped     %s%n", step.id());
            System.out.println("      " + step.reason());
        }

        List<ArtifactBuild.Step> notBuildable = plan.notBuildable();
        if (!notBuildable.isEmpty()) {
            System.out.println();
            // Not a footnote. These are artifacts the caller asked about and
            // this command did not repair; burying them under a success line is
            // how a partial repair gets read as a complete one.
            System.out.println("not rebuilt here — nothing in `build` produces these:");
            for (ArtifactBuild.Step step : notBuildable) {
                System.out.printf("  %s  (%s)%n", step.id(), step.before().token());
                System.out.println("      " + step.reason());
            }
        }

        System.out.println();
        BuildReport.Summary summary = report.summary();
        System.out.printf("%d selected: %d %s, %d already current, %d not buildable here%n",
                summary.selected(),
                dryRun ? summary.selected() - summary.alreadyCurrent() - summary.notBuildable()
                       : summary.rebuilt(),
                dryRun ? "to build" : "built",
                summary.alreadyCurrent(), summary.notBuildable());
        if (summary.noOp() > 0) {
            // Never folded into "built". A run that reports 18 rebuilt over a
            // home where 11 producers wrote nothing is the presence proxy
            // inverted: work claimed on the strength of an exit code.
            Log.error("%d producer(s) ran and wrote nothing — the backend reported the "
                    + "dependency already satisfied from outside this home", summary.noOp());
        }
        if (summary.failed() > 0) {
            Log.error("%d rebuild(s) failed", summary.failed());
        }
        if (!dryRun && summary.stillStale() > 0) {
            System.out.printf("%d of the selected artifact(s) are still stale%n",
                    summary.stillStale());
        }
    }

    private static String outcomeMark(BuildReport.StepView view) {
        if (view == null) return "built      ";
        return switch (view.outcome()) {
            case BuildReport.BUILT -> "built      ";
            case BuildReport.NO_OP -> "no-op      ";
            case BuildReport.FAILED -> "FAILED     ";
            default -> "skipped    ";
        };
    }

    /**
     * The measured post-build sentence.
     *
     * <p>The verdict's own reason, always — including when it is
     * {@code unverifiable}, where the reason already names which side of the
     * comparison is missing. The #120 caveat is APPENDED to that rather than
     * printed instead of it: the first version of this method printed the
     * caveat from the PLAN-time expectation and so announced "nothing can
     * confirm what it produced" over an artifact the rebuild had just made
     * confirmable, which is the same substitution of a stale claim for a fresh
     * measurement this whole epic is about.
     */
    private static String describeAfter(ArtifactFreshness after, String id,
                                        BuildReport.StepView view) {
        if (after == null) return "";
        ArtifactFreshness.Verdict verdict = after.of(id);
        if (verdict == null) return "";
        String caveat = view.verifiable() ? ""
                : " (its backend records no install fingerprint, so this home cannot confirm "
                        + "what the rebuild produced — #120)";
        return " — " + verdict.reason() + caveat;
    }
}
