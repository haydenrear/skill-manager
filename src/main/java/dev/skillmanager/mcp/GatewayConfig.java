package dev.skillmanager.mcp;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tracks the virtual MCP gateway URL that skill-manager registers against and
 * that agents are pointed to — and, since per-worktree homes, <em>whether this
 * home is allowed to start and stop it</em>.
 *
 * <h2>Ownership</h2>
 *
 * <p>A gateway is one process on one port. N per-project homes each running
 * {@code gateway up} therefore do not get N gateways; they get one winner and
 * N-1 bind failures, or worse, a home silently using another home's gateway
 * while believing it runs its own — and then killing it on {@code gateway
 * down}. meta-orchestrator worked around this by hand
 * ({@code onboard --skip-gateway}, then discover one endpoint via
 * {@code gateway status} and pass it to every agent); this makes the two modes
 * first-class instead:
 *
 * <ul>
 *   <li><b>owner</b> ({@code owned == true}) — the default. This home may
 *       start, stop, and persist the gateway.</li>
 *   <li><b>attached</b> ({@code owned == false}) — this home uses a gateway
 *       another home runs. {@code gateway up} and {@code gateway down} refuse,
 *       because starting would collide on the port and stopping would take the
 *       gateway out from under every other home attached to it.</li>
 * </ul>
 *
 * <p>Persisted next to the URL in {@code gateway.properties}, so it travels
 * with the home and survives a restart. A home cloned from another
 * ({@code home clone}) is attached to the source's gateway rather than
 * inheriting ownership of it — two homes cannot both own one port.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code SKILL_MANAGER_GATEWAY_URL} env var — <em>always attached</em>.
 *       An endpoint handed in from outside is by definition one this home did
 *       not create, so it must not be assumed ours to stop.</li>
 *   <li>{@code --gateway} CLI option (passed to constructor)</li>
 *   <li>Persisted {@code gateway.properties} in the store root</li>
 *   <li>Default: {@code http://127.0.0.1:51717}, owned</li>
 * </ol>
 */
public final class GatewayConfig {

    public static final String DEFAULT_URL = "http://127.0.0.1:51717";

    /**
     * The file a home records its gateway decision in.
     *
     * <p>Public since HIS-21: {@code home describe} has to say whether the URL
     * and the ownership it prints were DECLARED here or defaulted, and a second
     * spelling of this filename at that call site is the shape this epic keeps
     * filing findings about.
     */
    public static final String FILE = "gateway.properties";

    /**
     * The env var that overrides the URL, and always as ATTACHED. Named for
     * the same reason {@link #FILE} is: {@code home describe} must not report
     * "no gateway.properties here, so this is the default endpoint" about a URL
     * that came from the environment.
     */
    public static final String URL_ENV = "SKILL_MANAGER_GATEWAY_URL";
    private static final String KEY = "gateway.url";
    private static final String OWNED_KEY = "gateway.owned";

    private final URI baseUrl;
    private final boolean owned;

    private GatewayConfig(URI baseUrl, boolean owned) {
        this.baseUrl = baseUrl;
        this.owned = owned;
    }

    public URI baseUrl() { return baseUrl; }

    /**
     * True when this home may start and stop the gateway at
     * {@link #baseUrl()}; false when it is attached to a shared one.
     */
    public boolean owned() { return owned; }

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
        String env = System.getenv(URL_ENV);
        if (env != null && !env.isBlank()) {
            return new GatewayConfig(URI.create(env.trim()), false);
        }
        boolean persistedOwned = loadOwned(store);
        if (override != null && !override.isBlank()) {
            persist(store, override.trim(), persistedOwned);
            return new GatewayConfig(URI.create(override.trim()), persistedOwned);
        }
        String persisted = loadPersisted(store);
        if (persisted != null) return new GatewayConfig(URI.create(persisted), persistedOwned);
        return new GatewayConfig(URI.create(DEFAULT_URL), persistedOwned);
    }

    /**
     * Build a non-persisted config from an explicit URL. Use for paths
     * that want EnsureGateway's success-path persist (LiveInterpreter
     * persists on healthy start) without writing the URL up front — that
     * would leave a stale URL on disk if the gateway never came up.
     */
    public static GatewayConfig of(URI baseUrl) { return new GatewayConfig(baseUrl, true); }

    /** Explicit-URL config carrying an explicit ownership mode. */
    public static GatewayConfig of(URI baseUrl, boolean owned) {
        return new GatewayConfig(baseUrl, owned);
    }

    /**
     * Record {@code url} as a gateway this home <em>attaches to</em>: the
     * URL is persisted and ownership is dropped, so {@code gateway up} and
     * {@code gateway down} will refuse rather than fight another home for
     * the port.
     */
    public static GatewayConfig attach(SkillStore store, String url) throws IOException {
        String trimmed = url.trim();
        persist(store, trimmed, false);
        return new GatewayConfig(URI.create(trimmed), false);
    }

    /**
     * Take ownership of the currently configured gateway, so this home may
     * start and stop it again. The inverse of {@link #attach}.
     */
    public static GatewayConfig detach(SkillStore store) throws IOException {
        String persisted = loadPersisted(store);
        String url = persisted != null ? persisted : DEFAULT_URL;
        persist(store, url, true);
        return new GatewayConfig(URI.create(url), true);
    }

    /**
     * Persist the URL, leaving the recorded ownership mode alone. Callers
     * that mean to change ownership say so via
     * {@link #persist(SkillStore, String, boolean)} — a plain URL write
     * (from {@code gateway set}, or EnsureGateway's healthy-start path)
     * must not silently promote an attached home to owner.
     */
    public static void persist(SkillStore store, String url) throws IOException {
        persist(store, url, loadOwned(store));
    }

    public static void persist(SkillStore store, String url, boolean owned) throws IOException {
        Fs.ensureDir(store.root());
        Properties props = new Properties();
        props.setProperty(KEY, url);
        props.setProperty(OWNED_KEY, Boolean.toString(owned));
        try (var out = Files.newOutputStream(store.root().resolve(FILE))) {
            props.store(out, "skill-manager gateway config");
        }
    }

    private static String loadPersisted(SkillStore store) throws IOException {
        String value = property(store, KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The recorded ownership mode, defaulting to owned.
     *
     * <p>Absent means "written before ownership was modeled", and every
     * such home was in fact the only home on the machine — so owned is
     * both the compatible answer and the true one. Attachment is only ever
     * established by an explicit act.
     */
    private static boolean loadOwned(SkillStore store) throws IOException {
        String value = property(store, OWNED_KEY);
        if (value == null || value.isBlank()) return true;
        return !"false".equalsIgnoreCase(value.trim());
    }

    private static String property(SkillStore store, String key) throws IOException {
        Path file = store.root().resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        return props.getProperty(key);
    }
}
