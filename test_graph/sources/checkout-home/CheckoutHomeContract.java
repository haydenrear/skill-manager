///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../home-clone/HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The descriptor half of the per-checkout home contract, formerly
 * {@code assert-home.sh} — a scratchpad script run by hand once, at the end of
 * ticket #10.
 *
 * <p>The checks are the same. What changes is that they now run every time
 * anything touches home provisioning, instead of once when an agent remembered
 * to run them.
 *
 * <h2>Policy is asserted by AGREEMENT, not by value</h2>
 *
 * The shell version asserted {@code policy = live} twice, once from
 * {@code home policy} and once by grepping the descriptor. Asserting the literal
 * {@code live} would be wrong here: this home is produced by {@code home clone}
 * rather than by {@code bootstrap-home.sh}, and which policy a clone should
 * inherit is a design question, not a fact this node gets to fix.
 *
 * <p>What IS assertable, and is the stronger property anyway, is that the two
 * read paths AGREE. "Two paths that should agree, don't, and nothing detects
 * it" is a named recurring failure in this epic — {@code unbind} deleting the
 * store unit, {@code list} re-encoding a ledger, {@code /var} vs
 * {@code /private/var}. A home whose CLI reports one policy while its descriptor
 * records another is that bug, and neither of the shell script's two assertions
 * could see it because both would have to be wrong in the same direction to
 * fail together.
 *
 * <h2>The CLI entrypoint assertion is issue #10's second defect</h2>
 *
 * Nothing writes {@code <home>/bin/cli/skill-manager}, yet the launcher shims
 * and {@code HomeDescriptor.resolveCli} both read it. The observed symptom was
 * the worst kind: the shim printed "Unmatched arguments" and exited 0 — a
 * silent no-launch. An exit code is not evidence a launcher launched, so this
 * node asserts the file exists rather than that some command succeeded.
 */
public class CheckoutHomeContract {
    static final NodeSpec SPEC = NodeSpec.of("checkout.home.contract")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("checkout.home.provisioned")
            .tags("checkout-home", "home", "descriptor")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String store = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            if (store == null) {
                return NodeResult.fail("checkout.home.contract", "missing upstream context");
            }
            Path home = Path.of(store);

            Path descriptorFile = home.resolve("home.runtime.json");
            boolean theDescriptorExists = Files.isRegularFile(descriptorFile);
            String descriptor = HomeCloneSupport.read(descriptorFile);
            String descriptorPolicy = HomeCloneSupport.jsonString(descriptor, "policy");

            ProcessRecord policyProc =
                    HomeCloneSupport.sm(ctx, "home-policy", store, "home", "policy", "--home", store);
            boolean homePolicyExitsZero = policyProc.exitCode() == 0;
            String reportedPolicy = policyLine(HomeCloneSupport.log(ctx, "home-policy"));

            boolean theDescriptorRecordsAPolicy = !descriptorPolicy.isBlank();
            boolean bothReadPathsReportTheSamePolicy =
                    theDescriptorRecordsAPolicy && descriptorPolicy.equals(reportedPolicy);

            // Issue #10: nothing provisions this path, and two independent
            // readers depend on it.
            Path cli = home.resolve("bin").resolve("cli").resolve("skill-manager");
            boolean theHomeCarriesItsOwnCliEntrypoint =
                    Files.exists(cli, java.nio.file.LinkOption.NOFOLLOW_LINKS);

            boolean pass = theDescriptorExists && homePolicyExitsZero && theDescriptorRecordsAPolicy
                    && bothReadPathsReportTheSamePolicy && theHomeCarriesItsOwnCliEntrypoint;
            return (pass
                    ? NodeResult.pass("checkout.home.contract")
                    : NodeResult.fail("checkout.home.contract",
                            "descriptorPolicy=[" + descriptorPolicy + "] reportedPolicy=["
                                    + reportedPolicy + "] policyExit=" + policyProc.exitCode()
                                    + " cli=" + cli + " cliExists=" + theHomeCarriesItsOwnCliEntrypoint))
                    .assertion("the_home_carries_a_runtime_descriptor", theDescriptorExists)
                    .assertion("home_policy_exits_zero", homePolicyExitsZero)
                    .assertion("the_descriptor_records_a_policy", theDescriptorRecordsAPolicy)
                    .assertion("the_cli_and_the_descriptor_report_the_same_policy",
                            bothReadPathsReportTheSamePolicy)
                    .assertion("the_home_carries_its_own_cli_entrypoint",
                            theHomeCarriesItsOwnCliEntrypoint)
                    .process(policyProc);
        });
    }

    /** The value of the {@code policy:} line emitted by {@code home policy}. */
    private static String policyLine(String logText) {
        for (String line : logText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("policy:")) {
                return trimmed.substring("policy:".length()).trim();
            }
        }
        return "";
    }
}
