///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Naming a home wins over a shim that has opinions about which home it is.
 *
 * <h2>Defect 10, which #124 calls the sharpest, and it is</h2>
 *
 * <p>Two documented behaviours, each correct alone, that contradict each other
 * in composition:
 *
 * <ol>
 *   <li>A home's {@code bin/cli/skill-manager} shim <b>exports its own</b>
 *       {@code SKILL_MANAGER_HOME}, deliberately overriding an inherited value.
 *       Its comment argues the case and names the incident: running a project
 *       home's shim against a decoy home created ten directories in the decoy,
 *       because the one command whose entire purpose is "the CLI for THIS home"
 *       was the command most likely to mutate a different one.</li>
 *   <li>{@code bootstrap-home.sh} projects a worktree home by running
 *       {@code env SKILL_MANAGER_HOME=<target> "$CLI" sync --skip-mcp}, where
 *       {@code $CLI} is {@code $SKILL_MANAGER_CLI} or else
 *       {@code command -v skill-manager}.</li>
 * </ol>
 *
 * <p>On any developer machine where {@code skill-manager} on PATH is a home
 * shim — which is the normal state, because {@code home shims} puts one there
 * and its directory goes on PATH — the shim overwrites the variable bootstrap
 * set, the sync targets the wrong home, the worktree home is never projected,
 * bootstrap exits 6, and roughly 58 downstream assertions in
 * {@code git-issue-workflow}'s selftest fall over. ARTI-14's review measured it
 * exactly: the suite is 149 passed / 58 failed as run, and <b>207 passed / 0
 * failed</b> with {@code SKILL_MANAGER_CLI} pointed at a plain CLI. Same suite,
 * green. This ticket hit the same wall creating its own worktree and used the
 * same workaround.
 *
 * <h2>What this node can and cannot assert</h2>
 *
 * <p>The fix is one line and it is <b>not in this repository</b>:
 * {@code bootstrap-home.sh} lives in the {@code git-issue-workflow} leaf, and
 * the outer {@code CLAUDE.md} forbids starting a leaf change from anywhere but
 * that leaf. So this node cannot land the fix. What it can do — and what makes
 * the leaf fix a one-liner with a green target to aim at — is pin down the two
 * halves of the contract so neither can drift:
 *
 * <ul>
 *   <li>the shim <b>never runs against an inherited {@code SKILL_MANAGER_HOME}</b>
 *       (half 1, and if this ever stops being true the decoy incident above
 *       comes back);</li>
 *   <li><b>{@code --home} beats the shim</b> (the escape the shim's own comment
 *       documents — "Name a different home with {@code --home}, or call the CLI
 *       directly"). Verified by hand on 2026-08-16 against the operator's root
 *       shim: {@code home describe --home <tmp>} addressed the named path, and
 *       wrote nothing.</li>
 * </ul>
 *
 * <p>Together those two make the remedy exact: bootstrap should pass
 * {@code --home <target>} rather than exporting the variable, and this node is
 * the evidence that doing so works. Anyone can then change one line in the leaf
 * and know before running it what the result will be.
 *
 * <h2>HIS-9 changed half 1, and it is a strengthening rather than a reversal</h2>
 *
 * <p>Overriding silently was the OTHER half of the same defect. The shim edited
 * <b>y</b> having been told <b>x</b> and said nothing, which is how a command
 * aimed at a worktree home lands in the root home — the class HIS-9 (#226)
 * exists for, on the launch surface instead of in the filesystem. Measured
 * before the fix, on two synthetic homes:
 * {@code SKILL_MANAGER_HOME=<x> <y>/bin/cli/skill-manager home describe}
 * exited <b>0</b> and reported {@code SKILL_MANAGER_HOME <y>}.
 *
 * <p>So the shim now <b>refuses</b>, exit
 * {@code LauncherShims.HOME_MISMATCH_EXIT_CODE} (79), naming the home you asked
 * for and the home it would have edited instead. Neither home is silently
 * operated on, which is what half 1 was always protecting; what changed is that
 * the disagreement is now visible rather than resolved in silence.
 *
 * <p><b>This makes bootstrap-home.sh's fix mandatory rather than merely
 * correct.</b> Before, {@code env SKILL_MANAGER_HOME=<target> "$CLI" sync} with
 * a home shim as {@code $CLI} silently synced the wrong home and 58 downstream
 * assertions fell over; now it exits 79 with both homes named. The remedy is
 * unchanged and is asserted below: pass {@code --home <target>}.
 */
public class BootstrapProjectsTheTargetHome {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.bootstrap.projects.target")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "shim")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path shimHome = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);
            Path target = scratch.resolve("bootstrap-target");

            ProcessRecord shims;
            ProcessRecord viaEnv;
            ProcessRecord viaFlag;
            try {
                Files.createDirectories(target);
                shims = HomeIntegritySupport.sm(ctx, "home-shims", shimHome, "home", "shims");
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            Path shim = shimHome.resolve("bin").resolve("cli").resolve("skill-manager");
            boolean shimWritten = Files.isExecutable(shim);
            if (!shimWritten) {
                return unproven("home shims wrote no CLI entrypoint (exit "
                        + shims.exitCode() + ")").process(shims);
            }

            // HALF 1. Run the shim with SKILL_MANAGER_HOME naming a DIFFERENT
            // home. It must not operate on either one silently: since HIS-9 it
            // REFUSES, exit 79, naming both.
            //
            // Asserted on the exit code AND on both paths appearing in the
            // output, never on the status alone. A shim that refused
            // unconditionally would satisfy a status-only assertion perfectly,
            // and this file has been burned by exactly that: a `printf '%%s'`
            // copied across once made every home's shim refuse on every
            // invocation, and the one-sided assertion covering it stayed green.
            ProcessBuilder envPb = new ProcessBuilder(shim.toString(), "home", "describe");
            SmEnv.apply(ctx, envPb, target);
            viaEnv = com.hayden.testgraphsdk.sdk.Procs.run(ctx, "shim-with-inherited-env", envPb);
            String envOut = readLog(ctx.reportDir(), viaEnv);
            boolean shimRefusedTheMismatch = viaEnv.exitCode() == HOME_MISMATCH_EXIT_CODE;
            boolean refusalNamesBothHomes =
                    mentions(envOut, target) && mentions(envOut, shimHome);
            // The property half 1 always protected, restated for the refusal:
            // the shim did not run against the home it was handed. `home
            // describe` prints its store root on a `SKILL_MANAGER_HOME <path>`
            // line, so a refusal that never reached the CLI cannot produce one.
            boolean shimNeverRanAgainstTheNamedHome =
                    !envOut.contains("SKILL_MANAGER_HOME " + target);

            // HALF 2. Same shim, same inherited value, plus --home. The flag
            // must win — this is the escape the shim's comment documents and
            // the one bootstrap-home.sh should be using.
            ProcessBuilder flagPb = new ProcessBuilder(shim.toString(),
                    "home", "describe", "--home", target.toString());
            SmEnv.apply(ctx, flagPb, shimHome);
            viaFlag = com.hayden.testgraphsdk.sdk.Procs.run(ctx, "shim-with-home-flag", flagPb);
            String flagOut = readLog(ctx.reportDir(), viaFlag);
            // The target is not a home yet, so `home describe` refuses — and the
            // refusal names the path it was pointed at, which is the whole
            // question. Addressing the right home is what is under test, not
            // whether that home happens to exist.
            boolean flagAddressedTheTarget = flagOut.contains(target.toString());
            boolean flagDidNotAddressTheShimsHome =
                    !flagOut.contains(shimHome.toString() + "\n")
                            && !flagOut.contains("home: " + shimHome);

            boolean pass = shimWritten
                    && shimRefusedTheMismatch && refusalNamesBothHomes
                    && shimNeverRanAgainstTheNamedHome
                    && flagAddressedTheTarget && flagDidNotAddressTheShimsHome;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "shimWritten=" + shimWritten
                                    + " shimRefusedTheMismatch=" + shimRefusedTheMismatch
                                    + " refusalNamesBothHomes=" + refusalNamesBothHomes
                                    + " shimNeverRanAgainstTheNamedHome="
                                    + shimNeverRanAgainstTheNamedHome
                                    + " flagAddressedTheTarget=" + flagAddressedTheTarget
                                    + " flagDidNotAddressTheShimsHome="
                                    + flagDidNotAddressTheShimsHome
                                    + " envExit=" + viaEnv.exitCode()
                                    + " (expected " + HOME_MISMATCH_EXIT_CODE + ")"
                                    + " flagExit=" + viaFlag.exitCode());

            return result
                    .process(shims).process(viaEnv).process(viaFlag)
                    .assertion("the_home_carries_its_own_cli_entrypoint", shimWritten)
                    .assertion("the_shim_refuses_a_home_it_was_not_told_to_edit",
                            shimRefusedTheMismatch)
                    .assertion("the_refusal_names_both_homes", refusalNamesBothHomes)
                    .assertion("the_shim_never_runs_against_an_inherited_home",
                            shimNeverRanAgainstTheNamedHome)
                    .assertion("naming_a_home_with_the_flag_beats_the_shims_own_binding",
                            flagAddressedTheTarget)
                    .assertion("the_flag_does_not_silently_fall_back_to_the_shims_home",
                            flagDidNotAddressTheShimsHome)
                    .metric("shimExitOnHomeMismatch", viaEnv.exitCode())
                    .log("Defect 10 is bootstrap-home.sh's `env SKILL_MANAGER_HOME=<target>"
                            + " \"$CLI\" sync` meeting a shim that binds its own home. Since"
                            + " HIS-9 that composition exits " + HOME_MISMATCH_EXIT_CODE
                            + " with both homes named instead of syncing the wrong one in"
                            + " silence. The last two assertions are the remedy: pass --home"
                            + " instead. The one-line fix is in the git-issue-workflow leaf"
                            + " and cannot land from this repository.");
        });
    }

    private static String readLog(Path reportDir, ProcessRecord proc) {
        try {
            String log = proc.logPath();
            if (log == null || log.isBlank()) return "";
            Path p = Path.of(log);
            if (!p.isAbsolute() && reportDir != null) p = reportDir.resolve(p);
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("the_home_carries_its_own_cli_entrypoint", false)
                .assertion("the_shim_refuses_a_home_it_was_not_told_to_edit", false)
                .assertion("the_refusal_names_both_homes", false)
                .assertion("the_shim_never_runs_against_an_inherited_home", false)
                .assertion("naming_a_home_with_the_flag_beats_the_shims_own_binding", false)
                .assertion("the_flag_does_not_silently_fall_back_to_the_shims_home", false);
    }

    /**
     * Does {@code out} name {@code home}, in either spelling?
     *
     * <p>The shim resolves its own location with {@code pwd -P} while the node
     * holds whatever spelling the fixture handed it, and on macOS a temp path is
     * reachable as both {@code /var/…} and {@code /private/var/…}. A raw
     * {@code contains} on one spelling is a false negative — the trap
     * {@code Fs.realOrNormalized}'s javadoc says this codebase has fallen into
     * five times.
     */
    private static boolean mentions(String out, Path home) {
        if (out.contains(home.toString())) return true;
        try {
            return out.contains(home.toRealPath().toString());
        } catch (IOException notThere) {
            return false;
        }
    }

    /** Mirrors {@code LauncherShims.HOME_MISMATCH_EXIT_CODE}; graph nodes do not
     *  compile against the product's classes. */
    private static final int HOME_MISMATCH_EXIT_CODE = 79;
}
