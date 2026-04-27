///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enumerates runpod's tools through the gateway and dumps the full
 * response to a log artifact.
 *
 * <p>Asserts:
 * <ul>
 *   <li>{@code browse_active_tools(server_id="runpod")} returns at
 *       least one tool — the runpod-mcp server actually started and
 *       handed its tool surface to the gateway over stdio.</li>
 *   <li>The expected read-only entry {@code list-endpoints} is in the
 *       returned set. That's the canary tool {@link HyperRunpodToolInvoked}
 *       calls in the next node.</li>
 * </ul>
 *
 * <p>The tool path is published as {@code toolPath} for the invoked
 * node downstream — typically {@code runpod/list-endpoints}, but
 * sourced from the live gateway response so a future runpod-mcp
 * release that changes the path scheme doesn't quietly break the
 * graph.
 */
public class HyperRunpodTools {
    static final String SERVER_ID = "runpod";
    static final String CANARY_TOOL = "list-endpoints";

    static final NodeSpec SPEC = NodeSpec.of("hyper.runpod.tools")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.runpod.deployed")
            .tags("hyper", "mcp", "runpod", "tools")
            .timeout("30s")
            .output("toolPath", "string")
            .output("toolCount", "integer");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (gatewayUrl == null) {
                return NodeResult.fail("hyper.runpod.tools",
                        "missing gateway.up baseUrl");
            }

            List<String> toolNames = new ArrayList<>();
            String canaryPath = null;
            String rawDump;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-hyper-runpod-tools")) {
                Map<String, Object> res = mcp.call("browse_active_tools",
                        Map.of("server_id", SERVER_ID, "limit", 200));
                rawDump = res.toString();
                Object items = res.get("items");
                if (items instanceof List<?> list) {
                    for (Object it : list) {
                        if (!(it instanceof Map<?, ?> m)) continue;
                        Object name = m.get("tool_name");
                        Object path = m.get("path");
                        if (name instanceof String n) toolNames.add(n);
                        if (canaryPath == null
                                && CANARY_TOOL.equals(name)
                                && path instanceof String p) {
                            canaryPath = p;
                        }
                    }
                }
            }

            // Persist the full browse response so a failure further down
            // the chain can be triaged from the artifact, not from a
            // re-run.
            try {
                Path log = Procs.logFile(ctx, "browse-active-tools");
                Files.writeString(log,
                        "browse_active_tools(server_id=" + SERVER_ID + ") returned\n"
                                + "tools (" + toolNames.size() + "): " + toolNames + "\n"
                                + "canary_path: " + canaryPath + "\n"
                                + "--- raw ---\n" + rawDump + "\n");
            } catch (Exception ignored) {}

            boolean hasTools = !toolNames.isEmpty();
            boolean hasCanary = toolNames.contains(CANARY_TOOL);

            String reason;
            if (!hasTools) {
                reason = "runpod returned no tools — likely the npx subprocess "
                        + "failed to start or the API key was rejected; check the "
                        + "gateway log for runpod-mcp output";
            } else if (!hasCanary) {
                reason = CANARY_TOOL + " missing from runpod tool surface; "
                        + "got: " + toolNames;
            } else {
                reason = "";
            }

            NodeResult result = (hasTools && hasCanary)
                    ? NodeResult.pass("hyper.runpod.tools")
                    : NodeResult.fail("hyper.runpod.tools", reason);

            return result
                    .assertion("runpod_returned_tools", hasTools)
                    .assertion("canary_tool_present", hasCanary)
                    .metric("toolCount", toolNames.size())
                    .publish("toolPath",
                            canaryPath != null ? canaryPath : SERVER_ID + "/" + CANARY_TOOL)
                    .publish("toolCount", Integer.toString(toolNames.size()));
        });
    }
}
