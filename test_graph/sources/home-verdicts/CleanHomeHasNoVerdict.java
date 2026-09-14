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
            HomeVerdictsSupport.RepairReport parsed = HomeVerdictsSupport.report(repair);
            boolean repairClean = repair.exit() == 0 && parsed.parsedClean();
            boolean stdoutIsJson = parsed.object();
            boolean verifyClean = verify.exit() == 0;

            // The parser every shape node matches through, shown to match by
            // FIELD: keys reordered, whitespace added, an extra field, and a
            // near-miss subject that must NOT match. A text matcher passes the
            // first stdout the command happens to print and fails this.
            var reordered = HomeVerdictsSupport.RepairReport.parse("""
                    {
                      "findings" : [ { "repairable" : true, "subject" : "bin/cli/x",
                                       "detail" : "d", "kind" : "FROZEN_HOME_PATH_IN_SHIM" } ],
                      "clean" : false, "examined" : 3, "home" : "/h"
                    }
                    """);
            boolean parserMatchesByField = reordered.object()
                    && reordered.reports("FROZEN_HOME_PATH_IN_SHIM", "bin/cli/x")
                    && !reordered.reports("FROZEN_HOME_PATH_IN_SHIM", "bin/cli/xy")
                    && !reordered.parsedClean()
                    && !HomeVerdictsSupport.RepairReport.parse("banner\n{\"clean\":true}").object()
                    && !HomeVerdictsSupport.RepairReport.parse("{\"clean\":true} trailing").object();

            boolean pass = isAHome && repairClean && stdoutIsJson && verifyClean && parserMatchesByField;
            return (pass ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(), "repair=" + repair.exit() + " verify=" + verify.exit()
                            + " stdout=" + HomeVerdictsSupport.head(repair.stdout())))
                    .process(repair.proc()).process(verify.proc())
                    .assertion("production_accepts_the_layout_as_a_home", isAHome)
                    .assertion("home_repair_json_exits_0_and_says_clean", repairClean)
                    .assertion("home_repair_json_stdout_alone_is_one_json_object", stdoutIsJson)
                    .assertion("home_verify_exits_0", verifyClean)
                    .assertion("the_finding_parser_matches_by_field_not_by_key_order_or_whitespace",
                            parserMatchesByField)
                    .metric("repair.exit", repair.exit())
                    .metric("verify.exit", verify.exit())
                    .log("repair --json stdout: " + repair.stdout().strip());
        });
    }
}
