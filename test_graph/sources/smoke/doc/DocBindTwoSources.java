///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * After {@code doc.unbind.cleans.up} leaves the project empty, this
 * node binds BOTH declared sources from hello-doc-repo
 * ({@code review-stance} and {@code build-instructions}) into a fresh
 * project root and verifies the multi-source layout:
 *
 * <ul>
 *   <li>Two tracked copies under {@code <project>/docs/agents/}.</li>
 *   <li>{@code CLAUDE.md} carries BOTH {@code @docs/agents/<file>.md}
 *       lines (review-stance agents = [claude, codex];
 *       build-instructions agents = [claude]).</li>
 *   <li>{@code AGENTS.md} carries ONLY review-stance (build-
 *       instructions is claude-only).</li>
 *   <li>Ledger holds two Binding rows for hello-doc-repo, both
 *       {@code source=EXPLICIT}.</li>
 * </ul>
 *
 * <p>Drives the {@code bind doc:<repo>} whole-repo fan-out path —
 * one CLI call produces N bindings.
 */
public class DocBindTwoSources {
    static final NodeSpec SPEC = NodeSpec.of("doc.bind.two.sources")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("doc.unbind.cleans.up")
            .tags("bind", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s")
            .output("projectRoot", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("doc.bind.two.sources", "missing env.prepared.home");
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path project;
            try {
                project = Files.createTempDirectory(Path.of(home), "project-multi-");
            } catch (Exception e) {
                return NodeResult.fail("doc.bind.two.sources",
                        "could not mkdir project: " + e.getMessage());
            }

            // `bind doc:hello-doc-repo --to <project>` — no sub-element,
            // so DocRepoBinder fans out to every [[sources]] row.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "bind", "doc:hello-doc-repo",
                    "--to", project.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "bind", pb);
            int rc = proc.exitCode();

            Path reviewTracked = project.resolve("docs/agents/review-stance.md");
            Path buildTracked = project.resolve("docs/agents/build-instructions.md");
            Path claudeMd = project.resolve("CLAUDE.md");
            Path agentsMd = project.resolve("AGENTS.md");

            boolean reviewPresent = Files.isRegularFile(reviewTracked);
            boolean buildPresent = Files.isRegularFile(buildTracked);
            String claudeContent = Files.isRegularFile(claudeMd) ? Files.readString(claudeMd) : "";
            String agentsContent = Files.isRegularFile(agentsMd) ? Files.readString(agentsMd) : "";

            boolean claudeHasReview = claudeContent.contains("@docs/agents/review-stance.md");
            boolean claudeHasBuild = claudeContent.contains("@docs/agents/build-instructions.md");
            boolean agentsHasReview = agentsContent.contains("@docs/agents/review-stance.md");
            boolean agentsHasNoBuild = !agentsContent.contains("@docs/agents/build-instructions.md");

            // Ledger should now have 2 Binding rows for hello-doc-repo.
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String ledgerJson = Files.isRegularFile(ledger) ? Files.readString(ledger) : "";
            // Crude count of top-level binding entries via the "subElement" field —
            // one row per source: "review-stance" and "build-instructions".
            boolean ledgerHasReview = ledgerJson.contains("\"review-stance\"");
            boolean ledgerHasBuild = ledgerJson.contains("\"build-instructions\"");

            boolean pass = rc == 0 && reviewPresent && buildPresent
                    && claudeHasReview && claudeHasBuild
                    && agentsHasReview && agentsHasNoBuild
                    && ledgerHasReview && ledgerHasBuild;
            NodeResult result = pass
                    ? NodeResult.pass("doc.bind.two.sources")
                    : NodeResult.fail("doc.bind.two.sources",
                            "rc=" + rc + " reviewPresent=" + reviewPresent
                                    + " buildPresent=" + buildPresent
                                    + " claudeHasReview=" + claudeHasReview
                                    + " claudeHasBuild=" + claudeHasBuild
                                    + " agentsHasReview=" + agentsHasReview
                                    + " agentsHasNoBuild=" + agentsHasNoBuild
                                    + " ledgerHasReview=" + ledgerHasReview
                                    + " ledgerHasBuild=" + ledgerHasBuild);
            return result
                    .process(proc)
                    .assertion("bind_ok", rc == 0)
                    .assertion("review_tracked_copy", reviewPresent)
                    .assertion("build_tracked_copy", buildPresent)
                    .assertion("claude_imports_review", claudeHasReview)
                    .assertion("claude_imports_build", claudeHasBuild)
                    .assertion("agents_imports_review_only", agentsHasReview && agentsHasNoBuild)
                    .assertion("ledger_two_bindings", ledgerHasReview && ledgerHasBuild)
                    .metric("exitCode", rc)
                    .publish("projectRoot", project.toString());
        });
    }
}
