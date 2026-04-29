package dev.skillmanager.mcp;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tracks the virtual MCP gateway URL that skill-manager registers against and
 * that agents are pointed to.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code SKILL_MANAGER_GATEWAY_URL} env var</li>
 *   <li>{@code --gateway} CLI option (passed to constructor)</li>
 *   <li>Persisted {@code gateway.properties} in the store root</li>
 *   <li>Default: {@code http://127.0.0.1:51717}</li>
 * </ol>
 */
public final class GatewayConfig {

    public static final String DEFAULT_URL = "http://127.0.0.1:51717";
    private static final String FILE = "gateway.properties";
    private static final String KEY = "gateway.url";

    private final URI baseUrl;

    private GatewayConfig(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI baseUrl() { return baseUrl; }

    public URI mcpEndpoint() {
        String base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/mcp");
    }

    public URI serversEndpoint() {
        String base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/servers");
    }

    public static GatewayConfig resolve(SkillStore store, String override) throws IOException {
        String env = System.getenv("SKILL_MANAGER_GATEWAY_URL");
        if (env != null && !env.isBlank()) return new GatewayConfig(URI.create(env.trim()));
        if (override != null && !override.isBlank()) {
            persist(store, override.trim());
            return new GatewayConfig(URI.create(override.trim()));
        }
        String persisted = loadPersisted(store);
        if (persisted != null) return new GatewayConfig(URI.create(persisted));
        return new GatewayConfig(URI.create(DEFAULT_URL));
    }

    public static void persist(SkillStore store, String url) throws IOException {
        Fs.ensureDir(store.root());
        Properties props = new Properties();
        props.setProperty(KEY, url);
        try (var out = Files.newOutputStream(store.root().resolve(FILE))) {
            props.store(out, "skill-manager gateway config");
        }
    }

    private static String loadPersisted(SkillStore store) throws IOException {
        Path file = store.root().resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        String value = props.getProperty(KEY);
        return value == null || value.isBlank() ? null : value;
    }
}
