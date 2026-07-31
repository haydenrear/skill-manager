///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two silent-data-loss defects of the baseline rule, end to end: whose
 * bytes the destination is holding (CHM-9) and which state the two homes
 * actually share (CHM-10).
 *
 * <h2>Why a node and not only a unit test</h2>
 *
 * <p>Both are already pinned in {@code HomeSyncMergeTest}, in process, against
 * the classes. Neither is reproducible there in the shape it occurs in: CHM-9
 * is a <em>three-command sequence across three homes with a teardown in the
 * middle</em> — sync up, close out, remove the worktree, sync down — and the
 * thing that makes it dangerous is that every individual command reports
 * success. Only a node that runs the real CLI, removes a real directory, and
 * then reads the surviving bytes off disk can assert the property the sequence
 * is about, which is that the ticket's work is still there after the tool has
 * said "safe" and the operator has acted on it.
 *
 * <h2>Two scenarios, one rule</h2>
 *
 * <ul>
 *   <li><b>CHM-9.</b> The project home's copy of a unit came from a worktree
 *       that has since been torn down. The root home then ships its own new
 *       version. The root never held the agent's bytes, so it may not replace
 *       them: held back and reported, and with {@code --merge} a conflict, not
 *       a guess. Before the fix this was {@code updated} and the work was
 *       gone.</li>
 *   <li><b>CHM-10.</b> A second {@code --merge} from the same source, after a
 *       first one folded local work in. The source moved a file it had already
 *       moved once and never touched the agent's. Before the fix the second
 *       merge reported success and reverted the agent's file to the source's
 *       version, with no conflict and nothing in the report.</li>
 * </ul>
 *
 * <p>Every claim is measured on bytes read back from the homes, never on a
 * status string: a pass that reports {@code held-back} and overwrites anyway
 * and one that reports {@code updated} and keeps the bytes are the same string
 * to a status check, and only one of them has lost the ticket's work.
 */
public class HomeSyncProvenance {

    /** CHM-9: reaches the project home from the worktree, then is pushed at by the root. */
    private static final String FF = "pv-fastforward";
    /** CHM-10: merged once, then merged again from the same source. */
    private static final String MERGED = "pv-merged";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.provenance")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "provenance", "merge-base", "no-destruction")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.provenance", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("provenance");
            Path root = base.resolve("root");
            Path project = base.resolve("project/.skill-manager");
            Path worktree = base.resolve("worktree/.skill-manager");
            Files.createDirectories(base);

            // ---------------- CHM-9: sync up, close out, tear down, sync down
            HomeSyncSupport.mkUnit(root, FF, "root v1");
            ProcessRecord seedProject = HomeSyncSupport.sm(ctx, "pv-seed-project", ambient,
                    "home", "sync", "--from", root.toString(), "--to", project.toString(), "--json");
            ProcessRecord cloneWorktree = HomeSyncSupport.sm(ctx, "pv-clone-worktree", ambient,
                    "home", "clone", "--from", project.toString(), "--to", worktree.toString(),
                    "--json");

