///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Direction A — root → project.</b> Scaffolding a project home from the
 * root home, and then keeping it current without ever costing the project an
 * edit.
 *
 * <h2>Two claims, and they are not the same claim</h2>
 *
 * <p>The first is that the scaffold is <em>complete</em>: every unit the root
 * home holds arrives byte-for-byte, nested directories included, and the copy
 * records the baseline that makes its own later edits recoverable. A copy that
 * is merely "close" is the failure mode the record exists to prevent, because
 * the first thing a wrong baseline does is turn a fast-forward into a conflict.
 *
 * <p>The second is that the <em>second</em> pass — the one that runs after the
 * root home has moved on and the project has made an edit of its own — is not
 * allowed to spend the project's edit to get the root's. Both halves are
 * asserted at once and deliberately: a pass that refreshes nothing loses
 * nothing, so "the edit survived" only means something alongside "the other
 * units did move".
 *
 * <h2>Bytes, then status</h2>
 *
 * <p>Every survival claim here is {@link HomeSyncSupport#difference} over the
 * unit's per-file digests taken immediately before the sync and immediately
 * after — not the reported status. A pass that reports {@code held-back} and
 * overwrites anyway, and one that reports {@code updated} and keeps the bytes,
 * are indistinguishable from a status string, and only one of them loses work.
 */
public class HomeSyncRootToProject {

    private static final String ALPHA = "hs-alpha";
    private static final String BETA = "hs-beta";
    private static final String GAMMA = "hs-gamma";
    /** Appears in the root home only after the project was scaffolded. */
    private static final String DELTA = "hs-delta";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.root.to.project")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "root-to-project", "no-destruction")
            .timeout("300s")
            .output("projectBetaContent", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String rootRaw = ctx.get("home.sync.fixture.built", "rootHome").orElse(null);
            String projectRaw = ctx.get("home.sync.fixture.built", "projectHome").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (rootRaw == null || projectRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.root.to.project", "missing upstream context");
            }
            Path root = Path.of(rootRaw);
            Path project = Path.of(projectRaw);

            // --- scaffold the project home ---------------------------------
            ProcessRecord clone = HomeSyncSupport.sm(ctx, "clone-root-to-project", ambient,
                    "home", "clone", "--from", rootRaw, "--to", projectRaw, "--json");
            boolean cloneOk = clone.exitCode() == 0
                    && HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "clone-root-to-project"),
                            "clean");

            List<String> incomplete = new ArrayList<>();
            for (String unit : List.of(ALPHA, BETA, GAMMA)) {
                incomplete.addAll(prefix(unit, HomeSyncSupport.difference(
                        HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(root, unit)),
                        HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, unit)))));
            }
            boolean scaffoldComplete = incomplete.isEmpty()
                    && Files.isRegularFile(HomeSyncSupport.unitDir(project, ALPHA)
                            .resolve("references/deep/note.md"));

            // The baseline is the only witness to what the two homes last
            // shared; without it the first reconcile back can only conflict.
            Path records = project.resolve(".materialization").resolve("skill");
            boolean baselineRecorded = true;
            boolean baselineNamesNoOtherHome = true;
            for (String unit : List.of(ALPHA, BETA, GAMMA)) {
                Path record = records.resolve(unit + ".json");
                if (!Files.isRegularFile(record)) baselineRecorded = false;
                else if (HomeSyncSupport.read(record).contains(rootRaw)) {
                    baselineNamesNoOtherHome = false;
                }
            }

            // A sync straight after the scaffold must find nothing to do. A
            // clone that produced a home the sync immediately wants to rewrite
            // would be a clone whose bytes and whose provenance disagree.
            ProcessRecord settled = HomeSyncSupport.sm(ctx, "sync-after-clone", ambient,
                    "home", "sync", "--from", rootRaw, "--to", projectRaw, "--json");
            Map<String, Object> settledReport = HomeSyncSupport.json(ctx, "sync-after-clone");
            boolean scaffoldIsSettled = settled.exitCode() == 0
                    && HomeSyncSupport.status(settledReport, "skill:" + ALPHA).equals("unchanged")
                    && HomeSyncSupport.status(settledReport, "skill:" + BETA).equals("unchanged")
                    && HomeSyncSupport.status(settledReport, "skill:" + GAMMA).equals("unchanged");

            // --- both homes move, on different units ------------------------
            HomeSyncSupport.append(HomeSyncSupport.unitDir(root, ALPHA).resolve("SKILL.md"),
                    "alpha v2 — improved upstream in the root home\n");
            HomeSyncSupport.append(
                    HomeSyncSupport.unitDir(root, ALPHA).resolve("references/deep/note.md"),
                    "nested reference v2\n");
            HomeSyncSupport.mkUnit(root, DELTA, "delta v1 — installed into the root home later");
            String projectBeta = HomeSyncSupport.read(
                    HomeSyncSupport.unitDir(project, BETA).resolve("SKILL.md"))
                    + "beta — EDITED IN THE PROJECT HOME\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(project, BETA).resolve("SKILL.md"),
                    projectBeta);
            HomeSyncSupport.write(HomeSyncSupport.unitDir(project, BETA).resolve("project-note.md"),
                    "a file only the project home has\n");

            LinkedHashMap<String, String> betaBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, BETA));
            LinkedHashMap<String, String> rootBefore = HomeSyncSupport.entryDigests(root);

            ProcessRecord sync = HomeSyncSupport.sm(ctx, "sync-root-to-project", ambient,
                    "home", "sync", "--from", rootRaw, "--to", projectRaw, "--json");
            Map<String, Object> report = HomeSyncSupport.json(ctx, "sync-root-to-project");

            // --- what must be true afterwards -------------------------------
            List<String> betaMoved = HomeSyncSupport.difference(betaBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, BETA)));
            List<String> rootMoved = HomeSyncSupport.difference(rootBefore,
                    HomeSyncSupport.entryDigests(root));
            List<String> alphaDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(root, ALPHA)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, ALPHA)));
            List<String> deltaDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(root, DELTA)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, DELTA)));

            boolean syncOk = sync.exitCode() == 0;
            boolean upstreamEditArrived =
                    HomeSyncSupport.status(report, "skill:" + ALPHA).equals("updated")
                            && alphaDrift.isEmpty();
            boolean newUnitArrived = HomeSyncSupport.status(report, "skill:" + DELTA).equals("new")
                    && deltaDrift.isEmpty();
            boolean untouchedUnitStaysUnchanged =
                    HomeSyncSupport.status(report, "skill:" + GAMMA).equals("unchanged");
            boolean projectEditHeldBack =
                    HomeSyncSupport.status(report, "skill:" + BETA).equals("held-back");
            boolean projectEditIntact = betaMoved.isEmpty();
            boolean sourceOnlyRead = rootMoved.isEmpty();
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty();
            // A unit that arrived by reconcile has content and no install
            // ledger entry: `home sync` visits unit directories, not state.
            boolean syncCarriesNoInstalledState =
                    !Files.exists(project.resolve("installed").resolve(DELTA + ".json"));

            boolean pass = cloneOk && scaffoldComplete && baselineRecorded
                    && baselineNamesNoOtherHome && scaffoldIsSettled && syncOk
                    && upstreamEditArrived && newUnitArrived && untouchedUnitStaysUnchanged
                    && projectEditHeldBack && projectEditIntact && sourceOnlyRead
                    && noStagingLeftovers && syncCarriesNoInstalledState;
            return (pass
                    ? NodeResult.pass("home.sync.root.to.project")
                    : NodeResult.fail("home.sync.root.to.project",
                            "cloneOk=" + cloneOk + " scaffoldComplete=" + scaffoldComplete
                                    + " incomplete=" + incomplete
                                    + " baselineRecorded=" + baselineRecorded
                                    + " scaffoldIsSettled=" + scaffoldIsSettled
                                    + " syncExit=" + sync.exitCode()
                                    + " alpha=" + HomeSyncSupport.status(report, "skill:" + ALPHA)
                                    + " beta=" + HomeSyncSupport.status(report, "skill:" + BETA)
                                    + " delta=" + HomeSyncSupport.status(report, "skill:" + DELTA)
                                    + " betaMoved=" + betaMoved + " rootMoved=" + rootMoved))
                    .process(clone).process(settled).process(sync)
                    .assertion("a_project_home_scaffolds_cleanly_from_the_root_home", cloneOk)
                    .assertion("every_root_unit_arrives_byte_for_byte_nested_files_included",
                            scaffoldComplete)
                    .assertion("the_scaffold_records_a_baseline_for_every_unit", baselineRecorded)
                    .assertion("no_recorded_baseline_names_the_home_it_was_copied_from",
                            baselineNamesNoOtherHome)
                    .assertion("a_sync_straight_after_the_scaffold_finds_nothing_to_do",
                            scaffoldIsSettled)
                    .assertion("a_root_side_edit_reaches_the_project_home", upstreamEditArrived)
                    .assertion("a_unit_added_upstream_after_the_scaffold_arrives", newUnitArrived)
                    .assertion("a_unit_neither_side_touched_is_reported_unchanged",
                            untouchedUnitStaysUnchanged)
                    .assertion("a_project_edited_unit_is_held_back", projectEditHeldBack)
                    .assertion("the_project_edit_keeps_every_byte_it_had", projectEditIntact)
                    .assertion("the_root_home_is_only_read_never_written", sourceOnlyRead)
                    .assertion("the_project_home_carries_no_staging_leftovers", noStagingLeftovers)
                    .assertion("a_unit_that_arrived_by_sync_carries_no_installed_state",
                            syncCarriesNoInstalledState)
                    .metric("scaffoldedUnits", 3)
                    .metric("projectUnitsAfterSync",
                            HomeSyncSupport.names(HomeSyncSupport.skills(project)).size())
                    .publish("projectBetaContent", projectBeta);
        });
    }

    private static List<String> prefix(String unit, List<String> entries) {
        List<String> out = new ArrayList<>();
        for (String entry : entries) out.add(unit + ": " + entry);
        return out;
    }
}
