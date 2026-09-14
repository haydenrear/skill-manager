package dev.skillmanager.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    /**
     * The environment variable a rewritten shim derives its home into.
     *
     * <p>Named, not inlined, because the rewritten line has to be readable by
     * whoever opens the shim next and wonders what happened to the absolute
     * path they wrote.
     */
    public static final String SHIM_HOME_VAR = "SKILL_MANAGER_SHIM_HOME";

    /**
     * The line a rewritten shim derives its home with: two directories above
     * the shim's own location ({@code bin/cli/<name>}).
     *
     * <h2>Portable on purpose (DEF-OHV-190)</h2>
     *
     * <p>{@link #isShellShebang} accepts {@code sh}, {@code dash}, {@code bash},
     * {@code zsh} and {@code ksh}, so this line has to parse and resolve under
     * every one of them. {@code ${BASH_SOURCE:-$0}} does: bash expands an
     * array named without a subscript to element 0 (the sourced file, or the
     * script), and every other shell has no {@code BASH_SOURCE}, so it falls
     * back to {@code $0}, which is the script path when a shim is executed.
     * zsh included: its {@code $0} is the script, not the shell.
     *
     * <p>The line 43e5fb99 wrote, {@link #LEGACY_SHIM_HOME_ANCHOR}, used
     * {@code ${BASH_SOURCE[0]:-$0}}. dash rejects the subscript as "Bad
     * substitution", the command substitution is empty, {@code cd /../..}
     * lands on {@code /}, and the exec goes to {@code //cache/...}: rc 127 on
     * every Debian or Ubuntu {@code #!/bin/sh} shim. macOS's {@code /bin/sh} is
     * bash, which is why nothing showed it. Pinned by
     * {@code ShimAnchorRunsUnderEveryShellTest}, which RUNS the shim under each
     * shell rather than reading this string.
     *
     * <p>Not symlink-resolving, like the line before it: a shim reached
     * through a link from another directory derives the LINK's home. That is
     * unchanged by this line and deliberately out of its scope.
     */
    public static final String SHIM_HOME_ANCHOR =
            SHIM_HOME_VAR + "=\"$(cd \"$(dirname \"${BASH_SOURCE:-$0}\")/../..\" && pwd)\"";

    /**
     * The anchor 43e5fb99 wrote, exactly. Correct under bash, zsh and ksh
     * (measured), broken under {@code sh}/{@code dash}; see
     * {@link #bashOnlyAnchorLines}.
     */
    public static final String LEGACY_SHIM_HOME_ANCHOR =
            SHIM_HOME_VAR + "=\"$(cd \"$(dirname \"${BASH_SOURCE[0]:-$0}\")/../..\" && pwd)\"";

    /** An array subscript on BASH_SOURCE: what dash cannot parse. */
    private static final String BASH_SOURCE_SUBSCRIPT = "${BASH_SOURCE[";

    /**
     * The {@link #SHIM_HOME_VAR} assignment lines of {@code shim} that its own
     * interpreter cannot run: a {@code sh} or {@code dash} shebang (directly or
     * through {@code env}) and a {@code ${BASH_SOURCE[...]}} subscript on the
     * assignment. Trimmed, in file order; empty is conformant.
     *
     * <h2>The rule for shims already on disk (DEF-OHV-190)</h2>
     *
     * <p>Shims carrying {@link #LEGACY_SHIM_HOME_ANCHOR} exist. Under a
     * {@code bash}, {@code zsh} or {@code ksh} shebang they resolve their home
     * correctly, so they are NOT reported and a rewrite leaves that line
     * byte-identical: reporting them wholesale would turn {@code home verify}
     * red on shims that work. Under {@code sh} or {@code dash} they resolve
     * {@code /}, and that is a shim that does not know its home, the same
     * failure {@code FROZEN_HOME_PATH_IN_SHIM} names, so {@code HomeRepair}
     * reports it under that kind and {@link #selfDerivingRewrite} replaces the
     * line with {@link #SHIM_HOME_ANCHOR}.
     *
     * <p>{@code #!/bin/sh} is reported even on a host whose {@code /bin/sh} is
     * bash: the shim is broken the moment the home is copied to one where it
     * is not, which is the move this contract exists for.
     *
     * <p>Only the {@link #SHIM_HOME_VAR} assignment is read. That is the line
     * skill-manager writes; a unit's own installer that uses bash-isms under
     * {@code #!/bin/sh} is that installer's defect, not a home finding.
     */
    public static List<String> bashOnlyAnchorLines(Path shim) {
        if (shim == null) return List.of();
        String body = readShim(shim);
        if (body == null) return List.of();
        return bashOnlyAnchorLinesIn(body);
    }

    private static List<String> bashOnlyAnchorLinesIn(String body) {
        if (!body.startsWith("#!")) return List.of();
        String[] lines = body.split("\n", -1);
        if (!isPosixOnlyShebang(lines[0])) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (SHIM_HOME_ASSIGNMENT.matcher(lines[i]).find() && lines[i].contains(BASH_SOURCE_SUBSCRIPT)) {
                out.add(lines[i].strip());
            }
        }
        return List.copyOf(out);
    }

    /**
     * A frozen shim rewritten to resolve the home it is STANDING IN, or null
     * when it cannot be rewritten safely.
     *
     * <h2>Why rewriting and not refusing</h2>
     *
     * <p>The install that produces these shims does not write them —
     * {@code skill-script:} dependencies run the unit's own installer, and
     * what lands in {@code bin/cli} is that installer's bytes. Refusing the
     * install was considered and rejected in {@code SkillScriptBackend}: it
     * would break every already-shipped unit that writes an absolute wrapper,
     * including ones whose homes are never copied.
     *
     * <p>But skill-manager owns {@code bin/}. It can leave the installer
     * alone and still fix the file afterwards, which is what this does — the
     * shim keeps working exactly where it is, and starts working after a copy
     * as well.
     *
     * <h2>What a copy meant before</h2>
     *
     * <p>{@code home clone} re-anchors these, so a cloned home was fine.
     * Measured 2026-09-06: after a clone the shim points into the copy; after
     * a plain {@code cp -R} — which is what a container image build does — it
     * still points at the SOURCE home. So the image builds green and dies at
     * first use, on a machine where the source path does not exist. A
     * self-deriving shim needs no re-anchoring by anybody.
     *
     * <h2>Deliberately narrow</h2>
     *
     * <p>Returns null unless the file is a text shell script with a shebang.
     * A compiled launcher, a Python console script with a frozen interpreter
     * shebang, anything not obviously a shell body — those are reported by
     * {@link #frozenHomeLines} where they spell the home on a line that runs,
     * and left alone. Half-rewriting a file whose shape is not understood is
     * worse than the freeze.
     *
     * <h2>A shim that already holds the token is NOT "already rewritten" (OHV-4)</h2>
     *
     * <p>This used to return null for any body containing
     * {@link #SHIM_HOME_VAR}. That produced DEF-OHV-001, measured on the root
     * home: {@code bin/cli/computeq}, {@code helm-deploy} and {@code monitoring}
     * carried this preamble and a token-derived {@code export} line, and an
     * {@code exec "/Users/.../.skill-manager/cache/skill-script-.../venv/bin/<tool>"}
     * that was still literal. Any path that put the token into one line first —
     * an installer that half-adopted the recipe, or
     * {@code HomeRepair}'s {@code FOREIGN_PATH_IN_SHIM} fix, which maps another
     * home's path to this one's and then calls this — left every other line
     * frozen for good, and {@code home repair} (which asked this method whether
     * to report) exempted the result.
     *
     * <p>So every remaining literal spelling is re-anchored, whether or not the
     * token is already there. The preamble is added only when no assignment of
     * the token precedes the first rewritten line; a second pass finds no
     * literal and returns null, which is the idempotence the installer and
     * {@code --fix} rely on.
     *
     * <p>Replacement is of a WHOLE path prefix: a spelling followed by a path
     * character ({@code /home-other}) or preceded by one
     * ({@code /private/var/...} while replacing {@code /var/...}) is not this
     * home and is left alone. And the result is checked before it is returned:
     * a rewrite that leaves any spelling behind returns null rather than a
     * second half-rewritten shim.
     *
     * <h2>Comment lines: rewritten, and deliberately not DETECTED</h2>
     *
     * <p>The rewrite's trigger is its own, and wider than the detector's: a
     * literal spelling on ANY line after the shebang, {@code #} comments
     * included — the trigger it had before OHV-4, when every token in the file
     * counted. So once asked, it leaves no spelling of the home in the bytes
     * (BLOCKER 2's repaired wrapper carries its base path in a comment, and a
     * copied home carrying the source's path in prose is still the source's
     * path). {@link #frozenHomeLines} does NOT read comments, because a finding
     * there would make {@code home verify} red on a shim that runs correctly
     * (#341 "Watch for": prose about a path is not a reference to one). The
     * consequence, stated: a shim whose ONLY spelling is a comment is not a
     * finding, so {@code home repair --fix} leaves it byte-identical; the
     * skill-script installer, which asks this method directly, still
     * re-anchors it on a fresh install.
     */
    public static String selfDerivingRewrite(Path home, Path shim) {
        if (home == null || shim == null) return null;
        String body = readShim(shim);
        if (body == null || !body.startsWith("#!")) return null;
        int firstNl = body.indexOf('\n');
        if (firstNl < 0) return null;
        String shebang = body.substring(0, firstNl);
        if (!isShellShebang(shebang)) return null;
        List<String> spellings = spellingsLongestFirst(home);
        // DEF-OHV-190: an sh/dash shim whose anchor is the bash-only line is
        // re-anchored even when it spells no home literally -- it resolves `/`.
        boolean posixOnly = isPosixOnlyShebang(shebang);
        boolean bashOnlyAnchor = posixOnly && !bashOnlyAnchorLinesIn(body).isEmpty();
        if (!spelledAfterShebang(body, spellings) && !bashOnlyAnchor) return null;

        // Depth is fixed by the store: bin/cli/<name>, so the home is two up.
        // Derived from the shim's OWN location, which is the whole point --
        // wherever the file is, that is the home it belongs to.
        String preamble = "\n# Rewritten by skill-manager: resolve the home this shim is standing in\n"
                + "# rather than the one it was written into, so a copy of the home works.\n"
                + SHIM_HOME_ANCHOR + "\n";

        String[] lines = body.substring(firstNl + 1).split("\n", -1);
        boolean assigned = false;
        boolean needsPreamble = false;
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean assignment = SHIM_HOME_ASSIGNMENT.matcher(line).find();
            String replaced = replaceSpellings(line, spellings);
            if (assignment && !replaced.equals(line)) {
                // `SKILL_MANAGER_SHIM_HOME="/abs/home"`: rewriting it would make
                // the variable name itself. A shape nobody generates; refused.
                return null;
            }
            if (assignment) assigned = true;
            if (assignment && posixOnly && line.contains(BASH_SOURCE_SUBSCRIPT)) {
                // Replaced IN PLACE, so the one assignment stays the one
                // assignment. Only the exact line this class wrote is taken;
                // any other subscripted form is not a shape we understand,
                // and it stays reported with repairable=false.
                String stripped = line.strip();
                boolean exported = stripped.startsWith("export ");
                String core = exported ? stripped.substring("export ".length()).strip() : stripped;
                if (!core.equals(LEGACY_SHIM_HOME_ANCHOR)) return null;
                lines[i] = line.substring(0, line.indexOf(stripped.charAt(0)))
                        + (exported ? "export " : "") + SHIM_HOME_ANCHOR;
                changed = true;
                continue;
            }
            if (!replaced.equals(line)) {
                if (!assigned) needsPreamble = true;
                lines[i] = replaced;
                changed = true;
            }
        }
        if (!changed) return null;
        String rewritten = shebang + (needsPreamble ? preamble : "\n") + String.join("\n", lines);
        // POSTCONDITION: never hand back a shim that still spells the home on any
        // line the rewrite reads -- stricter than the detector, so a rewrite can
        // never leave a finding behind either. Nor one whose own interpreter
        // still cannot parse its anchor.
        if (spelledAfterShebang(rewritten, spellings)) return null;
        if (!bashOnlyAnchorLinesIn(rewritten).isEmpty()) return null;
        return rewritten;
    }

    /**
     * A shebang whose interpreter is {@code sh} or {@code dash}: a shell that
     * may be POSIX-only, so bash array syntax is not guaranteed to parse. It
     * is bash on macOS and dash on Debian and Ubuntu, and a shim moves between
     * them with its home.
     */
    private static boolean isPosixOnlyShebang(String shebang) {
        String name = interpreterName(shebang);
        return "sh".equals(name) || "dash".equals(name);
    }

    /**
     * The lines of {@code shim} that spell {@code home} literally, in a place
     * that RUNS — the content rule {@code HomeRepair}'s
     * {@code FROZEN_HOME_PATH_IN_SHIM} reports on (OHV-4, #341). Trimmed, in
     * file order; empty is conformant.
     *
     * <h2>The rule</h2>
     *
     * <p>A literal spelling of this home — any of {@link PathSpellings#of}'s
     * given, real and alias forms, as a whole path prefix, the home root itself
     * included — on:
     *
     * <ul>
     *   <li>any non-comment line after the shebang of a SHELL script (the shape
     *       {@link #selfDerivingRewrite} can re-anchor); or</li>
     *   <li>an {@code exec}, {@code export} or shell-assignment line of any
     *       other text file, which is then reported but not rewritable.</li>
     * </ul>
     *
     * <p>This is deliberately independent of whether a rewrite is on offer.
     * The detector used to ask {@code selfDerivingRewrite(...) != null}, so a
     * shim the rewriter had half-done — token in the {@code export} line, home
     * literal in the {@code exec} line — was exempt precisely because it was
     * broken in the way the rewriter produced (DEF-OHV-001). "Can I rewrite
     * this" is not "does this name the home".
     *
     * <h2>Deliberately NOT reported: a venv-internal shebang</h2>
     *
     * <p>The FIRST line is never read. A console-script entrypoint pip wrote
     * opens {@code #!<home>/venvs/<venv>/bin/python}; that is a literal own-home
     * path too, and it is out of scope on purpose, for three reasons:
     *
     * <ul>
     *   <li>The kernel reads a shebang literally. It cannot hold
     *       {@code ${SKILL_MANAGER_SHIM_HOME}}, so no rewrite exists and a
     *       finding would be one {@code --fix} can never clear.</li>
     *   <li>It belongs to the toolchain tree, not to a shim skill-manager
     *       generates: {@code venvs/} is a toolchain root a clone never carries
     *       and re-provisioning regenerates, interpreter path included.</li>
     *   <li>A shebang is not a line that names a path at runtime from the
     *       shim's own bytes — {@link HomeRepair#absolutePathTokens} has never
     *       tokenized one, so {@link #frozenHomePaths} did not see it
     *       either.</li>
     * </ul>
     *
     * <p>A copied home carrying such an entrypoint is the re-provisioning
     * question ({@code home verify}'s unresolved references, {@code build}),
     * not this one. {@code scripts/measure_goal_no_own_home_path.py} counts the
     * shape separately as {@code shebang_only_out_of_scope} so it never reads as
     * a detector gap.
     *
     * <h2>Deliberately NOT reported: a spelling only in a {@code #} comment</h2>
     *
     * <p>Comment lines are not read. Prose about a path is not a reference to
     * one — #341's "Watch for" records the cold-artifact scanner reporting its
     * own refusal text as a dangling path — and since OHV-2 {@code home verify}
     * fails on every {@code home repair} finding, so a comment-only match would
     * turn verify red on a shim that runs correctly. This classifier is
     * therefore NOT the rewrite's trigger: {@link #selfDerivingRewrite} reads
     * comments too and says what that means for {@code --fix}.
     */
    public static List<String> frozenHomeLines(Path home, Path shim) {
        if (home == null || shim == null) return List.of();
        String body = readShim(shim);
        if (body == null) return List.of();
        return frozenLinesIn(body, spellingsLongestFirst(home));
    }

    /** exec / export / {@code NAME=} (optionally local, readonly or declare). */
    private static final java.util.regex.Pattern RUNNING_LINE = java.util.regex.Pattern.compile(
            "^\\s*(?:exec\\b|export\\b|(?:local\\s+|readonly\\s+|declare(?:\\s+-\\w+)*\\s+)?"
                    + "[A-Za-z_][A-Za-z0-9_]*=)");

    private static final java.util.regex.Pattern SHIM_HOME_ASSIGNMENT = java.util.regex.Pattern.compile(
            "^\\s*(?:export\\s+)?" + SHIM_HOME_VAR + "=");

    private static List<String> frozenLinesIn(String body, List<String> spellings) {
        String[] lines = body.split("\n", -1);
        int from = 0;
        boolean shell = false;
        if (lines.length > 0 && lines[0].startsWith("#!")) {
            shell = isShellShebang(lines[0]);
            from = 1;     // the shebang: see frozenHomeLines, deliberately not read
        }
        List<String> out = new ArrayList<>();
        for (int i = from; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!spellsAny(lines[i], spellings)) continue;
            if (shell || RUNNING_LINE.matcher(lines[i]).find()) out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /**
     * The REWRITE's classifier, kept separate from the detector's
     * ({@link #frozenLinesIn}) on purpose: any line after the shebang, comments
     * included. See {@link #selfDerivingRewrite}.
     */
    private static boolean spelledAfterShebang(String body, List<String> spellings) {
        int firstNl = body.indexOf('\n');
        String rest = body.startsWith("#!") ? (firstNl < 0 ? "" : body.substring(firstNl + 1)) : body;
        for (String line : rest.split("\n", -1)) {
            if (spellsAny(line, spellings)) return true;
        }
        return false;
    }

    /** The home's spellings as whole-prefix patterns, longest spelling first. */
    private static List<String> spellingsLongestFirst(Path home) {
        List<String> spellings = new ArrayList<>(PathSpellings.of(home));
        spellings.removeIf(s -> s.isEmpty() || "/".equals(s));
        spellings.sort(java.util.Comparator.comparingInt(String::length).reversed());
        return List.copyOf(spellings);
    }

    private static java.util.regex.Pattern wholePrefix(String spelling) {
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.\\-/])"
                + java.util.regex.Pattern.quote(spelling) + "(?=[/\"'\\s:;,)}]|$)");
    }

    private static boolean spellsAny(String line, List<String> spellings) {
        for (String s : spellings) {
            if (line.contains(s) && wholePrefix(s).matcher(line).find()) return true;
        }
        return false;
    }

    private static String replaceSpellings(String line, List<String> spellings) {
        String out = line;
        for (String s : spellings) {
            if (!out.contains(s)) continue;
            out = wholePrefix(s).matcher(out)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement("${" + SHIM_HOME_VAR + "}"));
        }
        return out;
    }

    /** The shim's text, or null when it is not a regular, readable, shim-sized text file. */
    private static String readShim(Path shim) {
        try {
            if (!Files.isRegularFile(shim, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(shim) > 1024L * 1024L) return null;
            return Files.readString(shim);
        } catch (IOException | RuntimeException notText) {
            return null;
        }
    }

    /**
     * Is this shebang a SHELL interpreter?
     *
     * <p>Reads the interpreter's own basename rather than searching the line.
     * {@code shebang.contains("sh")} was the first attempt and it is wrong in
     * a way that only shows up sometimes: any path component containing those
     * two letters matches, so a Python console script under a directory named
     * {@code shim-home-1234} was accepted as a shell script. Its own test
     * caught it.
     */
    private static boolean isShellShebang(String shebang) {
        String name = interpreterName(shebang);
        return name != null && switch (name) {
            case "sh", "bash", "zsh", "dash", "ksh" -> true;
            default -> false;
        };
    }

    /** The basename of a shebang's interpreter, or null for an empty shebang. */
    private static String interpreterName(String shebang) {
        String line = shebang.substring(2).trim();
        if (line.isEmpty()) return null;
        String[] words = line.split("\\s+");
        // `#!/usr/bin/env bash` names the interpreter in the second word.
        String interpreter = words[words.length - 1];
        int slash = interpreter.lastIndexOf('/');
        return slash < 0 ? interpreter : interpreter.substring(slash + 1);
    }

    /**
     * Every spelling of the home root — given, real, and their top-level
     * aliases (#343, see {@link PathSpellings}) — LONGEST FIRST, because the
     * caller does a textual replace: replacing {@code /var/x} before
     * {@code /private/var/x} would leave {@code /private${SHIM_HOME}}.
     */
    private static Set<Path> rootSpellings(Path home) {
        List<String> spellings = new ArrayList<>(PathSpellings.of(home));
        spellings.sort(java.util.Comparator.comparingInt(String::length).reversed());
        Set<Path> roots = new LinkedHashSet<>();
        for (String s : spellings) roots.add(Path.of(s));
        return roots;
    }

    public static List<String> frozenHomePaths(Path home, Path shim) {
        if (home == null || shim == null) return List.of();
        // BOTH spellings, for HomeCloner.rootSpellings' reason: a home
        // addressed through a symlink holds the spelling it was GIVEN in its
        // generated files, and a check against the resolved one alone reports
        // clean without having looked. And the alias spellings too (#343):
        // handed /private/var/x, a shim holding /var/x/... is still this home.
        Set<Path> roots = new LinkedHashSet<>();
        for (String s : PathSpellings.of(home)) roots.add(Path.of(s));
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
