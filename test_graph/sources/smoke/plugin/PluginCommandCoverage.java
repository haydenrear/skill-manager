///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression coverage for kind-aware CLI commands while an installed
 * plugin is still live: list, show, deps, lock status, and upgrade.
 */
public class PluginCommandCoverage {
    static final NodeSpec SPEC = NodeSpec.of("plugin.command.coverage")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("plugin.synced", "partner.skill.installed")
            .tags("plugin", "commands", "list", "show", "deps", "lock", "upgrade")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String pluginName = ctx.get("umbrella.plugin.installed", "pluginName").orElse(null);
            if (home == null || pluginName == null) {
                return NodeResult.fail("plugin.command.coverage", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessRecord list = run(ctx, "list", home, repoRoot, sm, "list");
            String listBody = readLog(ctx, "list");
            boolean listShowsPlugin = list.exitCode() == 0
                    && listBody.contains("BINDINGS")
                    && listHasRow(listBody, pluginName, "plugin", null);

            ProcessRecord show = run(ctx, "show", home, repoRoot, sm, "show", pluginName);
            String showBody = readLog(ctx, "show");
            boolean showDisplaysPlugin = show.exitCode() == 0
                    && showBody.contains("PLUGIN  " + pluginName + "@")
                    && showBody.contains("contained skills")
                    && showBody.contains("effective dependencies");

            ProcessRecord deps = run(ctx, "deps", home, repoRoot, sm,
                    "deps", pluginName, "--cli", "--mcp");
            String depsBody = readLog(ctx, "deps");
            boolean depsDisplaysPlugin = deps.exitCode() == 0
                    && depsBody.contains(pluginName + " (plugin)")
                    && depsBody.contains("[cli] pycowsay")
                    && depsBody.contains("[mcp] umbrella-plugin-server");

            ProcessRecord refresh = run(ctx, "sync-refresh", home, repoRoot, sm,
                    "sync", "--refresh");

            ProcessRecord lock = run(ctx, "lock-status", home, repoRoot, sm,
                    "lock", "status");
            String lockBody = readLog(ctx, "lock-status");
            boolean lockIncludesPlugin = refresh.exitCode() == 0
                    && lock.exitCode() == 0
                    && lockBody.contains("units.lock.toml is in sync");

            ProcessRecord upgrade = run(ctx, "upgrade", home, repoRoot, sm,
                    "upgrade", pluginName);
            String upgradeBody = readLog(ctx, "upgrade");
            boolean upgradeSeesPlugin = upgrade.exitCode() == 5
                    && upgradeBody.contains(pluginName + ": not git-tracked");

            boolean pass = listShowsPlugin && showDisplaysPlugin && depsDisplaysPlugin
                    && lockIncludesPlugin && upgradeSeesPlugin;
            NodeResult result = pass
                    ? NodeResult.pass("plugin.command.coverage")
                    : NodeResult.fail("plugin.command.coverage",
                            "list=" + listShowsPlugin
                                    + " show=" + showDisplaysPlugin
                                    + " deps=" + depsDisplaysPlugin
                                    + " lock=" + lockIncludesPlugin
                                    + " upgrade=" + upgradeSeesPlugin);
            return result
                    .process(list)
                    .process(show)
                    .process(deps)
                    .process(refresh)
                    .process(lock)
                    .process(upgrade)
                    .assertion("list_shows_plugin_kind_and_bindings_column", listShowsPlugin)
                    .assertion("show_displays_plugin", showDisplaysPlugin)
                    .assertion("deps_displays_plugin", depsDisplaysPlugin)
                    .assertion("lock_status_accounts_for_plugin", lockIncludesPlugin)
                    .assertion("upgrade_resolves_plugin_target", upgradeSeesPlugin)
                    .metric("listExitCode", list.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("depsExitCode", deps.exitCode())
                    .metric("refreshExitCode", refresh.exitCode())
                    .metric("lockExitCode", lock.exitCode())
                    .metric("upgradeExitCode", upgrade.exitCode());
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean listHasRow(String body, String name, String kind, String bindings) {
        for (String line : body.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 4 && parts[0].equals(name) && parts[1].equals(kind)) {
                return bindings == null || parts[3].equals(bindings);
            }
        }
        return false;
    }
}
