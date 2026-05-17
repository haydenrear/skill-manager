///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../lib/MarkdownImportFixture.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugin-level markdown can import files from doc-repos, harnesses,
 * and other plugins. The final plugin intentionally also imports a
 * missing plugin target so the graph proves plugin markdown is parsed
 * and advisory violations are rendered.
 */
public class PluginMarkdownImportTargets {
    static final NodeSpec SPEC = NodeSpec.of("plugin.markdown.import.targets")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gateway.up")
            .tags("plugin", "markdown-imports", "doc-repo", "harness")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("plugin.markdown.import.targets",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path root = Path.of(home, "plugin-markdown-import-fixtures");
            List<ProcessRecord> procs = new ArrayList<>();
            try {
                Files.createDirectories(root);
                Path doc = MarkdownImportFixture.doc(root, "pm-target-doc", "claude-md/reference.md");
                Path harness = MarkdownImportFixture.harness(root, "pm-target-harness", "reference.md");
                Path plugin = MarkdownImportFixture.plugin(root, "pm-target-plugin", "docs/reference.md");
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        doc, "install-doc"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        harness, "install-harness"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        plugin, "install-target-plugin"));

                Path source = MarkdownImportFixture.pluginWithReadme(root, "pm-source-plugin",
                        MarkdownImportFixture.imports(
                                MarkdownImportFixture.entry("pm-target-doc", "claude-md/reference.md",
                                        "Plugin smoke validates imports can target doc-repos."),
                                MarkdownImportFixture.entry("pm-target-harness", "reference.md",
                                        "Plugin smoke validates imports can target harnesses."),
                                MarkdownImportFixture.entry("pm-target-plugin", "docs/reference.md",
                                        "Plugin smoke validates imports can target other plugins."),
                                MarkdownImportFixture.entry("pm-missing-plugin", "docs/reference.md",
                                        "Plugin smoke keeps one missing plugin import as a parsing sentinel.")));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        source, "install-source-plugin"));
            } catch (Exception e) {
                return NodeResult.error("plugin.markdown.import.targets", e);
            }

            boolean allExitZero = procs.stream().allMatch(p -> p.exitCode() == 0);
            ProcessRecord sourceProc = procs.get(procs.size() - 1);
            String log = MarkdownImportFixture.logBody(ctx, sourceProc);
            boolean renderedMissingPlugin = log.contains("markdown skill-import violations (1)")
                    && log.contains("pm-source-plugin (plugin)")
                    && log.contains("references missing unit `pm-missing-plugin`");
            boolean noPathFailures = !log.contains("references missing path");
            boolean pass = allExitZero && renderedMissingPlugin && noPathFailures;
            NodeResult result = pass
                    ? NodeResult.pass("plugin.markdown.import.targets")
                    : NodeResult.fail("plugin.markdown.import.targets",
                            "allExitZero=" + allExitZero
                                    + " renderedMissingPlugin=" + renderedMissingPlugin
                                    + " noPathFailures=" + noPathFailures);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    .assertion("all_installs_exit_zero", allExitZero)
                    .assertion("missing_plugin_violation_rendered", renderedMissingPlugin)
                    .assertion("installed_doc_harness_plugin_imports_resolved", noPathFailures);
        });
    }
}
