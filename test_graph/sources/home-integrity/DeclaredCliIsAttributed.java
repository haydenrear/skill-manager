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
 *   <li><b>satisfied</b> — holds. Measured 27 of 27 in the operator's project
 *       home. Every declared dep either has a shim here or is present on the
 *       system PATH.</li>
 *   <li><b>attributed</b> — <b>does not hold, and is a known open defect.</b>
 *       Nothing in {@code cli-lock.toml} distinguishes "this home built a shim"
 *       from "{@code CliPresence.alreadyProvided} found it on the machine". 11
 *       rows in the operator's project home are in the second state; ARTI-06
 *       measured them, offered two candidate fixes — record the output at
 *       {@code Scope.EXTERNAL}, or stop recording a {@code binary} the install
 *       did not produce — and recorded that choosing between them is the
 *       owner's call, not a ticket's. It is a design change. <b>#120</b> owns
 *       it.</li>
 * </ul>
 *
 * <p>So this node asserts the true conjunct positively, and <b>pins the false
 * one as a defect</b>: {@code the_attribution_gap_is_still_open_see_120} is
 * expected to be TRUE, and it is true precisely while the defect exists.
 *
 * <p><b>When #120 lands, this node goes red.</b> That is the design and not an
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

            // The fixture's one dep is a skill-script one, which always writes
            // into the home — so on the fixture BOTH conjuncts hold, and the
            // defect has to be planted to be observed. Planting it is what makes
            // the pin an assertion about the product rather than about a home
            // that happens to be simple.
            HomeIntegritySupport.Mutation externallySatisfied;
            HomeIntegritySupport.Mutation unsatisfied;
            try {
                externallySatisfied = HomeIntegritySupport.probe(home, scratch,
                        "dependency_the_machine_already_satisfied",
                        new DeclareAnExternalDep(),
                        HomeIntegrity::declaredCliIsSatisfiedAndAttributed);
                unsatisfied = HomeIntegritySupport.probe(home, scratch,
                        "dependency_nothing_satisfies", new DeclareAMissingDep(),
                        HomeIntegrity::declaredCliIsSatisfied);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean everyDepSatisfied = satisfied.holdsNonVacuously();
            // THE PIN. True while #120 is open. See the class comment.
            boolean attributionGapOpen = externallySatisfied.caught();
            boolean fixtureIsFullyAttributed = attributed.holds();

            boolean pass = everyDepSatisfied
                    && attributionGapOpen && externallySatisfied.repaired()
                    && unsatisfied.caught() && unsatisfied.repaired()
                    && fixtureIsFullyAttributed;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "everyDepSatisfied=" + everyDepSatisfied
                                    + " examined=" + satisfied.examined()
                                    + " attributionGapOpen=" + attributionGapOpen
                                    + " externalRepaired=" + externallySatisfied.repaired()
                                    + " unsatisfiedCaught=" + unsatisfied.caught()
                                    + " unsatisfiedRepaired=" + unsatisfied.repaired()
                                    + " fixtureIsFullyAttributed=" + fixtureIsFullyAttributed
                                    + " | " + satisfied.describe()
                                    + " | " + attributed.describe());

            return HomeIntegritySupport.withReport(result, satisfied)
                    .assertion("every_declared_cli_dependency_is_satisfied", everyDepSatisfied)
                    .assertion("a_dependency_nothing_satisfies_is_caught", unsatisfied.caught())
                    .assertion("repairing_an_unsatisfied_dependency_clears_it",
                            unsatisfied.repaired())
                    .assertion("a_home_whose_deps_it_built_itself_attributes_all_of_them",
                            fixtureIsFullyAttributed)
                    // PINNED DEFECT — expected TRUE while #120 is open, and this
                    // node is meant to go red when #120 closes it. Do not relax.
                    .assertion("the_attribution_gap_is_still_open_see_issue_120",
                            attributionGapOpen)
                    .log("PINNED DEFECT #120: a dependency satisfied by the machine rather than"
                            + " by this home is recorded no differently from one that was never"
                            + " materialized. " + externallySatisfied.evidence())
                    .log(attributed.describe());
        });
    }

    /**
     * Declare a dependency on something the machine already has, and write no
     * shim for it — the {@code ALREADY_PRESENT} state, verbatim.
     *
     * <p>{@code sh} is the target because it is the one binary a POSIX runner is
     * guaranteed to have, so the mutation means the same thing on this laptop
     * and on a hosted runner. A tool that might be absent would turn this
     * assertion into a statement about the runner's image.
     */
    private record DeclareAnExternalDep() implements HomeIntegritySupport.Damage {
        private static final String MARK = "# home-integrity: external dep\n";

        @Override
        public void plant(Path home) throws IOException {
            Path manifest = home.resolve("skills").resolve(HomeIntegritySupport.UNIT_B)
                    .resolve("skill-manager.toml");
            Files.writeString(manifest, Files.readString(manifest) + "\n" + MARK + """
                    [[cli_dependencies]]
                    spec = "brew:hi-external"
                    on_path = "sh"
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
                .assertion("a_home_whose_deps_it_built_itself_attributes_all_of_them", false)
                .assertion("the_attribution_gap_is_still_open_see_issue_120", false);
    }
}
