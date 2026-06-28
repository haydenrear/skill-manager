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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * End-to-end docs coverage for CLI progressive-disclosure workflows.
 */
public class CliSkillDocsCatalogCovered {
    static final NodeSpec SPEC = NodeSpec.of("cli.skill-docs.catalog.covered")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "cli.metadata.catalog.covered")
            .tags("cli", "docs", "skills", "progressive-disclosure")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Map<String, String> docsBySurface = new LinkedHashMap<>();
            docsBySurface.put("skill-manager-skill", markdownUnder(repoRoot.resolve("skill-manager-skill")));
            docsBySurface.put("skill-publisher-skill", markdownUnder(repoRoot.resolve("skill-publisher-skill")));
            docsBySurface.put("skill-dev-skill", markdownUnder(repoRoot.resolve("skill-dev-skill")));

            List<String> missingWorkflowDocs = new ArrayList<>();
            List<String> missingHelpRoutes = new ArrayList<>();
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                String helpCommand = helpCommand(workflow.commandPath());
                for (String surface : workflow.relatedSkillDocs()) {
                    String docs = docsBySurface.getOrDefault(surface, "");
                    String key = surface + ":" + workflow.id();
                    if (!docs.contains(workflow.id())) {
                        missingWorkflowDocs.add(key);
                    }
                    if (!docs.contains(helpCommand)) {
                        missingHelpRoutes.add(key + ":" + helpCommand);
                    }
                }
            }

            boolean workflowDocsCovered = missingWorkflowDocs.isEmpty();
            boolean helpRoutesCovered = missingHelpRoutes.isEmpty();
            boolean pass = workflowDocsCovered && helpRoutesCovered;
            return (pass
                    ? NodeResult.pass("cli.skill-docs.catalog.covered")
                    : NodeResult.fail("cli.skill-docs.catalog.covered",
                            "missingWorkflowDocs=" + missingWorkflowDocs
                                    + " missingHelpRoutes=" + missingHelpRoutes))
                    .assertion("workflow_ids_documented", workflowDocsCovered)
                    .assertion("workflow_help_routes_documented", helpRoutesCovered)
                    .metric("workflowIds", CliMetadata.workflowIds().size());
        });
    }

    private static String markdownUnder(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String helpCommand(String commandPath) {
        if ("skill-manager".equals(commandPath)) {
            return "skill-manager --help";
        }
        return "skill-manager " + commandPath + " --help";
    }
}
