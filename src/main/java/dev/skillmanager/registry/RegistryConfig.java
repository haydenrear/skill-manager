package dev.skillmanager.registry;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves the skill registry base URL.
 *
 * <p>Precedence: env {@code SKILL_MANAGER_REGISTRY_URL} → CLI override → persisted
 * {@code registry.properties} → default {@code http://127.0.0.1:8090}.
 */
public final class RegistryConfig {

    public static final String DEFAULT_URL = "http://127.0.0.1:8090";
    private static final String FILE = "registry.properties";
    private static final String KEY = "registry.url";

    private final URI baseUrl;

    private RegistryConfig(URI baseUrl) { this.baseUrl = baseUrl; }

    public URI baseUrl() { return baseUrl; }

    public static RegistryConfig resolve(SkillStore store, String override) throws IOException {
        String env = System.getenv("SKILL_MANAGER_REGISTRY_URL");
        if (env != null && !env.isBlank()) return new RegistryConfig(URI.create(env.trim()));
        if (override != null && !override.isBlank()) {
            persist(store, override.trim());
            return new RegistryConfig(URI.create(override.trim()));
        }
        String persisted = loadPersisted(store);
        if (persisted != null) return new RegistryConfig(URI.create(persisted));
        return new RegistryConfig(URI.create(DEFAULT_URL));
    }

    public static void persist(SkillStore store, String url) throws IOException {
        Fs.ensureDir(store.root());
        Properties props = new Properties();
        props.setProperty(KEY, url);
        try (var out = Files.newOutputStream(store.root().resolve(FILE))) {
            props.store(out, "skill-manager registry config");
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
