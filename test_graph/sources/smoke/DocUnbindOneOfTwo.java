///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unbind ONLY the {@code review-stance} binding; verify the
 * {@code build-instructions} binding survives intact.
 *
 * <p>Expectations after the partial unbind:
 * <ul>
 *   <li>{@code <project>/docs/agents/review-stance.md} removed.</li>
 *   <li>{@code <project>/docs/agents/build-instructions.md} still
 *       present (unchanged).</li>
 *   <li>{@code CLAUDE.md} has the {@code @build-instructions.md} line
 *       but NOT {@code @review-stance.md}.</li>
 *   <li>{@code AGENTS.md} had only review-stance imported, so the
 *       managed section was the entire file content → unbind dropped
 *       the file outright (ManagedImports leaves no empty stub).</li>
 *   <li>Ledger now holds exactly one Binding row
 *       (build-instructions).</li>
 * </ul>
 *
 * <p>The {@code docs/agents/} dir is still present because
 * build-instructions.md still lives there — pruning only fires when
 * the last MANAGED_COPY leaves.
 */
public class DocUnbindOneOfTwo {
    static final NodeSpec SPEC = NodeSpec.of("doc.unbind.one.of.two")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.bind.two.sources")
            .tags("unbind", "doc-repo", "ticket-48")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bind.two.sources", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.unbind.one.of.two", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Find the binding for sub-element "review-stance" in the
            // ledger; capture its id. The JSON shape is
            // bindings[N].subElement = "review-stance" with
            // bindings[N].bindingId = "<ULID>".
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String ledgerJson;
            try {
                ledgerJson = Files.readString(ledger);
            } catch (Exception e) {
                return NodeResult.fail("doc.unbind.one.of.two", "cannot read ledger: " + e.getMessage());
            }
            // Walk the ledger as JSON-ish text: locate the binding whose
            // subElement is "review-stance" and capture its bindingId.
            // The Jackson default order is insertion: bindingId, ..., subElement.
            // So we look back from the subElement match to the most-recent bindingId.
            String reviewBindingId = findBindingIdBySubElement(ledgerJson, "review-stance");
            if (reviewBindingId == null) {
                return NodeResult.fail("doc.unbind.one.of.two",
                        "could not locate review-stance binding id in ledger");
            }

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "unbind", reviewBindingId);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "unbind-review", pb);
            int rc = proc.exitCode();

            Path reviewTracked = Path.of(project, "docs/agents/review-stance.md");
            Path buildTracked = Path.of(project, "docs/agents/build-instructions.md");
            Path claudeMd = Path.of(project, "CLAUDE.md");
            Path agentsMd = Path.of(project, "AGENTS.md");

            boolean reviewGone = !Files.exists(reviewTracked);
            boolean buildSurvives = Files.isRegularFile(buildTracked);

            String claudeContent = Files.isRegularFile(claudeMd) ? Files.readString(claudeMd) : "";
            boolean claudeHasBuild = claudeContent.contains("@docs/agents/build-instructions.md");
            boolean claudeNoReview = !claudeContent.contains("@docs/agents/review-stance.md");

            // AGENTS.md only had the review-stance import; after unbind
            // the managed section is empty → ManagedImports drops the
            // whole section → reverseProjection deletes the file
            // because there's no user content left.
            boolean agentsRemoved = !Files.exists(agentsMd);

            // Ledger now has exactly the build-instructions binding.
            String ledgerAfter = Files.isRegularFile(ledger) ? Files.readString(ledger) : "";
            boolean ledgerHasBuild = ledgerAfter.contains("\"build-instructions\"");
            boolean ledgerNoReview = !ledgerAfter.contains("\"review-stance\"");

            boolean pass = rc == 0 && reviewGone && buildSurvives
                    && claudeHasBuild && claudeNoReview
                    && agentsRemoved
                    && ledgerHasBuild && ledgerNoReview;
            NodeResult result = pass
                    ? NodeResult.pass("doc.unbind.one.of.two")
                    : NodeResult.fail("doc.unbind.one.of.two",
                            "rc=" + rc + " reviewGone=" + reviewGone
                                    + " buildSurvives=" + buildSurvives
                                    + " claudeHasBuild=" + claudeHasBuild
                                    + " claudeNoReview=" + claudeNoReview
                                    + " agentsRemoved=" + agentsRemoved
                                    + " ledgerHasBuild=" + ledgerHasBuild
                                    + " ledgerNoReview=" + ledgerNoReview);
            return result
                    .process(proc)
                    .assertion("unbind_ok", rc == 0)
                    .assertion("review_tracked_removed", reviewGone)
                    .assertion("build_tracked_survives", buildSurvives)
                    .assertion("claude_keeps_build_import", claudeHasBuild)
                    .assertion("claude_drops_review_import", claudeNoReview)
                    .assertion("agents_md_removed_when_empty", agentsRemoved)
                    .assertion("ledger_only_build_remains", ledgerHasBuild && ledgerNoReview)
                    .metric("exitCode", rc);
        });
    }

    /**
     * Walk the ledger JSON text for the first {@code bindingId} that
     * sits in the same Binding object as {@code subElement = "<id>"}.
     * Crude but reliable given Jackson's stable field order:
     * {@code bindingId} appears before {@code subElement} inside the
     * binding object. We find the subElement marker, then walk back
     * to the most recent bindingId pattern.
     */
    private static String findBindingIdBySubElement(String json, String subElementId) {
        int idx = json.indexOf("\"subElement\" : \"" + subElementId + "\"");
        if (idx < 0) idx = json.indexOf("\"subElement\":\"" + subElementId + "\"");
        if (idx < 0) return null;
        Pattern p = Pattern.compile("\"bindingId\"\\s*:\\s*\"([0-9A-HJKMNP-TV-Z]{26})\"");
        Matcher m = p.matcher(json.substring(0, idx));
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }
}
