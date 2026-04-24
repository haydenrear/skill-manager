package dev.skillmanager.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** POSTs skill MCP dependencies to the virtual MCP gateway. */
public final class GatewayClient {

    private final GatewayConfig config;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public GatewayClient(GatewayConfig config) {
        this.config = config;
    }

    public boolean ping() {
        try {
            URI health = URI.create(stripSlash(config.baseUrl().toString()) + "/health");
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    public RegisterResult register(McpDependency dep, boolean deploy) throws IOException {
        Map<String, Object> body = registerPayload(dep, deploy);
        String payload = json.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(config.serversEndpoint())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("gateway register failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return new RegisterResult(
                    (String) parsed.getOrDefault("server_id", dep.name()),
                    Boolean.TRUE.equals(parsed.get("registered")),
                    Boolean.TRUE.equals(parsed.get("deployed")),
                    (String) parsed.get("transport"),
                    (String) parsed.getOrDefault("default_scope", dep.defaultScope()),
                    (String) parsed.get("scope"),
                    (String) parsed.get("deploy_error")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("gateway register interrupted", e);
        }
    }

    /** Describe a registered server. Returns empty if the gateway has no such entry. */
    public Optional<ServerState> describe(String serverId) throws IOException {
        URI uri = URI.create(stripSlash(config.serversEndpoint().toString()) + "/" + serverId);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return Optional.empty();
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("gateway describe failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return Optional.of(ServerState.fromMap(parsed));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("gateway describe interrupted", e);
        }
    }

    /** Exposed for tests + digest comparison. */
    public Map<String, Object> registerPayload(McpDependency dep, boolean deploy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server_id", dep.name());
        body.put("display_name", dep.displayName());
        body.put("description", dep.description());
        body.put("load_spec", serializeLoad(dep.load()));
        body.put("init_schema", serializeSchema(dep.initSchema()));
        if (!dep.initializationParams().isEmpty()) body.put("initialization_params", dep.initializationParams());
        if (dep.idleTimeoutSeconds() != null) body.put("idle_timeout_seconds", dep.idleTimeoutSeconds());
        body.put("default_scope", dep.defaultScope());
        body.put("deploy", deploy);
        return body;
    }

    public boolean unregister(String serverId) throws IOException {
        URI uri = URI.create(stripSlash(config.serversEndpoint().toString()) + "/" + serverId);
        Log.debug("DELETE %s", uri);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return false;
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("unregister failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unregister interrupted", e);
        }
    }

    private Map<String, Object> serializeLoad(McpDependency.LoadSpec load) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (load) {
            case McpDependency.DockerLoad d -> {
                out.put("type", "docker");
                out.put("image", d.image());
                out.put("pull", d.pull());
                if (d.containerPlatform() != null) out.put("platform", d.containerPlatform());
                if (!d.command().isEmpty()) out.put("command", d.command());
                if (!d.args().isEmpty()) out.put("args", d.args());
                if (!d.env().isEmpty()) out.put("env", d.env());
                if (!d.volumes().isEmpty()) out.put("volumes", d.volumes());
                if (d.transport() != null) out.put("transport", d.transport());
                if (d.url() != null) out.put("url", d.url());
            }
            case McpDependency.BinaryLoad b -> {
                out.put("type", "binary");
                Map<String, Object> install = new LinkedHashMap<>();
                for (var e : b.install().entrySet()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("url", e.getValue().url());
                    if (e.getValue().archive() != null) t.put("archive", e.getValue().archive());
                    if (e.getValue().binary() != null) t.put("binary", e.getValue().binary());
                    if (e.getValue().sha256() != null) t.put("sha256", e.getValue().sha256());
                    install.put(e.getKey(), t);
                }
                out.put("install", install);
                if (b.initScript() != null) out.put("init_script", b.initScript());
                if (b.binPath() != null) out.put("bin_path", b.binPath());
                if (!b.args().isEmpty()) out.put("args", b.args());
                if (!b.env().isEmpty()) out.put("env", b.env());
                if (b.transport() != null) out.put("transport", b.transport());
                if (b.url() != null) out.put("url", b.url());
            }
        }
        return out;
    }

    private List<Map<String, Object>> serializeSchema(List<McpDependency.InitField> schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpDependency.InitField f : schema) {
            // Include every field (even when null/empty) so the payload matches
            // Python's InitSchemaFieldPayload.model_dump shape — required for
            // the install-time spec_digest to line up across ends.
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("description", f.description());
            m.put("required", f.required());
            m.put("secret", f.secret());
            m.put("default", f.defaultValue());
            m.put("enum", f.enumValues());
            out.add(m);
        }
        return out;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record RegisterResult(
            String serverId,
            boolean registered,
            boolean deployed,
            String transport,
            String defaultScope,
            String deployedScope,
            String deployError) {}

    /**
     * State of a server as the gateway sees it. Used by install-time idempotency checks
     * to decide whether to skip, redeploy, or re-register.
     */
    public record ServerState(
            String serverId,
            String defaultScope,
            boolean deployed,
            boolean deployedGlobally,
            Map<String, Object> loadSpecDigestSource,
            List<Map<String, Object>> initSchemaDigestSource,
            String specDigest,
            Map<String, Object> lastError) {

        @SuppressWarnings("unchecked")
        public static ServerState fromMap(Map<String, Object> m) {
            List<Map<String, Object>> schema = (List<Map<String, Object>>) m.getOrDefault("init_schema", List.of());
            Map<String, Object> loadSource = new LinkedHashMap<>();
            loadSource.put("transport", m.get("transport"));
            Map<String, Object> lastError = (Map<String, Object>) m.get("last_error");
            return new ServerState(
                    (String) m.get("server_id"),
                    (String) m.getOrDefault("default_scope", McpDependency.DEFAULT_SCOPE),
                    Boolean.TRUE.equals(m.get("deployed")),
                    Boolean.TRUE.equals(m.get("deployed_globally")),
                    loadSource,
                    schema,
                    (String) m.get("spec_digest"),
                    lastError
            );
        }
    }
}
