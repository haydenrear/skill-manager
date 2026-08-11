///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import java.util.List;
import java.util.Map;

import com.hayden.testgraphsdk.sdk.ClusterLifecycle;
import com.hayden.testgraphsdk.sdk.ClusterLifecyclePlan;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

public class DeleteCluster {
    private record Target(String target, String backend) {}

    private static final List<Target> TARGETS = List.of(
            new Target("local-preview", "local"),
            new Target("local-github-action", "github-action"),
            new Target("aws-preview", "aws")
    );

    private static final NodeSpec SPEC = NodeSpec.of("tg6.lifecycle.java.delete-cluster")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("tg6", "environment", "lifecycle-template", "jbang")
            .output("Runtime", "string")
            .output("DeleteCases", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            boolean skipKeepsActive = true;
            boolean destroyRuns = true;
            int cases = 0;
            for (Target target : TARGETS) {
                ClusterLifecyclePlan skip = ClusterLifecycle.deleteCluster(target.target(), target.backend(), false);
                ClusterLifecyclePlan destroy = ClusterLifecycle.deleteCluster(target.target(), target.backend(), true);
                cases += 2;
                skipKeepsActive &= !skip.shouldRun()
                        && "destroy-not-requested".equals(skip.skipReason())
                        && "kept-active".equals(skip.expectedState());
                destroyRuns &= destroy.shouldRun()
                        && "destroy".equals(destroy.environmentAction())
                        && destroy.tofuCommand().equals(List.of("tofu", "destroy", "-auto-approve"))
                        && "destroyed".equals(destroy.expectedState());
            }

            ClusterLifecyclePlan sample = ClusterLifecycle.deleteCluster("local-preview", "local", false);
            NodeResult result = NodeResult.pass(ctx.nodeId())
                    .assertion("all_targets_covered", cases == TARGETS.size() * 2)
                    .assertion("destroy_skips_without_intent", skipKeepsActive)
                    .assertion("destroy_runs_with_intent", destroyRuns)
                    .assertion("aws_destroy_requires_explicit_selection", ClusterLifecycle.deleteCluster("aws-preview", "aws", true).requiresExplicitSelection())
                    .publish("Runtime", "jbang")
                    .publish("DeleteCases", Integer.toString(cases));
            for (Map.Entry<String, String> entry : sample.published().entrySet()) {
                result.publish("Delete" + entry.getKey(), entry.getValue());
            }
            return result;
        });
    }
}
