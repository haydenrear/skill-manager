///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A HALF-rewritten own-home shim (DEF-OHV-001, OHV-4 #341): the root home's
 * {@code bin/cli/computeq}, {@code helm-deploy} and {@code monitoring}, byte for
 * byte in shape. The rewrite header and the {@code SKILL_MANAGER_SHIM_HOME}
 * assignment are there, the {@code export} line derives the home, and the
 * {@code exec} line still spells this home absolutely:
 *
 * <pre>
 * #!/usr/bin/env bash
 * # Rewritten by skill-manager: resolve the home this shim is standing in
 * # rather than the one it was written into, so a copy of the home works.
 * SKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." &amp;&amp; pwd)"
 * export MONITORING_DEPLOY_CDC_ROOT="${SKILL_MANAGER_SHIM_HOME}/skills/hv-unit"
 * exec "&lt;home&gt;/cache/skill-script-hv-unit-half-tool/venv/bin/half-tool" "$@"
 * </pre>
 *
 * <p>Until OHV-4 {@code home repair} exempted it: its detector asked "can the
 * rewrite still offer something", and the rewrite declined any shim already
 * holding the token. Both verdicts called the root home clean over a shim that a
 * copied home would run from the source host's path.
 *
 * <p>Beyond {@code runShape}'s common assertions (repair and verify both name
 * it, {@code --fix} then a separate detection is clean), this node checks the
 * repaired BYTES: no spelling of the home survives, the exec line names the
 * token, there is still exactly one assignment of it, the shim still runs, and a
 * second {@code --fix} changes nothing.
 */
public class HalfRewrittenShimIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.half.rewritten.shim")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "shim", "ohv-4")
            .timeout("600s");

    static final String TOOL = "half-tool";
    static final String CACHE_REL = "cache/skill-script-" + HomeVerdictsSupport.UNIT + "-" + TOOL
            + "/venv/bin/" + TOOL;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("half-rewritten-shim", "FROZEN_HOME_PATH_IN_SHIM", 1, true),
                (Path subject, Path neighbour) -> {
                    Path tool = subject.resolve(CACHE_REL);
                    Files.createDirectories(tool.getParent());
                    Files.writeString(tool, "#!/bin/sh\nexit 0\n");
                    tool.toFile().setExecutable(true);
                    Path shim = subject.resolve("bin/cli").resolve(TOOL);
                    Files.writeString(shim, "#!/usr/bin/env bash\n"
                            + "# Rewritten by skill-manager: resolve the home this shim is standing in\n"
                            + "# rather than the one it was written into, so a copy of the home works.\n"
                            + "SKILL_MANAGER_SHIM_HOME=\"$(cd \"$(dirname \"${BASH_SOURCE[0]:-$0}\")/../..\" && pwd)\"\n"
                            + "export MONITORING_DEPLOY_CDC_ROOT=\"${SKILL_MANAGER_SHIM_HOME}/skills/"
                            + HomeVerdictsSupport.UNIT + "\"\n"
                            + "exec \"" + tool + "\" \"$@\"\n", StandardCharsets.UTF_8);
                    shim.toFile().setExecutable(true);
                    return "bin/cli/" + TOOL;
                },
                HalfRewrittenShimIsReported::afterFix));
    }

    /** The repaired bytes, checked while the subject still exists. */
    static List<HomeVerdictsSupport.Check> afterFix(com.hayden.testgraphsdk.sdk.NodeContext ctx,
                                                    Path subject, String rel) throws IOException {
        Path shim = subject.resolve(rel);
        String body = Files.readString(shim, StandardCharsets.UTF_8);
        List<HomeVerdictsSupport.Check> checks = new ArrayList<>();

        List<String> spellings = HomeVerdictsSupport.spellings(subject);
        List<String> survivors = spellings.stream().filter(body::contains).toList();
        checks.add(new HomeVerdictsSupport.Check("the_fixed_shim_spells_no_form_of_its_own_home",
                survivors.isEmpty(), "the fixed shim still spells " + survivors + ": " + body));

        String tokenExec = "exec \"${SKILL_MANAGER_SHIM_HOME}/" + CACHE_REL + "\" \"$@\"";
        checks.add(new HomeVerdictsSupport.Check("the_exec_line_derives_the_home_from_the_token",
                body.contains(tokenExec), "no `" + tokenExec + "` line: " + body));

        long assignments = body.lines().filter(l -> l.startsWith("SKILL_MANAGER_SHIM_HOME=")).count();
        checks.add(new HomeVerdictsSupport.Check("exactly_one_assignment_of_the_token_no_second_preamble",
                assignments == 1, assignments + " assignment line(s): " + body));

        int ran = runShim(shim);
        checks.add(new HomeVerdictsSupport.Check("the_fixed_shim_still_runs_its_tool",
                ran == 0, "running the fixed shim exited " + ran));

        HomeVerdictsSupport.Verdict again = HomeVerdictsSupport.repairJson(ctx, "fix-again", subject, "--fix");
        String bodyAgain = Files.readString(shim, StandardCharsets.UTF_8);
        boolean noop = again.exit() == 0 && HomeVerdictsSupport.report(again).parsedClean()
                && bodyAgain.equals(body);
        checks.add(new HomeVerdictsSupport.Check("a_second_fix_is_a_no_op",
                noop, "second --fix exited " + again.exit() + ", bytes "
                        + (bodyAgain.equals(body) ? "unchanged" : "CHANGED") + ": "
                        + HomeVerdictsSupport.head(again.stdout())));
        return checks;
    }

    private static int runShim(Path shim) {
        try {
            Process p = new ProcessBuilder("bash", shim.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -2;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
