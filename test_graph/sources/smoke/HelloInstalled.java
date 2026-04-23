///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

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
            .tags("registry", "add")
            .timeout("60s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("hello.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "add", "hello-skill",
                    "--registry", registryUrl,
                    "--yes", "--no-install")
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc;
            try {
                rc = pb.start().waitFor();
            } catch (Exception e) {
                return NodeResult.error("hello.installed", e);
            }

            Path skillDir = Path.of(home).resolve("skills/hello-skill");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            return (rc == 0 && mdOk && tomlOk
                    ? NodeResult.pass("hello.installed")
                    : NodeResult.fail("hello.installed", "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk))
                    .assertion("add_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }
}
