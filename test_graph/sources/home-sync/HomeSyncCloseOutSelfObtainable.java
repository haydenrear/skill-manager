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
 * <b>DEF-101 at the CLI, and the node that would have caught MAJOR 1.</b>
 *
 * <h2>Why this exists</h2>
 *
 * <p>HIS-22 shipped DEF-101 — {@code home close-out} clearing a {@code NEW} unit
 * the destination's own manifest declares — with four in-process unit cases and
 * no graph coverage. The reviewer of PR #255 found the defect those four cases
 * <em>structurally could not see</em>, and found it by running the real CLI:
 *
 * <pre>
 *   {"safe":true,"exitCode":0,"blockers":[],
 *    "selfObtainable":[{"unit":"skill:probe-skill",
 *      "remedy":"... nothing in this worktree's copy exists only here"}]}
 *   ! skills with outstanding errors (1)
 *   ✗   probe-skill:
 *   ✗     - NO_GIT_REMOTE: git-tracked but no origin remote configured
 * </pre>
 *
 * <p><b>One command, one document, two readers, opposite answers</b> — the gate
 * saying nothing would be lost, the closing report saying the unit is on no
 * remote. An in-process case that calls {@code HomeCloseOut.inspect} sees the
 * verdict and never sees the report printed beside it, so no number of them
 * could have caught it. That is the argument for this node, and it is the same
 * argument {@code GOAL-one-home-one-answer} makes about every other pair of
 * readers in this epic.
 *
 * <h2>The three arms</h2>
 *
 * <ol>
 *   <li><b>published + declared clears.</b> A checkout whose HEAD is on a real
 *       {@code refs/remotes/} ref, declared by the destination's own manifest.
 *       {@code safe:true}, and the exemption names the ref it rests on.</li>
 *   <li><b>unpublished blocks.</b> The reviewer's case, built the way it is
 *       really produced: commits made after the clone baseline was stamped, so
 *       the unit reads as pristine and is on no remote.</li>
 *   <li><b>the two readers agree.</b> Whatever the verdict, the gate and the
 *       closing error report must not contradict each other about the same unit
 *       in the same document. This is the assertion that would have failed
 *       before the fix.</li>
 * </ol>
 */
public class HomeSyncCloseOutSelfObtainable {

