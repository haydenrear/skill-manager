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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FOUR READERS, ONE ANSWER, on ONE cloned home, with no {@code --against}.
 *
 * <h2>The measurement this node exists to keep closed</h2>
 *
 * <p>HIS-10 / issue #227, measured on epic tip {@code 23e35c7} by cloning this
 * repository's own project home:
 *
 * <pre>
 *   home clone                  clean — "no path in it reaches another home"
 *   home verify --home &lt;clone&gt;   exit 1 — 5x FOREIGN_HOME on bin/cli/{computeq,
 *                               helm-deploy,monitoring,tla-spec-dev,tlc2}
 *   home verify … --against …   exit 0 — "5 sanctioned parent-store shim(s)"
 *   sync                        PRUNED all five ("not this home's parent
 *                               store"), then spent ~90s re-provisioning five
 *                               toolchains nobody had changed
 * </pre>
 *
 * <p>Four readers, three answers, one home, the same five paths. The only thing
 * that made the sanction visible was an operator typing {@code --against},
 * which is a flag and not a fact. The expensive answer is the last one, and it
 * contradicts the contract the owner stated for this work: <i>"we clone, then
 * we say, here are the artifacts for these skills on the path for you, and here
 * is how you rebuild them if you make a change. Why would we build them without
 * the agent changing?"</i>
 *
 * <h2>Why this is a graph node and not only a unit test</h2>
 *
 * <p>The unit test ({@code ClonedHomeDescentTest}) drives {@code HomeCloner},
 * {@code HomeCommand.VerifyCmd} and {@code CliShimPruner} in-process. It cannot
 * observe the fourth reader as an operator meets it: {@code sync} is a whole
 * plan — prune, presence gate, install pass, recorder — and the defect was that
 * the prune deleted an artifact the presence gate would then have accepted. So
 * this node runs the REAL {@code sync} against a REAL clone and asserts the
 * inherited link is still there afterwards.
 *
 * <h2>THREE TIERS, because a two-tier fixture cannot contain the defect</h2>
 *
 * <p>The same trap {@code ParentHomeSurvivesAChildBuild} records in its own
 * class comment, and it is worth repeating here because the shape is identical.
 * Cloning the fixture home directly produces a copy whose {@code bin/cli} entry
 * is a REAL FILE — the fixture installed the tool into its own home — so there
 * is no inherited symlink, nothing is foreign, and every reader trivially
 * agrees. The node would be green against a completely broken product.
 *
 * <p>The real topology is {@code root -> project -> worktree}: the tool lives in
 * ONE home, the middle home holds an ABSOLUTE SYMLINK at it plus a
 * {@code child-homes/} claim from the parent, and the leaf is a CLONE of the
 * middle that inherits the link. Only then is the leaf a grandchild, which is
 * the state nothing durable recorded.
 *
 * <h2>The control that makes the rest mean something</h2>
 *
 * <p>Assertion {@code removing_the_record_makes_the_readers_disagree} deletes
 * {@code home.provenance.json} from the leaf and re-runs the bare
 * {@code home verify}, which must go back to {@code FOREIGN_HOME}. Without it,
 * "all four agree" would also pass on a build where the agreement came from
 * somewhere else entirely — and two assertions in this epic have already
 * shipped green without their fix.
 */
public class ReadersAgreeAboutOneClone {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.readers.agree.about.one.clone")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "clone", "provenance", "his-10")
            .timeout("900s");

    /** The unit the fixture installs, and the CLI dep it declares. */
    private static final String UNIT = HomeIntegritySupport.UNIT;
    private static final String TOOL = HomeIntegritySupport.TOOL;

    /** The record a clone writes about its own descent. */
    private static final String PROVENANCE = "home.provenance.json";

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
        try {
            return run(ctx, homeStr, ctx.get("home.integrity.fixture", "scratchRoot").orElse(null));
        } finally {
            // Every exit path, including the early fixture refusals below.
            removeClaim(homeStr);
        }
    }

    /** Drop the child-home record this node plants in the shared fixture home. */
    private static void removeClaim(String homeStr) {
        if (homeStr == null) return;
        Path claim = Path.of(homeStr).resolve("child-homes/his10-middle");
        try {
            Files.deleteIfExists(claim.resolve("child-home.json"));
            Files.deleteIfExists(claim);
        } catch (IOException cannotClean) {
            // Reported, never fatal: a leftover record is a fixture smell, and
            // failing the assertion over it would report the wrong defect.
            System.err.println("could not remove the fixture claim at " + claim
                    + ": " + cannotClean.getMessage());
        }
    }

    private static NodeResult run(NodeContext ctx, String homeStr, String scratchStr)
            throws IOException {
        if (homeStr == null || scratchStr == null) {
            return NodeResult.fail(SPEC.id(), "missing home.integrity.fixture context");
        }
        Path parent = Path.of(homeStr);
        Path scratch = Path.of(scratchStr).resolve("his10");
        Files.createDirectories(scratch);

        Path parentShim = parent.resolve("bin/cli").resolve(TOOL);
        if (!Files.isRegularFile(parentShim)) {
            return NodeResult.fail(SPEC.id(),
                    "fixture did not produce " + parentShim + " — with no artifact in the root "
                            + "tier there is no inherited toolchain for the leaf to keep");
        }

        // --- the middle tier: a child home that SHARES the parent's tool ------
        Path middle = scratch.resolve("middle-home/.skill-manager");
        ProcessRecord mk = HomeIntegritySupport.sm(ctx, "his10-clone-middle", parent,
                "home", "clone", "--from", parent.toString(), "--to", middle.toString());
        if (mk.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "could not build the middle tier: home clone exited " + mk.exitCode());
        }
        Path middleShim = middle.resolve("bin/cli").resolve(TOOL);
        Files.createDirectories(middleShim.getParent());
        Files.deleteIfExists(middleShim);
        Files.createSymbolicLink(middleShim, parentShim);

        // And the parent CLAIMS it. Shape alone never sanctions — without this
        // record the middle tier is a leak and the leaf inherits a leak, which
        // is the laundering case ChildHomeShimIsolationTest pins.
        // REMOVED AGAIN at the end of this node, in every exit path. It is a
        // record in the SHARED fixture home that this node alone needs, and a
        // home-integrity graph whose own nodes leave claims lying around in the
        // subject is a graph that will eventually assert about its own litter --
        // HomeFixpointLaw runs last, over this home.
        Path claim = parent.resolve("child-homes/his10-middle");
        Files.createDirectories(claim);
        Files.writeString(claim.resolve("child-home.json"), """
                {
                  "id" : "his10-middle",
                  "parentHome" : "%s",
                  "childHome" : "%s",
                  "units" : [ ],
                  "createdAt" : "2026-01-01T00:00:00Z"
                }
                """.formatted(parent, middle));

        // --- READER 1: the clone that makes the leaf -------------------------
        Path leaf = scratch.resolve("leaf-home/.skill-manager");
        ProcessRecord clone = HomeIntegritySupport.sm(ctx, "his10-clone-leaf", parent,
                "home", "clone", "--from", middle.toString(), "--to", leaf.toString());

        Path leafShim = leaf.resolve("bin/cli").resolve(TOOL);
        if (!Files.isSymbolicLink(leafShim)) {
            // Say so rather than passing: with no inherited link the four
            // readers below are agreeing about nothing.
            return NodeResult.fail(SPEC.id(),
                    "the leaf did not inherit " + TOOL + " as a symlink (clone exited "
                            + clone.exitCode() + "), so this node cannot observe the "
                            + "disagreement it exists for; the fixture needs fixing, not the "
                            + "product")
                    .process(mk).process(clone);
        }

        List<String> failures = new ArrayList<>();

        // --- READER 2: home verify, with NO --against ------------------------
        ProcessRecord bare = HomeIntegritySupport.sm(ctx, "his10-verify-bare", leaf,
                "home", "verify", "--home", leaf.toString());

        // --- READER 3: the same command WITH the flag ------------------------
        ProcessRecord against = HomeIntegritySupport.sm(ctx, "his10-verify-against", leaf,
                "home", "verify", "--home", leaf.toString(), "--against", middle.toString());

        // --- READER 4: the first sync in the clone ---------------------------
        Path linkTargetBefore = Files.readSymbolicLink(leafShim);
        ProcessRecord sync = HomeIntegritySupport.sm(ctx, "his10-sync-leaf", leaf,
                "sync", UNIT, "--yes");
        boolean stillALink = Files.isSymbolicLink(leafShim);
        boolean stillInherited = stillALink
                && Files.readSymbolicLink(leafShim).equals(linkTargetBefore);
        boolean stillPresent = Files.exists(leafShim, LinkOption.NOFOLLOW_LINKS);

        // FOUR VERDICTS, COUNTED — not one boolean.
        //
        // The goal's metric is "distinct verdicts returned by the readers over
        // one fixed scenario; the target is one verdict per scenario", so the
        // node has to be able to REPORT two or three readers agreeing. An
        // earlier version published `readers.agreeing = allAgree ? 4 : 0`,
        // which can never say 2 — and 2 is exactly what the epic-tip baseline
        // scored (clone and --against sanctioned it; bare verify and the pruner
        // did not). A metric that cannot express the baseline cannot show
        // progress towards the target either.
        boolean[] verdicts = {
                clone.exitCode() == 0,      // the clone
                bare.exitCode() == 0,       // home verify, no flag
                against.exitCode() == 0,    // home verify --against
                stillInherited,             // sync's shim pruner
        };
        int sanctioned = 0;
        for (boolean v : verdicts) if (v) sanctioned++;
        int agreeingWithTheClone = verdicts[0] ? sanctioned : verdicts.length - sanctioned;
        int distinctVerdicts = (sanctioned == 0 || sanctioned == verdicts.length) ? 1 : 2;

        boolean allAgree = distinctVerdicts == 1 && verdicts[0];
        if (!allAgree) {
            failures.add("readers disagreed: clone=" + clone.exitCode()
                    + " verify=" + bare.exitCode()
                    + " verify--against=" + against.exitCode()
                    + " shim-after-sync=" + (stillInherited ? "inherited"
                            : stillPresent ? "replaced locally" : "DELETED")
                    + " (sync exited " + sync.exitCode() + ")");
        }

        // --- THE CONTROL: remove the evidence, watch them split up -----------
        //
        // Restored afterwards so the assertion cannot damage the subject the
        // rest of this graph's downstream nodes and the fixpoint law inspect.
        Path record = leaf.resolve(PROVENANCE);
        boolean recordWritten = Files.isRegularFile(record);
        boolean disagreeWithoutIt = false;
        String controlEvidence;
        if (!recordWritten) {
            controlEvidence = "the clone wrote no " + PROVENANCE + " at all";
            failures.add("no descent record in the leaf — the sanction has nothing durable "
                    + "behind it and the agreement above is coming from somewhere else");
        } else {
            byte[] saved = Files.readAllBytes(record);
            Files.delete(record);
            ProcessRecord without = HomeIntegritySupport.sm(ctx, "his10-verify-no-record", leaf,
                    "home", "verify", "--home", leaf.toString());
            Files.write(record, saved);
            disagreeWithoutIt = without.exitCode() != 0;
            controlEvidence = "without " + PROVENANCE + ": home verify exited "
                    + without.exitCode();
            if (!disagreeWithoutIt) {
                failures.add("removing " + PROVENANCE + " changed nothing — the readers agree "
                        + "for some other reason and this node is vacuous");
            }
        }

        NodeResult result = failures.isEmpty()
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), String.join(" | ", failures));

        return result
                .process(mk).process(clone).process(bare).process(against).process(sync)
                .assertion("all_four_readers_agree_about_one_cloned_home", allAgree)
                .assertion("the_verdict_needs_no_against_flag",
                        bare.exitCode() == against.exitCode())
                .assertion("the_first_sync_keeps_the_inherited_toolchain", stillInherited)
                .assertion("the_clone_recorded_its_descent", recordWritten)
                .assertion("removing_the_record_makes_the_readers_disagree", disagreeWithoutIt)
                .log("clone=" + clone.exitCode() + " verify=" + bare.exitCode()
                        + " verify--against=" + against.exitCode()
                        + " sync=" + sync.exitCode()
                        + " shim=" + (stillInherited ? "inherited link kept"
                                : stillPresent ? "replaced by a local artifact" : "deleted")
                        + " | " + controlEvidence)
                .metric("readers.agreeing", agreeingWithTheClone)
                .metric("readers.distinctVerdicts", distinctVerdicts)
                .metric("readers.sanctioning", sanctioned);
    }
}
