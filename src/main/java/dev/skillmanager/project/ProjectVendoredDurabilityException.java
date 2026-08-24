package dev.skillmanager.project;

import java.io.IOException;

/**
 * A project's declared {@code [[vendored]]} paths do not survive a move, a
 * clone or a worktree — thrown by {@link ProjectDependencyResolver#resolve}
 * when {@link ProjectVendoredResolver.Report#fatalProblems()} is non-empty.
 *
 * <h2>Why this needs its own type (DEF-103)</h2>
 *
 * <p>It used to be a bare {@link IOException} carrying
 * {@link ProjectVendoredResolver.Report#failureMessage()}. Every caller
 * therefore saw it as "the project sync blew up", and one caller —
 * {@link dev.skillmanager.effects.SkillEffect.SyncClaimingProjects}, the
 * <em>best-effort</em> child-home refresh that runs after a unit sync at the
 * parent home — turned it into a {@code PROJECT_SYNC_FAILED} error stamped on
 * the unit and a non-zero exit for the whole sync.
 *
 * <p>Measured on the operator's root home on 2026-08-24: six registered
 * projects, one of them (<em>meta-harness</em>) carrying three absolutely
 * spelled vendored symlinks. All four attempted unit syncs into the root home
 * failed with {@code PROJECT_SYNC_FAILED: meta-harness}, and the promotion
 * direction the owner asked to enforce — project home to root home — could not
 * be used at all.
 *
 * <h2>What is scoped, and what is emphatically NOT weakened</h2>
 *
 * <p><b>The check is right.</b> An absolute symlink into the project's own tree
 * resolves today and does not survive a move, a clone or a worktree; that is
 * the same durability principle HIS-19 shipped for the CLI pin, and the equal
 * {@code resolves:} line is the trap rather than the defect. So:
 *
 * <ul>
 *   <li>{@code project resolve} and {@code project sync} — the commands whose
 *       subject <em>is</em> that project — still refuse, with this message, at
 *       the same exit code. Nothing about the finding is downgraded there.</li>
 *   <li>The post-sync child-home refresh reports it, names the project and the
 *       remedy, and <em>declines to refresh that one project</em> — but it does
 *       not fail the unit sync it was tacked onto, because the finding is not
 *       attributable to the unit whose bytes just moved.</li>
 * </ul>
 *
 * <p>That is HIS-9's confinement rule applied one layer up: refuse the write
 * you can attribute, not every write.
 */
public final class ProjectVendoredDurabilityException extends IOException {

    /**
     * What {@code resolve} had ALREADY DONE by the time the check refused —
     * MAJOR 2 of PR #255's review.
     *
     * <p>The advisory used to say "this project's realization was NOT
     * refreshed". Measured, that sentence is false:
     * {@link ProjectDependencyResolver#resolve} runs {@code register()},
     * {@code installMissing()} and {@code scaffold()} BEFORE
     * {@code checkVendored}, and the check's own javadoc explains why it has to
     * — the declared vendored source lives inside the child home the scaffolder
     * creates, so checking earlier would report every path dangling on a fresh
     * checkout. So units really are installed, the store's units-lock really is
     * rewritten, and the child home really is refreshed. Only bindings and the
     * project lock are not.
     *
     * <p>Pre-PR the operator saw exit 1 and went looking. Post-PR they see exit
     * 0, so a false "nothing happened" is strictly worse than it was: it is an
     * output carrying no way to detect its own invalidity, inside the fix for a
     * defect of exactly that family. These fields exist so the sentence
     * REPORTS rather than asserts.
     *
     * @param unitsInstalled     units newly installed into the store before the refusal
     * @param childHomeRefreshed whether the project's child home was scaffolded
     */
    public record PartialWork(int unitsInstalled, boolean childHomeRefreshed) {
        static final PartialWork UNKNOWN = new PartialWork(-1, false);

        /**
         * The clause naming what landed, or what is merely unknown.
         *
         * <p>The units-lock half is CONDITIONAL, and that is not fussiness.
         * {@code installMissing} returns before writing anything when nothing is
         * missing, so an unconditional "units-lock rewritten" would be false
         * exactly as often as the sentence it replaced — a second output with no
         * way to detect its own invalidity, inside the fix for the first one.
         * Verified against the early return at
         * {@code if (missing.isEmpty()) return ...}.
         */
        String describe() {
            if (unitsInstalled < 0) {
                return "how much of the refresh had already been applied was not recorded";
            }
            String installedClause = unitsInstalled > 0
                    ? unitsInstalled + " unit(s) installed into the store and the units-lock "
                            + "rewritten"
                    : "no units needed installing, so the store and its units-lock are untouched";
            return "PARTIALLY REFRESHED before the refusal — " + installedClause
                    + ", child home " + (childHomeRefreshed ? "scaffolded" : "not scaffolded")
                    + ", registration snapshot rewritten"
                    + "; bindings NOT materialized and the project lock NOT written";
        }
    }

    private final String projectName;
    private final transient java.nio.file.Path projectRoot;
    private final transient ProjectVendoredResolver.Report report;
    private final transient PartialWork partial;

    public ProjectVendoredDurabilityException(String projectName,
                                              java.nio.file.Path projectRoot,
                                              ProjectVendoredResolver.Report report,
                                              PartialWork partial) {
        super(report.failureMessage());
        this.projectName = projectName;
        this.projectRoot = projectRoot;
        this.report = report;
        this.partial = partial == null ? PartialWork.UNKNOWN : partial;
    }

    /** The project whose declared paths are not durable. */
    public String projectName() { return projectName; }

    /**
     * The project's own directory — MINOR 1 of PR #255's review.
     *
     * <p>{@link #advisory()} used to interpolate the project NAME into a
     * {@code --project-dir <…>} placeholder, producing
     * {@code --project-dir <skip-probe>}: angle brackets are shell redirection,
     * and the name is not the directory. The remedy could not be pasted.
     * #142 — a remedy that does not work is worse than none — and the
     * information was in hand the whole time, because {@code checkVendored}
     * holds the {@link dev.skillmanager.model.SkillProject}. The other new
     * reader in the same pull request
     * ({@code ProjectManifestRealization.render()}) already printed a real path;
     * two remedies in one PR, one runnable, is the divergence this closes.
     */
    public java.nio.file.Path projectRoot() { return projectRoot; }

    /** What had already been written when the refusal fired. */
    public PartialWork partial() { return partial; }

    /** Every finding, so a caller can render the whole thing rather than a summary. */
    public ProjectVendoredResolver.Report report() { return report; }

    /** How many declared paths are fatally non-durable. */
    public int fatalCount() { return report == null ? 0 : report.fatalProblems().size(); }

    /**
     * The one-line form a best-effort caller prints: what is wrong, whose it
     * is, what it costs, and the exact command that fixes it.
     */
    public String advisory() {
        return projectName + ": " + fatalCount() + " declared vendored path(s) do not survive a "
                + "move, a clone or a worktree. This project was " + partial.describe()
                + " (the unit sync itself is unaffected) — repair it with "
                + repairCommand();
    }

    /**
     * The repair, as a command that can be pasted.
     *
     * <p>Quoted through {@link dev.skillmanager.store.HomeDescriptor#shellQuote}
     * and naming the real directory, so a project root containing a space does
     * not silently become two arguments.
     */
    public String repairCommand() {
        String dir = projectRoot == null
                ? projectName
                : projectRoot.toAbsolutePath().normalize().toString();
        return "skill-manager project resolve --project-dir "
                + dev.skillmanager.store.HomeDescriptor.shellQuote(dir)
                + " --repair-vendored";
    }
}
