package dev.skillmanager.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The resolution rule for entry-point shims, written down once.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A shim resolves its unit's path from the home the shim lives in:
 * prefer this home's copy, fall back to the pinned one.</b>
 *
 * <p>"Prefer this home's copy" is the whole of it. "Always this home" would be
 * wrong — a home that legitimately holds no copy of a unit has nowhere else to
 * go, and reaching its parent store is what child homes are for. So the rule
 * has exactly one exception and it is conditioned on absence, never on shape.
 *
 * <h2>This is a rule about BYTES, and that is not a style preference</h2>
 *
 * <p>Measured 2026-08-28 in this repository's own project home:
 *
 * <pre>
 *   it HAS its own copy   .skill-manager/skills/spec-double-compiler/scripts/tla_spec_dev.py  (38288 B)
 *   it does NOT run it    .skill-manager/bin/cli/tla-spec-dev -&gt; the ROOT home's shim
 *                         -&gt; exec python3 "/Users/hayde/.skill-manager/skills/.../tla_spec_dev.py"
 *   and the two copies    are BYTE-IDENTICAL (diff -q silent)
 * </pre>
 *
 * <p>The wrong copy therefore produces the right answer, and every behavioural
 * assertion passes straight over the defect. A conformance check has to ask
 * <em>which path the shim NAMES</em> — which is why this class takes a file and
 * reads its text, and why {@code ShimHomeContractTest} asserts on generated
 * bytes rather than on what running a shim prints. {@code LauncherShims:548-552}
 * documents the same reasoning from the other direction: a one-sided
 * behavioural assertion once kept a shim green that refused unconditionally.
 *
 * <h2>Where this differs from {@link HomeCloner#unsanctionedForeignHome}, and why</h2>
 *
 * <p>Not a third reading. The EXTRACTION is
 * {@link HomeRepair#absolutePathTokens} and the "is this another home"
 * predicate is {@link HomeCloner#foreignHomeReachedBy} — the same two both
 * existing readers use, so a blind spot here is the same blind spot there
 * (interpolated paths, relative escapes and paths containing spaces are
 * documented as invisible on {@code HomeRepair.pathTokensIn}).
 *
 * <p>What differs is the SANCTION, and the difference is the disagreement this
 * class exists to settle. {@code HomeCloner.sanctionedParentShim} accepts a
 * crossing on SHAPE alone: same {@code bin/cli/<name>} spelling on both sides,
 * one resolved artifact, and evidence that the other home is an ancestor. It
 * never asks whether this home holds its own copy of the unit — so a home that
 * has the unit, has the installer that would have produced a correct local
 * shim, and still execs the parent's copy is sanctioned by shape, and
 * {@code home verify} exits 0 on it. That is 16 of the 19 pairs measured for
 * {@code GOAL-a-home-runs-its-own-copy} on 2026-08-29 (27 homes, 194 shims);
 * the other 3 have the foreign path frozen into the wrapper body, which no
 * shape sanction covers and which {@code home repair} does report. Same home,
 * same minute, two answers.
 *
 * <p>This contract asks the question the shape sanction omits: <b>does this
 * home hold {@code <kind>/<unit>} itself?</b> If it does, the crossing is a
 * violation however parent-shaped it looks. If it does not, the crossing is the
 * sanctioned fallback the rule's second clause permits, and nothing is
 * reported. Sanctioned fallbacks measured on 2026-08-29: zero. Every single
 * crossing on this machine is the unsanctioned kind, with a local copy sitting
 * unused.
 *
 * <p>Bringing the two seams onto this reading is HBR-1's and HBR-5's work.
 * This class changes no generator and no gate; it states the rule so that both
 * can be checked against one of it.
 */
public final class ShimHomeContract {

    private ShimHomeContract() {}

    /** The rule, in one sentence, for a message a reader has to act on. */
    public static final String RULE =
            "a shim resolves its unit's path from the home the shim lives in: "
                    + "prefer this home's copy, fall back to the pinned one";

    /**
     * The shim names a unit copy — {@code skills/<unit>} or
     * {@code plugins/<unit>} — inside another home, while its OWN home holds
     * that unit too.
     *
     * <p>CHARGED to {@code GOAL-a-home-runs-its-own-copy}: this is the metric's
     * {@code (home, cli-shim)} pair.
     */
    public static final String FOREIGN_UNIT_COPY = "FOREIGN_UNIT_COPY";

    /**
     * The shim names some other live path inside another home — a
     * {@code cache/} tree a skill-script provisioned, another home's
     * {@code bin/}.
     *
     * <p>The same freeze and the same defect: the path was resolved once, at
     * install time, against the home that happened to be installing, and
     * relocating the shim does not move it. NOT charged to the goal, which
     * counts unit copies; the baseline harness separates the two populations
     * for the same reason (17 of the 19 measured pairs also exec a foreign
     * cache venv, counted and not charged).
     */
    public static final String FOREIGN_HOME_PATH = "FOREIGN_HOME_PATH";

    /**
     * One path a shim names that is resolved from a home other than its own.
     *
     * @param generator  what wrote the bytes — a Java writer or an installer
     *                   script; the thing a fix has to change
     * @param shimRel    the shim's location inside the home it lives in
     * @param kind       {@link #FOREIGN_UNIT_COPY} or {@link #FOREIGN_HOME_PATH}
     * @param names      the path the shim's bytes name
     * @param foreignHome the home that path lands in
     * @param unit       {@code <kind>/<unit>} when the path is a unit copy, else null
     * @param charged    whether this counts toward {@code GOAL-a-home-runs-its-own-copy}
     */
    public record Violation(String generator, String shimRel, String kind, Path names,
                            Path foreignHome, String unit, boolean charged) {

        @Override
        public String toString() {
            return generator + " -> " + shimRel + ": " + kind
                    + (unit == null ? "" : " (" + unit + ")")
                    + "\n      names  " + names
                    + "\n      which is in the home at " + foreignHome
                    + "\n      " + (charged
                            ? "CHARGED to GOAL-a-home-runs-its-own-copy"
                            : "not charged (counted separately, as the baseline does)");
        }
    }

    /**
     * Every way the shim at {@code shim} breaks {@link #RULE} for the home at
     * {@code home}.
     *
     * <p>Empty means conformant. The list is per-path rather than a boolean
     * because one bad pair is the whole defect and a verdict that cannot name
     * the path is a verdict nobody can act on.
     *
     * @param generator what wrote these bytes, for the message
     * @param home      the home the shim LIVES IN — not the home it was
     *                  generated for. Those are the same directory for a shim
     *                  that has never moved, and the rule is only observable
     *                  where they differ: a conformant shim derives its home
     *                  from its own location, so relocating it changes what it
     *                  names, and a frozen one does not.
     * @param shim      the shim file, under {@code home}
     */
    public static List<Violation> check(String generator, Path home, Path shim) {
        if (home == null || shim == null) return List.of();
        Path root = real(home);
        String rel = relativeTo(root, shim);
        List<Violation> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String token : HomeRepair.absolutePathTokens(shim)) {
            Path candidate;
            try {
                candidate = Path.of(token);
            } catch (RuntimeException notAPath) {
                continue;
            }
            Path foreign = HomeCloner.foreignHomeReachedBy(candidate, root);
            if (foreign == null || !seen.add(candidate)) continue;
            String unit = unitUnder(foreign, candidate);
            if (unit == null) {
                out.add(new Violation(generator, rel, FOREIGN_HOME_PATH,
                        candidate, foreign, null, false));
                continue;
            }
            // THE SANCTIONED FALLBACK, and the ONLY one. Not "it looks like a
            // parent mirror" — that is the shape test this contract exists to
            // replace — but "this home has no copy to prefer".
            if (!Files.exists(root.resolve(unit.replace('/', java.io.File.separatorChar)))) continue;
            out.add(new Violation(generator, rel, FOREIGN_UNIT_COPY,
                    candidate, foreign, unit, true));
        }
        return List.copyOf(out);
    }

    /**
     * The paths {@code shim} has FROZEN: absolute paths in its own bytes that
     * land inside {@code home} itself, home-relative and sorted.
     *
     * <p>{@link #check} asks the question after the fact — this shim has moved,
     * is it now running somebody else's copy? By then the bytes are somebody
     * else's problem and the generator that wrote them may be three homes away.
     * This asks the same question at the moment of writing, where the answer is
     * still actionable: a shim whose body names its own home absolutely is a
     * shim that will go on naming THIS home from wherever it is later copied,
     * symlinked or cloned to. Empty is the conformant state, and it is
     * reachable — {@code bin/launch/*} and both {@code LauncherShims} writers
     * have always been empty here.
     *
     * <p>Deliberately not "any absolute path". A shim may legitimately name
     * something outside every home — the interpreter it was built against, a
     * pinned build — because relocating the shim does not move those either,
     * and pretending otherwise would make the check fire on shims that are
     * already right. The freeze is specifically about the home.
     *
     * @param home the home the shim was written into
     * @param shim the shim file
     */
    public static List<String> frozenHomePaths(Path home, Path shim) {
        if (home == null || shim == null) return List.of();
        // BOTH spellings, for HomeCloner.rootSpellings' reason: a home
        // addressed through a symlink holds the spelling it was GIVEN in its
        // generated files, and a check against the resolved one alone reports
        // clean without having looked.
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(home.toAbsolutePath().normalize());
        roots.add(real(home));
        Set<String> out = new java.util.TreeSet<>();
        for (String token : HomeRepair.absolutePathTokens(shim)) {
            Path candidate;
            try {
                candidate = Path.of(token).toAbsolutePath().normalize();
            } catch (RuntimeException notAPath) {
                continue;
            }
            for (Path root : roots) {
                if (!candidate.startsWith(root) || candidate.equals(root)) continue;
                out.add(root.relativize(candidate).toString()
                        .replace(java.io.File.separatorChar, '/'));
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code skills/<unit>} or {@code plugins/<unit>} when {@code path} is a
     * unit copy inside {@code home}, else null.
     *
     * <p>The two segments and no more: a shim naming
     * {@code skills/x/scripts/y.py} is running unit {@code skills/x}, and the
     * question "does this home have its own" is asked of the unit, not of the
     * file, because a home with the unit installed at a different internal
     * layout still has its own copy to prefer.
     */
    private static String unitUnder(Path home, Path path) {
        Path rel;
        try {
            rel = real(home).relativize(real(path));
        } catch (IllegalArgumentException notUnderIt) {
            return null;
        }
        if (rel.getNameCount() < 2) return null;
        String top = rel.getName(0).toString();
        if (!"skills".equals(top) && !"plugins".equals(top)) return null;
        return top + "/" + rel.getName(1);
    }

    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException notThere) {
            return abs;
        }
    }

    /** {@code base}-relative and {@code /}-separated, or the absolute path. */
    private static String relativeTo(Path base, Path path) {
        Path abs = real(path);
        try {
            return base.relativize(abs).toString().replace(java.io.File.separatorChar, '/');
        } catch (IllegalArgumentException notUnderIt) {
            return abs.toString();
        }
    }
}
