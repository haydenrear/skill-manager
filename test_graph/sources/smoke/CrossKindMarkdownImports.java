///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/MarkdownImportFixture.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that skill markdown imports can target non-skill units:
 * plugin, doc-repo, and harness. This is a successful validation path:
 * each target is installed first, then a skill imports one markdown file
 * from each target unit kind.
 */
public class CrossKindMarkdownImports {
    static final NodeSpec SPEC = NodeSpec.of("markdown.imports.cross_kind.targets")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("markdown-imports", "skill", "plugin", "doc-repo", "harness")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("markdown.imports.cross_kind.targets",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path root = Path.of(home, "cross-kind-import-fixtures");
            List<ProcessRecord> procs = new ArrayList<>();
            try {
                Files.createDirectories(root);
                Path plugin = MarkdownImportFixture.plugin(root, "mk-target-plugin", "docs/reference.md");
                Path doc = MarkdownImportFixture.doc(root, "mk-target-doc", "claude-md/reference.md");
                Path harness = MarkdownImportFixture.harness(root, "mk-target-harness", "reference.md");
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        plugin, "install-plugin"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        doc, "install-doc"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        harness, "install-harness"));

                Path source = MarkdownImportFixture.skill(root, "mk-source-skill",
                        MarkdownImportFixture.imports(
                                MarkdownImportFixture.entry("mk-target-plugin", "docs/reference.md",
                                        "Smoke validates imports can target plugins."),
                                MarkdownImportFixture.entry("mk-target-doc", "claude-md/reference.md",
                                        "Smoke validates imports can target doc-repos."),
                                MarkdownImportFixture.entry("mk-target-harness", "reference.md",
                                        "Smoke validates imports can target harnesses.")));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        source, "install-source"));
            } catch (Exception e) {
                return NodeResult.error("markdown.imports.cross_kind.targets", e);
            }

            boolean allExitZero = procs.stream().allMatch(p -> p.exitCode() == 0);
            ProcessRecord sourceProc = procs.get(procs.size() - 1);
            String sourceLog = MarkdownImportFixture.logBody(ctx, sourceProc);
            boolean noViolations = !sourceLog.contains("markdown skill-import violations");
            boolean pass = allExitZero && noViolations;
            NodeResult result = pass
                    ? NodeResult.pass("markdown.imports.cross_kind.targets")
                    : NodeResult.fail("markdown.imports.cross_kind.targets",
                            "allExitZero=" + allExitZero + " noViolations=" + noViolations);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    .assertion("all_installs_exit_zero", allExitZero)
                    .assertion("source_imports_validate_cleanly", noViolations);
        });
    }

}
