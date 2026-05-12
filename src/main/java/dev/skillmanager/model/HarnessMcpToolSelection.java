package dev.skillmanager.model;

import java.util.List;

/**
 * One {@code [[mcp_tools]]} row inside a {@code harness.toml} (#47).
 * Names the gateway-registered MCP server the harness's agent
 * should see, optionally restricted to a {@code tools} allowlist.
 *
 * <p>{@code tools = null} (manifest omits the key) means "expose every
 * tool the server advertises"; an empty list is preserved as
 * "expose nothing" so authors can declare a server purely for
 * documentation purposes without granting any tools.
 */
public record HarnessMcpToolSelection(String server, List<String> tools) {
    public HarnessMcpToolSelection {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("HarnessMcpToolSelection.server must not be blank");
        }
        tools = tools == null ? null : List.copyOf(tools);
    }

    /** True when no tool filter is set (every tool exposed). */
    public boolean exposesAllTools() { return tools == null; }
}
