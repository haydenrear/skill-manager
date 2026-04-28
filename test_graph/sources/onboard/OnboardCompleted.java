///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives {@code skill-manager onboard} against the per-run registry. The
 * onboard CLI installs both bundled skills (skill-manager-skill,
 * skill-publisher-skill) from local paths and ensures the gateway is up.
 *
 * <p>We pass {@code --install-dir} explicitly so the command doesn't
 * depend on cwd-walking from inside the test_graph subdirectory and so
 * the same flag form is exercised that a future packaged-CLI release
 * would use.
 */
public class OnboardCompleted {
    static final NodeSpec SPEC = NodeSpec.of("onboard.completed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up", "ci.logged.in", "gateway.python.venv.ready")
            .tags("onboard", "cli")
            .timeout("180s")
            .output("home", "string")
            .output("agentHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElse(null);
            if (home == null || registryUrl == null || gatewayPort == null) {
                return NodeResult.fail("onboard.completed", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Scope agent-config writes to a per-run directory so the
            // install's claude/codex sync doesn't clobber the developer's
            // real ~/.claude.json and ~/.codex/config.toml. The agent
            // classes honor CLAUDE_HOME / CODEX_HOME env vars; setting
            // both here keeps everything inside <home>/agent-home/.
            Path agentHome = Path.of(home).resolve("agent-home");
            Files.createDirectories(agentHome);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "onboard",
                    "--install-dir", repoRoot.toString(),
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            // Pin the gateway port to the env-allocated one so onboard's
            // gateway-up doesn't try to bind 8080 (already taken on most
            // dev boxes). InstallCommand reads this through the same
            // GatewayConfig.resolve path.
            pb.environment().put("SKILL_MANAGER_GATEWAY_URL",
                    "http://127.0.0.1:" + gatewayPort);
            pb.environment().put("CLAUDE_HOME", agentHome.toString());
            pb.environment().put("CODEX_HOME", agentHome.resolve(".codex").toString());

            ProcessRecord proc = Procs.run(ctx, "onboard", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("onboard.completed")
                    : NodeResult.fail("onboard.completed", "onboard exited " + rc);
            return result
                    .process(proc)
                    .assertion("onboard_exit_zero", rc == 0)
                    .metric("exitCode", rc)
                    .publish("home", home)
                    .publish("agentHome", agentHome.toString());
        });
    }
}
