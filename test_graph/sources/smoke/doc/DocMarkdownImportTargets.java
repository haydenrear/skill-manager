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
 * parsed and the violation is reported.
 *
 * <h2>The exit code is part of the report</h2>
 *
 * <p>This node used to assert every install exited 0, including the one that
 * printed a violation. That is the defect
 * {@code MarkdownImportValidator.EXIT_CODE} exists to close: the violation
 * block lands after the success banner and after {@code ACTION_REQUIRED}, so
 * a caller reading the tail or {@code $?} concluded success — a printed
 * violation that does not reach an exit code is a comment, not a check. The
 * assertion now pins the contract that replaced it: an install whose markdown
 * references resolve exits 0, and an install that reports a violation exits
 * {@code 11}, with the unit still committed.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>The three target installs in this same node, in this same home, through
 * this same binary, must exit 0. Without them "exits 11" would also hold for
 * a build in which every install failed for an unrelated reason, and the
 * absence assertion below ({@code noPathFailures}) would hold over an empty
 * log. The three zeros and the rendered violation text are what make the 11
 * evidence of anything.
 */
public class DocMarkdownImportTargets {
    static final NodeSpec SPEC = NodeSpec.of("doc.markdown.import.targets")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("doc-repo", "markdown-imports", "skill", "harness")
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
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        skill, "install-skill"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        harness, "install-harness"));
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
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
                procs.add(MarkdownImportFixture.install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        source, "install-source-doc"));
            } catch (Exception e) {
                return NodeResult.error("doc.markdown.import.targets", e);
            }

            ProcessRecord sourceProc = procs.get(procs.size() - 1);
            List<ProcessRecord> targets = procs.subList(0, procs.size() - 1);
            // The three units whose markdown has nothing wrong with it. This is
            // the discriminator for the 11 below.
            boolean targetsExitZero = targets.stream().allMatch(p -> p.exitCode() == 0);
            boolean sourceExitsViolationCode =
                    sourceProc.exitCode() == MARKDOWN_IMPORT_VIOLATION_EXIT;
            String log = MarkdownImportFixture.logBody(ctx, sourceProc);
            boolean renderedMissingDoc = log.contains("markdown skill-import violations (1)")
                    && log.contains("dm-source-doc (doc)")
                    && log.contains("references missing unit `dm-missing-doc`");
            // The unit is still committed — 11 reports the reference, it does
            // not abandon the install.
            boolean sourceCommitted = Files.isDirectory(Path.of(home, "docs", "dm-source-doc"));
            boolean noPathFailures = !log.contains("references missing path");
            boolean pass = targetsExitZero && sourceExitsViolationCode && renderedMissingDoc
                    && sourceCommitted && noPathFailures;
            NodeResult result = pass
                    ? NodeResult.pass("doc.markdown.import.targets")
                    : NodeResult.fail("doc.markdown.import.targets",
                            "targetsExitZero=" + targetsExitZero
                                    + " sourceRc=" + sourceProc.exitCode()
                                    + " renderedMissingDoc=" + renderedMissingDoc
                                    + " sourceCommitted=" + sourceCommitted
                                    + " noPathFailures=" + noPathFailures);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    .assertion("resolved_import_installs_exit_zero", targetsExitZero)
                    .assertion("unresolved_import_install_exits_eleven", sourceExitsViolationCode)
                    .assertion("missing_doc_violation_rendered", renderedMissingDoc)
                    .assertion("unit_still_committed_under_violation_exit", sourceCommitted)
                    .assertion("installed_skill_harness_doc_imports_resolved", noPathFailures)
                    .metric("sourceExitCode", sourceProc.exitCode());
        });
    }
}
