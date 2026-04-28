///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * Creates three campaigns for the sponsored-graph assertions:
 *
 *   <li>reviewer @ 500¢ on "code-review" + "review"
 *   <li>formatter @ 300¢ on "format" + "style"
 *   <li>formatter @ 1000¢ on "review" — to prove higher bid wins the slot
 *       when two campaigns compete for the same keyword
 */
public class CampaignsCreated {
    static final NodeSpec SPEC = NodeSpec.of("campaigns.created")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("reviewer.published", "formatter.published")
            .tags("ads")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("campaigns.created", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessRecord reviewer = run(ctx, "ads-reviewer", sm, home, repoRoot, List.of(
                    "ads", "create",
                    "--sponsor", "Acme Reviews Inc.",
                    "--skill", "reviewer-skill",
                    "--keyword", "code-review,review",
                    "--category", "review",
                    "--bid-cents", "500",
                    "--registry", registryUrl));

            ProcessRecord formatter = run(ctx, "ads-formatter", sm, home, repoRoot, List.of(
                    "ads", "create",
                    "--sponsor", "Acme Style Co.",
                    "--skill", "formatter-skill",
                    "--keyword", "format,style",
                    "--category", "style",
                    "--bid-cents", "300",
                    "--registry", registryUrl));

            // A second campaign on "review" — higher bid, promotes formatter.
            ProcessRecord highBid = run(ctx, "ads-highbid", sm, home, repoRoot, List.of(
                    "ads", "create",
                    "--sponsor", "Acme Style Co.",
                    "--skill", "formatter-skill",
                    "--keyword", "review",
                    "--bid-cents", "1000",
                    "--notes", "bid-competition test",
                    "--registry", registryUrl));

            int rcReviewer = reviewer.exitCode();
            int rcFormatter = formatter.exitCode();
            int rcHighBid = highBid.exitCode();
            boolean ok = rcReviewer == 0 && rcFormatter == 0 && rcHighBid == 0;
            NodeResult result = ok
                    ? NodeResult.pass("campaigns.created")
                    : NodeResult.fail("campaigns.created",
                            "rcReviewer=" + rcReviewer + " rcFormatter=" + rcFormatter
                                    + " rcHighBid=" + rcHighBid);
            return result
                    .process(reviewer)
                    .process(formatter)
                    .process(highBid)
                    .assertion("reviewer_campaign_created", rcReviewer == 0)
                    .assertion("formatter_campaign_created", rcFormatter == 0)
                    .assertion("high_bid_campaign_created", rcHighBid == 0);
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, Path sm, String home,
                                     Path repoRoot, List<String> subArgs) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(sm.toString());
        cmd.addAll(subArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }
}
