package dev.skillmanager.agent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Single resolution point for the per-agent config-root env vars
 * ({@code CLAUDE_HOME}, {@code CLAUDE_CONFIG_DIR}, {@code CODEX_HOME})
 * that skill-manager reads when locating the harness's on-disk state.
 *
 * <p>The same lookup is needed in several places — {@link ClaudeAgent}
 * for the skill-symlink target, {@link CodexAgent} for {@code config.toml},
 * {@code HarnessPluginCli.Claude.claudeEnv()} for the subprocess env
 * passed to {@code claude plugin …}, and the equivalent codex driver.
 * Centralizing it here makes one place for tests to intercept.
 *
 * <p><b>Why this exists:</b> a Java process can't mutate
 * {@code System.getenv()}, so unit-test code that exercises the
 * harness-CLI path (via {@link dev.skillmanager.effects.LiveInterpreter})
 * couldn't previously sandbox its {@code claude plugin marketplace add}
 * subprocess calls — those went to the developer's real {@code ~/.claude/}
 * and left behind stale marketplace entries pointing at deleted temp
 * dirs. The thread-local override below gives tests a way to redirect
 * the lookup without touching the process-level environment.
 *
 * <p>Resolution order, highest precedence first:
 * <ol>
 *   <li>{@link #setOverride(String, Path)} thread-local override (tests).</li>
 *   <li>Corresponding env var.</li>
 *   <li>Filesystem default (caller-supplied).</li>
 * </ol>
 *
 * <p>The thread-local lives until {@link #clearOverrides()} is called or
 * the thread exits. Use the {@link TestHarness}-style try-with-resources
 * cleanup pattern; in a worst-case "test forgot to close" scenario the
 * subsequent code on that thread points at the (now-deleted) temp dir,
 * which fails LOUDLY with ENOENT rather than silently polluting the
 * developer's real config.
 */
public final class AgentHomes {

    public static final String CLAUDE_HOME = "CLAUDE_HOME";
    public static final String CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    public static final String CODEX_HOME = "CODEX_HOME";

    private static final ThreadLocal<Map<String, Path>> OVERRIDES =
            ThreadLocal.withInitial(HashMap::new);

    private AgentHomes() {}

    /**
     * Look up {@code key} through override → env var → null. Callers
     * decide the system default when the lookup misses.
     */
    public static Path resolve(String key) {
        Path override = OVERRIDES.get().get(key);
        if (override != null) return override;
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return Path.of(env);
        return null;
    }

    /**
     * Look up {@code key} with an explicit fallback when neither the
     * override nor the env var is set. {@code defaultValue} is the
     * "if everything else is unset" path — typically derived from
     * {@code user.home}.
     */
    public static Path resolveOrDefault(String key, Path defaultValue) {
        Path p = resolve(key);
        return p != null ? p : defaultValue;
    }

    /**
     * Install a thread-local override. Production code never calls
     * this; tests (or any sandboxed context) call it from a setup
     * step and pair it with {@link #clearOverrides()} on teardown.
     *
     * <p>Passing {@code null} for {@code value} removes the override
     * for {@code key} without disturbing other entries.
     */
    public static void setOverride(String key, Path value) {
        if (value == null) {
            OVERRIDES.get().remove(key);
        } else {
            OVERRIDES.get().put(key, value);
        }
    }

    /**
     * Drop every thread-local override. Tests call this in {@code @AfterEach}
     * (or {@link AutoCloseable#close()} on a harness wrapper) so a polluted
     * override doesn't bleed into the next test sharing the same JUnit
     * worker thread.
     */
    public static void clearOverrides() {
        OVERRIDES.get().clear();
    }
}
