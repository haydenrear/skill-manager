package dev.skillmanager.store;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitRef;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reconciles one Skill Manager home against another, by copy, in whichever
 * direction the caller asks for.
 *
 * <h2>The problem</h2>
 *
 * <p>There are three tiers of home — the root one the operator installs into,
 * a copy per repository, and a copy per ticket worktree — and until now edits
 * only ever flowed <em>down</em>, at materialization time. Nothing carried a
 * change back up. A ticket agent that improved a skill inside its worktree home
 * lost the improvement the moment the worktree was removed, and the loss was
 * silent: the teardown succeeded, nothing was reported, and the only evidence
 * was a skill that had quietly stopped improving.
 *
 * <p>Symlinking the tiers together would make the problem disappear by making
 * the tiers disappear, and it was ruled out for the reason it always is: a link
 * is a single shared object, so two worktrees editing "their" copy are editing
 * each other's, and a half-written unit is visible everywhere at once. So the
 * tiers stay copies and the copies get reconciled.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not a second copier. Every byte written here goes through
 * {@link ChildHomeMaterializer}, which already owns the copy, the per-unit
 * materialization record, the refusal to overwrite a locally-edited unit, and
 * the stage-then-atomically-swap that makes an interrupted write leave the
 * previous unit intact. "Sync home A into home B" is the same operation as
 * "materialize a unit from a parent store into a child home", pointed at a
 * different pair of directories; treating it as a new problem would have
 * produced a second set of rules about when a unit may be overwritten, and the
 * two would have disagreed about exactly the units that matter.
 *
 * <p>What is genuinely new is above the unit: which units to visit, the lock
 * that keeps two syncs from interleaving across the home
 * ({@link HomeLock}), and the report.
 *
 * <h2>Direction is the caller's, safety is not</h2>
 *
 * <p>Nothing here knows or cares whether it is pushing a worktree's edits up
 * into a project home or pulling the root home's new skills down. The rules are
 * the same in both directions: an edited destination unit is never overwritten,
 * a merge conflict is reported rather than resolved, and a unit the source no
 * longer has is reported rather than deleted.
 */
public final class HomeSync {

    /**
     * @param merge three-way merge a destination unit that carries local work,
     *        instead of holding it back
     * @param dryRun compute and report the whole reconciliation, write nothing
     */
    public record Options(boolean merge, boolean dryRun) {
        public static Options defaults() { return new Options(false, false); }
    }

    /**
     * @param destinationFrozen the destination declares {@code policy = "frozen"},
     *        so a real run would have been refused. Only ever true under
     *        {@code dryRun} — a real run throws instead of setting it.
     */
    public record Report(
            Path from,
            Path to,
            boolean merge,
            boolean dryRun,
            boolean destinationFrozen,
            List<UnitSync> units
    ) {
        public Report {
            units = units == null ? List.of() : List.copyOf(units);
        }

        public List<UnitSync> with(SyncStatus status) {
            return units.stream().filter(u -> u.status() == status).toList();
        }

        public List<UnitSync> conflicted() { return with(SyncStatus.CONFLICTED); }

        public List<UnitSync> heldBack() { return with(SyncStatus.HELD_BACK); }

        /** Units the reconciliation could not settle by itself. */
        public List<UnitSync> unresolved() {
            return units.stream().filter(UnitSync::unresolved).toList();
        }

        /** Units that moved (or, under {@code --dry-run}, would move). */
        public List<UnitSync> moved() {
            return units.stream().filter(UnitSync::writes).toList();
        }

        public boolean clean() { return unresolved().isEmpty(); }

        public int count(SyncStatus status) { return with(status).size(); }
    }

    private HomeSync() {}

