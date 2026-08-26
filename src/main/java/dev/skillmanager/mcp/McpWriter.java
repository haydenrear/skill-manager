package dev.skillmanager.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
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
    /**
     * Where {@link McpRegistrationLock} is written, or null when this writer was
     * built without a home — the agent-config half ({@link #writeAgentEntry})
     * needs no home, and a writer that only does that must not be forced to
     * name one.
     */
    private final Path homeRoot;

    public McpWriter(GatewayConfig gateway) {
        this(gateway, (Path) null);
    }

    /**
     * A writer that also RECORDS what it registered, into
     * {@code <homeRoot>/mcp-lock.json}.
     *
     * <p>Every caller that reaches {@link #registerAll} should use this one: the
     * spec digest is computed on the way past regardless, and the difference
     * between recording it and dropping it is the difference between an
     * artifact class that can answer "did my declaration move" offline and one
     * that can only answer "a file mentions this name".
     */
    public McpWriter(GatewayConfig gateway, Path homeRoot) {
        this.gateway = gateway;
        this.client = new GatewayClient(gateway);
        this.homeRoot = homeRoot == null ? null : homeRoot.toAbsolutePath().normalize();
    }

    /**
     * The store form of {@link #McpWriter(GatewayConfig, Path)}.
     *
     * <p>Was deprecated as a no-op after runtime-tool bootstrap moved to
     * {@code ToolInstallRecorder}. It is meaningful again for a different
     * reason: the store names the home this writer records its registrations
     * into.
     */
    public McpWriter(GatewayConfig gateway, SkillStore store) {
        this(gateway, store == null ? null : store.root());
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
            Log.warn("start the gateway: python -m gateway.server --config <cfg> --host 127.0.0.1 --port 51717");
            return List.of();
        }
        Map<String, McpDependency> merged = new LinkedHashMap<>();
        // Which unit's manifest each merged dep came from. Recorded beside the
        // digest so a row in mcp-lock.json names the declaration it was built
        // from — without it, "recompute the payload and compare" has nowhere to
        // start. First declarer wins, matching the putIfAbsent merge below it.
        Map<String, String> declaredBy = new LinkedHashMap<>();
        for (Skill s : skills) {
            for (McpDependency d : s.mcpDependencies()) {
                merged.putIfAbsent(d.name(), d);
                declaredBy.putIfAbsent(d.name(), s.name());
            }
        }

        java.util.List<InstallResult> results = new java.util.ArrayList<>();
        for (McpDependency d : merged.values()) {
            results.add(installOne(d, declaredBy.get(d.name())));
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

    /**
     * Install-time decision for one dependency. See ticket:
     * deploy-per-session.md.
     *
     * <p>Runtime tools the gateway needs ({@code uv}, {@code npx},
     * {@code docker}) are guaranteed available before this method runs
     * by {@code ToolInstallRecorder} executing the {@code Section.TOOLS}
     * group of the install plan.
     *
     * <p><b>Init from environment:</b> for each {@code init_schema}
     * field declared by the dep, this method reads {@link System#getenv}
     * for an env var with the same name. When found (non-blank), the
     * value is folded into the registration's {@code initialization_params}
     * and counts toward the auto-deploy decision — so a manifest
     * declaring {@code RUNPOD_API_KEY} as required+secret can still
     * auto-deploy at install time when the operator runs
     * {@code RUNPOD_API_KEY=$X_RUNPOD_KEY skill-manager install ...}.
     * Manifest-supplied {@code initialization_params} still apply; env
     * values override them.
     */
    private InstallResult installOne(McpDependency dep, String declaringUnit) {
        String scope = dep.defaultScope();

        // Pull init values from the install process's environment. Any
        // init_schema field whose name matches an env var fills its slot;
        // everything else stays missing.
        Map<String, Object> envInit = new LinkedHashMap<>();
        for (var f : dep.initSchema()) {
            String v = System.getenv(f.name());
            if (v != null && !v.isBlank()) envInit.put(f.name(), v);
        }

        // Effective missing = required + no-default + not in envInit.
        List<String> missing = new java.util.ArrayList<>();
        for (var f : dep.initSchema()) {
            if (!f.required()) continue;
            if (f.defaultValue() != null) continue;
            if (envInit.containsKey(f.name())) continue;
            missing.add(f.name());
        }

        boolean canAutoDeploy = !McpDependency.SCOPE_SESSION.equals(scope) && missing.isEmpty();
        if (!envInit.isEmpty()) {
            Log.info("gateway: %s init from env: %s", dep.name(), envInit.keySet());
        }

        // Computed ONCE, here, rather than inside the idempotency branch it used
        // to live in. It is the payload this home is asking the gateway to hold,
        // so it describes what this home wants whether or not a describe call
        // succeeds, whether or not the gateway already had it, and whether or
        // not the register ends up being skipped. That is precisely the fact
        // `mcp-lock.json` needs, and the old placement made it available only on
        // the one path where it was about to be thrown away.
        String desiredDigest = GatewayClient.specDigest(
                client.registerPayload(dep, canAutoDeploy, envInit));

        // Idempotency: skip expensive re-registration when the gateway is
        // already in the state we want — same scope AND same spec_digest.
        // Digest drift (image bump, init_schema edit, transport change)
        // forces re-register so the gateway runs the new spec.
        try {
            var existing = client.describe(dep.name());
            if (existing.isPresent() && scope.equals(existing.get().defaultScope())) {
                var state = existing.get();
                boolean digestMatches = state.specDigest() != null
                        && state.specDigest().equals(desiredDigest);
                if (digestMatches) {
                    if (McpDependency.SCOPE_SESSION.equals(scope)) {
                        Log.ok("gateway: %s already registered (scope=session)", dep.name());
                        recordRegistration(dep, declaringUnit, scope, desiredDigest);
                        return InstallResult.registered(dep.name(), scope,
                                "already registered (session scope — agent deploys per session)");
                    }
                    if (state.deployed()) {
                        Log.ok("gateway: %s already deployed (%s)", dep.name(), scope);
                        recordRegistration(dep, declaringUnit, scope, desiredDigest);
                        return InstallResult.deployed(dep.name(), scope, "already deployed");
                    }
                    if (!missing.isEmpty()) {
                        Log.warn("gateway: %s registered but not deployed — required init: %s",
                                dep.name(), missing);
                        recordRegistration(dep, declaringUnit, scope, desiredDigest);
                        return InstallResult.awaitingInit(dep.name(), scope, dep, missing);
                    }
                    // Registered, same scope, same digest, can deploy — fall through.
                } else {
                    Log.info("gateway: %s spec changed — re-registering", dep.name());
                }
            }
        } catch (IOException e) {
            Log.warn("gateway: describe %s failed: %s — continuing with register", dep.name(), e.getMessage());
        }

        try {
            var r = client.register(dep, canAutoDeploy, envInit);
            Log.ok("gateway: registered %s (%s, scope=%s)", r.serverId(), dep.load().type(), scope);
            if (r.deployError() != null) {
                Log.warn("gateway: deploy failed for %s: %s", dep.name(), r.deployError());
                // Deliberately NOT recorded: the payload was posted but the
                // server did not come up, and a row here means "this home's
                // declaration is what the gateway was asked to hold". Writing
                // one for a failed deploy would let the next pass compare
                // equal and conclude there is nothing to do.
                return InstallResult.error(dep.name(), scope, r.deployError());
            }
            recordRegistration(dep, declaringUnit, scope, desiredDigest);
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

    /**
     * Persist what this home asked the gateway to hold for {@code dep}.
     *
     * <p>Graded {@link Fingerprint.Kind#DECLARED}, and the grade is asserted
     * here because this is the only place that knows what went into the digest.
     * See {@link McpRegistrationLock} for why {@code declared} rather than
     * {@code resolved}, and for why the gateway's own digest is not copied in
     * beside it.
     *
     * <p>Failing to write is a warning and never an error: this file is
     * EVIDENCE about a registration, never an input to making one, so a
     * read-only or full home must not turn a successful register into a failed
     * install.
     */
    private void recordRegistration(McpDependency dep, String declaringUnit, String scope, String digest) {
        if (homeRoot == null || digest == null || digest.isBlank()) return;
        try {
            McpRegistrationLock.read(homeRoot)
                    .with(McpRegistrationLock.Entry.of(dep.name(), declaringUnit, scope,
                            Fingerprint.declared(digest, McpRegistrationLock.BASIS)))
                    .write(homeRoot);
        } catch (IOException e) {
            Log.warn("gateway: could not record the spec digest for %s in %s: %s",
                    dep.name(), McpRegistrationLock.FILENAME, e.getMessage());
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
            case "gemini-json" -> writeGemini(agent.mcpConfigPath());
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
        Log.detail("✓ claude: pointed %s → %s", GATEWAY_ENTRY, desiredUrl);
        return entryExisted ? ConfigChange.UPDATED : ConfigChange.ADDED;
    }

    private ConfigChange writeGemini(Path file) throws IOException {
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
            if (desiredUrl.equals(em.get("httpUrl"))) {
                return ConfigChange.UNCHANGED;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("httpUrl", desiredUrl);
        servers.put(GATEWAY_ENTRY, entry);
        json.writeValue(file.toFile(), root);
        Log.detail("✓ gemini: pointed %s -> %s", GATEWAY_ENTRY, desiredUrl);
        return entryExisted ? ConfigChange.UPDATED : ConfigChange.ADDED;
    }

    private ConfigChange writeCodexToml(Path file) throws IOException {
        Fs.ensureDir(file.getParent());
        String existing = Files.isRegularFile(file) ? Files.readString(file) : "";
        String tableHeader = "[mcp_servers." + GATEWAY_ENTRY.replace('-', '_') + "]";
        boolean tableExisted = existing.contains(tableHeader);
        // Scope the url check to the gateway table only — a substring match
        // against the whole file would succeed on an unrelated entry that
        // happens to carry the same URL, even when our table is stale.
        String tableBody = tableExisted ? sliceTable(existing, tableHeader) : "";
        boolean urlOk = tableExisted && tableBody.contains("url = \"" + gateway.mcpEndpoint() + "\"");
        if (urlOk) return ConfigChange.UNCHANGED;
        String desiredTable = tableHeader + "\nurl = \"" + gateway.mcpEndpoint() + "\"\n";
        String rebuilt = replaceOrAppendTable(existing, tableHeader, desiredTable);
        Files.writeString(file, rebuilt);
        Log.detail("✓ codex: pointed %s → %s", GATEWAY_ENTRY, gateway.mcpEndpoint());
        return tableExisted ? ConfigChange.UPDATED : ConfigChange.ADDED;
    }

    /** Return the substring of {@code source} covering the TOML table that begins with {@code header}, up to the next table or EOF. Empty if not found. */
    private String sliceTable(String source, String header) {
        int start = source.indexOf(header);
        if (start < 0) return "";
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
        return source.substring(start, end);
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
            case "gemini-json" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = json.readValue(file.toFile(), Map.class);
                Object servers = root.get("mcpServers");
                if (servers instanceof Map<?, ?> m && m.remove(GATEWAY_ENTRY) != null) {
                    json.writeValue(file.toFile(), root);
                    Log.ok("gemini: removed %s entry", GATEWAY_ENTRY);
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
