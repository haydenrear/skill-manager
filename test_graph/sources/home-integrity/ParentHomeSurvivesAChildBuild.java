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
 * A build inside a child home does not write into its parent.
 *
 * <h2>The damage this pins, measured on the operator's own machine</h2>
 *
 * <p>HIS-7 / issue #223. A cloned home inherits {@code bin/cli/<name>} as an
 * absolute symlink into its parent — deliberately, because those are the
 * parent's artifacts and the agent needs them on PATH. Producers then write
 * with {@code cat > "$SKILL_MANAGER_BIN_DIR/<name>"}, and <b>{@code cat >}
 * follows a symlink</b>. So {@code skill-manager build} inside the clone never
 * wrote the clone's shim; it overwrote the PARENT's, with a wrapper carrying
 * the CLONE's absolute paths:
 *
 * <pre>
 *   ~/.skill-manager/bin/cli/computeq
 *     exec "/private/tmp/.../clone-probe/.skill-manager/cache/.../venv/bin/computeq"
 * </pre>
 *
 * <p>That is a child home mutating its parent's bytes — the one thing the
 * epic's baseline rule exists to prevent — and it reached the operator's real
 * root home twice before anybody noticed, because the command reported
 * {@code ✓ installed} and {@code built} either way. One shim survived only
 * because its wrapper happens to be {@code BIN_DIR}-relative. Luck, not design.
 *
 * <h2>Why this is a graph node and not only a unit test</h2>
 *
 * <p>The unit tests for HIS-7 assert the two halves separately —
 * {@code CliArtifact} stops calling a foreign path "provided", and
 * {@code HomeCloner} lets a copy inherit its source's sanction. Neither runs a
 * real producer. The defect was in what a REAL {@code skill-script} installer
 * does to a REAL symlink when a REAL clone builds, and no assertion over a
 * hand-built fixture would have caught it: the fixture would have had to
 * contain the very {@code cat >} that was the bug.
 *
 * <p>So this drives the product end to end — install into a parent, clone it,
 * build inside the clone — and asserts the property an operator cares about:
 * <b>the parent's bytes are exactly as they were.</b>
 *
 * <h2>What it asserts, and why each one</h2>
 *
 * <ol>
 *   <li><b>The parent's shim is byte-identical after the child builds.</b> The
 *       direct statement of the defect.</li>
 *   <li><b>The child ends up holding its own artifact</b>, not a link into the
 *       parent. Without this, "the parent was not written" would also pass on a
 *       build that did nothing at all — which is exactly the state the first
 *       half of the fix was needed for, and the flattering way to be green.</li>
 *   <li><b>The child's artifact names the CHILD's paths.</b> A copy that ran
 *       and produced a wrapper pointing back into the parent's tree would
 *       satisfy both assertions above and still be wrong.</li>
 * </ol>
 */
public class ParentHomeSurvivesAChildBuild {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.parent.survives.child.build")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "artifacts", "his-7")
            .timeout("900s");

    /** The unit the fixture installs with a skill-script CLI dependency. */
    private static final String TOOL = "hi-tool";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException | InterruptedException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException, InterruptedException {
        String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
        String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
        if (homeStr == null || scratchStr == null) {
            return NodeResult.fail(SPEC.id(), "missing home.integrity.fixture context");
        }
        Path parent = Path.of(homeStr);
        Path scratch = Path.of(scratchStr);

        Path parentShim = parent.resolve("bin/cli").resolve(TOOL);
        if (!Files.isRegularFile(parentShim)) {
            return NodeResult.fail(SPEC.id(),
                    "fixture did not produce " + parentShim + " — this node proves nothing "
                            + "about a child build if the parent holds no artifact to damage");
        }
        String parentBefore = Files.readString(parentShim);

        // THREE TIERS, and this is the whole reason the node exists.
        //
        // A first version of this cloned the fixture home directly and asserted
        // the same things. IT PASSED WITH THE FIX REMOVED. The fixture installs
        // hi-tool into its own bin/cli, so that shim is a REAL FILE; the clone
        // copies a real file, there is no inherited symlink, and `cat >` has
        // nothing to follow. The node tested a shape the defect cannot occur in.
        //
        // The real topology is root -> project -> worktree: the tool lives in
        // ONE home, the middle home holds an ABSOLUTE SYMLINK at it, and the
        // worktree clones that middle home and inherits the link. Only then can
        // a producer in the leaf follow it into the root.
        Path middle = scratch.resolve("middle-home/.skill-manager");
        ProcessRecord mk = HomeIntegritySupport.sm(ctx, "clone-middle", parent,
                "home", "clone", "--from", parent.toString(), "--to", middle.toString());
        if (mk.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(), "could not build the middle tier: home clone exited "
                    + mk.exitCode());
        }
        // Make the middle tier hold an absolute link at the tool, which is what
        // a child home that shares its parent's toolchain actually holds.
        Path middleShim = middle.resolve("bin/cli").resolve(TOOL);
        Files.createDirectories(middleShim.getParent());
        Files.deleteIfExists(middleShim);
        Files.createSymbolicLink(middleShim, parentShim);