    /**
     * Reconcile {@code to} against {@code from}.
     *
     * <p>The lock is taken on the destination and covers the whole pass, not
     * one unit: per-unit atomicity keeps each unit coherent, and only a
     * home-wide lock keeps the <em>home</em> coherent when two worktrees close
     * out at once.
     *
     * <p>A dry run takes the lock too — but through
     * {@link HomeLock#acquireWithoutCreating}, which never creates the lock
     * file. It reads every unit in both homes and reports what a real run would
     * do; a peer reconciling underneath it would make that report describe a
     * state that never existed, so where a peer is possible it still waits.
     *
     * <p>A dry run now puts <b>nothing whatever</b> on disk. It used to put one
     * thing there — {@code .materialization/} and a zero-byte
     * {@code .home.lock} inside it, created by taking the lock — and that was
     * measured in the operator's own read-only {@code ~/.skill-manager}. It
     * broke the documented contract ("Compute and print the whole report, write
     * nothing"), and against a read-only or frozen destination it turned a
     * report into a crash. Issue #42. The lock file's absence is itself the
     * evidence that no peer holds the lock — creating it is the first thing
     * acquiring it does — so the case where nothing exists is exactly the case
     * where there is nothing to wait for. It does not stage, does not write a
     * record, and does not initialise the destination's directory layout,
     * because "what would this do" must not be answered by starting to do it.
     */
    public static Report run(SkillStore from, SkillStore to, Options options) throws IOException {
        Options opts = options == null ? Options.defaults() : options;
        Path source = from.root().toAbsolutePath().normalize();
        Path dest = to.root().toAbsolutePath().normalize();
        if (source.equals(dest)) {
            throw new IOException("home sync: --from and --to are the same home (" + source + ")");
        }
        // Before the lock, before init(), before anything is read: the source
        // has to actually BE a home. A non-home contributes zero units to the
        // union below, and zero units is indistinguishable in every report from
        // "the two homes agree" — which is how `--from <a path that does not
        // exist>` printed "✓ reconciled" with all-zero counts and exit 0, and
        // how `close-out --home <the worktree directory>` cleared a teardown.
        // The destination is deliberately NOT checked: creating a tier by
        // reconciling into it is the operation this exists for.
        NotAHomeException.require(source, "home sync --from");
        // The source is only read, so a frozen source is fine — that is the
        // whole point of freezing one. The destination is written, so a frozen
        // destination refuses before anything is staged.
        //
        // A DRY RUN is the exception, and it took a second look to see why.
        // The first reading was "the answer to 'what would this do to a frozen
        // home' is 'it is refused', so reporting a plan for it would be
        // reporting a lie" — and that is right about the plan and wrong about
        // the report. #42's whole rationale was the difference between a report
        // and a crash, and a frozen home is where a report is worth most: it is
        // the tier an operator most needs to inspect and least may touch. So a
        // dry run against a frozen destination now computes and prints
        // everything, marks the report `destinationFrozen`, and the command
        // still exits FrozenHomeException.EXIT_CODE. Nothing that branches on
        // the exit code changes behaviour; what changes is that the operator
        // gets the answer as well as the refusal. Issue #51.
        boolean frozenDest = false;
        if (opts.dryRun()) {
            frozenDest = HomePolicy.load(to).frozen();
        } else {
            HomePolicy.requireLive(to, "home sync");
        }

        try (HomeLock ignored = opts.dryRun()
                ? HomeLock.acquireWithoutCreating(dest, "home sync --dry-run")
                : HomeLock.acquire(dest, "home sync")) {
            if (!opts.dryRun()) to.init();
            ChildHomeMaterializer materializer = new ChildHomeMaterializer(from, to);
            List<UnitSync> outcomes = new ArrayList<>();
            for (UnitRef ref : unitsToVisit(from, to)) {
                UnitSync outcome = opts.dryRun()
                        ? materializer.planSync(ref.name(), ref.kind(), opts.merge())
                        : materializer.applySync(ref.name(), ref.kind(), opts.merge());
                outcomes.add(outcome);
                if (!opts.dryRun() && outcome.unresolved()) {
                    Log.warn("home sync: %s %s — %s", outcome.label(),
                            outcome.status().name().toLowerCase().replace('_', ' '),
                            outcome.detail());
                }
            }
            if (!opts.dryRun()) materializer.cleanStaging();
            return new Report(source, dest, opts.merge(), opts.dryRun(), frozenDest, outcomes);
        }
    }

    /**
     * Every unit either home holds.
     *
     * <p>The union, not the source's list: a unit the source no longer has is
     * the case a one-sided walk cannot see, and it is the one that most needs
     * saying out loud before somebody assumes an empty report means the two
     * homes agree.
     */
    static List<UnitRef> unitsToVisit(SkillStore from, SkillStore to) throws IOException {
        LinkedHashSet<UnitRef> refs = new LinkedHashSet<>(ChildHomeMaterializer.unitDirectories(from));
        refs.addAll(ChildHomeMaterializer.unitDirectories(to));
        List<UnitRef> out = new ArrayList<>(refs);
        java.util.Collections.sort(out);
        return out;
    }
}
