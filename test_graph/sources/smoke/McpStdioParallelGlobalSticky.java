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
 * Parallel stress on a stdio MCP server at scope {@code global-sticky}.
 * Fires N concurrent {@code invoke_tool} calls against the single shared
 * subprocess and asserts every one round-trips with its unique marker.
 *
 * <p>This is the shape that used to surface {@code anyio.ClosedResourceError}
 * before {@code StdioMCPClient} owned its session in a dedicated worker
 * task. Multiple calls arriving on different request-handler tasks would
 * race the persistent session's stream ownership; the worker model
 * funnels them through a single queue and reopens on broken pipes.
 *
 * <p>One {@link TgMcp} client per concurrent call so each disclosure
 * gate is satisfied within the issuing client's session — the gateway
 * tracks {@code (session, tool_path)} as the unit of disclosure.
 */
public class McpStdioParallelGlobalSticky {
    static final NodeSpec SPEC = NodeSpec.of("mcp.stdio.parallel.global_sticky")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("mcp.stdio.tool.invoked")
            .tags("mcp", "stdio", "parallel", "global-sticky")
            .timeout("90s")
            .retries(1);

    private static final int CONCURRENCY = 10;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.stdio.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.stdio.parallel.global_sticky", "missing upstream context");
            }
            String toolPath = serverId + "/echo";

            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            try {
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENCY; i++) {
                    final int idx = i;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        String sessionId = "test-stdio-parallel-gs-" + ctx.runId() + "-" + idx;
                        String marker = "gs-marker-" + idx;
                        try (TgMcp mcp = new TgMcp(gatewayUrl, sessionId)) {
                            mcp.call("describe_tool", Map.of("tool_path", toolPath));
                            Map<String, Object> inv = mcp.call("invoke_tool",
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

                int hits = 0;
                StringBuilder errs = new StringBuilder();
                for (int i = 0; i < results.size(); i++) {
                    String r = results.get(i);
                    if (("gs-marker-" + i).equals(r)) hits++;
                    else { if (errs.length() > 0) errs.append(" | "); errs.append(r); }
                }

                // After parallel load, prove the gateway can still talk to
                // the worker: describe_mcp_server, list its tools, and fire
                // one final invoke whose response we parse for the
                // expected echo payload (transport=stdio, server=<id>,
                // message=<marker>).
                boolean stillDeployed;
                boolean toolListOk;
                int toolCount;
                boolean finalInvokeOk;
                String finalMarker = "gs-final-" + ctx.runId();
                String finalDump;
                try (TgMcp mcp = new TgMcp(gatewayUrl, "test-stdio-parallel-gs-survive-" + ctx.runId())) {
                    Map<String, Object> desc = mcp.call("describe_mcp_server",
                            Map.of("server_id", serverId));
                    stillDeployed = TgMcp.bool(desc, "deployed_globally")
                            || TgMcp.bool(desc, "deployed");

                    Map<String, Object> browse = mcp.call("browse_active_tools",
                            Map.of("server_id", serverId));
                    Object items = browse.get("items");
                    List<?> tools = items instanceof List<?> l ? l : List.of();
                    toolCount = tools.size();
                    toolListOk = toolCount > 0 && tools.stream().anyMatch(it ->
                            it instanceof Map<?, ?> m && "echo".equals(m.get("tool_name")));

                    mcp.call("describe_tool", Map.of("tool_path", toolPath));
                    Map<String, Object> finalInv = mcp.call("invoke_tool",
                            Map.of("tool_path", toolPath,
                                   "arguments", Map.of("message", finalMarker)));
                    finalDump = finalInv.toString();
                    finalInvokeOk = finalDump.contains(finalMarker)
                            && finalDump.contains("\"transport\"")
                            && finalDump.contains("stdio")
                            && finalDump.contains(serverId);
                }

                boolean allOk = hits == CONCURRENCY;
                return (allOk && stillDeployed && toolListOk && finalInvokeOk
                        ? NodeResult.pass("mcp.stdio.parallel.global_sticky")
                        : NodeResult.fail("mcp.stdio.parallel.global_sticky",
                                "hits=" + hits + "/" + CONCURRENCY
                                        + " stillDeployed=" + stillDeployed
                                        + " toolListOk=" + toolListOk
                                        + " toolCount=" + toolCount
                                        + " finalInvokeOk=" + finalInvokeOk
                                        + (errs.length() == 0 ? "" : " errs=[" + errs + "]")
                                        + (finalInvokeOk ? "" : " finalDump=" + finalDump)))
                        .assertion("all_invocations_round_tripped", allOk)
                        .assertion("server_still_deployed", stillDeployed)
                        .assertion("tools_list_parsed", toolListOk)
                        .assertion("final_invoke_returns_expected_payload", finalInvokeOk)
                        .metric("concurrency", CONCURRENCY)
                        .metric("hits", hits)
                        .metric("toolCount", toolCount);
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