        // And the parent CLAIMS it, which is what makes that link a sanctioned
        // parent-store mirror rather than a leak. Without the claim `home clone`
        // refuses the middle tier outright — measured, exit 1 — and rightly so:
        // shape alone never sanctions. A real project home carries exactly this
        // record, written by `project resolve`.
        Path claim = parent.resolve("child-homes/leaf-fixture");
        Files.createDirectories(claim);
        Files.writeString(claim.resolve("child-home.json"), """
                {
                  "id" : "leaf-fixture",
                  "parentHome" : "%s",
                  "childHome" : "%s",
                  "units" : [ ],
                  "createdAt" : "2026-01-01T00:00:00Z"
                }
                """.formatted(parent, middle));

        // The leaf: a clone of the middle tier, inheriting that link verbatim.
        Path child = scratch.resolve("leaf-home/.skill-manager");
        ProcessRecord clone = HomeIntegritySupport.sm(ctx, "clone-leaf", parent,
                "home", "clone", "--from", middle.toString(), "--to", child.toString());
        if (clone.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "home clone of the middle tier exited " + clone.exitCode()
                            + "; a home that cannot be cloned cannot host a ticket, which is the "
                            + "other half of #223");
        }

        Path childShim = child.resolve("bin/cli").resolve(TOOL);
        boolean inherited = Files.isSymbolicLink(childShim);
        if (!inherited) {
            // Say so rather than passing: without the inherited link the rest of
            // this node is asserting nothing, which is exactly how its first
            // version was green against a broken product.
            return NodeResult.fail(SPEC.id(),
                    "the leaf home did not inherit " + TOOL + " as a symlink, so this node "
                            + "cannot observe a write-through; the fixture, not the product, "
                            + "needs fixing");
        }

        // THE ACT: build inside the LEAF.
        ProcessRecord build = HomeIntegritySupport.sm(ctx, "build-in-leaf", child,
                "build", "--force", "cli-shim:skill-script/" + TOOL);

        List<String> failures = new ArrayList<>();

        // 1. The parent is untouched. The defect, stated directly.
        String parentAfter = Files.exists(parentShim) ? Files.readString(parentShim) : null;
        if (parentAfter == null) {
            failures.add("the child's build DELETED the parent's " + TOOL + " shim");
        } else if (!parentBefore.equals(parentAfter)) {
            failures.add("the child's build REWROTE the parent's " + TOOL + " shim — a child "
                    + "home mutated its parent's bytes, which is what the baseline rule exists "
                    + "to prevent");
        }

        if (build.exitCode() != 0) {
            // Reported, not fatal on its own: a producer that cannot run here
            // is a fixture/environment fact, and the parent-safety assertion
            // above is still meaningful. But it must not read as success.
            failures.add("build in the child exited " + build.exitCode()
                    + " (inherited-as-symlink=" + inherited + ")");
        } else {
            // 2. The child holds its OWN artifact now. Without this, a build
            //    that did nothing would pass assertion 1.
            if (Files.isSymbolicLink(childShim)) {
                failures.add("after building, the child's " + TOOL + " is still a symlink -> "
                        + Files.readSymbolicLink(childShim) + "; the producer reported success "
                        + "and replaced nothing");
            } else if (!Files.isRegularFile(childShim, LinkOption.NOFOLLOW_LINKS)) {
                failures.add("after building, the child holds no " + TOOL + " artifact at all");
            } else {
                // 3. And it names the CHILD's paths, not the parent's.
                String childBody = Files.readString(childShim);
                if (childBody.contains(parent.toString())) {
                    failures.add("the child's rebuilt " + TOOL + " still points into the parent "
                            + "home at " + parent + "; it ran, and produced the wrong home's "
                            + "wrapper");
                }
            }
        }

        return failures.isEmpty()
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), String.join(" | ", failures));
    }
}
