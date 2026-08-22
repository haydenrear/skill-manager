package dev.skillmanager.sandbox;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A confined process asked for an operation whose target lies outside the
 * confinement root it declared.
 *
 * <h2>Its own exit code, for the reason the others have theirs</h2>
 *
 * <p>"The command failed" and "the command was refused because it would have
 * escaped" are different things to a script and to an operator — the same
 * argument {@code SkillManagerCli.UNBINDABLE_HOME_EXIT_CODE} makes. A driver
 * that means to prove the guard fired must be able to tell a refusal from a
 * crash, and DEF-046's whole shape was an instrument that could not tell one
 * outcome from another.
 *
 * @see Confinement
 * @see dev.skillmanager.project.ProjectRoot
 */
public final class ConfinementEscapeException extends IOException {

    /** Free at the time of writing; 13 was the highest in use. */
    public static final int EXIT_CODE = 14;

    /** The {@code --json} discriminator, so a consumer branches on a type. */
    public static final String ERROR_CODE = "confinement_escape";

    private final transient Path target;
    private final transient Confinement confinement;
    private final String origin;

    /**
     * @param verb        the command that was refused, e.g. {@code project resolve}
     * @param target      the path it would have acted on
     * @param origin      where that path came from — {@code "the working
     *                    directory"} or {@code "--project-dir"}. Named because
     *                    the remedy differs: one is fixed by passing the flag,
     *                    the other by passing a different value.
     * @param confinement the report as it stood when the refusal was raised
     */
    public ConfinementEscapeException(String verb, Path target, String origin,
                                      Confinement confinement) {
        super(message(verb, target, origin, confinement));
        this.target = target;
        this.origin = origin;
        this.confinement = confinement;
    }

    /**
     * The message NAMES THE CONFLICT, which is the acceptance criterion: what
     * was going to be touched, where that came from, the root that forbids it,
     * and the remedy. A refusal that says only "refused" leaves the operator
     * to guess which of two answers to "which project is this about" won.
     */
    private static String message(String verb, Path target, String origin,
                                  Confinement confinement) {
        return verb + " refused — its target came from " + origin
                + " and lies outside this process's declared confinement.\n"
                + "  target:            " + target + "\n"
                + "  confinement root:  " + confinement.root() + "\n"
                + "  escaped axes:      " + String.join(", ", confinement.escapedAxes()) + "\n"
                + confinement.describe() + "\n"
                + "  pass --project-dir <dir> inside the confinement root, or clear $"
                + Confinement.ROOT_ENV + " if this process is not meant to be confined";
    }

    public Path target() { return target; }

    public String origin() { return origin; }

    public Confinement confinement() { return confinement; }
}
