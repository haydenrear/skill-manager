///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * A foreign path in a shim: {@code bin/cli/wrapper} is a regular file that execs
 * the NEIGHBOUR home's {@code venvs/v/bin/wrapper}. It runs, so nothing that
 * checks executability or link resolution sees anything. DEF-104 / HIS-21.
 *
 * <p>The subject holds its own {@code venvs/v/bin/wrapper} too, so the finding
 * is repairable (the path maps into this home) and the fix-then-detect half of
 * the node has something to prove.
 *
 * <p>Today: the one shape both readers already agree on — repair names
 * {@code FOREIGN_PATH_IN_SHIM}, and verify exits 1 naming the same subject.
 */
public class ForeignPathInShimIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.foreign.path.in.shim")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "shim", "ohv-0")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("foreign-path-in-shim", "FOREIGN_PATH_IN_SHIM", 1, true),
                (Path subject, Path neighbour) -> {
                    HomeVerdictsSupport.venvTool(subject, "v", "wrapper");
                    Path theirs = HomeVerdictsSupport.venvTool(neighbour, "v", "wrapper");
                    HomeVerdictsSupport.literalShim(subject, "wrapper", theirs);
                    return "bin/cli/wrapper";
                }));
    }
}
