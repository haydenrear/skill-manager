//DEPS io.modelcontextprotocol.sdk:mcp:1.1.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS org.slf4j:slf4j-simple:2.0.16

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * Thin MCP test helper used from test_graph smoke nodes.
 *
 * <p>Test nodes construct one of these per-call, inject an {@code x-session-id}
 * so the gateway treats multiple CLI processes as the same agent session, fire
 * a tool call, and close. No state outlives a node.
 *
 * <p>Intentionally independent of {@code dev.skillmanager.mcp.*} — we want the
 * test graph to talk MCP exactly like a third-party agent would, not reach
 * into skill-manager internals.
 */
public final class TgMcp implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;

    public TgMcp(String gatewayBaseUrl, String sessionId) {
        var transport = HttpClientStreamableHttpTransport.builder(stripSlash(gatewayBaseUrl))
                .endpoint("/mcp")
                .openConnectionOnStartup(false)
                .resumableStreams(false)
                .customizeRequest(req -> {
                    if (sessionId != null && !sessionId.isBlank()) {
                        req.header("x-session-id", sessionId);
                    }
                })
                .build();
        this.client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
        this.client.initialize();
    }

    /** Call a gateway virtual tool ({@code browse_mcp_servers}, {@code deploy_mcp_server}, …). */
    public Map<String, Object> call(String tool, Map<String, Object> args) {
        McpSchema.CallToolRequest req = McpSchema.CallToolRequest.builder()
                .name(tool)
                .arguments(args == null ? Map.of() : args)
                .build();
        McpSchema.CallToolResult result = client.callTool(req);
        if (result.structuredContent() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result.structuredContent();
            return m;
        }
        StringBuilder sb = new StringBuilder();
        for (var c : result.content()) {
            if (c instanceof McpSchema.TextContent text) sb.append(text.text());
        }
        if (sb.length() == 0) return Map.of();
        try {
            return JSON.readValue(sb.toString(), new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", sb.toString());
        }
    }

    @Override
    public void close() {
        try { client.closeGracefully(); } catch (Exception ignored) {}
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Convenience: coerce a nested JSON path to a boolean (for asserting flags). */
    public static boolean bool(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> cm)) return false;
            cur = cm.get(key);
        }
        return Boolean.TRUE.equals(cur);
    }

    /** Convenience: walk a map with string keys. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> cm)) return Map.of();
            cur = cm.get(key);
        }
        return cur instanceof Map ? (Map<String, Object>) cur : Map.of();
    }
}
