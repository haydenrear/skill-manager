package dev.skillmanager.store;

import java.nio.file.Path;

/**
 * A filesystem mutation was refused because it resolved outside every root the
 * operation declared it may write under.
 *
 * <h2>Unchecked, and that is the point</h2>
 *
 * <p>The two DEF-007 call sites both sit inside {@code catch (IOException)}
 * blocks that log a warning and carry on — {@code CliShimPruner.prune} says
 * "best-effort per entry", and
 * {@code InstallerRegistry.takeOwnershipOfShim} warns and returns null. A
 * checked exception would be caught by exactly those handlers and turned back
 * into the thing this class exists to stop being: a line in a log. The epic's
 * slice says <b>REFUSED, not logged</b>, so this is a {@link RuntimeException}
 * and it goes past them.
 *
 * <p>Inside a program it surfaces as a failed {@code EffectReceipt} carrying
 * this message, because {@code LiveInterpreter.runEffects} traps
 * {@code Exception}. That is the wanted behaviour: the operation that would
 * have escaped fails, loudly, and the rest of the program halts or continues on
 * its own declared continuation policy.
 *
 * <h2>The message names the path AND the home</h2>
 *
 * <p>Both, and separately the path as SPELLED and as RESOLVED, because in every
 * measured instance of this defect those two differed and the difference was the
 * whole bug: {@code homeB/bin/cli/alpha} that is really {@code homeA/bin/cli/alpha}.
 * A refusal printing only the spelling would name a path that looks perfectly
 * fine.
 */
public final class WriteOutsideHomeException extends RuntimeException {

    /**
     * Exit status when this reaches the top of the CLI.
     *
     * <p>13, the next unallocated code after {@code HomeSync}'s 12. Deliberately
     * its own: an operator or a script that sees it has hit a confinement
     * refusal and not a missing home (2), a frozen home (9) or a fetch failure
     * (10), and the remedy for each of those is different.
     */
    public static final int EXIT_CODE = 13;

    private final transient Path spelled;
    private final transient Path resolved;
    private final transient Path home;

    WriteOutsideHomeException(String what, String verb, Path spelled, Path resolved,
                              WriteConfinement.Scope scope) {
        super(message(what, verb, spelled, resolved, scope));
        this.spelled = spelled;
        this.resolved = resolved;
        this.home = scope == null ? null : scope.home();
    }

    /** The path as the caller spelled it. */
    public Path spelled() { return spelled; }

    /** Where that path actually resolves — the bytes that would have been hit. */
    public Path resolved() { return resolved; }

    /** The home the refused operation was given. */
    public Path home() { return home; }

    private static String message(String what, String verb, Path spelled, Path resolved,
                                  WriteConfinement.Scope scope) {
        StringBuilder out = new StringBuilder();
        out.append("refused: ").append(what == null ? "this operation" : what)
                .append(" would ").append(verb)
                .append(" a path outside the home it was given");
        out.append("\n  path:     ").append(spelled);
        if (resolved != null && !resolved.equals(spelled)) {
            out.append("\n  resolves: ").append(resolved)
                    .append("  <- outside the home, through a symlink");
        }
        if (scope != null) {
            out.append("\n  home:     ").append(scope.home());
            if (scope.roots() != null && scope.roots().size() > 1) {
                out.append("\n  also permitted: ")
                        .append(scope.roots().subList(1, scope.roots().size()));
            }
        }
        return out.toString();
    }
}
