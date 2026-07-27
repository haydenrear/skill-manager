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
 * <b>Direction B — project → root.</b> The two ways an edit made in a project
 * home gets out of it, and which one applies when.
 *
 * <h2>They are not alternatives; they carry different things</h2>
 *
 * <p>{@code home sync} moves <em>bytes</em> between two homes on this machine.
 * {@code unit publish} moves <em>history</em> to the unit's own repository, as
 * a branch and a pull request, so everyone who installs that unit gets the
 * improvement rather than one operator's home. A skill improved in a project
 * home wants both: the sync so the operator's other worktrees see it now, the
 * publish so it outlives the machine.
 *
 * <h2>The root home is the awkward destination, and this is why</h2>
 *
 * <p>A root home is <em>installed</em> into, never materialized into, so it
 * carries no per-unit materialization record. The destination's record is what
 * a reconcile uses to tell "this tree is a pristine copy" from "this tree holds
 * work", and with none it must assume the second. So a plain
 * {@code home sync --from project --to root} holds every differing unit back —
 * correctly, and unhelpfully if that is not expected. {@code --merge} then
 * succeeds, because the merge base falls back to the <em>source's</em> record:
 * the project home was cloned from the root home and wrote down what it started
 * from, and that clone-time record is the only surviving witness to the two
 * homes' common ancestor. Both halves are asserted here, in that order, because
 * the first is the one that surprises people and the second is the one that
 * makes the direction usable at all.
 *
 * <h2>The publish half runs against a real remote, locally</h2>
 *
 * <p>A bare repository in a temp directory. Nothing about {@code git push} is
 * different for a filesystem remote, and a graph that skipped the push would be
 * asserting that a function was called rather than that a branch exists with
 * the agent's bytes on it. The trunk hash is captured before and compared
 * after: the one thing a publish must never do is move {@code main}.
 */
public class HomeSyncProjectToRoot {

