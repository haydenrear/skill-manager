///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Path;

/**
 * Authenticates against the registry's embedded authorization server via
 * client_credentials and caches the resulting JWT at
 * {@code ${SKILL_MANAGER_HOME}/auth.token}. Downstream {@code
 * skill-manager publish} / {@code ads create} / {@code skills delete}
 * subprocesses then attach the bearer to every request.
 *
 * <p>The {@code skill-manager-ci} client is registered in
 * {@code AuthorizationServerConfig}; the shared secret comes from
 * {@code SKILL_REGISTRY_CI_SECRET} and must match what the server reads.
 */
public class CiLoggedIn {
    static final String CLIENT_ID = "skill-manager-ci";

    static final NodeSpec SPEC = NodeSpec.of("ci.logged.in")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up")
            .tags("auth")
            .timeout("30s")
            .output("clientId", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("ci.logged.in", "missing upstream context");
            }
            String secret = System.getenv().getOrDefault(
                    "SKILL_REGISTRY_CI_SECRET", "dev-ci-secret-change-me");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "login",
                    "--client-id", CLIENT_ID,
                    "--client-secret", secret,
                    "--scope", "skill:publish ad:manage",
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            int rc = Procs.runLogged(ctx, "login", pb);
            NodeResult result = rc == 0
                    ? NodeResult.pass("ci.logged.in")
                    : NodeResult.fail("ci.logged.in", "login exited " + rc);
            return Procs.attach(result, ctx, "login", rc, 200)
                    .assertion("token_cached", rc == 0)
                    .publish("clientId", CLIENT_ID);
        });
    }
}
