///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CHM-15: {@code project resolve} / {@code project sync} interleaved with
 * {@code home sync} — the seam between the two writers into a project home.
 *
 * <h2>The sequence</h2>
 *
 * <pre>
 *   project resolve            R -&gt; P    materializes skills/u from the root store
 *   home sync --from P --to W            the ticket worktree's home
 *   (W edits SKILL.md)
 *   home sync --from W --to P --merge    the edit reaches the project home
 *   home close-out --home W --into P     exit 0 — "nothing would be lost"
 *   rm -rf W                             the point of no return
 *   project resolve            R -&gt; P    &lt;-- destroyed the edit
 * </pre>
 *
 * <p>Every command reported success. The last one reported the unit as
 * {@code MATERIALIZED}, detail <em>"copied from the parent store"</em>, and the
 * worktree's work then existed in no home at all. Nothing in the sequence is an
 * operator error: it is the documented epic flow, with the ordinary
 * dependency-refresh command run after it.
 *
 * <h2>Why the two writers disagreed</h2>
 *
 * <p>{@code home sync} and {@code project resolve} write into the same project
 * home through the same class, and both consult the same materialization
 * record — but they read it differently. The reconcile asked all three parts of
 * the <a href="../../../src/main/java/dev/skillmanager/bindings/ChildHomeMaterializer.java">baseline
 * rule</a>: the destination still holds what its record says, the record is
 * evidence about THIS source, and the tree is not a merge result. The downward
 * materialization asked only the first. After the merge, the project home's
 * record names the WORKTREE — so the tree was pristine by its own record while
 * being a wholesale copy of no store on earth, and the missing two clauses were
 * exactly the difference between refreshing something stale and deleting work
 * that exists nowhere else.
 *
 * <h2>Why an end-to-end node</h2>
 *
 * <p>A unit test can pin the predicate, and one does. It cannot reproduce this:
 * the defect only exists in the ORDER two different commands touch one home,
 * with a teardown in the middle, and neither command is wrong on its own. So
 * the sequence is run as the operator runs it — real CLI, real homes, real
 * {@code rm -rf} — and every claim is about BYTES read back off disk, never
 * about a status string: "materialized" over a refreshed unit and "materialized"
 * over a destroyed edit are the same string.
 */
public class HomeSyncProjectSeam {

    private static final String UNIT = "ps-unit";
    private static final String WORKTREE_EDIT = "THE TICKET'S WORK, WHICH EXISTS NOWHERE ELSE\n";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.project.seam")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "project-sync", "seam", "no-destruction")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.project.seam", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("project-seam");
            Path root = base.resolve("root");
            Path sources = base.resolve("sources");
            Path projectDir = base.resolve("project");
            Path project = projectDir.resolve(".skill-manager");
            Path worktreeDir = base.resolve("wt");
            Path worktree = worktreeDir.resolve(".skill-manager");
            Files.createDirectories(sources);
            Files.createDirectories(projectDir);

            HomeSyncSupport.mkSource(sources, UNIT, "seam unit v1");
            Files.writeString(projectDir.resolve("skill-project.toml"), """
                    [project]
                    name = "seam-project"

                    [skills.u]
                    source = "%s"
                    """.formatted(sources.resolve(UNIT)));

