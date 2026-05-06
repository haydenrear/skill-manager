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
 * Installs hello-skill from the Java registry into a fresh
 * {@code SKILL_MANAGER_HOME/skills/}, asserts the SKILL.md + skill-manager.toml
 * both landed. Exercises: registry → client transport → tar.gz extraction →
 * SkillStore commit.
 */
public class HelloInstalled {
    static final NodeSpec SPEC = NodeSpec.of("hello.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hello.published")
            .tags("registry", "install")
            .timeout("60s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || registryUrl == null) {
                return NodeResult.fail("hello.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "hello-skill",
                    "--registry", registryUrl,
                    "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path skillDir = Path.of(home).resolve("skills/hello-skill");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            boolean pass = rc == 0 && mdOk && tomlOk;
            NodeResult result = pass
                    ? NodeResult.pass("hello.installed")
                    : NodeResult.fail("hello.installed", "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk);
            return result
                    .process(proc)
                    .assertion("add_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }
}
