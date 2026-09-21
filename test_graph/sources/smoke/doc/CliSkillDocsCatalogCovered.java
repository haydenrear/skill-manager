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
            // SI-18: only the surfaces this repository carries on disk are
            // readable here. `unit-authoring` moved into the tla-spec-dev
            // plugin, in another repository, and the skill-publisher-skill/
            // tree this node used to read it from is gone. Resolving it to an
            // empty string — which `getOrDefault(surface, "")` used to do the
            // moment the directory vanished — would have reported every one of
            // its workflows as undocumented, so the branch is explicit now.
            Map<String, String> docsBySurface = new LinkedHashMap<>();
            for (String surface : CliMetadata.inTreeDocSurfaces()) {
                docsBySurface.put(surface, markdownUnder(repoRoot.resolve(surface)));
            }

            List<String> missingWorkflowDocs = new ArrayList<>();
            List<String> missingHelpRoutes = new ArrayList<>();
            List<String> unknownSurfaces = new ArrayList<>();
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                String helpCommand = helpCommand(workflow.commandPath());
                for (String surface : workflow.relatedSkillDocs()) {
                    String key = surface + ":" + workflow.id();
                    String docs = docsBySurface.get(surface);
                    if (docs == null) {
                        // External, and checked by the assertion below instead
                        // of by reading bytes this repository does not have.
                        if (!CliMetadata.UNIT_AUTHORING_DOCS.equals(surface)) {
                            unknownSurfaces.add(key);
                        }
                        continue;
                    }
                    if (!docs.contains(workflow.id())) {
                        missingWorkflowDocs.add(key);
                    }
                    if (!docs.contains(helpCommand)) {
                        missingHelpRoutes.add(key + ":" + helpCommand);
                    }
                }
            }

            // The non-vacuity guard. A surface that cannot be read is skipped,
            // so something has to pin WHICH workflows get skipped — otherwise
            // pointing a workflow at the external surface silently removes it
            // from coverage and this node still goes green.
            List<String> expectedExternal = List.of(
                    "author-dependencies", "author-unit", "install-local-unit",
                    "publish-unit", "skill-scripts");
            List<String> actualExternal =
                    new ArrayList<>(CliMetadata.workflowsWithExternalDocs());
            boolean externalSetPinned = actualExternal.equals(expectedExternal);
            boolean surfacesKnown = unknownSurfaces.isEmpty();

            boolean workflowDocsCovered = missingWorkflowDocs.isEmpty();
            boolean helpRoutesCovered = missingHelpRoutes.isEmpty();
            boolean pass = workflowDocsCovered && helpRoutesCovered
                    && externalSetPinned && surfacesKnown;
            return (pass
                    ? NodeResult.pass("cli.skill-docs.catalog.covered")
                    : NodeResult.fail("cli.skill-docs.catalog.covered",
                            "missingWorkflowDocs=" + missingWorkflowDocs
                                    + " missingHelpRoutes=" + missingHelpRoutes
                                    + " unknownSurfaces=" + unknownSurfaces
                                    + " externalWorkflows=" + actualExternal
                                    + " expected=" + expectedExternal))
                    .assertion("workflow_ids_documented", workflowDocsCovered)
                    .assertion("workflow_help_routes_documented", helpRoutesCovered)
                    .assertion("doc_surfaces_known", surfacesKnown)
                    .assertion("external_doc_workflows_pinned", externalSetPinned)
                    .metric("workflowIds", CliMetadata.workflowIds().size())
                    .metric("externalDocWorkflows", actualExternal.size());
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