            // 1. The ordinary dependency resolve. The project home is a copy of
            //    the root store, and its record says so.
            ProcessRecord resolveFirst = HomeSyncSupport.sm(ctx, "seam-resolve-initial",
                    root.toString(), "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            boolean theProjectHomeStartsAsACopyOfTheRootStore = resolveFirst.exitCode() == 0
                    && Files.isRegularFile(HomeSyncSupport.unitDir(project, UNIT)
                            .resolve("SKILL.md"));

            // 2. The ticket worktree's home, cloned off the project home.
            ProcessRecord seedWorktree = HomeSyncSupport.sm(ctx, "seam-project-to-wt", ambient,
                    "home", "sync", "--from", project.toString(), "--to", worktree.toString(),
                    "--json");
            boolean theWorktreeHomeStartsFromTheProjectsBytes = seedWorktree.exitCode() == 0
                    && Files.isRegularFile(HomeSyncSupport.unitDir(worktree, UNIT)
                            .resolve("SKILL.md"));

            // 3. The agent does the ticket's work.
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, UNIT).resolve("SKILL.md"),
                    WORKTREE_EDIT);

            // 4. The return path the epic exists to make routine.
            ProcessRecord up = HomeSyncSupport.sm(ctx, "seam-wt-to-project", ambient,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(),
                    "--merge", "--json");
            boolean theTicketsWorkReachesTheProjectHome = up.exitCode() == 0
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(project, UNIT)
                            .resolve("SKILL.md")).equals(WORKTREE_EDIT);

            // 5. The gate says the worktree may go.
            ProcessRecord gate = HomeSyncSupport.sm(ctx, "seam-close-out", ambient,
                    "home", "close-out", "--home", worktree.toString(),
                    "--into", project.toString(), "--json");
            boolean theGateClearsTheTeardown = gate.exitCode() == 0
                    && HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "seam-close-out"), "safe");

            HomeSyncSupport.deleteTree(worktreeDir);
            boolean theWorktreeIsReallyGone = !Files.exists(worktree);

            // 6. The other writer runs. THIS is the seam.
            ProcessRecord resolveAgain = HomeSyncSupport.sm(ctx, "seam-resolve-after-merge",
                    root.toString(), "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String afterResolve = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"));
            boolean resolveAfterAMergeDoesNotDestroyTheTicketsWork =
                    afterResolve.equals(WORKTREE_EDIT);

            // A held-back unit that is not named is a silent skip wearing a
            // different hat, so the report has to name it.
            String resolveLog = HomeSyncSupport.log(ctx, "seam-resolve-after-merge");
            boolean theResolveNamesTheUnitItHeldBack = resolveLog.contains("skill:" + UNIT)
                    && resolveLog.contains("\"heldBack\":[{");

            // 7. And `project sync`, which is the same reconcile plus a trunk
            //    pull, must not destroy it either.
            ProcessRecord sync = HomeSyncSupport.sm(ctx, "seam-project-sync",
                    root.toString(), "project", "sync", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String afterSync = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"));
            boolean projectSyncDoesNotDestroyTheTicketsWorkEither =
                    afterSync.equals(WORKTREE_EDIT);

            // 8. A hold-back must not be a dead end. The report has to name
            //    something concrete the operator can run, because "held back"
            //    with no exit is how a correct refusal becomes the reason
            //    somebody deletes the directory by hand.
            //
            //    It names `unit publish` rather than another `home sync`, and
            //    that is the right answer here rather than a shortcoming: this
            //    project home holds a tree neither the root store nor any
            //    surviving home ever stood on, so no pair of homes has a shared
            //    baseline to reconcile through, and a file copy in either
            //    direction would have to guess. Pushing the work to the unit's
            //    own trunk is the one move that gives the two homes a common
            //    ancestor again.
            boolean theHoldBackNamesAWayForward = resolveLog.contains("unit publish " + UNIT);

            // Measured, not asserted: what the upward reconcile makes of it.
            // Pinning today's answer would make a later improvement fail this
            // node, and a graph should never be the reason a defect stays.
            ProcessRecord upToRoot = HomeSyncSupport.sm(ctx, "seam-project-to-root", ambient,
                    "home", "sync", "--from", project.toString(), "--to", root.toString(),
                    "--merge", "--json");
            String upToRootStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "seam-project-to-root"), "skill:" + UNIT);

            // Whatever it decided, it must not have destroyed anything: the
            // ticket's work is still in the project home afterwards, and the
            // root home was not silently rewritten with a tree it never held.
            boolean theWorkSurvivesTheUpwardPassToo = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"))
                    .equals(WORKTREE_EDIT);

            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty()
                    && HomeSyncSupport.stagingLeftovers(root).isEmpty();

            boolean pass = theProjectHomeStartsAsACopyOfTheRootStore
                    && theWorktreeHomeStartsFromTheProjectsBytes
                    && theTicketsWorkReachesTheProjectHome
                    && theGateClearsTheTeardown && theWorktreeIsReallyGone
                    && resolveAfterAMergeDoesNotDestroyTheTicketsWork
                    && theResolveNamesTheUnitItHeldBack
                    && projectSyncDoesNotDestroyTheTicketsWorkEither
                    && theHoldBackNamesAWayForward
                    && theWorkSurvivesTheUpwardPassToo
                    && noStagingLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.project.seam")
                    : NodeResult.fail("home.sync.project.seam",
                            "resolvedFirst=" + theProjectHomeStartsAsACopyOfTheRootStore
                                    + " seeded=" + theWorktreeHomeStartsFromTheProjectsBytes
                                    + " merged=" + theTicketsWorkReachesTheProjectHome
                                    + " gate=" + gate.exitCode()
                                    + " worktreeGone=" + theWorktreeIsReallyGone
                                    + " afterResolve=" + afterResolve.strip()
                                    + " resolveExit=" + resolveAgain.exitCode()
                                    + " namedHeldBack=" + theResolveNamesTheUnitItHeldBack
                                    + " afterSync=" + afterSync.strip()
                                    + " syncExit=" + sync.exitCode()
                                    + " namesWayForward=" + theHoldBackNamesAWayForward
                                    + " upToRoot=" + upToRootStatus
                                    + " survivesUpward=" + theWorkSurvivesTheUpwardPassToo
                                    + " staging=" + noStagingLeftovers))
                    .process(resolveFirst)
                    .process(seedWorktree)
                    .process(up)
                    .process(gate)
                    .process(resolveAgain)
                    .process(sync)
                    .process(upToRoot)
                    .assertion("project_resolve_seeds_the_project_home_from_the_root_store",
                            theProjectHomeStartsAsACopyOfTheRootStore)
                    .assertion("the_worktree_home_starts_from_the_projects_bytes",
                            theWorktreeHomeStartsFromTheProjectsBytes)
                    .assertion("home_sync_merge_carries_the_tickets_work_into_the_project_home",
                            theTicketsWorkReachesTheProjectHome)
                    .assertion("close_out_clears_the_worktree_teardown", theGateClearsTheTeardown)
                    .assertion("the_worktree_home_is_really_gone", theWorktreeIsReallyGone)
                    .assertion("project_resolve_after_a_merge_does_not_destroy_the_tickets_work",
                            resolveAfterAMergeDoesNotDestroyTheTicketsWork)
                    .assertion("project_resolve_names_the_unit_it_held_back",
                            theResolveNamesTheUnitItHeldBack)
                    .assertion("project_sync_after_a_merge_does_not_destroy_the_tickets_work",
                            projectSyncDoesNotDestroyTheTicketsWorkEither)
                    .assertion("the_hold_back_names_a_concrete_way_forward",
                            theHoldBackNamesAWayForward)
                    .assertion("the_upward_reconcile_destroys_nothing_either",
                            theWorkSurvivesTheUpwardPassToo)
                    .assertion("no_staging_leftovers_in_either_home", noStagingLeftovers)
                    .metric("resolveAfterMergeExitCode", resolveAgain.exitCode())
                    .metric("projectSyncExitCode", sync.exitCode())
                    .metric("upwardReconcileSettledWithoutAHuman",
                            "merged".equals(upToRootStatus) || "updated".equals(upToRootStatus)
                                    ? 1 : 0)
                    .log("upward reconcile status: " + upToRootStatus);
        });
    }
}
