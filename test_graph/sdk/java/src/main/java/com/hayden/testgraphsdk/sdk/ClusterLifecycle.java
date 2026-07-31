package com.hayden.testgraphsdk.sdk;

import java.util.List;
import java.util.Map;

public final class ClusterLifecycle {
    private ClusterLifecycle() {}

    private record Target(String target, String backend, String dispatchKey, boolean requiresExplicitSelection) {}

    private static final Map<String, Target> TARGETS = Map.of(
            "local-preview", new Target("local-preview", "local", "local", false),
            "local-github-action", new Target("local-github-action", "github-action", "github-action", false),
            "aws-preview", new Target("aws-preview", "aws", "aws", true)
    );

    public static ClusterLifecyclePlan deployCluster(String target, String backend, boolean environmentExists) {
        Target selected = resolve(target, backend);
        return new ClusterLifecyclePlan(
                "deploy_cluster",
                selected.target(),
                selected.backend(),
                selected.dispatchKey(),
                "provision",
                List.of("tofu", "apply", "-auto-approve"),
                true,
                environmentExists ? "reuse-existing" : "provision-missing",
                "",
                false,
                false,
                false,
                selected.requiresExplicitSelection()
        );
    }

    public static ClusterLifecyclePlan resetNode(
            String target,
            String backend,
            boolean justProvisioned,
            boolean alreadyReset
    ) {
        Target selected = resolve(target, backend);
        String skipReason = "";
        if (justProvisioned) {
            skipReason = "just-provisioned";
        } else if (alreadyReset) {
            skipReason = "already-reset";
        }
        boolean shouldRun = skipReason.isEmpty();
        return new ClusterLifecyclePlan(
                "reset_node",
                selected.target(),
                selected.backend(),
                selected.dispatchKey(),
                shouldRun ? "reset" : null,
                shouldRun ? List.of("tofu", "apply", "-auto-approve") : List.of(),
                shouldRun,
                shouldRun ? "reset" : "kept-active",
                skipReason,
                justProvisioned,
                alreadyReset,
                false,
                selected.requiresExplicitSelection()
        );
    }

    public static ClusterLifecyclePlan deleteCluster(String target, String backend, boolean destroyRequested) {
        Target selected = resolve(target, backend);
        return new ClusterLifecyclePlan(
                "delete_cluster",
                selected.target(),
                selected.backend(),
                selected.dispatchKey(),
                destroyRequested ? "destroy" : null,
                destroyRequested ? List.of("tofu", "destroy", "-auto-approve") : List.of(),
                destroyRequested,
                destroyRequested ? "destroyed" : "kept-active",
                destroyRequested ? "" : "destroy-not-requested",
                false,
                false,
                destroyRequested,
                selected.requiresExplicitSelection()
        );
    }

    private static Target resolve(String target, String backend) {
        Target selected = TARGETS.get(target);
        if (selected == null) {
            throw new IllegalArgumentException("unsupported lifecycle target '" + target + "'");
        }
        if (backend != null && !backend.equals(selected.backend())) {
            throw new IllegalArgumentException(
                    "lifecycle target '" + target + "' requires backend '" + selected.backend() + "', got '" + backend + "'");
        }
        return selected;
    }
}
