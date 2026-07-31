///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Direction C — worktree → project.</b> The one the whole mechanism exists
 * for: a ticket agent improves a skill inside its worktree home, the worktree
 * is removed, and until this existed the improvement went with it — silently,
 * because a teardown that deletes a directory succeeds exactly as loudly
 * whether the directory held work or not.
 *
 * <h2>Three things have to be true, and only the third is about bytes</h2>
 *
 * <ol>
 *   <li>The gate <b>refuses</b> while the work exists only in the worktree.
 *       Not warns — refuses, with a non-zero exit a teardown script can be
 *       gated on, and with the unit named.</li>
 *   <li>The remedy it prints <b>works</b>. This node does not paraphrase the
 *       remedy or run the command it assumes was meant: it takes the string out
 *       of {@code blockers[].remedy} and executes it. A gate that refuses and
 *       then names a command that does not fix it is worse than no gate,
 *       because the operator's next move is to delete the worktree anyway.</li>
 *   <li>Afterwards the project home holds the agent's bytes <b>exactly</b> —
 *       compared file by file against the worktree, not against an expected
 *       string, so a truncation or a lost nested file is a failure rather than
 *       a passing substring match.</li>
 * </ol>
 *
 * <p>And close-out must have written nothing itself: it is a question, and a
 * question that quietly performs the answer is a gate somebody eventually runs
 * to make the error go away.
 */
public class HomeSyncWorktreeToProject {

