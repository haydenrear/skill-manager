///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Step 8 — the epic agent publishes.</b> Reconciling into the project home
 * is not the same as pushing a skill edit back to that skill's own repository,
 * and nothing in the workflow prompts for it (git-integration-skill#8). This is
 * the step that closes the loop.
 *
 * <h2>A real remote, and no `gh` auth</h2>
 *
 * <p>The unit's {@code origin} is a bare repository in the run's temp
 * directory. Nothing about {@code git push} is different for a filesystem
 * remote, so the branch that lands is a real branch with real bytes on it —
 * where a graph that skipped the push would be asserting that a function was
 * called. Pull-request creation is covered from the two sides that do not need
 * an authenticated machine: {@code --dry-run} must REPORT the pull request it
 * would open, and {@code --no-pr} must land the branch. A graph that only
 * passed on an authenticated machine is a graph that gets disabled.
 *
 * <h2>What a publish must never do</h2>
 *
 * <p>Move the trunk. It is captured before and compared after, and
 * {@code --direct} — the only flag that writes to the trunk — is deliberately
 * not used. The dry run's "changed nothing" is asserted the same way: the
 * branch must not exist in the remote afterwards, because a dry run that
 * reported an intent and then performed it would satisfy every assertion made
 * about its output.
 *
 * <h2>Only the intended files moved</h2>
 *
 * <p>The published tree is compared against the trunk's, and the difference has
 * to be exactly the file the agent edited. A publish that swept the home's
 * bookkeeping — a materialization record, a lock file, an {@code installed/}
 * entry — into the skill's own repository would pass a branch-name assertion
 * and be a mess in someone's pull request.
 */
public class TicketLifecyclePublish {

    private static final String UNIT = "tl-pub";
    private static final String BRANCH = "skill/ticket-c-tl-pub";
    private static final String TRUNK = "main";

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.publish")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.next.agent")
            .tags("ticket-lifecycle", "publish")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("ticket.lifecycle.fixture.built", "workspace")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            String homeCRaw = ctx.get("ticket.lifecycle.next.agent", "homeC").orElse(null);
            if (workspaceRaw == null || ambient == null || homeCRaw == null) {
                return NodeResult.fail("ticket.lifecycle.publish", "missing upstream context");
            }
            Path workspace = Path.of(workspaceRaw);
            Path homeC = Path.of(homeCRaw);

            // --- a unit with a real origin ------------------------------------
            Path remotes = workspace.resolve("remotes");
            Path sources = workspace.resolve("publish-sources");
            Path bare = remotes.resolve(UNIT + ".git");
            Files.createDirectories(remotes);
            Files.createDirectories(sources);
            List<String> setupFailures = new ArrayList<>();
            check(setupFailures, HomeSyncSupport.git(workspace, "init", "--bare", "-b", TRUNK,
                    bare.toString()));
            TicketLifecycleSupport.mkSource(sources, UNIT, "publishable v1");
            Path unitSource = sources.resolve(UNIT);
            check(setupFailures, HomeSyncSupport.git(unitSource, "init", "-b", TRUNK));
            check(setupFailures, HomeSyncSupport.git(unitSource, "config", "user.email",
                    "graph@localhost"));
            check(setupFailures, HomeSyncSupport.git(unitSource, "config", "user.name", "graph"));
            check(setupFailures, HomeSyncSupport.git(unitSource, "add", "-A"));
            check(setupFailures, HomeSyncSupport.git(unitSource, "commit", "-m", UNIT + " v1"));
            check(setupFailures, HomeSyncSupport.git(unitSource, "remote", "add", "origin",
                    bare.toString()));
            check(setupFailures, HomeSyncSupport.git(unitSource, "push", "origin", TRUNK));

            ProcessRecord install = TicketLifecycleSupport.sm(ctx, "install-publishable", homeCRaw,
                    "install", unitSource.toString(), "--yes");
            Path homeUnit = TicketLifecycleSupport.unitDir(homeC, UNIT);
            boolean theUnitIsACheckoutInTheHome = install.exitCode() == 0
                    && HomeSyncSupport.git(homeUnit, "rev-parse", "--is-inside-work-tree").ok();
            // The install points origin at the directory it was installed from;
            // a publish has to reach the unit's real remote, the same way a real
            // unit's origin names its trunk rather than whoever handed it over.
            HomeSyncSupport.git(homeUnit, "remote", "set-url", "origin", bare.toString());

            // --- the agent edits it in the home -------------------------------
            String edit = HomeSyncSupport.read(homeUnit.resolve("SKILL.md"))
                    + "the ticket agent improved this skill inside its worktree home\n";
            HomeSyncSupport.write(homeUnit.resolve("SKILL.md"), edit);
            String trunkBefore = HomeSyncSupport.git(bare, "rev-parse", TRUNK).trimmed();

            // --- --dry-run reports the pull request it would open --------------
            ProcessRecord dry = TicketLifecycleSupport.sm(ctx, "publish-dry-run", homeCRaw,
                    "unit", "publish", UNIT, "--ticket", TicketLifecycleSupport.TICKET_C,
                    "--home", homeCRaw, "--dry-run", "--json");
            Map<String, Object> dryReport = HomeSyncSupport.json(ctx, "publish-dry-run");
            boolean theDryRunReportsTheIntent = dry.exitCode() == 0
                    && "planned".equals(String.valueOf(dryReport.get("status")))
                    && BRANCH.equals(dryReport.get("branch"))
                    && TRUNK.equals(dryReport.get("base"));
            // The pull request it WOULD open is in the human sentence, not in
            // the --json payload (which carries an empty `pullRequest` for a
            // plan). So the human output is read too — the same reason the
            // drift remedy is checked there: the two surfaces have already
            // disagreed once in this epic, and only one of them was fixed.
            ProcessRecord dryText = TicketLifecycleSupport.sm(ctx, "publish-dry-run-text", homeCRaw,
                    "unit", "publish", UNIT, "--ticket", TicketLifecycleSupport.TICKET_C,
                    "--home", homeCRaw, "--dry-run");
            boolean theDryRunNamesThePullRequestItWouldOpen =
                    dryText.exitCode() == 0
                            && HomeSyncSupport.log(ctx, "publish-dry-run-text")
                                    .contains("open a pull request against " + TRUNK);
            List<String> branchesAfterDryRun = HomeSyncSupport
                    .git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines();
            boolean theDryRunChangedNothing = !branchesAfterDryRun.contains(BRANCH)
                    && trunkBefore.equals(HomeSyncSupport.git(bare, "rev-parse", TRUNK).trimmed());

            // --- --no-pr lands the branch --------------------------------------
            ProcessRecord publish = TicketLifecycleSupport.sm(ctx, "publish-no-pr", homeCRaw,
                    "unit", "publish", UNIT, "--ticket", TicketLifecycleSupport.TICKET_C,
                    "--home", homeCRaw, "--no-pr", "--json");
            Map<String, Object> report = HomeSyncSupport.json(ctx, "publish-no-pr");
            List<String> branches = HomeSyncSupport
                    .git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines();
            String trunkAfter = HomeSyncSupport.git(bare, "rev-parse", TRUNK).trimmed();

            boolean thePublishSucceeded = publish.exitCode() == 0
                    && "pushed_no_pr".equals(String.valueOf(report.get("status")));
            boolean theBranchIsNamedForTheTicketAndUnit = BRANCH.equals(report.get("branch"))
                    && branches.contains(BRANCH);
            boolean itIsBasedOnTheTrunk = TRUNK.equals(report.get("base"))
                    && HomeSyncSupport.git(bare, "merge-base", "--is-ancestor", trunkBefore, BRANCH)
                            .ok();
            boolean thePublishNeverMovedTheTrunk =
                    !trunkBefore.isBlank() && trunkBefore.equals(trunkAfter);
            boolean theBranchCarriesTheAgentsExactBytes = HomeSyncSupport
                    .git(bare, "show", BRANCH + ":SKILL.md").out().equals(edit);
            // Exactly the edited file, and nothing the home keeps for itself.
            List<String> movedFiles = HomeSyncSupport
                    .git(bare, "diff", "--name-only", TRUNK, BRANCH).lines();
            boolean onlyTheIntendedFilesMoved = movedFiles.equals(List.of("SKILL.md"));
            boolean theHomeCheckoutIsLeftClean =
                    HomeSyncSupport.git(homeUnit, "status", "--porcelain").trimmed().isEmpty();

            boolean pass = setupFailures.isEmpty() && theUnitIsACheckoutInTheHome
                    && theDryRunReportsTheIntent && theDryRunNamesThePullRequestItWouldOpen
                    && theDryRunChangedNothing && thePublishSucceeded
                    && theBranchIsNamedForTheTicketAndUnit && itIsBasedOnTheTrunk
                    && thePublishNeverMovedTheTrunk && theBranchCarriesTheAgentsExactBytes
                    && onlyTheIntendedFilesMoved && theHomeCheckoutIsLeftClean;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.publish")
                    : NodeResult.fail("ticket.lifecycle.publish",
                            "setupFailures=" + setupFailures
                                    + " installExit=" + install.exitCode()
                                    + " isCheckout=" + theUnitIsACheckoutInTheHome
                                    + " dryExit=" + dry.exitCode() + " dryReport=" + dryReport
                                    + " dryChangedNothing=" + theDryRunChangedNothing
                                    + " publishExit=" + publish.exitCode()
                                    + " status=" + report.get("status")
                                    + " branch=" + report.get("branch") + " branches=" + branches
                                    + " trunk=" + trunkBefore + "->" + trunkAfter
                                    + " movedFiles=" + movedFiles))
                    .process(install).process(dry).process(dryText).process(publish)
                    .assertion("a_unit_installed_from_git_is_a_checkout_in_the_home",
                            theUnitIsACheckoutInTheHome)
                    .assertion("a_dry_run_publish_reports_the_branch_and_base_it_would_use",
                            theDryRunReportsTheIntent)
                    .assertion("a_dry_run_publish_names_the_pull_request_it_would_open",
                            theDryRunNamesThePullRequestItWouldOpen)
                    .assertion("a_dry_run_publish_pushes_nothing", theDryRunChangedNothing)
                    .assertion("publish_pushes_the_home_edit_to_the_units_own_repository",
                            thePublishSucceeded)
                    .assertion("the_branch_is_named_skill_ticket_unit",
                            theBranchIsNamedForTheTicketAndUnit)
                    .assertion("the_branch_is_based_on_the_trunk", itIsBasedOnTheTrunk)
                    .assertion("publish_never_moves_the_trunk", thePublishNeverMovedTheTrunk)
                    .assertion("the_branch_carries_the_agents_exact_bytes",
                            theBranchCarriesTheAgentsExactBytes)
                    .assertion("only_the_files_the_agent_edited_moved", onlyTheIntendedFilesMoved)
                    .assertion("publish_leaves_the_homes_checkout_clean", theHomeCheckoutIsLeftClean)
                    .metric("remoteBranches", branches.size())
                    .metric("filesMoved", movedFiles.size());
        });
    }

    private static void check(List<String> failures, HomeSyncSupport.Capture capture) {
        if (!capture.ok()) failures.add(capture.trimmed());
    }
}
