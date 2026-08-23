package dev.skillmanager.project;

import dev.skillmanager.sandbox.Confinement;
import dev.skillmanager.sandbox.ConfinementEscapeException;

import java.nio.file.Path;

/**
 * <b>The one place a command turns "which project is this about?" into a
 * path.</b>
 *
 * <h2>Why it is one place</h2>
 *
 * <p>Six call sites spelled the same three lines — {@code projectDir == null ||
 * projectDir.isBlank() ? Path.of(System.getProperty("user.dir")) :
 * Path.of(projectDir)} — across {@code ProjectCommand}'s five verbs and
 * {@code EnvCommand}. Five of them are the {@code project} family; the sixth is
 * {@code env}, which nobody would have thought to change. That is the shape
 * this epic keeps meeting: a rule held in N copies, where the copy that would
 * have caught the defect is always the one nobody edited.
 *
 * <h2>What it adds: the CWD axis is checked against the declared confinement</h2>
 *
 * <p>{@code SKILL_MANAGER_HOME} and the working directory are two answers to
 * "which project is this command about", and until #237 nothing ever compared
 * them. When a process has declared a {@link Confinement} and the project root
 * it would act on lies outside that root, the command <b>refuses and names the
 * conflict</b> rather than acting on a project the caller never meant to name.
 *
 * <p><b>Nothing changes for an unconfined process.</b> {@link Confinement#covers}
 * answers true for everything when no confinement was declared, so the operator
 * flow — {@code cd ~/myrepo && skill-manager project resolve}, with the root
 * home in {@code SKILL_MANAGER_HOME} and the project's child home somewhere
 * else entirely — behaves exactly as it did. A rule that fired without a
 * declaration would refuse the product's main path; see {@code Confinement}'s
 * class comment.
 *
 * <h2>An explicit {@code --project-dir} is checked too</h2>
 *
 * <p>It would be defensible to check only the CWD-derived case, since that is
 * the one that escaped. It is not correct: a confinement is a statement about
 * what this process may touch, not about how it spelled the path. What differs
 * is the message — {@link ConfinementEscapeException#origin()} says which of
 * the two happened, because the remedy differs.
 */
public final class ProjectRoot {

    /** {@link #resolve}'s answer, and where it came from. */
    public record Resolved(Path path, boolean fromWorkingDirectory) {}

    /** The {@code origin} wording for a root taken from the working directory. */
    public static final String FROM_CWD = "the working directory";

    /** The {@code origin} wording for a root given on the command line. */
    public static final String FROM_OPTION = "--project-dir";

    private ProjectRoot() {}

    /**
     * The project root for a command, refusing when it escapes this process's
     * declared confinement.
     *
     * @param projectDirOption the {@code --project-dir} value, or null/blank
     * @param verb             the command name, for the refusal message
     * @throws ConfinementEscapeException when a confinement is declared and the
     *                                    resolved root is outside it
     */
    public static Path resolve(String projectDirOption, String verb)
            throws ConfinementEscapeException {
        return resolve(projectDirOption, verb, Confinement.current()).path();
    }

    /**
     * {@link #resolve(String, String)} against a stated {@link Confinement},
     * and reporting where the root came from.
     *
     * <p>The confinement is a parameter rather than read inside so a test can
     * drive both sides of the guard without a thread-local, and so the report
     * carried in the refusal is the same object the decision was made from —
     * not a second read that could have changed in between.
     */
    public static Resolved resolve(String projectDirOption, String verb, Confinement confinement)
            throws ConfinementEscapeException {
        boolean fromCwd = projectDirOption == null || projectDirOption.isBlank();
        Path root = fromCwd
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectDirOption);
        root = root.toAbsolutePath().normalize();
        if (!confinement.covers(root)) {
            throw new ConfinementEscapeException(
                    verb, root, fromCwd ? FROM_CWD : FROM_OPTION, confinement);
        }
        return new Resolved(root, fromCwd);
    }
}
