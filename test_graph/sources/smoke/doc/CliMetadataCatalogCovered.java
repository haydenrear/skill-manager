///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../../../src/main/java/**/*.java
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showThreadName=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showDateTime=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.levelInBrackets=true
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.3
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2
//DEPS org.tomlj:tomlj:1.1.1
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.1
//DEPS org.slf4j:slf4j-simple:2.0.16

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

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

/**
 * End-to-end catalog check for the progressive-disclosure metadata source.
 * This compiles production CLI sources and compares the metadata catalog with
 * the actual picocli command tree.
 */
public class CliMetadataCatalogCovered {
    static final NodeSpec SPEC = NodeSpec.of("cli.metadata.catalog.covered")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cli", "metadata", "progressive-disclosure")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            CommandTree tree = commandTree();
            boolean pathsMatch = tree.commandPaths().equals(CliMetadata.commandPaths());
            boolean aliasesMatch = tree.aliasesByPath().equals(CliMetadata.aliasesByCommandPath());
            boolean workflowsHaveCommands = CliMetadata.workflowCommandLinks().values().stream()
                    .allMatch(CliMetadata.commandPaths()::contains);
            boolean workflowsHaveExamples = CliMetadata.workflows().stream()
                    .allMatch(workflow -> !workflow.examples().isEmpty());
            boolean workflowsHaveDocs = CliMetadata.workflows().stream()
                    .allMatch(workflow -> !workflow.relatedSkillDocs().isEmpty());
            boolean workflowsHaveContext = CliMetadata.workflows().stream()
                    .allMatch(CliMetadata.WorkflowMetadata::agentContextAvailable);

            Set<String> missingFromMetadata = new LinkedHashSet<>(tree.commandPaths());
            missingFromMetadata.removeAll(CliMetadata.commandPaths());
            Set<String> staleInMetadata = new LinkedHashSet<>(CliMetadata.commandPaths());
            staleInMetadata.removeAll(tree.commandPaths());

            boolean pass = pathsMatch && aliasesMatch && workflowsHaveCommands
                    && workflowsHaveExamples && workflowsHaveDocs && workflowsHaveContext;
            return (pass
                    ? NodeResult.pass("cli.metadata.catalog.covered")
                    : NodeResult.fail("cli.metadata.catalog.covered",
                            "pathsMatch=" + pathsMatch
                                    + " aliasesMatch=" + aliasesMatch
                                    + " missing=" + missingFromMetadata
                                    + " stale=" + staleInMetadata))
                    .assertion("command_paths_match_picocli", pathsMatch)
                    .assertion("aliases_match_picocli", aliasesMatch)
                    .assertion("workflow_commands_exist", workflowsHaveCommands)
                    .assertion("workflow_examples_exist", workflowsHaveExamples)
                    .assertion("workflow_docs_exist", workflowsHaveDocs)
                    .assertion("workflow_context_affordances_exist", workflowsHaveContext)
                    .metric("commandPaths", CliMetadata.commandPaths().size())
                    .metric("workflowIds", CliMetadata.workflowIds().size());
        });
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
