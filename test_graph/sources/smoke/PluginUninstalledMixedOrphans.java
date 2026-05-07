///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Uninstalls the umbrella plugin while a partner skill still claims
 * the plugin-level MCP server. Asserts that the orphan-detection
 * surface treats each dep correctly:
 *
 * <ul>
 *   <li><b>Plugin-level MCP server</b> ({@code PLUGIN_SERVER_ID}) —
 *       NOT orphan: the partner skill still claims it. Must STAY
 *       registered with the gateway.</li>
 *   <li><b>Contained-skill MCP server</b> ({@code SKILL_SERVER_ID}) —
 *       orphan: only the umbrella plugin's contained {@code inner-impl}
 *       skill claimed it. Must be UNREGISTERED.</li>
 *   <li><b>Plugin store dir</b> + <b>marketplace.json entry</b> +
 *       <b>installed-record</b> all gone.</li>
 *   <li><b>Plugin-level CLI binary + contained CLI binary</b> remain
 *       on disk — feature parity with bare-skill uninstall, which
 *       also leaves CLI binaries around (no orphan-cleanup is
 *       implemented for CLI deps yet on either kind).</li>
 * </ul>
 *
 * <p>Polls the gateway briefly after uninstall to absorb async
 * unregister latency.
 */
public class PluginUninstalledMixedOrphans {
    static final NodeSpec SPEC = NodeSpec.of("plugin.uninstalled.mixed.orphans")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("partner.skill.installed", "plugin.synced")
            .tags("plugin", "uninstall", "orphan")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String pluginName = ctx.get("umbrella.plugin.installed", "pluginName").orElse(null);
            String pluginServerId = ctx.get("umbrella.plugin.installed", "pluginServerId").orElse(null);
            String skillServerId = ctx.get("umbrella.plugin.installed", "skillServerId").orElse(null);
            String pluginCli = ctx.get("umbrella.plugin.installed", "pluginCliBinary").orElse(null);
            String skillCli = ctx.get("umbrella.plugin.installed", "skillCliBinary").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || gatewayUrl == null || pluginName == null
                    || pluginServerId == null || skillServerId == null
                    || pluginCli == null || skillCli == null) {
                return NodeResult.fail("plugin.uninstalled.mixed.orphans", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Pre: confirm both servers are registered + plugin is in store.
            boolean preBothRegistered;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "uninstall-pre-" + ctx.runId())) {
                preBothRegistered = serverListed(mcp, pluginServerId)
                        && serverListed(mcp, skillServerId);
            }

            // Run uninstall.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "uninstall", pluginName);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("CLAUDE_CONFIG_DIR",
                    Path.of(claudeHome).resolve(".claude").toString());
            ProcessRecord proc = Procs.run(ctx, "uninstall", pb);
            int rc = proc.exitCode();

            // Plugin store dir + installed record + marketplace entry
            // should all be gone.
            Path pluginStoreDir = Path.of(home).resolve("plugins").resolve(pluginName);
            Path installedRecord = Path.of(home).resolve("installed").resolve(pluginName + ".json");
            Path marketplaceManifest = Path.of(home).resolve("plugin-marketplace")
                    .resolve(".claude-plugin").resolve("marketplace.json");
            Path marketplaceLink = Path.of(home).resolve("plugin-marketplace")
                    .resolve("plugins").resolve(pluginName);

            boolean storeGone = !Files.exists(pluginStoreDir, LinkOption.NOFOLLOW_LINKS);
            boolean recordGone = !Files.exists(installedRecord);
            boolean marketplaceLinkGone = !Files.exists(marketplaceLink, LinkOption.NOFOLLOW_LINKS);
            boolean marketplaceEntryGone = false;
            if (Files.isRegularFile(marketplaceManifest)) {
                String body = Files.readString(marketplaceManifest);
                marketplaceEntryGone = !body.contains("\"" + pluginName + "\"");
            }

            // Orphan/non-orphan MCP differentiation. Poll briefly to
            // absorb async gateway refresh latency.
            boolean sharedKept = false;   // plugin server (still claimed by partner)
            boolean orphanGone = false;   // skill server (no surviving claimer)
            for (int attempt = 0; attempt < 8; attempt++) {
                try (TgMcp mcp = new TgMcp(gatewayUrl,
                        "uninstall-post-" + ctx.runId() + "-" + attempt)) {
                    boolean sharedListed = serverListed(mcp, pluginServerId);
                    boolean skillListed = serverListed(mcp, skillServerId);
                    if (sharedListed && !skillListed) {
                        sharedKept = true;
                        orphanGone = true;
                        break;
                    }
                    sharedKept = sharedListed;
                    orphanGone = !skillListed;
                }
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }

            // CLI binaries — feature parity with bare-skill uninstall:
            // both stay on disk. Orphan CLI cleanup is not yet
            // implemented for either kind. Documenting the current
            // contract; if cleanup lands later, flip these to
            // assertFalse and add a separate "shared CLI survives"
            // case.
            Path cliDir = Path.of(home).resolve("bin").resolve("cli");
            boolean pluginCliPresent = Files.isExecutable(cliDir.resolve(pluginCli));
            boolean skillCliPresent = Files.isExecutable(cliDir.resolve(skillCli));

            boolean pass = rc == 0 && preBothRegistered
                    && storeGone && recordGone
                    && marketplaceLinkGone && marketplaceEntryGone
                    && sharedKept && orphanGone;
            return (pass
                    ? NodeResult.pass("plugin.uninstalled.mixed.orphans")
                    : NodeResult.fail("plugin.uninstalled.mixed.orphans",
                            "rc=" + rc + " preBoth=" + preBothRegistered
                                    + " storeGone=" + storeGone + " recordGone=" + recordGone
                                    + " marketplaceLink=" + marketplaceLinkGone
                                    + " marketplaceEntry=" + marketplaceEntryGone
                                    + " sharedKept=" + sharedKept
                                    + " orphanGone=" + orphanGone))
                    .process(proc)
                    .assertion("uninstall_exit_zero", rc == 0)
                    .assertion("both_mcp_registered_pre_uninstall", preBothRegistered)
                    .assertion("plugin_store_dir_removed", storeGone)
                    .assertion("plugin_installed_record_removed", recordGone)
                    .assertion("plugin_marketplace_symlink_removed", marketplaceLinkGone)
                    .assertion("plugin_marketplace_manifest_entry_removed", marketplaceEntryGone)
                    .assertion("shared_mcp_server_kept_partner_still_claims_it", sharedKept)
                    .assertion("orphan_contained_mcp_server_unregistered", orphanGone)
                    // CLI parity: bare-skill uninstall also leaves
                    // these. Recorded as metrics, not assertions, to
                    // avoid coupling test outcomes to behavior we
                    // haven't implemented yet either way.
                    .metric("plugin_level_cli_still_on_disk", pluginCliPresent ? 1 : 0)
                    .metric("contained_skill_cli_still_on_disk", skillCliPresent ? 1 : 0)
                    .metric("exitCode", rc);
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
