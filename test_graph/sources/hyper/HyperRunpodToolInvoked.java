///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Calls {@code list-endpoints} on the deployed runpod MCP server end
 * to end (describe + invoke), dumps the full response to a log
 * artifact, and asserts the response shape looks right.
 *
 * <p>Why {@code list-endpoints}: read-only, no side effects, doesn't
 * depend on the operator having any pods or endpoints. A successful
 * response means
 * (a) the npx subprocess started,
 * (b) the API key reached it via the install-time env-init path
 *     (RUNPOD_API_KEY=$X_RUNPOD_KEY skill-manager install ...),
 * (c) runpod's API answered.
 *
 * <p>Validation is intentionally loose. The exact JSON shape under
 * {@code result.content[].text} can shift between runpod-mcp releases,
 * but a successful response always carries an {@code isError=false}
 * (or absent) flag and at least one content item. That's what we
 * check; the artifact log carries the full text for human review.
 */
public class HyperRunpodToolInvoked {
    static final NodeSpec SPEC = NodeSpec.of("hyper.runpod.tool.invoked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.runpod.tools")
            .tags("hyper", "mcp", "runpod", "invoke")
            .timeout("60s")
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String toolPath = ctx.get("hyper.runpod.tools", "toolPath").orElse(null);
            if (gatewayUrl == null || toolPath == null) {
                return NodeResult.fail("hyper.runpod.tool.invoked",
                        "missing upstream context (gateway baseUrl or toolPath)");
            }

            String sessionId = "test-hyper-runpod-invoke-" + ctx.runId();

            String describeDump;
            String invokeDump;
            String describedToolName = "";
            boolean isErrorFlag = false;
            int contentItems = 0;
            String firstContentText = "";

            try (TgMcp mcp = new TgMcp(gatewayUrl, sessionId)) {
                // describe_tool first to satisfy the gateway disclosure gate.
                Map<String, Object> desc = mcp.call("describe_tool",
                        Map.of("tool_path", toolPath));
                describeDump = desc.toString();
                Object name = desc.get("tool_name");
                if (name != null) describedToolName = name.toString();

                // Same TgMcp client (and session id) so the disclosure record
                // applies to the invoke call.
                Map<String, Object> inv = mcp.call("invoke_tool",
                        Map.of("tool_path", toolPath, "arguments", Map.of()));
                invokeDump = inv.toString();

                // Standard MCP CallToolResult shape: { isError: bool, content: [{type, text|json}, ...] }
                Object isErr = inv.get("isError");
                isErrorFlag = Boolean.TRUE.equals(isErr);
                Object content = inv.get("content");
                if (content instanceof java.util.List<?> list) {
                    contentItems = list.size();
                    if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
                        Object t = m.get("text");
                        if (t != null) firstContentText = t.toString();
                    }
                }
            }

            // Persist the full describe + invoke output. This is the artifact
            // the user explicitly asked for — concrete proof the tool ran
            // and what it returned, durable across re-runs.
            try {
                Path log = Procs.logFile(ctx, "list-endpoints");
                StringBuilder body = new StringBuilder();
                body.append("invoked: ").append(toolPath).append("\n");
                body.append("session: ").append(sessionId).append("\n");
                body.append("--- describe_tool ---\n").append(describeDump).append("\n");
                body.append("--- invoke_tool ---\n").append(invokeDump).append("\n");
                body.append("isError: ").append(isErrorFlag).append("\n");
                body.append("contentItems: ").append(contentItems).append("\n");
                body.append("--- first content[].text (truncated) ---\n");
                body.append(firstContentText.length() > 4000
                        ? firstContentText.substring(0, 4000) + "..."
                        : firstContentText)
                        .append("\n");
                Files.writeString(log, body.toString());
            } catch (Exception ignored) {}

            boolean describeOk = "list-endpoints".equals(describedToolName);
            boolean noError = !isErrorFlag;
            boolean hasContent = contentItems > 0;

            String reason;
            if (!describeOk) {
                reason = "describe_tool returned wrong tool_name: " + describedToolName;
            } else if (isErrorFlag) {
                reason = "invoke returned isError=true; first content text: "
                        + (firstContentText.length() > 200
                                ? firstContentText.substring(0, 200) + "..."
                                : firstContentText);
            } else if (!hasContent) {
                reason = "invoke returned no content items";
            } else {
                reason = "";
            }

            NodeResult result = (describeOk && noError && hasContent)
                    ? NodeResult.pass("hyper.runpod.tool.invoked")
                    : NodeResult.fail("hyper.runpod.tool.invoked", reason);

            return result
                    .assertion("describe_returned_list_endpoints", describeOk)
                    .assertion("invoke_no_error", noError)
                    .assertion("invoke_has_content", hasContent)
                    .metric("contentItems", contentItems);
        });
    }
}
