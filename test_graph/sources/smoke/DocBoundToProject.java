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
 * Bind {@code doc:hello-doc-repo/review-stance} to a fresh project
 * directory; verify the two-projection contract from ticket-48:
 * a tracked-copy at {@code <project>/docs/agents/review-stance.md}
 * AND an {@code @docs/agents/review-stance.md} import line inside
 * the managed {@code # skill-manager-imports} section of
 * {@code <project>/CLAUDE.md} (and {@code AGENTS.md} when the
 * source declares Codex — review-stance defaults to both).
 *
 * <p>Also: the binding ledger at
 * {@code <home>/installed/hello-doc-repo.projections.json} must
 * carry the new row with {@code source=EXPLICIT} and a
 * {@code MANAGED_COPY} projection holding a non-null
 * {@code boundHash}.
 */
public class DocBoundToProject {
    static final NodeSpec SPEC = NodeSpec.of("doc.bound.to.project")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("doc.repo.installed")
            .tags("bind", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s")
            .output("projectRoot", "string")
            .output("trackedCopyPath", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("doc.bound.to.project", "missing env.prepared.home");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path project;
            try {
                project = Files.createTempDirectory(Path.of(home), "project-");
            } catch (Exception e) {
                return NodeResult.fail("doc.bound.to.project", "could not mkdir project: " + e.getMessage());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "bind", "doc:hello-doc-repo/review-stance",
                    "--to", project.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "bind", pb);
            int rc = proc.exitCode();

            Path tracked = project.resolve("docs/agents/review-stance.md");
            Path claudeMd = project.resolve("CLAUDE.md");
            Path agentsMd = project.resolve("AGENTS.md");
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");

            boolean trackedPresent = Files.isRegularFile(tracked);
            boolean claudePresent = Files.isRegularFile(claudeMd);
            boolean agentsPresent = Files.isRegularFile(agentsMd);
            boolean ledgerPresent = Files.isRegularFile(ledger);

            // Content sanity: the tracked file should match the upstream
            // bytes shipped in examples/hello-doc-repo/claude-md/review-stance.md.
            String trackedContent = trackedPresent
                    ? java.nio.file.Files.readString(tracked) : "";
            boolean trackedHasStanceContent = trackedContent.contains("Review stance");

            // Import line + managed markers in CLAUDE.md.
            String claudeContent = claudePresent ? java.nio.file.Files.readString(claudeMd) : "";
            boolean importLine = claudeContent.contains("@docs/agents/review-stance.md");
            boolean managedSection = claudeContent.contains("<!-- skill-manager:imports start -->")
                    && claudeContent.contains("<!-- skill-manager:imports end -->")
                    && claudeContent.contains("# skill-manager-imports");

            // Ledger sanity: contains source=EXPLICIT + MANAGED_COPY +
            // boundHash. JSON-shallow check is enough; the round-trip
            // is tested at Layer-2 in DocRepoTest.
            String ledgerContent = ledgerPresent ? Files.readString(ledger) : "";
            boolean ledgerHasExplicit = ledgerContent.contains("\"EXPLICIT\"");
            boolean ledgerHasManagedCopy = ledgerContent.contains("\"MANAGED_COPY\"");
            boolean ledgerHasBoundHash = ledgerContent.contains("\"boundHash\"");

            boolean pass = rc == 0 && trackedPresent && trackedHasStanceContent
                    && claudePresent && agentsPresent && ledgerPresent
                    && importLine && managedSection
                    && ledgerHasExplicit && ledgerHasManagedCopy && ledgerHasBoundHash;
            NodeResult result = pass
                    ? NodeResult.pass("doc.bound.to.project")
                    : NodeResult.fail("doc.bound.to.project",
                            "rc=" + rc + " tracked=" + trackedPresent
                                    + " trackedContent=" + trackedHasStanceContent
                                    + " claude=" + claudePresent + " agents=" + agentsPresent
                                    + " importLine=" + importLine + " managed=" + managedSection
                                    + " ledger=" + ledgerPresent
                                    + " ledgerExplicit=" + ledgerHasExplicit
                                    + " ledgerManagedCopy=" + ledgerHasManagedCopy
                                    + " ledgerBoundHash=" + ledgerHasBoundHash);
            return result
                    .process(proc)
                    .assertion("bind_ok", rc == 0)
                    .assertion("tracked_copy_present", trackedPresent)
                    .assertion("tracked_copy_content_matches", trackedHasStanceContent)
                    .assertion("claude_md_created", claudePresent)
                    .assertion("agents_md_created", agentsPresent)
                    .assertion("import_line_in_claude_md", importLine)
                    .assertion("managed_section_markers", managedSection)
                    .assertion("ledger_row_written", ledgerPresent)
                    .assertion("ledger_source_explicit", ledgerHasExplicit)
                    .assertion("ledger_kind_managed_copy", ledgerHasManagedCopy)
                    .assertion("ledger_carries_bound_hash", ledgerHasBoundHash)
                    .metric("exitCode", rc)
                    .publish("projectRoot", project.toString())
                    .publish("trackedCopyPath", tracked.toString());
        });
    }
}
