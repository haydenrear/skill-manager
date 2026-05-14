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
 * Regression coverage for kind-aware CLI commands while a harness
 * template is installed and one instance is still materialized.
 */
public class HarnessCommandCoverage {
    static final NodeSpec SPEC = NodeSpec.of("harness.command.coverage")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.instance.materialized")
            .tags("harness", "commands", "list", "show", "deps", "lock", "upgrade")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String harnessName = ctx.get("harness.transitive.installed", "harnessName").orElse(null);
            if (home == null || harnessName == null) {
                return NodeResult.fail("harness.command.coverage", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessRecord list = run(ctx, "list", home, repoRoot, sm, "list");
            String listBody = readLog(ctx, "list");
            boolean listShowsHarness = list.exitCode() == 0
                    && listBody.contains("BINDINGS")
                    && listHasRow(listBody, harnessName, "harness", null)
                    && listHasRow(listBody, "hello-doc-repo", "doc", "2")
                    && listHasRow(listBody, "hello-plugin", "plugin", null);

            ProcessRecord show = run(ctx, "show", home, repoRoot, sm, "show", harnessName);
            String showBody = readLog(ctx, "show");
            boolean showDisplaysHarness = show.exitCode() == 0
                    && showBody.contains("HARNESS  " + harnessName + "@")
                    && showBody.contains("units:")
                    && showBody.contains("docs:");

            ProcessRecord deps = run(ctx, "deps", home, repoRoot, sm, "deps", harnessName);
            String depsBody = readLog(ctx, "deps");
            boolean depsDisplaysHarness = deps.exitCode() == 0
                    && depsBody.contains(harnessName + " (harness)")
                    && depsBody.contains("hello-plugin (plugin)")
                    && depsBody.contains("hello-doc-repo (doc)");

            ProcessRecord refresh = run(ctx, "sync-refresh", home, repoRoot, sm,
                    "sync", "--refresh");

            ProcessRecord lock = run(ctx, "lock-status", home, repoRoot, sm,
                    "lock", "status");
            String lockBody = readLog(ctx, "lock-status");
            boolean lockIncludesHarness = refresh.exitCode() == 0
                    && lock.exitCode() == 0
                    && lockBody.contains("units.lock.toml is in sync");

            ProcessRecord upgrade = run(ctx, "upgrade", home, repoRoot, sm,
                    "upgrade", harnessName);
            String upgradeBody = readLog(ctx, "upgrade");
            boolean upgradeRejectsHarness = upgrade.exitCode() == 5
                    && upgradeBody.contains("harness units do not support `upgrade`");

            boolean pass = listShowsHarness && showDisplaysHarness && depsDisplaysHarness
                    && lockIncludesHarness && upgradeRejectsHarness;
            NodeResult result = pass
                    ? NodeResult.pass("harness.command.coverage")
                    : NodeResult.fail("harness.command.coverage",
                            "list=" + listShowsHarness
                                    + " show=" + showDisplaysHarness
                                    + " deps=" + depsDisplaysHarness
                                    + " lock=" + lockIncludesHarness
                                    + " upgrade=" + upgradeRejectsHarness);
            return result
                    .process(list)
                    .process(show)
                    .process(deps)
                    .process(refresh)
                    .process(lock)
                    .process(upgrade)
                    .assertion("list_shows_harness_and_transitive_unit_kinds", listShowsHarness)
                    .assertion("show_displays_harness", showDisplaysHarness)
                    .assertion("deps_displays_harness_refs", depsDisplaysHarness)
                    .assertion("lock_status_accounts_for_harness", lockIncludesHarness)
                    .assertion("upgrade_rejects_harness_with_sync_hint", upgradeRejectsHarness)
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
