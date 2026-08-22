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

/**
 * <b>Step 1 — provision.</b> Two ticket worktrees, through
 * {@code new-change.sh}, and each with a home that is genuinely its own.
 *
 * <h2>Why the inode comparison and not a report</h2>
 *
 * <p>"The worktree has its own home" is exactly the claim a symlink, a bind
 * mount or a hardlinked tree would also satisfy in every report the CLI prints.
 * So the assertion is made on the filesystem: the home ROOT has an inode of its
 * own, and so does a file inside it that a later node will edit. The second
 * half matters more than the first — a copy that shares file inodes with its
 * source is a home whose first edit silently rewrites the project home, which
 * is the failure the three-tier model exists to prevent and which no status
 * line would mention.
 *
 * <h2>And no path resolving back</h2>
 *
 * <p>Read from the tree rather than from {@code home verify}. That is not
 * distrust of the command: {@code home verify}'s default-mode reporting is
 * being changed under issue #133 (SYMLINK_TARGET and FOREIGN_HOME move to
 * reported-and-failed by default, CONTENT_REFERENCE stays tolerated), so a node
 * that asserted on its exit code would break for a reason that has nothing to
 * do with this graph. Walking the tree for the source home's path answers the
 * same question and answers it the same way before and after that change.
 *
 * <h2>A runnable pinned CLI</h2>
 *
 * <p>Since skill-manager #61 a launch has no PATH fallback at all, so the pin
 * at {@code <home>/bin/cli/skill-manager} is not a preference — a home whose
 * pin is wrong or missing cannot launch. Asserted by RUNNING it: it has to
 * answer {@code home close-out --help} with the option {@code close-change.sh}
 * itself probes for. A pin that exists and does not run is the state #61 was
 * filed about.
 */
