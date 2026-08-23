package dev.skillmanager.sandbox;

import dev.skillmanager.agent.AgentHomes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>"Is this process confined?" — one call, and it covers the working
 * directory.</b>
 *
 * <h2>The defect this exists to end (DEF-046 / DEF-047, #237)</h2>
 *
 * <p>Every home override in this repository pins the <b>HOME axis</b>:
 * {@code SKILL_MANAGER_HOME}, {@code CLAUDE_HOME}, {@code CLAUDE_CONFIG_DIR},
 * {@code CODEX_HOME}, {@code GEMINI_HOME} — through the environment, or through
 * {@link AgentHomes}' thread-locals for an in-process driver. <b>None of them
 * pins the CWD axis</b>, and the {@code project} family resolves its target by
 * walking up from the working directory, which a JVM cannot change.
 *
 * <p>So a test, a driver or an agent that pins all five variables and believes
 * it is sandboxed is <b>wrong in exactly one direction, silently</b>. Measured
 * twice: {@code JsonContractTest}'s driver pinned five variables, asserted the
 * default home resolved inside its temp directory, and {@code project resolve}
 * still acted on this repository — installing two units into a worktree home
 * and removing a third from it. The driver's own sandbox assertion was correct
 * and answered a narrower question than the one that mattered.
 *
 * <h2>Why a declaration and not an inference</h2>
 *
 * <p>Confinement is <b>declared</b>, by {@link #ROOT_ENV}, and is absent by
 * default. It has to be: the normal operator flow is {@code cd ~/myrepo &&
 * skill-manager project resolve} with {@code SKILL_MANAGER_HOME} naming the
 * root home, and the project's own child home is legitimately somewhere else
 * entirely. A rule that fired on "the CWD-derived target is not the home
 * {@code SKILL_MANAGER_HOME} names" without a declaration would refuse the
 * product's main path.
 *
 * <p>What a declaration buys is the thing DEF-046 lacked: a driver states the
 * one root everything it touches must live under, and <b>production</b> — not
 * the driver's own list of variables — decides whether each axis is inside it.
 * A new axis added here is covered by every existing caller for free, which is
 * the opposite of five variables set by hand at each site.
 *
 * <h2>An UNSET axis is an ESCAPE, not a pass</h2>
 *
 * <p>An unset agent variable resolves, eventually, to the operator's real
 * {@code ~/.claude}. {@code SmEnv}'s class comment records what that cost:
 * five units projected into all three real agent homes and eighteen symlinks
 * repaired by hand. So an axis with no value under a declared confinement is
 * reported as escaping. "I could not look" is never reported as "I looked and
 * it was fine" — the failure mode this epic keeps paying for.
 *
 * <h2>{@link #CWD} is expected to escape, and that is the point</h2>
 *
 * <p>A JVM cannot change its own working directory, so an in-process driver
 * <b>cannot</b> confine the CWD axis, ever. {@link #escapes()} therefore names
 * it rather than hiding it, and the driver's honest assertion is <em>"the only
 * axis outside my confinement is the one I cannot pin"</em>. The protection is
 * then production's: with a confinement declared,
 * {@code dev.skillmanager.project.ProjectRoot} refuses a CWD-derived target
 * outside the root instead of acting on it.
 *
 * @see dev.skillmanager.project.ProjectRoot
 * @see ConfinementEscapeException
 */
public record Confinement(Path root, List<Axis> axes, Source source) {

    /** Where this confinement's root came from. */
    public enum Source {
        /** Nothing declared one. */
        NONE,
        /** {@link #ROOT_ENV} was set — by an operator, a driver, or a binding. */
        EXPLICIT,
        /**
         * DERIVED from a per-checkout home, because a home that lives INSIDE a
         * checkout is a home ABOUT that checkout.
         *
         * <h2>Why an implicit source exists at all</h2>
         *
         * <p>Review of #241, H4: the guard shipped with nothing arming it.
         * {@code grep} found three hits for {@link #ROOT_ENV} — the constant,
         * the test-graph helper and the prose — so re-running DEF-046/DEF-047's
         * shape with the guard merged still went straight through: a session
         * pins {@code SKILL_MANAGER_HOME} to a worktree home, declares no
         * confinement, runs {@code project resolve} from an unnamed checkout,
         * and the home is re-realized at exit 0.
         *
         * <p>{@code <checkout>/.skill-manager} is the per-checkout layout this
         * whole epic exists to produce, and its home root IS the checkout. So
         * that shape needs no new variable to be armed; the boundary DEF-047
         * crossed is already written down in the home's own path.
         *
         * <p><b>The ROOT tier is excluded</b>, and that exclusion is what keeps
         * this from breaking the product: {@code ~/.skill-manager} would derive
         * the operator's whole home directory, which is not a statement about
         * any checkout, and would then refuse a perfectly ordinary
         * {@code project resolve} in a repository kept outside {@code $HOME}.
         *
         * <p>An implicit confinement enforces the <b>CWD axis only</b> — the one
         * defect measured — and never the home axes. Pinning
         * {@code CLAUDE_CONFIG_DIR} outside a project home is a thing operators
         * legitimately do; a rule inferred rather than declared has not earned
         * the right to refuse it.
         */
        PER_CHECKOUT_HOME
    }

    /** The two-arg form, for callers that do not care where the root came from. */
    public Confinement(Path root, List<Axis> axes) {
        this(root, axes, root == null ? Source.NONE : Source.EXPLICIT);
    }

    /**
     * The variable a process sets to declare "everything I touch lives under
     * this directory". Read through {@link AgentHomes#resolve(String)}, so an
     * in-process driver declares it with
     * {@link AgentHomes#setOverride(String, Path)} exactly as it pins the home
     * variables — one mechanism, not a sixth spelling.
     */
    public static final String ROOT_ENV = AgentHomes.CONFINE_ROOT;

    /** The name of the working-directory axis, which no variable can pin. */
    public static final String CWD = "cwd";

    /**
     * One axis of the sandbox: a named thing that decides where this process
     * writes, the path it currently resolves to, and whether that path is
     * inside the declared root.
     *
     * @param name   the variable, or {@link #CWD}
     * @param value  the resolved path, or null when nothing sets it
     * @param inside whether {@code value} is under the confinement root; false
     *               when {@code value} is null — see the class comment
     */
    public record Axis(String name, Path value, boolean inside) {

        /** How this axis reads in a refusal message. */
        public String describe() {
            if (value == null) return name + " = (unset)";
            return name + " = " + value + (inside ? "" : "  ← outside");
        }
    }

    /**
     * The axes, in the order a reader wants them: the store first, because it
     * is the one people think of, then the agent roots, then the one that got
     * away.
     */
    private static final List<String> VARIABLE_AXES = List.of(
            AgentHomes.SKILL_MANAGER_HOME,
            AgentHomes.CLAUDE_HOME,
            AgentHomes.CLAUDE_CONFIG_DIR,
            AgentHomes.CODEX_HOME,
            AgentHomes.GEMINI_HOME);

    /**
     * <b>The one call.</b> Reads every axis and answers whether this process is
     * confined.
     *
     * <p>Never throws and never returns null: an undeclared confinement is a
     * {@code Confinement} whose {@link #root()} is null and whose
     * {@link #declared()} is false, so a caller can log the report either way.
     */
    public static Confinement current() {
        Path explicit = AgentHomes.resolve(ROOT_ENV);
        Source source = explicit != null ? Source.EXPLICIT : Source.NONE;
        Path root = explicit;
        if (root == null) {
            root = perCheckoutHomeRoot();
            if (root != null) source = Source.PER_CHECKOUT_HOME;
        }
        Path normalizedRoot = root == null ? null : canonicalize(root);
        List<Axis> axes = new ArrayList<>();
        for (String key : VARIABLE_AXES) {
            Path value = AgentHomes.resolve(key);
            axes.add(axis(key, value, normalizedRoot));
        }
        axes.add(axis(CWD, workingDirectory(), normalizedRoot));
        return new Confinement(normalizedRoot, List.copyOf(axes), source);
    }

    /**
     * The checkout a per-checkout {@code SKILL_MANAGER_HOME} belongs to, or
     * null. See {@link Source#PER_CHECKOUT_HOME} for why this exists and why
     * the root tier is excluded.
     */
    private static Path perCheckoutHomeRoot() {
        Path store = AgentHomes.resolve(AgentHomes.SKILL_MANAGER_HOME);
        if (store == null) return null;
        Path normalized = store.toAbsolutePath().normalize();
        if (!AgentHomes.STORE_DIR_NAME.equals(String.valueOf(normalized.getFileName()))) return null;
        Path checkout = normalized.getParent();
        if (checkout == null) return null;
        // The ROOT tier is not a statement about a checkout.
        Path userHome = AgentHomes.userHome();
        if (userHome != null
                && canonicalize(userHome).equals(canonicalize(checkout))) return null;
        return checkout;
    }

    /** The JVM's working directory — the axis nothing can override. */
    public static Path workingDirectory() {
        String dir = System.getProperty("user.dir");
        return dir == null || dir.isBlank() ? null : canonicalize(Path.of(dir));
    }

    private static Axis axis(String name, Path value, Path root) {
        Path normalized = value == null ? null : canonicalize(value);
        boolean inside = root != null && normalized != null && normalized.startsWith(root);
        return new Axis(name, normalized, inside);
    }

    /**
     * The canonical spelling of a path, whether or not it exists yet.
     *
     * <h2>Why plain absolute-and-normalized was wrong, measured</h2>
     *
     * <p>This deliberately did not call {@code toRealPath()}, because a driver
     * declares its root BEFORE laying the home out and {@code toRealPath}
     * throws on a path that is not there. The consequence was a
     * <b>false refusal</b>, not merely a missed catch: {@code ProjectRoot}
     * takes {@code user.dir}, which the JVM reports PHYSICALLY, so a
     * confinement declared as {@code /tmp/x} was compared against a working
     * directory of {@code /private/tmp/x} — two spellings of one directory,
     * each asserted to be outside the other:
     *
     * <pre>
     *   target:            /private/tmp/his16-symlink-probe/proj
     *   confinement root:  /tmp/his16-symlink-probe
     *   EXIT=14
     * </pre>
     *
     * <p>{@code /tmp} and {@code java.io.tmpdir} ({@code /var/folders/…}) are
     * BOTH symlinks on this platform, so any driver declaring the obvious root
     * got a 100% refusal rate. Reported in review of #241.
     *
     * <p>So: resolve the deepest ANCESTOR that exists, and re-append the
     * segments that do not exist yet. An existing path gets its real spelling;
     * a path that will be created gets the real spelling of the directory it
     * will be created under. Both ends of every comparison go through here, so
     * the two are always compared in the same alphabet.
     *
     * <p>This also closes the false-NEGATIVE half of DEF-051 for any component
     * that already exists — a symlink inside the root pointing out of it now
     * resolves before the prefix test. What it still cannot see is a link
     * created after the check, which no in-JVM check can.
     */
    static Path canonicalize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        int climbed = 0;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
            climbed++;
        }
        if (existing == null) return abs;
        try {
            Path real = existing.toRealPath();
            int total = abs.getNameCount();
            for (int i = total - climbed; i < total; i++) real = real.resolve(abs.getName(i));
            return real;
        } catch (IOException | RuntimeException e) {
            return abs;
        }
    }

    // ------------------------------------------------------------- questions

    /** Whether any confinement was declared at all. */
    public boolean declared() {
        return root != null;
    }

    /**
     * <b>Is this process confined?</b> True only when a root was declared and
     * <em>every</em> axis — the working directory included — resolves inside
     * it.
     */
    public boolean confined() {
        return declared() && escapes().isEmpty();
    }

    /** The axes that are not inside the declared root, in axis order. */
    public List<Axis> escapes() {
        if (!declared()) return axes;
        List<Axis> out = new ArrayList<>();
        for (Axis a : axes) if (!a.inside()) out.add(a);
        return out;
    }

    /** The names of {@link #escapes()} — what a test asserts against. */
    public List<String> escapedAxes() {
        List<String> out = new ArrayList<>();
        for (Axis a : escapes()) out.add(a.name());
        return out;
    }

    /**
     * The escaping axes a process CAN do something about — every axis except
     * {@link #CWD}.
     *
     * <h2>Why this split is the difference between a report and a guard</h2>
     *
     * <p>Review of #241: {@link #confined()} and {@link #escapes()} were
     * computed and never enforced. The only enforcement in the tree consulted
     * the project root alone, so the same process, back to back, could be told
     * it was not confined and then act anyway:
     *
     * <pre>
     *   sandbox status --json   →  "confined": false, "escaped": ["CODEX_HOME"]   EXIT=14
     *   project resolve --json  →  {"name":"revproj", …}                          EXIT=0
     * </pre>
     *
     * <p>Worse, with every home axis UNSET under a declared confinement,
     * {@code project resolve} exited 0 and scaffolded {@code $HOME/.skill-manager}
     * — the operator's real home, the failure mode this class's own comment
     * calls the one this epic keeps paying for. The report said so and nothing
     * acted on it.
     *
     * <p>These axes are enforced centrally by
     * {@code SkillManagerCli}: a confined process whose STORE or AGENT roots
     * resolve outside its declared root is refused before any command runs.
     * {@link #CWD} is deliberately excluded, because a JVM cannot change its
     * own working directory — that axis is enforced where it is USED, by
     * {@link dev.skillmanager.project.ProjectRoot} refusing a CWD-derived
     * target outside the root. Two axes, two enforcement points, one
     * declaration.
     */
    public List<Axis> enforceableEscapes() {
        List<Axis> out = new ArrayList<>();
        for (Axis a : escapes()) {
            if (CWD.equals(a.name())) continue;
            // An IMPLICIT confinement enforces the CWD axis only -- see
            // Source.PER_CHECKOUT_HOME. A rule inferred rather than declared
            // has not earned the right to refuse an operator's own variables.
            if (source != Source.EXPLICIT) continue;
            if (AgentHomes.CLAUDE_HOME.equals(a.name()) && claudeConfigDirDecides()) continue;
            out.add(a);
        }
        return out;
    }

    /**
     * Whether {@code CLAUDE_CONFIG_DIR} is set, and therefore whether
     * {@code CLAUDE_HOME} decides anything at all.
     *
     * <h2>Measured: enforcing it unconditionally was a false refusal</h2>
     *
     * <p>{@link AgentHomes#claude()} consults {@code CLAUDE_CONFIG_DIR}
     * <b>first</b> and falls back to {@code CLAUDE_HOME} only when it is unset —
     * that precedence is documented on {@code AgentHomes.claude()} and is the
     * whole reason both variables exist. {@link AgentHomes#agentBinding} binds
     * the config DIRS and leaves {@code CLAUDE_HOME} alone for the same reason.
     *
     * <p>So a correctly bound process has {@code CLAUDE_CONFIG_DIR} inside its
     * home and whatever {@code CLAUDE_HOME} it was launched with, and treating
     * the second as an independent axis refused it:
     *
     * <pre>
     *   escaped axes:      CLAUDE_HOME
     *   CLAUDE_CONFIG_DIR = …/h/.claude
     *   CLAUDE_HOME       = …/ag           ← outside
     * </pre>
     *
     * <p>That is this epic's own recurring shape — two answers to one question,
     * and a checker that reads the one production does not. The axis is still
     * REPORTED, because an operator debugging a leak wants to see it; it is
     * enforced only where it is consulted. With both unset, both escape and the
     * refusal stands, which is the case that matters.
     */
    private boolean claudeConfigDirDecides() {
        Axis configDir = axis(AgentHomes.CLAUDE_CONFIG_DIR);
        return configDir != null && configDir.value() != null;
    }

    /** The names of {@link #enforceableEscapes()}. */
    public List<String> enforceableEscapedAxes() {
        List<String> out = new ArrayList<>();
        for (Axis a : enforceableEscapes()) out.add(a.name());
        return out;
    }

    /**
     * Whether {@code path} is inside the declared root. Undeclared confinement
     * answers <b>true</b> for everything: with no declaration there is nothing
     * to escape, and this is what keeps the unconfined operator path unchanged.
     */
    public boolean covers(Path path) {
        if (!declared()) return true;
        if (path == null) return false;
        return canonicalize(path).startsWith(root);
    }

    /** The axis by name, or null. */
    public Axis axis(String name) {
        for (Axis a : axes) if (a.name().equals(name)) return a;
        return null;
    }

    // --------------------------------------------------------------- reports

    /** Human-readable, one axis per line, for a refusal or a log. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!declared()) {
            // No "← outside" markers here. With nothing declared there is
            // nothing to be outside OF, and printing the marker anyway would
            // report six escapes to an operator who has none — a check that
            // cries wolf is read as noise and then not read at all.
            sb.append("confinement root: (not declared — set $")
                    .append(ROOT_ENV).append(" to declare one)");
            for (Axis a : axes) {
                sb.append("\n  ").append(a.name()).append(" = ")
                        .append(a.value() == null ? "(unset)" : a.value());
            }
            return sb.toString();
        }
        sb.append("confinement root: ").append(root);
        for (Axis a : axes) sb.append("\n  ").append(a.describe());
        return sb.toString();
    }

    /** The report as a map, for a {@code --json} surface. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("declared", declared());
        out.put("source", source.name());
        out.put("root", root == null ? null : root.toString());
        out.put("confined", confined());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Axis a : axes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("axis", a.name());
            row.put("value", a.value() == null ? null : a.value().toString());
            row.put("inside", a.inside());
            rows.add(row);
        }
        out.put("axes", rows);
        out.put("escaped", escapedAxes());
        out.put("enforceableEscaped", enforceableEscapedAxes());
        // The TYPED code, so a driver branches on a discriminator rather than
        // on a boolean it has to interpret — the same reason every other
        // refusal in this CLI carries one. Omitted when the answer is "yes,
        // confined": there is no error to name. Review of #241, M2.
        if (declared() && !confined()) out.put("error", ConfinementEscapeException.ERROR_CODE);
        return out;
    }
}
