///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A declared CLI dependency is satisfied, and the home can say <em>how</em>.
 *
 * <h2>This node carries a PINNED DEFECT, on purpose. Read this before touching it.</h2>
 *
 * <p>The invariant has two conjuncts and they have different truth values
 * today. The argument is on
 * {@link HomeIntegrity#declaredCliIsSatisfiedAndAttributed}; the outcome is:
 *
 * <ul>
 *   <li><b>satisfied</b> — holds, over all <b>22 distinct {@code on_path}
 *       binaries</b> the operator's project home declares (across 27
 *       declaration rows; three units declare {@code brew:gh}, so the two
 *       denominators differ and an earlier version of this comment slid between
 *       them). Every one either has a shim here or is on the system PATH.</li>
 *   <li><b>attributed</b> — <b>does not hold, and is a known open defect.</b>
 *       Nothing in {@code cli-lock.toml} distinguishes "this home built a shim"
 *       from "{@code CliPresence.alreadyProvided} found it on the machine". 11
 *       rows in the operator's project home are in the second state; ARTI-06
 *       measured them, offered two candidate fixes — record the output at
 *       {@code Scope.EXTERNAL}, or stop recording a {@code binary} the install
 *       did not produce — and recorded that choosing between them is the
 *       owner's call, not a ticket's. It is a design change, and <b>#122</b>
 *       (ARTI-20) owns it — <b>not</b> #120. #120 (ARTI-17) is scoped to "the
 *       producers that are NOT CLI installers — marketplace, harness, MCP", and
 *       {@code dd5039b}'s own commit body on this branch says the
 *       brew/npm/pip/tar rows "are ARTI-20's (#122) to move". An earlier version
 *       of this node pinned #120; a review caught it.</li>
 * </ul>
 *
 * <p>So this node asserts the true conjunct positively, and <b>pins the false
 * one as a defect</b>: {@code the_attribution_gap_is_still_open_see_issue_122}
 * is expected to be TRUE, and it is true precisely while the defect exists.
 *
 * <h2>The pin has to be falsifiable BY THE PRODUCT, and once it was not</h2>
 *
 * <p>An earlier version of this node pinned a check that read exactly two
 * things: does {@code bin/cli/<on_path>} exist, and is {@code <on_path>} on
 * {@code PATH}. Neither answer moves when the product starts recording
 * provenance — so <b>no product change could ever have turned that pin red.</b>
 * It could only be retired by editing the checker. That is precisely the "lie
 * with a ticket number attached" the next paragraph warns about, written by the
 * same author, and a review caught it.
 *
 * <p>{@link HomeIntegrity#declaredCliIsSatisfiedAndAttributed} now reads the
 * lock row and asks whether it <em>says</em> the dependency came from outside
 * this home, against {@link HomeIntegrity#EXTERNAL_PROVENANCE_FIELDS}. Writing
 * any one of those fields turns that check green and this pin red.
 *
 * <p><b>When #122 lands, this node goes red.</b> That is the design and not an
 * accident. A pinned defect that quietly keeps passing after its fix is a lie
 * with a ticket number attached; one that fails the moment the gap closes
 * forces whoever closed it to come here, delete the pin, and promote
 * {@code declaredCliIsSatisfiedAndAttributed} to a plain assertion. The correct
 * response to this node going red is not to relax it.
 *
 * <p>The alternative — asserting the full invariant and leaving the node red —
 * was rejected because this graph is in the CI core set, and a permanently red
 * core graph is a signal nobody reads within a week. The alternative of
 * asserting nothing was rejected because #124 says so directly: an
 * expected-fail with a ticket number is a better artifact than a silent gap.
 */
public class DeclaredCliIsAttributed {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.declared.cli.attributed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "cli", "pinned-defect")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report satisfied = HomeIntegrity.declaredCliIsSatisfied(home);
            HomeIntegrity.Report attributed = HomeIntegrity.declaredCliIsSatisfiedAndAttributed(home);

            // THE PIN'S SUBJECT IS A ROW THE PRODUCT WROTE, and that is the
            // whole point of it. The fixture installs `hi-unit-external`, which
            // declares a dependency the machine already satisfies; the install
            // reports `tools: 1 already present`, writes NO shim, and records a
            // lock row anyway — carrying `binary = "sh"`, a binary it never
            // produced. Defect 7, generated by the product on a hermetic
            // runner rather than planted here.
            //
            // A pin asserted over a row this test wrote by hand could never
            // observe the product being fixed: the row would keep saying
            // whatever the test made it say. Asserted over THIS row, writing
            // any provenance field flips it. That distinction is the review
            // finding that rewrote this node.
            HomeIntegrity.Report externalRow =
                    HomeIntegrity.declaredCliIsSatisfiedAndAttributed(home);

            HomeIntegritySupport.Mutation unsatisfied;
            try {
                unsatisfied = HomeIntegritySupport.probe(home, scratch,
                        "dependency_nothing_satisfies", new DeclareAMissingDep(),
                        HomeIntegrity::declaredCliIsSatisfied);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean everyDepSatisfied = satisfied.holdsNonVacuously();

            // THE PIN. True while #122 is open. The product-written row for the
            // externally-satisfied dependency records no provenance, so the
            // check reports it by name.
            boolean attributionGapOpen = externalRow.findings().stream()
                    .anyMatch(f -> f.subject().equals(HomeIntegritySupport.EXTERNAL_TOOL));

            // The complement, so the pin cannot pass by the check reporting
            // EVERYTHING: the skill-script dependency this home built itself is
            // attributed and must NOT be among the findings.
            boolean selfBuiltDepIsNotReported = externalRow.findings().stream()
                    .noneMatch(f -> f.subject().equals(HomeIntegritySupport.TOOL));

            boolean pass = everyDepSatisfied
                    && attributionGapOpen && selfBuiltDepIsNotReported
                    && unsatisfied.caught() && unsatisfied.repaired();

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "everyDepSatisfied=" + everyDepSatisfied
                                    + " examined=" + satisfied.examined()
                                    + " attributionGapOpen=" + attributionGapOpen
                                    + " selfBuiltDepIsNotReported=" + selfBuiltDepIsNotReported
                                    + " unsatisfiedCaught=" + unsatisfied.caught()
                                    + " unsatisfiedRepaired=" + unsatisfied.repaired()
                                    + " | " + satisfied.describe()
                                    + " | " + attributed.describe());

            return HomeIntegritySupport.withReport(result, satisfied)
                    .assertion("every_declared_cli_dependency_is_satisfied", everyDepSatisfied)
                    .assertion("a_dependency_nothing_satisfies_is_caught", unsatisfied.caught())
                    .assertion("repairing_an_unsatisfied_dependency_clears_it",
                            unsatisfied.repaired())
                    .assertion("a_dependency_this_home_built_itself_is_attributed",
                            selfBuiltDepIsNotReported)
                    // PINNED DEFECT — expected TRUE while #122 is open, and this
                    // node is meant to go red when #122 closes it. Do not relax.
                    .assertion("the_attribution_gap_is_still_open_see_issue_122",
                            attributionGapOpen)
                    .log("PINNED DEFECT #122: the install recorded a row for '"
                            + HomeIntegritySupport.EXTERNAL_TOOL + "' naming a binary it never"
                            + " produced, and nothing in that row says the machine supplied it."
                            + " This assertion reads the PRODUCT's row, so recording any of "
                            + HomeIntegrity.EXTERNAL_PROVENANCE_FIELDS + " turns it red.")
                    .log(attributed.describe());
        });
    }

    /** Declared, no shim, and nothing on PATH either: genuinely unsatisfied. */
    private record DeclareAMissingDep() implements HomeIntegritySupport.Damage {
        private static final String MARK = "# home-integrity: missing dep\n";

        @Override
        public void plant(Path home) throws IOException {
            Path manifest = home.resolve("skills").resolve(HomeIntegritySupport.UNIT_B)
                    .resolve("skill-manager.toml");
            Files.writeString(manifest, Files.readString(manifest) + "\n" + MARK + """
                    [[cli_dependencies]]
                    spec = "brew:hi-absent"
                    on_path = "hi-definitely-not-on-this-machine"
                    """);
        }

        @Override
        public void repair(Path home) throws IOException {
            Path manifest = home.resolve("skills").resolve(HomeIntegritySupport.UNIT_B)
                    .resolve("skill-manager.toml");
            String body = Files.readString(manifest);
            int cut = body.indexOf(MARK);
            if (cut >= 0) Files.writeString(manifest, body.substring(0, cut).stripTrailing() + "\n");
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_declared_cli_dependency_is_satisfied", false)
                .assertion("a_dependency_nothing_satisfies_is_caught", false)
                .assertion("repairing_an_unsatisfied_dependency_clears_it", false)
                .assertion("a_dependency_this_home_built_itself_is_attributed", false)
                .assertion("the_attribution_gap_is_still_open_see_issue_122", false);
    }
}