    private static final String DECLARED = "cos-declared-unit";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.close.out.self.obtainable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.project.to.root.change.management")
            .tags("home-sync", "close-out", "def-101", "one-home-one-answer")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail(SPEC.id(), "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("close-out-self-obtainable");
            Path repoRoot = base.resolve("project");
            Path project = repoRoot.resolve(".skill-manager");
            Path seed = base.resolve("seed/.skill-manager");
            Path worktree = base.resolve("wt/.skill-manager");
            Path origin = base.resolve("origins/declared.git");
            Path staging = base.resolve("staging");
            // A resumed run reuses the workspace, and this node builds git
            // repositories -- a second pass over an existing `staging` tree hits
            // "nothing to commit, working tree clean" and ERRORS rather than
            // failing an assertion, which reads as a node defect. Start from
            // nothing so the node is idempotent under --resume-from-node.
            if (Files.exists(base)) {
                try (var walk = Files.walk(base)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (java.io.IOException ignored) {
                            // best effort; the workspace is a temp tree
                        }
                    });
                }
            }
            Files.createDirectories(repoRoot);
            Files.createDirectories(staging);
            HomeSyncSupport.mkHome(project);
            HomeSyncSupport.mkHome(seed);

            // The destination's OWN manifest, beside its own home. That geometry
            // is the mechanism under test, not scaffolding.
            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "cos-project"

                    [skills.%s]
                    source = "git+file://%s#main"
                    """.formatted(DECLARED, origin));

            // A unit whose history exists on a real remote: a bare repo on disk.
            // Nothing about `git push` differs for a filesystem remote, and a
            // fixture that wrote a ref by hand would be asserting that a string
            // exists rather than that a commit is reachable.
            HomeSyncSupport.mkSource(staging, DECLARED, "declared unit v1");
            Path src = staging.resolve(DECLARED);
            git(src, "init", "-b", "main");
            git(src, "add", ".");
            git(src, "-c", "user.email=t@e.com", "-c", "user.name=T", "commit", "-m", "published");
            Files.createDirectories(origin.getParent());
            git(src, "clone", "--bare", src.toString(), origin.toString());
            Path seedUnit = HomeSyncSupport.unitDir(seed, DECLARED);
            Files.createDirectories(seedUnit.getParent());
            git(base, "clone", origin.toString(), seedUnit.toString());

            // THE WORKTREE HOME IS A CLONE, because that is what it is in
            // reality: bootstrap-home.sh makes every ticket worktree home with
            // `home clone`, and `home clone` is what runs recordCloneBaselines
            // on its destination. The first version of this node built the
            // worktree home by hand and cloned it AWAY, so the home under test
            // had no materialization record at all -- every unit read as locally
            // modified, nothing could ever be exempt, and the node failed for a
            // reason that had nothing to do with the claim. Mechanism B, caught
            // by running it.
            ProcessRecord baseline = HomeSyncSupport.sm(ctx, "cos-baseline", ambient,
                    "home", "clone", "--from", seed.toString(),
                    "--to", worktree.toString(), "--json");
            Path unitDir = HomeSyncSupport.unitDir(worktree, DECLARED);

            // FIXTURE PRECONDITIONS, asserted rather than hoped. Either of these
            // being false makes arm 1 unable to pass for the right reason.
            boolean theWorktreeHomeIsACloneCarryingTheUnit =
                    baseline.exitCode() == 0
                            && Files.isRegularFile(unitDir.resolve("SKILL.md"));
            boolean theClonedUnitKeptItsRemoteTrackingRef =
                    Files.isDirectory(unitDir.resolve(".git"))
                            && (Files.exists(unitDir.resolve(".git/refs/remotes/origin/main"))
                                || HomeSyncSupport.read(unitDir.resolve(".git/packed-refs"))
                                        .contains("refs/remotes/origin/main"));

            // ------------------------------------------- ARM 1: published clears
            ProcessRecord published = HomeSyncSupport.sm(ctx, "cos-published",
                    ambient, "home", "close-out", "--home", worktree.toString(),
                    "--into", project.toString(), "--json");
            String publishedLog = HomeSyncSupport.log(ctx, "cos-published");
            String publishedJson = HomeSyncSupport.jsonLine(publishedLog);
            boolean aPublishedDeclaredUnitClearsTheGate = published.exitCode() == 0
                    && publishedJson.contains("\"safe\":true")
                    && publishedJson.contains("\"selfObtainable\":[{")
                    && publishedJson.contains(DECLARED);
            boolean theExemptionNamesTheRefItRestsOn =
                    publishedJson.contains("refs/remotes/");

            // ----------------------------------------- ARM 2: unpublished blocks
            // Commits made AFTER the baseline was stamped: pristine by its own
            // record, and on no remote. This is the reviewer's case.
            Files.writeString(unitDir.resolve("SKILL.md"),
                    Files.readString(unitDir.resolve("SKILL.md")) + "\nNEVER PUSHED\n");
            git(unitDir, "add", ".");
            git(unitDir, "-c", "user.email=t@e.com", "-c", "user.name=T",
                    "commit", "-m", "work that exists nowhere else");
            git(unitDir, "remote", "remove", "origin");
            git(unitDir, "update-ref", "-d", "refs/remotes/origin/main");

            ProcessRecord unpublished = HomeSyncSupport.sm(ctx, "cos-unpublished",
                    ambient, "home", "close-out", "--home", worktree.toString(),
                    "--into", project.toString(), "--json");
            String unpublishedLog = HomeSyncSupport.log(ctx, "cos-unpublished");
            String unpublishedJson = HomeSyncSupport.jsonLine(unpublishedLog);
            boolean unpublishedWorkStillBlocksTheTeardown = unpublished.exitCode() != 0
                    && unpublishedJson.contains("\"safe\":false")
                    && unpublishedJson.contains("\"selfObtainable\":[]");

            // ------------------------- ARM 3: the two readers must not disagree
            // THE ASSERTION THAT WOULD HAVE CAUGHT MAJOR 1. The gate's verdict
            // and the closing error report travel in one document; if the report
            // says a unit is on no remote, the gate must not say nothing would be
            // lost about that same unit.
            boolean reportSaysNoRemote = unpublishedLog.contains("NO_GIT_REMOTE")
                    || unpublishedLog.contains("no origin remote");
            boolean theGateAndTheErrorReportAgree =
                    !(reportSaysNoRemote && unpublishedJson.contains("\"safe\":true"));

            // POSITIVE CONTROL for arm 3. `theGateAndTheErrorReportAgree` is an
            // implication, and an implication is trivially true when its
            // antecedent never holds -- a green that means "the report never
            // mentioned a remote" reads exactly like a green that means "and the
            // gate agreed with it". So assert the antecedent was reachable: the
            // gate DID have an opinion about this unit and DID refuse it.
            boolean armThreeWasReachable = unpublishedJson.contains(DECLARED)
                    && unpublishedJson.contains("\"blockers\":[{");

            boolean pass = theWorktreeHomeIsACloneCarryingTheUnit
                    && theClonedUnitKeptItsRemoteTrackingRef
                    && aPublishedDeclaredUnitClearsTheGate
                    && theExemptionNamesTheRefItRestsOn
                    && unpublishedWorkStillBlocksTheTeardown
                    && theGateAndTheErrorReportAgree
                    && armThreeWasReachable;

            return (pass ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "cloneOk=" + theWorktreeHomeIsACloneCarryingTheUnit
                                    + " remoteRefKept=" + theClonedUnitKeptItsRemoteTrackingRef
                                    + " publishedExit=" + published.exitCode()
                                    + " publishedJson=" + publishedJson
                                    + " unpublishedExit=" + unpublished.exitCode()
                                    + " unpublishedJson=" + unpublishedJson
                                    + " reportSaysNoRemote=" + reportSaysNoRemote))
                    .process(baseline).process(published).process(unpublished)
                    .assertion("the_worktree_home_is_a_clone_that_carries_the_unit",
                            theWorktreeHomeIsACloneCarryingTheUnit)
                    .assertion("and_the_cloned_unit_kept_its_remote_tracking_ref",
                            theClonedUnitKeptItsRemoteTrackingRef)
                    .assertion("a_published_unit_the_destination_declares_clears_the_gate",
                            aPublishedDeclaredUnitClearsTheGate)
                    .assertion("and_the_exemption_names_the_remote_ref_it_rests_on",
                            theExemptionNamesTheRefItRestsOn)
                    .assertion("commits_that_exist_on_no_remote_still_block_the_teardown",
                            unpublishedWorkStillBlocksTheTeardown)
                    .assertion("the_gate_and_the_closing_error_report_never_disagree",
                            theGateAndTheErrorReportAgree)
                    .assertion("positive_control_arm_three_had_something_to_disagree_about",
                            armThreeWasReachable)
                    .metric("publishedExitCode", published.exitCode())
                    .metric("unpublishedExitCode", unpublished.exitCode());
        });
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        }
    }
}
