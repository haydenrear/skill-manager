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
 * Installs {@code fixtures/mcp-tool-loads-skill} from a local file path
 * to drive the new MCP-load tool-bundling path end-to-end.
 *
 * <p>Each of the three MCP deps in that fixture
 * ({@code mcp-tool-load-{npm,uv,docker}}) declares one non-binary load
 * type. Installing them forces {@code PlanBuilder} to collect their
 * {@code requiredToolIds()}, fold them with the other skills' tool
 * needs, and emit {@code Section.TOOLS} {@code EnsureTool} actions that
 * {@code ToolInstallRecorder} executes before the gateway register
 * step. {@link McpToolLoadsBundled} asserts the post-install state.
 *
 * <p>All three load entries use {@code default_scope = "session"}, so
 * the gateway registers them but never auto-deploys — no docker pull,
 * no npx fetch, no PyPI hit. The test stays hermetic.
 */
public class McpToolLoadsInstalled {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.loads.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared", "gateway.up")
            .tags("mcp", "tool-loads", "install")
            .timeout("300s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("mcp.tool.loads.installed",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixture = repoRoot.resolve(
                    "test_graph/fixtures/mcp-tool-loads-skill");
            if (!Files.isDirectory(fixture)) {
                return NodeResult.fail("mcp.tool.loads.installed",
                        "fixture missing at " + fixture);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", fixture.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path skillDir = Path.of(home).resolve("skills/mcp-tool-loads-skill");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            boolean pass = rc == 0 && mdOk && tomlOk;
            NodeResult result = pass
                    ? NodeResult.pass("mcp.tool.loads.installed")
                    : NodeResult.fail("mcp.tool.loads.installed",
                            "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }
}
