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
 * Git state, read from git, wherever this mechanism touches a working tree.
 *
 * <h2>Why an exit code is not evidence here</h2>
 *
 * <p>Every command in this graph can succeed while leaving a git checkout
 * subtly wrong: an index reset so a staged file is no longer staged, a stray
 * staged deletion left behind by a copy that walked the tree, a HEAD moved onto
 * a branch nobody asked for, a commit that quietly dropped work somebody had
 * already {@code git add}ed. None of those change an exit code and all of them
 * lose work. So the unit of evidence is
 * {@link HomeSyncSupport#gitState} — HEAD, branch, {@code status --porcelain}
 * and the full index from {@code ls-files -s} — captured before and compared
 * after, plus the committed blobs read back out of the repository.
 *
 * <h2>The three read-only operations</h2>
 *
 * <p>{@code home sync} reading a home, {@code home close-out} inspecting one,
 * and {@code unit publish --dry-run} planning against one must each leave the
 * checkout's git state <em>identical</em> — not "clean", identical, including
 * the deliberately dirty parts. A checkout with staged work and an unstaged
 * modification is set up first precisely so "identical" has something to be
 * wrong about; against a pristine checkout the assertion would pass for a
 * command that reset the index.
 *
 * <h2>And the one that writes</h2>
 *
 * <p>{@code unit publish} commits, so its correctness is what the commit
 * contains and what it does not move: the agent's edit and the unrelated staged
 * work both land ({@code git add -A} is the documented behaviour — the failure
 * to guard against is dropping it, not including it), the index comes out with
 * no residue, and the trunk on the remote does not move.
 */
public class HomeSyncGitFlawless {

    private static final String UNIT = "hs-git";
    private static final String STAGED = "staged-note.md";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.git.flawless")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.project.to.root")
            .tags("home-sync", "git", "publish")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gitHomeRaw = ctx.get("home.sync.project.to.root", "gitHome").orElse(null);
            String remoteRaw = ctx.get("home.sync.project.to.root", "gitRemote").orElse(null);
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            if (gitHomeRaw == null || remoteRaw == null || workspaceRaw == null) {
                return NodeResult.fail("home.sync.git.flawless", "missing upstream context");
            }
            Path gitHome = Path.of(gitHomeRaw);
            Path bare = Path.of(remoteRaw);
            Path repo = HomeSyncSupport.unitDir(gitHome, UNIT);
            Path dest = Path.of(workspaceRaw).resolve("git-dest");

            // --- make the checkout deliberately, realistically dirty ---------
            HomeSyncSupport.write(repo.resolve(STAGED), "work somebody already staged\n");
            HomeSyncSupport.git(repo, "add", STAGED);
            HomeSyncSupport.append(repo.resolve("skill-manager.toml"),
                    "# an unstaged working-tree change\n");

            String stateBefore = HomeSyncSupport.gitState(repo);
            String headBlobBefore = HomeSyncSupport.git(repo, "show", "HEAD:SKILL.md").out();
            String trunkBefore = HomeSyncSupport.git(bare, "rev-parse", "main").trimmed();
            boolean checkoutIsDirtyOnPurpose = !HomeSyncSupport
                    .git(repo, "status", "--porcelain").trimmed().isEmpty()
                    && HomeSyncSupport.git(repo, "diff", "--cached", "--name-only").lines()
                            .contains(STAGED);

            // --- read-only operation 1: a sync that reads this home ----------
            ProcessRecord sync = HomeSyncSupport.sm(ctx, "git-sync-out", gitHomeRaw,
                    "home", "sync", "--from", gitHomeRaw, "--to", dest.toString(), "--json");
            String syncStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "git-sync-out"), "skill:" + UNIT);
            boolean syncLeftGitAlone = HomeSyncSupport.gitState(repo).equals(stateBefore);

            // --- read-only operation 2: the close-out gate -------------------
            ProcessRecord closeOut = HomeSyncSupport.sm(ctx, "git-close-out", gitHomeRaw,
                    "home", "close-out", "--home", gitHomeRaw, "--into", dest.toString(), "--json");
            boolean closeOutLeftGitAlone = HomeSyncSupport.gitState(repo).equals(stateBefore);

            // --- read-only operation 3: a planned publish --------------------
            ProcessRecord planned = HomeSyncSupport.sm(ctx, "git-publish-dry", gitHomeRaw,
                    "unit", "publish", UNIT, "--ticket", "T-DRY", "--dry-run",
                    "--home", gitHomeRaw, "--json");
            Map<String, Object> plannedReport = HomeSyncSupport.json(ctx, "git-publish-dry");
            boolean dryPublishPlanned = planned.exitCode() == 0
                    && "planned".equals(String.valueOf(plannedReport.get("status")));
            boolean dryPublishLeftGitAlone = HomeSyncSupport.gitState(repo).equals(stateBefore);
            boolean headBlobUnmoved = HomeSyncSupport.git(repo, "show", "HEAD:SKILL.md").out()
                    .equals(headBlobBefore);

            // --- the operation that does write -------------------------------
            String agentEdit = HomeSyncSupport.read(repo.resolve("SKILL.md"))
                    + "a second improvement, published on its own ticket\n";
            HomeSyncSupport.write(repo.resolve("SKILL.md"), agentEdit);
            LinkedHashMap<String, String> destBeforePublish =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT));

            ProcessRecord publish = HomeSyncSupport.sm(ctx, "git-publish", gitHomeRaw,
                    "unit", "publish", UNIT, "--ticket", "T-C2", "--no-pr",
                    "--home", gitHomeRaw, "--json");
            Map<String, Object> publishReport = HomeSyncSupport.json(ctx, "git-publish");
            String branch = String.valueOf(publishReport.get("branch"));

            boolean publishOk = publish.exitCode() == 0
                    && "pushed_no_pr".equals(String.valueOf(publishReport.get("status")));
            boolean commitCarriesTheEdit = HomeSyncSupport
                    .git(repo, "show", "HEAD:SKILL.md").out().equals(agentEdit);
            boolean stagedWorkSurvivedTheCommit = HomeSyncSupport
                    .git(repo, "show", "HEAD:" + STAGED).out()
                    .equals("work somebody already staged\n");
            boolean workingTreeIsClean = HomeSyncSupport
                    .git(repo, "status", "--porcelain").trimmed().isEmpty();
            boolean noStrayStagedChange = HomeSyncSupport
                    .git(repo, "diff", "--cached", "--name-status").trimmed().isEmpty();
            List<String> index = HomeSyncSupport.git(repo, "ls-files", "-s").lines();
            boolean indexIsIntact = index.size() >= 3
                    && index.stream().allMatch(line -> line.split("\\s+").length >= 4
                            && "0".equals(line.split("\\s+")[2]))
                    && index.stream().anyMatch(line -> line.endsWith("\t" + STAGED))
                    && index.stream().anyMatch(line -> line.endsWith("\tSKILL.md"));
            boolean trunkStillUnmoved = trunkBefore
                    .equals(HomeSyncSupport.git(bare, "rev-parse", "main").trimmed());
            boolean branchOnTheRemoteCarriesTheCommit = HomeSyncSupport
                    .git(bare, "show", branch + ":SKILL.md").out().equals(agentEdit);
            boolean publishTouchedNoOtherHome = HomeSyncSupport.difference(destBeforePublish,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT))).isEmpty();

            // --- a copied git unit that the destination has since edited ------
            Path destUnit = HomeSyncSupport.unitDir(dest, UNIT);
            boolean destUnitExists = Files.isDirectory(destUnit);
            HomeSyncSupport.append(destUnit.resolve("SKILL.md"),
                    "edited in the destination home\n");
            LinkedHashMap<String, String> destEditedBefore = HomeSyncSupport.entryDigests(destUnit);
            ProcessRecord resync = HomeSyncSupport.sm(ctx, "git-resync", gitHomeRaw,
                    "home", "sync", "--from", gitHomeRaw, "--to", dest.toString(), "--json");
            String resyncStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "git-resync"), "skill:" + UNIT);
            boolean editedCopyIsNeverOverwritten = resync.exitCode() == 0
                    && HomeSyncSupport.difference(destEditedBefore,
                            HomeSyncSupport.entryDigests(destUnit)).isEmpty();

            boolean pass = checkoutIsDirtyOnPurpose && sync.exitCode() == 0 && syncLeftGitAlone
                    && closeOut.exitCode() >= 0 && closeOutLeftGitAlone && dryPublishPlanned
                    && dryPublishLeftGitAlone && headBlobUnmoved && publishOk
                    && commitCarriesTheEdit && stagedWorkSurvivedTheCommit && workingTreeIsClean
                    && noStrayStagedChange && indexIsIntact && trunkStillUnmoved
                    && branchOnTheRemoteCarriesTheCommit && publishTouchedNoOtherHome
                    && destUnitExists && editedCopyIsNeverOverwritten;
            return (pass
                    ? NodeResult.pass("home.sync.git.flawless")
                    : NodeResult.fail("home.sync.git.flawless",
                            "dirtyOnPurpose=" + checkoutIsDirtyOnPurpose
                                    + " syncExit=" + sync.exitCode()
                                    + " syncLeftGitAlone=" + syncLeftGitAlone
                                    + " closeOutLeftGitAlone=" + closeOutLeftGitAlone
                                    + " dryPublish=" + plannedReport.get("status")
                                    + " dryPublishLeftGitAlone=" + dryPublishLeftGitAlone
                                    + " publishStatus=" + publishReport.get("status")
                                    + " commitCarriesTheEdit=" + commitCarriesTheEdit
                                    + " stagedSurvived=" + stagedWorkSurvivedTheCommit
                                    + " clean=" + workingTreeIsClean
                                    + " index=" + index
                                    + " trunkUnmoved=" + trunkStillUnmoved
                                    + " resync=" + resyncStatus
                                    + " editedCopyKept=" + editedCopyIsNeverOverwritten))
                    .process(sync).process(closeOut).process(planned).process(publish)
                    .process(resync)
                    .assertion("the_checkout_under_test_really_is_dirty", checkoutIsDirtyOnPurpose)
                    .assertion("a_sync_reading_a_home_leaves_its_git_state_identical",
                            syncLeftGitAlone)
                    .assertion("close_out_leaves_the_checkouts_git_state_identical",
                            closeOutLeftGitAlone)
                    .assertion("a_planned_publish_reports_without_touching_git",
                            dryPublishPlanned && dryPublishLeftGitAlone)
                    .assertion("no_read_only_operation_moved_a_committed_blob", headBlobUnmoved)
                    .assertion("publish_commits_and_pushes_the_home_edit", publishOk)
                    .assertion("the_commit_holds_the_agents_exact_bytes", commitCarriesTheEdit)
                    .assertion("unrelated_staged_work_is_carried_into_the_commit_not_dropped",
                            stagedWorkSurvivedTheCommit)
                    .assertion("the_working_tree_is_clean_after_a_publish", workingTreeIsClean)
                    .assertion("no_stray_staged_change_is_left_behind", noStrayStagedChange)
                    .assertion("every_index_entry_is_at_stage_zero_and_present", indexIsIntact)
                    .assertion("publish_never_moves_the_trunk_on_the_remote", trunkStillUnmoved)
                    .assertion("the_remote_branch_holds_the_commit_that_was_made",
                            branchOnTheRemoteCarriesTheCommit)
                    .assertion("publishing_from_one_home_writes_into_no_other",
                            publishTouchedNoOtherHome)
                    .assertion("a_destination_edited_git_unit_is_never_overwritten",
                            editedCopyIsNeverOverwritten)
                    .metric("indexEntries", index.size())
                    .metric("gitUnitFirstSyncStatus", "new".equals(syncStatus) ? 1 : 0)
                    .metric("gitUnitResyncHeldBack", "held-back".equals(resyncStatus) ? 1 : 0);
        });
    }
}
