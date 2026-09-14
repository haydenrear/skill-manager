///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An unstamped pm tree: {@code pm/node/22.9.0} with no {@code .platform} stamp,
 * the population provisioned before {@code PmPlatform} stamped. DEF-OUN-018.
 *
 * <p>Today: {@code home repair} names {@code UNSTAMPED_PM_TREE};
 * {@code home verify} exits 0. <b>OHV-2 flips verify to 1.</b>
 */
public class UnstampedPmTreeIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.unstamped.pm.tree")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "pm", "ohv-0")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("unstamped-pm-tree", "UNSTAMPED_PM_TREE", 0, false),
                (Path subject, Path neighbour) -> {
                    Path bin = Files.createDirectories(subject.resolve("pm/node/22.9.0/bin"));
                    Files.writeString(bin.resolve("node"), "#!/bin/sh\nexit 0\n");
                    return "pm/node/22.9.0";
                }));
    }
}
