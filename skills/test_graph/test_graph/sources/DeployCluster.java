///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import java.util.List;
import java.util.Map;

import com.hayden.testgraphsdk.sdk.ClusterLifecycle;
import com.hayden.testgraphsdk.sdk.ClusterLifecyclePlan;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

public class DeployCluster {
    private record Target(String target, String backend, String dispatch, boolean explicit) {}

    private static final List<Target> TARGETS = List.of(
            new Target("local-preview", "local", "local", false),
            new Target("local-github-action", "github-action", "github-action", false),
            new Target("aws-preview", "aws", "aws", true)
    );

    private static final NodeSpec SPEC = NodeSpec.of("tg6.lifecycle.java.deploy-cluster")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("tg6", "environment", "lifecycle-template", "jbang")
            .output("Runtime", "string")
            .output("DeployCases", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            int cases = 0;
            boolean dispatchMatches = true;
            boolean missingProvisions = true;
            boolean existingReuses = true;
            for (Target target : TARGETS) {
                ClusterLifecyclePlan missing = ClusterLifecycle.deployCluster(target.target(), target.backend(), false);
                ClusterLifecyclePlan existing = ClusterLifecycle.deployCluster(target.target(), target.backend(), true);
                cases += 2;
                dispatchMatches &= missing.dispatchKey().equals(target.dispatch());
                missingProvisions &= missing.expectedState().equals("provision-missing");
                existingReuses &= existing.expectedState().equals("reuse-existing");
            }

            ClusterLifecyclePlan sample = ClusterLifecycle.deployCluster("local-preview", "local", false);
            NodeResult result = NodeResult.pass(ctx.nodeId())
                    .assertion("all_targets_covered", cases == TARGETS.size() * 2)
                    .assertion("deploy_uses_provision_action", sample.environmentAction().equals("provision"))
                    .assertion("deploy_uses_apply_command", sample.tofuCommand().equals(List.of("tofu", "apply", "-auto-approve")))
                    .assertion("missing_environment_is_provisioned", missingProvisions)
                    .assertion("existing_environment_is_reused", existingReuses)
                    .assertion("dispatch_metadata_matches_target_matrix", dispatchMatches)
                    .assertion("aws_requires_explicit_selection", ClusterLifecycle.deployCluster("aws-preview", "aws", false).requiresExplicitSelection())
                    .publish("Runtime", "jbang")
                    .publish("DeployCases", Integer.toString(cases));
            for (Map.Entry<String, String> entry : sample.published().entrySet()) {
                result.publish("Deploy" + entry.getKey(), entry.getValue());
            }
            return result;
        });
    }
}
