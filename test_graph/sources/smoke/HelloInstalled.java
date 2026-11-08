///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installs examples/hello-skill from a local {@code file:} coordinate into a
 * fresh {@code SKILL_MANAGER_HOME/skills/}, asserts the SKILL.md +
 * skill-manager.toml both landed. Exercises: file coordinate → fetch →
 * SkillStore commit.
 *
 * <p>HP-11 (#426): this used to install from a per-run registry server that
 * {@code hello.published} had just published to. skill-manager is not used
 * through that server — units install from github/git/file coordinates — so
 * the graph no longer boots docker, Postgres or the registry to reach a
 * fixture that sits in this repository.
 */
public class HelloInstalled {
    static final NodeSpec SPEC = NodeSpec.of("hello.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared", "gateway.up")
            .tags("install", "file")
            // Cold runs may bundle uv and node. Keep this above the one-minute
            // executor default so external validation fails on behavior, not
            // setup latency.
            .timeout("180s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) {
                return NodeResult.fail("hello.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path helloSkill = repoRoot.resolve("examples/hello-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + helloSkill,
                    "--yes");
            SmEnv.apply(ctx, pb, home);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path skillDir = Path.of(home).resolve("skills/hello-skill");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            // hello-skill declares one MCP server. Until HP-11 it was a Docker
            // image at global-sticky scope, so on a host without a docker
            // daemon install exited 0 and left MCP_REGISTRATION_FAILED behind
            // for every later command to report. Exit 0 alone did not see it.
            String log;
            try {
                log = Files.readString(Procs.logFile(ctx, "install"));
            } catch (Exception e) {
                log = "";
            }
            boolean mcpRegistered = log.contains("sequential-thinking")
                    && !log.contains("MCP_REGISTRATION_FAILED");

            boolean pass = rc == 0 && mdOk && tomlOk && mcpRegistered;
            NodeResult result = pass
                    ? NodeResult.pass("hello.installed")
                    : NodeResult.fail("hello.installed", "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk
                            + " mcpRegistered=" + mcpRegistered);
            return result
                    .process(proc)
                    .assertion("add_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .assertion("mcp_dependency_registered_without_docker", mcpRegistered)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }
}
