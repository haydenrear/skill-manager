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

    /**
     * A unit the destination does not have and <b>can obtain for itself</b> —
     * DEF-101.
     *
     * <h2>The distinction close-out could not draw</h2>
     *
     * <p>Every {@code NEW} unit used to block, on one rule:
     * {@code Files.isDirectory(<into>/<kind>/<name>) == false}. That rule is
     * right about the case it was written for — a unit the agent authored in
     * the worktree exists nowhere else, and deleting the worktree deletes it.
     * It is wrong about the case measured on HIS-20: {@code skill:skill-manager}
     * and {@code plugin:skt}, both unmodified checkouts of published refs, both
     * <b>declared by the destination's own {@code skill-project.toml}</b>, and
     * the destination short of them only because DEF-096 left its manifest
     * unrealized. Two blockers, {@code safe: false}, {@code exit 1}, and
     * <em>nothing would have been destroyed</em>.
     *
     * <p>Blocking on those is a spurious hold-back: it makes the operator run a
     * sync to move bytes the destination can fetch, from a home that is about
     * to be deleted, to make a gate stop complaining. That is the failure mode
     * {@code GOAL-no-spurious-holdback} names.
     *
     * <h2>Both halves are required, and each is load-bearing</h2>
     *
     * <ol>
     *   <li><b>The destination declares it.</b> Not "it looks fetchable" — the
     *       destination's own manifest names it and names where from. That is
     *       the destination making the claim, not close-out guessing on its
     *       behalf.</li>
     *   <li><b>The worktree's copy holds nothing of its own.</b> Read from the
     *       worktree's materialization record via
     *       {@link ChildHomeMaterializer#isLocallyModified}, the same predicate
     *       that decides {@code HELD_BACK} and dirty checkouts. If the agent
     *       edited it, or its git history moved on, it is <em>not</em> obtainable
     *       from the published ref and it blocks exactly as before.</li>
     * </ol>
     *
     * <p>Reported, never silent: a unit that clears the gate this way is named
     * in the verdict and in {@code --json}, with the command that obtains it.
     * "Nothing is at risk" and "nothing to say" are different answers.
     */
    public record SelfObtainable(UnitSync unit, String source, String remedy) {
        public String label() { return unit.label(); }
    }

    public record Verdict(
            Path home,
            Path into,
            boolean safe,
            List<Blocker> blockers,
            List<UnitSync> units,
            List<SelfObtainable> selfObtainable
    ) {
        public Verdict {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            units = units == null ? List.of() : List.copyOf(units);
            selfObtainable = selfObtainable == null ? List.of() : List.copyOf(selfObtainable);
        }

        /** Pre-DEF-101 shape, kept so existing call sites and tests still read. */
        public Verdict(Path home, Path into, boolean safe,
                       List<Blocker> blockers, List<UnitSync> units) {
            this(home, into, safe, blockers, units, List.of());
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

        // DEF-101. What the DESTINATION says it can obtain for itself. Read
        // once, before the loop, and read from the destination — a claim about
        // what `--into` can fetch has to come from `--into`.
        dev.skillmanager.project.ProjectManifestRealization.Shortfall declared =
                dev.skillmanager.project.ProjectManifestRealization.inspect(into);
        java.util.Set<String> modifiedInWorktree = new java.util.HashSet<>();
        for (ChildHomeMaterializer.UnitOutcome outcome : worktreeSide.locallyModifiedUnits()) {
            modifiedInWorktree.add(outcome.label());
        }

        List<Blocker> blockers = new ArrayList<>();
        List<SelfObtainable> selfObtainable = new ArrayList<>();
        for (UnitSync unit : report.units()) {
            SelfObtainable obtainable =
                    selfObtainable(unit, declared, modifiedInWorktree, worktreeSide, intoRoot);
            if (obtainable != null) {
                selfObtainable.add(obtainable);
                continue;
            }
            String remedy = remedyFor(unit, homeRoot, intoRoot);
            if (remedy != null) blockers.add(new Blocker(unit, remedy));
        }
        for (UnitSync unit : report.units()) {
            if (!dirtyCheckouts.contains(unit.label())) continue;
            if (blockers.stream().anyMatch(b -> b.label().equals(unit.label()))) continue;
            // A dirty checkout is never self-obtainable — modifiedInWorktree is
            // the same enumeration dirtyCheckouts is filtered from — but the
            // guard is stated rather than relied on, because the two lists are
            // built from one walk and a future change to either must not be able
            // to let a unit with unpushed work out through this seam.
            if (selfObtainable.stream().anyMatch(o -> o.label().equals(unit.label()))) continue;
            blockers.add(new Blocker(unit,
                    (cliInvocation(homeRoot) + " unit publish "
                            + HomeDescriptor.shellQuote(unit.unitName()) + " "
                            + HomeDescriptor.homeArg(homeRoot)).strip()
                            + "  (a git checkout with unpushed work; a file copy cannot carry it)"));
        }
        return new Verdict(homeRoot, intoRoot, blockers.isEmpty(), blockers,
                report.units(), selfObtainable);
    }

    /**
     * "The destination lacks this and its own manifest declares it at a
     * published ref", or null for every other case — DEF-101.
     *
     * <p>Narrow on purpose. Only {@link SyncStatus#NEW} is considered: every
     * other status describes a unit the destination <em>has</em>, where the
     * question is whose bytes win rather than whether the bytes exist. A
     * declared unit that the destination already holds and the worktree has
     * edited is still {@code UPDATED} / {@code HELD_BACK} / {@code CONFLICTED},
     * and still blocks.
     *
     * @param declared          what the destination's own manifest names
     * @param modifiedInWorktree labels the worktree home has local work on
     */
    private static SelfObtainable selfObtainable(
            UnitSync unit,
            dev.skillmanager.project.ProjectManifestRealization.Shortfall declared,
            java.util.Set<String> modifiedInWorktree,
            ChildHomeMaterializer worktreeSide,
            Path into) throws IOException {
        if (unit.status() != SyncStatus.NEW) return null;
        if (declared == null || !declared.hasManifest()) return null;
        if (modifiedInWorktree.contains(unit.label())) return null;
        // POSITIVE evidence of provenance, not merely the absence of a
        // complaint. isLocallyModified already answers true for a unit with no
        // materialization record, so this is belt-and-braces — and deliberately
        // so: "we found no record saying this came from somewhere" and "we
        // found a record saying this came from somewhere" must not be the same
        // input to a gate that decides whether a directory may be deleted. If
        // that predicate is ever relaxed, this line still refuses.
        if (worktreeSide.readRecord(unit.unitName(), unit.unitKind()).isEmpty()) return null;
        var match = declared.declared().stream()
                .filter(d -> d.kind() == unit.unitKind())
                .filter(d -> d.lookupName().equals(unit.unitName())
                        || d.alias().equals(unit.unitName()))
                .findFirst()
                .orElse(null);
        if (match == null) return null;
        return new SelfObtainable(unit, match.source(),
                "skill-manager project resolve --project-dir "
                        + HomeDescriptor.shellQuote(String.valueOf(
                                declared.manifest().getParent()))
                        + "  (declared by " + declared.manifest().getFileName()
                        + " at " + match.source() + "; nothing in this worktree's copy "
                        + "exists only here)");
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
        String cli = cliInvocation(home);
        // CLASS 1 (#161): `home sync --from <a> --to <b>` NAMES ITS OWN TARGET
        // in its arguments, so it binds nothing further. This is the remedy
        // `home-sync`'s `remedyArgs` re-runs after dropping the head token, and
        // it is the spelling that shipped green; #229's first attempt put an
        // `env SKILL_MANAGER_HOME=…` prefix in front of it, which that strip
        // (guarded by endsWith("skill-manager")) passed through as ARGUMENTS —
        // `Unmatched arguments from index 0` — taking the graph red.
        String sync = cli + " home sync --from " + HomeDescriptor.shellQuote(home.toString())
                + " --to " + HomeDescriptor.shellQuote(into.toString());
        // CLASS 2: `unit publish` names no home, and takes --home.
        String publish = (cli + " unit publish " + HomeDescriptor.shellQuote(unit.unitName())
                + " " + HomeDescriptor.homeArg(home)).strip();
        return switch (unit.status()) {
            case NEW, UPDATED -> sync;
            case MERGED -> sync + " --merge";
            case CONFLICTED -> sync + " --merge  (then resolve: "
                    + String.join(", ", unit.conflicts()) + ")";
            case HELD_BACK -> publish + ", or " + sync + " --merge";
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

    /**
     * How a remedy names the {@code skill-manager} to run.
     *
     * <h2>Why a bare token was wrong, and why fixing it HERE</h2>
     *
     * <p>Every remedy used to begin with the literal word {@code skill-manager},
     * which is only runnable if the {@code skill-manager} first on the
     * operator's {@code PATH} understands the command. Measured on the
     * development machine it does not: {@code command -v skill-manager} is the
     * released 0.19.2, which has no {@code home} subcommand at all, so a remedy
     * copy-pasted verbatim exits 2 — from a gate that was working correctly and
     * printing correct advice. The gate's whole contract is "here is the exact
     * command that clears this blocker", and a command that cannot run is not
     * that.
     *
     * <p>It is fixed at this one site because there are two consumers and they
     * are not equally visible: {@code --json} (which {@code close-change.sh}
     * renders) and {@link #render}, the human path {@code home close-out} prints
     * directly. A substitution in the shell script fixed the first and left the
     * second — the documented human invocation still printed the un-runnable
     * spelling. A guard at N call sites is N chances to miss one, so the answer
     * belongs where the string is built.
     *
     * <p>{@link dev.skillmanager.store.HomeDescriptor#resolveCli} is that
     * answer, reused rather than re-derived: it is already the definition of
     * "which build goes with this home" for the launcher shims and the
     * descriptor, and it honours {@code SKILL_MANAGER_CLI} first — which is how
     * a caller that has ALREADY established capability (close-change.sh probes
     * the help text for {@code --into}, because 0.19.2 answers unknown
     * subcommands with top-level usage and exit 0) passes that answer in rather
     * than having it guessed again.
     *
     * <p>The worktree home is the store root asked about, not {@code --into}:
     * {@code <home>/bin/cli/skill-manager} names the build the home was created
     * with, which is the build that understands its layout.
     *
     * <p>Falls back to the bare token when nothing resolves. That is strictly
     * better than an invented path: the operator sees the same string as
     * before and can fix their PATH, whereas a plausible-looking wrong absolute
     * path fails confusingly. Same reasoning as {@code resolveCli} returning
     * null rather than a guess.
     */
    private static String cliInvocation(Path home) {
        // Promoted to HomeDescriptor (#142) once the drift gate needed the same
        // answer. Delegating rather than keeping a second copy: two spellings of
        // "which build goes with this home" is exactly how the --json and human
        // paths diverged the first time this was fixed.
        return HomeDescriptor.cliInvocation(home);
    }

    /**
     * Human-readable verdict lines, shared by the CLI and anything that logs
     * one.
     *
     * <p>Every line is prospective. {@code applied = false} is passed as a
     * literal rather than plumbed from a flag, because there is no call path
     * through this class that writes — {@link #inspect} is a dry run by
     * construction — so there must be no call path that can claim to have.
     * Issue #133.
     */
    public static List<String> render(Verdict verdict) {
        List<String> out = new ArrayList<>();
        for (UnitSync unit : verdict.units()) {
            if (unit.status() == SyncStatus.UNCHANGED) continue;
            out.add("  %-18s %s — %s".formatted(
                    unit.statusLabel(false), unit.label(), unit.detail()));
        }
        // DEF-101: cleared, not silent. Printed before the blockers so a reader
        // who sees a short list of fixes can also see what was decided about the
        // units that are NOT on it.
        for (SelfObtainable obtainable : verdict.selfObtainable()) {
            out.add("  obtainable %s: the destination declares it — %s".formatted(
                    obtainable.label(), obtainable.remedy()));
        }
        for (Blocker blocker : verdict.blockers()) {
            out.add("  fix %s: %s".formatted(blocker.label(), blocker.remedy()));
        }
        // M4 of #229's review: the caveat reaches four surfaces and this one
        // dropped it. Every remedy above names a build, and when that build
        // came from a raw PATH walk — or when this home's own entrypoint pins
        // something an upgrade deleted — the reader is entitled to know before
        // pasting it. Printed once for the verdict rather than once per
        // blocker: it is a fact about the resolution, not about a unit.
        if (!verdict.blockers().isEmpty()) {
            String caveat = HomeDescriptor.cliSpelling(verdict.home()).caveat();
            if (caveat != null) out.add("  note: " + caveat);
        }
        return out;
    }
}
