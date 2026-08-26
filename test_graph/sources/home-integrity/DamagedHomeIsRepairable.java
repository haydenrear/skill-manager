///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>DETECT, REPAIR, DETECT — three separate processes, in that order.</b>
 *
 * <p>HIS-13 / issue #159. {@code GOAL-no-destructive-recovery} clause 2.
 *
 * <h2>Why this is a graph node and not only a unit test</h2>
 *
 * <p>{@code DamagedHomeIsRepairableTest} drives {@code HomeRepair} and
 * {@code HomeCommand.RepairCmd} in-process, over twelve cases, and it is where
 * the coverage lives. This node exists for one thing the unit test cannot
 * express, and it is the thing DEF-067 is about.
 *
 * <h2>DEF-067 — an observer that repairs is no longer an observer</h2>
 *
 * <p>{@code HomeFixpointLaw} parses the remedy out of a refusal and RUNS it, so
 * it can silently repair the condition it was checking and report PASS. It can
 * mutate 24 graphs' homes mid-run, it destroys the evidence for telling "the
 * product is broken" from "a fixture left that there", and nothing in it says
 * so. This ticket ships a repairer, which makes that hazard first-class rather
 * than incidental.
 *
 * <p>So the proof that repair works is <b>three separate invocations of the
 * real CLI</b> — a detect that must go red, a repair, and a detect that must go
 * green — and never one call that does both. A node that ran
 * {@code home repair --fix} and read its own exit code would be exactly the
 * self-healing instrument DEF-067 names: the command would be its own witness.
 *
 * <p>And the separation is asserted, not assumed. Before the repair runs, this
 * node runs detection TWICE against the damaged home and asserts:
 *
 * <ul>
 *   <li>the verdict is red both times, and</li>
 *   <li>the home is <b>byte-identical</b> after those two runs — every file's
 *       content and every symlink's target, on both axes.</li>
 * </ul>
 *
 * <p>If the bare command repaired anything, the second detect would be green
 * and the snapshot would differ. Either one fails this node.
 *
 * <h2>The environment names a DIFFERENT agent directory, on purpose</h2>
 *
 * <p>{@code SmEnv} binds {@code CLAUDE_CONFIG_DIR} / {@code CODEX_HOME} /
 * {@code GEMINI_HOME} at the SANDBOX's agent directories, which are not the
 * subject home's. That is the two-axis decoy for free: a repair that took the
 * agent half of "this home" from the environment (HIS-14's defect, #145 /
 * DEF-029) would repair nothing here and write in the sandbox instead. This
 * node asserts the subject home's own {@code .claude} was repaired AND that the
 * directory the environment names was not touched.
 *
 * <h2>What this node deliberately does NOT cover</h2>
 *
 * <p>Two of the four damage shapes. {@code PRUNED_INHERITED_ENTRY} needs a
 * descent record plus an artifact ledger plus a live parent claim, and
 * {@code DANGLING_CLI_PIN} needs a second BUILD of the CLI to re-pin at —
 * neither is available in a graph run without inventing a fixture whose only
 * purpose is to satisfy this node. Both are covered, with a control each, in
 * the unit suite. Said out loud rather than left for a reader to infer from
 * what the node happens to assert: a node whose scope is narrower than its name
 * is the shape this epic keeps filing findings about.
 */