    private static final String ALPHA = "hs-alpha";
    private static final String DELTA = "hs-delta";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.worktree.to.project")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.project.to.root")
            .tags("home-sync", "worktree-to-project", "close-out", "no-destruction")
            .timeout("300s")
            .output("worktreeAlphaContent", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String projectRaw = ctx.get("home.sync.fixture.built", "projectHome").orElse(null);
            String worktreeRaw = ctx.get("home.sync.fixture.built", "worktreeHome").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (projectRaw == null || worktreeRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.worktree.to.project", "missing upstream context");
            }
            Path project = Path.of(projectRaw);
            Path worktree = Path.of(worktreeRaw);

            // --- the ticket worktree gets its own home ----------------------
            ProcessRecord clone = HomeSyncSupport.sm(ctx, "clone-project-to-worktree", ambient,
                    "home", "clone", "--from", projectRaw, "--to", worktreeRaw, "--json");
            boolean cloneOk = clone.exitCode() == 0
                    && HomeSyncSupport.flag(
                            HomeSyncSupport.json(ctx, "clone-project-to-worktree"), "clean");

            // --- the agent works ---------------------------------------------
            String alphaEdit = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(worktree, ALPHA).resolve("SKILL.md"))
                    + "AGENT IMPROVED ALPHA WHILE WORKING THE TICKET\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, ALPHA).resolve("SKILL.md"),
                    alphaEdit);
            HomeSyncSupport.append(
                    HomeSyncSupport.unitDir(worktree, ALPHA).resolve("references/deep/note.md"),
                    "nested reference edited by the agent\n");
            HomeSyncSupport.write(
                    HomeSyncSupport.unitDir(worktree, DELTA).resolve("references/new-page.md"),
                    "a page the agent wrote that exists nowhere else\n");

            LinkedHashMap<String, String> projectBeforeGate = HomeSyncSupport.entryDigests(project);

            // --- the gate ----------------------------------------------------
            ProcessRecord blocked = HomeSyncSupport.sm(ctx, "close-out-blocked", ambient,
                    "home", "close-out", "--home", worktreeRaw, "--into", projectRaw, "--json");
            Map<String, Object> verdict = HomeSyncSupport.json(ctx, "close-out-blocked");
            Map<String, Object> alphaBlocker =
                    HomeSyncSupport.blocker(verdict, "skill:" + ALPHA);
            Map<String, Object> deltaBlocker =
                    HomeSyncSupport.blocker(verdict, "skill:" + DELTA);

            boolean gateRefuses = blocked.exitCode() == 1
                    && !HomeSyncSupport.flag(verdict, "safe");
            boolean gateNamesEveryUnitAtRisk = !alphaBlocker.isEmpty() && !deltaBlocker.isEmpty()
                    && HomeSyncSupport.blockerCount(verdict) == 2;
            boolean gateWroteNothing = HomeSyncSupport
                    .difference(projectBeforeGate, HomeSyncSupport.entryDigests(project)).isEmpty();
            // Repeatable: a gate that answers differently the second time cannot
            // be used by a teardown that checks and then acts.
            ProcessRecord again = HomeSyncSupport.sm(ctx, "close-out-repeat", ambient,
                    "home", "close-out", "--home", worktreeRaw, "--into", projectRaw, "--json");
            boolean gateIsRepeatable = again.exitCode() == 1
                    && !HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "close-out-repeat"), "safe");

            // --- run the remedy the gate printed, verbatim -------------------
            String remedy = String.valueOf(alphaBlocker.get("remedy"));
            List<String> remedyArgs = remedyArgs(remedy);
            boolean remedyIsRunnable = !remedyArgs.isEmpty();
            ProcessRecord remedyRun = remedyIsRunnable
                    ? HomeSyncSupport.sm(ctx, "run-the-remedy", ambient,
                            remedyArgs.toArray(new String[0]))
                    : null;
            boolean remedyWorked = remedyRun != null && remedyRun.exitCode() == 0;

            // --- and now the gate must let the worktree go -------------------
            ProcessRecord cleared = HomeSyncSupport.sm(ctx, "close-out-cleared", ambient,
                    "home", "close-out", "--home", worktreeRaw, "--into", projectRaw, "--json");
            Map<String, Object> clearedVerdict = HomeSyncSupport.json(ctx, "close-out-cleared");
            boolean gateClears = cleared.exitCode() == 0
                    && HomeSyncSupport.flag(clearedVerdict, "safe")
                    && HomeSyncSupport.blockerCount(clearedVerdict) == 0;

            // --- the bytes ---------------------------------------------------
            List<String> alphaDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, ALPHA)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, ALPHA)));
            List<String> deltaDrift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, DELTA)),
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, DELTA)));
            boolean projectHoldsTheWork = alphaDrift.isEmpty() && deltaDrift.isEmpty();
            boolean nestedFileArrived = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, DELTA).resolve("references/new-page.md"))
                    .equals("a page the agent wrote that exists nowhere else\n");
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty();

            boolean pass = cloneOk && gateRefuses && gateNamesEveryUnitAtRisk && gateWroteNothing
                    && gateIsRepeatable && remedyIsRunnable && remedyWorked && gateClears
                    && projectHoldsTheWork && nestedFileArrived && noStagingLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.worktree.to.project")
                    : NodeResult.fail("home.sync.worktree.to.project",
                            "cloneOk=" + cloneOk + " gateRefuses=" + gateRefuses
                                    + " blockers=" + HomeSyncSupport.blockerCount(verdict)
                                    + " gateWroteNothing=" + gateWroteNothing
                                    + " remedy=" + remedy + " remedyWorked=" + remedyWorked
                                    + " gateClears=" + gateClears
                                    + " alphaDrift=" + alphaDrift + " deltaDrift=" + deltaDrift))
                    .process(clone).process(blocked).process(again)
                    .process(remedyRun == null ? blocked : remedyRun)
                    .process(cleared)
                    .assertion("a_ticket_worktree_home_clones_cleanly_from_the_project", cloneOk)
                    .assertion("close_out_refuses_while_the_worktree_still_holds_work", gateRefuses)
                    .assertion("close_out_names_every_unit_that_would_be_lost",
                            gateNamesEveryUnitAtRisk)
                    .assertion("close_out_writes_nothing_at_all", gateWroteNothing)
                    .assertion("close_out_gives_the_same_verdict_twice", gateIsRepeatable)
                    .assertion("the_remedy_the_gate_printed_is_a_runnable_command",
                            remedyIsRunnable)
                    .assertion("running_the_printed_remedy_clears_the_blocker", remedyWorked)
                    .assertion("close_out_passes_once_the_work_has_been_synced", gateClears)
                    .assertion("the_project_home_holds_the_agents_bytes_exactly",
                            projectHoldsTheWork)
                    .assertion("a_nested_file_the_agent_added_reaches_the_project_home",
                            nestedFileArrived)
                    .assertion("the_return_path_leaves_no_staging_leftovers", noStagingLeftovers)
                    .metric("blockersRaised", HomeSyncSupport.blockerCount(verdict))
                    .publish("worktreeAlphaContent", alphaEdit);
        });
    }

    /**
     * The remedy string as an argument list for the CLI.
     *
     * <p>Everything after a two-space run is prose the remedy adds for a human
     * ("then resolve: both.md"); everything before it is the command. The
     * leading {@code skill-manager} token is dropped because the graph invokes
     * the checkout's own CLI rather than whatever is on PATH — that is the only
     * substitution made, and it is the difference between running the remedy
     * and running something like it.
     */
    private static List<String> remedyArgs(String remedy) {
        if (remedy == null || remedy.isBlank() || "null".equals(remedy)) return List.of();
        String command = remedy.split(" {2}")[0].trim();
        List<String> tokens = new ArrayList<>(List.of(command.split("\\s+")));
        if (tokens.isEmpty()) return List.of();
        if (tokens.get(0).endsWith("skill-manager")) tokens.remove(0);
        tokens.add("--json");
        return tokens;
    }
}
