///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import java.nio.file.Files;
import java.nio.file.Path;

import com.hayden.testgraphsdk.sdk.EnvironmentRepository;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.SideEffect;

public class Tg6EnvironmentRepositoryProvisionJbang {
    private static final EnvironmentRepository REPOSITORY = EnvironmentRepository
            .builder("build/tg6-environment-repository-local-preview", "templates/branch-preview")
            .target("local-preview")
            .backend("local")
            .build();

    private static final NodeSpec SPEC = NodeSpec.of("tg6.environment.repository.provision.jbang")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("tg6.environment.repository.scaffold.local")
            .tags("tg6", "environment", "repository-execution", "jbang")
            .sideEffect(SideEffect.environment(SideEffect.EnvironmentAction.PROVISION))
            .environmentRepository(REPOSITORY)
            .output("EnvironmentId", "string")
            .output("KUBECONFIG", "string")
            .output("KUBECONTEXT", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String kubeconfig = getenv("KUBECONFIG");
            String kubecontext = getenv("KUBECONTEXT");
            String environmentId = getenv("EnvironmentId");
            return NodeResult.pass(ctx.nodeId())
                    .assertion("environment_id_is_available", !environmentId.isBlank())
                    .assertion("kubeconfig_env_is_available", !kubeconfig.isBlank() && Files.isRegularFile(Path.of(kubeconfig)))
                    .assertion("kubecontext_uses_local_preview_target", kubecontext.startsWith("test-graph-local-preview-"))
                    .assertion("target_env_is_local_preview", "local-preview".equals(getenv("TEST_GRAPH_ENVIRONMENT_TARGET")))
                    .assertion("backend_env_is_local", "local".equals(getenv("TEST_GRAPH_ENVIRONMENT_BACKEND")))
                    .assertion("environment_was_not_reused_first_time", "false".equals(getenv("TEST_GRAPH_ENVIRONMENT_REUSED")))
                    .assertion("repository_dir_env_is_available", Files.isDirectory(Path.of(getenv("TEST_GRAPH_ENVIRONMENT_REPOSITORY_DIR"))))
                    .assertion("template_dir_env_is_available", Files.isDirectory(Path.of(getenv("TEST_GRAPH_ENVIRONMENT_TEMPLATE_DIR"))))
                    .publish("EnvironmentId", environmentId)
                    .publish("KUBECONFIG", kubeconfig)
                    .publish("KUBECONTEXT", kubecontext);
        });
    }

    private static String getenv(String key) {
        return System.getenv().getOrDefault(key, "");
    }
}