public class DamagedHomeIsRepairable {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.damaged.home.is.repairable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "repair", "his-13")
            .timeout("600s")
            .output("repairedHome", "string");

    private static final String UNIT = HomeIntegritySupport.UNIT;

    /** The wrapper this node plants, and re-points. */
    private static final String WRAPPER = "his13-wrapper";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
        String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
        if (homeStr == null || scratchStr == null) {
            return unproven("missing home.integrity.fixture context");
        }
        Path fixture = Path.of(homeStr);
        Path work = Path.of(scratchStr).resolve("his13");
        HomeIntegritySupport.deleteRecursively(work);

        // ROOT-SHAPED, because the root tier is the one that keeps taking the
        // damage and it is the tier the acceptance names. `<x>/.skill-manager`
        // beside `<x>/.claude` -- so the store's own parent is the home root
        // and the agent directories are derived from it structurally.
        Path subject = work.resolve("subject/.skill-manager");
        Path other = work.resolve("other/.skill-manager");
        Files.createDirectories(subject.getParent());
        Files.createDirectories(other.getParent());
        HomeIntegritySupport.copyTree(fixture, subject);
        HomeIntegritySupport.copyTree(fixture, other);

        // --- the damage -----------------------------------------------------
        //
        // Shape 1: the agent projection resolves into the OTHER home's store.
        // It RESOLVES -- that is the whole point, and it is why `home verify`
        // has never had anything to say about it.
        Path skills = Files.createDirectories(
                subject.getParent().resolve(".claude").resolve("skills"));
        Path projection = skills.resolve(UNIT);
        Files.deleteIfExists(projection);
        Files.createSymbolicLink(projection, other.resolve("skills").resolve(UNIT));

        // Shape 2: a generated REGULAR-FILE wrapper whose TEXT names a live
        // path in the other home. It runs, so nothing that tests executability
        // or link resolution sees anything.
        Path wrapper = subject.resolve("bin/cli").resolve(WRAPPER);
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "#!/usr/bin/env bash\ncat \""
                + other.resolve("skills").resolve(UNIT).resolve("SKILL.md") + "\"\n");
        wrapper.toFile().setExecutable(true);

        // The fixture's precondition, asserted rather than hoped for: both
        // damaged paths must actually RESOLVE, or this node is about dangling
        // links, which is a shape production already found.
        boolean damageResolves = Files.exists(projection)
                && Files.exists(other.resolve("skills").resolve(UNIT).resolve("SKILL.md"));

        List<String> otherBefore = snapshot(other);
        Path sandboxClaude = sandboxAgentDir(ctx);
        List<String> sandboxBefore = snapshot(sandboxClaude);

        // --- INVOCATION 1: detection, alone ---------------------------------
        List<String> beforeDetect = snapshot(subject.getParent());
        ProcessRecord detect1 = HomeIntegritySupport.sm(ctx, "his13-detect-1", subject,
                "home", "repair", "--home", subject.toString());

        // --- INVOCATION 2: detection AGAIN, still alone ---------------------
        //
        // DEF-067's control. A detector that repairs would go green here and
        // the snapshot would move.
        ProcessRecord detect2 = HomeIntegritySupport.sm(ctx, "his13-detect-2", subject,
                "home", "repair", "--home", subject.toString());
        List<String> afterDetect = snapshot(subject.getParent());

        String log1 = readLog(ctx.reportDir(), detect1);
        boolean detectionIsRed = detect1.exitCode() == 1 && detect2.exitCode() == 1;
        boolean detectionWroteNothing = beforeDetect.equals(afterDetect);
        boolean namesBothShapes = log1.contains("MISANCHORED_AGENT_LINK")
                && log1.contains("FOREIGN_PATH_IN_SHIM");
        boolean namesTheRepair = log1.contains("repair:")
                && log1.contains("--fix");

        // --- INVOCATION 2b/2c: THE OTHER READER, on the same bytes ----------
        //
        // HIS-21 / DEF-104. This node planted a regular-file wrapper naming
        // another home at shape 2 and asked ONE reader about it. The other
        // reader, on the operator's real root home in the same minute, called
        // that home clean:
        //
        //   home verify --home ~/.skill-manager -> exit 0, "no path in it
        //                                          reaches any other home"
        //   home repair --home ~/.skill-manager -> 5 FOREIGN_PATH_IN_SHIM
        //
        // The fixture for it was already here; nobody had ever run
        // `home verify` over it. The same subject now goes to both readers
        // before the repair and again after, and the answers must agree.
        //
        // THE CONTROL is `verifyOther`: the undamaged copy, same command, must
        // NOT name the kind. Without it, "verify names FOREIGN_PATH_IN_SHIM" is
        // satisfied by a check that names it for every home — and that check
        // would redden HomeFixpointLaw across 24 graphs.
        ProcessRecord verifyBefore = HomeIntegritySupport.sm(ctx, "his21-verify-before", subject,
                "home", "verify", "--home", subject.toString());
        ProcessRecord verifyOther = HomeIntegritySupport.sm(ctx, "his21-verify-other", other,
                "home", "verify", "--home", other.toString());

        // --- INVOCATION 3: the repair ---------------------------------------
        ProcessRecord repair = HomeIntegritySupport.sm(ctx, "his13-repair", subject,
                "home", "repair", "--home", subject.toString(), "--fix");
        List<String> afterRepair = snapshot(subject.getParent());

        // --- INVOCATION 4: detection, alone, afterwards ---------------------
        //
        // THE VERDICT. Not the repair's exit code: a repairer that reports its
        // own success has asserted nothing (#142).
        ProcessRecord detect3 = HomeIntegritySupport.sm(ctx, "his13-detect-3", subject,
                "home", "repair", "--home", subject.toString());

        // --- INVOCATION 5: repair again -------------------------------------
        ProcessRecord repairAgain = HomeIntegritySupport.sm(ctx, "his13-repair-2", subject,
                "home", "repair", "--home", subject.toString(), "--fix");
        List<String> afterSecondRepair = snapshot(subject.getParent());

        // HIS-21 / DEF-104: the second reader, after the repair.
        ProcessRecord verifyAfter = HomeIntegritySupport.sm(ctx, "his21-verify-after", subject,
                "home", "verify", "--home", subject.toString());

        String log3 = readLog(ctx.reportDir(), detect3);
        boolean repairMadeDetectionClean = detect3.exitCode() == 0;

        // Keyed on the SUBJECT as well as the kind. `log.contains("FOREIGN_
        // PATH_IN_SHIM")` alone would be satisfied by a finding about any file
        // in the home, including one a later ticket plants for another reason,
        // and the claim here is about ONE wrapper.
        String verifyBeforeLog = readLog(ctx.reportDir(), verifyBefore);
        String verifyAfterLog = readLog(ctx.reportDir(), verifyAfter);
        String verifyOtherLog = readLog(ctx.reportDir(), verifyOther);
        String theWrapper = "FOREIGN_PATH_IN_SHIM bin/cli/" + WRAPPER;
        boolean verifySawTheWrapper = verifyBeforeLog.contains(theWrapper)
                && verifyBefore.exitCode() == 1;
        boolean verifyAgreesAfterRepair = !verifyAfterLog.contains("FOREIGN_PATH_IN_SHIM");
        boolean verifyIsNotAlwaysRed = !verifyOtherLog.contains("FOREIGN_PATH_IN_SHIM");
        boolean repairIsIdempotent = repairAgain.exitCode() == 0
                && afterRepair.equals(afterSecondRepair);
        boolean projectionRepaired = Files.isSymbolicLink(projection)
                && Files.readSymbolicLink(projection)
                        .equals(subject.resolve("skills").resolve(UNIT));
        boolean wrapperRepaired = Files.readString(wrapper, StandardCharsets.UTF_8)
                .contains(subject.toString());
        boolean otherHomeUntouched = otherBefore.equals(snapshot(other));
        // ASSERTED, not assumed. Review of PR #244: with `claudeHome` absent
        // from the fixture context this resolves to null, `snapshot(null)`
        // returns an empty list, and "untouched" is then two empty lists
        // comparing equal -- a green assertion over no subject at all, which is
        // mechanism B in the control for the two-axis defect.
        boolean sandboxIsReal = sandboxClaude != null
                && Files.isDirectory(sandboxClaude)
                && !sandboxBefore.isEmpty();
        boolean sandboxUntouched = sandboxBefore.equals(snapshot(sandboxClaude));

        List<String> failures = new ArrayList<>();
        if (!damageResolves) {
            failures.add("the planted damage does not RESOLVE, so this node is about dangling "
                    + "links rather than about mis-anchored ones — the fixture needs fixing, "
                    + "not the product");
        }
        if (!detectionIsRed) {
            failures.add("bare `home repair` did not refuse a damaged home: detect1="
                    + detect1.exitCode() + " detect2=" + detect2.exitCode());
        }
        if (!detectionWroteNothing) {
            failures.add("DETECTION REPAIRED SOMETHING — two bare runs changed the home. "
                    + "That is DEF-067's hazard in the command this ticket ships");
        }
        if (!repairMadeDetectionClean) {
            failures.add("detection re-run after the repair still exits " + detect3.exitCode()
                    + ": " + firstLines(log3));
        }
        if (!repairIsIdempotent) {
            failures.add("the second repair was not a no-op: exit " + repairAgain.exitCode()
                    + ", bytes " + (afterRepair.equals(afterSecondRepair) ? "same" : "CHANGED"));
        }
        if (!otherHomeUntouched) {
            failures.add("the OTHER home changed — a repair wrote outside the home it was given");
        }
        if (!sandboxIsReal) {
            failures.add("the sandbox agent directory named by env.prepared/claudeHome is "
                    + "absent or empty (" + sandboxClaude + "), so the decoy assertion below "
                    + "has no subject and proves nothing");
        }
        if (!sandboxUntouched) {
            failures.add("the agent directory the ENVIRONMENT names changed — the repair took "
                    + "the agent axis from CLAUDE_CONFIG_DIR instead of from the home");
        }
        if (!verifyIsNotAlwaysRed) {
            failures.add("DEF-104's CONTROL FAILED: `home verify` names FOREIGN_PATH_IN_SHIM on "
                    + "the UNDAMAGED copy, so the finding on the damaged one says nothing. "
                    + "This is the state that would redden HomeFixpointLaw across 24 graphs");
        }
        if (!verifySawTheWrapper) {
            failures.add("DEF-104: `home repair` names " + theWrapper + " and `home verify` "
                    + "exits " + verifyBefore.exitCode() + " without it — the two readers "
                    + "disagree about one file in one home, which is the defect");
        }
        if (!verifyAgreesAfterRepair) {
            failures.add("DEF-104: after the repair `home repair` is clean and `home verify` "
                    + "still names a foreign path in a shim — the readers disagree in the "
                    + "other direction");
        }

        NodeResult result = failures.isEmpty()
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), String.join(" | ", failures));

        return result
                .process(detect1).process(detect2).process(repair)
                .process(detect3).process(repairAgain)
                .process(verifyBefore).process(verifyOther).process(verifyAfter)
                .assertion("the_planted_damage_resolves_and_is_therefore_the_right_shape",
                        damageResolves)
                .assertion("bare_home_repair_refuses_a_damaged_home", detectionIsRed)
                .assertion("bare_home_repair_names_each_damage_shape", namesBothShapes)
                .assertion("every_finding_names_the_repair_for_it", namesTheRepair)
                .assertion("DETECTION_ALONE_REPAIRS_NOTHING", detectionWroteNothing)
                .assertion("a_separate_detection_run_after_the_repair_is_clean",
                        repairMadeDetectionClean)
                .assertion("the_projection_points_at_this_homes_own_store", projectionRepaired)
                .assertion("the_wrapper_runs_this_homes_own_tree", wrapperRepaired)
                .assertion("running_the_repair_twice_changes_nothing_the_second_time",
                        repairIsIdempotent)
                .assertion("the_other_home_is_byte_identical_throughout", otherHomeUntouched)
                .assertion("the_decoy_agent_dir_exists_and_has_content_to_be_changed",
                        sandboxIsReal)
                .assertion("the_agent_dir_the_environment_names_is_untouched", sandboxUntouched)
                .assertion("DEF104_home_verify_names_the_same_wrapper_home_repair_does",
                        verifySawTheWrapper)
                .assertion("DEF104_home_verify_is_clean_once_home_repair_is", verifyAgreesAfterRepair)
                .assertion("DEF104_control_home_verify_is_silent_about_the_undamaged_copy",
                        verifyIsNotAlwaysRed)
                .log("detect=" + detect1.exitCode() + "," + detect2.exitCode()
                        + " repair=" + repair.exitCode()
                        + " detect-after=" + detect3.exitCode()
                        + " repair-again=" + repairAgain.exitCode()
                        + " | subject entries snapshotted: " + afterRepair.size())
                .metric("repair.detectRunsBeforeRepair", 2)
                .metric("repair.findingsAtFirstDetect", countFindings(log1))
                .metric("repair.findingsAfterRepair", countFindings(log3))
                .publish("repairedHome", subject.toString());
    }

    /**
     * The agent directory {@code SmEnv} points the CHILD PROCESS at.
     *
     * <p>Not the subject's. That is the decoy: it is what a repair reading the
     * environment for the agent half of "this home" would write.
     */
    private static Path sandboxAgentDir(NodeContext ctx) {
        String claude = ctx.get("env.prepared", "claudeHome").orElse(null);
        return claude == null ? null : Path.of(claude);
    }

    /** {@code ProcessRecord.logPath()} is relative to the run's report dir. */
    private static String readLog(Path reportDir, ProcessRecord proc) {
        try {
            String log = proc.logPath();
            if (log == null || log.isBlank()) return "";
            Path p = Path.of(log);
            if (!p.isAbsolute() && reportDir != null) p = reportDir.resolve(p);
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Content and link targets under {@code root}, sorted. Null root: empty. */
    private static List<String> snapshot(Path root) {
        List<String> out = new ArrayList<>();
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return out;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isSymbolicLink(p)) {
                    out.add(p + " -> " + Files.readSymbolicLink(p));
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    // Content, not just presence. A repointed symlink and a
                    // rewritten wrapper are both invisible to a name-only walk,
                    // and both are exactly what this node is about.
                    out.add(p + " :: " + hash(p));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            out.add(root + " !! " + unreadable);
        }
        return out;
    }

    /**
     * A real digest, not a 32-bit hash.
     *
     * <p>Review of PR #244. This was {@code Arrays.hashCode}, which is 32 bits
     * and not collision-resistant, used to decide the node's strongest claim —
     * that a detection run changed NOTHING. A hash collision there reads as
     * "byte-identical" and greens the DEF-067 control. The cost of SHA-256 over
     * a home's worth of small files is nothing next to five CLI forks.
     */
    private static String hash(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.append(':').append(bytes.length).toString();
        } catch (IOException | java.security.NoSuchAlgorithmException unreadable) {
            return "unreadable";
        }
    }

    private static int countFindings(String stderr) {
        int count = 0;
        for (String line : stderr.split("\n")) {
            if (line.contains("MISANCHORED_AGENT_LINK") || line.contains("FOREIGN_PATH_IN_SHIM")
                    || line.contains("PRUNED_INHERITED_ENTRY")
                    || line.contains("DANGLING_CLI_PIN")) {
                if (line.contains("repair:")) continue;
                count++;
            }
        }
        return count;
    }

    private static String firstLines(String text) {
        String[] lines = text.split("\n");
        return String.join(" / ", java.util.Arrays.copyOfRange(lines, 0, Math.min(4, lines.length)));
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("the_planted_damage_resolves_and_is_therefore_the_right_shape", false)
                .assertion("bare_home_repair_refuses_a_damaged_home", false)
                .assertion("bare_home_repair_names_each_damage_shape", false)
                .assertion("every_finding_names_the_repair_for_it", false)
                .assertion("DETECTION_ALONE_REPAIRS_NOTHING", false)
                .assertion("a_separate_detection_run_after_the_repair_is_clean", false)
                .assertion("the_projection_points_at_this_homes_own_store", false)
                .assertion("the_wrapper_runs_this_homes_own_tree", false)
                .assertion("running_the_repair_twice_changes_nothing_the_second_time", false)
                .assertion("the_other_home_is_byte_identical_throughout", false)
                .assertion("the_decoy_agent_dir_exists_and_has_content_to_be_changed", false)
                .assertion("the_agent_dir_the_environment_names_is_untouched", false);
    }
}
