///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GOAL-excluded-paths-keep-git, #261: an install honours a manifest exclusion and the
 * installed copy keeps every git function.
 *
 * <p>Fixture: a skill declaring an exclusion of {@code specs/.history} in its manifest,
 * installed from a bare upstream with two commits touching the excluded path. No
 * The upstream serves partial clones, so the install is a real promisor checkout.
 *
 * <p>Clauses, separately: (a) {@code shape_no_files_at_excluded_path}, the only one
 * expected red at baseline (the count is the metric); (b) the seven operations on the
 * installed unit, each its own assertion, including log/blame/diff on an excluded path;
 * (c) a commit made in the unit leaves the excluded tree unchanged.
 *
 * <p>Clause (b)/(c) arms are expected GREEN today (an install is a full clone) and are
 * the regression half: they exist so CC-5's partial/sparse clone is held to them.
 */
public class InstallExclusion {
    static final String ID = "cheap.install-exclusion";
    static final String UNIT = "cc-exclude-unit";
    static final String EXCLUDED = "specs/.history";
    static final String DECLARATION = "excluded_from_install = [\"" + EXCLUDED + "\"]";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "install", "261", "git")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path bare;
            Path writer;
            ProcessRecord install;
            String baselineTree;
            String advanced;
            try {
                root = CheapChecksKit.scratch(ctx, "install-exclusion");
                writer = root.resolve("upstream");
                bare = root.resolve("upstream.git");
                CheapChecksKit.writeSkill(writer, UNIT, "0.1.0", "marker");
                Files.writeString(writer.resolve("skill-manager.toml"), """
                        [skill]
                        name = "%s"
                        version = "0.1.0"
                        description = "cheap-checks graph fixture"
                        %s
                        """.formatted(UNIT, DECLARATION));
                Files.createDirectories(writer.resolve(EXCLUDED));
                Files.writeString(writer.resolve(EXCLUDED).resolve("a.txt"), "one\n");
                Files.writeString(writer.resolve(EXCLUDED).resolve("b.txt"), "b\n");
                CheapChecksKit.git(writer, "init", "-b", "main", "--quiet");
                CheapChecksKit.commitAll(writer, "A");
                Files.writeString(writer.resolve(EXCLUDED).resolve("a.txt"), "two\n");
                CheapChecksKit.commitAll(writer, "B");
                CheapChecksKit.git(root, "clone", "--quiet", "--bare", writer.toString(), bare.toString());
                CheapChecksKit.git(bare, "config", "uploadpack.allowFilter", "true");
                CheapChecksKit.git(writer, "remote", "add", "origin", bare.toString());
                Path home = root.resolve("home");
                CheapChecksKit.writePolicy(home);
                install = CheapChecksKit.sm(ctx, "install", root, home,
                        "install", "git+" + bare.toUri(), "--yes", "--no-bind-default");
                baselineTree = CheapChecksKit.gitOut(writer, "ls-tree", "-r", "HEAD", "--", EXCLUDED);
                Files.writeString(writer.resolve("later.txt"), "later\n");
                CheapChecksKit.commitAll(writer, "C");
                CheapChecksKit.git(writer, "push", "--quiet", "origin", "main");
                advanced = CheapChecksKit.gitOut(writer, "rev-parse", "HEAD");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            Path unit = root.resolve("home/skills").resolve(UNIT);
            boolean setupInstalled = install.exitCode() == 0 && Files.isDirectory(unit.resolve(".git"));
            boolean controlKeptFilePresent = Files.isRegularFile(unit.resolve("SKILL.md"));
            long excludedFiles;
            try {
                excludedFiles = CheapChecksKit.fileCount(unit.resolve(EXCLUDED));
            } catch (Exception e) {
                excludedFiles = -1;
            }

            // Before anything below faults a blob in: the object store really is partial.
            String[] missingAtInstall = CheapChecksKit.gitTry(unit, "rev-list", "--objects", "--missing=print", "--all");
            boolean blobsAbsent = ok(missingAtInstall) && missingAtInstall[1].lines().anyMatch(l -> l.startsWith("?"));

            String aPath = EXCLUDED + "/a.txt";
            String[] log = CheapChecksKit.gitTry(unit, "log", "--format=%h", "--", aPath);
            String[] blame = CheapChecksKit.gitTry(unit, "blame", "HEAD", "--", aPath);
            String[] diff = CheapChecksKit.gitTry(unit, "diff", "HEAD~1", "HEAD", "--", aPath);
            String[] status = CheapChecksKit.gitTry(unit, "status", "--porcelain");
            boolean logOk = ok(log) && log[1].lines().count() == 2;
            boolean blameOk = ok(blame) && blame[1].contains("two");
            boolean diffOk = ok(diff) && diff[1].contains("two") && diff[1].contains("one");
            boolean statusOk = ok(status) && status[1].isEmpty();

            String[] fetch = CheapChecksKit.gitTry(unit, "fetch", "origin", "main");
            String fetchedHead = CheapChecksKit.gitTry(unit, "rev-parse", "FETCH_HEAD")[1];
            boolean fetchOk = ok(fetch) && advanced.equals(fetchedHead);

            boolean commitOk = false;
            boolean pushOk = false;
            boolean keepsTree = false;
            String[] commit = {"-1", ""};
            String[] push = {"-1", ""};
            try {
                Files.writeString(unit.resolve("new-in-install.txt"), "x\n");
                CheapChecksKit.git(unit, "add", "new-in-install.txt");
                commit = CheapChecksKit.gitTry(unit, "-c", "user.email=cc@local", "-c", "user.name=cc",
                        "commit", "--quiet", "-m", "from the installed unit");
                commitOk = ok(commit);
                String afterTree = CheapChecksKit.gitOut(unit, "ls-tree", "-r", "HEAD", "--", EXCLUDED);
                keepsTree = baselineTree.equals(afterTree) && !afterTree.isEmpty();
                push = CheapChecksKit.gitTry(unit, "push", "origin", "HEAD:refs/heads/from-install");
                pushOk = ok(push) && ok(CheapChecksKit.gitTry(bare, "rev-parse", "refs/heads/from-install"));
            } catch (Exception e) {
                // Reported below by the false assertions; the message is in the detail.
                commit[1] = commit[1] + " " + e;
            }

            // What a sparse checkout tends to break: staging everything, stash, and a
            // sync that merges an upstream commit touching the excluded path.
            boolean addAllOk = false;
            boolean addAllKeepsTree = false;
            boolean stashOk = false;
            boolean syncOk = false;
            boolean syncStaysExcluded = false;
            boolean syncTookExcludedChange = false;
            boolean blameFetchedAfterSync = false;
            ProcessRecord sync = null;
            long excludedAfterSync = -1;
            try {
                String before = CheapChecksKit.gitOut(unit, "ls-tree", "-r", "HEAD", "--", EXCLUDED);
                Files.writeString(unit.resolve("added-by-all.txt"), "y\n");
                CheapChecksKit.git(unit, "add", "-A");
                addAllOk = ok(CheapChecksKit.gitTry(unit, "-c", "user.email=cc@local", "-c", "user.name=cc",
                        "commit", "--quiet", "-m", "after add -A"));
                addAllKeepsTree = before.equals(CheapChecksKit.gitOut(unit, "ls-tree", "-r", "HEAD", "--", EXCLUDED));

                Files.writeString(unit.resolve("SKILL.md"), CheapChecksKit.read(unit.resolve("SKILL.md")) + "dirty\n");
                stashOk = ok(CheapChecksKit.gitTry(unit, "stash", "--quiet"))
                        && ok(CheapChecksKit.gitTry(unit, "stash", "pop", "--quiet"))
                        && CheapChecksKit.gitTry(unit, "status", "--porcelain")[1].contains("SKILL.md");
                CheapChecksKit.git(unit, "checkout", "--quiet", "--", "SKILL.md");

                Files.writeString(writer.resolve(EXCLUDED).resolve("a.txt"), "three\n");
                CheapChecksKit.commitAll(writer, "D");
                CheapChecksKit.git(writer, "push", "--quiet", "origin", "main");
                sync = CheapChecksKit.sm(ctx, "sync", root, root.resolve("home"), "sync", UNIT, "--merge", "--yes");
                syncOk = sync.exitCode() == 0;
                excludedAfterSync = CheapChecksKit.fileCount(unit.resolve(EXCLUDED));
                syncStaysExcluded = excludedAfterSync == 0;
                String[] show = CheapChecksKit.gitTry(unit, "show", "HEAD:" + aPath);
                syncTookExcludedChange = ok(show) && show[1].contains("three");
                String[] blameAfter = CheapChecksKit.gitTry(unit, "blame", "HEAD", "--", aPath);
                blameFetchedAfterSync = ok(blameAfter) && blameAfter[1].contains("three");
            } catch (Exception e) {
                commit[1] = commit[1] + " " + e;
            }

            // A source that ignores --filter keeps the working-tree exclusion and its blobs.
            boolean fallbackExcluded = false;
            boolean fallbackBlobsPresent = false;
            boolean fallbackBlame = false;
            // A unit declaring nothing is exactly the clone it always was.
            boolean controlPlain = false;
            try {
                Path noFilter = root.resolve("no-filter.git");
                CheapChecksKit.git(root, "clone", "--quiet", "--bare", writer.toString(), noFilter.toString());
                Path fbHome = root.resolve("home-fallback");
                CheapChecksKit.writePolicy(fbHome);
                ProcessRecord fb = CheapChecksKit.sm(ctx, "install-fallback", root, fbHome,
                        "install", "git+" + noFilter.toUri(), "--yes", "--no-bind-default");
                Path fbUnit = fbHome.resolve("skills").resolve(UNIT);
                fallbackExcluded = fb.exitCode() == 0 && CheapChecksKit.fileCount(fbUnit.resolve(EXCLUDED)) == 0;
                String[] missing = CheapChecksKit.gitTry(fbUnit, "rev-list", "--objects", "--missing=print", "--all");
                fallbackBlobsPresent = ok(missing) && missing[1].lines().noneMatch(l -> l.startsWith("?"));
                fallbackBlame = ok(CheapChecksKit.gitTry(fbUnit, "blame", "HEAD", "--", aPath));

                Path plainWriter = root.resolve("plain-upstream");
                Path plainBare = root.resolve("plain.git");
                CheapChecksKit.writeSkill(plainWriter, "cc-plain-unit", "0.1.0", "marker");
                CheapChecksKit.git(plainWriter, "init", "-b", "main", "--quiet");
                CheapChecksKit.commitAll(plainWriter, "A");
                CheapChecksKit.git(root, "clone", "--quiet", "--bare", plainWriter.toString(), plainBare.toString());
                CheapChecksKit.git(plainBare, "config", "uploadpack.allowFilter", "true");
                Path plainHome = root.resolve("home-plain");
                CheapChecksKit.writePolicy(plainHome);
                CheapChecksKit.sm(ctx, "install-plain", root, plainHome,
                        "install", "git+" + plainBare.toUri(), "--yes", "--no-bind-default");
                Path plainUnit = plainHome.resolve("skills/cc-plain-unit");
                controlPlain = Files.isDirectory(plainUnit.resolve(".git"))
                        && !Files.exists(plainUnit.resolve(".git/info/sparse-checkout"))
                        && CheapChecksKit.gitTry(plainUnit, "config", "--get", "remote.origin.promisor")[1].isBlank()
                        && CheapChecksKit.gitTry(plainUnit, "config", "--get", "core.sparseCheckout")[1].isBlank()
                        && CheapChecksKit.gitTry(plainUnit, "config", "--get", "extensions.partialClone")[1].isBlank();
            } catch (Exception e) {
                commit[1] = commit[1] + " " + e;
            }

            // A home clone carries the sparse checkout and keeps it working.
            boolean cloneExcluded = false;
            boolean cloneClean = false;
            boolean cloneBlame = false;
            try {
                Path cloned = root.resolve("home-cloned");
                ProcessRecord homeClone = CheapChecksKit.cloneHome(ctx, "home-clone", root, root.resolve("home"), cloned);
                Path clonedUnit = cloned.resolve("skills").resolve(UNIT);
                cloneExcluded = homeClone.exitCode() == 0 && CheapChecksKit.fileCount(clonedUnit.resolve(EXCLUDED)) == 0;
                cloneClean = CheapChecksKit.gitTry(clonedUnit, "status", "--porcelain")[1].isBlank();
                cloneBlame = ok(CheapChecksKit.gitTry(clonedUnit, "blame", "HEAD", "--", aPath));
            } catch (Exception e) {
                commit[1] = commit[1] + " " + e;
            }

            boolean shapeExcluded = excludedFiles == 0;
            int gitOps = (logOk ? 1 : 0) + (blameOk ? 1 : 0) + (diffOk ? 1 : 0) + (statusOk ? 1 : 0)
                    + (commitOk ? 1 : 0) + (fetchOk ? 1 : 0) + (pushOk ? 1 : 0);
            String detail = "excluded files on disk=" + excludedFiles + "; git ops ok=" + gitOps + "/7"
                    + " log=" + log[0] + " blame=" + blame[0] + " diff=" + diff[0] + " status=" + status[0]
                    + " commit=" + commit[0] + " fetch=" + fetch[0] + " fetchedHead=" + fetchedHead + " advanced=" + advanced
                    + " push=" + push[0] + " keepsTree=" + keepsTree + "; install rc=" + install.exitCode()
                    + " blobsAbsent=" + blobsAbsent + " addAll=" + addAllOk + "/" + addAllKeepsTree + " stash=" + stashOk
                    + " sync=" + syncOk + " excludedAfterSync=" + excludedAfterSync + " syncContent=" + syncTookExcludedChange
                    + " blameAfterSync=" + blameFetchedAfterSync
                    + " fallback=" + fallbackExcluded + "/" + fallbackBlobsPresent + "/" + fallbackBlame
                    + " controlPlain=" + controlPlain + " homeClone=" + cloneExcluded + "/" + cloneClean + "/" + cloneBlame;
            boolean setup = setupInstalled;
            boolean all = setup && controlKeptFilePresent && shapeExcluded && gitOps == 7 && keepsTree
                    && blobsAbsent && addAllOk && addAllKeepsTree && stashOk && syncOk && syncStaysExcluded
                    && syncTookExcludedChange && blameFetchedAfterSync && fallbackExcluded && fallbackBlobsPresent
                    && fallbackBlame && controlPlain && cloneExcluded && cloneClean && cloneBlame;
            NodeResult result = all ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install)
                    .metric("excluded_files_on_disk", excludedFiles)
                    .metric("git_ops_working", gitOps)
                    .assertion("setup_installed_as_git_checkout", setupInstalled)
                    .assertion("control_non_excluded_file_is_installed", controlKeptFilePresent)
                    .assertion("shape_no_files_at_excluded_path", shapeExcluded)
                    .assertion("shape_git_log_on_excluded_path", logOk)
                    .assertion("shape_git_blame_on_excluded_path", blameOk)
                    .assertion("shape_git_diff_across_history_on_excluded_path", diffOk)
                    .assertion("shape_git_status_clean", statusOk)
                    .assertion("shape_git_commit", commitOk)
                    .assertion("shape_git_fetch", fetchOk)
                    .assertion("shape_git_push", pushOk)
                    .assertion("shape_commit_keeps_excluded_paths_in_tree", keepsTree)
                    .assertion("shape_partial_clone_blobs_absent", blobsAbsent)
                    .assertion("shape_commit_after_add_all", addAllOk)
                    .assertion("shape_add_all_keeps_excluded_paths_in_tree", addAllKeepsTree)
                    .assertion("shape_stash_and_pop", stashOk)
                    .assertion("shape_sync_merge_touching_excluded_path", syncOk)
                    .assertion("shape_sync_keeps_exclusion_on_disk", syncStaysExcluded)
                    .assertion("shape_sync_took_excluded_change_in_history", syncTookExcludedChange)
                    .assertion("shape_blame_on_demand_after_sync", blameFetchedAfterSync)
                    .assertion("fallback_no_filter_source_excludes_working_tree", fallbackExcluded)
                    .assertion("fallback_no_filter_source_keeps_blobs", fallbackBlobsPresent)
                    .assertion("fallback_blame_on_excluded_path", fallbackBlame)
                    .assertion("control_undeclared_unit_is_a_plain_clone", controlPlain)
                    .assertion("shape_home_clone_keeps_exclusion", cloneExcluded)
                    .assertion("shape_home_clone_status_clean", cloneClean)
                    .assertion("shape_home_clone_blame_on_excluded_path", cloneBlame)
                    .log(detail);
        });
    }

    private static boolean ok(String[] r) {
        return "0".equals(r[0]);
    }
}
