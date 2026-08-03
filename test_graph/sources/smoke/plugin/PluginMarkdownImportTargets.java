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
 * and the violation is reported.
 *
 * <h2>The exit code is part of the report</h2>
 *
 * <p>This node used to assert every install exited 0, including the one that
 * printed a violation — which was encoding the defect
 * {@code MarkdownImportValidator.EXIT_CODE} exists to close: the block lands
 * after the success banner, so a caller reading the tail or {@code $?}
 * concluded success. It now pins the contract that replaced it: installs whose
 * markdown references resolve exit 0, the one that reports a violation exits
 * {@code 11}, and the unit is committed either way.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>The three target installs above — same node, same home, same binary —
 * must exit 0. Without them "exits 11" would also hold for a build that failed
 * every install, and {@code noPathFailures} would hold over an empty log.
 */
public class PluginMarkdownImportTargets {
    static final NodeSpec SPEC = NodeSpec.of("plugin.markdown.import.targets")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gateway.up")
            .tags("plugin", "markdown-imports", "doc-repo", "harness")
            .timeout("120s");

    /** {@code dev.skillmanager.validation.MarkdownImportValidator.EXIT_CODE}. */
    private static final int MARKDOWN_IMPORT_VIOLATION_EXIT = 11;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) {
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
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        doc, "install-doc"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        harness, "install-harness"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
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
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        source, "install-source-plugin"));
            } catch (Exception e) {
                return NodeResult.error("plugin.markdown.import.targets", e);
            }

            ProcessRecord sourceProc = procs.get(procs.size() - 1);
            List<ProcessRecord> targets = procs.subList(0, procs.size() - 1);
            boolean targetsExitZero = targets.stream().allMatch(p -> p.exitCode() == 0);
            boolean sourceExitsViolationCode =
                    sourceProc.exitCode() == MARKDOWN_IMPORT_VIOLATION_EXIT;
            String log = MarkdownImportFixture.logBody(ctx, sourceProc);
            boolean renderedMissingPlugin = log.contains("markdown skill-import violations (1)")
                    && log.contains("pm-source-plugin (plugin)")
                    && log.contains("references missing unit `pm-missing-plugin`");
            boolean noPathFailures = !log.contains("references missing path");
            // 11 reports the reference; it does not abandon the install.
            boolean sourceCommitted = Files.isDirectory(Path.of(home, "plugins", "pm-source-plugin"));
            boolean pass = targetsExitZero && sourceExitsViolationCode && renderedMissingPlugin
                    && sourceCommitted && noPathFailures;
            NodeResult result = pass
                    ? NodeResult.pass("plugin.markdown.import.targets")
                    : NodeResult.fail("plugin.markdown.import.targets",
                            "targetsExitZero=" + targetsExitZero
                                    + " sourceRc=" + sourceProc.exitCode()
                                    + " renderedMissingPlugin=" + renderedMissingPlugin
                                    + " sourceCommitted=" + sourceCommitted
                                    + " noPathFailures=" + noPathFailures);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    .assertion("resolved_import_installs_exit_zero", targetsExitZero)
                    .assertion("unresolved_import_install_exits_eleven", sourceExitsViolationCode)
                    .assertion("missing_plugin_violation_rendered", renderedMissingPlugin)
                    .assertion("unit_still_committed_under_violation_exit", sourceCommitted)
                    .assertion("installed_doc_harness_plugin_imports_resolved", noPathFailures)
                    .metric("sourceExitCode", sourceProc.exitCode());
        });
    }
}
