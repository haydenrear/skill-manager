package dev.skillmanager.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * Native Java MCP client for talking to the virtual MCP gateway.
 *
 * <p>Uses {@code io.modelcontextprotocol.sdk:mcp} with the
 * {@link HttpClientStreamableHttpTransport}. One short-lived session per
 * operation — skill-manager commands are one-shot, so holding a session
 * open buys nothing and complicates shutdown.
 */
public final class GatewayMcpClient implements AutoCloseable {

    private final McpSyncClient client;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public GatewayMcpClient(GatewayConfig gateway) {
        this(gateway, null);
    }

    /**
     * @param sessionId optional {@code x-session-id} to inject on every request.
     *                  Letting the caller pin this lets two CLI invocations (each
     *                  a fresh process, each with its own MCP handshake) share
     *                  the gateway's disclosure state — {@code describe-tool}
     *                  in one call, {@code invoke} in the next.
     */
    public GatewayMcpClient(GatewayConfig gateway, String sessionId) {
        String base = stripSlash(gateway.baseUrl().toString());
        var builder = HttpClientStreamableHttpTransport.builder(base)
                .endpoint("/mcp")
                .openConnectionOnStartup(false)
                .resumableStreams(false);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.customizeRequest(req -> req.header("x-session-id", sessionId));
        }
        var transport = builder.build();
        this.client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
        this.client.initialize();
    }

    public McpSchema.ListToolsResult listTools() {
        return client.listTools();
    }

    public McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        McpSchema.CallToolRequest req = McpSchema.CallToolRequest.builder()
                .name(tool)
                .arguments(args == null ? Map.of() : args)
                .build();
        return client.callTool(req);
    }

    /**
     * Extract the tool's structured payload if present, else concatenate any
     * text content — whichever the downstream server produced.
     */
    public Object extractPayload(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) return result.structuredContent();
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent text) sb.append(text.text());
        }
        String raw = sb.toString();
        if (raw.isEmpty()) return Map.of();
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    public String prettyPrint(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {}
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
