package dev.skillmanager.store;

/**
 * Whether the invocation now running is allowed to <em>create</em> a
 * skill-manager home.
 *
 * <h2>The defect this exists to make impossible</h2>
 *
 * <p>{@code skill-manager --version} used to materialize a home:
 *
 * <pre>{@code
 * $ mkdir -p decoy
 * $ SKILL_MANAGER_HOME="$PWD/decoy" skill-manager --version
 * skill-manager 0.19.2
 * $ find decoy | wc -l
 * 13
 * }</pre>
 *
 * <p>Twelve directories — {@code cache bin bin/cli bin/mcp plugins projects
 * venvs harnesses installed docs skills npm} — from the one command an
 * onboarding checklist tells an agent to run to <em>prove</em> a home works.
 * With {@code SKILL_MANAGER_HOME} unset that is the operator's global home.
 *
 * <p>The cause was not {@code --version}. Every invocation ran
 * {@code SkillStore.defaultStore().init()} from the CLI's execution strategy
 * before the parsed command ever executed, so the home layout was a side
 * effect of <em>starting the process</em> rather than of doing work. That is
 * issue #33's shape ("a read-only-looking command writes to a home") at the
 * one site the #60 sweep did not cover, and it is the same class of bug as
 * the incident in this epic where a subagent running an unrelated command
 * with only {@code SKILL_MANAGER_HOME} redirected projected 15 symlinks into
 * the operator's three real agent homes.
 *
 * <h2>Why a declared access mode and not a {@code --version} special case</h2>
 *
 * <p>A {@code --version}-only patch leaves the class alive: the next
 * informational command added to the tree inherits the eager scaffold again,
 * silently, and nothing detects it. So the rule is stated once, as data —
 * {@link dev.skillmanager.cli.CommandHomeAccess} classifies every command
 * path in {@link dev.skillmanager.cli.CliMetadata} as {@link Access#READ_ONLY}
 * or {@link Access#WRITES_HOME} — the CLI {@linkplain #declare(Access)
 * declares} the mode of the command it parsed, and {@link SkillStore#init()}
 * is the single choke point that honours it.
 *
 * <p>Read paths never needed the scaffold: every listing in {@link SkillStore}
 * already guards on {@code Files.isDirectory}, and a missing directory is
 * indistinguishable from an empty one for a reader. The scaffold only ever
 * served writers.
 *
 * <h2>The default is permissive, on purpose</h2>
 *
 * <p>{@link Access#WRITES_HOME} is the default so that every embedded caller
 * that never declares anything — unit tests, the server, an out-of-tree
 * library user — keeps exactly the behaviour it had. Failing open toward the
 * old behaviour means this change can only ever remove writes from paths that
 * were explicitly classified as read-only, never add a missing directory to a
 * writer. The classification's completeness is what makes that safe, and
 * {@code CommandHomeAccess} is tested against {@code CliMetadata} so a new
 * command cannot be added without being classified.
 *
 * <h2>Scope</h2>
 *
 * <p>Process-global rather than per-{@link SkillStore}: a single CLI
 * invocation runs one command, and that command's classification governs
 * every store it opens — including the second and third stores that
 * {@code home clone}, {@code home sync} and the child-home scaffolder build
 * for the <em>other</em> home. Threading the mode through every constructor
 * would give each of those sites its own chance to forget it, which is how
 * the four disagreeing copies of the sandbox env recipe happened (#30).
 */
public final class HomeScaffold {

    /** What an invocation is permitted to do to a home that does not exist yet. */
    public enum Access {
        /**
         * Informational: reads a home if one is there, and creates nothing if
         * one is not. {@link SkillStore#init()} is a no-op under this mode.
         */
        READ_ONLY,
        /**
         * May create the home layout. First-run creation for {@code install},
         * {@code sync}, {@code home clone}, {@code project resolve} and every
         * other writer happens exactly as before.
         */
        WRITES_HOME
    }

    private static volatile Access current = Access.WRITES_HOME;

    private HomeScaffold() {}

    /** State the access mode of the command about to run. */
    public static void declare(Access access) {
        current = access == null ? Access.WRITES_HOME : access;
    }

    /** The declared mode; {@link Access#WRITES_HOME} when nothing declared one. */
    public static Access declared() { return current; }

    /** True when the current invocation may create home directories. */
    public static boolean mayScaffold() { return current == Access.WRITES_HOME; }

    /** Restore the permissive default. Tests pair this with {@link #declare}. */
    public static void reset() { current = Access.WRITES_HOME; }
}
