///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.SideEffect;

public class Tg6AwsLifecycleContextJbang {
    private static final String GUARD = "tg6.aws.lifecycle.guard";
    private static final String UPSTREAM = "tg6.aws.lifecycle.provision-missing";

    private static final NodeSpec SPEC = NodeSpec.of("tg6.aws.lifecycle.context.jbang")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(UPSTREAM)
            .tags("tg6", "environment", "aws-lifecycle", "jbang")
            .sideEffect(SideEffect.envAll());

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            boolean enabled = "true".equals(ctx.get(GUARD, "awsLifecycleEnabled").orElse("false"));
            NodeResult result = NodeResult.pass(ctx.nodeId())
                    .assertion("aws_guard_context_available", ctx.get(GUARD, "awsLifecycleEnabled").isPresent());
            if (enabled) {
                return result
                        .assertion("environment_id_projected", getenv("EnvironmentId").equals(ctx.get(UPSTREAM, "EnvironmentId").orElse("")))
                        .assertion("kubeconfig_projected", getenv("KUBECONFIG").equals(ctx.get(UPSTREAM, "KUBECONFIG").orElse("")))
                        .assertion("kubecontext_projected", getenv("KUBECONTEXT").equals(ctx.get(UPSTREAM, "KUBECONTEXT").orElse("")))
                        .assertion("environment_reused_projected_by_all", "false".equals(getenv("EnvironmentRepositoryReused")));
            }
            return result
                    .assertion("aws_guard_reason_projected", getenv("awsGuardReason").equals(ctx.get(UPSTREAM, "awsGuardReason").orElse("")));
        });
    }

    private static String getenv(String key) {
        return System.getenv().getOrDefault(key, "");
    }
}
