package dev.skillmanager.sandbox;

import dev.skillmanager.agent.AgentHomes;

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
public record Confinement(Path root, List<Axis> axes) {

    /**
     * The variable a process sets to declare "everything I touch lives under
     * this directory". Read through {@link AgentHomes#resolve(String)}, so an
     * in-process driver declares it with
     * {@link AgentHomes#setOverride(String, Path)} exactly as it pins the home
     * variables — one mechanism, not a sixth spelling.
     */
    public static final String ROOT_ENV = "SKILL_MANAGER_CONFINE_ROOT";

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
        Path root = AgentHomes.resolve(ROOT_ENV);
        Path normalizedRoot = root == null ? null : normalize(root);
        List<Axis> axes = new ArrayList<>();
        for (String key : VARIABLE_AXES) {
            Path value = AgentHomes.resolve(key);
            axes.add(axis(key, value, normalizedRoot));
        }
        axes.add(axis(CWD, workingDirectory(), normalizedRoot));
        return new Confinement(normalizedRoot, List.copyOf(axes));
    }

    /** The JVM's working directory — the axis nothing can override. */
    public static Path workingDirectory() {
        String dir = System.getProperty("user.dir");
        return dir == null || dir.isBlank() ? null : normalize(Path.of(dir));
    }

    private static Axis axis(String name, Path value, Path root) {
        Path normalized = value == null ? null : normalize(value);
        boolean inside = root != null && normalized != null && normalized.startsWith(root);
        return new Axis(name, normalized, inside);
    }

    /**
     * Absolute and normalized, but deliberately <b>not</b>
     * {@code toRealPath()}: this must answer for paths that do not exist yet —
     * a driver declares its root before laying the home out — and a
     * non-existent path makes {@code toRealPath} throw.
     *
     * <p>The consequence is that confinement is a check over path
     * <em>spellings</em>, and a symlink pointing out of the root would defeat
     * it. That is not this ticket's claim to make: {@code GOAL-one-home-one-answer}
     * clause 2 owns spelling-invariance, HIS-9 owns the effect boundary, and
     * this is the axis check that neither of them performs. Stated so the gap
     * is a decision rather than an omission.
     */
    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
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
     * Whether {@code path} is inside the declared root. Undeclared confinement
     * answers <b>true</b> for everything: with no declaration there is nothing
     * to escape, and this is what keeps the unconfined operator path unchanged.
     */
    public boolean covers(Path path) {
        if (!declared()) return true;
        if (path == null) return false;
        return normalize(path).startsWith(root);
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
        return out;
    }
}
