///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * End-to-end exercise of {@code skill-manager sync} for plugins:
 * simulate drift in the skill-manager-owned plugin marketplace
 * ({@code <home>/plugin-marketplace/}), then run {@code sync} and
 * assert the {@link
 * dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins} effect
 * heals the layout back to install-time invariants.
 *
 * <p>Drift simulated:
 * <ul>
 *   <li>Delete the per-plugin symlink under
 *       {@code plugin-marketplace/plugins/<name>}.</li>
 *   <li>Truncate {@code plugin-marketplace/.claude-plugin/marketplace.json}
 *       so the manifest no longer lists the umbrella plugin.</li>
 * </ul>
 *
 * <p>After {@code sync}, both must be regenerated from the live
 * installed-plugin set. Sync's CLI output must also include the
 * canonical MCP install-results JSON block — proof it re-walked the
 * gateway and re-registered every installed unit's MCP deps (parity
 * with {@link SkillSynced}).
 */
public class PluginSynced {
    static final NodeSpec SPEC = NodeSpec.of("plugin.synced")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("umbrella.plugin.installed")
            .tags("plugin", "sync")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String pluginName = ctx.get("umbrella.plugin.installed", "pluginName").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || pluginName == null) {
                return NodeResult.fail("plugin.synced", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path marketplaceRoot = Path.of(home).resolve("plugin-marketplace");
            Path manifest = marketplaceRoot.resolve(".claude-plugin").resolve("marketplace.json");
            Path symlink = marketplaceRoot.resolve("plugins").resolve(pluginName);

            // Pre: drift the marketplace by deleting the per-plugin
            // symlink AND truncating the manifest. Both should be
            // restored by sync.
            if (Files.exists(symlink, LinkOption.NOFOLLOW_LINKS)) Files.delete(symlink);
            // Truncate manifest by writing a minimal stub that omits the plugin.
            String stubManifest = "{ \"name\": \"skill-manager\", \"plugins\": [] }\n";
            Files.writeString(manifest, stubManifest);

            boolean preSymlinkGone = !Files.exists(symlink, LinkOption.NOFOLLOW_LINKS);
            boolean preManifestStubbed = Files.readString(manifest).equals(stubManifest);

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "sync");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("CLAUDE_CONFIG_DIR",
                    Path.of(claudeHome).resolve(".claude").toString());

            ProcessRecord proc = Procs.run(ctx, "sync", pb);
            int rc = proc.exitCode();
            String body = readLog(ctx, "sync");

            // Post: marketplace symlink + manifest entry must be back.
            boolean symlinkRestored = Files.exists(symlink, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(symlink);
            String manifestAfter = Files.readString(manifest);
            boolean manifestRestored = manifestAfter.contains("\"" + pluginName + "\"")
                    && manifestAfter.contains("\"./plugins/" + pluginName + "\"");
            // Sync must have re-walked MCP deps too (parity with SkillSynced).
            boolean mcpReRegistered = body.contains("---MCP-INSTALL-RESULTS-BEGIN---")
                    && body.contains("---MCP-INSTALL-RESULTS-END---");

            boolean pass = preSymlinkGone && preManifestStubbed
                    && symlinkRestored && manifestRestored && mcpReRegistered;
            return (pass
                    ? NodeResult.pass("plugin.synced")
                    : NodeResult.fail("plugin.synced",
                            "rc=" + rc + " preSymlinkGone=" + preSymlinkGone
                                    + " preManifestStubbed=" + preManifestStubbed
                                    + " symlinkRestored=" + symlinkRestored
                                    + " manifestRestored=" + manifestRestored
                                    + " mcpReRegistered=" + mcpReRegistered))
                    .process(proc)
                    .assertion("marketplace_symlink_was_drifted", preSymlinkGone)
                    .assertion("marketplace_manifest_was_drifted", preManifestStubbed)
                    .assertion("marketplace_symlink_restored", symlinkRestored)
                    .assertion("marketplace_manifest_restored", manifestRestored)
                    .assertion("mcp_register_results_emitted", mcpReRegistered)
                    .metric("exitCode", rc);
        });
    }

    private static String readLog(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }
}
