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
 * Doc-repo markdown sources can import files from skills, harnesses,
 * and other doc-repos. The source doc-repo intentionally also imports
 * a missing doc-repo so the graph proves doc markdown sources are
 * parsed and advisory violations are rendered.
 */
public class DocMarkdownImportTargets {
    static final NodeSpec SPEC = NodeSpec.of("doc.markdown.import.targets")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("doc-repo", "markdown-imports", "skill", "harness")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("doc.markdown.import.targets",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path root = Path.of(home, "doc-markdown-import-fixtures");
            List<ProcessRecord> procs = new ArrayList<>();
            try {
                Files.createDirectories(root);
                Path skill = MarkdownImportFixture.skill(root, "dm-target-skill", "skill-imports: []\n");
                Path harness = MarkdownImportFixture.harness(root, "dm-target-harness", "reference.md");
                Path doc = MarkdownImportFixture.doc(root, "dm-target-doc", "claude-md/reference.md");
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        skill, "install-skill"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        harness, "install-harness"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        doc, "install-target-doc"));

                Path source = MarkdownImportFixture.docWithSourceImports(root, "dm-source-doc",
                        MarkdownImportFixture.imports(
                                MarkdownImportFixture.entry("dm-target-skill", "SKILL.md",
                                        "Doc smoke validates imports can target skills."),
                                MarkdownImportFixture.entry("dm-target-harness", "reference.md",
                                        "Doc smoke validates imports can target harnesses."),
                                MarkdownImportFixture.entry("dm-target-doc", "claude-md/reference.md",
                                        "Doc smoke validates imports can target other doc-repos."),
                                MarkdownImportFixture.entry("dm-missing-doc", "claude-md/reference.md",
                                        "Doc smoke keeps one missing doc import as a parsing sentinel.")));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        source, "install-source-doc"));
            } catch (Exception e) {
                return NodeResult.error("doc.markdown.import.targets", e);
            }

            boolean allExitZero = procs.stream().allMatch(p -> p.exitCode() == 0);
            ProcessRecord sourceProc = procs.get(procs.size() - 1);
            String log = MarkdownImportFixture.logBody(ctx, sourceProc);
            boolean renderedMissingDoc = log.contains("markdown skill-import violations (1)")
                    && log.contains("dm-source-doc (doc)")
                    && log.contains("references missing unit `dm-missing-doc`");
            boolean noPathFailures = !log.contains("references missing path");
            boolean pass = allExitZero && renderedMissingDoc && noPathFailures;
            NodeResult result = pass
                    ? NodeResult.pass("doc.markdown.import.targets")
                    : NodeResult.fail("doc.markdown.import.targets",
                            "allExitZero=" + allExitZero
                                    + " renderedMissingDoc=" + renderedMissingDoc
                                    + " noPathFailures=" + noPathFailures);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    .assertion("all_installs_exit_zero", allExitZero)
                    .assertion("missing_doc_violation_rendered", renderedMissingDoc)
                    .assertion("installed_skill_harness_doc_imports_resolved", noPathFailures);
        });
    }
}