public class TicketLifecycleProvisioned {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.provisioned")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.fixture.built")
            .tags("ticket-lifecycle", "new-change", "isolation")
            .timeout("900s")
            .output("worktreeA", "string")
            .output("worktreeB", "string")
            .output("homeA", "string")
            .output("homeB", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String checkoutRaw = ctx.get("ticket.lifecycle.fixture.built", "checkout").orElse(null);
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            String rootRaw = ctx.get("ticket.lifecycle.fixture.built", "rootHome").orElse(null);
            String scriptsRaw = ctx.get("ticket.lifecycle.fixture.built", "scriptsDir")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            if (checkoutRaw == null || projectRaw == null || rootRaw == null
                    || scriptsRaw == null || ambient == null) {
                return NodeResult.fail("ticket.lifecycle.provisioned", "missing upstream context");
            }
            Path checkout = Path.of(checkoutRaw);
            Path project = Path.of(projectRaw);
            Path newChange = Path.of(scriptsRaw).resolve("new-change.sh");

            ProcessRecord provisionA = TicketLifecycleSupport.script(ctx, "new-change-a",
                    checkout, newChange, ambient, TicketLifecycleSupport.TICKET_A);
            ProcessRecord provisionB = TicketLifecycleSupport.script(ctx, "new-change-b",
                    checkout, newChange, ambient, TicketLifecycleSupport.TICKET_B);
            boolean bothProvisioned = provisionA.exitCode() == 0 && provisionB.exitCode() == 0;

            Path worktreeA = TicketLifecycleSupport.worktreeFor(checkout,
                    TicketLifecycleSupport.TICKET_A);
            Path worktreeB = TicketLifecycleSupport.worktreeFor(checkout,
                    TicketLifecycleSupport.TICKET_B);
            Path homeA = TicketLifecycleSupport.homeOf(worktreeA);
            Path homeB = TicketLifecycleSupport.homeOf(worktreeB);

            boolean bothWorktreesExist = Files.isDirectory(worktreeA) && Files.isDirectory(worktreeB)
                    && Files.isRegularFile(homeA.resolve("home.runtime.json"))
                    && Files.isRegularFile(homeB.resolve("home.runtime.json"));
            boolean bothBranchesExist =
                    HomeSyncSupport.git(worktreeA, "rev-parse", "--abbrev-ref", "HEAD").trimmed()
                            .equals("feature/" + TicketLifecycleSupport.TICKET_A)
                    && HomeSyncSupport.git(worktreeB, "rev-parse", "--abbrev-ref", "HEAD").trimmed()
                            .equals("feature/" + TicketLifecycleSupport.TICKET_B);

            // --- distinct inodes, for the home root AND for a file in it -----
            Path sharedSkillA = TicketLifecycleSupport
                    .unitDir(homeA, TicketLifecycleSupport.SHARED).resolve("SKILL.md");
            Path sharedSkillB = TicketLifecycleSupport
                    .unitDir(homeB, TicketLifecycleSupport.SHARED).resolve("SKILL.md");
            Path sharedSkillProject = TicketLifecycleSupport
                    .unitDir(project, TicketLifecycleSupport.SHARED).resolve("SKILL.md");
            List<Long> rootInodes = List.of(
                    TicketLifecycleSupport.inode(homeA),
                    TicketLifecycleSupport.inode(homeB),
                    TicketLifecycleSupport.inode(project),
                    TicketLifecycleSupport.inode(Path.of(rootRaw)));
            boolean everyHomeRootIsItsOwnInode = allPositiveAndDistinct(rootInodes);
            List<Long> fileInodes = List.of(
                    TicketLifecycleSupport.inode(sharedSkillA),
                    TicketLifecycleSupport.inode(sharedSkillB),
                    TicketLifecycleSupport.inode(sharedSkillProject));
            boolean theEditedFileIsItsOwnInode = allPositiveAndDistinct(fileInodes);

            // --- nothing in either home names another home -------------------
            TicketLifecycleSupport.Scan aNamesProject =
                    TicketLifecycleSupport.filesNaming(homeA, projectRaw, 5);
            TicketLifecycleSupport.Scan aNamesRoot =
                    TicketLifecycleSupport.filesNaming(homeA, rootRaw, 5);
            TicketLifecycleSupport.Scan bNamesProject =
                    TicketLifecycleSupport.filesNaming(homeB, projectRaw, 5);
            TicketLifecycleSupport.Scan bNamesRoot =
                    TicketLifecycleSupport.filesNaming(homeB, rootRaw, 5);
            boolean noWorktreeHomeNamesAnother = aNamesProject.hits().isEmpty()
                    && aNamesRoot.hits().isEmpty() && bNamesProject.hits().isEmpty()
                    && bNamesRoot.hits().isEmpty();

            // The floor under that zero, and the proof the walk can find one.
            //
            // A home is a pure function of its root — nothing in a correct one
            // names ANY absolute home path, its own included (`home clone`
            // re-anchors every link and record). So "the walk found the home's
            // own path" is not available as a self-check: it is exactly what a
            // correct clone does not contain. Two things are asserted instead:
            // the walk read a non-trivial number of entries, and a REFERENCE
            // PLANTED in a decoy copy is found by the same function.
            boolean theWalkReadTheTree = aNamesProject.entriesRead() >= 20
                    && bNamesProject.entriesRead() >= 20;
            Path decoy = Path.of(checkoutRaw).getParent().resolve("reference-scan-decoy");
            Files.deleteIfExists(decoy.resolve("skills").resolve("linked"));
            Files.createDirectories(decoy.resolve("skills").resolve("planted"));
            Files.writeString(decoy.resolve("skills").resolve("planted").resolve("SKILL.md"),
                    "a file that names " + projectRaw + " the way a bad clone would\n");
            Files.createSymbolicLink(decoy.resolve("skills").resolve("linked"),
                    Path.of(projectRaw).resolve("skills"));
            TicketLifecycleSupport.Scan planted =
                    TicketLifecycleSupport.filesNaming(decoy, projectRaw, 5);
            boolean thePlantedReferenceIsFound = planted.hits().size() >= 2;

            // --- a runnable pinned CLI ---------------------------------------
            //
            // `--home <this shim's home>` is NOT decoration, and it was added
            // after HIS-9 (#226) merged. The probe runs the shim with
            // SKILL_MANAGER_HOME set to `ambient`, which is a DIFFERENT home
            // from the one the shim lives in -- and HIS-9's entrypoint now
            // refuses exactly that rather than silently editing one of the two:
            //
            //   skill-manager: refusing to run against a home you did not name.
            //     you named:  .../ticket-lifecycle/ambient
            //     this shim would have edited: .../proj-TICKET-A/.skill-manager
            //     Say which one you mean:  --home <...>
            //
            // So the node was asking a question the product no longer answers.
            // The refusal is right and the probe was wrong: "does this home's
            // pinned CLI run" has to NAME the home now, which is what the
            // refusal itself recommends. Same shape as the descent-record
            // finding one wave earlier -- a node encoding a contract a ticket
            // deliberately changed.
            List<ProcessRecord> pins = new ArrayList<>();
            boolean bothPinsRun = true;
            for (Path home : List.of(homeA, homeB)) {
                Path pin = home.resolve("bin").resolve("cli").resolve("skill-manager");
                if (!Files.isExecutable(pin)) {
                    bothPinsRun = false;
                    continue;
                }
                ProcessRecord probe = TicketLifecycleSupport.plain(ctx,
                        "pin-probe-" + home.getParent().getFileName(), null, ambient,
                        List.of(pin.toString(), "home", "close-out",
                                "--home", home.toString(), "--help"));
                pins.add(probe);
                if (probe.exitCode() != 0
                        || !HomeSyncSupport.log(ctx,
                                "pin-probe-" + home.getParent().getFileName()).contains("--into")) {
                    bothPinsRun = false;
                }
            }

            // --- and PRODUCTION'S OWN VERDICT on the same question -----------
            //
            // The walk above is a SECOND SPELLING of a rule production already
            // owns in HomeCloner.verifyRoots. That is this epic's signature
            // defect -- two readers of one rule -- and it bit here: HIS-10
            // (#227) made a correct clone record its DESCENT, which names the
            // homes above it on purpose, and production exempted that one file
            // from its own isolation rule. This walk did not follow, so it went
            // red the day HIS-10 merged and took 11 skipped nodes with it, and
            // nobody saw it for a wave because the graph was not in HIS-10's
            // selected set.
            //
            // Exempting the record in the walk fixes today. Asking PRODUCTION
            // the same question is what stops the two drifting apart again: if
            // a future change moves the rule once more, this assertion fails on
            // the day of that change rather than a wave later.
            boolean bothVerifyClean = true;
            for (Path home : List.of(homeA, homeB)) {
                Path pin = home.resolve("bin").resolve("cli").resolve("skill-manager");
                if (!Files.isExecutable(pin)) { bothVerifyClean = false; continue; }
                String label = "verify-" + home.getParent().getFileName();
                ProcessRecord verify = TicketLifecycleSupport.plain(ctx, label, null, ambient,
                        List.of(pin.toString(), "home", "verify", "--home", home.toString()));
                pins.add(verify);
                if (verify.exitCode() != 0) bothVerifyClean = false;
            }

            boolean pass = bothProvisioned && bothWorktreesExist && bothBranchesExist
                    && everyHomeRootIsItsOwnInode && theEditedFileIsItsOwnInode
                    && noWorktreeHomeNamesAnother && theWalkReadTheTree
                    && thePlantedReferenceIsFound && bothPinsRun && bothVerifyClean;

            NodeResult result = pass
                    ? NodeResult.pass("ticket.lifecycle.provisioned")
                    : NodeResult.fail("ticket.lifecycle.provisioned",
                            "exits=" + provisionA.exitCode() + "/" + provisionB.exitCode()
                                    + " worktrees=" + bothWorktreesExist
                                    + " branches=" + bothBranchesExist
                                    + " rootInodes=" + rootInodes
                                    + " fileInodes=" + fileInodes
                                    + " aNamesProject=" + aNamesProject + " aNamesRoot=" + aNamesRoot
                                    + " bNamesProject=" + bNamesProject + " bNamesRoot=" + bNamesRoot
                                    + " entriesRead=" + aNamesProject.entriesRead() + "/"
                                    + bNamesProject.entriesRead()
                                    + " plantedFound=" + planted.hits()
                                    + " pins=" + bothPinsRun
                                    + " verifyClean=" + bothVerifyClean);
            result = result.process(provisionA).process(provisionB);
            for (ProcessRecord p : pins) result = result.process(p);
            return result
                    .assertion("new_change_sh_provisioned_both_ticket_worktrees", bothProvisioned)
                    .assertion("each_worktree_exists_and_carries_its_own_home", bothWorktreesExist)
                    .assertion("each_worktree_is_on_its_own_feature_branch", bothBranchesExist)
                    .assertion("all_four_home_roots_are_distinct_inodes",
                            everyHomeRootIsItsOwnInode)
                    .assertion("a_file_the_agent_will_edit_is_a_distinct_inode_per_home",
                            theEditedFileIsItsOwnInode)
                    .assertion("no_path_in_a_worktree_home_resolves_back_to_the_homes_above_it",
                            noWorktreeHomeNamesAnother)
                    .assertion("the_reference_walk_read_the_tree_it_claims_to_have_read",
                            theWalkReadTheTree)
                    .assertion("the_reference_walk_finds_a_planted_reference_to_another_home",
                            thePlantedReferenceIsFound)
                    .assertion("each_worktree_home_has_a_pinned_cli_that_actually_runs", bothPinsRun)
                    .assertion("production_agrees_the_worktree_home_reaches_no_other_home",
                            bothVerifyClean)
                    .metric("entriesScannedPerHome", aNamesProject.entriesRead())
                    .metric("plantedReferencesFound", planted.hits().size())
                    .publish("worktreeA", worktreeA.toString())
                    .publish("worktreeB", worktreeB.toString())
                    .publish("homeA", homeA.toString())
                    .publish("homeB", homeB.toString());
        });
    }

    private static boolean allPositiveAndDistinct(List<Long> inodes) {
        for (int i = 0; i < inodes.size(); i++) {
            if (inodes.get(i) <= 0) return false;
            for (int j = i + 1; j < inodes.size(); j++) {
                if (inodes.get(i).equals(inodes.get(j))) return false;
            }
        }
        return true;
    }
}
