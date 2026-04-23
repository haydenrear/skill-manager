///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sync every installed skill to Claude + Codex with a per-run fake $HOME so
 * this test doesn't touch the user's real agent configs. The Java CLI reads
 * {@code System.getProperty("user.home")} — set via {@code -Duser.home=…} on
 * the JBang command (the bash wrapper doesn't forward it, so we invoke jbang
 * directly here).
 */
public class AgentsSynced {
    static final NodeSpec SPEC = NodeSpec.of("agents.synced")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("umbrella.installed", "echo.http.registered")
            .tags("agents", "sync")
            .timeout("60s")
            .output("fakeHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || gatewayUrl == null) {
                return NodeResult.fail("agents.synced", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path fakeHome = Path.of(home, "fake-home");
            Files.createDirectories(fakeHome);

            // Call jbang with -Duser.home=<fakeHome> so the Java CLI's
            // agent code (ClaudeAgent / CodexAgent) writes into our sandbox.
            ProcessBuilder pb = new ProcessBuilder(
                    "jbang", "run",
                    "-Duser.home=" + fakeHome,
                    repoRoot.resolve("SkillManager.java").toString(),
                    "sync", "--agent", "claude,codex", "--copy", "--yes",
                    "--gateway", gatewayUrl)
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            int rc = pb.start().waitFor();

            return (rc == 0
                    ? NodeResult.pass("agents.synced")
                    : NodeResult.fail("agents.synced", "sync exited " + rc))
                    .assertion("sync_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("fakeHome", fakeHome.toString());
        });
    }
}
