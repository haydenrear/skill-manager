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
 * The clone-of-an-edited-home case: a worktree branched from a project home
 * that had already improved the unit itself.
 *
 * <h2>Why this is its own node</h2>
 *
 * <p>A project home's materialization record says what the home was
 * <em>handed</em>. Editing a unit in place does not update it, and should not —
 * the record is provenance, not a snapshot. But {@code home clone} copied those
 * records verbatim and only wrote a baseline for units that had none, so a
 * worktree cloned from a project home whose unit was edited inherited a
 * baseline describing content <em>neither home had any more</em>, and the merge
 * base for the return path was older than the two homes' real common ancestor —
 * which is the clone itself.
 *
 * <p>An older base never loses work; it produces conflicts. That is the right
 * direction to be wrong in, which is why this was a finding rather than a
 * defect — but the cost was paid by the exact flow this epic exists to make
 * routine, the ticket agent's close-out, so it was fixed with CHM-9 and CHM-10
 * as the third face of one baseline rule:
 * {@code ChildHomeMaterializer.recordCloneBaselines} restates the baseline of a
 * COPY unit whose inherited record describes content the copy does not hold.
 *
 * <h2>What is asserted, and what is only measured</h2>
 *
 * <p>Asserted: the invariants that must hold under <em>any</em> behaviour —
 * the worktree is only read, the project home ends up either exactly the
 * worktree's tree (reconciled) or exactly its own previous tree (reported and
 * untouched) and never a half-written third thing, the gate never says "safe"
 * while the worktree holds content the project does not, and no staging residue
 * is left.
 *
 * <p>Measured, not asserted: whether the reconcile settled it or reported a
 * conflict. Pinning today's answer as the expected one would make a fix to the
 * stale baseline fail this node, which is the wrong way round — a graph should
 * not be the reason a defect stays.
 */
public class HomeSyncStaleBaseline {

    private static final String UNIT = "sb-unit";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.stale.baseline")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "baseline", "close-out")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            if (workspaceRaw == null) {
                return NodeResult.fail("home.sync.stale.baseline", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("stale-baseline");
            Path root = base.resolve("root");
            Path project = base.resolve("project/.skill-manager");
            Path worktree = base.resolve("worktree/.skill-manager");
            Files.createDirectories(base);

            HomeSyncSupport.mkUnit(root, UNIT, "unit v1");
            ProcessRecord seed = HomeSyncSupport.sm(ctx, "stale-seed", root.toString(),
                    "home", "sync", "--from", root.toString(), "--to", project.toString(), "--json");

            // The project home improves the unit itself. Its record still
            // describes v1, correctly: the record is what the home was handed.
            String projectText = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"))
                    + "the project home improved this first\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"),
                    projectText);

            ProcessRecord clone = HomeSyncSupport.sm(ctx, "stale-clone", project.toString(),
                    "home", "clone", "--from", project.toString(), "--to", worktree.toString(),
                    "--json");
            boolean cloneOk = clone.exitCode() == 0;
            boolean cloneStartedFromTheProjectsBytes = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(worktree, UNIT).resolve("SKILL.md"))
                    .equals(projectText);

            // The ticket agent builds on exactly what it was given.
            String worktreeText = projectText + "and the ticket agent improved it further\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktree, UNIT).resolve("SKILL.md"),
                    worktreeText);
            boolean worktreeStrictlyContainsTheProject = worktreeText.startsWith(projectText);

            LinkedHashMap<String, String> projectBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, UNIT));
            LinkedHashMap<String, String> worktreeBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, UNIT));

            ProcessRecord gate = HomeSyncSupport.sm(ctx, "stale-close-out", worktree.toString(),
                    "home", "close-out", "--home", worktree.toString(), "--into",
                    project.toString(), "--json");
            Map<String, Object> verdict = HomeSyncSupport.json(ctx, "stale-close-out");
            boolean gateNeverSaysSafeWhileWorkIsOutstanding =
                    !HomeSyncSupport.flag(verdict, "safe") && gate.exitCode() != 0;

            ProcessRecord merge = HomeSyncSupport.sm(ctx, "stale-merge", worktree.toString(),
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(),
                    "--merge", "--json");
            Map<String, Object> report = HomeSyncSupport.json(ctx, "stale-merge");
            String outcome = HomeSyncSupport.status(report, "skill:" + UNIT);

            LinkedHashMap<String, String> projectAfter =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(project, UNIT));
            List<String> projectMoved = HomeSyncSupport.difference(projectBefore, projectAfter);
            List<String> worktreeMoved = HomeSyncSupport.difference(worktreeBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, UNIT)));
            List<String> projectVersusWorktree = HomeSyncSupport.difference(projectAfter,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(worktree, UNIT)));

            boolean worktreeOnlyRead = worktreeMoved.isEmpty();
            // Either it reconciled (project == worktree) or it reported and wrote
            // nothing (project == its own previous tree). Never a third state.
            boolean noHalfWrittenOutcome = projectVersusWorktree.isEmpty()
                    || projectMoved.isEmpty();
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty();
            boolean reconciled = projectVersusWorktree.isEmpty();

            boolean pass = seed.exitCode() == 0 && cloneOk && cloneStartedFromTheProjectsBytes
                    && worktreeStrictlyContainsTheProject && gateNeverSaysSafeWhileWorkIsOutstanding
                    && worktreeOnlyRead && noHalfWrittenOutcome && noStagingLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.stale.baseline")
                    : NodeResult.fail("home.sync.stale.baseline",
                            "cloneOk=" + cloneOk
                                    + " cloneStartedFromTheProjectsBytes="
                                    + cloneStartedFromTheProjectsBytes
                                    + " gateBlocked=" + gateNeverSaysSafeWhileWorkIsOutstanding
                                    + " worktreeMoved=" + worktreeMoved
                                    + " outcome=" + outcome
                                    + " projectMoved=" + projectMoved
                                    + " projectVersusWorktree=" + projectVersusWorktree))
                    .process(seed).process(clone).process(gate).process(merge)
                    .log("clone-of-an-edited-home reconcile outcome: " + outcome
                            + " (reconciled=" + reconciled + "). The worktree's content strictly "
                            + "contains the project's, so a merge base equal to the clone would "
                            + "settle this without a human.")
                    .assertion("the_clone_starts_from_the_project_homes_current_bytes",
                            cloneStartedFromTheProjectsBytes)
                    .assertion("the_worktree_content_strictly_contains_the_projects",
                            worktreeStrictlyContainsTheProject)
                    .assertion("close_out_never_reports_safe_while_the_worktree_holds_more",
                            gateNeverSaysSafeWhileWorkIsOutstanding)
                    .assertion("the_worktree_home_is_only_read", worktreeOnlyRead)
                    .assertion("the_project_home_is_either_reconciled_or_untouched_never_partial",
                            noHalfWrittenOutcome)
                    .assertion("no_staging_residue_survives_the_attempt", noStagingLeftovers)
                    .metric("reconciledWithoutAHuman", reconciled ? 1 : 0)
                    .metric("spuriousConflict", "conflicted".equals(outcome) ? 1 : 0);
        });
    }
}
