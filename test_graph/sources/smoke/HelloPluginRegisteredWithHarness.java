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
 * Asserts the harness CLIs see {@code hello-plugin} after install.
 *
 * <p>Each harness branches on whether its CLI is on PATH:
 * <ul>
 *   <li>{@code claude} on PATH → {@code claude plugin list} must show
 *       {@code hello-plugin@skill-manager} (full lifecycle drove it).</li>
 *   <li>{@code claude} missing → the plugin's installed-record must
 *       carry the {@code HARNESS_CLI_UNAVAILABLE} error.</li>
 *   <li>{@code codex} on PATH → {@code codex plugin marketplace
 *       upgrade skill-manager} must succeed (proves the marketplace
 *       was added).</li>
 *   <li>{@code codex} missing → similar to claude, error recorded.</li>
 * </ul>
 *
 * <p>Subprocesses set {@code CLAUDE_CONFIG_DIR=<claudeHome>/.claude}
 * and {@code CODEX_HOME=<codexHome>} so the CLIs read/write the
 * test's sandboxed harness home rather than the developer's real one.
 */
public class HelloPluginRegisteredWithHarness {
    static final NodeSpec SPEC = NodeSpec.of("hello.plugin.registered.with.harness")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.plugin.installed")
            .tags("plugin", "harness", "cli")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("hello.plugin.registered.with.harness", "missing upstream context");
            }

            NodeResult result = NodeResult.pass("hello.plugin.registered.with.harness");

            // Claude branch.
            boolean claudeOnPath = onPath("claude");
            if (claudeOnPath) {
                ProcessBuilder pb = new ProcessBuilder("claude", "plugin", "list");
                pb.environment().put("CLAUDE_CONFIG_DIR",
                        Path.of(claudeHome).resolve(".claude").toString());
                ProcessRecord proc = Procs.run(ctx, "claude-plugin-list", pb);
                int rc = proc.exitCode();
                String out = readLog(ctx, "claude-plugin-list");
                boolean claudeShowsHelloPlugin = rc == 0
                        && out.contains("hello-plugin")
                        && out.contains("skill-manager");
                result.process(proc)
                        .assertion("claude_plugin_list_ok", rc == 0)
                        .assertion("claude_lists_hello_plugin_from_skill_manager_marketplace",
                                claudeShowsHelloPlugin);
            } else {
                Path installedJson = Path.of(home).resolve("installed/hello-plugin.json");
                boolean errorRecorded = false;
                try {
                    errorRecorded = Files.isRegularFile(installedJson)
                            && Files.readString(installedJson).contains("HARNESS_CLI_UNAVAILABLE");
                } catch (Exception ignored) { }
                result.assertion("missing_claude_recorded_as_error", errorRecorded);
            }

            // Codex branch — codex's `marketplace upgrade <name>` only
            // operates on git-backed sources, so we can't use it as a
            // verification verb for our local-path marketplace. Instead
            // we look at codex's config.toml directly: a successful
            // `marketplace add` writes a {@code [marketplaces.skill-manager]}
            // section pointing at the marketplace root. If that
            // section is present, the install flow's
            // RefreshHarnessPlugins effect did its job.
            boolean codexOnPath = onPath("codex");
            if (codexOnPath) {
                Path codexConfig = Path.of(codexHome).resolve("config.toml");
                String body = "";
                try {
                    body = Files.readString(codexConfig);
                } catch (Exception ignored) { }
                boolean marketplaceRegistered = body.contains("[marketplaces.skill-manager]")
                        && body.contains("source_type = \"local\"");
                result.assertion("codex_config_lists_skill_manager_marketplace_local",
                        marketplaceRegistered);
            } else {
                Path installedJson = Path.of(home).resolve("installed/hello-plugin.json");
                boolean errorRecorded = false;
                try {
                    errorRecorded = Files.isRegularFile(installedJson)
                            && Files.readString(installedJson).contains("HARNESS_CLI_UNAVAILABLE");
                } catch (Exception ignored) { }
                result.assertion("missing_codex_recorded_as_error", errorRecorded);
            }

            return result;
        });
    }

    private static boolean onPath(String bin) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) continue;
            if (Files.isExecutable(Path.of(part, bin))) return true;
        }
        return false;
    }

    private static String readLog(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }
}
