///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * The control for every shape node: the fixture's clean home, laid out from
 * nothing, is clean to BOTH readers. {@code home repair --json} exits 0 with
 * {@code "clean":true} and {@code home verify} exits 0. Without this, a reader
 * that reports every home would satisfy every shape node.
 *
 * <p>Also asserts the layout is a home to production (neither command exits 2,
 * {@code NotAHomeException}) — otherwise every shape node would be measuring a
 * refusal.
 */
public class CleanHomeHasNoVerdict {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.clean.home")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "control", "ohv-0")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get(HomeVerdictsSupport.FIXTURE, "cleanHome").orElse(null);
            if (homeStr == null) {
                return NodeResult.fail(SPEC.id(), "missing " + HomeVerdictsSupport.FIXTURE + " context");
            }
            Path home = Path.of(homeStr);
            var repair = HomeVerdictsSupport.repairJson(ctx, "repair", home);
            var verify = HomeVerdictsSupport.verify(ctx, "verify", home);

            boolean isAHome = repair.exit() != 2 && verify.exit() != 2;
            boolean repairClean = repair.exit() == 0 && repair.stdout().contains("\"clean\":true");
            boolean stdoutIsJson = HomeVerdictsSupport.isOneJsonObject(repair.stdout());
            boolean verifyClean = verify.exit() == 0;
            boolean pass = isAHome && repairClean && stdoutIsJson && verifyClean;
            return (pass ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(), "repair=" + repair.exit() + " verify=" + verify.exit()
                            + " stdout=" + HomeVerdictsSupport.head(repair.stdout())))
                    .process(repair.proc()).process(verify.proc())
                    .assertion("production_accepts_the_layout_as_a_home", isAHome)
                    .assertion("home_repair_json_exits_0_and_says_clean", repairClean)
                    .assertion("home_repair_json_stdout_alone_is_one_json_object", stdoutIsJson)
                    .assertion("home_verify_exits_0", verifyClean)
                    .metric("repair.exit", repair.exit())
                    .metric("verify.exit", verify.exit())
                    .log("repair --json stdout: " + repair.stdout().strip());
        });
    }
}
