///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * GOAL-checking-costs-what-changed clause (d), #402 first comment / #442: {@code sync
 * <unit>} re-projects 0 OTHER plugins into the agents.
 *
 * <p>Fixture: two plugins, with fake {@code claude} and {@code codex} first on PATH.
 * The fakes append their argv to a log and answer the marketplace list so the driver
 * reaches {@code install}. An argv naming the other plugin is one projection, whatever
 * its CLI cost would be; the number is per agent per verb and is the honest unit of
 * work #402 is about. Real agent CLIs are not run: the graph must not touch real
 * agent state and must not depend on their being installed.
 *
 * <p>CONTROLS: install projected both plugins through the fakes (the log sees them);
 * the sync projected the NAMED plugin; {@code sync} with no unit still projects both;
 * and the marketplace file still lists both. (CC-4 must keep the last two.)
 */
public class ForeignProjection {
    static final String ID = "cheap.foreign-projection";
    static final String TARGET = "cc-proj-target";
    static final String OTHER = "cc-proj-other";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "sync", "402", "442", "projection")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path source;
            Path clone;
            Path log;
            Path bin;
            Path targetDir;
            ProcessRecord installTarget;
            ProcessRecord installOther;
            ProcessRecord cloned;
            ProcessRecord sync;
            ProcessRecord syncAll;
            List<String> atInstall;
            List<String> atSync;
            List<String> atSyncAll;
            try {
                root = CheapChecksKit.scratch(ctx, "foreign-projection");
                source = root.resolve("source");
                clone = root.resolve("clone");
                log = root.resolve("agent-cli.log");
                bin = root.resolve("fake-bin");
                CheapChecksKit.writePolicy(source);
                CheapChecksKit.writeFakeAgents(bin, log, root.resolve("claude-marketplaces"));
                targetDir = root.resolve("units").resolve(TARGET);
                Path otherDir = root.resolve("units").resolve(OTHER);
                CheapChecksKit.writePlugin(targetDir, TARGET, "v1");
                CheapChecksKit.writePlugin(otherDir, OTHER, "v1");
                installTarget = CheapChecksKit.sm(ctx, "install_target", root, source, bin,
                        "install", targetDir.toString(), "--yes");
                installOther = CheapChecksKit.sm(ctx, "install_other", root, source, bin,
                        "install", otherDir.toString(), "--yes");
                atInstall = CheapChecksKit.lines(log);
                cloned = CheapChecksKit.cloneHome(ctx, "clone", root, source, clone);
                Files.writeString(targetDir.resolve("skills").resolve(TARGET + "-impl").resolve("SKILL.md"),
                        Files.readString(targetDir.resolve("skills").resolve(TARGET + "-impl").resolve("SKILL.md"))
                                + "\nsynced-marker\n");
                Files.deleteIfExists(log);
                sync = CheapChecksKit.sm(ctx, "sync_one", root, clone, bin,
                        "sync", TARGET, "--from", targetDir.toString(), "--yes", "--skip-mcp");
                atSync = CheapChecksKit.lines(log);
                Files.deleteIfExists(log);
                syncAll = CheapChecksKit.sm(ctx, "sync_all", root, clone, bin, "sync", "--yes", "--skip-mcp");
                atSyncAll = CheapChecksKit.lines(log);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            boolean setup = installTarget.exitCode() == 0 && installOther.exitCode() == 0 && cloned.exitCode() == 0;
            boolean controlInstallProjected = names(atInstall, TARGET) > 0 && names(atInstall, OTHER) > 0;
            boolean controlSyncLanded = sync.exitCode() == 0 && names(atSync, TARGET) > 0
                    && CheapChecksKit.read(clone.resolve("plugins").resolve(TARGET)
                            .resolve("skills").resolve(TARGET + "-impl").resolve("SKILL.md")).contains("synced-marker");
            boolean controlSyncAll = syncAll.exitCode() == 0 && names(atSyncAll, TARGET) > 0 && names(atSyncAll, OTHER) > 0;
            String market = CheapChecksKit.read(clone.resolve("plugin-marketplace/.claude-plugin/marketplace.json"));
            boolean controlMarketplaceListsBoth = market.contains(TARGET) && market.contains(OTHER);
            long otherProjections = names(atSync, OTHER);
            boolean shape = otherProjections == 0;
            String detail = "calls naming " + OTHER + " during sync of " + TARGET + "=" + otherProjections
                    + " agent-cli lines=" + atSync.size() + " sync rc=" + sync.exitCode()
                    + " syncAll rc=" + syncAll.exitCode() + " target calls=" + names(atSync, TARGET)
                    + " at install=" + atInstall.size() + " at syncAll=" + atSyncAll.size();
            NodeResult result = setup && controlInstallProjected && controlSyncLanded && controlSyncAll
                    && controlMarketplaceListsBoth && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(installTarget).process(installOther).process(sync).process(syncAll)
                    .metric("other_plugin_projection_calls", otherProjections)
                    .metric("agent_cli_calls_during_sync", atSync.size())
                    .assertion("setup_two_plugins_installed_and_cloned", setup)
                    .assertion("control_install_projected_both_through_the_fakes", controlInstallProjected)
                    .assertion("control_sync_projected_the_named_plugin", controlSyncLanded)
                    .assertion("control_sync_with_no_unit_projects_both", controlSyncAll)
                    .assertion("control_marketplace_still_lists_both", controlMarketplaceListsBoth)
                    .assertion("shape_no_other_plugin_reprojected", shape)
                    .log(detail + " | sync argvs: " + atSync);
        });
    }

    static long names(List<String> lines, String plugin) {
        return lines.stream().filter(l -> l.contains(plugin)).count();
    }
}