            // The ticket agent improves the unit inside its worktree home.
            String agentText = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(worktree, FF).resolve("SKILL.md"))
                    + "AGENT IMPROVED THIS WHILE WORKING THE TICKET\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, FF).resolve("SKILL.md"),
                    agentText);
            String agentNote = "a note that exists only in the worktree\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, FF).resolve("agent-note.md"),
                    agentNote);

            // The documented happy path: a plain sync up, which fast-forwards.
            ProcessRecord up = HomeSyncSupport.sm(ctx, "pv-sync-up", ambient,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(),
                    "--json");
            String upStatus = HomeSyncSupport.status(HomeSyncSupport.json(ctx, "pv-sync-up"),
                    "skill:" + FF);
            boolean theWorkReachedTheProject = up.exitCode() == 0 && upStatus.equals("updated")
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(project, FF)
                            .resolve("SKILL.md")).equals(agentText);

            // The gate clears, so the operator removes the worktree — which is
            // the point of no return: from here the project home is the only
            // place the agent's bytes exist.
            ProcessRecord gate = HomeSyncSupport.sm(ctx, "pv-close-out", ambient,
                    "home", "close-out", "--home", worktree.toString(), "--into",
                    project.toString(), "--json");
            boolean gateClears = gate.exitCode() == 0
                    && HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "pv-close-out"), "safe");
            HomeSyncSupport.deleteTree(worktree);
            boolean worktreeIsGone = !Files.exists(worktree);

            // And now the root home ships its own new version of the same unit.
            HomeSyncSupport.write(HomeSyncSupport.unitDir(root, FF).resolve("SKILL.md"),
                    "root v2 — improved upstream, knowing nothing of the ticket\n");
            LinkedHashMap<String, String> projectBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, FF));

            ProcessRecord down = HomeSyncSupport.sm(ctx, "pv-sync-down", ambient,
                    "home", "sync", "--from", root.toString(), "--to", project.toString(), "--json");
            String downStatus = HomeSyncSupport.status(HomeSyncSupport.json(ctx, "pv-sync-down"),
                    "skill:" + FF);
            List<String> projectMoved = HomeSyncSupport.difference(projectBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, FF)));

            boolean agentBytesSurviveThePushFromRoot = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, FF).resolve("SKILL.md"))
                    .equals(agentText)
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(project, FF)
                            .resolve("agent-note.md")).equals(agentNote)
                    && projectMoved.isEmpty();
            boolean thePushFromRootIsReported = downStatus.equals("held-back");

            // --merge cannot settle it either: there is no state the root home
            // and the project home can be shown to share.
            ProcessRecord downMerge = HomeSyncSupport.sm(ctx, "pv-sync-down-merge", ambient,
                    "home", "sync", "--from", root.toString(), "--to", project.toString(),
                    "--merge", "--json");
            String downMergeStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "pv-sync-down-merge"), "skill:" + FF);
            List<String> projectMovedByMerge = HomeSyncSupport.difference(projectBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, FF)));
            boolean aMergeWithNoSharedBaseConflicts = downMergeStatus.equals("conflicted")
                    && projectMovedByMerge.isEmpty();

            // ---------------- CHM-10: the second merge from the same source ---
            Path source = base.resolve("merge-source");
            Path dest = base.resolve("merge-dest");
            HomeSyncSupport.mkUnit(source, MERGED, "shared v1");
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, MERGED).resolve("upstream.md"),
                    "v1\n");
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, MERGED).resolve("local.md"),
                    "v1\n");
            ProcessRecord seedMerge = HomeSyncSupport.sm(ctx, "pv-seed-merge", ambient,
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(), "--json");

            String localWork = "THE AGENT'S WORK, ON A FILE THE SOURCE NEVER TOUCHES AGAIN\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(dest, MERGED).resolve("local.md"),
                    localWork);
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, MERGED).resolve("upstream.md"),
                    "v2\n");
            ProcessRecord firstMerge = HomeSyncSupport.sm(ctx, "pv-merge-1", ambient,
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(),
                    "--merge", "--json");
            String firstStatus = HomeSyncSupport.status(HomeSyncSupport.json(ctx, "pv-merge-1"),
                    "skill:" + MERGED);
            boolean theFirstMergeKeptTheWork = firstStatus.equals("merged")
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(dest, MERGED)
                            .resolve("local.md")).equals(localWork);

            // The source moves the SAME file again and nothing touches the
            // agent's. Under a merge base taken from the merged result, the
            // agent's file is measured against its own bytes, reads as "only
            // the source moved", and is reverted.
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, MERGED).resolve("upstream.md"),
                    "v3\n");
            ProcessRecord secondMerge = HomeSyncSupport.sm(ctx, "pv-merge-2", ambient,
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(),
                    "--merge", "--json");
            Map<String, Object> secondReport = HomeSyncSupport.json(ctx, "pv-merge-2");
            String secondStatus = HomeSyncSupport.status(secondReport, "skill:" + MERGED);

            boolean theSecondMergeTookTheSourceChange = secondStatus.equals("merged")
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(dest, MERGED)
                            .resolve("upstream.md")).equals("v3\n");
            boolean theSecondMergeKeptTheWork = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(dest, MERGED).resolve("local.md"))
                    .equals(localWork);
            boolean andNeededNoConflictToDoIt =
                    HomeSyncSupport.conflicts(secondReport, "skill:" + MERGED).isEmpty();

            // A third round, because the defect was in what a merge WRITES
            // DOWN: a baseline that is still wrong reverts the work one round
            // later rather than never.
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, MERGED).resolve("upstream.md"),
                    "v4\n");
            ProcessRecord thirdMerge = HomeSyncSupport.sm(ctx, "pv-merge-3", ambient,
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(),
                    "--merge", "--json");
            boolean theWorkSurvivesRepeatedMerges = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(dest, MERGED).resolve("local.md"))
                    .equals(localWork)
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(dest, MERGED)
                            .resolve("upstream.md")).equals("v4\n");

            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty()
                    && HomeSyncSupport.stagingLeftovers(dest).isEmpty();

            boolean pass = seedProject.exitCode() == 0 && cloneWorktree.exitCode() == 0
                    && seedMerge.exitCode() == 0 && theWorkReachedTheProject && gateClears
                    && worktreeIsGone && agentBytesSurviveThePushFromRoot
                    && thePushFromRootIsReported && aMergeWithNoSharedBaseConflicts
                    && theFirstMergeKeptTheWork && theSecondMergeTookTheSourceChange
                    && theSecondMergeKeptTheWork && andNeededNoConflictToDoIt
                    && theWorkSurvivesRepeatedMerges && noStagingLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.provenance")
                    : NodeResult.fail("home.sync.provenance",
                            "up=" + upStatus + " gateClears=" + gateClears
                                    + " down=" + downStatus + " projectMoved=" + projectMoved
                                    + " downMerge=" + downMergeStatus
                                    + " merge1=" + firstStatus + " merge2=" + secondStatus
                                    + " localAfter2=" + HomeSyncSupport.read(
                                            HomeSyncSupport.unitDir(dest, MERGED)
                                                    .resolve("local.md"))
                                    + " upstreamAfter3=" + HomeSyncSupport.read(
                                            HomeSyncSupport.unitDir(dest, MERGED)
                                                    .resolve("upstream.md"))))
                    .process(seedProject).process(cloneWorktree).process(up).process(gate)
                    .process(down).process(downMerge).process(seedMerge).process(firstMerge)
                    .process(secondMerge).process(thirdMerge)
                    .assertion("a_plain_sync_up_carries_the_agents_work_into_the_project",
                            theWorkReachedTheProject)
                    .assertion("close_out_then_clears_the_worktree_for_teardown", gateClears)
                    .assertion("the_worktree_home_really_is_removed", worktreeIsGone)
                    .assertion("a_push_from_a_home_that_never_held_the_work_destroys_nothing",
                            agentBytesSurviveThePushFromRoot)
                    .assertion("and_it_is_reported_rather_than_silently_skipped",
                            thePushFromRootIsReported)
                    .assertion("a_merge_with_no_shared_baseline_conflicts_and_writes_nothing",
                            aMergeWithNoSharedBaseConflicts)
                    .assertion("a_first_merge_folds_the_source_change_in_and_keeps_local_work",
                            theFirstMergeKeptTheWork)
                    .assertion("a_second_merge_still_takes_the_file_the_source_moved",
                            theSecondMergeTookTheSourceChange)
                    .assertion("a_second_merge_does_not_revert_the_work_the_first_one_kept",
                            theSecondMergeKeptTheWork)
                    .assertion("and_it_needed_no_conflict_to_keep_it", andNeededNoConflictToDoIt)
                    .assertion("the_local_work_survives_repeated_merges_from_the_same_source",
                            theWorkSurvivesRepeatedMerges)
                    .assertion("neither_home_carries_staging_leftovers", noStagingLeftovers)
                    .metric("syncDownExitCode", down.exitCode())
                    .metric("mergeConflictsReported",
                            HomeSyncSupport.conflicts(secondReport, "skill:" + MERGED).size());
        });
    }
}
