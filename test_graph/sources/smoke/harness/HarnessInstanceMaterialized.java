///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Instantiate the installed harness template and verify the sandbox
 * directory carries the right per-kind projections:
 *
 * <ul>
 *   <li>{@code <sandbox>/skills/pip-cli-skill} — SYMLINK to the
 *       store-side skill dir.</li>
 *   <li>{@code <sandbox>/plugins/hello-plugin} — SYMLINK to the
 *       store-side plugin dir.</li>
 *   <li>{@code <sandbox>/docs/agents/<file>.md} — MANAGED_COPY of
 *       each doc-repo source.</li>
 *   <li>{@code <sandbox>/CLAUDE.md} — managed section with one
 *       {@code @docs/agents/<file>.md} line per source bound to
 *       Claude.</li>
 *   <li>{@code <sandbox>/AGENTS.md} — managed section per Codex
 *       source.</li>
 * </ul>
 *
 * <p>Ledger checks: {@link dev.skillmanager.bindings.BindingStore}
 * holds one binding per (unit, instanceId) pair, all with
 * {@code source=HARNESS} and the stable
 * {@code harness:<instanceId>:...} id prefix.
 */
public class HarnessInstanceMaterialized {
    static final NodeSpec SPEC = NodeSpec.of("harness.instance.materialized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.transitive.installed")
            .tags("instantiate", "harness", "ticket-47")
            .timeout("60s")
            .output("instanceId", "string")
            .output("instanceDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("harness.instance.materialized", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            String instanceId = "smoke-instance";
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "harness", "instantiate", "smoke-harness",
                    "--id", instanceId);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "instantiate", pb);
            int rc = proc.exitCode();

            Path sandbox = Path.of(home, "harnesses", "instances", instanceId);
            Path skillLink = sandbox.resolve("skills/pip-cli-skill");
            Path pluginLink = sandbox.resolve("plugins/hello-plugin");
            Path reviewDoc = sandbox.resolve("docs/agents/review-stance.md");
            Path buildDoc = sandbox.resolve("docs/agents/build-instructions.md");
            Path claudeMd = sandbox.resolve("CLAUDE.md");
            Path agentsMd = sandbox.resolve("AGENTS.md");

            boolean skillSym = Files.isSymbolicLink(skillLink)
                    && Files.exists(skillLink, LinkOption.NOFOLLOW_LINKS);
            boolean pluginSym = Files.isSymbolicLink(pluginLink)
                    && Files.exists(pluginLink, LinkOption.NOFOLLOW_LINKS);
            boolean reviewTracked = Files.isRegularFile(reviewDoc);
            boolean buildTracked = Files.isRegularFile(buildDoc);

            String claudeContent = Files.isRegularFile(claudeMd) ? Files.readString(claudeMd) : "";
            String agentsContent = Files.isRegularFile(agentsMd) ? Files.readString(agentsMd) : "";
            boolean claudeHasReview = claudeContent.contains("@docs/agents/review-stance.md");
            boolean claudeHasBuild = claudeContent.contains("@docs/agents/build-instructions.md");
            boolean agentsHasReview = agentsContent.contains("@docs/agents/review-stance.md");

            // Ledger sanity: bindings keyed by harness:<instanceId>: prefix.
            Path docLedger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String docLedgerJson = Files.isRegularFile(docLedger) ? Files.readString(docLedger) : "";
            boolean harnessBindingIds = docLedgerJson.contains(
                    "\"harness:" + instanceId + ":hello-doc-repo:");
            boolean sourceIsHarness = docLedgerJson.contains("\"HARNESS\"");

            boolean pass = rc == 0
                    && skillSym && pluginSym
                    && reviewTracked && buildTracked
                    && claudeHasReview && claudeHasBuild
                    && agentsHasReview
                    && harnessBindingIds && sourceIsHarness;
            NodeResult result = pass
                    ? NodeResult.pass("harness.instance.materialized")
                    : NodeResult.fail("harness.instance.materialized",
                            "rc=" + rc + " skillSym=" + skillSym + " pluginSym=" + pluginSym
                                    + " reviewTracked=" + reviewTracked
                                    + " buildTracked=" + buildTracked
                                    + " claudeHasReview=" + claudeHasReview
                                    + " claudeHasBuild=" + claudeHasBuild
                                    + " agentsHasReview=" + agentsHasReview
                                    + " harnessBindingIds=" + harnessBindingIds
                                    + " sourceIsHarness=" + sourceIsHarness);
            return result
                    .process(proc)
                    .assertion("instantiate_ok", rc == 0)
                    .assertion("skill_symlinked_into_sandbox", skillSym)
                    .assertion("plugin_symlinked_into_sandbox", pluginSym)
                    .assertion("doc_review_stance_tracked_copy", reviewTracked)
                    .assertion("doc_build_instructions_tracked_copy", buildTracked)
                    .assertion("claude_md_imports_both_docs", claudeHasReview && claudeHasBuild)
                    .assertion("agents_md_imports_codex_doc", agentsHasReview)
                    .assertion("ledger_binding_ids_are_harness_scoped", harnessBindingIds)
                    .assertion("ledger_source_is_HARNESS", sourceIsHarness)
                    .metric("exitCode", rc)
                    .publish("instanceId", instanceId)
                    .publish("instanceDir", sandbox.toString());
        });
    }
}
