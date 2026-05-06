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
 * Installs hello-plugin from the registry. Parallel to {@code HelloInstalled}
 * for skills, but verifies the plugin lands at {@code SKILL_MANAGER_HOME/plugins/}
 * (not {@code skills/}) — exercises the kind-aware
 * {@code CommitUnitsToStore} path from ticket 08, the lock-flip from
 * ticket 10, and the {@code ClaudeProjector} symlink from ticket 11.
 */
public class HelloPluginInstalled {
    static final NodeSpec SPEC = NodeSpec.of("hello.plugin.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hello.plugin.published")
            .tags("registry", "install", "plugin")
            .timeout("60s")
            .output("pluginDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || registryUrl == null) {
                return NodeResult.fail("hello.plugin.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "hello-plugin",
                    "--registry", registryUrl,
                    "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            // --yes works without policy gates as long as the plugin's
            // dep mix doesn't trigger any. hello-plugin's contained skill
            // declares pip:ruff (CLI), which gates by default — turn off
            // the cli flag for this run.
            pb.environment().put("SKILL_MANAGER_POLICY_INSTALL_REQUIRE_CONFIRMATION_FOR_CLI_DEPS", "false");

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            // Plugin lands under plugins/ (not skills/) — kind-aware
            // CommitUnitsToStore from ticket 08.
            Path pluginDir = Path.of(home).resolve("plugins/hello-plugin");
            boolean manifestOk = Files.isRegularFile(pluginDir.resolve(".claude-plugin/plugin.json"));
            boolean tomlOk = Files.isRegularFile(pluginDir.resolve("skill-manager-plugin.toml"));
            boolean containedOk = Files.isRegularFile(
                    pluginDir.resolve("skills/hello-impl/SKILL.md"));

            // Lock should now have a row for hello-plugin.
            Path lockPath = Path.of(home).resolve("units.lock.toml");
            boolean lockOk = false;
            if (Files.isRegularFile(lockPath)) {
                String body = Files.readString(lockPath);
                lockOk = body.contains("name = \"hello-plugin\"") && body.contains("kind = \"plugin\"");
            }

            // ClaudeProjector should have placed a plugins/ symlink in
            // the agent's home (ticket 11).
            Path claudePluginLink = Path.of(claudeHome).resolve(".claude/plugins/hello-plugin");
            boolean projOk = Files.exists(claudePluginLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(claudePluginLink);

            boolean pass = rc == 0 && manifestOk && tomlOk && containedOk && lockOk && projOk;
            NodeResult result = pass
                    ? NodeResult.pass("hello.plugin.installed")
                    : NodeResult.fail("hello.plugin.installed",
                            "rc=" + rc + " manifest=" + manifestOk + " toml=" + tomlOk
                                    + " contained=" + containedOk + " lock=" + lockOk
                                    + " projector=" + projOk);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("plugin_manifest_present", manifestOk)
                    .assertion("plugin_toml_present", tomlOk)
                    .assertion("contained_skill_present", containedOk)
                    .assertion("lock_advanced", lockOk)
                    .assertion("claude_projection_present", projOk)
                    .metric("exitCode", rc)
                    .publish("pluginDir", pluginDir.toString());
        });
    }
}
