package dev.skillmanager.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The spelling of a {@code skill-manager} build that a home should write DOWN,
 * as opposed to the spelling the running process happens to have.
 *
 * <h2>The defect: the pin named a version, so every upgrade broke every home</h2>
 *
 * <p>DEF-012/DEF-027, measured on the operator's machine during this epic's own
 * 0.24.0 release. {@link LauncherShims#write(dev.skillmanager.store.SkillStore, Path)}
 * records the running build's absolute path in {@code <home>/bin/cli/skill-manager},
 * and on a Homebrew install that path is inside the keg:
 *
 * <pre>
 * /opt/homebrew/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager
 * </pre>
 *
 * <p>{@code brew upgrade} deletes that directory. The pin is then correct about
 * a build that no longer exists — the shim file itself is still present and
 * still executable, so every {@code -x} test passes while running it can only
 * produce exit 127. {@code home verify} returned <b>exit 0</b> on that home.
 *
 * <p>HIS-12 made the resolver skip a dead pin and HIS-13 made {@code home repair}
 * report and re-pin it. Neither stopped the product from writing the same pin
 * again on the next {@code home shims}, so the repair was a treadmill: detection
 * plus repair of a defect the product re-creates on a schedule is not a fix.
 * This class is the cause.
 *
 * <h2>The record is a POINTER, not a grant</h2>
 *
 * <p>Same shape as {@code HomeProvenance.sanctions}, which does not trust the
 * snapshot it carries but re-walks {@code clonedFrom} live on every read. A
 * versioned pin is a <em>grant</em>: it asserts "this exact file is the build",
 * which stops being true the moment the packaging moves. A pin through the
 * installation's own stable alias is a <em>pointer</em>: the alias is re-resolved
 * by the kernel at every exec, so the home follows the installation instead of
 * a snapshot of it.
 *
 * <h2>What this is NOT: it is not a PATH search</h2>
 *
 * <p>{@link RunningCli}'s javadoc argues at length that resolving the CLI from
 * {@code PATH} is the defect of issue #61, and that argument stands. Two things
 * separate this from it, and both are enforced below rather than asserted:
 *
 * <ul>
 *   <li><b>The build is already chosen.</b> This never selects a build; it is
 *       handed one and looks only for other <em>spellings of that same file</em>.
 *       Every candidate is rejected unless {@link Path#toRealPath} makes it equal
 *       to the located build's real path, so the answer cannot name a different
 *       installation than the one that ran {@code home shims}. Today's behaviour
 *       is byte-identical; only tomorrow's differs.</li>
 *   <li><b>Nothing is resolved at run time.</b> The generated entrypoint still
 *       holds one absolute path and still has no {@code PATH} branch. The
 *       resolution happens once, here, when the file is written.</li>
 * </ul>
 *
 * <p>What DOES change tomorrow is the point of the ticket: after
 * {@code brew upgrade}, {@code /opt/homebrew/bin/skill-manager} names 0.25.0 and
 * the home runs 0.25.0. That is the intended semantics of a pin whose located
 * build came from a package manager — "the skill-manager this machine installs"
 * — and it is better than the alternative on offer, which is a path that names
 * nothing at all.
 *
 * <h2>It CAN follow a downgrade, and an earlier version of this javadoc denied
 * it. The correction is the reason the {@code PATH} arm was removed</h2>
 *
 * <p>This section used to end: <i>"it is also not a silent downgrade: an upgrade
 * only moves forward, and issue #61's failure was an OLDER build answering,
 * which this cannot produce."</i> The review of #250 challenged the first half
 * for the {@code PATH} arm. Measured, it is false for <b>both</b> arms —
 * {@code probes/his-19/downgrade.out}:
 *
 * <pre>
 * a pin at &lt;prefix&gt;/bin/skill-manager, written while it named 0.24.0;
 * then `brew link` an older keg:            the pin runs BUILD 0.19.2
 * </pre>
 *
 * <p>So the honest statement is narrower and is what this class now claims. A
 * package-manager alias can move backwards when the OPERATOR moves their own
 * installation backwards, which is a deliberate act with its own command, about
 * this product, and indistinguishable from what they asked for. What cannot
 * happen is issue #61's failure — an <em>unrelated</em> build the operator never
 * chose, selected by {@code PATH} ordering at launch — because nothing is
 * resolved at launch and the recorded path is the package manager's own alias
 * for this formula.
 *
 * <h2>Every alias is DERIVED FROM THE LOCATED PATH. There is no {@code PATH} arm</h2>
 *
 * <p>An earlier version probed {@code PATH} for a versionless entry of the same
 * basename, as a generic arm for layouts this class does not know by name. It is
 * gone, and the argument for removing it is the same measurement one line up
 * with the bound taken off:
 *
 * <ul>
 *   <li><b>The set of things that can move a derived alias is bounded and is
 *       about this product.</b> {@code brew} moves {@code <prefix>/bin/skill-manager};
 *       nothing else has a reason to. An arbitrary directory that happened to be
 *       on the caller's {@code PATH} can be moved by any version manager, any
 *       shell profile, or another package that ships a binary of the same name —
 *       and nix generations roll backwards by design.</li>
 *   <li><b>A {@code PATH} arm makes the written pin depend on ambient state.</b>
 *       The same command, on the same machine, against the same home, writes a
 *       different pin from cron than from a login shell. That is a second answer
 *       to "which build does this home run" produced by the mechanism added to
 *       remove one, and it is what {@code GOAL-one-home-one-answer} forbids.</li>
 *   <li><b>The fallback is loud and already handled.</b> With no derivable alias
 *       the versioned path is written, and if an upgrade deletes it the home
 *       fails with exit 127, {@code home verify} exits 1 and
 *       {@code home repair --fix} repairs it — all of which this epic built. A
 *       silent downgrade has none of that. <b>Prefer no substitution over an
 *       unsafe one.</b></li>
 *   <li><b>It had no measured user.</b> The only layouts this class has been
 *       seen against are Homebrew's and a synthetic fixture written for the arm
 *       itself (DEF-090). An arm serving a hypothetical does not earn an
 *       unbounded failure mode.</li>
 * </ul>
 *
 * <p>A layout this class cannot derive is therefore a layout it leaves alone.
 * The fix for one is a new arm keyed on that packaging's own marker — the way
 * the two below are keyed on a literal {@code Cellar} segment — never a search.
 *
 * <h2>It can only improve, never worsen</h2>
 *
 * <p>Every exit either returns the located path unchanged or returns a candidate
 * that is, at this instant, the same file with strictly fewer version segments.
 * A build that carries no version segment at all short-circuits before anything
 * is probed, so a source checkout, a CI build directory and a hand-built binary
 * are pinned exactly as before.
 */
public final class DurableCliPin {

    private DurableCliPin() {}

    /** Which rule produced the pin. One value per branch, so a probe can name one. */
    public enum Source {
        /**
         * {@code located} carries no version-looking segment, so there is
         * nothing an alias could make more durable. The short-circuit, and the
         * branch every source checkout and every test fixture takes.
         */
        NO_VERSION_TO_LOSE,
        /**
         * {@code <prefix>/bin/<name>} — the entry point Homebrew links into the
         * prefix and re-points on every upgrade. Preferred over the keg alias
         * because it survives the formula MOVING its binary: 0.23.0 installed
         * {@code libexec/bin/skill-manager} and 0.24.0 also publishes
         * {@code bin/skill-manager}, and the linked name is the one that is
         * stable across both layouts.
         */
        HOMEBREW_LINKED,
        /**
         * {@code <prefix>/opt/<formula>/<rest>} — Homebrew's per-formula symlink
         * at the current keg. Tried after the linked name because it embeds the
         * keg's internal layout, and reached when a formula is keg-only (not
         * linked into {@code <prefix>/bin}) or its binary is not on the prefix.
         */
        HOMEBREW_KEG,
        /**
         * {@code located} names a version and no DERIVABLE alias survives it.
         * The pin is written versioned, exactly as before — this class refuses
         * to invent an alias or to search for one, and HIS-13's
         * {@code DANGLING_CLI_PIN} remains the net under it. This is the branch
         * an unrecognised packaging layout takes, and taking it is the correct
         * outcome: a loud 127 this epic detects and repairs beats a silent
         * downgrade nothing detects.
         */
        NO_DURABLE_ALIAS
    }

    /**
     * The chosen pin, the path it was chosen for, and every candidate that was
     * looked at with the reason it was or was not taken.
     *
     * <p>{@code considered} is ordered, and {@link #auditLines()} renders it for
     * the {@code home shims} detail log. A substitution that happens silently is
     * one an operator cannot audit, and this one changes which file a home's
     * front door names.
     *
     * <p>This javadoc claimed the detail-log part before anything printed it —
     * `grep -rn "\.considered()" src/main` returned no hits, found by the review
     * of #250. A documented audit surface that does not exist is DEF-021's class
     * in the javadoc of the record whose job is auditability. It exists now.
     */
    public record Choice(Path pin, Path located, Source source, Map<String, String> considered) {
        public Choice {
            considered = considered == null ? Map.of() : Map.copyOf(considered);
        }

        /** True when the pin is a different spelling from the located build. */
        public boolean substituted() { return pin != null && !pin.equals(located); }

        /**
         * One line per candidate: the path, and why it was or was not taken.
         *
         * <p>Bounded by construction — every candidate is derived from the
         * located path and there are at most two of them plus the located path
         * itself — so this can be printed unconditionally without the
         * enumeration-scales-with-the-home problem {@code home verify}'s report
         * was rewritten to avoid.
         */
        public List<String> auditLines() {
            List<String> out = new ArrayList<>();
            considered.forEach((path, why) -> out.add(path + " — " + why));
            return List.copyOf(out);
        }
    }

    /**
     * A path segment that names a version: {@code 0.24.0}, {@code v1.2.3},
     * Homebrew's revision form {@code 0.24.0_1}, {@code 1.4.0-rc2}.
     *
     * <p>Deliberately requires at least one internal dot between digits. A bare
     * number is a directory name far more often than it is a version, and a
     * false positive here costs a pointless probe while a false NEGATIVE costs
     * nothing at all — the located path is returned unchanged, which is the
     * pre-existing behaviour. The asymmetry is why this errs narrow.
     */
    private static final Pattern VERSION =
            Pattern.compile("v?\\d+(?:\\.\\d+)+(?:[._+\\-][0-9A-Za-z]+)*");

    /**
     * The same, allowed to be the tail of a longer name after a {@code -} or
     * {@code _}: {@code skill-manager-0.24.0}, {@code sdk_2.1.3}.
     */
    private static final Pattern VERSIONED_SEGMENT =
            Pattern.compile("(?:.*[-_])?" + VERSION.pattern());

    /** Whether one path segment names a version. */
    static boolean isVersionSegment(String segment) {
        return VERSIONED_SEGMENT.matcher(segment).matches();
    }

    /**
     * Every segment of {@code path} that names a version, in order.
     *
     * <p>Returned rather than a boolean because the reason a candidate was
     * rejected is printed, and "names a version too ({@code 0.24.0})" is a
     * sentence an operator can act on where "not durable" is not.
     */
    static List<String> versionSegmentsIn(Path path) {
        List<String> found = new ArrayList<>();
        for (Path segment : path) {
            String name = segment.toString();
            if (isVersionSegment(name)) found.add(name);
        }
        return found;
    }

    /** Whether {@code path} names a version anywhere along it. */
    public static boolean namesAVersion(Path path) {
        return path != null && !versionSegmentsIn(path.toAbsolutePath().normalize()).isEmpty();
    }

    /**
     * The most durable spelling of {@code located}, or {@code located} itself.
     *
     * <p>The entry point {@link LauncherShims} and {@code HomeRepair} both call.
     * Pure and idempotent: feeding the result back in returns it unchanged,
     * which is what lets the writer and the repairer agree without either
     * knowing about the other.
     */
    public static Path forPin(Path located) {
        return choose(located).pin();
    }

    /**
     * The full {@link Choice} rather than just the path.
     *
     * <p><b>It takes no environment, and that is a property rather than an
     * omission.</b> It used to take a {@code Function<String,String>} so the
     * {@code PATH} arm could be driven from a test — the seam
     * {@link RunningCli#locate(Function, String, Path)} and
     * {@code HomeDescriptor.locateCli} both carry, and for their reasons the
     * right call. With the {@code PATH} arm gone there is nothing ambient left
     * to inject: the answer is a function of the located path and the
     * filesystem, so the same command writes the same pin from cron, from a
     * login shell and from a graph node. See the class javadoc.
     */
    static Choice choose(Path located) {
        Map<String, String> considered = new LinkedHashMap<>();
        if (located == null) {
            return new Choice(null, null, Source.NO_VERSION_TO_LOSE, considered);
        }
        Path abs = located.toAbsolutePath().normalize();

        // BRANCH 1. Nothing to improve. Checked FIRST and on the located path
        // alone, so a source checkout or a test fixture never touches the disk
        // for a probe and never has its pin rewritten.
        List<String> versions = versionSegmentsIn(abs);
        if (versions.isEmpty()) {
            considered.put(abs.toString(), "kept: names no version");
            return new Choice(abs, abs, Source.NO_VERSION_TO_LOSE, considered);
        }

        // The identity every candidate is checked against. Resolved ONCE: a
        // candidate is an alias only if it is the same file, and "the same
        // file" is a real-path comparison, never a string one.
        //
        // BRANCH 2 is VERDICT-NEUTRAL and is here for its MESSAGE. The review of
        // #250 showed it deletable with the suite green, and that is true: with
        // `real` null, `candidateReal.equals(null)` is false for every candidate,
        // so each is rejected below and the verdict is NO_DURABLE_ALIAS either
        // way. What is lost by deleting it is the only sentence that tells an
        // operator WHY -- "the build you pointed me at is not there" reads very
        // differently from "no alias resolves to it". So it stays, it is
        // declared neutral rather than claimed as a gate, and the probe for it
        // asserts the RECORDED REASON, which is the thing it actually decides.
        Path real = realOrNull(abs);
        if (real == null) {
            considered.put(abs.toString(),
                    "kept: names " + first(versions) + " but does not resolve, so there is "
                            + "nothing to compare an alias against");
            return new Choice(abs, abs, Source.NO_DURABLE_ALIAS, considered);
        }

        for (Candidate candidate : candidates(abs)) {
            String rejection = rejection(candidate.path(), real);
            if (rejection != null) {
                considered.putIfAbsent(candidate.path().toString(), rejection);
                continue;
            }
            considered.put(candidate.path().toString(),
                    "TAKEN: same build, no version segment (" + candidate.source() + ")");
            return new Choice(candidate.path(), abs, candidate.source(), considered);
        }
        considered.put(abs.toString(), candidatesWereConsidered(considered)
                ? "kept: names " + first(versions)
                        + " and no derivable versionless alias resolves to it"
                : "kept: names " + first(versions)
                        + " and this layout has no derivable alias -- see DurableCliPin's "
                        + "javadoc for why an unrecognised layout is left alone rather "
                        + "than searched for");
        return new Choice(abs, abs, Source.NO_DURABLE_ALIAS, considered);
    }

    /**
     * Whether anything was actually probed, for the sake of the recorded reason.
     *
     * <p>"No alias resolves to it" and "this packaging has no alias this class
     * knows how to name" are different facts, and after the {@code PATH} arm was
     * removed the second is the common one. Reporting them as the same sentence
     * would send an operator looking for a broken symlink that was never
     * expected to exist.
     */
    private static boolean candidatesWereConsidered(Map<String, String> considered) {
        return !considered.isEmpty();
    }

    /** One candidate spelling and the rule that produced it. */
    private record Candidate(Path path, Source source) {}

    /**
     * Every alias worth probing for {@code abs}, in preference order.
     *
     * <p><b>Every candidate is derived from {@code abs} alone.</b> That is the
     * invariant the class's safety argument rests on and it is cheap to check:
     * this method reads no environment and touches no disk. A candidate that
     * could not be written down by looking at the located path does not belong
     * here — see the class javadoc for the measurement that removed the one that
     * could not be.
     *
     * <p>Empty for a path this class does not recognise, which is the honest
     * answer and produces {@link Source#NO_DURABLE_ALIAS}.
     */
    private static List<Candidate> candidates(Path abs) {
        List<Candidate> out = new ArrayList<>();
        Keg keg = kegOf(abs);
        Path name = abs.getFileName();
        if (keg != null && name != null) {
            out.add(new Candidate(keg.prefix().resolve("bin").resolve(name),
                    Source.HOMEBREW_LINKED));
            out.add(new Candidate(keg.prefix().resolve("opt").resolve(keg.formula())
                    .resolve(keg.rest()), Source.HOMEBREW_KEG));
        }
        return out;
    }

    /** {@code <prefix>/Cellar/<formula>/<version>/<rest>}, decomposed. */
    private record Keg(Path prefix, String formula, Path rest) {}

    /**
     * Decompose {@code abs} as a Homebrew keg path, or null.
     *
     * <p>Keyed on a {@code Cellar} segment followed by a formula name and a
     * VERSION segment. The version test is what stops an unrelated directory
     * called {@code Cellar} from being read as a keg — and it is the same test
     * that decides the whole class is even interested in this path, so the two
     * cannot drift apart.
     */
    private static Keg kegOf(Path abs) {
        int count = abs.getNameCount();
        for (int i = 0; i + 3 < count; i++) {
            if (!"Cellar".equals(abs.getName(i).toString())) continue;
            String version = abs.getName(i + 2).toString();
            if (!isVersionSegment(version)) continue;
            Path prefix = i == 0 ? abs.getRoot() : abs.getRoot().resolve(abs.subpath(0, i));
            if (prefix == null) continue;
            return new Keg(prefix, abs.getName(i + 1).toString(),
                    abs.subpath(i + 3, count));
        }
        return null;
    }

    /**
     * Why {@code candidate} is not a durable alias of the build at {@code real},
     * or null when it is one.
     *
     * <p>Three gates, in this order. <b>Only the last two can change a verdict</b>,
     * and the javadoc says so because an earlier version did not:
     *
     * <ol>
     *   <li><b>existence and executability</b> — {@code isRegularFile} as well
     *       as {@code isExecutable}, because a DIRECTORY is executable on POSIX.
     *       Both follow links, which is the point: the alias IS a link.
     *       <b>Verdict-neutral</b>: every input these refuse is refused again by
     *       the same-file gate below (a dangling link does not resolve; a
     *       directory does not resolve to the located FILE; a symlink takes its
     *       target's mode). They buy an earlier exit and a better message;</li>
     *   <li><b>versionlessness</b> — an alias that also names a version is not
     *       more durable, only different. This is the gate that makes the class's
     *       claim checkable rather than hopeful, and it decides verdicts;</li>
     *   <li><b>identity of the FILE</b> — last and decisive. Without it this
     *       would substitute a build the operator never chose, which is issue #61
     *       and is the one outcome worse than the defect being fixed.</li>
     * </ol>
     *
     * <p><b>A fourth gate stood first and is gone.</b> It refused a candidate
     * equal to the located path. With every candidate now derived from a keg
     * decomposition, no candidate can contain the {@code Cellar} segment the
     * located path must contain, so it was unreachable — and the review of #250
     * found it deletable with the suite fully green. Deleting a dead branch is
     * the right answer to that; adding a control for one would have been a
     * control for nothing. Ledger row 18.
     */
    private static String rejection(Path candidate, Path real) {
        if (!Files.isRegularFile(candidate)) return "skipped: no such file";
        if (!Files.isExecutable(candidate)) return "skipped: not executable";
        List<String> versions = versionSegmentsIn(candidate);
        if (!versions.isEmpty()) {
            return "skipped: names a version too (" + first(versions) + ")";
        }
        Path candidateReal = realOrNull(candidate);
        if (candidateReal == null) return "skipped: does not resolve";
        if (!candidateReal.equals(real)) {
            return "skipped: resolves to " + candidateReal + ", which is a DIFFERENT build";
        }
        return null;
    }

    /**
     * The first version segment, or a placeholder.
     *
     * <p>{@code get(0)} stood here and was safe only because the short-circuit
     * above guarantees the list is non-empty. A message that can throw when a
     * guard one screen away is moved is a coupling, not a contract — and it is
     * the shape a mutation probe finds by crashing instead of by reddening,
     * which teaches nothing about the branch it was aimed at.
     */
    private static String first(List<String> versions) {
        return versions.isEmpty() ? "no version" : versions.get(0);
    }

    /** {@link Path#toRealPath}, or null when the path does not resolve. */
    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException unresolved) {
            return null;
        }
    }
}
