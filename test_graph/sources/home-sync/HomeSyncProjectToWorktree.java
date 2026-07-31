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
 * <b>Direction D — project → worktree.</b> A ticket worktree picking up what
 * the project home learned after the worktree branched off it.
 *
 * <p>This is the direction that looks like the easy one and is not. The
 * worktree is where work in progress lives, so "bring me up to date" has to
 * mean "bring me the units I have not touched" and never "bring me up to date
 * with the project's idea of the unit I am halfway through rewriting". The
 * three outcomes are asserted in one pass on purpose: a unit the worktree never
 * touched fast-forwards, a unit it did touch is held back with its bytes
 * proven intact, and a unit the project created after the branch arrives whole.
 *
 * <p>Then the same unit is reconciled again with {@code --merge}, with the two
 * sides having moved on <em>different files inside it</em>. That is the case
 * three-way merging exists for and the one a whole-unit digest cannot express:
 * the unit differs on both sides, and the correct answer is neither "take
 * theirs" nor "keep mine" but both.
 */
public class HomeSyncProjectToWorktree {

    private static final String ALPHA = "hs-alpha";
    private static final String GAMMA = "hs-gamma";
    private static final String DELTA = "hs-delta";
    /** Created in the project home only after the worktree branched. */
    private static final String EPSILON = "hs-epsilon";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.project.to.worktree")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.worktree.to.project")
            .tags("home-sync", "project-to-worktree", "merge")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String projectRaw = ctx.get("home.sync.fixture.built", "projectHome").orElse(null);
            String worktreeRaw = ctx.get("home.sync.fixture.built", "worktreeHome").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (projectRaw == null || worktreeRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.project.to.worktree", "missing upstream context");
            }
            Path project = Path.of(projectRaw);
            Path worktree = Path.of(worktreeRaw);

            // --- the project moves on after the worktree branched ------------
            HomeSyncSupport.append(HomeSyncSupport.unitDir(project, GAMMA).resolve("SKILL.md"),
                    "gamma v2 — learned in the project home after the branch\n");
            HomeSyncSupport.mkUnit(project, EPSILON, "epsilon v1 — new in the project home");

            // --- and the agent is mid-edit on a different unit ---------------
            String agentPage = "a page the agent is still writing\n";
            HomeSyncSupport.append(
                    HomeSyncSupport.unitDir(worktree, DELTA).resolve("references/new-page.md"),
                    agentPage);

            LinkedHashMap<String, String> deltaBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, DELTA));
            LinkedHashMap<String, String> projectBefore = HomeSyncSupport.entryDigests(project);

            ProcessRecord sync = HomeSyncSupport.sm(ctx, "sync-project-to-worktree", ambient,
                    "home", "sync", "--from", projectRaw, "--to", worktreeRaw, "--json");
            Map<String, Object> report = HomeSyncSupport.json(ctx, "sync-project-to-worktree");

            List<String> gammaDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, GAMMA)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, GAMMA)));
            List<String> epsilonDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, EPSILON)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, EPSILON)));
            List<String> deltaMoved = HomeSyncSupport.difference(deltaBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, DELTA)));

            boolean syncOk = sync.exitCode() == 0;
            boolean untouchedUnitFastForwards =
                    HomeSyncSupport.status(report, "skill:" + GAMMA).equals("updated")
                            && gammaDrift.isEmpty();
            boolean newProjectUnitArrives =
                    HomeSyncSupport.status(report, "skill:" + EPSILON).equals("new")
                            && epsilonDrift.isEmpty();
            boolean inFlightUnitHeldBack =
                    HomeSyncSupport.status(report, "skill:" + DELTA).equals("held-back");
            boolean inFlightWorkIntact = deltaMoved.isEmpty();
            boolean settledUnitUnchanged =
                    HomeSyncSupport.status(report, "skill:" + ALPHA).equals("unchanged");
            boolean projectOnlyRead = HomeSyncSupport
                    .difference(projectBefore, HomeSyncSupport.entryDigests(project)).isEmpty();

            // --- both sides move inside one unit, on different files ---------
            // On GAMMA, whose baseline the fast-forward above just refreshed:
            // the two homes agree on it and both records say so, which is the
            // state a three-way merge is defined against. (DELTA is left in its
            // held-back state deliberately — the same pass has to settle one
            // unit and leave the other alone.)
            String projectSkill = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, GAMMA).resolve("SKILL.md"))
                    + "gamma — the project home rewrote the body again\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(project, GAMMA).resolve("SKILL.md"),
                    projectSkill);
            HomeSyncSupport.write(HomeSyncSupport.unitDir(project, GAMMA)
                    .resolve("references/project-page.md"), "a page only the project wrote\n");
            String agentText = "a page only the agent wrote\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, GAMMA)
                    .resolve("references/agent-page.md"), agentText);

            LinkedHashMap<String, String> deltaBeforeMerge =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, DELTA));

            ProcessRecord mergeRun = HomeSyncSupport.sm(ctx, "merge-project-to-worktree", ambient,
                    "home", "sync", "--from", projectRaw, "--to", worktreeRaw, "--merge", "--json");
            Map<String, Object> mergeReport = HomeSyncSupport.json(ctx, "merge-project-to-worktree");

            boolean mergedDisjointEdits =
                    HomeSyncSupport.status(mergeReport, "skill:" + GAMMA).equals("merged");
            boolean tookTheProjectSideOfTheMerge = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(worktree, GAMMA).resolve("SKILL.md"))
                    .equals(projectSkill);
            boolean keptTheAgentSideOfTheMerge = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(worktree, GAMMA)
                            .resolve("references/agent-page.md"))
                    .equals(agentText);
            boolean projectOnlyFileArrived = Files.isRegularFile(
                    HomeSyncSupport.unitDir(worktree, GAMMA).resolve("references/project-page.md"));
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(worktree).isEmpty();

            // A unit the same pass could not settle must come out of it exactly
            // as it went in. One unit merging and another being left alone is
            // the case where a partial write would be least visible.
            String deltaAfter = HomeSyncSupport.status(mergeReport, "skill:" + DELTA);
            boolean deltaUnsettled = deltaAfter.equals("conflicted") || deltaAfter.equals("held-back");
            boolean unsettledUnitLeftExactlyAsItWas = !deltaUnsettled
                    || HomeSyncSupport.difference(deltaBeforeMerge,
                            HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, DELTA)))
                            .isEmpty();

            boolean pass = syncOk && untouchedUnitFastForwards && newProjectUnitArrives
                    && inFlightUnitHeldBack && inFlightWorkIntact && settledUnitUnchanged
                    && projectOnlyRead && mergedDisjointEdits && tookTheProjectSideOfTheMerge
                    && keptTheAgentSideOfTheMerge && projectOnlyFileArrived && noStagingLeftovers
                    && unsettledUnitLeftExactlyAsItWas;
            return (pass
                    ? NodeResult.pass("home.sync.project.to.worktree")
                    : NodeResult.fail("home.sync.project.to.worktree",
                            "syncExit=" + sync.exitCode()
                                    + " gamma=" + HomeSyncSupport.status(report, "skill:" + GAMMA)
                                    + " epsilon=" + HomeSyncSupport.status(report, "skill:" + EPSILON)
                                    + " delta=" + HomeSyncSupport.status(report, "skill:" + DELTA)
                                    + " deltaMoved=" + deltaMoved
                                    + " mergeExit=" + mergeRun.exitCode()
                                    + " mergedGamma="
                                    + HomeSyncSupport.status(mergeReport, "skill:" + GAMMA)
                                    + " deltaAfterMerge=" + deltaAfter
                                    + " unsettledLeftAlone=" + unsettledUnitLeftExactlyAsItWas
                                    + " tookProject=" + tookTheProjectSideOfTheMerge
                                    + " keptAgent=" + keptTheAgentSideOfTheMerge))
                    .process(sync).process(mergeRun)
                    .assertion("a_unit_the_worktree_never_touched_fast_forwards",
                            untouchedUnitFastForwards)
                    .assertion("a_unit_created_after_the_branch_arrives_whole", newProjectUnitArrives)
                    .assertion("a_unit_the_agent_is_mid_edit_on_is_held_back", inFlightUnitHeldBack)
                    .assertion("the_agents_in_flight_work_keeps_every_byte", inFlightWorkIntact)
                    .assertion("a_unit_neither_side_moved_stays_unchanged", settledUnitUnchanged)
                    .assertion("the_project_home_is_only_read_by_a_downward_sync", projectOnlyRead)
                    .assertion("merge_folds_edits_both_sides_made_inside_one_unit",
                            mergedDisjointEdits)
                    .assertion("the_merge_took_the_file_only_the_project_changed",
                            tookTheProjectSideOfTheMerge)
                    .assertion("the_merge_kept_the_file_only_the_agent_changed",
                            keptTheAgentSideOfTheMerge)
                    .assertion("a_file_only_the_project_added_arrives_in_the_merge",
                            projectOnlyFileArrived)
                    .assertion("the_worktree_home_carries_no_staging_leftovers", noStagingLeftovers)
                    .assertion("a_unit_the_same_pass_could_not_settle_is_left_exactly_as_it_was",
                            unsettledUnitLeftExactlyAsItWas)
                    .metric("mergeExitCode", mergeRun.exitCode())
                    .metric("worktreeUnits",
                            HomeSyncSupport.names(HomeSyncSupport.skills(worktree)).size());
        });
    }
}
