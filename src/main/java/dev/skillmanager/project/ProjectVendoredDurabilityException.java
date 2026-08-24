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

    private final String projectName;
    private final transient ProjectVendoredResolver.Report report;

    public ProjectVendoredDurabilityException(String projectName,
                                              ProjectVendoredResolver.Report report) {
        super(report.failureMessage());
        this.projectName = projectName;
        this.report = report;
    }

    /** The project whose declared paths are not durable. */
    public String projectName() { return projectName; }

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
                + "move, a clone or a worktree, so this project's realization was NOT refreshed "
                + "(the unit sync itself is unaffected) — repair it with `skill-manager project "
                + "resolve --project-dir <" + projectName + "> --repair-vendored`";
    }
}
