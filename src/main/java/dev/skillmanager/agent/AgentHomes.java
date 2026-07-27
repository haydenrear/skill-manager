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
 * <p>When even the default has to be derived from the user's home directory,
 * derive it from {@link #userHome()} and never from
 * {@code System.getProperty("user.home")} — see that method for why the JVM
 * property is not a safe stand-in for {@code $HOME}.
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

    /**
     * The POSIX home-directory variable. Not an agent config root — the
     * last-resort <em>base</em> the agent roots are derived from when no
     * agent-specific variable is set. See {@link #userHome()}.
     */
    public static final String HOME = "HOME";

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
     * The user's home directory: {@code $HOME} when the environment sets it,
     * otherwise the JVM's {@code user.home}.
     *
     * <h2>Why this is not just {@code System.getProperty("user.home")}</h2>
     *
     * <p>On macOS the JVM derives {@code user.home} from the OS directory
     * services record for the uid and <b>ignores {@code $HOME}</b>. So a caller
     * that sandboxes a child process by setting {@code HOME} — which is what
     * every shell, test fixture and per-checkout home does — gets a JVM whose
     * {@code user.home} still points at the operator's real home. Every
     * {@code user.home} read is therefore a hole straight through the sandbox,
     * and the sandbox is leaky <em>by default</em>: it leaks unless the caller
     * additionally passes {@code JAVA_TOOL_OPTIONS=-Duser.home=...}, which
     * nothing forces them to remember.
     *
     * <p>That is exactly how issue #18 happened. The agent-home env vars
     * ({@code CLAUDE_CONFIG_DIR}, {@code CODEX_HOME}, {@code GEMINI_HOME})
     * covered the <em>lookup</em> path, so MCP writes — which resolve through
     * {@code SKILL_MANAGER_HOME} — landed in the sandbox correctly. But the
     * <em>fallback</em> path was written down three separate times, once in
     * {@link #claude(java.util.function.Function)}, once in {@link CodexAgent}
     * and once in {@link GeminiAgent}, and all three read {@code user.home}
     * directly. Skill projection took the fallback and wrote into the
     * operator's real {@code ~/.claude}, {@code ~/.codex} and {@code ~/.gemini},
     * leaving dangling symlinks into deleted temp dirs — one of which surfaced
     * in a live agent's available-skills list.
     *
     * <p>Three independent copies of one rule is the failure mode this epic has
     * now paid for several times: two paths that are supposed to agree, don't,
     * and nothing detects the disagreement. Hence exactly one method. Do not
     * reintroduce a second copy — there must be no
     * {@code System.getProperty("user.home")} anywhere else in this package.
     *
     * <p>Resolution order, highest precedence first:
     * <ol>
     *   <li>{@link #setOverride(String, Path)} override for {@link #HOME}
     *       (tests), via the same {@link #resolve(String)} used by every other
     *       lookup here — so one interception point covers all of them.</li>
     *   <li>The {@code HOME} environment variable, when set and non-blank.</li>
     *   <li>{@code user.home}.</li>
     * </ol>
     */
    public static Path userHome() {
        return userHome(AgentHomes::resolve);
    }

    /**
     * {@link #userHome()} against an explicit lookup, so
     * {@link #claude(Map)} derives its last-resort root from the
     * <em>launch</em> environment's {@code HOME} rather than the ambient one —
     * the same "a launch env is the complete statement of what the child gets"
     * rule the rest of that path follows.
     *
     * <p>This is the only {@code user.home} read in the package, on purpose.
     */
    private static Path userHome(java.util.function.Function<String, Path> lookup) {
        Path fromEnv = lookup.apply(HOME);
        return fromEnv != null ? fromEnv : Path.of(System.getProperty("user.home"));
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
     *   <li>{@link #userHome()} — {@code $HOME}, then {@code user.home}.
     *       Reading {@code user.home} directly here is what let issue #18
     *       project skills into the operator's real {@code ~/.claude}.</li>
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
        return claude(AgentHomes::resolve);
    }

    /**
     * The same resolution applied to an explicit environment rather than the
     * ambient one — used when deciding where a launch <em>would</em> put
     * Claude before starting it (see
     * {@code dev.skillmanager.launch.LaunchEnv}).
     *
     * <p>It delegates to the same private implementation as {@link #claude()}
     * precisely so there is still exactly one place the
     * {@code CLAUDE_CONFIG_DIR} → {@code CLAUDE_HOME} → {@code user.home}
     * precedence is written down. A launcher that re-derived it could disagree
     * with the reader, and then a launch could pass its own isolation check
     * while skill-manager wrote skills somewhere else — the split brain this
     * method's javadoc describes, reintroduced from the other side.
     *
     * <p>A key absent from {@code env} is treated as unset, <em>not</em> as
     * "fall back to the ambient variable". A launch environment is the
     * complete statement of what the child gets, so an ambient
     * {@code CLAUDE_CONFIG_DIR} that the launch does not pass on must not
     * influence the answer.
     */
    public static ClaudeHome claude(Map<String, String> env) {
        Map<String, String> source = env == null ? Map.of() : env;
        return claude(key -> {
            String value = source.get(key);
            return value == null || value.isBlank() ? null : Path.of(value);
        });
    }

    private static ClaudeHome claude(java.util.function.Function<String, Path> lookup) {
        Path explicitConfigDir = lookup.apply(CLAUDE_CONFIG_DIR);
        if (explicitConfigDir != null) {
            Path parent = explicitConfigDir.getParent();
            return new ClaudeHome(parent != null ? parent : explicitConfigDir, explicitConfigDir);
        }
        Path root = lookup.apply(CLAUDE_HOME);
        if (root == null) root = userHome(lookup);
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
