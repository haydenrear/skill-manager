///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end MCP tool invocation: describe the tool (to satisfy the
 * gateway's disclosure gate), then invoke it with an argument, and assert
 * the echo server's payload made it back through the gateway.
 *
 * The gateway requires a {@code describe_tool} call before the tool's
 * corresponding {@code invoke_tool} call within the same session. Since
 * each CLI invocation is a one-shot session, we pass a stable
 * {@code x-session-id} via the MCP SDK by… actually we can't — the CLI
 * doesn't expose a session header today. Instead we describe + invoke
 * in a single {@code invoke} call path: the CLI under the hood opens
 * its own short-lived SDK session and {@code invoke} alone won't work
 * without describe. So this test drives describe-tool FIRST, then
 * immediately invokes, both in this node, each as its own process —
 * acceptable because the gateway treats sessions as sticky only within
 * a single streamable-http connection. If the second connection has a
 * different session id, the invoke will fail with a disclosure error,
 * and that's the signal we catch.
 */
public class McpToolInvoked {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.invoked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.deployed")
            .tags("mcp", "invoke")
            .timeout("45s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.registered", "serverId").orElse(null);
            if (home == null || gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.tool.invoked", "missing upstream context");
            }
            String toolPath = serverId + "/echo";
            // Stable session so the gateway's disclosure state survives across
            // the two separate CLI invocations (each is a fresh MCP handshake).
            String sessionId = "test-graph-invoke-" + ctx.runId();

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            String describeOut = run(sm, home, repoRoot, List.of(
                    "gateway", "describe-tool", toolPath,
                    "--gateway", gatewayUrl,
                    "--session-id", sessionId));
            boolean describeOk = describeOut.contains("\"tool_name\"")
                    || describeOut.contains("\"echo\"");

            String invokeOut = run(sm, home, repoRoot, List.of(
                    "gateway", "invoke", toolPath,
                    "--args", "{\"message\":\"hello-from-testgraph\"}",
                    "--gateway", gatewayUrl,
                    "--session-id", sessionId));
            boolean invokeOk = invokeOut.contains("hello-from-testgraph");

            return (describeOk && invokeOk
                    ? NodeResult.pass("mcp.tool.invoked")
                    : NodeResult.fail("mcp.tool.invoked",
                            "describeOk=" + describeOk + " invokeOk=" + invokeOk))
                    .assertion("describe_ok", describeOk)
                    .assertion("invoke_roundtrip_ok", invokeOk);
        });
    }

    private static String run(Path sm, String home, Path repoRoot, List<String> subArgs) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(sm.toString());
        cmd.addAll(subArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        p.waitFor();
        return out.toString();
    }
}
