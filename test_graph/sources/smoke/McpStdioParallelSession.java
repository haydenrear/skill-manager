///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Session-scoped variant of {@link McpStdioParallelGlobalSticky}: deploy
 * the stdio fixture for one agent session, then hammer it with N
 * concurrent {@code invoke_tool} calls — every call carrying the same
 * {@code x-session-id}, so the gateway routes them to the same
 * per-session subprocess.
 *
 * <p>A second {@link TgMcp} pinned to a different session asserts
 * deployment isolation didn't get accidentally widened by the parallel
 * traffic.
 */
public class McpStdioParallelSession {
    static final NodeSpec SPEC = NodeSpec.of("mcp.stdio.parallel.session")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.stdio.session_skill.installed")
            .tags("mcp", "stdio", "parallel", "session")
            .timeout("90s")
            .retries(1);

    private static final int CONCURRENCY = 8;
    private static final String SESSION_A = "test-stdio-session-A";
    private static final String SESSION_B = "test-stdio-session-B";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.stdio.session_skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.stdio.parallel.session", "missing upstream context");
            }
            String toolPath = serverId + "/echo";
            String aSession = SESSION_A + "-" + ctx.runId();
            String bSession = SESSION_B + "-" + ctx.runId();

            // Deploy in session A.
            boolean deployOk;
            try (TgMcp a = new TgMcp(gatewayUrl, aSession)) {
                Map<String, Object> deploy = a.call("deploy_mcp_server",
                        Map.of("server_id", serverId, "scope", "session"));
                deployOk = Boolean.TRUE.equals(deploy.get("deployed"))
                        && "session".equals(deploy.get("scope"));
            }
            if (!deployOk) {
                return NodeResult.fail("mcp.stdio.parallel.session",
                        "session deploy did not return deployed=true scope=session");
            }

            // Fire CONCURRENCY parallel invoke_tool calls, every one
            // carrying SESSION_A — they must all land on the same
            // per-session subprocess and serialize through its worker.
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            int hits;
            String errs;
            try {
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENCY; i++) {
                    final int idx = i;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        String marker = "sess-marker-" + idx;
                        try (TgMcp call = new TgMcp(gatewayUrl, aSession)) {
                            call.call("describe_tool", Map.of("tool_path", toolPath));
                            Map<String, Object> inv = call.call("invoke_tool",
                                    Map.of("tool_path", toolPath,
                                           "arguments", Map.of("message", marker)));
                            String dump = inv.toString();
                            return dump.contains(marker) ? marker : "MISS:" + idx + ":" + dump;
                        } catch (Throwable t) {
                            return "ERR:" + idx + ":" + t.getClass().getSimpleName() + ":" + t.getMessage();
                        }
                    }, pool));
                }
                List<String> results = new ArrayList<>();
                for (CompletableFuture<String> f : futures) {
                    results.add(f.get(60, TimeUnit.SECONDS));
                }
                int h = 0;
                StringBuilder e = new StringBuilder();
                for (int i = 0; i < results.size(); i++) {
                    String r = results.get(i);
                    if (("sess-marker-" + i).equals(r)) h++;
                    else { if (e.length() > 0) e.append(" | "); e.append(r); }
                }
                hits = h;
                errs = e.toString();
            } finally {
                pool.shutdownNow();
            }

            // Session A: still deployed in session, not globally. Also
            // list its tools and fire one final invoke whose response we
            // parse — proves the per-session subprocess survived the
            // parallel load and is still wired into the worker correctly.
            boolean aInSession;
            boolean aGlobal;
            boolean toolListOk;
            int toolCount;
            boolean finalInvokeOk;
            String finalMarker = "sess-final-" + ctx.runId();
            String finalDump;
            try (TgMcp a = new TgMcp(gatewayUrl, aSession)) {
                Map<String, Object> desc = a.call("describe_mcp_server",
                        Map.of("server_id", serverId));
                aInSession = TgMcp.bool(desc, "deployed_in_session");
                aGlobal = TgMcp.bool(desc, "deployed_globally");

                Map<String, Object> browse = a.call("browse_active_tools",
                        Map.of("server_id", serverId));
                Object items = browse.get("items");
                List<?> tools = items instanceof List<?> l ? l : List.of();
                toolCount = tools.size();
                toolListOk = toolCount > 0 && tools.stream().anyMatch(it ->
                        it instanceof Map<?, ?> m && "echo".equals(m.get("tool_name")));

                a.call("describe_tool", Map.of("tool_path", toolPath));
                Map<String, Object> finalInv = a.call("invoke_tool",
                        Map.of("tool_path", toolPath,
                               "arguments", Map.of("message", finalMarker)));
                finalDump = finalInv.toString();
                finalInvokeOk = finalDump.contains(finalMarker)
                        && finalDump.contains("\"transport\"")
                        && finalDump.contains("stdio")
                        && finalDump.contains(serverId);
            }

            // Session B: must not see A's deployment.
            boolean bInSession;
            boolean bGlobal;
            try (TgMcp b = new TgMcp(gatewayUrl, bSession)) {
                Map<String, Object> desc = b.call("describe_mcp_server",
                        Map.of("server_id", serverId));
                bInSession = TgMcp.bool(desc, "deployed_in_session");
                bGlobal = TgMcp.bool(desc, "deployed_globally");
            }

            boolean allHit = hits == CONCURRENCY;
            boolean isolated = aInSession && !aGlobal && !bInSession && !bGlobal;
            return (allHit && isolated && toolListOk && finalInvokeOk
                    ? NodeResult.pass("mcp.stdio.parallel.session")
                    : NodeResult.fail("mcp.stdio.parallel.session",
                            "hits=" + hits + "/" + CONCURRENCY
                                    + " aSess=" + aInSession + " aGlob=" + aGlobal
                                    + " bSess=" + bInSession + " bGlob=" + bGlobal
                                    + " toolListOk=" + toolListOk
                                    + " toolCount=" + toolCount
                                    + " finalInvokeOk=" + finalInvokeOk
                                    + (errs.isEmpty() ? "" : " errs=[" + errs + "]")
                                    + (finalInvokeOk ? "" : " finalDump=" + finalDump)))
                    .assertion("all_session_invocations_round_tripped", allHit)
                    .assertion("session_A_still_deployed", aInSession)
                    .assertion("not_widened_to_global", !aGlobal)
                    .assertion("session_B_isolated", !bInSession && !bGlobal)
                    .assertion("tools_list_parsed_in_session", toolListOk)
                    .assertion("final_invoke_returns_expected_payload", finalInvokeOk)
                    .metric("concurrency", CONCURRENCY)
                    .metric("hits", hits)
                    .metric("toolCount", toolCount);
        });
    }
}