    private static final String ALPHA = "hs-alpha";
    private static final String BETA = "hs-beta";
    private static final String GIT_UNIT = "hs-git";
    private static final String TICKET = "T-B";
    private static final String BRANCH = "skill/t-b-hs-git";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.project.to.root")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.root.to.project")
            .tags("home-sync", "project-to-root", "publish")
            .timeout("300s")
            .output("gitHome", "string")
            .output("gitRemote", "string")
            .output("gitSource", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String rootRaw = ctx.get("home.sync.fixture.built", "rootHome").orElse(null);
            String projectRaw = ctx.get("home.sync.fixture.built", "projectHome").orElse(null);
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            String expectedBeta = ctx.get("home.sync.root.to.project", "projectBetaContent")
                    .orElse(null);
            if (rootRaw == null || projectRaw == null || workspaceRaw == null
                    || ambient == null || expectedBeta == null) {
                return NodeResult.fail("home.sync.project.to.root", "missing upstream context");
            }
            Path root = Path.of(rootRaw);
            Path project = Path.of(projectRaw);
            Path workspace = Path.of(workspaceRaw);

            // ---------------- part 1: the copy path, upward ------------------
            LinkedHashMap<String, String> rootBetaBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(root, BETA));

            ProcessRecord plain = HomeSyncSupport.sm(ctx, "sync-project-to-root", ambient,
                    "home", "sync", "--from", projectRaw, "--to", rootRaw, "--json");
            Map<String, Object> plainReport = HomeSyncSupport.json(ctx, "sync-project-to-root");
            List<String> rootBetaMoved = HomeSyncSupport.difference(rootBetaBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(root, BETA)));

            boolean plainHeldBack = plain.exitCode() == 0
                    && HomeSyncSupport.status(plainReport, "skill:" + BETA).equals("held-back");
            boolean plainWroteNothing = rootBetaMoved.isEmpty();

            LinkedHashMap<String, String> projectBefore = HomeSyncSupport.entryDigests(project);
            ProcessRecord merged = HomeSyncSupport.sm(ctx, "merge-project-to-root", ambient,
                    "home", "sync", "--from", projectRaw, "--to", rootRaw, "--merge", "--json");
            Map<String, Object> mergedReport = HomeSyncSupport.json(ctx, "merge-project-to-root");

            boolean mergeMoved = merged.exitCode() == 0
                    && HomeSyncSupport.status(mergedReport, "skill:" + BETA).equals("merged");
            boolean rootGotTheEdit = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(root, BETA).resolve("SKILL.md"))
                    .equals(expectedBeta);
            boolean rootGotTheAddedFile = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(root, BETA).resolve("project-note.md"))
                    .equals("a file only the project home has\n");
            boolean otherUnitsUntouched =
                    HomeSyncSupport.status(mergedReport, "skill:" + ALPHA).equals("unchanged");
            boolean projectOnlyRead = HomeSyncSupport.difference(projectBefore,
                    HomeSyncSupport.entryDigests(project)).isEmpty();

            // ---------------- part 2: the publish path -----------------------
            Path remotes = workspace.resolve("remotes");
            Path gitSource = workspace.resolve("git-source");
            Path gitHome = workspace.resolve("git-home");
            Path bare = remotes.resolve(GIT_UNIT + ".git");
            Files.createDirectories(remotes);
            Files.createDirectories(gitSource);

            HomeSyncSupport.git(workspace, "init", "--bare", "-b", "main", bare.toString());
            HomeSyncSupport.mkSource(gitSource, GIT_UNIT, "git unit v1");
            Path unitSource = gitSource.resolve(GIT_UNIT);
            HomeSyncSupport.git(unitSource, "init", "-b", "main");
            HomeSyncSupport.git(unitSource, "add", "-A");
            HomeSyncSupport.git(unitSource, "-c", "user.email=graph@localhost",
                    "-c", "user.name=graph", "commit", "-m", "hs-git v1");
            HomeSyncSupport.git(unitSource, "remote", "add", "origin", bare.toString());
            HomeSyncSupport.git(unitSource, "push", "origin", "main");

            ProcessRecord install = HomeSyncSupport.sm(ctx, "install-git-unit", gitHome.toString(),
                    "install", unitSource.toString(), "--yes");
            Path homeUnit = HomeSyncSupport.unitDir(gitHome, GIT_UNIT);
            boolean homeUnitIsACheckout = install.exitCode() == 0
                    && HomeSyncSupport.git(homeUnit, "rev-parse", "--is-inside-work-tree").ok();
            // The install points origin at the directory it was installed from;
            // a publish has to reach the unit's real remote, so it is repointed
            // at the bare repo the same way a real unit's origin points at its
            // trunk rather than at whoever happened to hand it over.
            HomeSyncSupport.git(homeUnit, "remote", "set-url", "origin", bare.toString());

            String trunkBefore = HomeSyncSupport.git(bare, "rev-parse", "main").trimmed();
            String agentEdit = HomeSyncSupport.read(homeUnit.resolve("SKILL.md"))
                    + "AGENT IMPROVED THIS SKILL IN A HOME\n";
            HomeSyncSupport.write(homeUnit.resolve("SKILL.md"), agentEdit);

            ProcessRecord publish = HomeSyncSupport.sm(ctx, "unit-publish", gitHome.toString(),
                    "unit", "publish", GIT_UNIT, "--ticket", TICKET, "--no-pr",
                    "--home", gitHome.toString(), "--json");
            Map<String, Object> publishReport = HomeSyncSupport.json(ctx, "unit-publish");

            List<String> remoteBranches = HomeSyncSupport
                    .git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines();
            String publishedBlob = HomeSyncSupport.git(bare, "show", BRANCH + ":SKILL.md").out();
            String trunkAfter = HomeSyncSupport.git(bare, "rev-parse", "main").trimmed();

            boolean publishOk = publish.exitCode() == 0
                    && "pushed_no_pr".equals(String.valueOf(publishReport.get("status")));
            boolean branchPushed = remoteBranches.contains(BRANCH);
            boolean branchCarriesTheEdit = publishedBlob.equals(agentEdit);
            boolean trunkUntouched = !trunkBefore.isBlank() && trunkBefore.equals(trunkAfter);
            boolean homeCheckoutIsClean =
                    HomeSyncSupport.git(homeUnit, "status", "--porcelain").trimmed().isEmpty();
            boolean publishNamedTheBranch = BRANCH.equals(publishReport.get("branch"));

            boolean pass = plainHeldBack && plainWroteNothing && mergeMoved && rootGotTheEdit
                    && rootGotTheAddedFile && otherUnitsUntouched && projectOnlyRead
                    && homeUnitIsACheckout && publishOk && branchPushed && branchCarriesTheEdit
                    && trunkUntouched && homeCheckoutIsClean && publishNamedTheBranch;
            return (pass
                    ? NodeResult.pass("home.sync.project.to.root")
                    : NodeResult.fail("home.sync.project.to.root",
                            "plainHeldBack=" + plainHeldBack
                                    + " plainWroteNothing=" + plainWroteNothing
                                    + " rootBetaMoved=" + rootBetaMoved
                                    + " mergeMoved=" + mergeMoved
                                    + " beta=" + HomeSyncSupport.status(mergedReport, "skill:" + BETA)
                                    + " rootGotTheEdit=" + rootGotTheEdit
                                    + " projectOnlyRead=" + projectOnlyRead
                                    + " publishExit=" + publish.exitCode()
                                    + " publishStatus=" + publishReport.get("status")
                                    + " branches=" + remoteBranches
                                    + " trunk=" + trunkBefore + "->" + trunkAfter))
                    .process(plain).process(merged).process(install).process(publish)
                    .assertion("a_plain_sync_into_a_recordless_root_home_holds_every_unit_back",
                            plainHeldBack)
                    .assertion("the_held_back_root_unit_keeps_every_byte_it_had", plainWroteNothing)
                    .assertion("merge_folds_the_project_edit_into_the_root_home", mergeMoved)
                    .assertion("the_root_home_ends_with_the_projects_bytes", rootGotTheEdit)
                    .assertion("a_file_only_the_project_had_reaches_the_root_home",
                            rootGotTheAddedFile)
                    .assertion("units_neither_side_moved_stay_unchanged", otherUnitsUntouched)
                    .assertion("the_project_home_is_only_read_by_an_upward_sync", projectOnlyRead)
                    .assertion("a_unit_installed_from_git_is_a_checkout_in_the_home",
                            homeUnitIsACheckout)
                    .assertion("unit_publish_pushes_the_home_edit_to_the_units_own_repo", publishOk)
                    .assertion("publish_lands_on_a_ticket_branch_never_the_trunk",
                            branchPushed && publishNamedTheBranch)
                    .assertion("the_published_branch_carries_the_agents_exact_bytes",
                            branchCarriesTheEdit)
                    .assertion("publish_never_moves_the_trunk", trunkUntouched)
                    .assertion("publish_leaves_the_homes_checkout_clean", homeCheckoutIsClean)
                    .metric("remoteBranches", remoteBranches.size())
                    .publish("gitHome", gitHome.toString())
                    .publish("gitRemote", bare.toString())
                    .publish("gitSource", unitSource.toString());
        });
    }
}
