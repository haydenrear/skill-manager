///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * After {@link DocUnbindLastSectionAndDirGone} removed every binding
 * (tracked copies, managed section, owned dirs), bind ONE source
 * again and verify the binder recreates everything from scratch:
 *
 * <ul>
 *   <li>{@code <project>/docs/agents/} dir gets re-created.</li>
 *   <li>The tracked copy lands.</li>
 *   <li>{@code CLAUDE.md} is re-created with a fresh managed section
 *       carrying the import line.</li>
 *   <li>Per-unit ledger file is re-written with one binding.</li>
 * </ul>
 *
 * <p>This is the regression check the user asked for: re-binding
 * after a complete tear-down should produce a clean working state,
 * not depend on any leftover scaffolding.
 */
public class DocRebindAfterAllRemoved {
    static final NodeSpec SPEC = NodeSpec.of("doc.rebind.after.all.removed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.unbind.last.section.and.dir.gone")
            .tags("bind", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bind.two.sources", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.rebind.after.all.removed", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Pre-condition: the prior node verified docs/agents/ + CLAUDE.md
            // are gone. Pin the same invariant here so a regression in the
            // dir-pruning logic surfaces as a clear pre-condition failure
            // rather than getting masked by the rebind itself.
            Path docsAgentsDir = Path.of(project, "docs/agents");
            Path claudeMd = Path.of(project, "CLAUDE.md");
            if (Files.exists(docsAgentsDir) || Files.exists(claudeMd)) {
                return NodeResult.fail("doc.rebind.after.all.removed",
                        "expected clean project state — agentsDir=" + Files.exists(docsAgentsDir)
                                + " claudeMd=" + Files.exists(claudeMd));
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "bind", "doc:hello-doc-repo/review-stance",
                    "--to", project);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "rebind", pb);
            int rc = proc.exitCode();

            Path tracked = docsAgentsDir.resolve("review-stance.md");
            boolean dirRecreated = Files.isDirectory(docsAgentsDir);
            boolean trackedRecreated = Files.isRegularFile(tracked);
            boolean claudeRecreated = Files.isRegularFile(claudeMd);
            String claudeContent = claudeRecreated ? Files.readString(claudeMd) : "";
            boolean importPresent = claudeContent.contains("@docs/agents/review-stance.md");
            boolean managedSectionPresent = claudeContent.contains("<!-- skill-manager:imports start -->")
                    && claudeContent.contains("<!-- skill-manager:imports end -->");

            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            boolean ledgerRecreated = Files.isRegularFile(ledger);

            boolean pass = rc == 0 && dirRecreated && trackedRecreated
                    && claudeRecreated && importPresent && managedSectionPresent
                    && ledgerRecreated;
            NodeResult result = pass
                    ? NodeResult.pass("doc.rebind.after.all.removed")
                    : NodeResult.fail("doc.rebind.after.all.removed",
                            "rc=" + rc + " dirRecreated=" + dirRecreated
                                    + " trackedRecreated=" + trackedRecreated
                                    + " claudeRecreated=" + claudeRecreated
                                    + " importPresent=" + importPresent
                                    + " managedSection=" + managedSectionPresent
                                    + " ledgerRecreated=" + ledgerRecreated);
            return result
                    .process(proc)
                    .assertion("rebind_ok", rc == 0)
                    .assertion("docs_agents_dir_recreated", dirRecreated)
                    .assertion("tracked_copy_recreated", trackedRecreated)
                    .assertion("claude_md_recreated", claudeRecreated)
                    .assertion("import_line_recreated", importPresent)
                    .assertion("managed_section_recreated", managedSectionPresent)
                    .assertion("ledger_file_recreated", ledgerRecreated)
                    .metric("exitCode", rc);
        });
    }
}
