///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the ToolDependency / EnsureTool refactor end-to-end, on the
 * MCP side specifically.
 *
 * <p>After {@link McpToolLoadsInstalled} has installed the fixture
 * declaring npm + uv + docker MCP loads, this node asserts that the
 * install pipeline:
 *
 * <ol>
 *   <li>Bundled the runtime tools into {@code $SKILL_MANAGER_HOME/pm/}
 *       — {@code pm/uv/current/bin/uv} and {@code pm/node/current/bin/npx}
 *       must be executable. The npm and uv MCP loads are the canonical
 *       paths into the bundling code; if either bundle is missing, the
 *       refactor regressed.</li>
 *   <li>Made the docker presence call (external; not bundled). We don't
 *       require docker to be installed in the test env, but we record
 *       which side of the WARN/INFO ladder we landed on so the report
 *       captures it.</li>
 *   <li>Registered all three MCP servers with the gateway — the same
 *       install pipeline that produced the bundles must have wired the
 *       registrations into {@code browse_mcp_servers}. Each entry must
 *       carry the right load-type label.</li>
 * </ol>
 *
 * <p>Smoke today already exercises pip + npm CLI bundling via the
 * umbrella fixture's transitive sub-skills. This node is the analogous
 * check for the MCP side: prove that an MCP load is enough to drive
 * the same bundling pipeline, without piggybacking on a CLI dep.
 */
public class McpToolLoadsBundled {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.loads.bundled")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("mcp.tool.loads.installed", "gateway.up")
            .tags("mcp", "tool-loads", "bundled")
            .timeout("30s");

    private static final List<String> EXPECTED_SERVER_IDS = List.of(
            "mcp-tool-load-npm",
            "mcp-tool-load-uv",
            "mcp-tool-load-docker");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || gatewayUrl == null) {
                return NodeResult.fail("mcp.tool.loads.bundled",
                        "missing upstream context (home or gateway.up baseUrl)");
            }

            // ---- bundled runtimes on disk under $SKILL_MANAGER_HOME/pm/ ----
            Path uvBin = Path.of(home, "pm", "uv", "current", "bin", "uv");
            Path npxBin = Path.of(home, "pm", "node", "current", "bin", "npx");
            Path nodeBin = Path.of(home, "pm", "node", "current", "bin", "node");
            Path npmBin = Path.of(home, "pm", "node", "current", "bin", "npm");

            boolean uvBundled = Files.isExecutable(uvBin);
            boolean npxBundled = Files.isExecutable(npxBin);
            boolean nodeBundled = Files.isExecutable(nodeBin);
            boolean npmBundled = Files.isExecutable(npmBin);

            // docker is external; record presence but never assert installed.
            boolean dockerOnPath = isOnPath("docker");

            // ---- gateway registry: all three MCP servers must be visible ----
            Set<String> registeredIds = new LinkedHashSet<>();
            Map<String, String> loadTypeBySid = new java.util.LinkedHashMap<>();
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-mcp-tool-loads-bundled")) {
                Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
                Object items = res.get("items");
                if (items instanceof List<?> list) {
                    for (Object it : list) {
                        if (!(it instanceof Map<?, ?> m)) continue;
                        Object sid = m.get("server_id");
                        if (!(sid instanceof String s)) continue;
                        if (!EXPECTED_SERVER_IDS.contains(s)) continue;
                        registeredIds.add(s);
                        // Some gateway builds include the load type in browse;
                        // describe always carries it. Try browse first, fall
                        // back to describe-per-server below.
                        Object lt = m.get("load_type");
                        if (lt != null) loadTypeBySid.put(s, lt.toString());
                    }
                }
                // Fill in any missing load types from describe_mcp_server.
                // The gateway's describe_server payload now carries a flat
                // `load_type` field (npm / uv / docker / binary / shell) —
                // see registry.py describe_server.
                for (String s : registeredIds) {
                    if (loadTypeBySid.containsKey(s)) continue;
                    try {
                        Map<String, Object> desc = mcp.call("describe_mcp_server",
                                Map.of("server_id", s));
                        Object lt = desc.get("load_type");
                        if (lt != null) {
                            loadTypeBySid.put(s, lt.toString());
                        } else {
                            // Fall back to load_spec.type for older gateway builds.
                            Object spec = desc.get("load_spec");
                            if (spec instanceof Map<?, ?> sm) {
                                Object t = sm.get("type");
                                if (t != null) loadTypeBySid.put(s, t.toString());
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            boolean allRegistered = registeredIds.containsAll(EXPECTED_SERVER_IDS);
            boolean npmLoadType = "npm".equals(loadTypeBySid.get("mcp-tool-load-npm"));
            boolean uvLoadType = "uv".equals(loadTypeBySid.get("mcp-tool-load-uv"));
            boolean dockerLoadType = "docker".equals(loadTypeBySid.get("mcp-tool-load-docker"));

            // ---- final pass/fail ----
            // Bundles and registrations are required. docker on PATH is
            // recorded but not required (the planner emits WARN, not BLOCK).
            boolean pass = uvBundled && npxBundled
                    && allRegistered
                    && npmLoadType && uvLoadType && dockerLoadType;

            String reason = pass ? "" : String.join("; ", List.of(
                    "uvBundled=" + uvBundled,
                    "npxBundled=" + npxBundled,
                    "registered=" + registeredIds,
                    "loadTypes=" + loadTypeBySid));

            NodeResult result = pass
                    ? NodeResult.pass("mcp.tool.loads.bundled")
                    : NodeResult.fail("mcp.tool.loads.bundled", reason);

            return result
                    .assertion("uv_bundled_under_pm", uvBundled)
                    .assertion("node_bundled_under_pm", nodeBundled)
                    .assertion("npm_bundled_under_pm", npmBundled)
                    .assertion("npx_bundled_under_pm", npxBundled)
                    .assertion("docker_on_path_recorded", true)
                    .metric("dockerOnPath", dockerOnPath ? 1 : 0)
                    .assertion("npm_mcp_registered",
                            registeredIds.contains("mcp-tool-load-npm"))
                    .assertion("uv_mcp_registered",
                            registeredIds.contains("mcp-tool-load-uv"))
                    .assertion("docker_mcp_registered",
                            registeredIds.contains("mcp-tool-load-docker"))
                    .assertion("npm_mcp_load_type_npm", npmLoadType)
                    .assertion("uv_mcp_load_type_uv", uvLoadType)
                    .assertion("docker_mcp_load_type_docker", dockerLoadType);
        });
    }

    private static boolean isOnPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(part, tool);
            if (Files.isExecutable(candidate)) return true;
        }
        return false;
    }
}
