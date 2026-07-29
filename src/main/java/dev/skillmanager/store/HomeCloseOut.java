package dev.skillmanager.store;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The question a worktree teardown has to ask before it deletes anything:
 * <em>is there work in this home that exists nowhere else?</em>
 *
 * <h2>Why a gate and not a sync</h2>
 *
 * <p>Removing a ticket worktree removes its Skill Manager home with it. If the
 * ticket agent edited a skill in there, that edit is gone — and gone silently,
 * because a teardown that deletes a directory succeeds exactly as loudly
 * whether the directory held work or not. That is the failure this exists to
 * prevent, and preventing it needs a check that <em>refuses</em>, not a
 * cleanup that helpfully does something.
 *
 * <p>So this writes nothing. It reports, and it exits non-zero while there is
 * anything to lose. Doing the merge itself would make the safe path and the
 * destructive path the same command, and a gate that also acts is a gate
 * somebody eventually runs to make the error go away.
 *
 * <h2>What counts as work that would be lost</h2>
 *
 * <ul>
 *   <li>A unit the project home does not have, or has an older copy of: the
 *       worktree carries content nobody else does.</li>
 *   <li>A unit both sides changed that a three-way merge can fold together —
 *       <b>can</b>, not <b>has</b>. Until the merge is actually run the
 *       worktree's half of it only exists in the worktree.</li>
 *   <li>A unit both sides changed that cannot be merged cleanly.</li>
 *   <li>A unit materialized as a git checkout that carries uncommitted or
 *       unpushed work; its home is its history, and no file copy carries
 *       that. Those go home through {@code unit publish}.</li>
 * </ul>
 *
 * <p>A unit the project home has and the worktree does not is <em>not</em>
 * blocking: nothing in the worktree is at risk. It is still reported, because a
 * silent asymmetry is how somebody concludes the two homes agree.
 */
public final class HomeCloseOut {

    /** Exit code for "this worktree still holds work"; distinct from a crash. */
    public static final int BLOCKED_EXIT_CODE = 1;

    public record Blocker(UnitSync unit, String remedy) {
        public String label() { return unit.label(); }
    }

    public record Verdict(
            Path home,
            Path into,
            boolean safe,
            List<Blocker> blockers,
            List<UnitSync> units
    ) {
        public Verdict {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            units = units == null ? List.of() : List.copyOf(units);
        }

        public int exitCode() { return safe ? 0 : BLOCKED_EXIT_CODE; }
    }

    private HomeCloseOut() {}

