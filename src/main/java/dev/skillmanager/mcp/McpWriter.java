package dev.skillmanager.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.agent.Agent;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers every skill MCP dependency with the virtual MCP gateway, then
 * writes a single {@code virtual-mcp-gateway} entry into each agent's MCP
 * config pointing at the gateway. Agents see one MCP server; the gateway
 * multiplexes tools from all registered downstream servers.
 *
 * <p>The gateway entry name is {@value #GATEWAY_ENTRY}. It replaces any
 * previous entry by that name. Other entries in the agent config are left
 * untouched.
 */
public final class McpWriter {

    public static final String GATEWAY_ENTRY = "virtual-mcp-gateway";
    /** Start marker for the machine-readable install result JSON block. */
    public static final String RESULTS_START = "---MCP-INSTALL-RESULTS-BEGIN---";
    /** End marker for the machine-readable install result JSON block. */
    public static final String RESULTS_END = "---MCP-INSTALL-RESULTS-END---";

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final GatewayConfig gateway;
    private final GatewayClient client;

    public McpWriter(GatewayConfig gateway) {
        this.gateway = gateway;
        this.client = new GatewayClient(gateway);
    }

    /**
     * Register each skill's MCP deps with the gateway. Auto-deploys where
     * possible (global / global-sticky scope with no unsatisfied required
     * init). Returns a per-server descriptor suitable for structured CLI
     * output — both humans and invoking agents read the results.
     */
    public List<InstallResult> registerAll(List<Skill> skills) throws IOException {
        if (!client.ping()) {
            Log.warn("gateway not reachable at %s — skipping dynamic registration", gateway.baseUrl());
            Log.warn("start the gateway: python -m gateway.server --config <cfg> --host 127.0.0.1 --port 8080");
            return List.of();
        }
        Map<String, McpDependency> merged = new LinkedHashMap<>();
        for (Skill s : skills) for (McpDependency d : s.mcpDependencies()) merged.putIfAbsent(d.name(), d);

        java.util.List<InstallResult> results = new java.util.ArrayList<>();
        for (McpDependency d : merged.values()) {
            results.add(installOne(d));
        }
        return results;
    }

    /**
     * Print a human-readable summary followed by a JSON block wrapped in
     * {@link #RESULTS_START}/{@link #RESULTS_END} markers, so invoking agents
     * can parse the structured outcome out of the skill-manager CLI output.
     */
    public void printInstallResults(List<InstallResult> results) {
        if (results == null || results.isEmpty()) return;
        for (InstallResult r : results) {
            Log.info("mcp: %s", r.message());
        }
        try {
            System.out.println(RESULTS_START);
            System.out.println(json.writeValueAsString(results));
            System.out.println(RESULTS_END);
        } catch (IOException e) {
            Log.warn("failed to emit install results JSON: %s", e.getMessage());
        }
    }

    /** Install-time decision for one dependency. See ticket: deploy-per-session.md. */
    private InstallResult installOne(McpDependency dep) {
        String scope = dep.defaultScope();
        List<String> missing = dep.missingRequiredInit();
        boolean canAutoDeploy = !McpDependency.SCOPE_SESSION.equals(scope) && missing.isEmpty();

        // Idempotency: skip expensive re-registration when the gateway is
        // already in the state we want.
        try {
            var existing = client.describe(dep.name());
            if (existing.isPresent() && scope.equals(existing.get().defaultScope())) {
                var state = existing.get();
                if (McpDependency.SCOPE_SESSION.equals(scope)) {
                    Log.ok("gateway: %s already registered (scope=session)", dep.name());
                    return InstallResult.registered(dep.name(), scope,
                            "already registered (session scope — agent deploys per session)");
                }
                if (state.deployed()) {
                    Log.ok("gateway: %s already deployed (%s)", dep.name(), scope);
                    return InstallResult.deployed(dep.name(), scope, "already deployed");
                }
                if (!missing.isEmpty()) {
                    Log.warn("gateway: %s registered but not deployed — required init: %s",
                            dep.name(), missing);
                    return InstallResult.awaitingInit(dep.name(), scope, dep, missing);
                }
                // Registered, same scope, can deploy — fall through to register+deploy.
            }
        } catch (IOException e) {
            Log.warn("gateway: describe %s failed: %s — continuing with register", dep.name(), e.getMessage());
        }

        try {
            var r = client.register(dep, canAutoDeploy);
            Log.ok("gateway: registered %s (%s, scope=%s)", r.serverId(), dep.load().type(), scope);
            if (r.deployError() != null) {
                Log.warn("gateway: deploy failed for %s: %s", dep.name(), r.deployError());
                return InstallResult.error(dep.name(), scope, r.deployError());
            }
            if (r.deployed()) {
                return InstallResult.deployed(dep.name(), scope, "registered and deployed");
            }
            if (!missing.isEmpty()) {
                Log.warn("gateway: %s not deployed — required init: %s", dep.name(), missing);
                return InstallResult.awaitingInit(dep.name(), scope, dep, missing);
            }
            return InstallResult.registered(dep.name(), scope,
                    "registered (session scope — agent deploys per session)");
        } catch (IOException e) {
            Log.warn("gateway: failed to register %s: %s", dep.name(), e.getMessage());
            return InstallResult.error(dep.name(), scope, e.getMessage());
        }
    }

    /** Outcome of a {@link #writeAgentEntry(Agent)} call. */
    public enum ConfigChange {
        /** Entry was freshly added. */
        ADDED,
        /** Entry existed but pointed at a different URL; rewritten. */
        UPDATED,
        /** Entry already present with the right URL — no write happened. */
        UNCHANGED,
        /** Agent format not recognized; nothing touched. */
        SKIPPED
    }

    /** Write the single virtual-mcp-gateway entry into the agent's MCP config. */
    public ConfigChange writeAgentEntry(Agent agent) throws IOException {
        return switch (agent.mcpConfigFormat()) {
            case "claude" -> writeClaude(agent.mcpConfigPath());
            case "codex-toml" -> writeCodexToml(agent.mcpConfigPath());
            default -> {
                Log.warn("unknown MCP format for agent %s", agent.id());
                yield ConfigChange.SKIPPED;
            }
        };
    }

    private ConfigChange writeClaude(Path file) throws IOException {
        Fs.ensureDir(file.getParent());
        Map<String, Object> root;
        boolean fileExisted = Files.isRegularFile(file);
        if (fileExisted) {
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = json.readValue(file.toFile(), Map.class);
            root = loaded != null ? loaded : new LinkedHashMap<>();
        } else {
            root = new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) root.computeIfAbsent("mcpServers", k -> new LinkedHashMap<>());

        String desiredUrl = gateway.mcpEndpoint().toString();
        Object existing = servers.get(GATEWAY_ENTRY);
        boolean entryExisted = existing instanceof Map<?, ?>;
        if (entryExisted) {
            Map<?, ?> em = (Map<?, ?>) existing;
            if ("http".equals(em.get("type")) && desiredUrl.equals(em.get("url"))) {
                return ConfigChange.UNCHANGED;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "http");
        entry.put("url", desiredUrl);
        servers.put(GATEWAY_ENTRY, entry);
        json.writeValue(file.toFile(), root);
        Log.ok("claude: pointed %s → %s", GATEWAY_ENTRY, desiredUrl);
        return entryExisted ? ConfigChange.UPDATED : ConfigChange.ADDED;
    }

    private ConfigChange writeCodexToml(Path file) throws IOException {
        Fs.ensureDir(file.getParent());
        String existing = Files.isRegularFile(file) ? Files.readString(file) : "";
        String tableHeader = "[mcp_servers." + GATEWAY_ENTRY.replace('-', '_') + "]";
        boolean tableExisted = existing.contains(tableHeader);
        boolean urlOk = tableExisted && existing.contains("url = \"" + gateway.mcpEndpoint() + "\"");
        if (urlOk) return ConfigChange.UNCHANGED;
        String desiredTable = tableHeader + "\nurl = \"" + gateway.mcpEndpoint() + "\"\n";
        String rebuilt = replaceOrAppendTable(existing, tableHeader, desiredTable);
        Files.writeString(file, rebuilt);
        Log.ok("codex: pointed %s → %s", GATEWAY_ENTRY, gateway.mcpEndpoint());
        return tableExisted ? ConfigChange.UPDATED : ConfigChange.ADDED;
    }

    public void removeAgentEntry(Agent agent) throws IOException {
        Path file = agent.mcpConfigPath();
        if (!Files.isRegularFile(file)) return;
        switch (agent.mcpConfigFormat()) {
            case "claude" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = json.readValue(file.toFile(), Map.class);
                Object servers = root.get("mcpServers");
                if (servers instanceof Map<?, ?> m && m.remove(GATEWAY_ENTRY) != null) {
                    json.writeValue(file.toFile(), root);
                    Log.ok("claude: removed %s entry", GATEWAY_ENTRY);
                }
            }
            case "codex-toml" -> {
                String existing = Files.readString(file);
                String header = "[mcp_servers." + GATEWAY_ENTRY.replace('-', '_') + "]";
                String rebuilt = replaceOrAppendTable(existing, header, "");
                if (!rebuilt.equals(existing)) {
                    Files.writeString(file, rebuilt);
                    Log.ok("codex: removed %s table", GATEWAY_ENTRY);
                }
            }
            default -> {}
        }
    }

    /** Replace the TOML table that begins with {@code header} (up to the next table or EOF) with {@code replacement}. */
    private String replaceOrAppendTable(String source, String header, String replacement) {
        int start = source.indexOf(header);
        if (start < 0) {
            StringBuilder sb = new StringBuilder(source);
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append(replacement);
            return sb.toString();
        }
        int end = source.length();
        int search = start + header.length();
        while (search < source.length()) {
            int nl = source.indexOf('\n', search);
            if (nl < 0) break;
            int nextLineStart = nl + 1;
            if (nextLineStart < source.length() && source.charAt(nextLineStart) == '[') {
                end = nextLineStart;
                break;
            }
            search = nextLineStart;
        }
        return source.substring(0, start) + replacement + source.substring(end);
    }
}
