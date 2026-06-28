package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.CliMetadata;
import dev.skillmanager.cli.SkillManagerCli;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CliMetadataTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliMetadataTest");

        suite.test("metadata command catalog matches picocli command tree", () -> {
            CommandTree tree = commandTree();
            assertEquals(tree.commandPaths(), CliMetadata.commandPaths(), "command paths");
            assertEquals(tree.aliasesByPath(), CliMetadata.aliasesByCommandPath(), "command aliases");
        });

        suite.test("workflow metadata links to commands and docs", () -> {
            Set<String> commandPaths = CliMetadata.commandPaths();
            Set<String> workflowIds = CliMetadata.workflowIds();
            Map<String, String> links = CliMetadata.workflowCommandLinks();

            assertEquals(workflowIds, links.keySet(), "workflow link ids");
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                assertTrue(commandPaths.contains(workflow.commandPath()),
                        "workflow command exists: " + workflow.id());
                assertTrue(!workflow.examples().isEmpty(), "workflow has examples: " + workflow.id());
                assertTrue(!workflow.relatedSkillDocs().isEmpty(), "workflow has docs: " + workflow.id());
                assertTrue(workflow.agentContextAvailable(),
                        "workflow has agent context affordance: " + workflow.id());
            }
        });

        suite.test("workflow examples parse against picocli command tree", () -> {
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                for (String example : workflow.examples()) {
                    String[] parts = example.split("\\s+");
                    assertTrue(parts.length > 1, "example has command args: " + example);
                    assertEquals("skill-manager", parts[0], "example root: " + example);
                    new CommandLine(new SkillManagerCli())
                            .parseArgs(Arrays.copyOfRange(parts, 1, parts.length));
                }
            }
        });

        suite.test("lockfile workflow examples point at distinct sync modes", () -> {
            Map<String, CliMetadata.WorkflowMetadata> byId = new LinkedHashMap<>();
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                byId.put(workflow.id(), workflow);
            }

            assertEquals(Set.of("skill-manager sync --refresh"),
                    Set.copyOf(byId.get("refresh-lockfile").examples()),
                    "refresh lockfile example");
            assertEquals(Set.of("skill-manager sync --lock units.lock.toml"),
                    Set.copyOf(byId.get("sync-lockfile").examples()),
                    "sync from lockfile example");
        });

        suite.test("representative modeled workflows are present", () -> {
            assertTrue(CliMetadata.commandPaths().contains("bindings show"),
                    "bindings show in metadata");
            assertEquals(Set.of("ls"), CliMetadata.aliasesByCommandPath().get("list"),
                    "list alias");
            for (String workflow : Set.of(
                    "install-local-unit",
                    "skill-scripts",
                    "project-env",
                    "sync-one-unit",
                    "project-profile-resolve")) {
                assertTrue(CliMetadata.workflowIds().contains(workflow),
                        "workflow present: " + workflow);
            }
        });

        return suite.runAll();
    }

    private static CommandTree commandTree() {
        CommandLine root = new CommandLine(new SkillManagerCli());
        Set<String> commandPaths = new LinkedHashSet<>();
        Map<String, Set<String>> aliasesByPath = new LinkedHashMap<>();
        Set<CommandLine> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(root, root.getCommandName(), commandPaths, aliasesByPath, seen);
        return new CommandTree(Set.copyOf(commandPaths), Map.copyOf(aliasesByPath));
    }

    private static void collect(CommandLine commandLine, String path, Set<String> commandPaths,
                                Map<String, Set<String>> aliasesByPath, Set<CommandLine> seen) {
        if (!seen.add(commandLine)) return;
        commandPaths.add(path);

        Set<String> aliases = new LinkedHashSet<>(
                Arrays.asList(commandLine.getCommandSpec().aliases()));
        if (!aliases.isEmpty()) aliasesByPath.put(path, Set.copyOf(aliases));

        for (CommandLine child : commandLine.getSubcommands().values()) {
            String childPath = path.equals("skill-manager")
                    ? child.getCommandName()
                    : path + " " + child.getCommandName();
            collect(child, childPath, commandPaths, aliasesByPath, seen);
        }
    }

    private record CommandTree(Set<String> commandPaths, Map<String, Set<String>> aliasesByPath) {}
}
