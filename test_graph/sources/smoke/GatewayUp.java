///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/StaleProcCleanup.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Starts the virtual MCP gateway via {@code skill-manager gateway up}.
 *
 * The CLI already tracks pid/log/config under {@code $SKILL_MANAGER_HOME} so
 * we just need to point it at the per-run home from {@code env.prepared} and
 * the port env.prepared allocated. Teardown lives in {@code servers.down}.
 */
public class GatewayUp {
    static final NodeSpec SPEC = NodeSpec.of("gateway.up")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("env.prepared")
            // Transitive: any graph that includes gateway.up auto-pulls in
            // the venv-ready fixture, so a fresh checkout doesn't fail with
            // "ModuleNotFoundError: uvicorn" on first run.
            .dependsOn("gateway.python.venv.ready")
            .tags("gateway", "mcp")
            .sideEffects("net:local", "proc:spawn")
            .timeout("60s")
            .output("baseUrl", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String portStr = ctx.get("env.prepared", "gatewayPort").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || portStr == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("gateway.up", "missing env.prepared context");
            }
            int port = Integer.parseInt(portStr);

            // Kill any virtual-mcp-gateway processes left over from a
            // previous run that crashed before teardown. Pidfiles live
            // under random per-run homes, so command-line match is the
            // only reliable signal across runs.
            StaleProcCleanup.killByCommandLineMatch(
                    ctx, "gateway-stale-cleanup",
                    "gateway.server",
                    "virtual-mcp-gateway");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "up",
                    "--port", Integer.toString(port),
                    "--wait-seconds", "45");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            // gateway up now syncs agent MCP configs as part of its run —
            // sandbox CLAUDE_HOME / CODEX_HOME to the per-run agent dirs
            // so the test doesn't clobber the developer's real configs.
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            ProcessRecord proc = Procs.run(ctx, "gateway-up", pb);
            int rc = proc.exitCode();
            if (rc != 0) {
                return NodeResult.fail("gateway.up", "skill-manager gateway up exited " + rc)
                        .process(proc);
            }
            return NodeResult.pass("gateway.up")
                    .process(proc)
                    .assertion("gateway_healthy", true)
                    .metric("port", port)
                    .publish("baseUrl", "http://127.0.0.1:" + port);
        });
    }
}
