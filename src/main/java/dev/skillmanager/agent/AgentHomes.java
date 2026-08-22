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

    /**
     * The variable naming the Skill Manager store. Not an agent root either,
     * but the one that <em>decides</em> the agent roots when no agent-specific
     * variable is set — see {@link #agentHomeRoot()}. Declared here rather than
     * imported from {@code SkillStore.HOME_ENV} (which now delegates to it) so
     * this package keeps no dependency on {@code store}.
     */
    public static final String SKILL_MANAGER_HOME = "SKILL_MANAGER_HOME";

    /** The directory name Claude Code keeps its config tree under. */
    public static final String CLAUDE_DIR_NAME = ".claude";

    /** The conventional basename of a Skill Manager store inside its home. */
    public static final String STORE_DIR_NAME = ".skill-manager";

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
     * The home root the agent config directories belong to: the directory
     * that holds {@code .claude/}, {@code .codex/} and {@code .gemini/}
     * beside the active store.
     *
     * <p>By convention a store is {@code <root>/.skill-manager}, so the root is
     * its parent — {@code ~} for {@code ~/.skill-manager}, {@code <repo>} for a
     * per-project {@code <repo>/.skill-manager}. A store not named
     * {@code .skill-manager} is its own root, because there is nothing else it
     * could be. This is the one definition;
     * {@code HomeDescriptor.homeRootFor} delegates to it, so the descriptor a
     * launcher publishes and the directory skill-manager itself projects into
     * are derived from the same rule rather than from two that agree today.
     *
     * <h2>Why the fallback base is this and not {@code $HOME}</h2>
     *
     * <p>This is issue #145, and its measured consequence. Every agent root
     * used to fall back to {@code <user home>/.claude|.codex|.gemini} whenever
     * the agent-specific variable was unset — <em>regardless of which home
     * skill-manager was operating on</em>. So the documented remedy
     *
     * <pre>{@code SKILL_MANAGER_HOME=<fresh>/.skill-manager skill-manager sync --force-scripts}</pre>
     *
     * projected the fresh home's units into the <b>operator's global</b> agent
     * directories, repointing 24 of their skill links at a throwaway project
     * that was about to be deleted. Reproduced here against a fixture
     * {@code $HOME}: the hijack happens with the cloned home's binding ledger
     * <em>deleted</em>, so it was never the ledger that decided the
     * destination — the destination was always re-derived from the ambient
     * environment, and the ambient environment does not know which home it is
     * talking about.
     *
     * <p>The same gap is the operator's original complaint from the other side:
     * {@code bootstrap-home.sh} creates {@code <project>/.claude} and nothing
     * ever populated it, so a markdown skill-import naming a unit in the home
     * had nothing to resolve against, while {@code exec} pointed
     * {@code CLAUDE_CONFIG_DIR} at that empty directory. One resolution rule
     * fixes both directions: units land in the agent directories of the home
     * they were installed into.
     *
     * <p>This is also the invariant
     * {@code LaunchEnv#requireClaudeRedirected} already enforces on the launch
     * side — "the Claude config dir must be inside this home's root, and for
     * the global home that is exactly {@code ~/.claude}". A launcher refusing a
     * configuration that skill-manager's own writer would happily produce is
     * two rules where there should be one.
     *
     * <p>Behaviour for the global home is unchanged in every case:
     * {@code $SKILL_MANAGER_HOME} unset resolves to {@code ~/.skill-manager}
     * and hence {@code ~}, and an explicit {@code ~/.skill-manager} resolves to
     * {@code ~} as well. What changes is only the case where the operator has
     * pointed skill-manager at some other home, which is the case where the
     * old answer was never right.
     *
     * <p>An explicit {@code CLAUDE_CONFIG_DIR} / {@code CLAUDE_HOME} /
     * {@code CODEX_HOME} / {@code GEMINI_HOME} still wins, so an operator who
     * genuinely wants a non-default store to project into {@code ~} says so and
     * gets it.
     */
    public static Path agentHomeRoot() {
        return agentHomeRoot(AgentHomes::resolve);
    }

    /** {@link #agentHomeRoot()} against an explicit lookup. */
    private static Path agentHomeRoot(java.util.function.Function<String, Path> lookup) {
        Path store = lookup.apply(SKILL_MANAGER_HOME);
        return store == null ? userHome(lookup) : homeRootFor(store);
    }

    /**
     * The home root enclosing {@code storeRoot}. See {@link #agentHomeRoot()}
     * for the convention and for why there is one spelling of it.
     *
     * <p>The profile layout ({@code ProjectChildHomeScaffolder.layoutFor} with
     * a named profile) puts its agent homes under {@code agents/} instead, so
     * such callers pass their root explicitly rather than relying on this.
     */
    public static Path homeRootFor(Path storeRoot) {
        Path abs = storeRoot.toAbsolutePath().normalize();
        Path name = abs.getFileName();
        if (name != null && STORE_DIR_NAME.equals(name.toString())) {
            Path parent = abs.getParent();
            if (parent != null) return parent;
        }
        return abs;
    }

    /**
     * The three agent config directories that belong to the home rooted at
     * {@code homeRoot}. One list, so a caller asking "is this path in some
     * home's agent dirs" and a caller asking "where do I project" cannot
     * disagree about what an agent home is.
     */
    public static java.util.List<Path> agentDirsUnder(Path homeRoot) {
        Path root = homeRoot.toAbsolutePath().normalize();
        return java.util.List.of(
                root.resolve(CLAUDE_DIR_NAME), root.resolve(".codex"), root.resolve(".gemini"));
    }

    /**
     * The environment variable an agent directory is named by:
     * {@code .claude} → {@code CLAUDE_CONFIG_DIR} (the variable the Claude CLI
     * itself honours — see {@link #claude()}), {@code .codex} →
     * {@code CODEX_HOME}, {@code .gemini} → {@code GEMINI_HOME}.
     */
    public static String variableFor(Path agentDir) {
        String name = agentDir.getFileName() == null ? "" : agentDir.getFileName().toString();
        return switch (name) {
            case ".codex" -> CODEX_HOME;
            case ".gemini" -> GEMINI_HOME;
            default -> CLAUDE_CONFIG_DIR;
        };
    }

    /**
     * <b>What "this command is about home X" means, as an environment.</b>
     *
     * <p>A home has TWO AXES. {@code SKILL_MANAGER_HOME} says where the UNITS
     * live; {@code CLAUDE_CONFIG_DIR} / {@code CODEX_HOME} / {@code GEMINI_HOME}
     * say where the AGENT CONFIGS live, and they are a separate axis. Naming
     * only the first sends the store half where it was told and resolves the
     * agent half against whatever the ambient environment happens to export —
     * issue #145, measured again as DEF-029: {@code sync <unit> --merge --home
     * <scratch>} wrote {@code units.lock.toml} into the named home and then
     * linked that unit into the operator's real {@code ~/.claude},
     * {@code ~/.codex} and {@code ~/.gemini}, registering a marketplace in
     * three real config files on the way.
     *
     * <p>This method is the ONE statement of that environment. Two callers
     * consume it and they must not be able to disagree:
     *
     * <ul>
     *   <li>{@code HomeCommand.homeEnvPrefix} RENDERS it, as the {@code env
     *       NAME=value …} prefix of a printed remedy;</li>
     *   <li>{@link #bind} APPLIES it, as the thread-local overrides
     *       {@code --home} installs, so an in-process invocation resolves
     *       exactly what that printed prefix would have resolved.</li>
     * </ul>
     *
     * <p>So {@code --home <X>} and {@code env <prefix> <cli>} are one binding
     * in two syntaxes rather than two answers to "which home is this command
     * about" — which is what HIS-14 is, and what
     * {@code GOAL-one-home-one-answer} measures.
     *
     * <h2>Why {@code CLAUDE_HOME} is not in the map</h2>
     *
     * <p>Because it would be a second way to say the first. {@code CLAUDE_HOME}
     * is the PARENT spelling of {@code CLAUDE_CONFIG_DIR}, and
     * {@link #claude()} consults the config dir FIRST — so a binding that
     * declares {@code CLAUDE_CONFIG_DIR} already wins over any ambient
     * {@code CLAUDE_HOME}, and adding it would put one directory in the map
     * twice and give a future edit two places to be wrong.
     * {@code HomeDescriptor.envFor} publishes both because a descriptor is read
     * by consumers that know only one of the names; a binding is applied by
     * this codebase, which knows the precedence.
     *
     * @param storeRoot the Skill Manager store — {@code <root>/.skill-manager}
     *                  by convention; see {@link #homeRootFor}
     */
    public static Map<String, String> binding(Path storeRoot) {
        return binding(storeRoot, homeRootFor(storeRoot));
    }

    /**
     * {@link #binding(Path)} with the agent-home root stated rather than
     * derived — the profile layout ({@code ProjectChildHomeScaffolder.layoutFor}
     * with a named profile) keeps its agent homes under {@code agents/} instead
     * of beside the store, and {@code --home-root} is how a caller says so.
     */
    public static Map<String, String> binding(Path storeRoot, Path homeRoot) {
        Path store = storeRoot.toAbsolutePath().normalize();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put(SKILL_MANAGER_HOME, store.toString());
        for (Path dir : agentDirsUnder(homeRoot)) {
            out.put(variableFor(dir), dir.toString());
        }
        return out;
    }

    /**
     * Apply {@link #binding} to this thread, and answer what was displaced so
     * the caller can put it back.
     *
     * <p>A JVM cannot mutate its own environment, so the overrides ARE the
     * binding as far as this process is concerned: {@link #resolve} consults
     * them ahead of the real variables, {@code SkillStore.defaultStore()}
     * resolves through it, and the two harness drivers that spawn a child
     * ({@code claude plugin …}, {@code codex …}) pass the resolved value down
     * explicitly rather than letting the child inherit the ambient one. Every
     * reader therefore agrees, in-process and in the children.
     *
     * @return the overrides this call displaced, for {@link #restoreOverrides}
     */
    public static Map<String, Path> bind(Path storeRoot) {
        return bind(storeRoot, homeRootFor(storeRoot));
    }

    /** {@link #bind(Path)} against {@link #binding(Path, Path)}. */
    public static Map<String, Path> bind(Path storeRoot, Path homeRoot) {
        Map<String, Path> displaced = snapshotOverrides();
        binding(storeRoot, homeRoot).forEach((key, value) -> setOverride(key, Path.of(value)));
        return displaced;
    }

    /**
     * The variables {@link #bind} cannot honestly bind for {@code storeRoot},
     * each with the reason, or empty when it can bind them all.
     *
     * <p>Setting a variable is not the same as confining a write. An agent
     * directory that is a SYMLINK OUT of the home writes wherever the link
     * points, so a caller that binds it has named one home and edited another —
     * the very outcome the binding exists to prevent, and the shape the epic
     * measured twice on the operator's own root home during HIS-7. This is the
     * "or refuses and names the variable it cannot set" half of HIS-14's slice;
     * there is no third option in which the command runs anyway.
     *
     * <p>Only directories that EXIST are judged. A home whose {@code .claude}
     * has not been created yet is bindable — the projector will create it
     * inside the home. And a symlink that stays INSIDE the home root is fine:
     * the test is where it lands, not whether it is a link.
     */
    public static Map<String, String> unbindable(Path storeRoot) {
        return unbindable(storeRoot, homeRootFor(storeRoot));
    }

    /** {@link #unbindable(Path)} against an explicitly stated agent-home root. */
    public static Map<String, String> unbindable(Path storeRoot, Path homeRoot) {
        Path root = homeRoot.toAbsolutePath().normalize();
        Path real = dev.skillmanager.shared.util.Fs.realOrNormalized(root);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Path dir : agentDirsUnder(root)) {
            if (!java.nio.file.Files.exists(dir)) continue;
            Path target = dev.skillmanager.shared.util.Fs.realOrNormalized(dir);
            if (target.startsWith(real)) continue;
            out.put(variableFor(dir), dir + " resolves to " + target
                    + ", which is outside the home at " + root);
        }
        return out;
    }

    /**
     * Every override currently installed on this thread — a copy, so the
     * caller holds a value rather than a view of a map that keeps changing.
     */
    public static Map<String, Path> snapshotOverrides() {
        return new HashMap<>(OVERRIDES.get());
    }

    /**
     * Put back exactly the overrides {@code snapshot} names, dropping any
     * installed since it was taken.
     *
     * <p>Not {@link #clearOverrides()}: a binding applied by one CLI
     * invocation must not delete the sandbox an embedding process (the server,
     * a test harness) installed around it. That distinction is why
     * {@link #bind} returns the displaced map rather than a boolean.
     */
    public static void restoreOverrides(Map<String, Path> snapshot) {
        Map<String, Path> live = OVERRIDES.get();
        live.clear();
        if (snapshot != null) live.putAll(snapshot);
    }

    /**
     * The one Claude config location, in the three spellings callers need:
     * {@code root} is the parent directory, {@code configDir} is the
     * {@code .claude/} tree, and {@code configFile} is the {@code .claude.json}
     * the Claude CLI reads its {@code mcpServers} out of.
     *
     * <p>They are three views of a single value, never three values.
     *
     * <h2>Why {@code configFile} is not {@code root.resolve(".claude.json")}</h2>
     *
     * <p>Because it is only that when {@code CLAUDE_CONFIG_DIR} is unset.
     * Claude Code reads {@code $CLAUDE_CONFIG_DIR/.claude.json} when the
     * variable is set and {@code ~/.claude.json} when it is not — the file
     * moves <em>with the config dir</em>, it does not stay beside it. Deriving
     * it from {@code root} was right for the global home and wrong for every
     * per-checkout home, and the failure was silent in the worst way: {@code
     * install} reported {@code ADDED claude (<project>/.claude.json)} while the
     * launched agent, given {@code CLAUDE_CONFIG_DIR=<project>/.claude}, read
     * {@code <project>/.claude/.claude.json} and saw no MCP tools at all.
     *
     * <p>Measured: the real {@code claude} binary, run by that same install's
     * {@code marketplace-add} step under that same env, created
     * {@code <project>/.claude/.claude.json} itself. Two files, one of them
     * with {@code mcpServers} and one of them the one the agent reads, and
     * they were not the same file. The stray {@code <project>/.claude.json}
     * also fell outside the documented {@code /.claude/} gitignore rule, which
     * is what then made {@code wt new} refuse on an unclean tree.
     */
    public record ClaudeHome(Path root, Path configDir, Path configFile) {}

    /** The basename of Claude's JSON config file, in whichever directory it lives. */
    public static final String CLAUDE_CONFIG_FILENAME = ".claude.json";

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
     *   <li>{@link #agentHomeRoot()} — the root of the home skill-manager is
     *       operating on, which is {@code $HOME} when that home is the global
     *       one. Reading {@code user.home} directly here is what let issue #18
     *       project skills into the operator's real {@code ~/.claude}; reading
     *       {@code $HOME} <em>correctly</em> is what let issue #145 do the same
     *       thing from a project home.</li>
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
        return claude(AgentHomes::resolve, agentHomeRoot(AgentHomes::resolve));
    }

    /**
     * The same precedence applied to an explicit environment rather than the
     * ambient one — used when deciding where a launch <em>would</em> put
     * Claude before starting it (see
     * {@code dev.skillmanager.launch.LaunchEnv}).
     *
     * <p>It shares {@link #claude(java.util.function.Function, Path)} with
     * {@link #claude()} precisely so there is still exactly one place the
     * {@code CLAUDE_CONFIG_DIR} → {@code CLAUDE_HOME} → fallback precedence is
     * written down. A launcher that re-derived it could disagree with the
     * reader, and then a launch could pass its own isolation check while
     * skill-manager wrote skills somewhere else — the split brain
     * {@link #claude()}'s javadoc describes, reintroduced from the other side.
     *
     * <h2>The one thing it does not share: the last-resort base</h2>
     *
     * <p>{@link #claude()} falls back to {@link #agentHomeRoot()}, which
     * derives the root from {@code SKILL_MANAGER_HOME}. <b>This method must
     * not</b>, and the reason is that the two methods answer questions about
     * two different programs. {@link #claude()} answers "where will
     * <em>skill-manager</em> write this unit", and skill-manager implements the
     * {@code SKILL_MANAGER_HOME} rule. This one answers "where will the
     * <em>Claude CLI</em> read its config once we hand it this environment",
     * and the Claude CLI has never heard of {@code SKILL_MANAGER_HOME}: it
     * reads {@code CLAUDE_CONFIG_DIR}, and failing that, {@code $HOME/.claude}.
     *
     * <p>Deriving the child's answer from {@code SKILL_MANAGER_HOME} would make
     * this method predict a directory the child will never look in, and
     * {@code LaunchEnv#requireClaudeRedirected} — whose whole job is to refuse
     * a launch that would leave Claude reading the operator's real config —
     * would be satisfied by an env block that does not actually redirect
     * anything. The gate would go quiet in exactly the case it exists for. So
     * the fallback is the launch env's own {@code HOME}, and a descriptor that
     * declares no Claude variable is still refused.
     *
     * <p>A key absent from {@code env} is treated as unset, <em>not</em> as
     * "fall back to the ambient variable". A launch environment is the
     * complete statement of what the child gets, so an ambient
     * {@code CLAUDE_CONFIG_DIR} that the launch does not pass on must not
     * influence the answer.
     */
    public static ClaudeHome claude(Map<String, String> env) {
        Map<String, String> source = env == null ? Map.of() : env;
        java.util.function.Function<String, Path> lookup = key -> {
            String value = source.get(key);
            return value == null || value.isBlank() ? null : Path.of(value);
        };
        return claude(lookup, userHome(lookup));
    }

    /**
     * The precedence itself, over a lookup and the caller's last-resort root.
     * See {@link #claude(Map)} for why the root is the caller's and everything
     * above it is not.
     */
    private static ClaudeHome claude(java.util.function.Function<String, Path> lookup,
                                     Path fallbackRoot) {
        Path explicitConfigDir = lookup.apply(CLAUDE_CONFIG_DIR);
        if (explicitConfigDir != null) {
            Path parent = explicitConfigDir.getParent();
            // CLAUDE_CONFIG_DIR set: the CLI relocates the whole config tree,
            // .claude.json included. See ClaudeHome for the measurement.
            return new ClaudeHome(parent != null ? parent : explicitConfigDir, explicitConfigDir,
                    explicitConfigDir.resolve(CLAUDE_CONFIG_FILENAME));
        }
        Path root = lookup.apply(CLAUDE_HOME);
        if (root == null) root = fallbackRoot;
        Path configDir = root.resolve(CLAUDE_DIR_NAME);
        // CLAUDE_CONFIG_DIR unset: the CLI reads $HOME/.claude.json. That
        // sentence is true of the home directory and of NOTHING ELSE, and this
        // branch used to apply it to whatever root came out of the lookup. See
        // #claudeConfigFileFor.
        return new ClaudeHome(root, configDir, claudeConfigFileFor(root, configDir, lookup));
    }

    /**
     * Where Claude Code actually reads its {@code mcpServers} from, for a home
     * rooted at {@code root} with {@code CLAUDE_CONFIG_DIR} unset.
     *
     * <h2>The sentence that was true and the branch it was not true of</h2>
     *
     * <p>{@link ClaudeHome}'s javadoc already states the rule: "Claude Code
     * reads {@code $CLAUDE_CONFIG_DIR/.claude.json} when the variable is set and
     * {@code ~/.claude.json} when it is not — the file moves <em>with the config
     * dir</em>, it does not stay beside it." Only the first half was
     * implemented. The second was written as {@code root.resolve(".claude.json")}
     * under the comment "for the global home that is {@code ~/.claude.json}" —
     * which is exactly right, and is a statement about the global home rather
     * than about {@code root}.
     *
     * <p>{@link #claude(Map)}'s javadoc makes the argument this method is:
     * "<b>the Claude CLI has never heard of {@code SKILL_MANAGER_HOME}</b>: it
     * reads {@code CLAUDE_CONFIG_DIR}, and failing that, {@code $HOME/.claude}."
     * It was applied to the launch-env variant's fallback base and not to this
     * branch, so with {@code SKILL_MANAGER_HOME} pointing at a project home and
     * no Claude variable set, skill-manager derived {@code root} from
     * {@code SKILL_MANAGER_HOME} — correctly, that IS where skill-manager writes
     * — and then wrote the MCP entry to {@code <repo>/.claude.json}, a path
     * nothing on the machine reads, while reporting {@code ADDED 1} and
     * {@code ACTION_REQUIRED: restart Claude}. Measured on this repository:
     * {@code <repo>/.claude.json} holds the virtual-mcp-gateway entry and
     * {@code <repo>/.claude/.claude.json} does not exist.
     *
     * <h2>What it resolves to now</h2>
     *
     * <ul>
     *   <li>{@code root} IS this environment's {@code $HOME} — the genuine
     *       global home, and the only case where the CLI's unset-variable
     *       default lands there — {@code $HOME/.claude.json}, unchanged. That
     *       is where the operator's own entries live and moving it would be
     *       this same defect pointed at them.</li>
     *   <li>Anything else — {@code <root>/.claude/.claude.json}, which is
     *       {@code <configDir>/.claude.json}: precisely where a launch through
     *       this home lands, because {@link dev.skillmanager.store.HomeDescriptor}
     *       exports {@code CLAUDE_CONFIG_DIR=<root>/.claude} and the branch
     *       above then resolves the file inside it. The writer and the reader
     *       agree by construction rather than by coincidence.</li>
     * </ul>
     *
     * <p>Refusing instead was the alternative, and it is worse here: a home
     * whose descriptor declares {@code CLAUDE_CONFIG_DIR} is the normal case, so
     * the refusal would fire only for the operator who never launches through
     * the shim — and there is a correct answer to give them, one that the shim
     * will agree with the moment they do.
     *
     * <p>Compared by RESOLVED PHYSICAL PATH: {@code /var} vs {@code /private/var}
     * has now defeated four checks in this codebase, and a fixture {@code HOME}
     * under {@code /tmp} is exactly the shape that defeats them.
     */
    private static Path claudeConfigFileFor(Path root, Path configDir,
                                            java.util.function.Function<String, Path> lookup) {
        Path home = userHome(lookup);
        boolean isTheHomeDirectory = home != null && sameDirectory(root, home);
        return isTheHomeDirectory
                ? root.resolve(CLAUDE_CONFIG_FILENAME)
                : configDir.resolve(CLAUDE_CONFIG_FILENAME);
    }

    /**
     * Whether two paths name one directory, symlinks resolved, whether or not
     * either exists yet — a comparison that can be defeated by a spelling is a
     * comparison that will be.
     *
     * <p>This method briefly carried its own copy of the resolver. It is
     * {@link dev.skillmanager.shared.util.Fs#realOrNormalized} now and the copy
     * is gone, because the launch PATH sanitizer needed exactly the same
     * resolution and did not have it — which is how a symlinked foreign-home
     * bin came to decide an INSTALL. Two spellings of one rule is the failure
     * this class's own {@link #userHome()} javadoc says it has already paid for
     * three times.
     */
    private static boolean sameDirectory(Path a, Path b) {
        return dev.skillmanager.shared.util.Fs.realOrNormalized(a)
                .equals(dev.skillmanager.shared.util.Fs.realOrNormalized(b));
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
