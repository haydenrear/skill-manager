///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import java.util.List;
import java.util.Map;

import com.hayden.testgraphsdk.sdk.ClusterLifecycle;
import com.hayden.testgraphsdk.sdk.ClusterLifecyclePlan;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

public class ResetNode {
    private record Target(String target, String backend) {}

    private static final List<Target> TARGETS = List.of(
            new Target("local-preview", "local"),
            new Target("local-github-action", "github-action"),
            new Target("aws-preview", "aws")
    );

    private static final NodeSpec SPEC = NodeSpec.of("tg6.lifecycle.java.reset-node")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("tg6", "environment", "lifecycle-template", "jbang")
            .output("Runtime", "string")
            .output("ResetCases", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            boolean normalRuns = true;
            boolean justProvisionedSkips = true;
            boolean alreadyResetSkips = true;
            int cases = 0;
            for (Target target : TARGETS) {
                ClusterLifecyclePlan run = ClusterLifecycle.resetNode(target.target(), target.backend(), false, false);
                ClusterLifecyclePlan justProvisioned = ClusterLifecycle.resetNode(target.target(), target.backend(), true, false);
                ClusterLifecyclePlan alreadyReset = ClusterLifecycle.resetNode(target.target(), target.backend(), false, true);
                cases += 3;
                normalRuns &= run.shouldRun()
                        && "reset".equals(run.environmentAction())
                        && run.tofuCommand().equals(List.of("tofu", "apply", "-auto-approve"));
                justProvisionedSkips &= !justProvisioned.shouldRun()
                        && "just-provisioned".equals(justProvisioned.skipReason())
                        && "kept-active".equals(justProvisioned.expectedState());
                alreadyResetSkips &= !alreadyReset.shouldRun()
                        && "already-reset".equals(alreadyReset.skipReason())
                        && "kept-active".equals(alreadyReset.expectedState());
            }

            ClusterLifecyclePlan sample = ClusterLifecycle.resetNode("local-preview", "local", true, false);
            NodeResult result = NodeResult.pass(ctx.nodeId())
                    .assertion("all_targets_covered", cases == TARGETS.size() * 3)
                    .assertion("normal_reset_runs", normalRuns)
                    .assertion("just_provisioned_reset_skips", justProvisionedSkips)
                    .assertion("already_reset_skips", alreadyResetSkips)
                    .publish("Runtime", "jbang")
                    .publish("ResetCases", Integer.toString(cases));
            for (Map.Entry<String, String> entry : sample.published().entrySet()) {
                result.publish("Reset" + entry.getKey(), entry.getValue());
            }
            return result;
        });
    }
}
