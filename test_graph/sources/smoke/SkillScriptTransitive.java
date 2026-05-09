///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Transitive variant of {@link SkillScriptInstalled}. Installs an
 * umbrella fixture whose only job is to {@code file:} reference a child
 * sub-skill that carries the actual {@code skill-script:} CLI dep, then
 * asserts the child's install script ran (a distinct sentinel file lands
 * under {@code bin/cli/}).
 *
 * <p>Distinct sentinel from the standalone test so the transitive
 * assertion can't accidentally pass on the direct case's side-effect.
 */
public class SkillScriptTransitive {
    static final NodeSpec SPEC = NodeSpec.of("skill.script.transitive.installed")
            .kind(NodeSpec.Kind.ACTION)
            // Depend on the standalone skill-script case so the two
            // install processes serialize on the same SKILL_MANAGER_HOME
            // — they both call `skill-manager install` and share store
            // state, and parallel installs would race the CLI lock.
            .dependsOn("env.prepared", "skill.script.installed")
            .tags("cli", "skill-script", "transitive")
            .timeout("120s")
            .output("touchPath", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("skill.script.transitive.installed",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixture = repoRoot.resolve("test_graph/fixtures/skill-script-umbrella-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", fixture.toString(), "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path storeDir = Path.of(home, "skills");
            boolean umbrellaOk = Files.isDirectory(storeDir.resolve("skill-script-umbrella-skill"));
            boolean innerOk = Files.isDirectory(storeDir.resolve("skill-script-inner"));
            Path touch = Path.of(home, "bin", "cli", "skill-script-transitive-touched");
            boolean touchOk = Files.isRegularFile(touch);
            boolean execOk = touchOk && Files.isExecutable(touch);

            boolean pass = rc == 0 && umbrellaOk && innerOk && touchOk && execOk;
            NodeResult result = pass
                    ? NodeResult.pass("skill.script.transitive.installed")
                    : NodeResult.fail("skill.script.transitive.installed",
                            "rc=" + rc + " umbrella=" + umbrellaOk
                                    + " inner=" + innerOk
                                    + " touched=" + touchOk + " exec=" + execOk);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("umbrella_in_store", umbrellaOk)
                    .assertion("inner_transitive_in_store", innerOk)
                    .assertion("transitive_skill_script_touched", touchOk)
                    .assertion("transitive_skill_script_executable", execOk)
                    .metric("exitCode", rc)
                    .publish("touchPath", touch.toString());
        });
    }
}
