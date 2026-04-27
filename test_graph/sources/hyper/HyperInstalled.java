///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installs the just-published {@code hyper-experiments} skill from the
 * registry into the per-run {@code SKILL_MANAGER_HOME}, asserts the SKILL.md
 * + skill-manager.toml landed, and triggers transitive registration of the
 * declared MCP servers (runpod) with the gateway.
 *
 * <p>The install runs from a temp working directory rather than inside the
 * checkout — the user explicitly wanted the install path exercised from a
 * "fresh folder", not the source repo.
 */
public class HyperInstalled {
    static final NodeSpec SPEC = NodeSpec.of("hyper.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.published", "gateway.up")
            .tags("hyper", "registry", "install", "mcp")
            .timeout("120s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("hyper.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Run from a fresh cwd that is not the checkout — exercises the
            // by-name resolution path through the registry.
            Path freshCwd = Path.of(home).resolve("install-cwd");
            Files.createDirectories(freshCwd);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "hyper-experiments",
                    "--registry", registryUrl);
            pb.directory(freshCwd.toFile());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc;
            try {
                rc = Procs.runLogged(ctx, "install", pb);
            } catch (Exception e) {
                return NodeResult.error("hyper.installed", e);
            }

            Path skillDir = Path.of(home).resolve("skills/hyper-experiments");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            boolean pass = rc == 0 && mdOk && tomlOk;
            NodeResult result = pass
                    ? NodeResult.pass("hyper.installed")
                    : NodeResult.fail("hyper.installed",
                            "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk);
            return Procs.attach(result, ctx, "install", pass ? 0 : 1, 200)
                    .assertion("install_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }
}
