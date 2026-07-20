package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.CliAgentContext;
import dev.skillmanager.cli.SkillManagerCli;
import picocli.CommandLine;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CliAgentContextTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("CliAgentContextTest");

        suite.test("agent context flag is inherited and command path resolves", () -> {
            CommandLine sync = new CommandLine(new SkillManagerCli());
            CommandLine.ParseResult syncParse = sync.parseArgs("sync", "--agent-context", "--help");
            SkillManagerCli syncRoot = (SkillManagerCli) syncParse.commandSpec().root().userObject();
            assertTrue(syncRoot.agentContext, "sync inherited --agent-context");
            assertEquals("sync", CliAgentContext.commandPath(syncParse), "sync command path");

            CommandLine env = new CommandLine(new SkillManagerCli());
            CommandLine.ParseResult envParse = env.parseArgs("env", "sync", "--agent-context", "--help");
            SkillManagerCli envRoot = (SkillManagerCli) envParse.commandSpec().root().userObject();
            assertTrue(envRoot.agentContext, "nested inherited --agent-context");
            assertEquals("env sync", CliAgentContext.commandPath(envParse), "nested command path");
        });

        suite.test("renderer covers representative agent workflows", () -> {
            assertContext("install", "install-local-unit", "skill-manager show <unit>");
            assertContext("sync", "sync-one-unit", "skill-manager bindings list");
            assertContext("bind", "bind-projection", "skill-manager unbind <binding-id>");
            assertContext("env sync", "project-env", "skill-manager env run <env> -- <cmd>");
            assertContext("harness instantiate", "harness-instantiate", "skill-manager harness list");
            assertContext("publish", "publish-unit", "skill-manager search <unit>");
        });

        suite.test("renderer exposes only a valid lowercase trace handle", () -> {
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            String rendered = CliAgentContext.render("install", 0, traceId);
            assertContains(rendered, "trace_id: " + traceId,
                    "valid trace id is agent-visible");

            String invalid = CliAgentContext.render(
                    "install", 0, "00000000000000000000000000000000");
            assertTrue(!invalid.contains("trace_id:"),
                    "all-zero trace id is omitted");

            String uppercase = CliAgentContext.render(
                    "install", 0, traceId.toUpperCase());
            assertTrue(!uppercase.contains("trace_id:"),
                    "uppercase trace id is omitted");
        });

        return suite.runAll();
    }

    private static void assertContext(String commandPath, String workflowId, String nextCommand) {
        String rendered = CliAgentContext.render(commandPath, 0);
        assertContains(rendered, CliAgentContext.BEGIN, commandPath + " begin");
        assertContains(rendered, "command_path: " + commandPath, commandPath + " path");
        assertContains(rendered, "status: success", commandPath + " status");
        assertContains(rendered, "workflow_state: command_completed", commandPath + " state");
        assertContains(rendered, workflowId, commandPath + " workflow");
        assertContains(rendered, nextCommand, commandPath + " next command");
        assertContains(rendered, "$SKILL_MANAGER_HOME/logs", commandPath + " logs");
        assertContains(rendered, CliAgentContext.END, commandPath + " end");
    }
}