    /**
     * Decide whether {@code home} may be torn down.
     *
     * <p>Implemented as a dry-run {@code home sync --merge} from the worktree
     * home into the project home, because "would a merge into the project lose
     * anything" is precisely the question, and answering it with a second
     * comparison would give the gate and the sync two different opinions about
     * the same units.
     *
     * <p>Idempotent by construction: it writes nothing, so running it twice
     * gives the same answer, and running it after the sync it asked for gives
     * "safe" because the sync moved the units it named.
     */
    public static Verdict inspect(SkillStore home, SkillStore into) throws IOException {
        Path homeRoot = home.root().toAbsolutePath().normalize();
        Path intoRoot = into.root().toAbsolutePath().normalize();
        // Both ends, before anything is compared. `--home` is covered by the
        // same check inside HomeSync (it is the dry run's source), but stating
        // it here as well is deliberate: this is the gate, the failure mode was
        // that it approved a teardown of a directory it had never established
        // was a home, and a gate should not depend on a subroutine to notice
        // that its own argument was nonsense. `--into` is checked too — a
        // project home that is not a home makes every unit look NEW, which
        // blocks rather than clears, but "blocked with a nonsense remedy" is
        // still a report nobody can act on.
        NotAHomeException.require(homeRoot, "home close-out --home");
        NotAHomeException.require(intoRoot, "home close-out --into");
        HomeSync.Report report = HomeSync.run(home, into, new HomeSync.Options(true, true));
        // `home sync --dry-run` now reports against a frozen destination rather
        // than refusing (#51). The gate must NOT inherit that: every remedy it
        // could print is a write into `--into`, and a frozen project home
        // refuses all of them, so a verdict computed here would name fixes that
        // cannot be applied. The refusal is the answer, and it is the same
        // exit code this already returned.
        if (report.destinationFrozen()) {
            dev.skillmanager.policy.HomePolicy.requireLive(into, "home close-out --into");
        }

        // Checkout units are judged in the worktree home itself, against their
        // own git history: a copy-based comparison would call a checkout
        // "different" forever (its .git churns) and never say the one thing
        // that matters, which is whether the commits have been pushed.
        ChildHomeMaterializer worktreeSide = new ChildHomeMaterializer(into, home);
        List<String> dirtyCheckouts = new ArrayList<>();
        for (ChildHomeMaterializer.UnitOutcome outcome : worktreeSide.locallyModifiedUnits()) {
            worktreeSide.readRecord(outcome.unitName(), outcome.unitKind())
                    .filter(r -> dev.skillmanager.bindings.MaterializationMode.CHECKOUT.name()
                            .equals(r.mode()))
                    .ifPresent(r -> dirtyCheckouts.add(outcome.label()));
        }

        List<Blocker> blockers = new ArrayList<>();
        for (UnitSync unit : report.units()) {
            String remedy = remedyFor(unit, homeRoot, intoRoot);
            if (remedy != null) blockers.add(new Blocker(unit, remedy));
        }
        for (UnitSync unit : report.units()) {
            if (!dirtyCheckouts.contains(unit.label())) continue;
            if (blockers.stream().anyMatch(b -> b.label().equals(unit.label()))) continue;
            blockers.add(new Blocker(unit,
                    "skill-manager unit publish " + unit.unitName()
                            + "  (a git checkout with unpushed work; a file copy cannot carry it)"));
        }
        return new Verdict(homeRoot, intoRoot, blockers.isEmpty(), blockers, report.units());
    }

    /**
     * What to run for this unit, or null when it holds nothing that would be
     * lost.
     *
     * <p>{@code UPDATED} gets a plain {@code home sync} — the same command the
     * gate just dry-ran, so it produces the outcome that was reported rather
     * than something like it. That used to be the wrong advice for a reason
     * that was not local to this method: the fast-forward it names left the
     * project home holding a pristine copy of a worktree about to be deleted,
     * and the next sync from any other home overwrote it (CHM-9). It is the
     * right advice now because the copy records which home it came from and
     * {@link ChildHomeMaterializer}'s baseline rule reads it — a later pass
     * from a home that never held those bytes holds back instead of
     * overwriting.
     */
    private static String remedyFor(UnitSync unit, Path home, Path into) {
        String sync = "skill-manager home sync --from " + home + " --to " + into;
        return switch (unit.status()) {
            case NEW, UPDATED -> sync;
            case MERGED -> sync + " --merge";
            case CONFLICTED -> sync + " --merge  (then resolve: "
                    + String.join(", ", unit.conflicts()) + ")";
            case HELD_BACK -> "skill-manager unit publish " + unit.unitName()
                    + ", or " + sync + " --merge";
            // A linked unit is the one case where the gate cannot say whether
            // anything would be lost, because it cannot say whose bytes they
            // are: the link may point inside the worktree (removed with it) or
            // outside (untouched). "Cannot tell" has to block, not clear —
            // clearing it is how `clean: true` came to be printed for a home
            // whose whole skills/ directory was a link and whose report listed
            // no units at all.
            case LINKED -> "resolve the symlink first, then re-run: " + sync + " --merge"
                    + "  (" + unit.detail() + ")";
            case UNCHANGED, REMOVED_UPSTREAM -> null;
        };
    }

    /** Human-readable verdict lines, shared by the CLI and anything that logs one. */
    public static List<String> render(Verdict verdict) {
        List<String> out = new ArrayList<>();
        for (UnitSync unit : verdict.units()) {
            if (unit.status() == SyncStatus.UNCHANGED) continue;
            out.add("  %-14s %s — %s".formatted(
                    unit.status().name().toLowerCase().replace('_', '-'),
                    unit.label(), unit.detail()));
        }
        for (Blocker blocker : verdict.blockers()) {
            out.add("  fix %s: %s".formatted(blocker.label(), blocker.remedy()));
        }
        return out;
    }
}
