import com.hayden.testgraphsdk.sdk.NodeContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE place a test-graph node states the environment of a skill-manager
 * child process.
 *
 * <h2>The bug this exists to make impossible</h2>
 *
 * Issue #18: a node that spawns {@code skill-manager install} with only
 * {@code SKILL_MANAGER_HOME} redirected gets its <em>store</em> write in the
 * sandbox and its <em>agent projection</em> in the operator's real
 * {@code ~/.claude}, {@code ~/.codex} and {@code ~/.gemini}. The projection
 * resolves through {@code dev.skillmanager.agent.AgentHomes}, whose last resort
 * is {@code $HOME} and then the JVM's {@code user.home} — and on macOS the JVM
 * derives {@code user.home} from the OS, so nothing a parent process exports can
 * move it. The symlinks outlive the run and dangle once the temp home is
 * deleted; one of them appeared in a live agent's available-skills list.
 *
 * <p>#18's production fix (prefer {@code $HOME} over {@code user.home}) made
 * sandboxing <b>possible</b>. It did not make it <b>automatic</b>: issue #30
 * measured <b>50</b> of the <b>105</b> CLI env sites in {@code test_graph/sources}
 * passing neither the agent-home variables nor {@code HOME}, so their fallback
 * resolved to the inherited real home and they still leaked.
 *
 * <h2>Why one definition and not a 54th spelling</h2>
 *
 * Before this class the same recipe was written down four times, in four
 * fidelities, and they disagreed:
 *
 * <table>
 *   <caption>the four pre-existing spellings</caption>
 *   <tr><th>where</th><th>what it set</th></tr>
 *   <tr><td>{@code HomeCloneSupport.sm}</td>
 *       <td>all five, plus {@code HOME} and {@code JAVA_TOOL_OPTIONS}</td></tr>
 *   <tr><td>{@code HomeSyncSupport.sm}</td>
 *       <td>the same, copied</td></tr>
 *   <tr><td>{@code ChildHomeSupport.sm}</td>
 *       <td>no {@code CLAUDE_CONFIG_DIR}, no {@code HOME}</td></tr>
 *   <tr><td>{@code MarkdownImportFixture.install}</td>
 *       <td>all five, and still leaked — see #18's second comment</td></tr>
 * </table>
 *
 * <p>That is the epic's recurring shape (#24): two paths that should agree,
 * don't, and nothing detects the disagreement. So this is a choke point, and
 * {@code sources/sandbox/SandboxEnvContract.java} is the oracle that keeps it
 * one — it fails when any file other than this one writes
 * {@code SKILL_MANAGER_HOME} into a child environment, and it proves itself
 * sensitive on a synthetic node every run.
 *
 * <h2>The measured recipe</h2>
 *
 * Redirect {@code SKILL_MANAGER_HOME} <b>plus</b> {@link #CLAUDE_HOME},
 * {@link #CLAUDE_CONFIG_DIR}, {@link #CODEX_HOME} and {@link #GEMINI_HOME}.
 * {@code SKILL_MANAGER_HOME} alone is <b>not</b> enough: a session established
 * that at the cost of an incident, projecting five units into all three of the
 * operator's real agent homes and needing 18 symlinks repaired.
 *
 * <h2>Why {@code HOME} is opt-in and not part of the recipe</h2>
 *
 * {@code HOME} also relocates the jbang, uv, npm and git caches the graphs
 * depend on, which makes them slow or broken in ways that look like real
 * failures. The four agent variables above are sufficient for projection,
 * because every projection path resolves through {@code AgentHomes} and each of
 * those variables outranks the {@code $HOME}/{@code user.home} fallback. A node
 * that genuinely wants a hermetic {@code $HOME} — {@code home-clone} and
 * {@code home-sync} do, because they assert that nothing anywhere names another
 * home — asks for it with {@link #alsoRedirectPosixHome}.
 *
 * <h2>The fallback is never the operator's home</h2>
 *
 * {@link #sandboxOf} prefers the sandbox {@code env.prepared} published, and
 * when a graph published none it derives one from the skill-manager home under
 * test rather than leaving the variables unset. The three pre-existing
 * spellings all did the latter — {@code if (claude != null) put(...)} — which is
 * a silent no-op that reintroduces the leak whenever the upstream context is
 * missing. An unset agent variable is exactly the state #18 describes, so this
 * class never produces one.
 */
final class SmEnv {

    static final String SKILL_MANAGER_HOME = "SKILL_MANAGER_HOME";
    static final String SKILL_MANAGER_INSTALL_DIR = "SKILL_MANAGER_INSTALL_DIR";
    static final String CLAUDE_HOME = "CLAUDE_HOME";
    static final String CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    static final String CODEX_HOME = "CODEX_HOME";
    static final String GEMINI_HOME = "GEMINI_HOME";
    /**
     * The variable a process sets to declare "everything I touch lives under
     * this directory" (#237). Not one of the four agent roots and not the
     * store: it is the axis check that spans all of them PLUS the working
     * directory, which is the one a JVM cannot pin and the one DEF-046 escaped
     * through.
     *
     * <p>It lives here for the reason everything else does — the recipe is
     * written down once. A node that spelled it itself would be the fifth copy
     * this class exists to prevent.
     */
    static final String CONFINE_ROOT = "SKILL_MANAGER_CONFINE_ROOT";

    static final String POSIX_HOME = "HOME";
    static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";

    /**
     * The four variables that decide where an agent projection lands. All four,
     * always: {@code CLAUDE_CONFIG_DIR} outranks {@code CLAUDE_HOME} in
     * {@code AgentHomes.claude()}, and a context that sets only one of them used
     * to get a split brain where the CLI wrote to one directory and
     * skill-manager symlinked into another.
     */
    static final List<String> AGENT_VARS =
            List.of(CLAUDE_HOME, CLAUDE_CONFIG_DIR, CODEX_HOME, GEMINI_HOME);

    /** The name of the directory {@code Claude Code} keeps its config tree in. */
    static final String CLAUDE_DIR_NAME = ".claude";

    private SmEnv() {}

    // ------------------------------------------------------------ locations

    /**
     * The skill-manager repository root, as every node already computes it:
     * nodes run with {@code user.dir} at {@code test_graph/}.
     */
    static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
    }

    /** The real CLI entrypoint. */
    static Path cli() {
        return repoRoot().resolve("skill-manager");
    }

    // -------------------------------------------------------------- sandbox

    /**
     * A complete statement of where a child's agent state may go. Four values,
     * never three: {@code claudeConfigDir} is carried explicitly rather than
     * left to the child to derive, because the derivation is what disagreed.
     */
    record Sandbox(String claudeRoot, String claudeConfigDir, String codexHome, String geminiHome) {

        /** True when no field names a path under {@code home}. */
        boolean avoids(Path home) {
            String prefix = home.toAbsolutePath().normalize().toString();
            for (String v : List.of(claudeRoot, claudeConfigDir, codexHome, geminiHome)) {
                if (v == null || v.isBlank()) return false;
                Path p = Path.of(v).toAbsolutePath().normalize();
                if (p.startsWith(prefix)) return false;
            }
            return true;
        }
    }

    /**
     * The {@code env.prepared} layout, derived from one root: Claude reads
     * {@code <root>/.claude}, codex {@code <root>/.codex}, gemini
     * {@code <root>/.gemini}.
     */
    static Sandbox sandboxUnder(Path agentRoot) {
        Path root = agentRoot.toAbsolutePath().normalize();
        return new Sandbox(
                root.toString(),
                root.resolve(CLAUDE_DIR_NAME).toString(),
                root.resolve(".codex").toString(),
                root.resolve(".gemini").toString());
    }

    /**
     * Three roots a node named itself, with the config dir derived the same way
     * {@code AgentHomes.claude()} derives it from {@code CLAUDE_HOME} — so
     * passing it changes nothing except that it is no longer a fallback.
     */
    static Sandbox sandbox(String claudeRoot, String codexHome, String geminiHome) {
        return new Sandbox(
                claudeRoot,
                Path.of(claudeRoot).resolve(CLAUDE_DIR_NAME).toString(),
                codexHome,
                geminiHome);
    }

    /** {@link #sandbox(String, String, String)} for callers holding paths. */
    static Sandbox sandbox(Path claudeRoot, Path codexHome, Path geminiHome) {
        return sandbox(claudeRoot.toString(), codexHome.toString(), geminiHome.toString());
    }

    /**
     * The sandbox for this run: {@code env.prepared}'s when the graph published
     * one, otherwise {@code <smHome>/agent-home}.
     *
     * <p>Never the operator's home, and never unset — see the class comment.
     */
    static Sandbox sandboxOf(NodeContext ctx, String smHome) {
        String claude = ctx == null ? null : ctx.get("env.prepared", "claudeHome").orElse(null);
        if (claude != null && !claude.isBlank()) {
            String codex = ctx.get("env.prepared", "codexHome")
                    .orElse(Path.of(claude).resolve(".codex").toString());
            String gemini = ctx.get("env.prepared", "geminiHome")
                    .orElse(Path.of(claude).resolve(".gemini").toString());
            return new Sandbox(
                    claude,
                    Path.of(claude).resolve(CLAUDE_DIR_NAME).toString(),
                    codex,
                    gemini);
        }
        return sandboxUnder(Path.of(smHome).resolve("agent-home"));
    }

    // ------------------------------------------------------------------ env

    /** The complete variable set, as a map, for callers that build one. */
    static Map<String, String> env(String smHome, String installDir, Sandbox sandbox) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(SKILL_MANAGER_HOME, smHome);
        out.put(SKILL_MANAGER_INSTALL_DIR, installDir);
        out.put(CLAUDE_HOME, sandbox.claudeRoot());
        out.put(CLAUDE_CONFIG_DIR, sandbox.claudeConfigDir());
        out.put(CODEX_HOME, sandbox.codexHome());
        out.put(GEMINI_HOME, sandbox.geminiHome());
        return out;
    }

    /** {@link #env(String, String, Sandbox)} against {@code env.prepared}. */
    static Map<String, String> env(NodeContext ctx, String smHome) {
        return env(smHome, repoRoot().toString(), sandboxOf(ctx, smHome));
    }

    // ---------------------------------------------------------------- apply

    /** Put the complete variable set on {@code pb}, and return {@code pb}. */
    static ProcessBuilder apply(ProcessBuilder pb, String smHome, String installDir,
                                Sandbox sandbox) {
        pb.environment().putAll(env(smHome, installDir, sandbox));
        return pb;
    }

    /** {@link #apply} with the install dir at {@link #repoRoot()}. */
    static ProcessBuilder apply(ProcessBuilder pb, String smHome, Sandbox sandbox) {
        return apply(pb, smHome, repoRoot().toString(), sandbox);
    }

    /** {@link #apply} against {@code env.prepared}'s sandbox. */
    static ProcessBuilder apply(NodeContext ctx, ProcessBuilder pb, String smHome) {
        return apply(pb, smHome, repoRoot().toString(), sandboxOf(ctx, smHome));
    }

    /** {@link #apply(NodeContext, ProcessBuilder, String)} for a path home. */
    static ProcessBuilder apply(NodeContext ctx, ProcessBuilder pb, Path smHome) {
        return apply(ctx, pb, smHome.toString());
    }

    /**
     * {@link #apply(NodeContext, ProcessBuilder, Path)} with ONE agent-home
     * variable deliberately pointed somewhere wrong.
     *
     * <h2>Why a misconfiguration needs a sanctioned spelling</h2>
     *
     * <p>{@code sandbox.env.contract} allows exactly one file to write the six
     * managed variables into a child, and this is that file. A node asserting
     * what happens under a BAD value therefore cannot set it at the call site;
     * without this helper the only way to write such a node is to break the
     * single-writer rule the contract exists to keep.
     *
     * <p>The node that needs it is {@code InstallKeepsItsOwnPlugins}: #311 is
     * reached by pointing {@code CODEX_HOME} at a Skill Manager home, because
     * that variable names the config directory ITSELF rather than its parent,
     * so {@code CodexAgent.pluginsDir()} becomes the store's own
     * {@code plugins/}. Reproducing the defect requires writing the bad value;
     * asserting the fix requires reproducing the defect.
     *
     * @param name  one of the managed variables
     * @param value the deliberately wrong value
     */
    static ProcessBuilder applyWithAgentHomeOverride(NodeContext ctx, ProcessBuilder pb,
                                                     Path smHome, String name, String value) {
        apply(ctx, pb, smHome);
        pb.environment().put(name, value);
        return pb;
    }

    /**
     * Only the four agent variables, for a child that is an <em>agent</em> CLI
     * rather than skill-manager — {@code claude plugin list} reads the same
     * roots but has no {@code SKILL_MANAGER_HOME} to speak of.
     *
     * <p>It lives here rather than at the call site for the same reason as the
     * rest: {@code CLAUDE_CONFIG_DIR} alone used to be considered enough for
     * these, and that is precisely the split brain
     * {@code AgentHomes.claude()}'s javadoc describes.
     */
    static ProcessBuilder applyAgentHomes(ProcessBuilder pb, Sandbox sandbox) {
        pb.environment().put(CLAUDE_HOME, sandbox.claudeRoot());
        pb.environment().put(CLAUDE_CONFIG_DIR, sandbox.claudeConfigDir());
        pb.environment().put(CODEX_HOME, sandbox.codexHome());
        pb.environment().put(GEMINI_HOME, sandbox.geminiHome());
        return pb;
    }

    /**
     * Additionally redirect the POSIX home and the JVM's {@code user.home}.
     *
     * <p><b>Opt-in on purpose.</b> This also relocates the jbang, uv, npm and
     * git caches, so a graph that does not need it pays for it in wall-clock
     * time and in failures that look real. Ask for it when the node's claim is
     * about the whole home — {@code home-clone} asserts that nothing anywhere in
     * a clone names another home, and an unpredicted {@code user.home} read
     * would break that claim rather than merely slow it down.
     *
     * <p>{@code JAVA_TOOL_OPTIONS} is the belt to {@code HOME}'s braces: on
     * macOS a JVM's {@code user.home} comes from the OS and ignores
     * {@code $HOME}, and every JVM honours {@code JAVA_TOOL_OPTIONS}.
     */
    static ProcessBuilder alsoRedirectPosixHome(ProcessBuilder pb, String sandboxRoot) {
        pb.environment().put(POSIX_HOME, sandboxRoot);
        pb.environment().put(JAVA_TOOL_OPTIONS, "-Duser.home=" + sandboxRoot);
        return pb;
    }

    /** {@link #alsoRedirectPosixHome(ProcessBuilder, String)} for a path root. */
    static ProcessBuilder alsoRedirectPosixHome(ProcessBuilder pb, Path sandboxRoot) {
        return alsoRedirectPosixHome(pb, sandboxRoot.toString());
    }

    /**
     * Declare this child process CONFINED to {@code root}: every axis that
     * decides where it writes — the store, the three agent roots, and the
     * WORKING DIRECTORY — must resolve inside it, or the command refuses.
     *
     * <p>Opt-in, and deliberately not part of {@link #apply}'s recipe. A
     * confined {@code project} verb refuses a target taken from a working
     * directory outside the root, and most graph nodes legitimately drive
     * fixtures from wherever the graph put them. Ask for it when the node's
     * claim is about confinement itself.
     *
     * @see #unconfine
     */
    static ProcessBuilder confineTo(ProcessBuilder pb, Path root) {
        pb.environment().put(CONFINE_ROOT, root.toString());
        return pb;
    }

    /**
     * The CONTROL for {@link #confineTo}: the identical child with the
     * confinement removed, including any the parent process inherited.
     *
     * <p>Its own method rather than "just don't call confineTo", because a
     * vacuity check whose control silently inherits the very variable it means
     * to remove is a green run that proves nothing — mechanism C in this
     * epic's vacuity ledger.
     */
    static ProcessBuilder unconfine(ProcessBuilder pb) {
        pb.environment().remove(CONFINE_ROOT);
        return pb;
    }
}
