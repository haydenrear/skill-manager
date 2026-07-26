package dev.skillmanager.agent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Single resolution point for the per-agent config-root env vars
 * ({@code CLAUDE_HOME}, {@code CLAUDE_CONFIG_DIR}, {@code CODEX_HOME},
 * {@code GEMINI_HOME})
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
    public static final String GEMINI_HOME = "GEMINI_HOME";

    /** The directory name Claude Code keeps its config tree under. */
    public static final String CLAUDE_DIR_NAME = ".claude";

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
     * The one Claude config location, in both of the spellings callers
     * need: {@code root} is the parent that holds {@code .claude/} and
     * {@code .claude.json}; {@code configDir} is the {@code .claude/}
     * directory itself.
     *
     * <p>They are two views of a single value, never two values.
     */
    public record ClaudeHome(Path root, Path configDir) {}

    /**
     * Resolve Claude's config location <em>once</em>, for every caller.
     *
     * <h2>Why this must be a single lookup</h2>
     *
     * <p>{@code CLAUDE_HOME} and {@code CLAUDE_CONFIG_DIR} name the same
     * directory in two different spellings, and they used to be resolved
     * independently: {@link ClaudeAgent} read only {@code CLAUDE_HOME},
     * while {@code HarnessPluginCli.Claude} preferred
     * {@code CLAUDE_CONFIG_DIR}. A context that set only
     * {@code CLAUDE_CONFIG_DIR} — which is the variable the Claude CLI
     * itself honours, so it is the one a per-project home actually has to
     * set — therefore got a split brain: the CLI wrote into the
     * project-local config dir while skill-manager symlinked its skills
     * into the developer's real {@code ~/.claude/skills}. The home
     * isolation was defeated in the one place that matters most, silently,
     * and the only visible symptom was skills appearing globally.
     *
     * <p>Resolution order, highest precedence first:
     * <ol>
     *   <li>{@code CLAUDE_CONFIG_DIR} (override, then env) — taken
     *       verbatim as the config dir, with its parent as the root. It
     *       wins because it is what the Claude CLI reads, so honouring
     *       anything else would put skill-manager and the CLI in
     *       different directories.</li>
     *   <li>{@code CLAUDE_HOME} (override, then env) — the parent;
     *       config dir is {@code <CLAUDE_HOME>/.claude}.</li>
     *   <li>{@code user.home}.</li>
     * </ol>
     *
     * <p>This precedence is also what makes it safe for a home descriptor
     * to publish {@code CLAUDE_HOME} and {@code CLAUDE_CONFIG_DIR} as the
     * <em>same</em> string (see
     * {@link dev.skillmanager.store.HomeDescriptor}): consumers read
     * whichever one they know about, and because
     * {@code CLAUDE_CONFIG_DIR} is consulted first, the value never gets
     * a second {@code .claude} appended to it.
     */
    public static ClaudeHome claude() {
        Path explicitConfigDir = resolve(CLAUDE_CONFIG_DIR);
        if (explicitConfigDir != null) {
            Path parent = explicitConfigDir.getParent();
            return new ClaudeHome(parent != null ? parent : explicitConfigDir, explicitConfigDir);
        }
        Path root = resolveOrDefault(CLAUDE_HOME, Path.of(System.getProperty("user.home")));
        return new ClaudeHome(root, root.resolve(CLAUDE_DIR_NAME));
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
