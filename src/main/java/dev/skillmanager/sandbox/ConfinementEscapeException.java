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

    /**
     * The refusal for a confined process whose STORE or AGENT roots resolve
     * outside its declared root — as distinct from one whose TARGET does.
     *
     * <p>Its own factory rather than a fifth argument, because the two say
     * different things to an operator: this one means "the environment you set
     * does not match the confinement you declared", and the remedy is to fix a
     * variable, not to pass {@code --project-dir}. Review of #241, H2.
     */
    public static ConfinementEscapeException forAxes(String verb, Confinement confinement) {
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(" refused — this process declared a confinement and ")
          .append(confinement.enforceableEscapes().size())
          .append(" of the roots that decide where it writes resolve outside it.\n")
          .append("  confinement root:  ").append(confinement.root()).append("\n")
          .append("  escaped axes:      ")
          .append(String.join(", ", confinement.enforceableEscapedAxes())).append("\n")
          .append(confinement.describe()).append("\n")
          .append("  point every axis above inside the confinement root, or clear $")
          .append(Confinement.ROOT_ENV).append(" if this process is not meant to be confined");
        // An UNSET axis is listed too, and that is the point: it resolves to
        // the operator's real ~/.claude eventually. SmEnv's class comment
        // records the cost the last time — five units projected into all three
        // real agent homes, eighteen symlinks repaired by hand.
        return new ConfinementEscapeException(sb.toString(), confinement);
    }

    private ConfinementEscapeException(String message, Confinement confinement) {
        super(message);
        this.target = null;
        this.origin = FROM_AXES;
        this.confinement = confinement;
    }

    /** {@link #origin()} for a refusal about the environment rather than a target. */
    public static final String FROM_AXES = "the declared confinement's own axes";

    public Path target() { return target; }

    public String origin() { return origin; }

    public Confinement confinement() { return confinement; }
}
