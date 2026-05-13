///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgFixture.java
//SOURCES ../lib/TgMcp.java
//SOURCES ./plugin/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Installs a sibling skill that claims the SAME MCP server name as
 * the umbrella plugin's plugin-level MCP dep. The orphan check on the
 * upcoming plugin uninstall will see this skill's claim and keep that
 * server registered with the gateway.
 *
 * <p>Why a skill (not a plugin) for the partner? {@code RemoveUseCase}
 * computes orphan claims via {@code SkillStore.listInstalled()} —
 * skill-only today. A surviving plugin claim wouldn't be seen
 * (acknowledged gap). This node deliberately uses the implemented
 * branch.
 *
 * <p>Publishes the partner skill name + the shared server id so
 * {@link PluginUninstalledMixedOrphans} can target them without
 * re-stamping.
 */
public class PartnerSkillInstalled {
    static final NodeSpec SPEC = NodeSpec.of("partner.skill.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("umbrella.plugin.installed")
            .tags("plugin", "install", "mcp")
            .timeout("90s")
            .output("partnerSkillName", "string")
            .output("sharedServerId", "string");

    private static final String PARTNER_SKILL_NAME = "partner-skill";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String pluginServerId = ctx.get("umbrella.plugin.installed", "pluginServerId").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || mcpUrl == null || gatewayUrl == null || pluginServerId == null) {
                return NodeResult.fail("partner.skill.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/partner-skill-template");
            Path destRoot = Path.of(home).resolve("fixtures");

            Path skillDir = TgFixture.stampPartnerSkill(
                    template, destRoot, PARTNER_SKILL_NAME,
                    pluginServerId, "global-sticky", mcpUrl);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + skillDir, "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("CLAUDE_CONFIG_DIR",
                    Path.of(claudeHome).resolve(".claude").toString());

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            // Skill in store.
            Path partnerStoreDir = Path.of(home).resolve("skills").resolve(PARTNER_SKILL_NAME);
            boolean partnerInStore = Files.isDirectory(partnerStoreDir);

            // Shared server still registered with the gateway after the
            // partner install. (Plugin's claim was already there;
            // partner's claim is now also present.)
            boolean sharedServerStillRegistered;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "partner-skill-installed-" + ctx.runId())) {
                sharedServerStillRegistered = serverListed(mcp, pluginServerId);
            }

            boolean pass = rc == 0 && partnerInStore && sharedServerStillRegistered;
            return (pass
                    ? NodeResult.pass("partner.skill.installed")
                    : NodeResult.fail("partner.skill.installed",
                            "rc=" + rc + " partnerInStore=" + partnerInStore
                                    + " sharedServer=" + sharedServerStillRegistered))
                    .process(proc)
                    .assertion("install_exit_zero", rc == 0)
                    .assertion("partner_skill_in_store", partnerInStore)
                    .assertion("shared_mcp_server_still_registered_after_partner_install",
                            sharedServerStillRegistered)
                    .metric("exitCode", rc)
                    .publish("partnerSkillName", PARTNER_SKILL_NAME)
                    .publish("sharedServerId", pluginServerId);
        });
    }

    private static boolean serverListed(TgMcp mcp, String serverId) {
        Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
        Object items = res.get("items");
        return items instanceof List<?> list
                && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                        && serverId.equals(m.get("server_id")));
    }
}
