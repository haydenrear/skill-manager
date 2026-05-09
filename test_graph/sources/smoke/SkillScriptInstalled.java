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
 * Drives the skill-script CLI backend end-to-end: install the
 * {@code skill-script-skill} fixture (which carries a {@code
 * skill-scripts/install.sh} that touches a sentinel file under
 * {@code $SKILL_MANAGER_BIN_DIR}), then assert the sentinel exists.
 *
 * <p>This is the direct case — only the top-level skill carries the CLI
 * dep. The transitive variant lives in {@code SkillScriptTransitive}.
 */
public class SkillScriptInstalled {
    static final NodeSpec SPEC = NodeSpec.of("skill.script.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("cli", "skill-script")
            .timeout("120s")
            .output("touchPath", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("skill.script.installed", "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixture = repoRoot.resolve("test_graph/fixtures/skill-script-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", fixture.toString(), "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path storeDir = Path.of(home, "skills");
            boolean storeOk = Files.isDirectory(storeDir.resolve("skill-script-skill"));
            Path touch = Path.of(home, "bin", "cli", "skill-script-touched");
            boolean touchOk = Files.isRegularFile(touch);
            boolean execOk = touchOk && Files.isExecutable(touch);

            boolean pass = rc == 0 && storeOk && touchOk && execOk;
            NodeResult result = pass
                    ? NodeResult.pass("skill.script.installed")
                    : NodeResult.fail("skill.script.installed",
                            "rc=" + rc + " store=" + storeOk
                                    + " touched=" + touchOk + " exec=" + execOk);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("skill_in_store", storeOk)
                    .assertion("skill_script_touched", touchOk)
                    .assertion("skill_script_executable", execOk)
                    .metric("exitCode", rc)
                    .publish("touchPath", touch.toString());
        });
    }
}
