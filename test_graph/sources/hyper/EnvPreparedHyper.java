///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Hyper-experiments-specific overrides on the shared per-run env. Currently
 * the only thing it carries is {@code allowFileUpload="false"} so the
 * registry server boots with its production-default backend (github-pointer
 * publishes only) and {@code hyper.published} actually exercises the
 * github register endpoint instead of falling through to multipart.
 *
 * <p>{@link com.hayden.testgraphsdk.sdk RegistryUp} reads
 * {@code env.hyper.prepared.allowFileUpload} when present and falls back to
 * {@code true} (legacy upload allowed) otherwise — so smoke, sponsored,
 * onboard, refresh-flow keep working unchanged.
 */
public class EnvPreparedHyper {
    static final NodeSpec SPEC = NodeSpec.of("env.hyper.prepared")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("env", "hyper")
            .output("allowFileUpload", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> NodeResult.pass("env.hyper.prepared")
                .publish("allowFileUpload", "false"));
    }
}
