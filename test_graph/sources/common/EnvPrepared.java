///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the shared per-run state for the integration graph:
 *
 *   - A fresh {@code SKILL_MANAGER_HOME} so no pre-existing local state
 *     (skills/, bin/, gateway pid files) interferes with the run.
 *   - Sandboxed {@code CLAUDE_HOME} / {@code CODEX_HOME} so install-time
 *     symlinks (under {@code .claude/skills}, {@code .codex/skills}) and
 *     MCP-config writes (under {@code .claude.json},
 *     {@code .codex/config.toml}) land inside the temp home rather than
 *     polluting the developer's real {@code ~/}.
 *   - Two free TCP ports: one for the Java skill registry server, one
 *     for the virtual MCP gateway.
 *
 * Downstream nodes pick these up via ctx.get("env.prepared", ...).
 */
public class EnvPrepared {
    static final NodeSpec SPEC = NodeSpec.of("env.prepared")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("env")
            .output("home", "string")
            .output("claudeHome", "string")
            .output("codexHome", "string")
            .output("registryPort", "integer")
            .output("gatewayPort", "integer");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path home = Files.createTempDirectory("sm-testgraph-");
            Files.createDirectories(home.resolve("test-graph"));

            // Match OnboardCompleted's layout: ClaudeAgent treats $CLAUDE_HOME
            // as the parent of {.claude/, .claude.json}; CodexAgent treats
            // $CODEX_HOME as the dir holding {skills/, config.toml}. So
            // claudeHome=$home/agent-home and codexHome=$home/agent-home/.codex
            // gives:
            //   $home/agent-home/.claude/skills/<name>
            //   $home/agent-home/.codex/skills/<name>
            Path agentHome = home.resolve("agent-home");
            Path codexHome = agentHome.resolve(".codex");
            Files.createDirectories(agentHome);
            Files.createDirectories(codexHome);

            // Test environments are explicitly permissive: drop a
            // policy.toml that turns off every install-confirmation gate
            // so unattended `skill-manager install --yes` succeeds even
            // for fixtures that declare hooks (echo-stdio uses a shell
            // load, which trips ! HOOKS by default). The skill-manager
            // CLI's writeDefaultIfMissing skips when this file exists,
            // so what we write here wins for the rest of the run.
            Files.writeString(home.resolve("policy.toml"), """
                    # Test-graph permissive policy. Production defaults gate hooks
                    # + executable commands; tests run unattended so we relax all
                    # install gates here. Production users keep the strict defaults.
                    require_confirmation = false
                    [install]
                    require_confirmation_for_hooks = false
                    require_confirmation_for_mcp = false
                    require_confirmation_for_cli_deps = false
                    require_confirmation_for_executable_commands = false
                    """);

            int registryPort = freePort();
            int gatewayPort = freePort();

            return NodeResult.pass("env.prepared")
                    .assertion("home_created", Files.isDirectory(home))
                    .assertion("agent_home_created", Files.isDirectory(agentHome))
                    .assertion("codex_home_created", Files.isDirectory(codexHome))
                    .assertion("ports_allocated", registryPort > 0 && gatewayPort > 0)
                    .metric("registryPort", registryPort)
                    .metric("gatewayPort", gatewayPort)
                    .publish("home", home.toString())
                    .publish("claudeHome", agentHome.toString())
                    .publish("codexHome", codexHome.toString())
                    .publish("registryPort", Integer.toString(registryPort))
                    .publish("gatewayPort", Integer.toString(gatewayPort));
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
