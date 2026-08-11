///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.SideEffect;

public class Tg6EnvironmentContextEnvAllJbang {
    private static final String UPSTREAM = "tg6.environment.repository.provision.jbang";

    private static final NodeSpec SPEC = NodeSpec.of("tg6.environment.context.env-all.jbang")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(UPSTREAM)
            .tags("tg6", "environment", "context-propagation", "jbang")
            .sideEffect(SideEffect.envAll());

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx ->
                NodeResult.pass(ctx.nodeId())
                        .assertion("environment_id_projected", getenv("EnvironmentId").equals(ctx.get(UPSTREAM, "EnvironmentId").orElse("")))
                        .assertion("kubeconfig_projected", getenv("KUBECONFIG").equals(ctx.get(UPSTREAM, "KUBECONFIG").orElse("")))
                        .assertion("kubecontext_projected", getenv("KUBECONTEXT").equals(ctx.get(UPSTREAM, "KUBECONTEXT").orElse("")))
                        .assertion("environment_reused_projected_by_all", "false".equals(getenv("EnvironmentRepositoryReused")))
        );
    }

    private static String getenv(String key) {
        return System.getenv().getOrDefault(key, "");
    }
}
