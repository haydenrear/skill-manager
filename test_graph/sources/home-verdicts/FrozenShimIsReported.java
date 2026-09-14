///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * A FULLY frozen own-home shim: {@code bin/cli/frozen-tool} execs this home's
 * own {@code venvs/frozen/bin/frozen-tool} by absolute path, with no
 * {@code SKILL_MANAGER_SHIM_HOME} anywhere in it. DEF-OUN-018; measured on the
 * fleet as the {@code tla-spec-dev} and deploy-helm skill-script shims.
 *
 * <p>Today: {@code home repair} names {@code FROZEN_HOME_PATH_IN_SHIM} and
 * {@code home verify} exits 0 without naming it. <b>OHV-2 flips verify to 1.</b>
 *
 * <p>Not the HALF-rewritten shape (DEF-OHV-001): that one repair exempts today,
 * so its node is OHV-4's.
 */
public class FrozenShimIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.frozen.shim")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "shim", "ohv-0")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("frozen-shim", "FROZEN_HOME_PATH_IN_SHIM", 0, false),
                (Path subject, Path neighbour) -> {
                    Path tool = HomeVerdictsSupport.venvTool(subject, "frozen", "frozen-tool");
                    HomeVerdictsSupport.literalShim(subject, "frozen-tool", tool);
                    return "bin/cli/frozen-tool";
                }));
    }
}
